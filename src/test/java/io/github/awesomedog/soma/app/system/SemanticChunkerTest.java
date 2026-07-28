package io.github.awesomedog.soma.app.system;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.awesomedog.soma.app.ports.SearchModels;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticChunkerTest {

  private static final SearchModels.EmbeddingMetadata EMBEDDING_METADATA =
      new SearchModels.EmbeddingMetadata("model-recipe", "tokenizer-recipe", 768, 168);

  private final SemanticChunker chunker = new SemanticChunker(new CharacterCountingSearchModels());

  @Test
  void hardSplitsAnOversizedFenceWithBoundedOverlap() {
    var body = "```\n" + "x".repeat(100) + "\n```\n";
    var chunks = chunker.plan(body, EMBEDDING_METADATA);

    assertThat(chunks)
        .hasSizeGreaterThan(1)
        .allSatisfy(chunk -> assertThat(chunk.tokenCount()).isLessThanOrEqualTo(40));
    assertThat(chunks.get(1).charStartOffset()).isLessThan(chunks.getFirst().charEndOffset());
    assertThat(chunks.getLast().charEndOffset()).isEqualTo(body.length());
  }

  @Test
  void neverSplitsUnicodeSurrogatePairs() {
    var body = "😀".repeat(100);
    var chunks = chunker.plan(body, EMBEDDING_METADATA);

    assertThat(chunks)
        .allSatisfy(
            chunk -> {
              assertThat(Character.isLowSurrogate(body.charAt(chunk.charStartOffset()))).isFalse();
              assertThat(Character.isHighSurrogate(body.charAt(chunk.charEndOffset() - 1)))
                  .isFalse();
            });
  }

  private static final class CharacterCountingSearchModels implements SearchModels {

    @Override
    public EmbeddingMetadata embeddingMetadata() {
      return EMBEDDING_METADATA;
    }

    @Override
    public int countTokens(String inputText) {
      return inputText.codePointCount(0, inputText.length());
    }

    @Override
    public float[] embed(String inputText) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Expansion expand(String query) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<RerankScore> rerank(
        String searchQuery, List<String> candidateTexts, int resultLimit) {
      throw new UnsupportedOperationException();
    }
  }
}
