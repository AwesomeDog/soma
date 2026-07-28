package io.github.awesomedog.soma.domain.search;

import io.github.awesomedog.soma.domain.recipe.RecipeId;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class LexicalProjector {

  private static final int KATAKANA_HIRAGANA_PROLONGED_SOUND_MARK = 0x30fc;

  private LexicalProjector() {}

  public static String recipeId() {
    return RecipeId.of(
        "lexical.projection",
        "v1",
        "normalization=NFKC",
        "case=lower-root",
        "identifiers=case-and-letter-digit-boundaries",
        "cjk=unigram-bigram-trigram",
        "fts=porter-unicode61");
  }

  public static String toProjection(String text) {
    return String.join(" ", tokens(text));
  }

  public static List<String> tokens(String raw) {
    var tokens = new ArrayList<String>();
    var word = new StringBuilder();
    var cjk = new StringBuilder();
    var normalized = normalize(raw);
    for (var offset = 0; offset < normalized.length(); ) {
      var codePoint = normalized.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (isCjkRunCharacter(codePoint)) {
        flushWord(tokens, word);
        cjk.appendCodePoint(codePoint);
      } else if (isMark(codePoint)) {
        if (!word.isEmpty()) {
          word.appendCodePoint(codePoint);
        } else if (!cjk.isEmpty()) {
          cjk.appendCodePoint(codePoint);
        }
      } else if (Character.isLetterOrDigit(codePoint)) {
        flushCjk(tokens, cjk);
        word.appendCodePoint(codePoint);
      } else {
        flushWord(tokens, word);
        flushCjk(tokens, cjk);
      }
    }
    flushWord(tokens, word);
    flushCjk(tokens, cjk);
    return List.copyOf(tokens);
  }

  public static boolean containsCjk(String text) {
    return normalize(text).codePoints().anyMatch(LexicalProjector::isCjkRunCharacter);
  }

  private static void addCjkNgrams(List<String> tokens, String raw) {
    var units = characterUnits(raw);
    tokens.addAll(units);
    for (var unitPosition = 0; unitPosition + 1 < units.size(); unitPosition++) {
      tokens.add(units.get(unitPosition) + units.get(unitPosition + 1));
    }
    for (var unitPosition = 0; unitPosition + 2 < units.size(); unitPosition++) {
      tokens.add(
          units.get(unitPosition) + units.get(unitPosition + 1) + units.get(unitPosition + 2));
    }
  }

  private static void flushWord(List<String> tokens, StringBuilder word) {
    if (word.isEmpty()) {
      return;
    }
    var codePoints = word.toString().codePoints().toArray();
    var start = 0;
    for (var codePointPosition = 1; codePointPosition < codePoints.length; codePointPosition++) {
      if (identifierBoundary(codePoints, codePointPosition)) {
        addToken(tokens, codePoints, start, codePointPosition);
        start = codePointPosition;
      }
    }
    addToken(tokens, codePoints, start, codePoints.length);
    word.setLength(0);
  }

  private static boolean identifierBoundary(int[] codePoints, int codePointPosition) {
    var current = codePoints[codePointPosition];
    if (isMark(current)) {
      return false;
    }
    var previousIndex = codePointPosition - 1;
    while (previousIndex >= 0 && isMark(codePoints[previousIndex])) {
      previousIndex--;
    }
    if (previousIndex < 0) {
      return false;
    }
    var nextIndex = codePointPosition + 1;
    while (nextIndex < codePoints.length && isMark(codePoints[nextIndex])) {
      nextIndex++;
    }
    var previous = codePoints[previousIndex];
    if (Character.isDigit(previous) != Character.isDigit(current)) {
      return true;
    }
    if (Character.isLowerCase(previous) && Character.isUpperCase(current)) {
      return true;
    }
    return Character.isUpperCase(previous)
        && Character.isUpperCase(current)
        && nextIndex < codePoints.length
        && Character.isLowerCase(codePoints[nextIndex]);
  }

  private static void addToken(List<String> tokens, int[] codePoints, int start, int end) {
    tokens.add(new String(codePoints, start, end - start).toLowerCase(Locale.ROOT));
  }

  private static void flushCjk(List<String> tokens, StringBuilder cjk) {
    if (cjk.isEmpty()) {
      return;
    }
    addCjkNgrams(tokens, cjk.toString());
    cjk.setLength(0);
  }

  private static boolean isCjkRunCharacter(int codePoint) {
    var script = Character.UnicodeScript.of(codePoint);
    return script == Character.UnicodeScript.HAN
        || script == Character.UnicodeScript.HIRAGANA
        || script == Character.UnicodeScript.KATAKANA
        || script == Character.UnicodeScript.HANGUL
        || codePoint == KATAKANA_HIRAGANA_PROLONGED_SOUND_MARK;
  }

  private static boolean isMark(int codePoint) {
    return switch (Character.getType(codePoint)) {
      case Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK ->
          true;
      default -> false;
    };
  }

  private static List<String> characterUnits(String value) {
    var units = new ArrayList<StringBuilder>();
    for (var offset = 0; offset < value.length(); ) {
      var codePoint = value.codePointAt(offset);
      offset += Character.charCount(codePoint);
      if (isMark(codePoint) && !units.isEmpty()) {
        units.getLast().appendCodePoint(codePoint);
      } else {
        units.add(new StringBuilder().appendCodePoint(codePoint));
      }
    }
    return units.stream().map(StringBuilder::toString).toList();
  }

  private static String normalize(String value) {
    return Normalizer.normalize(Objects.requireNonNull(value, "value"), Normalizer.Form.NFKC);
  }
}
