package io.github.awesomedog.soma.domain.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LexicalProjectorTest {

  @Test
  void projectsNfkcNormalizedLatinTextWithLocaleIndependentCase() {
    assertThat(LexicalProjector.toProjection("Ｆｕｌｌ１２３ IValue can't-stop"))
        .isEqualTo("full 123 i value can t stop");
  }

  @Test
  void splitsPathsAndCodeIdentifiersAtSeparatorsCaseAndDigitBoundaries() {
    assertThat(LexicalProjector.tokens("src/api.HTTPServer2FA token_bucket rate-limit v2Endpoint"))
        .isEqualTo(
            List.of(
                "src",
                "api",
                "http",
                "server",
                "2",
                "fa",
                "token",
                "bucket",
                "rate",
                "limit",
                "v",
                "2",
                "endpoint"));
  }

  @Test
  void emitsCjkUnigramsBigramsAndTrigramsInStableOrder() {
    assertThat(LexicalProjector.toProjection("中文测试")).isEqualTo("中 文 测 试 中文 文测 测试 中文测 文测试");
    assertThat(LexicalProjector.toProjection("サーバー")).isEqualTo("サ ー バ ー サー ーバ バー サーバ ーバー");
    assertThat(LexicalProjector.toProjection("スーパー")).isEqualTo("ス ー パ ー スー ーパ パー スーパ ーパー");
    assertThat(LexicalProjector.tokens("Hello中文-world"))
        .isEqualTo(List.of("hello", "中", "文", "中文", "world"));
    assertThat(LexicalProjector.tokens("中\u20dd文")).containsExactly("中\u20dd", "文", "中\u20dd文");
    assertThat(LexicalProjector.containsCjk("server サーバー")).isTrue();
    assertThat(LexicalProjector.containsCjk("server")).isFalse();
  }
}
