package io.github.awesomedog.soma.infra.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.awesomedog.soma.support.HostPlatform;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsJobTest {

  @TempDir Path temporaryDirectory;

  @Test
  void killsInheritedProcessTreeWhenOwnerIsForciblyTerminated() throws Exception {
    Assumptions.assumeTrue(HostPlatform.current().isWindows());

    var readyFile = temporaryDirectory.resolve("ready");
    Process owner = null;
    Long rootProcessId = null;
    Long leafProcessId = null;
    try {
      owner = startJava(JobOwner.class, readyFile.toString());
      var processIds = waitForReady(owner, readyFile);
      rootProcessId = processIds[0];
      leafProcessId = processIds[1];

      assertThat(isAlive(rootProcessId)).isTrue();
      assertThat(isAlive(leafProcessId)).isTrue();

      owner.destroyForcibly();
      assertThat(owner.waitFor(10, TimeUnit.SECONDS)).isTrue();
      assertThat(waitForExit(rootProcessId, Duration.ofSeconds(10)))
          .as("assigned root process exits when the job owner is killed")
          .isTrue();
      assertThat(waitForExit(leafProcessId, Duration.ofSeconds(10)))
          .as("assigned descendant exits when the job owner is killed")
          .isTrue();
    } finally {
      destroy(owner);
      destroy(rootProcessId);
      destroy(leafProcessId);
    }
  }

  private static Process startJava(Class<?> mainClass, String... arguments) throws IOException {
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
    command.add("--enable-native-access=ALL-UNNAMED");
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(mainClass.getName());
    command.addAll(java.util.List.of(arguments));
    return new ProcessBuilder(command).redirectErrorStream(true).start();
  }

  private static long[] waitForReady(Process owner, Path readyFile) throws Exception {
    var deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
    while (System.nanoTime() < deadline) {
      if (Files.exists(readyFile)) {
        var value = Files.readString(readyFile, UTF_8).strip();
        if (!value.isEmpty()) {
          var processIds = value.split(" ");
          return new long[] {Long.parseLong(processIds[0]), Long.parseLong(processIds[1])};
        }
      }
      if (!owner.isAlive()) {
        throw new AssertionError("Job owner exited before readiness:\n" + processOutput(owner));
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for the job owner");
  }

  private static String processOutput(Process process) throws IOException {
    try (var input = process.getInputStream()) {
      return new String(input.readAllBytes(), UTF_8);
    }
  }

  private static boolean waitForExit(long processId, Duration timeout) throws InterruptedException {
    var deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (!isAlive(processId)) {
        return true;
      }
      Thread.sleep(50);
    }
    return !isAlive(processId);
  }

  private static boolean isAlive(long processId) {
    return ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
  }

  private static void destroy(Process process) {
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
    }
  }

  private static void destroy(Long processId) {
    if (processId == null) {
      return;
    }
    ProcessHandle.of(processId)
        .ifPresent(
            process -> {
              process.descendants().forEach(ProcessHandle::destroyForcibly);
              process.destroyForcibly();
            });
  }

  public static final class JobOwner {

    private JobOwner() {}

    public static void main(String[] arguments) throws Exception {
      WindowsJob.installForCurrentProcess();
      startJava(ProcessTreeRoot.class, arguments[0]).waitFor();
    }
  }

  public static final class ProcessTreeRoot {

    private ProcessTreeRoot() {}

    public static void main(String[] arguments) throws Exception {
      var leaf = startJava(ProcessTreeLeaf.class);
      Files.writeString(
          Path.of(arguments[0]), ProcessHandle.current().pid() + " " + leaf.pid(), UTF_8);
      Thread.currentThread().join();
    }
  }

  public static final class ProcessTreeLeaf {

    private ProcessTreeLeaf() {}

    public static void main(String[] arguments) throws InterruptedException {
      Thread.currentThread().join();
    }
  }
}
