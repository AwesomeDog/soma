package io.github.awesomedog.soma.infra.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

public final class ManagedRuntimeHttp {

  private ManagedRuntimeHttp() {}

  public static HttpClient newClient() {
    return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public static Response postJson(
      HttpClient client,
      ObjectMapper json,
      URI endpoint,
      String path,
      Object payload,
      Duration timeout)
      throws IOException, InterruptedException {
    var request =
        HttpRequest.newBuilder(Objects.requireNonNull(endpoint, "endpoint").resolve(path))
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Objects.requireNonNull(timeout, "timeout"))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    Objects.requireNonNull(json, "json").writeValueAsString(payload), UTF_8))
            .build();
    var response =
        Objects.requireNonNull(client, "client")
            .send(request, HttpResponse.BodyHandlers.ofString(UTF_8));
    return new Response(response.statusCode(), response.body());
  }

  public record Response(int statusCode, String body) {

    public boolean successful() {
      return statusCode >= 200 && statusCode < 300;
    }
  }
}
