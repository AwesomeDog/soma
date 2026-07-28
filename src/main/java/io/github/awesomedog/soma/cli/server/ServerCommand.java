package io.github.awesomedog.soma.cli.server;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.CliCommand;
import io.github.awesomedog.soma.cli.common.HttpOptionsMixin;
import io.github.awesomedog.soma.exec.CommandRunner;
import io.github.awesomedog.soma.exec.Invocation;
import io.micronaut.context.BeanProvider;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.http.server.HttpServerConfiguration;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.scheduling.TaskScheduler;
import jakarta.inject.Inject;
import java.net.BindException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;

@Prototype
@Command(
    name = "server",
    description = "Start the Soma service in HTTP mode.",
    addMethodSubcommands = true)
public final class ServerCommand extends CliCommand {

  private static final Logger LOG = LoggerFactory.getLogger(ServerCommand.class);
  private static final String LOOPBACK_HOST = "127.0.0.1";
  private static final Duration AUTO_SYNC_DELAY = Duration.ofHours(1);
  private static final int MIN_HTTP_PORT = 1;
  private static final int MAX_HTTP_PORT = 65_535;

  @Mixin HttpOptionsMixin http;

  @Inject BeanProvider<HttpServerConfiguration> serverConfiguration;

  @Inject BeanProvider<EmbeddedServer> embeddedServer;

  @Inject BeanProvider<TaskScheduler> taskScheduler;

  @Inject BeanProvider<CommandRunner> commandRunner;

  @Override
  public Integer call() {
    return startHttp(http, spec);
  }

  @Command(name = "http", description = "Start the HTTP service.")
  public int http(@Mixin HttpOptionsMixin options) {
    return startHttp(options, leafSpec());
  }

  private int startHttp(HttpOptionsMixin options, CommandSpec command) {
    var parsed = command.commandLine().getParseResult();
    var port = parsed.hasMatchedOption("--port") ? options.port() : http.port();
    var autoSyncEnabled =
        parsed.hasMatchedOption("--auto-sync") ? options.autoSync() : http.autoSync();

    validatePort(port, command);
    requireInjected();

    var server = configuredServer(port);
    var stopped = new CountDownLatch(1);
    var shutdownHook =
        Thread.ofPlatform()
            .name("soma-http-shutdown")
            .unstarted(() -> stopFromShutdownHook(server, stopped));
    ScheduledFuture<?> autoSyncTask = null;
    var hookInstalled = false;

    try {
      Runtime.getRuntime().addShutdownHook(shutdownHook);
      hookInstalled = true;
      server.start();
      announceServer(server, command);
      autoSyncTask = scheduleAutoSync(autoSyncEnabled);
      awaitShutdown(stopped);
      return CommandLine.ExitCode.OK;
    } catch (RuntimeException e) {
      if (causedByBindException(e)) {
        throw new AppException(
            AppError.Code.OPERATION_FAILED,
            "HTTP port " + port + " is already in use.",
            "Choose a different port with `--port <n>` and retry.",
            e);
      }
      throw e;
    } finally {
      if (autoSyncTask != null) {
        autoSyncTask.cancel(false);
      }
      if (hookInstalled) {
        removeShutdownHook(shutdownHook);
      }
      if (server.isRunning()) {
        server.stop();
      }
      stopped.countDown();
    }
  }

  private EmbeddedServer configuredServer(int port) {
    var configuration = serverConfiguration.get();
    configuration.setHost(LOOPBACK_HOST);
    configuration.setPort(port);
    return embeddedServer.get();
  }

  private static void announceServer(EmbeddedServer server, CommandSpec command) {
    LOG.info("HTTP server listening on {}", server.getURI());
    var err = SomaCommand.invocation(command).err();
    err.printf("Soma HTTP server listening on http://localhost:%d/%n", server.getPort());
    err.flush();
  }

  private ScheduledFuture<?> scheduleAutoSync(boolean enabled) {
    if (!enabled) {
      return null;
    }
    runAutoSync();
    return taskScheduler
        .get()
        .scheduleWithFixedDelay(AUTO_SYNC_DELAY, AUTO_SYNC_DELAY, this::runAutoSync);
  }

  private static void awaitShutdown(CountDownLatch stopped) {
    try {
      stopped.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void runAutoSync() {
    try {
      var exitCode = commandRunner.get().run(new String[] {"sync"}, Invocation.captured());
      if (exitCode == CommandLine.ExitCode.OK) {
        LOG.info("auto-sync finished");
      } else {
        LOG.warn("auto-sync skipped or failed with exit code {}", exitCode);
      }
    } catch (RuntimeException e) {
      LOG.error("auto-sync failed", e);
    }
  }

  private void requireInjected() {
    if (serverConfiguration == null
        || embeddedServer == null
        || taskScheduler == null
        || commandRunner == null) {
      throw new IllegalStateException("server commands must run via CommandRunner");
    }
  }

  private static void validatePort(int port, CommandSpec command) {
    if (port < MIN_HTTP_PORT || port > MAX_HTTP_PORT) {
      throw new CommandLine.ParameterException(
          command.commandLine(),
          "`--port` must be between " + MIN_HTTP_PORT + " and " + MAX_HTTP_PORT + ".");
    }
  }

  private static void stopFromShutdownHook(EmbeddedServer server, CountDownLatch stopped) {
    try {
      if (server.isRunning()) {
        server.stop();
      }
    } catch (RuntimeException e) {
      LOG.error("HTTP server shutdown failed", e);
    } finally {
      stopped.countDown();
    }
  }

  private static void removeShutdownHook(Thread hook) {
    try {
      Runtime.getRuntime().removeShutdownHook(hook);
    } catch (IllegalStateException ignored) {
      // JVM shutdown is already in progress.
    }
  }

  private static boolean causedByBindException(Throwable error) {
    for (var cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof BindException) {
        return true;
      }
    }
    return false;
  }
}
