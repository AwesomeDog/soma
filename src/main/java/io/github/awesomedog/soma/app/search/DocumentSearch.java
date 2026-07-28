package io.github.awesomedog.soma.app.search;

import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static io.github.awesomedog.soma.app.common.Renderable.renderJson;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.Renderable;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.project.ProjectSelection;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@Singleton
public final class DocumentSearch {

  private final ConfigStore configStore;
  private final CandidateRetriever candidateRetriever;
  private final HybridSearch hybrid;

  public DocumentSearch(
      ConfigStore configStore, WorkspaceIndex workspaceIndex, SearchModels searchModels) {
    this.configStore = Objects.requireNonNull(configStore, "configStore");
    var checkedWorkspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
    var checkedSearchModels = Objects.requireNonNull(searchModels, "searchModels");
    this.candidateRetriever = new CandidateRetriever(checkedWorkspaceIndex, checkedSearchModels);
    this.hybrid = new HybridSearch(candidateRetriever, checkedWorkspaceIndex, checkedSearchModels);
  }

  public Result search(
      Path configFile, Path databaseFile, Request request, Consumer<String> progressConsumer) {
    Objects.requireNonNull(configFile, "configFile");
    Objects.requireNonNull(databaseFile, "databaseFile");
    Objects.requireNonNull(request, "request");
    validate(request);
    if (request.searchMode() != Mode.vector && hasText(request.query())) {
      candidateRetriever.parseLexical(request.query(), false);
    }
    if (request.searchMode() == Mode.hybrid && hasText(request.lexicalInput())) {
      candidateRetriever.parseLexical(request.lexicalInput(), false);
    }

    var progress = progressConsumer == null ? (Consumer<String>) ignored -> {} : progressConsumer;
    var config = configStore.load(configFile);
    var projects =
        request.projectScope().isEmpty()
            ? config.defaultSearchProjects()
            : ProjectSelection.resolveExplicitProjectNames(config, request.projectScope());
    var projectNames = projects.stream().map(ProjectName::value).toList();
    var resolvedQuery =
        firstNonBlank(
            request.query(), request.lexicalInput(), request.vectorInput(), request.hydeInput());
    if (projectNames.isEmpty()) {
      if (requiresVectors(request)) {
        throw CandidateRetriever.emptyVectorIndex();
      }
      return new Result(request.searchMode().name(), resolvedQuery, List.of());
    }

    var candidateLimit = request.unlimited() ? Integer.MAX_VALUE : request.resultLimit();
    var matches =
        searchByMode(databaseFile, config, projectNames, request, candidateLimit, progress);
    return new Result(
        request.searchMode().name(),
        resolvedQuery,
        SearchResultAssembler.assembleResults(matches, resolvedQuery, request));
  }

  private List<Match> searchByMode(
      Path databaseFile,
      SomaConfig config,
      List<String> projectNames,
      Request request,
      int candidateLimit,
      Consumer<String> progress) {
    return switch (request.searchMode()) {
      case lexical ->
          matches(
              config,
              candidateRetriever.lexical(
                  databaseFile,
                  projectNames,
                  candidateRetriever.parseLexical(request.query(), false),
                  candidateLimit));
      case vector -> {
        progress.accept("Searching 1 vector query...");
        var hits =
            candidateRetriever.vector(
                databaseFile,
                projectNames,
                candidateRetriever.embedQuery(queryWithIntent(request.query(), request.intent())),
                candidateLimit);
        candidateRetriever.requireVectorHits(hits);
        yield matches(config, hits);
      }
      case hybrid ->
          hybrid.search(
              databaseFile,
              config,
              projectNames,
              request,
              Math.max(candidateLimit, hybrid.minimumCandidateLimit()),
              progress);
    };
  }

  private static List<Match> matches(SomaConfig config, List<WorkspaceIndex.SearchHit> searchHits) {
    return searchHits.stream()
        .map(hit -> new Match(hit, config.effectiveContext(hit.project(), hit.path())))
        .toList();
  }

  private static boolean requiresVectors(Request request) {
    return request.searchMode() == Mode.vector
        || request.searchMode() == Mode.hybrid
            && (hasText(request.query())
                || hasText(request.vectorInput())
                || hasText(request.hydeInput()));
  }

