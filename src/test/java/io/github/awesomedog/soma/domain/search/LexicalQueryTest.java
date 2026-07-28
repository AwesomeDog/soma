package io.github.awesomedog.soma.domain.search;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LexicalQueryTest {

  @Test
  void rejectsMalformedQueryBoundaries() {
    assertThatThrownBy(() -> LexicalQuery.parse("foo - bar"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LexicalQuery.parse("foo \"unterminated"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LexicalQuery.parse("foo \"\""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LexicalQuery.parse("\"foo\"bar"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LexicalQuery.parse("foo\"bar\""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LexicalQuery.parse("foo --bar"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
