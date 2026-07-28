package io.github.awesomedog.soma.http;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.exec.CommandRunner;
import io.github.awesomedog.soma.exec.Invocation;
import jakarta.inject.Singleton;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@Singleton
public final class RpcRunner {

  private static final Logger LOG = LoggerFactory.getLogger(RpcRunner.class);

  private final CommandRunner commandRunner;

  public RpcRunner(CommandRunner commandRunner) {
    this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner");
  }

  public RunResponse run(RunRequest request, Set<String> allowedCommands) {
    var startNanos = System.nanoTime();
    var requestId = UUID.randomUUID().toString();
    var invocation = Invocation.captured();

    try {
      if (request == null) {
        throw new AppException(invalidRequest("Request body is required."));
      }
      var arguments = RunRequestMapper.toCommandArguments(request, allowedCommands);
      var exitCode = commandRunner.run(arguments, invocation);
      return response(requestId, startNanos, exitCode, invocation, invocation.recordedError());
    } catch (AppException e) {
      return response(
          requestId, startNanos, CommandRunner.exitCode(e.error()), invocation, e.error());
    } catch (Exception e) {
      LOG.error("RPC request failed", e);
      return response(
          requestId, startNanos, CommandLine.ExitCode.SOFTWARE, invocation, internalError(e));
    }
  }

  public static RunResponse failure(AppError error) {
    var failure = Objects.requireNonNull(error, "error");
    return new RunResponse(
        false,
        UUID.randomUUID().toString(),
        0,
        CommandRunner.exitCode(failure),
        null,
        "",
        "",
        failure);
  }

  public static AppError invalidRequest(String message) {
    return AppError.of(AppError.Code.INVALID_REQUEST, message, null);
  }

  public static AppError internalError(Exception failure) {
    return AppError.of(
        AppError.Code.INTERNAL_ERROR,
        "Soma could not complete the request. " + failure,
        "Retry the request or inspect the Soma logs.");
  }

  private static RunResponse response(
      String requestId, long startNanos, int exitCode, Invocation invocation, AppError error) {
    return new RunResponse(
        error == null,
        requestId,
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
        exitCode,
        invocation.result(),
        invocation.stdout(),
        invocation.stderr(),
        error);
  }
}
