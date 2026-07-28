package io.github.awesomedog.soma;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.exec.CommandRunner;
import io.github.awesomedog.soma.exec.Invocation;
import io.github.awesomedog.soma.infra.logging.Logging;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.env.Environment;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

public final class SomaApplication {

  private static final String LOOPBACK_HOST = "127.0.0.1";

  private static final Logger LOG = LoggerFactory.getLogger(SomaApplication.class);

  private SomaApplication() {}

  static void main(String[] args) {
    int exitCode;
    try {
      exitCode = run(args);
    } catch (Throwable failure) {
      System.err.println(
          "INTERNAL_ERROR: " + failureMessage(failure, "Soma could not complete the command."));
      System.err.println("Run again with --verbose for diagnostic details.");
      if (verboseRequested(args)) {
        failure.printStackTrace(System.err);
      }
      System.err.flush();
      exitCode = CommandLine.ExitCode.SOFTWARE;
    }
    System.exit(exitCode);
  }

  public static int run(String... args) {
    var console = System.console();
    var invocation = Invocation.cli(console != null && console.isTerminal());
    try {
      final ApplicationContext context;
      try {
        Logging.configure();
        context =
            ApplicationContext.builder()
                .mainClass(SomaApplication.class)
                .environments(Environment.CLI)
                .properties(
                    Map.of(
                        "micronaut.server.host",
                        LOOPBACK_HOST,
                        "micronaut.router.static-resources.web.paths[0]",
                        "classpath:web"))
                .start();
      } catch (RuntimeException e) {
        return reportInternalFailure(
            invocation,
            args,
            "Soma could not start.",
            "Check the installation and workspace permissions, then retry.",
            e);
      }
      Integer commandExitCode = null;
      try (context) {
        commandExitCode = context.getBean(CommandRunner.class).run(args, invocation);
      } catch (RuntimeException failure) {
        if (commandExitCode == null) {
          throw failure;
        }
        LOG.error("Soma shutdown failed", failure);
        if (commandExitCode == CommandLine.ExitCode.OK) {
          return reportInternalFailure(
              invocation,
              args,
              "Soma could not shut down cleanly.",
              "Retry the command in a new Soma process.",
              failure);
        }
      }
      return commandExitCode;
    } finally {
      Logging.close();
    }
  }

  private static int reportInternalFailure(
      Invocation invocation,
      String[] args,
      String message,
      String remediation,
      RuntimeException failure) {
    var error =
        AppError.of(AppError.Code.INTERNAL_ERROR, failureMessage(failure, message), remediation);
    invocation.recordError(error);
    invocation.err().println(error.code() + ": " + error.message());
    invocation.err().println(error.remediation());
    if (verboseRequested(args)) {
      failure.printStackTrace(invocation.err());
    }
    invocation.err().flush();
    LOG.error(message, failure);
    return CommandLine.ExitCode.SOFTWARE;
  }

  private static String failureMessage(Throwable failure, String fallback) {
    var message = failure.getMessage();
    return message == null || message.isBlank() ? fallback : fallback + " " + message;
  }

  private static boolean verboseRequested(String[] args) {
    if ("1".equals(System.getenv("SOMA_VERBOSE"))) {
      return true;
    }
    if (args == null) {
      return false;
    }
    for (var arg : args) {
      if ("--".equals(arg)) {
        return false;
      }
      if ("-v".equals(arg) || "--verbose".equals(arg)) {
        return true;
      }
    }
    return false;
  }
}
