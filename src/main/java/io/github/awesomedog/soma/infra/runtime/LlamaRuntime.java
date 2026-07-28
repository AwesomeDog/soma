package io.github.awesomedog.soma.infra.runtime;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;
import static io.github.awesomedog.soma.infra.runtime.ManagedProcesses.ProcessKind.LLAMA;

import io.github.awesomedog.soma.app.common.AppException;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Singleton
public final class LlamaRuntime {

  private static final String SERVER_ARTIFACT_ID = "llama-server";
  private static final int PORT_START = 8088;
  private static final int PORT_END = 8187;
  private static final int MAX_LOADED_MODELS = 5;

  private final ManagedArtifacts managedArtifacts;
  private final ManagedProcesses managedProcesses;
  private final ObjectMapper json;
  private final HttpClient http;

  @Inject
  public LlamaRuntime(
      ManagedArtifacts managedArtifacts, ManagedProcesses managedProcesses, ObjectMapper json) {
    this.managedArtifacts = Objects.requireNonNull(managedArtifacts, "artifacts");
    this.managedProcesses = Objects.requireNonNull(managedProcesses, "processes");
    this.json = Objects.requireNonNull(json, "json");
    this.http = ManagedRuntimeHttp.newClient();
  }

  public ManagedRuntimeHttp.Response post(Request request) {
    Objects.requireNonNull(request, "request");
    try {
      var endpoint = ensureRunningEndpoint();
      var deadlineNanos = System.nanoTime() + request.timeout().toNanos();
      while (true) {
        var response =
            ManagedRuntimeHttp.postJson(
                http, json, endpoint, request.path(), request.payload(), request.timeout());
        if (response.statusCode() != 503 || System.nanoTime() >= deadlineNanos) {
          return response;
        }
        Thread.sleep(ManagedProcesses.READY_CHECK_INTERVAL);
      }
    } catch (IOException e) {
      throw runtimeFailure("Could not call the managed llama runtime.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw runtimeFailure("The managed llama runtime request was interrupted.", e);
    }
  }

  java.net.URI ensureRunningEndpoint() {
    return managedProcesses.ensureRunning(LLAMA, this::launch);
  }

  private ManagedProcesses.Launch launch() throws IOException {
    var artifacts = managedArtifacts.ensurePresent(requiredArtifactIds().toArray(String[]::new));
    var executable = artifacts.get(SERVER_ARTIFACT_ID);
    var port = ManagedProcesses.findAvailablePort(PORT_START, PORT_END);
    var preset = writeModelPreset(executable.getParent(), artifacts);
    return new ManagedProcesses.Launch(
        List.of(
            executable.toString(),
            "--host",
            ManagedProcesses.HOST,
            "--port",
            String.valueOf(port),
            "--models-preset",
            preset.toString(),
            "--models-max",
            String.valueOf(MAX_LOADED_MODELS),
            "--models-autoload"),
        executable.getParent(),
        port,
        preset);
  }

  private static List<String> requiredArtifactIds() {
    var ids = new LinkedHashSet<String>();
    for (var role : ModelRole.values()) {
      ids.addAll(role.runtimeArtifactIds());
    }
    return List.copyOf(ids);
  }

  private Path writeModelPreset(Path directory, Map<String, Path> artifacts) throws IOException {
    var preset = createModelPresetPath(directory);
    var contents = new StringBuilder();
    for (var role : ModelRole.values()) {
      appendModelSection(contents, role, artifacts);
    }
    Files.writeString(preset, contents, java.nio.charset.StandardCharsets.UTF_8);
    return preset;
  }

  private static Path createModelPresetPath(Path directory) {
    return directory.resolve(
        ".soma-llama-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID() + ".ini");
  }

  private static void appendModelSection(
      StringBuilder preset, ModelRole role, Map<String, Path> artifacts) {
    preset.append('[').append(role.apiName()).append("]\n");
    preset
        .append("model = ")
        .append(artifacts.get(role.artifactId()).toAbsolutePath().normalize())
        .append('\n');
    if (role.projectorArtifactId() != null) {
      preset
          .append("mmproj = ")
          .append(artifacts.get(role.projectorArtifactId()).toAbsolutePath().normalize())
          .append('\n');
    }
    preset.append(role.presetSettings()).append('\n');
  }

  private static AppException runtimeFailure(String message, Throwable cause) {
    return new AppException(
        OPERATION_FAILED, message, "Run `soma system pull`, then retry.", cause);
  }

  public enum ModelRole {
    EMBED(
        "embed",
        null,
        """
        embeddings = true
        ctx-size = 2048
        batch-size = 2048
        ubatch-size = 2048
        """),
    EXPAND("expand", null, "ctx-size = 2048\n"),
    RERANK(
        "rerank",
        null,
        """
        reranking = true
        ctx-size = 4096
        batch-size = 2048
        ubatch-size = 2048
        """),
    VISION("vision", "vision-mmproj", "ctx-size = 32768\n"),
    OCR_CLEANUP("ocr-cleanup", null, "ctx-size = 16384\n");

    private final String artifactId;
    private final String projectorArtifactId;
    private final String presetSettings;

    ModelRole(String artifactId, String projectorArtifactId, String presetSettings) {
      this.artifactId = artifactId;
      this.projectorArtifactId = projectorArtifactId;
      this.presetSettings = presetSettings;
    }

    public String apiName() {
      return name();
    }

    public String artifactId() {
      return artifactId;
    }

    public List<String> runtimeArtifactIds() {
      var ids = new ArrayList<String>(3);
      ids.add(SERVER_ARTIFACT_ID);
      ids.add(artifactId);
      if (projectorArtifactId != null) {
        ids.add(projectorArtifactId);
      }
      return List.copyOf(ids);
    }

    String projectorArtifactId() {
      return projectorArtifactId;
    }

    String presetSettings() {
      return presetSettings;
    }
  }

  public record Request(String path, Object payload, Duration timeout) {

    public Request {
      path = Objects.requireNonNull(path, "path");
      payload = Objects.requireNonNull(payload, "payload");
      timeout = Objects.requireNonNull(timeout, "timeout");
      if (!path.startsWith("/")) {
        throw new IllegalArgumentException("Llama request path must start with '/'");
      }
    }
  }
}
