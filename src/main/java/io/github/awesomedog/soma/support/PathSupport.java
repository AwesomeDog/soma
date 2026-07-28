package io.github.awesomedog.soma.support;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class PathSupport {

  private PathSupport() {}

  public static Path userHomeDirectory() {
    return Path.of(System.getProperty("user.home", ".")).toAbsolutePath().normalize();
  }

  public static Path xdgHome(String environmentVariable, String fallback) {
    return xdgHome(System.getenv(), environmentVariable, userHomeDirectory(), fallback);
  }

  public static Path somaDataDirectory() {
    return xdgHome("XDG_DATA_HOME", ".local/share").resolve("soma");
  }

  public static Path somaStateCacheDirectory() {
    return xdgHome("XDG_STATE_HOME", ".local/state").resolve("soma").resolve("caches");
  }

  public static Path xdgHome(
      Map<String, String> environment,
      String environmentVariable,
      Path userHomeDirectory,
      String fallback) {
    var configured = Objects.requireNonNull(environment, "environment").get(environmentVariable);
    if (configured != null && !configured.isBlank()) {
      var configuredPath = Path.of(configured);
      if (configuredPath.isAbsolute()) {
        return configuredPath.normalize();
      }
    }
    return Objects.requireNonNull(userHomeDirectory, "userHomeDirectory")
        .toAbsolutePath()
        .normalize()
        .resolve(Objects.requireNonNull(fallback, "fallback"));
  }

  public static String normalizePathSeparators(String value) {
    var path = Objects.requireNonNull(value, "value");
    return File.separatorChar == '\\' ? path.replace('\\', '/') : path;
  }

  public static String toPortableString(Path path) {
    var value = Objects.requireNonNull(path, "path").toString();
    return File.separatorChar == '\\' ? value.replace('\\', '/') : value;
  }

  public static Path resolveUserPath(String value) {
    return resolveUserPath(value, userHomeDirectory());
  }

  static Path resolveUserPath(String value, Path userHomeDirectory) {
    Objects.requireNonNull(value, "value");
    var home =
        Objects.requireNonNull(userHomeDirectory, "userHomeDirectory").toAbsolutePath().normalize();
    final Path path;
    if (value.equals("~")) {
      path = home;
    } else if (value.startsWith("~/") || value.startsWith("~\\")) {
      path = home.resolve(value.substring(2));
    } else {
      path = Path.of(value);
    }
    return path.toAbsolutePath().normalize();
  }
}