  private static void validate(Request request) {
    if (!request.unlimited() && request.resultLimit() < 1) {
      throw new AppException(
          INVALID_REQUEST, "Search limit must be at least 1.", "Use a positive `--limit`.");
    }
    if (request.pathOnly() && (request.fullDocuments() || request.includeLineNumbers())) {
      throw new AppException(
          INVALID_REQUEST,
          "Path-only output cannot include bodies or line numbers.",
          "Remove `--full` and `--line-number`, or choose another format.");
    }
    if (request.searchMode() == Mode.hybrid
        && !hasText(request.query())
        && !hasText(request.lexicalInput())
        && !hasText(request.vectorInput())
        && !hasText(request.hydeInput())) {
      throw new AppException(
          INVALID_REQUEST,
          "Hybrid search requires a query or manual search input.",
          "Pass a query or one of `--lex`, `--vec`, and `--hyde`.");
    }
    if (request.searchMode() != Mode.hybrid && !hasText(request.query())) {
      throw new AppException(
          INVALID_REQUEST,
          request.searchMode().name() + " search requires a query.",
          "Pass a query and retry.");
    }
  }

  static String queryWithIntent(String queryText, String intent) {
    return hasText(intent) ? queryText + "\nQuery intent: " + intent : queryText;
  }

  static String firstNonBlank(String... values) {
    for (var value : values) {
      if (hasText(value)) {
        return value;
      }
    }
    return "";
  }

  static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public enum Mode {
    hybrid,
    lexical,
    vector
  }

  public record Request(
      Mode searchMode,
      String query,
      String lexicalInput,
      String vectorInput,
      String hydeInput,
      String intent,
      List<String> projectScope,
      int resultLimit,
      boolean unlimited,
      boolean fullDocuments,
      boolean includeLineNumbers,
      boolean pathOnly,
      boolean verbose) {

    public Request {
      Objects.requireNonNull(searchMode, "searchMode");
      projectScope = projectScope == null ? List.of() : List.copyOf(projectScope);
    }
  }

  record Match(WorkspaceIndex.SearchHit hit, String context) {

    Match {
      Objects.requireNonNull(hit, "hit");
      context = nullToEmpty(context);
    }
  }

  @Serdeable
  public record Result(String mode, String query, List<Item> results) implements Renderable {

    public Result {
      results = results == null ? List.of() : List.copyOf(results);
    }

    @Override
    public void render(OutputFormat format, PrintWriter out) {
      switch (format) {
        case text -> renderText(out);
        case json -> renderJson(this, out, "Could not render search results as JSON.");
        case csv -> renderCsv(out);
        case md -> renderMarkdown(out);
        case paths -> results.forEach(item -> out.println(item.virtualPath()));
      }
    }

    private void renderText(PrintWriter out) {
      if (results.isEmpty()) {
        out.println("No results found.");
        return;
      }
      for (var position = 0; position < results.size(); position++) {
        var item = results.get(position);
        var displayPath =
            item.line() != null && item.line() > 0 && hasText(item.snippet())
                ? item.virtualPath() + ":" + item.line()
                : item.virtualPath();
        out.println(displayPath + " " + item.docId());
        out.println("Title: " + nullToEmpty(item.title()));
        out.println("Relevance score: " + item.score());
        if (hasText(item.context())) {
          out.println("Context: " + item.context());
        }
        out.println();
        var displayBody = hasText(item.body()) ? item.body() : item.snippet();
        if (hasText(displayBody)) {
          out.println(displayBody);
        }
        if (position + 1 < results.size()) {
          out.println();
          out.println();
        }
      }
    }

    private void renderCsv(PrintWriter out) {
      out.println("docId,virtualPath,title,score,context,snippet,body");
      for (var item : results) {
        out.printf(
            "%s,%s,%s,%s,%s,%s,%s%n",
            DisplayFormat.csvCell(item.docId()),
            DisplayFormat.csvCell(item.virtualPath()),
            DisplayFormat.csvCell(item.title()),
            item.score(),
            DisplayFormat.csvCell(item.context()),
            DisplayFormat.csvCell(item.snippet()),
            DisplayFormat.csvCell(item.body()));
      }
    }

    private void renderMarkdown(PrintWriter out) {
      for (var item : results) {
        out.println("### " + item.docId() + " " + item.virtualPath());
        out.println();
        if (hasText(item.context())) {
          out.println("**Context:** " + item.context());
          out.println();
        }
        var displayBody = hasText(item.body()) ? item.body() : item.snippet();
        if (hasText(displayBody)) {
          out.println("```text");
          out.println(displayBody);
          out.println("```");
          out.println();
        }
      }
    }
  }

  @Serdeable
  public record Item(
      String docId,
      String virtualPath,
      String title,
      double score,
      String snippet,
      String body,
      // 1-based source line containing the best match, not the first line of the snippet.
      Integer line,
      String context) {

    public Item {
      context = nullToEmpty(context);
    }
  }
}
