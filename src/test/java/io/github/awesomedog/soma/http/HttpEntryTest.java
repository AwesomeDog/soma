package io.github.awesomedog.soma.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.awesomedog.soma.app.common.AppError;
import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

@MicronautTest
@Property(name = "micronaut.server.host", value = "127.0.0.1")
class HttpEntryTest {

  @Inject
  @Client("/")
  HttpClient client;

  @Test
  void returnsTheRpcEnvelopeForFrameworkBindingErrors() {
    var requests =
        List.<HttpRequest<?>>of(
            HttpRequest.POST("/api/run", Map.of("command", "status", "workspace", "other")),
            HttpRequest.POST("/api/run", "{\"command\":")
                .contentType(MediaType.APPLICATION_JSON_TYPE),
            HttpRequest.POST("/api/run", Map.of("command", "status", "args", "invalid")),
            HttpRequest.POST("/api/run", "{\"command\":\"status\",\"args\":[null]}")
                .contentType(MediaType.APPLICATION_JSON_TYPE),
            HttpRequest.create(HttpMethod.POST, "/api/run")
                .contentType(MediaType.APPLICATION_JSON_TYPE));

    for (var request : requests) {
      assertBindingError(client.toBlocking().retrieve(request, RunResponse.class));
    }
  }

  @Test
  void rejectsValuesOutsideTheRpcJsonSchema() {
    var requestBodies =
        List.of(
            "{\"command\":42}",
            "{\"command\":\"status\",\"global\":{\"verbose\":\"true\"}}",
            "{\"command\":\"status\",\"global\":{\"verbose\":null}}",
            "{\"command\":\"status\",\"options\":{\"limit\":null}}",
            "{\"command\":\"status\",\"options\":{\"limit\":{}}}",
            "{\"command\":\"status\",\"options\":{\"project\":[[\"docs\"]]}}",
            "{\"command\":\"status\",\"options\":{\"full\":false}}");

    for (var requestBody : requestBodies) {
      var request =
          HttpRequest.POST("/api/run", requestBody).contentType(MediaType.APPLICATION_JSON_TYPE);
      assertInvalidRequest(client.toBlocking().retrieve(request, RunResponse.class));
    }
  }

  private static void assertBindingError(RunResponse response) {
    assertInvalidRequest(response);
    assertThat(response.durationMs()).isZero();
  }

  private static void assertInvalidRequest(RunResponse response) {
    assertThat(response.success()).isFalse();
    assertThat(response.requestId()).isNotBlank();
    assertThat(response.exitCode()).isEqualTo(CommandLine.ExitCode.USAGE);
    assertThat(response.data()).isNull();
    assertThat(response.error().code()).isEqualTo(AppError.Code.INVALID_REQUEST);
  }
}
