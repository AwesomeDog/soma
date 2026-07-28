package io.github.awesomedog.soma.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.awesomedog.soma.app.common.AppException;
import io.micronaut.json.tree.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RunRequestMapperTest {

  private static final Set<String> ALLOWED_COMMANDS =
      Set.of("project.list", "search.hybrid", "search.lexical", "status");

  @Test
  void mapsStructuredRequestsToSafeCommandArguments() {
    var request =
        new RunRequest(
            "search.hybrid",
            List.of("--workspace", "query"),
            Map.of(
                "project", json(List.of("docs", "notes")),
                "limit", json(10),
                "full", json(true)),
            Map.of("verbose", json(true), "no-color", json(false)));

    assertThat(RunRequestMapper.toCommandArguments(request, ALLOWED_COMMANDS))
        .containsExactly(
            "--verbose",
            "search",
            "hybrid",
            "--full",
            "--limit=10",
            "--project=docs",
            "--project=notes",
            "--",
            "--workspace",
            "query");
  }

  @Test
  void rejectsAnythingOutsideTheRequestAllowlist() {
    assertThatThrownBy(
            () ->
                RunRequestMapper.toCommandArguments(
                    new RunRequest("system.pull", null, null, null), ALLOWED_COMMANDS))
        .isInstanceOf(AppException.class);

    assertThatThrownBy(
            () ->
                RunRequestMapper.toCommandArguments(
                    new RunRequest("status", null, null, Map.of("workspace", json("other"))),
                    ALLOWED_COMMANDS))
        .isInstanceOf(AppException.class);

    assertThatThrownBy(
            () ->
                RunRequestMapper.toCommandArguments(
                    new RunRequest("status", null, Map.of("workspace", json("other")), null),
                    ALLOWED_COMMANDS))
        .isInstanceOf(AppException.class);

    assertThatThrownBy(
            () ->
                RunRequestMapper.toCommandArguments(
                    new RunRequest(
                        "status", null, Map.of("workspace=other", json("ignored")), null),
                    ALLOWED_COMMANDS))
        .isInstanceOf(AppException.class);
  }

  private static JsonNode json(Object value) {
    return JsonNode.from(value);
  }
}
