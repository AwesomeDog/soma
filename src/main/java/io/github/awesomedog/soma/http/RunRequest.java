package io.github.awesomedog.soma.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import java.util.Map;

@Serdeable
@JsonIgnoreProperties(ignoreUnknown = false)
public record RunRequest(
    String command,
    List<String> args,
    Map<String, JsonNode> options,
    Map<String, JsonNode> global) {

  public RunRequest {
    args = args == null ? List.of() : List.copyOf(args);
  }
}
