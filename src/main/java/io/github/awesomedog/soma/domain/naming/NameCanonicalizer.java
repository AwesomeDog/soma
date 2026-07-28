package io.github.awesomedog.soma.domain.naming;

import java.text.Normalizer;
import java.util.Objects;

public final class NameCanonicalizer {

  private NameCanonicalizer() {}

  public static String canonicalize(String input) {
    Objects.requireNonNull(input, "input");
    var normalized = Normalizer.normalize(input, Normalizer.Form.NFKC);
    var canonical = new StringBuilder();
    var separatorPending = false;
    for (var offset = 0; offset < normalized.length(); ) {
      var codePoint = normalized.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-') {
        if (separatorPending && !canonical.isEmpty()) {
          canonical.append('-');
        }
        canonical.appendCodePoint(codePoint);
        separatorPending = false;
      } else {
        separatorPending = true;
      }
    }

    var start = 0;
    var end = canonical.length();
    while (start < end && trimCharacter(canonical.charAt(start))) {
      start++;
    }
    while (end > start && trimCharacter(canonical.charAt(end - 1))) {
      end--;
    }
    return canonical.substring(start, end);
  }

  private static boolean trimCharacter(char value) {
    return value == '-' || value == '_';
  }
}
