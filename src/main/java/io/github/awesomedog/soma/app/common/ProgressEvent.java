package io.github.awesomedog.soma.app.common;

import java.util.Objects;

public record ProgressEvent(String message, Long completed, Long total, WorkUnit unit) {

  public ProgressEvent {
    Objects.requireNonNull(message, "message");
    var measured = completed != null || total != null || unit != null;
    if (measured && (completed == null || total == null || unit == null)) {
      throw new IllegalArgumentException(
          "Measured progress must provide completed, total, and unit together.");
    }
    if (measured && (completed < 0 || total < -1)) {
      throw new IllegalArgumentException("Progress values are invalid");
    }
  }

  public static ProgressEvent message(String message) {
    return new ProgressEvent(message, null, null, null);
  }

  public static ProgressEvent update(String message, long completed, long total, WorkUnit unit) {
    return new ProgressEvent(message, completed, total, unit);
  }

  public enum WorkUnit {
    BYTES,
    FILES,
    CHUNKS
  }
}
