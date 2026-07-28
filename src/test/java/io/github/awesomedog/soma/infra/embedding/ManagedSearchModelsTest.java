package io.github.awesomedog.soma.infra.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.awesomedog.soma.infra.runtime.LlamaRuntime;
import io.github.awesomedog.soma.infra.runtime.ManagedRuntimeHttp;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ManagedSearchModelsTest {

  private HttpServer server;
  private ManagedSearchModels searchModels;
  private URI endpoint;
  private final ObjectMapper json = ObjectMapper.getDefault();
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void startServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/tokenize", exchange -> respond(exchange, "{\"tokens\":[1,2,3]}"));
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          var request =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          var count = request.contains("[\"one\",\"two\"]") ? 2 : 1;
          var body = new StringBuilder("{\"data\":[");
          for (var position = 0; position < count; position++) {
            if (position > 0) body.append(',');
            var index = count == 2 ? 1 - position : 0;
            body.append("{\"index\":")
                .append(index)
                .append(",\"embedding\":")
                .append(vector(index + 1))
                .append('}');
          }
          respond(exchange, body.append("]}").toString());
        });
    server.createContext(
        "/v1/chat/completions",
        exchange ->
            respond(
                exchange,
                "{\"choices\":[{\"message\":{\"content\":\"lex: alpha\\nvec: beta\\nhyde: gamma\"}}]}"));
    server.createContext(
        "/reranking",
        exchange ->
            respond(
                exchange,
                "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.4}]}"));
    server.start();
    endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
    searchModels = new ManagedSearchModels(this::post, json, ignored -> "");
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  @Test
  void implementsTokenizerEmbeddingExpansionAndRerankingProtocols() {
    assertThat(searchModels.embeddingMetadata().dimensions()).isEqualTo(768);
    assertThat(searchModels.countTokens("hello")).isEqualTo(3);
    assertThat(searchModels.embed("one")).hasSize(768).startsWith(1.0f);
    assertThat(searchModels.embedBatch(java.util.List.of("one", "two")))
        .hasSize(2)
        .satisfies(
            vectors -> {
              assertThat(vectors.get(0)).hasSize(768).startsWith(1.0f);
              assertThat(vectors.get(1)).hasSize(768).startsWith(2.0f);
            });
    assertThat(searchModels.expand("query"))
        .satisfies(
            expansion -> {
              assertThat(expansion.lexical()).containsExactly("alpha");
              assertThat(expansion.vector()).containsExactly("beta");
              assertThat(expansion.hyde()).containsExactly("gamma");
            });
    assertThat(searchModels.rerank("query", java.util.List.of("one", "two"), 2))
        .extracting(score -> score.candidateIndex())
        .containsExactly(1, 0);
  }

  private static String vector(int first) {
    var out = new StringBuilder("[").append(first);
    for (var index = 1; index < 768; index++) {
      out.append(",0");
    }
    return out.append(']').toString();
  }

  private ManagedRuntimeHttp.Response post(LlamaRuntime.Request request) {
    try {
      return ManagedRuntimeHttp.postJson(
          http, json, endpoint, request.path(), request.payload(), request.timeout());
    } catch (IOException e) {
      throw new AssertionError(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    var bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }
}
