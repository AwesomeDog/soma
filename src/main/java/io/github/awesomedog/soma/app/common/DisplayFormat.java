package io.github.awesomedog.soma.app.common;

import java.util.List;
import java.util.Locale;

public final class DisplayFormat {

  private static final long BYTE_UNIT = 1024L;
  private static final String[] BYTE_UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

  private DisplayFormat() {}

  public static String bytes(long bytes) {
    if (bytes < 0) {
      return "unknown";
    }
    if (bytes < BYTE_UNIT) {
      return bytes + " B";
    }
    double value = bytes;
    int unit = 0;
    while (value >= BYTE_UNIT && unit < BYTE_UNITS.length - 1) {
      value /= BYTE_UNIT;
      unit++;
    }
    return String.format(Locale.ROOT, "%.1f %s", value, BYTE_UNITS[unit]);
  }

  public static String duration(long millis) {
    var safeMillis = Math.max(0L, millis);
    if (safeMillis < 1_000L) {
      return safeMillis + "ms";
    }
    var seconds = Math.round(safeMillis / 1_000.0);
    if (seconds < 60L) {
      return safeMillis < 10_000L
          ? String.format(Locale.ROOT, "%.1fs", safeMillis / 1_000.0)
          : seconds + "s";
    }
    if (seconds < 3_600L) {
      return (seconds / 60L) + "m " + (seconds % 60L) + "s";
    }
    return (seconds / 3_600L) + "h " + ((seconds % 3_600L) / 60L) + "m";
  }

  public static String score(double value) {
    return String.format(Locale.ROOT, "%.4f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
  }

  public static String csvCell(String value) {
    var text = value == null ? "" : value;
    return '"' + text.replace("\"", "\"\"") + '"';
  }

  public static String lineNumbers(String text, int startLine) {
    if (text == null || text.isEmpty()) {
      return text == null ? "" : text;
    }
    return lineNumbers(java.util.Arrays.asList(text.split("\\R", -1)), startLine);
  }

  public static String lineNumbers(List<String> lines, int startLine) {
    var numbered = new StringBuilder();
    for (var index = 0; index < lines.size(); index++) {
      if (index > 0) {
        numbered.append('\n');
      }
      numbered.append(startLine + index).append(": ").append(lines.get(index));
    }
    return numbered.toString();
  }
}
