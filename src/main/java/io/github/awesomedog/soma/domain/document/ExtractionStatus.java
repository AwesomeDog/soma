package io.github.awesomedog.soma.domain.document;

import java.util.Locale;

public enum ExtractionStatus {
  READY,
  PENDING,
  FAILED;

  public String value() {
    return name().toLowerCase(Locale.ROOT);
  }
}
