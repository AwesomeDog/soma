package io.github.awesomedog.soma.domain.document;

import java.util.Locale;

public enum FileType {
  TEXT,
  PDF,
  OFFICE,
  EPUB,
  IMAGE,
  AUDIO,
  VIDEO,
  OTHER;

  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
