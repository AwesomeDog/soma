package io.github.awesomedog.soma.app.search;

import static io.github.awesomedog.soma.app.search.DocumentSearch.hasText;
import static io.github.awesomedog.soma.app.search.DocumentSearch.nullToEmpty;

import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.domain.search.LexicalQuery;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

final class SearchResultAssembler {

  private static final int SNIPPET_LINE_COUNT = 3;
  private static final int MAX_SNIPPET_CHARS = 500;
  private static final String TRUNCATED_SNIPPET_SUFFIX = "...";

  private SearchResultAssembler() {}

  static List<DocumentSearch.Item> assembleResults(
      List<DocumentSearch.Match> matches, String resolvedQuery, DocumentSearch.Request request) {
    var selected =
        request.unlimited()
            ? matches
            : matches.subList(0, Math.min(request.resultLimit(), matches.size()));
    var items = new ArrayList<DocumentSearch.Item>(selected.size());
    for (var match : selected) {
      items.add(createItem(match, resolvedQuery, request));
    }
    return List.copyOf(items);
  }

  private static DocumentSearch.Item createItem(
      DocumentSearch.Match match, String resolvedQuery, DocumentSearch.Request request) {
    var hit = match.hit();
    if (request.pathOnly()) {
      return item(hit, match.context(), "", null, null);
    }

    var snippetSource =
        hasText(hit.evidenceBody()) ? hit.evidenceBody() : nullToEmpty(hit.documentBody());
    var snippet = buildSnippet(snippetSource, resolvedQuery, request.intent(), match.context());
    var evidenceStartLine =
        hit.evidenceStartOffset() == null
            ? 1
            : findLineNumberAtOffset(hit.documentBody(), hit.evidenceStartOffset());
    var snippetStartLine = evidenceStartLine + snippet.startLine() - 1;
    var matchLine = evidenceStartLine + snippet.matchLine() - 1;
    if (request.fullDocuments()) {
      return item(
          hit,
          match.context(),
          "",
          request.includeLineNumbers()
              ? DisplayFormat.lineNumbers(hit.documentBody(), 1)
              : nullToEmpty(hit.documentBody()),
          matchLine);
    }

    return item(
        hit,
        match.context(),
        request.includeLineNumbers()
            ? DisplayFormat.lineNumbers(snippet.text(), snippetStartLine)
            : snippet.text(),
        null,
        matchLine);
  }

  private static DocumentSearch.Item item(
      io.github.awesomedog.soma.app.ports.WorkspaceIndex.SearchHit hit,
      String context,
      String snippet,
      String body,
      Integer line) {
    return new DocumentSearch.Item(
        nullToEmpty(io.github.awesomedog.soma.app.ports.WorkspaceIndex.docId(hit.contentHash())),
        hit.virtualPath(),
        hit.title(),
        hit.score(),
        snippet,
        body,
        line,
        context);
  }

  private static Snippet buildSnippet(
      String sourceBody, String query, String intent, String context) {
    if (!hasText(sourceBody)) {
      return new Snippet(1, 1, "");
    }
    var queryTerms = positiveSearchTerms(query);
    var intentTerms = searchTerms(intent);
    var contextTerms = searchTerms(context);
    var lines = sourceBody.split("\\R", -1);
    var bestLine = 0;
    var bestScore = 0.0;
    for (var index = 0; index < lines.length; index++) {
      var normalizedLine = normalize(lines[index]);
      var score =
          matchingTermScore(normalizedLine, queryTerms, 1.0)
              + matchingTermScore(normalizedLine, intentTerms, 0.3)
              + matchingTermScore(normalizedLine, contextTerms, 0.15);
      if (score > bestScore) {
        bestScore = score;
        bestLine = index;
      }
    }
    var start = Math.max(0, bestLine - 1);
    var end = Math.min(lines.length, start + SNIPPET_LINE_COUNT);
    var text = String.join("\n", Arrays.copyOfRange(lines, start, end));
    if (text.length() > MAX_SNIPPET_CHARS) {
      text =
          text.substring(
                  0, safeBoundary(text, MAX_SNIPPET_CHARS - TRUNCATED_SNIPPET_SUFFIX.length()))
              + TRUNCATED_SNIPPET_SUFFIX;
    }
    return new Snippet(start + 1, bestLine + 1, text);
  }

  private static double matchingTermScore(
      String normalizedLine, List<String> terms, double weight) {
    return terms.stream().filter(normalizedLine::contains).count() * weight;
  }

  static List<String> positiveSearchTerms(String query) {
    if (!hasText(query)) {
      return List.of();
    }
    try {
      return LexicalQuery.parse(query).clauses().stream()
          .filter(clause -> !clause.excluded())
          .flatMap(clause -> searchTerms(clause.text()).stream())
          .toList();
    } catch (IllegalArgumentException ignored) {
      return searchTerms(query);
    }
  }

  static List<String> searchTerms(String text) {
    if (!hasText(text)) {
      return List.of();
    }
    return Arrays.stream(normalize(text).split("\\s+"))
        .map(term -> term.replace("\"", "").replace("-", " "))
        .flatMap(term -> Arrays.stream(term.split("\\s+")))
        .filter(term -> !term.isBlank())
        .toList();
  }

  private static int findLineNumberAtOffset(String body, int offset) {
    if (body == null || offset <= 0) {
      return 1;
    }
    var line = 1;
    for (var index = 0; index < Math.min(offset, body.length()); index++) {
      if (body.charAt(index) == '\n') {
        line++;
      }
    }
    return line;
  }

  private static int safeBoundary(String text, int requested) {
    var boundary = Math.max(0, Math.min(requested, text.length()));
    if (boundary > 0
        && boundary < text.length()
        && Character.isHighSurrogate(text.charAt(boundary - 1))
        && Character.isLowSurrogate(text.charAt(boundary))) {
      boundary--;
    }
    return boundary;
  }

  static String normalize(String text) {
    return Normalizer.normalize(nullToEmpty(text), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  private record Snippet(int startLine, int matchLine, String text) {}
}
