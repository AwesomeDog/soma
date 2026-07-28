package io.github.awesomedog.soma.app.ports;

import java.util.ArrayList;
import java.util.List;

public interface SearchModels {

  int VECTOR_DIMENSIONS = 768;

  EmbeddingMetadata embeddingMetadata();

  int countTokens(String inputText);

  float[] embed(String inputText);

  default List<float[]> embedBatch(List<String> inputTexts) {
    var embeddingVectors = new ArrayList<float[]>(inputTexts.size());
    for (var inputText : inputTexts) {
      embeddingVectors.add(embed(inputText));
    }
    return List.copyOf(embeddingVectors);
  }

  Expansion expand(String query);

  List<RerankScore> rerank(String searchQuery, List<String> candidateTexts, int resultLimit);

  record EmbeddingMetadata(
      String embeddingModelRecipeId,
      String tokenizerRecipeId,
      int dimensions,
      int maxInputTokens) {}

  record Expansion(List<String> lexical, List<String> vector, List<String> hyde) {

    public Expansion {
      lexical = lexical == null ? List.of() : List.copyOf(lexical);
      vector = vector == null ? List.of() : List.copyOf(vector);
      hyde = hyde == null ? List.of() : List.copyOf(hyde);
    }
  }

  record RerankScore(int candidateIndex, double score) {}
}
