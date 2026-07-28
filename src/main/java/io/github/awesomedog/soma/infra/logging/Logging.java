package io.github.awesomedog.soma.infra.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.rolling.FixedWindowRollingPolicy;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.SizeBasedTriggeringPolicy;
import ch.qos.logback.core.util.FileSize;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.slf4j.LoggerFactory;

public final class Logging {

  private static final String APPENDER_NAME = "SOMA_LOG";
  private static Path configuredFile;

  private Logging() {}

  public static synchronized void configure() {
    context().reset();
    configuredFile = null;
  }

  public static synchronized void configure(Path logFile) {
    var normalized = Objects.requireNonNull(logFile, "logFile").toAbsolutePath().normalize();
    var context = context();
    if (normalized.equals(configuredFile)) {
      var appender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(APPENDER_NAME);
      if (appender != null && appender.isStarted()) {
        return;
      }
    }

    context.reset();
    configuredFile = null;
    try {
      Files.createDirectories(normalized.getParent());
      var appender = startedAppender(context, normalized);
      var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
      root.setLevel(Level.INFO);
      root.addAppender(appender);
      configuredFile = normalized;
    } catch (IOException | RuntimeException e) {
      context.reset();
      System.err.println("Warning: Could not configure Soma logging: " + e.getMessage());
    }
  }

  private static RollingFileAppender<ILoggingEvent> startedAppender(
      LoggerContext context, Path logFile) {
    var encoder = new PatternLayoutEncoder();
    encoder.setContext(context);
    encoder.setPattern(
        "%date{ISO8601} %-5level [run=%X{run} ws=%X{ws} cmd=%X{cmd}] %logger - %msg%n");
    encoder.start();

    var appender = new RollingFileAppender<ILoggingEvent>();
    appender.setContext(context);
    appender.setName(APPENDER_NAME);
    appender.setFile(logFile.toString());
    appender.setAppend(true);
    appender.setImmediateFlush(true);
    appender.setEncoder(encoder);

    var rollingPolicy = new FixedWindowRollingPolicy();
    rollingPolicy.setContext(context);
    rollingPolicy.setParent(appender);
    rollingPolicy.setFileNamePattern(logFile + ".%i.gz");
    rollingPolicy.setMinIndex(1);
    rollingPolicy.setMaxIndex(3);
    rollingPolicy.start();

    var triggeringPolicy = new SizeBasedTriggeringPolicy<ILoggingEvent>();
    triggeringPolicy.setContext(context);
    triggeringPolicy.setMaxFileSize(FileSize.valueOf("10MB"));
    triggeringPolicy.start();

    appender.setRollingPolicy(rollingPolicy);
    appender.setTriggeringPolicy(triggeringPolicy);
    appender.start();
    if (!appender.isStarted()) {
      throw new IllegalStateException("workspace log appender did not start");
    }
    return appender;
  }

  public static synchronized void close() {
    context().reset();
    configuredFile = null;
  }

  private static LoggerContext context() {
    return (LoggerContext) LoggerFactory.getILoggerFactory();
  }
}
