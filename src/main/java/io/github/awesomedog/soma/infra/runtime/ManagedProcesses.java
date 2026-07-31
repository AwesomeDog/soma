package io.github.awesomedog.soma.infra.runtime;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.awesomedog.soma.app.common.AppException;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class ManagedProcesses {

  static final String HOST = "127.0.0.1";

  private static final Logger LOG = LoggerFactory.getLogger(ManagedProcesses.class);
  private static final int OCR_PORT_START = 9003;
  private static final int OCR_PORT_END = 9020;
  private static final Duration START_TIMEOUT = Duration.ofMinutes(5);
  static final Duration READY_CHECK_INTERVAL = Duration.ofMillis(500);
  private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
  private static final int OUTPUT_TAIL_CHARS = 8_192;

  private final ManagedArtifacts managedArtifacts;
  private final HttpClient httpClient;
  private final Map<ProcessKind, RunningProcess> runningProcesses =
      new EnumMap<>(ProcessKind.class);

  public ManagedProcesses(ManagedArtifacts managedArtifacts) {
    this.managedArtifacts = Objects.requireNonNull(managedArtifacts, "artifacts");
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public URI ensureOcrEndpoint() {
    return ensureRunning(ProcessKind.OCR, this::ocrLaunch);
  }

  synchronized URI ensureRunning(ProcessKind kind, Callable<Launch> launchFactory) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(launchFactory, "launchFactory");
    try {
      var currentProcess = runningProcesses.get(kind);
      if (currentProcess != null && currentProcess.process().isAlive()) {
        return currentProcess.endpoint();
      }
      logUnexpectedExit(kind, currentProcess);
      stopManagedProcess(kind);
      var startedProcess = startManagedProcess(kind, launchFactory.call());
      runningProcesses.put(kind, startedProcess);
      return startedProcess.endpoint();
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      stopManagedProcess(kind);
      throw new AppException(
          OPERATION_FAILED,
          "Managed runtime could not start: " + kind.id(),
          "Run `soma system pull`, then retry.",
          e);
    }
  }

  @PreDestroy
  public synchronized void stopAll() {
    for (var kind : List.copyOf(runningProcesses.keySet())) {
      stopManagedProcess(kind);
    }
  }

  static int findAvailablePort(int firstPort, int lastPort) {
    for (var port = firstPort; port <= lastPort; port++) {
      try (var socket = new ServerSocket()) {
        socket.setReuseAddress(false);
        socket.bind(new InetSocketAddress(HOST, port));
        return port;
      } catch (IOException ignored) {
        // Try the next managed-runtime port.
      }
    }
    throw new IllegalStateException(
        "No managed runtime port is available in " + firstPort + ".." + lastPort);
  }

  enum ProcessKind {
    LLAMA("llama"),
    OCR("ocr");

    private final String id;

    ProcessKind(String id) {
      this.id = id;
    }

    String id() {
      return id;
    }
  }

  record Launch(List<String> commandLine, Path workingDirectory, int port, Path transientFile) {

    Launch {
      commandLine = List.copyOf(Objects.requireNonNull(commandLine, "commandLine"));
      workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
    }
  }

  private Launch ocrLaunch() {
    var ocrExecutable = managedArtifacts.ensurePresent("rapidocr").get("rapidocr");
    var port = findAvailablePort(OCR_PORT_START, OCR_PORT_END);
    return new Launch(
        List.of(ocrExecutable.toString(), "serve", "--host", HOST, "--port", String.valueOf(port)),
        ocrExecutable.getParent(),
        port,
        null);
  }

  private RunningProcess startManagedProcess(ProcessKind kind, Launch launch) throws IOException {
    Process process = null;
    OutputTail output = null;
    try {
      process =
          new ProcessBuilder(launch.commandLine())
              .directory(launch.workingDirectory().toFile())
              .redirectErrorStream(true)
              .start();
      output = OutputTail.capture(process, kind);
      var endpoint = URI.create("http://" + HOST + ":" + launch.port());
      waitForServerReady(kind, process, endpoint, output);
      deleteFileQuietly(launch.transientFile());
      return new RunningProcess(process, endpoint, launch.transientFile(), output);
    } catch (IOException | RuntimeException e) {
      terminateProcess(process);
      deleteFileQuietly(launch.transientFile());
      throw e;
    }
  }

  private void waitForServerReady(
      ProcessKind kind, Process process, URI endpoint, OutputTail output) {
    var deadlineNanos = System.nanoTime() + START_TIMEOUT.toNanos();
    while (System.nanoTime() < deadlineNanos) {
      if (!process.isAlive()) {
        output.awaitCompletion();
        throw startupFailure(
            "Managed runtime exited during startup: " + kind.id(), output.snapshot());
      }
      if (isEndpointReady(endpoint)) {
        return;
      }
      try {
        Thread.sleep(READY_CHECK_INTERVAL);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
            "Interrupted while starting managed runtime: " + kind.id(), e);
      }
    }
    throw startupFailure(
        "Managed runtime readiness check timed out: " + kind.id(), output.snapshot());
  }

  private boolean isEndpointReady(URI endpoint) {
    try {
      var healthRequest =
          HttpRequest.newBuilder(endpoint.resolve("/health"))
              .timeout(Duration.ofSeconds(3))
              .GET()
              .build();
      var status =
          httpClient.send(healthRequest, HttpResponse.BodyHandlers.discarding()).statusCode();
      return status == 200;
    } catch (IOException e) {
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private void logUnexpectedExit(ProcessKind kind, RunningProcess process) {
    if (process == null || process.process().isAlive()) {
      return;
    }
    process.output().awaitCompletion();
    var output = process.output().snapshot().strip();
    if (output.isBlank()) {
      LOG.warn("Managed runtime {} exited; restarting", kind.id());
    } else {
      LOG.warn("Managed runtime {} exited; restarting. Last output:\n{}", kind.id(), output);
    }
  }

  private void stopManagedProcess(ProcessKind kind) {
    var runningProcess = runningProcesses.remove(kind);
    if (runningProcess == null) {
      return;
    }
    terminateProcess(runningProcess.process());
    deleteFileQuietly(runningProcess.transientFile());
  }

  private static void terminateProcess(Process process) {
    if (process == null) {
      return;
    }
    if (process.isAlive()) {
      try {
        process.destroy();
        if (!process.waitFor(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
          process.destroyForcibly();
          process.waitFor(2, TimeUnit.SECONDS);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
      }
    }
    try {
      process.getInputStream().close();
    } catch (IOException ignored) {
      // Closing a terminated process stream is best effort.
    }
  }

  private static IllegalStateException startupFailure(String message, String output) {
    var detail = output == null ? "" : output.strip();
    return new IllegalStateException(detail.isBlank() ? message : message + "\n" + detail);
  }

  private static void deleteFileQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Runtime-owned transient files are best-effort cleanup.
    }
  }

  private record RunningProcess(
      Process process, URI endpoint, Path transientFile, OutputTail output) {}

  private static final class OutputTail {

    private final StringBuilder value = new StringBuilder();
    private Thread readerThread;

    static OutputTail capture(Process process, ProcessKind kind) {
      var output = new OutputTail();
      output.readerThread =
          Thread.ofVirtual()
              .name("soma-" + kind.id() + "-output")
              .unstarted(() -> output.read(process));
      output.readerThread.start();
      return output;
    }

    private void read(Process process) {
      try (var reader = new InputStreamReader(process.getInputStream(), UTF_8)) {
        var buffer = new char[1024];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
          append(new String(buffer, 0, count));
        }
      } catch (IOException ignored) {
        // The stream normally closes when the managed process terminates.
      }
    }

    private synchronized void append(String chunk) {
      value.append(chunk);
      if (value.length() > OUTPUT_TAIL_CHARS) {
        value.delete(0, value.length() - OUTPUT_TAIL_CHARS);
      }
    }

    private void awaitCompletion() {
      try {
        readerThread.join(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    synchronized String snapshot() {
      return value.toString();
    }
  }
}
