package io.github.awesomedog.soma.exec;

import static io.github.awesomedog.soma.app.common.AppError.Code.INTERNAL_ERROR;
import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.infra.logging.Logging;
import io.micronaut.configuration.picocli.MicronautFactory;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.propagation.slf4j.MdcPropagationContext;
import io.micronaut.core.propagation.PropagatedContext;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import picocli.CommandLine;

@Singleton
public final class CommandRunner {

  private static final Logger LOG = LoggerFactory.getLogger(CommandRunner.class);
  private static final int RUN_ID_HEX_LENGTH = 8;
  private static final int PARTIAL_SUCCESS_EXIT_CODE = 3;

  private final ApplicationContext applicationContext;
  private final ActiveWorkspace activeWorkspace;

  public CommandRunner(ApplicationContext applicationContext, ActiveWorkspace activeWorkspace) {
    this.applicationContext = Objects.requireNonNull(applicationContext, "applicationContext");
    this.activeWorkspace = Objects.requireNonNull(activeWorkspace, "activeWorkspace");
  }

  public int run(String[] arguments, Invocation invocation) {
    Objects.requireNonNull(arguments, "arguments");
    Objects.requireNonNull(invocation, "invocation");

    var rootCommand = new SomaCommand(invocation);
    try {
      var commandLine =
          new CommandLine(rootCommand, new MicronautFactory(applicationContext))
              .setExpandAtFiles(false)
              .setOut(invocation.out())
              .setErr(invocation.err());
      var commandExecutor = new CommandLine.RunLast();
      commandLine.setExecutionStrategy(
          parseResult ->
              executeParsedCommand(
                  parseResult, commandExecutor, rootCommand, invocation, commandLine));
      commandLine.setParameterExceptionHandler(
          (parameterException, ignored) -> {
            configureAnsi(commandLine, invocation.isTty() && !rootCommand.noColor());
            return handleParameterFailure(parameterException, invocation);
          });
      return commandLine.execute(arguments);
    } catch (Exception e) {
      return handleExecutionFailure(e, rootCommand, invocation);
    }
  }

  private int executeParsedCommand(
      CommandLine.ParseResult parseResult,
      CommandLine.RunLast commandExecutor,
      SomaCommand rootCommand,
      Invocation invocation,
      CommandLine commandLine) {
    try {
      configureAnsi(commandLine, invocation.isTty() && !rootCommand.noColor());
      if (isVersionRequested(parseResult)) {
        commandLine.printVersionHelp(invocation.out());
        return CommandLine.ExitCode.OK;
      }
      if (isHelpRequested(parseResult)) {
        return commandExecutor.execute(parseResult);
      }

      if (isInitCommand(parseResult)) {
        if (rootCommand.requestedWorkspaceName() != null) {
          invocation
              .err()
              .println(
                  "The `--workspace` option has no effect on `soma init` (a directory-local workspace is always created). Continuing without it.");
        }
        activeWorkspace.selectWorkspaceForInit();
      } else {
        activeWorkspace.selectWorkspace(rootCommand.requestedWorkspaceName());
      }
      Logging.configure(activeWorkspace.logFile());

      var commandName = commandName(parseResult);
      var runId = UUID.randomUUID().toString().substring(0, RUN_ID_HEX_LENGTH);
      return commandContext(runId, activeWorkspace.workspaceName(), commandName)
          .propagate(
              () -> {
                LOG.info("command started");
                var exitCode = commandExecutor.execute(parseResult);
                invocation.finishProgress();
                recordMissingError(exitCode, invocation);
                LOG.info("command finished with exit code {}", exitCode);
                return exitCode;
              });
    } catch (Exception e) {
      return handleExecutionFailure(e, rootCommand, invocation);
    }
  }

  private int handleParameterFailure(
      CommandLine.ParameterException exception, Invocation invocation) {
    invocation.finishProgress();
    var message = messageOrFallback(exception, "Invalid command arguments.");
    invocation.recordError(AppError.of(INVALID_REQUEST, message, null));

    var failedCommand = exception.getCommandLine();
    failedCommand
        .getErr()
        .println(failedCommand.getColorScheme().errorText(INVALID_REQUEST + ": " + message));
    failedCommand.usage(failedCommand.getErr(), failedCommand.getColorScheme());
    failedCommand.getErr().flush();
    return CommandLine.ExitCode.USAGE;
  }

