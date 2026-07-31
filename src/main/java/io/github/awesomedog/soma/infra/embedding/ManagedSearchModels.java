package io.github.awesomedog.soma.infra.embedding;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import io.github.awesomedog.soma.infra.runtime.LlamaRuntime;
import io.github.awesomedog.soma.infra.runtime.LlamaRuntime.ModelRole;
import io.github.awesomedog.soma.infra.runtime.ManagedArtifacts;
import io.github.awesomedog.soma.infra.runtime.ManagedRuntimeHttp;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class ManagedSearchModels implements SearchModels {

  private static final Logger LOG = LoggerFactory.getLogger(ManagedSearchModels.class);
  private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(2);
  private static final String EXPANSION_GRAMMAR =
      """
      root ::= line+
      line ::= type ": " content "\\n"
      type ::= "lex" | "vec" | "hyde"
      content ::= [^\\n]+
      """;

  private final Function<LlamaRuntime.Request, ManagedRuntimeHttp.Response> llama;
  private final ObjectMapper objectMapper;
  private final Function<List<String>, String> artifactRecipeId;

  @Inject
  public ManagedSearchModels(
      LlamaRuntime llamaRuntime, ObjectMapper objectMapper, ManagedArtifacts artifacts) {
    this(
        llamaRuntime::post,
        objectMapper,
        ids -> artifacts.artifactRecipeId(ids.toArray(String[]::new)));
  }

  ManagedSearchModels(
      Function<LlamaRuntime.Request, ManagedRuntimeHttp.Response> llama,
      ObjectMapper objectMapper,
      Function<List<String>, String> artifactRecipeId) {
    this.llama = Objects.requireNonNull(llama, "llama");
    this.objectMapper = Objects.requireNonNull(objectMapper, "json");
    this.artifactRecipeId = Objects.requireNonNull(artifactRecipeId, "artifactRecipeId");
  }

  @Override
  public EmbeddingMetadata embeddingMetadata() {
    return new EmbeddingMetadata(
        RecipeId.of(
            "embedding.model", "v1", artifactRecipeId.apply(List.of(ModelRole.EMBED.artifactId()))),
        RecipeId.of(
            "embedding.tokenizer",
            "v1",
            "model=" + ModelRole.EMBED.apiName(),
            "endpoint=/tokenize",
            artifactRecipeId.apply(ModelRole.EMBED.runtimeArtifactIds())),
        SearchModels.VECTOR_DIMENSIONS,
        2048);
  }

  @Override
  public int countTokens(String inputText) {
    var response =
        readResponse(
            postRuntimeRequest(
                "/tokenize",
                new TokenizeRequest(ModelRole.EMBED.apiName(), nullToEmpty(inputText)),
                Duration.ofSeconds(30)),
            TokenizeResponse.class,
            "The tokenizer returned an invalid response.");
    if (response.tokens() == null) {
      throw runtimeFailure("The tokenizer returned an invalid response.");
    }
    return response.tokens().size();
  }

  @Override
  public float[] embed(String inputText) {
    var vectors =
        embeddingVectors(
            postRuntimeRequest(
                "/v1/embeddings",
                new EmbeddingRequest(ModelRole.EMBED.apiName(), nullToEmpty(inputText)),
                REQUEST_TIMEOUT));
    if (vectors.size() != 1) {
      throw runtimeFailure("The embedding runtime returned an invalid response.");
    }
    return vectors.getFirst();
  }

  @Override
  public List<float[]> embedBatch(List<String> inputTexts) {
    Objects.requireNonNull(inputTexts, "inputs");
    if (inputTexts.isEmpty()) {
      return List.of();
    }
    var vectors =
        embeddingVectors(
            postRuntimeRequest(
                "/v1/embeddings",
                new EmbeddingBatchRequest(
                    ModelRole.EMBED.apiName(),
                    inputTexts.stream().map(ManagedSearchModels::nullToEmpty).toList()),
                REQUEST_TIMEOUT));
    if (vectors.size() != inputTexts.size()) {
      throw runtimeFailure("The embedding runtime returned an incomplete batch response.");
    }
    return vectors;
  }

  @Override
  public Expansion expand(String query) {
    var response =
        readResponse(
            postRuntimeRequest(
                "/v1/chat/completions",
                new ChatCompletionRequest(
                    ModelRole.EXPAND.apiName(),
                    List.of(
                        new ChatMessage(
                            "user", "/no_think Expand this search query: " + nullToEmpty(query))),
                    600,
                    0.7,
                    20,
                    0.8,
                    0.5,
                    EXPANSION_GRAMMAR),
                REQUEST_TIMEOUT),
            ChatCompletionResponse.class,
            "The query expander returned an invalid response.");
    if (response.choices() == null
        || response.choices().isEmpty()
        || response.choices().getFirst() == null
        || response.choices().getFirst().message() == null
        || response.choices().getFirst().message().content() == null
        || response.choices().getFirst().message().content().isBlank()) {
      throw runtimeFailure("The query expander returned an invalid response.");
    }
    return parseExpansion(response.choices().getFirst().message().content());
  }

  @Override
  public List<RerankScore> rerank(
      String searchQuery, List<String> candidateTexts, int resultLimit) {
    Objects.requireNonNull(candidateTexts, "candidates");
    if (candidateTexts.isEmpty() || resultLimit < 1) {
      return List.of();
    }
    var response =
        readResponse(
            postRuntimeRequest(
                "/reranking",
                new RerankRequest(
                    ModelRole.RERANK.apiName(),
                    nullToEmpty(searchQuery),
                    candidateTexts.stream().map(ManagedSearchModels::nullToEmpty).toList()),
                REQUEST_TIMEOUT),
            RerankResponse.class,
            "The reranker returned an invalid response.");
    if (response.results() == null) {
      throw runtimeFailure("The reranker returned an invalid response.");
    }
    var scores = new ArrayList<RerankScore>(response.results().size());
    for (var result : response.results()) {
      if (result == null
          || result.index() == null
          || result.score() == null
          || result.index() < 0
          || result.index() >= candidateTexts.size()
          || !Double.isFinite(result.score())) {
        throw runtimeFailure("The reranker returned an invalid response.");
      }
      scores.add(new RerankScore(result.index(), Math.clamp(result.score(), 0.0, 1.0)));
    }
    return scores.stream()
        .sorted(Comparator.comparingDouble(RerankScore::score).reversed())
        .limit(resultLimit)
        .toList();
  }

  private Expansion parseExpansion(String text) {
    var lexical = new ArrayList<String>();
    var vector = new ArrayList<String>();
    var hyde = new ArrayList<String>();
    for (var rawLine : text.split("\\R")) {
      var line = rawLine.strip();
      var separator = line.indexOf(':');
      if (separator < 1) {
        continue;
      }
      var type = line.substring(0, separator).strip().toLowerCase(Locale.ROOT);
      var query = line.substring(separator + 1).replaceAll("\\s+", " ").strip();
      if (query.isBlank()) {
        continue;
      }
      switch (type) {
        case "lex" -> lexical.add(query);
        case "vec" -> vector.add(query);
        case "hyde" -> hyde.add(query);
        default -> {
          // The grammar prevents other types; ignore defensive trailing output.
        }
      }
    }
    return new Expansion(lexical, vector, hyde);
  }

  private List<float[]> embeddingVectors(String responseJson) {
    var response =
        readResponse(
            responseJson,
            EmbeddingResponse.class,
            "The embedding runtime returned an invalid response.");
    if (response.data() == null) {
      throw runtimeFailure("The embedding runtime returned an invalid response.");
    }
    var vectors = new float[response.data().size()][];
    for (var data : response.data()) {
      if (data == null
          || data.index() == null
          || data.embedding() == null
          || data.embedding().isEmpty()
          || data.index() < 0
          || data.index() >= vectors.length
          || vectors[data.index()] != null) {
        throw runtimeFailure("The embedding runtime returned an invalid response.");
      }
      var vector = new float[data.embedding().size()];
      for (var index = 0; index < vector.length; index++) {
        var component = data.embedding().get(index);
        if (component == null || !Float.isFinite(component)) {
          throw runtimeFailure("The embedding runtime returned an invalid vector.");
        }
        vector[index] = component;
      }
      vectors[data.index()] = vector;
    }
    for (var vector : vectors) {
      if (vector == null) {
        throw runtimeFailure("The embedding runtime returned an invalid response.");
      }
    }
    return List.copyOf(java.util.Arrays.asList(vectors));
  }

  private <T> T readResponse(String json, Class<T> type, String failureMessage) {
    try {
      var response = objectMapper.readValue(json, type);
      if (response == null) {
        throw runtimeFailure(failureMessage);
      }
      return response;
    } catch (AppException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw runtimeFailure(failureMessage, e);
    }
  }

  private String postRuntimeRequest(
      String requestPath, Object requestPayload, Duration requestTimeout) {
    try {
      var response =
          llama.apply(new LlamaRuntime.Request(requestPath, requestPayload, requestTimeout));
      if (!response.successful()) {
        LOG.warn(
            "Managed search runtime returned HTTP {}: {}", response.statusCode(), response.body());
        throw runtimeFailure(
            "The managed search runtime returned HTTP " + response.statusCode() + ".");
      }
      return response.body();
    } catch (AppException e) {
      throw e;
    } catch (RuntimeException e) {
      throw runtimeFailure("Could not call the managed search runtime.", e);
    }
  }

  private static String nullToEmpty(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    StringBuilder cleaned = null;
    for (var index = 0; index < value.length(); index++) {
      var character = value.charAt(index);
      if (Character.isHighSurrogate(character)
          && index + 1 < value.length()
          && Character.isLowSurrogate(value.charAt(index + 1))) {
        if (cleaned != null) {
          cleaned.append(character).append(value.charAt(index + 1));
        }
        index++;
        continue;
      }
      if (Character.isSurrogate(character)) {
        if (cleaned == null) {
          cleaned = new StringBuilder(value.length()).append(value, 0, index);
        }
        cleaned.append('\uFFFD');
      } else if (cleaned != null) {
        cleaned.append(character);
      }
    }
    return cleaned == null ? value : cleaned.toString();
  }

  private static AppException runtimeFailure(String message) {
    return new AppException(OPERATION_FAILED, message, "Run `soma system pull`, then retry.");
  }

  private static AppException runtimeFailure(String message, Throwable cause) {
    return new AppException(
        OPERATION_FAILED, message, "Run `soma system pull`, then retry.", cause);
  }

  @Serdeable
  private record TokenizeRequest(String model, String content) {}

  @Serdeable
  private record EmbeddingRequest(String model, String input) {}

  @Serdeable
  private record EmbeddingBatchRequest(String model, List<String> input) {}

  @Serdeable
  private record ChatCompletionRequest(
      String model,
      List<ChatMessage> messages,
      @JsonProperty("max_tokens") int maxTokens,
      double temperature,
      @JsonProperty("top_k") int topK,
      @JsonProperty("top_p") double topP,
      @JsonProperty("presence_penalty") double presencePenalty,
      String grammar) {}

  @Serdeable
  private record RerankRequest(String model, String query, List<String> documents) {}
}

@Serdeable
record TokenizeResponse(List<Integer> tokens) {}

@Serdeable
record EmbeddingResponse(List<EmbeddingData> data) {}

@Serdeable
record EmbeddingData(Integer index, List<Float> embedding) {}

@Serdeable
record ChatCompletionResponse(List<ChatChoice> choices) {}

@Serdeable
record ChatChoice(ChatMessage message) {}

@Serdeable
record ChatMessage(String role, String content) {}

@Serdeable
record RerankResponse(List<RerankResult> results) {}

@Serdeable
record RerankResult(Integer index, @JsonProperty("relevance_score") Double score) {}
