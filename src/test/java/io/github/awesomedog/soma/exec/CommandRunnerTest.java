package io.github.awesomedog.soma.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.infra.logging.Logging;
import io.micronaut.context.ApplicationContext;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class CommandRunnerTest {

  @TempDir Path tempDir;

  @Test
  void concurrentRunsKeepCommandAndInvocationStateIsolated() throws Exception {
    try (var context = ApplicationContext.run();
        var executor = Executors.newFixedThreadPool(2)) {
      var runner = context.getBean(CommandRunner.class);
      var help = Invocation.captured();
      var invalid = Invocation.captured();

      var helpResult = executor.submit(() -> runner.run(new String[] {"--help"}, help));
      var invalidResult = executor.submit(() -> runner.run(new String[] {"--unknown"}, invalid));

      assertThat(helpResult.get()).isZero();
      assertThat(invalidResult.get()).isEqualTo(2);
      assertThat(help.recordedError()).isNull();
      assertThat(invalid.recordedError())
          .isInstanceOfSatisfying(
              AppError.class,
              error -> assertThat(error.code()).isEqualTo(AppError.Code.INVALID_REQUEST));
      assertThat(context.isRunning()).isTrue();
    }
  }

  @Test
  void acceptsVersionWhenRequestedAfterANestedSubcommand() {
    var invocation = Invocation.captured();
    try (var context = ApplicationContext.run()) {
      var exitCode =
          context
              .getBean(CommandRunner.class)
              .run(new String[] {"project", "list", "--version"}, invocation);

      assertThat(exitCode).isZero();
    }
  }

  @Test
  void locksWorkspaceCommandsThatDoNotPullArtifacts() {
    var workspace = workspace();
    try (var context = ApplicationContext.builder().singletons(workspace).start()) {
      var runner = context.getBean(CommandRunner.class);
      workspace.selectWorkspace(null);
      var writeLock = context.getBean(WriteLock.class);
      var writers = workspaceWriterCommands();
      var readers = workspaceReaderCommands();

      assertOnlyWriterCommandsAreLocked(runner, writeLock, workspace, writers, readers);
    } finally {
      Logging.close();
    }
  }

  private static List<String[]> workspaceWriterCommands() {
    return List.of(
        new String[] {"project", "add", "."},
        new String[] {"project", "update", "docs", "--default-search"},
        new String[] {"project", "remove", "docs"},
        new String[] {"project", "rename", "old", "new"},
        new String[] {"context", "set", "/", "context"},
        new String[] {"context", "remove", "/"},
        new String[] {"system", "scan"},
        new String[] {"system", "clean"});
  }

  private static List<String[]> workspaceReaderCommands() {
    return List.of(
        new String[] {"status"},
        new String[] {"project", "list"},
        new String[] {"context", "list"},
        new String[] {"search", "lexical", "query"},
        new String[] {"get", "missing.txt"});
  }

  private static void assertOnlyWriterCommandsAreLocked(
      CommandRunner runner,
      WriteLock writeLock,
      ActiveWorkspace workspace,
      List<String[]> writers,
      List<String[]> readers) {
    try (var ignored = writeLock.acquire(workspace.lockFile(), "soma sync")) {
      for (var argv : writers) {
        var invocation = Invocation.captured();
        assertThat(runner.run(argv, invocation)).as(String.join(" ", argv)).isEqualTo(1);
        assertThat(invocation.recordedError())
            .isInstanceOfSatisfying(
                AppError.class,
                error -> assertThat(error.code()).isEqualTo(AppError.Code.WRITE_LOCKED));
      }
      for (var argv : readers) {
        var invocation = Invocation.captured();
        runner.run(argv, invocation);
        var error = invocation.recordedError();
        if (error != null) {
          assertThat(error.code())
              .as(String.join(" ", argv))
              .isNotEqualTo(AppError.Code.WRITE_LOCKED);
        }
      }
    }
  }

  @Test
  void reportsAnOccupiedHttpPortAsAnOperationFailure() throws Exception {
    var workspace = workspace();
    var invocation = Invocation.captured();

    try (var occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        var context =
            ApplicationContext.builder()
                .properties(Map.of("micronaut.server.host", "127.0.0.1"))
                .singletons(workspace)
                .start()) {
      var port = occupied.getLocalPort();
      var exitCode =
          context
              .getBean(CommandRunner.class)
              .run(new String[] {"server", "--port", Integer.toString(port)}, invocation);

      assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);
      assertThat(invocation.recordedError())
          .isInstanceOfSatisfying(
              AppError.class,
              error -> assertThat(error.code()).isEqualTo(AppError.Code.OPERATION_FAILED));
    } finally {
      Logging.close();
    }
  }

  private ActiveWorkspace workspace() {
    var environment =
        Map.of(
            "XDG_CONFIG_HOME", tempDir.resolve("config").toString(),
            "XDG_STATE_HOME", tempDir.resolve("state").toString());
    return new ActiveWorkspace(
        new WorkspaceResolver(environment, tempDir, tempDir.resolve("home")));
  }
}
