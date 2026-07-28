package io.github.awesomedog.soma.infra.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.HostPlatform;
import io.micronaut.serde.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlamaRuntimeTest {

  @TempDir Path temporaryDirectory;

  @Test
  void reusesALiveLazyLlamaRuntimeAndCleansItsPreset() throws Exception {
    var starts = temporaryDirectory.resolve("starts.txt");
    var script = fakeRuntimeScript(starts);
    var server = artifactServer(script);
    ManagedProcesses processes = null;
    try {
      var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      var artifacts = llamaArtifacts(endpoint, script);
      artifacts.pull(false);
      var runtimeDirectory = llamaRuntimeDirectory(script);

      processes = new ManagedProcesses(artifacts);
      var llama = new LlamaRuntime(artifacts, processes, ObjectMapper.getDefault());
      var first = llama.ensureRunningEndpoint();

      try (var files = Files.list(runtimeDirectory)) {
        assertThat(files)
            .noneMatch(path -> path.getFileName().toString().startsWith(".soma-llama-"));
      }
      assertLazyLlamaConfiguration();
      var loading =
          llama.post(
              new LlamaRuntime.Request(
                  "/v1/embeddings", Map.of("model", "EMBED"), Duration.ofSeconds(2)));
      assertThat(loading.statusCode()).isEqualTo(503);

      assertThat(llama.ensureRunningEndpoint()).isEqualTo(first);
      assertThat(Files.readAllLines(starts, UTF_8)).containsExactly("start");
    } finally {
      if (processes != null) {
        processes.stopAll();
      }
      server.stop(0);
    }
  }

  @Test
  void startupFailureCleansUpTheTransientPresetAndLeavesNoRunningRuntime() throws Exception {
    var script = fakeRuntimeScript(temporaryDirectory.resolve("starts.txt"), true);
    var server = artifactServer(script);
    try {
      var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      var artifacts = llamaArtifacts(endpoint, script);
      artifacts.pull(false);
      var processes = new ManagedProcesses(artifacts);
      var llama = new LlamaRuntime(artifacts, processes, ObjectMapper.getDefault());
      try {
        assertThatThrownBy(llama::ensureRunningEndpoint)
            .isInstanceOfSatisfying(
                AppException.class,
                error ->
                    assertThat(error.error().code()).isEqualTo(AppError.Code.OPERATION_FAILED));

        var runtimeDirectory = llamaRuntimeDirectory(script);
        try (var files = Files.list(runtimeDirectory)) {
          assertThat(files)
              .noneMatch(
                  path ->
                      path.getFileName().toString().startsWith(".soma-llama-")
                          && Files.isRegularFile(path));
        }
      } finally {
        processes.stopAll();
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void concurrentRequestsStartOnlyOneRuntime() throws Exception {
    var starts = temporaryDirectory.resolve("starts.txt");
    var script = fakeRuntimeScript(starts);
    var server = artifactServer(script);
    try {
      var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      var artifacts = llamaArtifacts(endpoint, script);
      artifacts.pull(false);
      var processes = new ManagedProcesses(artifacts);
      var llama = new LlamaRuntime(artifacts, processes, ObjectMapper.getDefault());
      try {
        var ready = new CountDownLatch(8);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(8)) {
          var futures = new ArrayList<Future<URI>>();
          for (var index = 0; index < 8; index++) {
            futures.add(
                workers.submit(
                    () -> {
                      ready.countDown();
                      release.await();
                      return llama.ensureRunningEndpoint();
                    }));
          }
          assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
          release.countDown();

          var first = futures.getFirst().get(10, TimeUnit.SECONDS);
          for (var future : futures) {
            assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(first);
          }
        }
        assertThat(Files.readAllLines(starts, UTF_8)).containsExactly("start");
      } finally {
        processes.stopAll();
      }
    } finally {
      server.stop(0);
    }
  }

  @Test
  void restartsTheRuntimeAfterItsOwnedProcessExits() throws Exception {
    var starts = temporaryDirectory.resolve("starts.txt");
    var script = fakeRuntimeScript(starts);
    var server = artifactServer(script);
    try {
      var endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
      var artifacts = llamaArtifacts(endpoint, script);
      artifacts.pull(false);
      var processes = new ManagedProcesses(artifacts);
      var llama = new LlamaRuntime(artifacts, processes, ObjectMapper.getDefault());
      try {
        var first = llama.ensureRunningEndpoint();
        stopRuntime(first);
        waitUntilUnavailable(first);

        waitUntilRestarted(llama, starts);

        assertThat(Files.readAllLines(starts, UTF_8)).containsExactly("start", "start");
      } finally {
        processes.stopAll();
      }
    } finally {
      server.stop(0);
    }
  }

  private String fakeRuntimeScript(Path starts) {
    return fakeRuntimeScript(starts, false);
  }

  private String fakeRuntimeScript(Path starts, boolean failOnStartup) {
    var launch = temporaryDirectory.resolve("launch.txt");
    var java =
        Path.of(
            System.getProperty("java.home"),
            "bin",
            HostPlatform.current().isWindows() ? "java.exe" : "java");
    var arguments = failOnStartup ? " --fail" : "";
    if (HostPlatform.current().isWindows()) {
      return """
              @echo off
              "%s" -cp "%s" %s "%s" "%s"%s %%*
              """
          .formatted(
              java,
              System.getProperty("java.class.path"),
              FakeRuntime.class.getName(),
              starts,
              launch,
              arguments);
    }
    return """
            #!/bin/sh
            exec %s -cp %s %s %s %s%s "$@"
            """
        .formatted(
            shellQuote(java.toString()),
            shellQuote(System.getProperty("java.class.path")),
            shellQuote(FakeRuntime.class.getName()),
            shellQuote(starts.toString()),
            shellQuote(launch.toString()),
            arguments);
  }

  private static HttpServer artifactServer(String script) throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          var path = exchange.getRequestURI().getPath();
          var body = (path.endsWith("llama-server") ? script : "GGUFmodel").getBytes(UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    return server;
  }

  private void assertLazyLlamaConfiguration() throws Exception {
    assertThat(Files.readString(temporaryDirectory.resolve("launch.txt"), UTF_8))
        .contains(
            "--models-max\n5\n",
            "--models-autoload\n",
            "[EMBED]",
            "[EXPAND]",
            "[RERANK]",
            "[VISION]",
            "[OCR_CLEANUP]",
            "embeddings = true",
            "reranking = true",
            "mmproj = ",
            "ctx-size = 16384")
        .doesNotContain("load-on-startup");
    assertThat(LlamaRuntime.ModelRole.VISION.runtimeArtifactIds())
        .containsExactly("llama-server", "vision", "vision-mmproj");
  }

  private static void stopRuntime(URI endpoint) throws Exception {
    var response =
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(endpoint.resolve("/stop"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding());
    assertThat(response.statusCode()).isEqualTo(200);
  }

  private static void waitUntilUnavailable(URI endpoint) throws Exception {
    var deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadlineNanos) {
      try {
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(endpoint.resolve("/health"))
                    .timeout(Duration.ofMillis(200))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.discarding());
      } catch (java.io.IOException expected) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("managed runtime did not exit");
  }

  private static void waitUntilRestarted(LlamaRuntime llama, Path starts) throws Exception {
    var deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (System.nanoTime() < deadlineNanos) {
      llama.ensureRunningEndpoint();
      if (Files.readAllLines(starts, UTF_8).size() == 2) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("managed runtime did not restart");
  }

  private ManagedArtifacts llamaArtifacts(String endpoint, String script) {
    return new ManagedArtifacts(
        temporaryDirectory.resolve("data"),
        HostPlatform.current().id(),
        llamaArtifactManifest(endpoint, script),
        HttpClient.newHttpClient());
  }

  private static String llamaArtifactManifest(String endpoint, String script) {
    var modelSha256 = Hashing.sha256HexUtf8("GGUFmodel");
    var modelArtifacts =
        java.util.stream.Stream.of(
                "embed", "expand", "rerank", "vision", "vision-mmproj", "ocr-cleanup")
            .map(
                id ->
                    """
                    {
                      "id": "%s",
                      "version": "1",
                      "platform": "all",
                      "url": "%s/%s",
                      "sha256": "%s",
                      "format": "file",
                      "entry": "model.gguf",
                      "executable": false
                    }
                    """
                        .formatted(id, endpoint, id, modelSha256))
            .collect(java.util.stream.Collectors.joining(",\n"));
    return """
            {
              "version": 1,
              "artifacts": [
                {
                  "id": "llama-server",
                  "version": "1",
                  "platform": "%s",
                  "url": "%s/llama-server",
                  "sha256": "%s",
                  "format": "file",
                  "entry": "%s",
                  "executable": true
                },
                %s
              ]
            }
            """
        .formatted(
            HostPlatform.current().id(),
            endpoint,
            Hashing.sha256HexUtf8(script),
            HostPlatform.current().isWindows() ? "llama-server.cmd" : "llama-server",
            modelArtifacts);
  }

  private Path llamaRuntimeDirectory(String script) {
    return temporaryDirectory
        .resolve("data/live/llama-server")
        .resolve(Hashing.sha256HexUtf8(script).substring(0, 6));
  }

  private static String shellQuote(String value) {
    return "'" + value.replace("'", "'\"'\"'") + "'";
  }

  public static final class FakeRuntime {

    public static void main(String[] args) throws Exception {
      exitWhenParentStops();
      var starts = Path.of(args[0]);
      var launch = Path.of(args[1]);
      var argumentIndex = 2;
      if (argumentIndex < args.length && "--fail".equals(args[argumentIndex])) {
        System.out.println("llama-startup-failed");
        System.exit(1);
      }
      Files.writeString(
          starts, "start\n", UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      var port = -1;
      Path preset = null;
      var launchDetails = new StringBuilder();
      for (var i = argumentIndex; i < args.length; i++) {
        launchDetails.append(args[i]).append('\n');
        if (i + 1 >= args.length) continue;
        if ("--port".equals(args[i])) {
          port = Integer.parseInt(args[i + 1]);
        }
        if ("--models-preset".equals(args[i])) {
          preset = Path.of(args[i + 1]);
        }
      }
      if (preset != null) {
        launchDetails.append("--- preset ---\n").append(Files.readString(preset));
      }
      Files.writeString(launch, launchDetails, UTF_8);
      try (var server = new ServerSocket()) {
        server.bind(new InetSocketAddress("127.0.0.1", port));
        var healthChecks = 0;
        while (true) {
          try (var socket = server.accept()) {
            var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), UTF_8));
            var requestLine = input.readLine();
            String line;
            while ((line = input.readLine()) != null && !line.isEmpty()) {}
            var stop = requestLine != null && requestLine.contains(" /stop ");
            var ready = stop || healthChecks++ == 0;
            var status = ready ? "200 OK" : "503 Service Unavailable";
            var body = stop ? "stopping" : ready ? "ready" : "loading";
            var response =
                "HTTP/1.1 "
                    + status
                    + "\r\nContent-Length: "
                    + body.getBytes(UTF_8).length
                    + "\r\nConnection: close\r\n\r\n"
                    + body;
            socket.getOutputStream().write(response.getBytes(UTF_8));
            if (stop) return;
          }
        }
      }
    }

    private static void exitWhenParentStops() {
      var parent = ProcessHandle.current().parent();
      if (parent.isEmpty()) {
        return;
      }
      Thread.startVirtualThread(
          () -> {
            while (parent.get().isAlive()) {
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
            }
            System.exit(0);
          });
    }
  }
}
