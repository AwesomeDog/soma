package io.github.awesomedog.soma.app.search;

import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.domain.search.LexicalQuery;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

final class CandidateRetriever {

  private static final String QUERY_PREFIX = "task: search result | query: ";

  private final WorkspaceIndex workspaceIndex;
  private final SearchModels searchModels;

  CandidateRetriever(WorkspaceIndex workspaceIndex, SearchModels searchModels) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
    this.searchModels = Objects.requireNonNull(searchModels, "searchModels");
  }

  LexicalQuery parseLexical(String queryText, boolean expanded) {
    try {
      return LexicalQuery.parse(queryText);
    } catch (IllegalArgumentException e) {
      if (expanded) {
        throw new AppException(
            OPERATION_FAILED,
            "Query expansion produced an invalid lexical input: " + e.getMessage(),
            "Retry, or pass `--lex` manually.",
            e);
      }
      throw new AppException(
          INVALID_REQUEST,
          "Invalid lexical query: " + e.getMessage(),
          "Use words, quoted phrases, and optional `-` exclusions.",
          e);
    }
  }

  List<WorkspaceIndex.SearchHit> lexical(
      Path databaseFile, List<String> projectNames, LexicalQuery lexicalQuery, int candidateLimit) {
    return workspaceIndex.lexicalSearch(databaseFile, projectNames, lexicalQuery, candidateLimit);
  }

  float[] embedQuery(String queryText) {
    var embedding = searchModels.embed(QUERY_PREFIX + queryText);
    requireValidEmbedding(embedding);
    return embedding;
  }

  List<float[]> embedQueries(List<String> queryTexts) {
    var embeddings =
        searchModels.embedBatch(queryTexts.stream().map(query -> QUERY_PREFIX + query).toList());
    if (embeddings == null || embeddings.size() != queryTexts.size()) {
      throw new AppException(
          OPERATION_FAILED,
          "The managed search runtime returned an incomplete embedding batch.",
          "Run `soma system pull`, then retry.");
    }
    embeddings.forEach(CandidateRetriever::requireValidEmbedding);
    return List.copyOf(embeddings);
  }

  List<WorkspaceIndex.SearchHit> vector(
      Path databaseFile, List<String> projectNames, float[] queryVector, int candidateLimit) {
    var bestHitByPath = new LinkedHashMap<String, WorkspaceIndex.SearchHit>();
    for (var hit :
        workspaceIndex.vectorSearch(databaseFile, projectNames, queryVector, candidateLimit)) {
      var existing = bestHitByPath.get(hit.virtualPath());
      if (existing == null || hit.score() > existing.score()) {
        bestHitByPath.put(hit.virtualPath(), hit);
      }
    }
    return bestHitByPath.values().stream()
        .sorted(
            Comparator.comparingDouble(WorkspaceIndex.SearchHit::score)
                .reversed()
                .thenComparing(WorkspaceIndex.SearchHit::virtualPath))
        .toList();
  }

  void requireVectorHits(List<WorkspaceIndex.SearchHit> hits) {
    if (hits.isEmpty()) {
      throw emptyVectorIndex();
    }
  }

  static AppException emptyVectorIndex() {
    return new AppException(
        OPERATION_FAILED,
        "Vector index is empty for the search scope.",
        "Run `soma sync`, then retry.");
  }

  private static void requireValidEmbedding(float[] embedding) {
    if (embedding == null || embedding.length != SearchModels.VECTOR_DIMENSIONS) {
      throw invalidEmbedding();
    }
    for (var component : embedding) {
      if (!Float.isFinite(component)) {
        throw invalidEmbedding();
      }
    }
  }

  private static AppException invalidEmbedding() {
    return new AppException(
        OPERATION_FAILED,
        "The managed search runtime returned an invalid embedding vector.",
        "Run `soma system pull`, then retry.");
  }
}
