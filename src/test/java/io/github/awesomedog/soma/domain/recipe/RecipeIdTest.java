package io.github.awesomedog.soma.domain.recipe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RecipeIdTest {

  @Test
  void hashesAnyNumberOfOrderedLengthPrefixedParts() {
    var recipeId = RecipeId.of("image.body", "v1", "description", "ocr");

    assertThat(recipeId).matches("[0-9a-f]{64}");
    assertThat(RecipeId.of("image.body", "v1", "description", "ocr")).isEqualTo(recipeId);
    assertThat(RecipeId.of("ab", "c")).isNotEqualTo(RecipeId.of("a", "bc"));
    assertThat(RecipeId.of("description", "ocr")).isNotEqualTo(RecipeId.of("ocr", "description"));
  }
}
