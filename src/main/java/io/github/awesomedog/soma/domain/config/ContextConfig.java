package io.github.awesomedog.soma.domain.config;

import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.support.PathSupport;

public record ContextConfig(ProjectName project, String path, String text) {

  public ContextConfig {
    path = normalizeAndValidatePath(path);
    if (text == null || text.isBlank()) {
      throw new IllegalArgumentException("Context text is required");
    }
  }

  public static String normalizeAndValidatePath(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Context path is required");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Context path must not contain NUL");
    }
    value = PathSupport.normalizePathSeparators(value);
    if (value.indexOf('\\') >= 0) {
      throw new IllegalArgumentException("Context path must use '/' separators");
    }
    if (!value.startsWith("/")) {
      throw new IllegalArgumentException("Context path must start with '/'");
    }
    if (value.length() > 1 && value.endsWith("/")) {
      throw new IllegalArgumentException("Context path must not end with '/'");
    }
    if (!value.equals("/")) {
      for (var segment : value.substring(1).split("/", -1)) {
        if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
          throw new IllegalArgumentException("Context path contains an invalid segment");
        }
      }
    }
    return value;
  }
}
