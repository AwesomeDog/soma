package io.github.awesomedog.soma.infra.extraction;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.infra.cache.ProcessingCache;
import io.github.awesomedog.soma.infra.runtime.LlamaRuntime;
import io.github.awesomedog.soma.infra.runtime.ManagedRuntimeHttp;
import io.github.awesomedog.soma.support.Hashing;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedContentExtractorTest {

  @TempDir Path temporaryDirectory;

  @Test
  void extractsPdfWithAStableRecipeAndCleansTheUniqueOutput() throws Exception {
    var source = Files.writeString(temporaryDirectory.resolve("manual.pdf"), "pdf");
    var pdfium = Files.writeString(temporaryDirectory.resolve("pdfium"), "tool");
    var output = new AtomicReference<Path>();
    var calls = new AtomicInteger();
    var extractor =
        extractor(
            id -> pdfium,
            (command, workingDirectory) -> {
              calls.incrementAndGet();
              var target = Path.of(command.get(command.indexOf("-o") + 1));
              output.set(target);
              writeString(target, "Extracted PDF text\n");
              return "";
            });

    var result = extractor.extract(source, FileType.PDF);
    var cachedResult =
        extractor.extract(
            Files.writeString(temporaryDirectory.resolve("manual-copy.pdf"), "pdf"), FileType.PDF);

    assertThat(result.body()).isEqualTo("Extracted PDF text");
    assertThat(cachedResult).isEqualTo(result);
    assertThat(extractor.recipeId(FileType.PDF)).matches("[0-9a-f]{64}");
    assertThat(output.get()).doesNotExist();
    assertThat(calls).hasValue(1);
    assertThat(cachedOperations()).containsExactly("pdf.text");
  }

  @Test
  void missingProcessOutputIsAnOperationFailure() throws Exception {
    var source = Files.writeString(temporaryDirectory.resolve("manual.pdf"), "pdf");
    var pdfium = Files.writeString(temporaryDirectory.resolve("pdfium"), "tool");
    var extractor = extractor(id -> pdfium, (command, directory) -> "");

    assertThatThrownBy(() -> extractor.extract(source, FileType.PDF))
        .isInstanceOfSatisfying(
            AppException.class,
            failure ->
                assertThat(failure.error().code()).isEqualTo(AppError.Code.OPERATION_FAILED));
  }

  @Test
  void imageCombinesVisionAndCleanedOcr() throws Exception {
    var requests = new AtomicInteger();
    var protocolUpgrades = new AtomicInteger();
    var runtimes = new ArrayList<String>();
    var visionRequest = new AtomicReference<String>();
    var ocrRequest = new AtomicReference<String>();
    var ocrCleanupRequest = new AtomicReference<String>();
    var server =
        imageExtractionServer(
            requests, protocolUpgrades, visionRequest, ocrRequest, ocrCleanupRequest);
    try {
      var source =
          Files.write(
              temporaryDirectory.resolve("diagram.jpg"),
              new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
      var extractor = imageExtractor(server, runtimes);

      var result = extractor.extract(source, FileType.IMAGE);
      var cachedResult =
          extractor.extract(
              Files.write(
                  temporaryDirectory.resolve("diagram-copy.png"),
                  new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a}),
              FileType.IMAGE);

      assertThat(result.body()).contains("A diagram with an API gateway");
      assertThat(cachedResult).isEqualTo(result);
      assertThat(extractor.recipeId(FileType.IMAGE)).matches("[0-9a-f]{64}");
      assertThat(visionRequest.get()).contains("data:image/png;base64,");
      assertThat(ocrCleanupRequest.get())
          .contains("\"content\":\"RawOCRtext\"")
          .doesNotContain("/no_think", "chat_template_kwargs", "enable_thinking");
      assertThat(requests).hasValue(3);
      assertThat(protocolUpgrades).hasValue(0);
      assertThat(runtimes).containsExactly("llama", "ocr", "llama");
      assertThat(cachedOperations()).containsExactly("image.describe", "ocr.text");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void preprocessesImagesOverTenMebibytesWithoutChangingSourceIdentity() throws Exception {
    var requests = new AtomicInteger();
    var protocolUpgrades = new AtomicInteger();
    var runtimes = new ArrayList<String>();
    var visionRequest = new AtomicReference<String>();
    var ocrRequest = new AtomicReference<String>();
    var ocrCleanupRequest = new AtomicReference<String>();
    var server =
        imageExtractionServer(
            requests, protocolUpgrades, visionRequest, ocrRequest, ocrCleanupRequest);
    try {
      var original = new byte[10 * 1024 * 1024 + 1];
      var pngSignature = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
      System.arraycopy(pngSignature, 0, original, 0, pngSignature.length);
      var source = Files.write(temporaryDirectory.resolve("large.png"), original);
      var sourceHash = Hashing.sha256Hex(original);
      var ffmpeg = Files.writeString(temporaryDirectory.resolve("ffmpeg"), "tool");
      var preprocessed = new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3};
      var command = new AtomicReference<List<String>>();
      var workingDirectory = new AtomicReference<Path>();
      var output = new AtomicReference<Path>();
      var commandCalls = new AtomicInteger();
      var extractor =
          imageExtractor(
              server,
              runtimes,
              id -> {
                assertThat(id).isEqualTo("ffmpeg");
                return ffmpeg;
              },
              (arguments, directory) -> {
                commandCalls.incrementAndGet();
                command.set(arguments);
                workingDirectory.set(directory);
                output.set(Path.of(arguments.getLast()));
                writeBytes(output.get(), preprocessed);
                return "";
              });

      var result = extractor.extract(source, FileType.IMAGE);
      var cachedResult = extractor.extract(source, FileType.IMAGE);

      assertThat(result.sourceHash()).isEqualTo(sourceHash);
      assertThat(cachedResult).isEqualTo(result);
      assertThat(Files.readAllBytes(source)).isEqualTo(original);
      assertThat(visionRequest.get())
          .contains("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(preprocessed));
      assertThat(Hashing.sha256Hex(requestImageBytes(ocrRequest.get()))).isEqualTo(sourceHash);
      assertThat(command.get())
          .containsExactly(
              ffmpeg.toString(),
              "-y",
              "-v",
              "error",
              "-i",
              source.toString(),
              "-frames:v",
              "1",
              "-vf",
              "scale='min(2048,iw)':'min(2048,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
              "-map_metadata",
              "-1",
              "-q:v",
              "3",
              output.get().toString());
      assertThat(workingDirectory.get()).isEqualTo(source.getParent());
      assertThat(output.get()).doesNotExist();
      assertThat(commandCalls).hasValue(1);
      assertThat(cachedInputHashes())
          .containsEntry("image.describe", sourceHash)
          .containsEntry("ocr.text", sourceHash);
    } finally {
      server.stop(0);
    }
  }

  private ManagedContentExtractor imageExtractor(HttpServer server, List<String> runtimes) {
    return imageExtractor(
        server,
        runtimes,
        id -> {
          throw new AssertionError("image extraction resolves artifacts through runtimes");
        },
        unusedCommands());
  }

  private ManagedContentExtractor imageExtractor(
      HttpServer server,
      List<String> runtimes,
      Function<String, Path> artifacts,
      BiFunction<List<String>, Path, String> commands) {
    var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    var json = ObjectMapper.getDefault();
    var http = HttpClient.newHttpClient();
    return new ManagedContentExtractor(
        artifacts,
        request -> {
          runtimes.add("llama");
          return post(http, json, endpoint, request);
        },
        () -> {
          runtimes.add("ocr");
          return endpoint;
        },
        json,
        http,
        commands,
        cache());
  }

  private static HttpServer imageExtractionServer(
      AtomicInteger requests,
      AtomicInteger protocolUpgrades,
      AtomicReference<String> visionRequest,
      AtomicReference<String> ocrRequest,
      AtomicReference<String> ocrCleanupRequest)
      throws IOException {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    registerVisionEndpoint(server, requests, protocolUpgrades, visionRequest, ocrCleanupRequest);
    registerOcrEndpoint(server, requests, protocolUpgrades, ocrRequest);
    server.start();
    return server;
  }

  private static void registerVisionEndpoint(
      HttpServer server,
      AtomicInteger requests,
      AtomicInteger protocolUpgrades,
      AtomicReference<String> visionRequest,
      AtomicReference<String> ocrCleanupRequest) {
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          requests.incrementAndGet();
          if (exchange.getRequestHeaders().containsKey("Upgrade")) {
            protocolUpgrades.incrementAndGet();
          }
          var request = new String(exchange.getRequestBody().readAllBytes(), UTF_8);
          if (request.contains("\"model\":\"VISION\"")) {
            visionRequest.set(request);
          } else {
            ocrCleanupRequest.set(request);
          }
          var content =
              request.contains("\"model\":\"VISION\"")
                  ? "A diagram with an API gateway."
                  : "Clean OCR text";
          respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}");
        });
  }

  private static void registerOcrEndpoint(
      HttpServer server,
      AtomicInteger requests,
      AtomicInteger protocolUpgrades,
      AtomicReference<String> ocrRequest) {
    server.createContext(
        "/api/ocr",
        exchange -> {
          requests.incrementAndGet();
          if (exchange.getRequestHeaders().containsKey("Upgrade")) {
            protocolUpgrades.incrementAndGet();
          }
          ocrRequest.set(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
          respond(
              exchange,
              200,
              "{\"results\":[{\"box\":[[0,0],[1,0],[1,1],[0,1]],"
                  + "\"txt\":\"RawOCRtext\",\"score\":0.99}],\"elapse\":0.01}");
        });
  }

  @Test
  void transcodesAndTranscribesWithTheManagedModelThenCleansTheWav() throws Exception {
    var paths = new HashMap<String, Path>();
    for (var id : List.of("ffmpeg", "whisper", "whisper-base-model")) {
      paths.put(id, Files.writeString(temporaryDirectory.resolve(id), id));
    }
    var wav = new AtomicReference<Path>();
    var calls = new AtomicInteger();
    var extractor =
        extractor(
            paths::get,
            (command, workingDirectory) -> {
              calls.incrementAndGet();
              if (command.getFirst().equals(paths.get("ffmpeg").toString())) {
                var target = Path.of(command.getLast());
                wav.set(target);
                writeBytes(target, new byte[] {1, 2, 3});
                assertThat(command).containsSubsequence("-ar", "16000", "-ac", "1");
                return "";
              }
              assertThat(command)
                  .containsSubsequence("-m", paths.get("whisper-base-model").toString());
              assertThat(command)
                  .containsSubsequence(
                      "-f",
                      wav.get().toString(),
                      "--language",
                      "auto",
                      "--no-timestamps",
                      "--no-prints");
              return "Actual transcript\n";
            });
    var source = Files.write(temporaryDirectory.resolve("meeting.mp4"), new byte[] {4, 5, 6});

    var result = extractor.extract(source, FileType.VIDEO);
    var cachedResult =
        extractor.extract(
            Files.write(temporaryDirectory.resolve("meeting-copy.mp4"), new byte[] {4, 5, 6}),
            FileType.VIDEO);

    assertThat(result.body()).isEqualTo("Actual transcript");
    assertThat(cachedResult).isEqualTo(result);
    assertThat(extractor.recipeId(FileType.VIDEO)).matches("[0-9a-f]{64}");
    assertThat(calls).hasValue(2);
    assertThat(wav.get()).doesNotExist();
    assertThat(cachedOperations()).containsExactly("media.transcribe");
  }

  private ManagedContentExtractor extractor(
      Function<String, Path> artifacts, BiFunction<List<String>, Path, String> commands) {
    return new ManagedContentExtractor(
        artifacts,
        unusedLlama(),
        unusedOcrEndpoint(),
        ObjectMapper.getDefault(),
        HttpClient.newHttpClient(),
        commands,
        cache());
  }

  private ProcessingCache cache() {
    return new ProcessingCache(temporaryDirectory.resolve("cache.sqlite"));
  }

  private List<String> cachedOperations() throws Exception {
    var operations = new ArrayList<String>();
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("cache.sqlite"));
        var statement =
            connection
                .createStatement()
                .executeQuery("SELECT operation FROM process_cache ORDER BY operation")) {
      while (statement.next()) {
        operations.add(statement.getString(1));
      }
    }
    return List.copyOf(operations);
  }

  private Map<String, String> cachedInputHashes() throws Exception {
    var inputHashes = new LinkedHashMap<String, String>();
    try (var connection =
            DriverManager.getConnection(
                "jdbc:sqlite:" + temporaryDirectory.resolve("cache.sqlite"));
        var statement =
            connection
                .createStatement()
                .executeQuery(
                    "SELECT operation, input_hash FROM process_cache ORDER BY operation")) {
      while (statement.next()) {
        inputHashes.put(statement.getString(1), statement.getString(2));
      }
    }
    return Map.copyOf(inputHashes);
  }

  private static byte[] requestImageBytes(String request) {
    try {
      var root = ObjectMapper.getDefault().readValue(request, JsonNode.class);
      var image = root == null || !root.isObject() ? null : root.get("image");
      if (image == null || !image.isString()) {
        throw new AssertionError("OCR request did not contain an image");
      }
      return Base64.getDecoder().decode(image.getStringValue());
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  private Function<LlamaRuntime.Request, ManagedRuntimeHttp.Response> unusedLlama() {
    return request -> {
      throw new AssertionError("runtime should not be used");
    };
  }

  private Supplier<URI> unusedOcrEndpoint() {
    return () -> {
      throw new AssertionError("runtime should not be used");
    };
  }

  private BiFunction<List<String>, Path, String> unusedCommands() {
    return (command, directory) -> {
      throw new AssertionError("external command should not be used");
    };
  }

  private void writeString(Path path, String value) {
    try {
      Files.writeString(path, value, UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private void writeBytes(Path path, byte[] value) {
    try {
      Files.write(path, value);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    var bytes = body.getBytes(UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private static ManagedRuntimeHttp.Response post(
      HttpClient http, ObjectMapper json, URI endpoint, LlamaRuntime.Request request) {
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
}
