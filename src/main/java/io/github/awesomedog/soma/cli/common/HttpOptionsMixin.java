package io.github.awesomedog.soma.cli.common;

import picocli.CommandLine.Option;

public final class HttpOptionsMixin {

  private static final String DEFAULT_HTTP_PORT = "8181";

  @Option(
      names = "--port",
      paramLabel = "<n>",
      defaultValue = DEFAULT_HTTP_PORT,
      description = "HTTP port, 1-65535 (default: ${DEFAULT-VALUE}).")
  int port;

  @Option(
      names = "--auto-sync",
      arity = "0",
      fallbackValue = "true",
      description = {
        "Sync on startup and hourly.",
        "Busy or overlapping runs are skipped; failures are logged."
      })
  boolean autoSync;

  public int port() {
    return port;
  }

  public boolean autoSync() {
    return autoSync;
  }
}