  private int handleExecutionFailure(
      Exception exception, SomaCommand rootCommand, Invocation invocation) {
    invocation.finishProgress();
    var unwrappedFailure = unwrapExecutionFailure(exception);
    if (unwrappedFailure instanceof CommandLine.ParameterException parameterException) {
      return handleParameterFailure(parameterException, invocation);
    }

    var appException = unwrappedFailure instanceof AppException value ? value : null;
    var appError =
        appException == null
            ? AppError.of(
                INTERNAL_ERROR,
                messageOrFallback(unwrappedFailure, "Soma could not complete the command."),
                "Fix the reported problem, or run again with --verbose for diagnostic details.")
            : appException.error();
    recordAndRender(appError, invocation);
    if (rootCommand.verbose()) {
      unwrappedFailure.printStackTrace(invocation.err());
    }
    invocation.err().flush();
    LOG.error("command failed with {}", appError.code(), unwrappedFailure);
    return exitCode(appError);
  }

  public static int exitCode(AppError error) {
    return error.code() == INVALID_REQUEST
        ? CommandLine.ExitCode.USAGE
        : CommandLine.ExitCode.SOFTWARE;
  }

  private static Exception unwrapExecutionFailure(Exception exception) {
    var currentFailure = exception;
    while (currentFailure instanceof CommandLine.ExecutionException
        && currentFailure.getCause() instanceof Exception cause) {
      currentFailure = cause;
    }
    return currentFailure;
  }

  private static String messageOrFallback(Exception exception, String fallback) {
    var message = exception.getMessage();
    return message == null || message.isBlank() ? fallback : message;
  }

  private static void recordMissingError(int exitCode, Invocation invocation) {
    if (exitCode != CommandLine.ExitCode.OK
        && exitCode != PARTIAL_SUCCESS_EXIT_CODE
        && invocation.result() == null
        && invocation.recordedError() == null) {
      recordAndRender(
          AppError.of(
              INTERNAL_ERROR,
              "Command failed without structured error details.",
              "Run again with --verbose for diagnostic details."),
          invocation);
    }
  }

  private static void recordAndRender(AppError error, Invocation invocation) {
    invocation.recordError(error);
    invocation.err().println(error.code() + ": " + error.message());
    if (error.remediation() != null && !error.remediation().isBlank()) {
      invocation.err().println(error.remediation());
    }
    invocation.err().flush();
  }

  private static boolean isHelpRequested(CommandLine.ParseResult parseResult) {
    return parseResult.isUsageHelpRequested()
        || (parseResult.subcommand() != null && isHelpRequested(parseResult.subcommand()));
  }

  private static boolean isVersionRequested(CommandLine.ParseResult parseResult) {
    return parseResult.isVersionHelpRequested()
        || (parseResult.subcommand() != null && isVersionRequested(parseResult.subcommand()));
  }

  private static boolean isInitCommand(CommandLine.ParseResult parseResult) {
    return parseResult.subcommand() != null
        && "init".equals(parseResult.subcommand().commandSpec().name());
  }

  private static String commandName(CommandLine.ParseResult parseResult) {
    return parseResult.asCommandLineList().stream()
        .map(CommandLine::getCommandName)
        .reduce((left, right) -> left + " " + right)
        .orElse("soma");
  }

  private static void configureAnsi(CommandLine commandLine, boolean enabled) {
    var ansi = enabled ? CommandLine.Help.Ansi.ON : CommandLine.Help.Ansi.OFF;
    commandLine.setColorScheme(CommandLine.Help.defaultColorScheme(ansi));
  }

  private static PropagatedContext commandContext(
      String runId, String workspaceName, String commandName) {
    var state = new HashMap<String, String>();
    var currentState = MDC.getCopyOfContextMap();
    if (currentState != null) {
      state.putAll(currentState);
    }
    state.put("run", runId);
    state.put("ws", workspaceName);
    state.put("cmd", commandName);

    var mdc = new MdcPropagationContext(state);
    var context = PropagatedContext.getOrEmpty();
    var existing = context.find(MdcPropagationContext.class);
    return existing.isPresent() ? context.replace(existing.get(), mdc) : context.plus(mdc);
  }
}
