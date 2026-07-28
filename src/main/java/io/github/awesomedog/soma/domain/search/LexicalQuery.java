package io.github.awesomedog.soma.domain.search;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

public record LexicalQuery(List<Clause> clauses) {

  public LexicalQuery {
    clauses = clauses == null ? List.of() : List.copyOf(clauses);
    if (clauses.stream().allMatch(Clause::excluded)) {
      throw new IllegalArgumentException(
          "Lexical query must contain a positive searchable term or phrase");
    }
  }

  public static LexicalQuery parse(String input) {
    var normalized = Normalizer.normalize(input == null ? "" : input.strip(), Normalizer.Form.NFKC);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException("Lexical query must not be empty");
    }

    var clauses = new ArrayList<Clause>();
    for (var cursor = 0; cursor < normalized.length(); ) {
      while (cursor < normalized.length() && Character.isWhitespace(normalized.charAt(cursor))) {
        cursor++;
      }
      if (cursor == normalized.length()) {
        break;
      }

      var excluded = normalized.charAt(cursor) == '-';
      if (excluded) {
        cursor++;
        if (cursor == normalized.length() || Character.isWhitespace(normalized.charAt(cursor))) {
          throw new IllegalArgumentException(
              "Exclusion marker must directly precede a word or quoted phrase");
        }
        if (normalized.charAt(cursor) == '-') {
          throw new IllegalArgumentException("Query clauses must not start with '--'");
        }
      }

      var phrase = normalized.charAt(cursor) == '"';
      final String text;
      if (phrase) {
        var closingQuote = normalized.indexOf('"', cursor + 1);
        if (closingQuote < 0) {
          throw new IllegalArgumentException("Unterminated quoted phrase");
        }
        text = normalized.substring(cursor + 1, closingQuote).strip();
        cursor = closingQuote + 1;
        if (cursor < normalized.length() && !Character.isWhitespace(normalized.charAt(cursor))) {
          throw new IllegalArgumentException(
              "Quoted phrase must be separated from the next query clause");
        }
        if (text.isEmpty()) {
          throw new IllegalArgumentException("Quoted phrase must not be empty");
        }
      } else {
        var start = cursor;
        while (cursor < normalized.length() && !Character.isWhitespace(normalized.charAt(cursor))) {
          cursor++;
        }
        text = normalized.substring(start, cursor);
        if (text.indexOf('"') >= 0) {
          throw new IllegalArgumentException("Quote must begin a query clause");
        }
      }

      if (LexicalProjector.tokens(text).isEmpty()) {
        throw new IllegalArgumentException("Lexical query clause must contain searchable text");
      }
      clauses.add(new Clause(text, phrase, excluded));
    }
    return new LexicalQuery(clauses);
  }

  public record Clause(String text, boolean phrase, boolean excluded) {}
}
