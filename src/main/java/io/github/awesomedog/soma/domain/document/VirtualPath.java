package io.github.awesomedog.soma.domain.document;

import io.github.awesomedog.soma.support.PathSupport;
import jakarta.annotation.Nonnull;
import java.util.Objects;

public record VirtualPath(String project, String path) {

  public static final String SCHEME = "soma://";

  public VirtualPath {
    project = Objects.requireNonNull(project, "project");
    path = PathSupport.normalizePathSeparators(Objects.requireNonNull(path, "path"));
  }

  public static Input parseInput(String input) {
    var value = Objects.requireNonNull(input, "input");
    var explicit = value.startsWith(SCHEME);
    if (explicit) {
      value = value.substring(SCHEME.length());
    }
    value = PathSupport.normalizePathSeparators(value);
    var separator = value.indexOf('/');
    return new Input(
        explicit,
        separator < 0 ? value : value.substring(0, separator),
        separator < 0 ? "" : value.substring(separator + 1),
        separator >= 0);
  }

  @Nonnull
  @Override
  public String toString() {
    return SCHEME + project + "/" + path;
  }

  public record Input(boolean explicit, String project, String path, boolean hasPathSeparator) {}
}
