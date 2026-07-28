package io.github.awesomedog.soma.domain.document;

import io.github.awesomedog.soma.support.PathSupport;
import java.io.File;

public record DocumentPath(String value) {

  public DocumentPath {
    value = PathSupport.normalizePathSeparators(value);
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Document path must not be empty");
    }
    if (value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Document path must not contain NUL");
    }
    if (File.separatorChar == '\\'
        && value.length() >= 2
        && Character.isLetter(value.charAt(0))
        && value.charAt(1) == ':') {
      throw new IllegalArgumentException("Document path must not include a Windows drive");
    }
    if (value.startsWith("/") || value.endsWith("/")) {
      throw new IllegalArgumentException("Document path must identify a relative file");
    }
    for (var segment : value.split("/", -1)) {
      if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
        throw new IllegalArgumentException("Document path contains an invalid segment");
      }
    }
  }
}
