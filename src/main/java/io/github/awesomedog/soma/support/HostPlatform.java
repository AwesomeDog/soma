package io.github.awesomedog.soma.support;

import java.util.Locale;
import java.util.Objects;

public record HostPlatform(OperatingSystem operatingSystem, String architecture) {

  public HostPlatform {
    Objects.requireNonNull(operatingSystem, "operatingSystem");
    Objects.requireNonNull(architecture, "architecture");
  }

  public static HostPlatform current() {
    return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
  }

  public static HostPlatform detect(String osName, String architectureName) {
    var os = normalized(osName);
    var operatingSystem =
        os.contains("mac") || os.contains("darwin")
            ? OperatingSystem.DARWIN
            : os.contains("linux")
                ? OperatingSystem.LINUX
                : os.contains("win") ? OperatingSystem.WINDOWS : OperatingSystem.UNKNOWN;
    var architecture = normalized(architectureName);
    var canonicalArchitecture =
        architecture.equals("aarch64") || architecture.equals("arm64")
            ? "arm64"
            : architecture.equals("x86_64") || architecture.equals("amd64") ? "x86_64" : "";
    return new HostPlatform(operatingSystem, canonicalArchitecture);
  }

  public String id() {
    return operatingSystem.id() + "-" + architecture;
  }

  public boolean isWindows() {
    return operatingSystem == OperatingSystem.WINDOWS;
  }

  private static String normalized(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT);
  }

  public enum OperatingSystem {
    DARWIN("darwin"),
    LINUX("linux"),
    WINDOWS("windows"),
    UNKNOWN("");

    private final String id;

    OperatingSystem(String id) {
      this.id = id;
    }

    public String id() {
      return id;
    }
  }
}
