package io.github.awesomedog.soma.infra.sqlite;

import static io.github.awesomedog.soma.app.ports.WorkspaceIndex.DOC_ID_LENGTH;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.bindStringParameters;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.databaseFailure;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.parseFileType;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.placeholders;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.requireNonBlank;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.serializeVectorAsJson;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.uniqueNonBlankStrings;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.validateEmbeddingVector;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ChunkRead;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.DocumentRead;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ProjectStats;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.SearchHit;
import io.github.awesomedog.soma.domain.search.LexicalProjector;
import io.github.awesomedog.soma.domain.search.LexicalQuery;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

// Handles verified read-only queries over the workspace index.
final class SqliteIndexQueries {

  private static final String PROJECT_STATISTICS_QUERY =
      """
      WITH requested(project_name) AS (VALUES %s),
      doc_stats AS (
          SELECT d.project_name,
                 COUNT(*) AS documents,
                 SUM(d.extraction_status = 'ready') AS ready_documents,
                 SUM(d.extraction_status = 'pending') AS pending_documents,
                 SUM(d.extraction_status = 'failed') AS failed_documents,
                 MAX(d.updated_at) AS updated_at
          FROM documents AS d
          JOIN requested AS r ON r.project_name = d.project_name
          GROUP BY d.project_name
      ),
      lexical_stats AS (
          SELECT d.project_name, COUNT(f.rowid) AS lexical_documents
          FROM documents AS d
          JOIN requested AS r ON r.project_name = d.project_name
          JOIN fts_index AS f ON f.rowid = d.id
          GROUP BY d.project_name
      ),
      chunk_stats AS (
          SELECT d.project_name, COUNT(c.chunk_index) AS chunks
          FROM documents AS d
          JOIN requested AS r ON r.project_name = d.project_name
          JOIN chunks AS c ON c.content_hash = d.content_hash
          GROUP BY d.project_name
      ),
      embedding_stats AS (
          SELECT d.project_name, COUNT(e.id) AS embeddings
          FROM documents AS d
          JOIN requested AS r ON r.project_name = d.project_name
          JOIN embeddings AS e ON e.document_id = d.id
          GROUP BY d.project_name
      ),
      vector_stats AS (
          SELECT d.project_name, COUNT(v.rowid) AS vectors
          FROM documents AS d
          JOIN requested AS r ON r.project_name = d.project_name
          JOIN embeddings AS e ON e.document_id = d.id
          JOIN vectors AS v ON v.rowid = e.id AND v.project_name = d.project_name
          GROUP BY d.project_name
      )
      SELECT r.project_name,
             COALESCE(ds.documents, 0) AS documents,
             COALESCE(ds.ready_documents, 0) AS ready_documents,
             COALESCE(ds.pending_documents, 0) AS pending_documents,
             COALESCE(ds.failed_documents, 0) AS failed_documents,
             COALESCE(ls.lexical_documents, 0) AS lexical_documents,
             COALESCE(cs.chunks, 0) AS chunks,
             COALESCE(es.embeddings, 0) AS embeddings,
             COALESCE(vs.vectors, 0) AS vectors,
             ds.updated_at
      FROM requested AS r
      LEFT JOIN doc_stats AS ds ON ds.project_name = r.project_name
      LEFT JOIN lexical_stats AS ls ON ls.project_name = r.project_name
      LEFT JOIN chunk_stats AS cs ON cs.project_name = r.project_name
      LEFT JOIN embedding_stats AS es ON es.project_name = r.project_name
      LEFT JOIN vector_stats AS vs ON vs.project_name = r.project_name
      """;

  private final SqliteWorkspaceIndex workspaceIndex;

  SqliteIndexQueries(SqliteWorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
  }

  List<DocumentRead> listDocuments(Path databaseFile, String projectName, String pathPrefix) {
    requireNonBlank(projectName, "project");
    var normalizedPathPrefix = pathPrefix == null ? "" : pathPrefix;
    var descendantPathPrefix =
        normalizedPathPrefix.endsWith("/") ? normalizedPathPrefix : normalizedPathPrefix + "/";
    var sql =
        """
        SELECT project_name, path, content_hash, title, source_size_bytes, source_mtime_ns,
               file_type, extraction_status, 0 AS body_size_bytes, NULL AS body
        FROM documents
        WHERE project_name = ?
          AND (? = '' OR path = ? OR substr(path, 1, length(?)) = ?)
        ORDER BY path
        """;
    try (var connection = workspaceIndex.openVerifiedReadConnection(databaseFile);
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, projectName);
      statement.setString(2, normalizedPathPrefix);
      statement.setString(3, normalizedPathPrefix);
      statement.setString(4, descendantPathPrefix);
      statement.setString(5, descendantPathPrefix);
      try (var resultSet = statement.executeQuery()) {
        var documentReads = new ArrayList<DocumentRead>();
        while (resultSet.next()) {
          documentReads.add(readDocumentRow(resultSet));
        }
        return List.copyOf(documentReads);
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw databaseFailure("Could not list indexed project files.", "Run `soma sync`.", e);
    }
  }

  Optional<DocumentRead> findDocument(
      Path databaseFile, String projectName, String documentPath, long maximumBodyBytes) {
    requireNonBlank(projectName, "project");
    requireNonBlank(documentPath, "path");
    var sql =
        """
        SELECT d.project_name, d.path, d.content_hash, d.title, d.source_size_bytes,
               d.source_mtime_ns, d.file_type, d.extraction_status,
               octet_length(c.body) AS body_size_bytes,
               CASE WHEN octet_length(c.body) <= ? THEN c.body END AS body
        FROM documents AS d
        LEFT JOIN contents AS c ON c.content_hash = d.content_hash
        WHERE d.project_name = ? AND d.path = ?
        """;
    try (var connection = workspaceIndex.openVerifiedReadConnection(databaseFile);
        var statement = connection.prepareStatement(sql)) {
      statement.setLong(1, maximumBodyBytes);
      statement.setString(2, projectName);
      statement.setString(3, documentPath);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next() ? Optional.of(readDocumentRow(resultSet)) : Optional.empty();
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw databaseFailure("Could not read the indexed document.", "Run `soma sync`.", e);
    }
  }

  List<DocumentRead> findReadyDocumentsByDocId(
      Path databaseFile, String docId, long maximumBodyBytes) {
    requireNonBlank(docId, "DocID");
    var sql =
        """
        SELECT d.project_name, d.path, d.content_hash, d.title, d.source_size_bytes,
               d.source_mtime_ns, d.file_type, d.extraction_status,
               octet_length(c.body) AS body_size_bytes,
               CASE WHEN octet_length(c.body) <= ? THEN c.body END AS body
        FROM documents AS d
        JOIN contents AS c ON c.content_hash = d.content_hash
        WHERE d.extraction_status = 'ready'
          AND '@' || substr(d.content_hash, 1, %d) = ?
        ORDER BY d.project_name, d.path
        """
            .formatted(DOC_ID_LENGTH);
    try (var connection = workspaceIndex.openVerifiedReadConnection(databaseFile);
        var statement = connection.prepareStatement(sql)) {
      statement.setLong(1, maximumBodyBytes);
      statement.setString(2, docId.toLowerCase(Locale.ROOT));
      try (var resultSet = statement.executeQuery()) {
        var readyDocuments = new ArrayList<DocumentRead>();
        while (resultSet.next()) {
          readyDocuments.add(readDocumentRow(resultSet));
        }
        return List.copyOf(readyDocuments);
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw databaseFailure("Could not resolve the indexed DocID.", "Run `soma sync`.", e);
    }
  }

  Map<String, ProjectStats> projectStats(Path databaseFile, List<String> projectNames) {
    var projectScope = uniqueNonBlankStrings(projectNames);
    try (var connection = workspaceIndex.openVerifiedReadConnection(databaseFile)) {
      if (projectScope.isEmpty()) {
        return Map.of();
      }
      var projectNameRows =
          String.join(", ", java.util.Collections.nCopies(projectScope.size(), "(?)"));
      var sql = PROJECT_STATISTICS_QUERY.formatted(projectNameRows);
      try (var statement = connection.prepareStatement(sql)) {
        bindStringParameters(statement, 1, projectScope);
        try (var resultSet = statement.executeQuery()) {
          var projectStatsByName = new java.util.LinkedHashMap<String, ProjectStats>();
          while (resultSet.next()) {
            projectStatsByName.put(
                resultSet.getString("project_name"),
                new ProjectStats(
                    resultSet.getLong("documents"),
                    resultSet.getLong("ready_documents"),
                    resultSet.getLong("pending_documents"),
                    resultSet.getLong("failed_documents"),
                    resultSet.getLong("lexical_documents"),
                    resultSet.getLong("chunks"),
                    resultSet.getLong("embeddings"),
                    resultSet.getLong("vectors"),
                    resultSet.getString("updated_at")));
          }
          return Map.copyOf(projectStatsByName);
        }
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw databaseFailure("Could not read project index statistics.", "Run `soma sync`.", e);
    }
  }

  List<SearchHit> lexicalSearch(
      Path databaseFile, List<String> projectNames, LexicalQuery query, int limit) {
    Objects.requireNonNull(query, "query");
    var projectScope = uniqueNonBlankStrings(projectNames);
    if (projectScope.isEmpty() || limit <= 0) {
      return List.of();
    }
    var lexicalMatchExpression = buildLexicalMatchExpression(query);
    var verifyOriginalPhrases = requiresOriginalPhraseVerification(query);
    var sql =
        """
        SELECT d.project_name, d.path, d.title, d.content_hash, c.body AS evidence_body,
               bm25(fts_index) AS raw_bm25
        FROM fts_index
        JOIN documents AS d ON d.id = fts_index.rowid
        JOIN contents AS c ON c.content_hash = d.content_hash
        WHERE fts_index MATCH ?
          AND d.extraction_status = 'ready'
          AND d.project_name IN (%s)
        ORDER BY raw_bm25 ASC, d.project_name, d.path
        """
            .formatted(placeholders(projectScope.size()));
    try (var connection = workspaceIndex.openVerifiedReadConnection(databaseFile);
        var statement = connection.prepareStatement(sql)) {
      statement.setString(1, lexicalMatchExpression);
      bindStringParameters(statement, 2, projectScope);
      try (var resultSet = statement.executeQuery()) {
        var lexicalHits = new ArrayList<SearchHit>();
        while (resultSet.next() && lexicalHits.size() < limit) {
          var hit = readLexicalSearchHit(resultSet);
          if (!verifyOriginalPhrases || matchesOriginalPhrases(query, hit.evidenceBody())) {
            lexicalHits.add(hit);
          }
        }
        return List.copyOf(lexicalHits);
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw databaseFailure("Could not search the lexical index.", "Run `soma sync`.", e);
    }
  }

  List<ChunkRead> chunks(Path databaseFile, List<String> contentHashes) {
    var contentHashScope = uniqueNonBlankStrings(contentHashes);
    if (contentHashScope.isEmpty()) {
      return List.of();
    }
    var sql =
        "SELECT content_hash, chunk_index, body, char_start_offset, char_end_offset "
            + "FROM chunks WHERE content_hash IN ("
            + placeholders(contentHashScope.size())
            + ") ORDER BY content_hash, chunk_index";
    try (var connection = workspaceIndex.openVerifiedReadConnection(databaseFile);
        var statement = connection.prepareStatement(sql)) {
      bindStringParameters(statement, 1, contentHashScope);
      try (var resultSet = statement.executeQuery()) {
        var chunkReads = new ArrayList<ChunkRead>();
        while (resultSet.next()) {
          chunkReads.add(
              new ChunkRead(
                  resultSet.getString("content_hash"),
                  resultSet.getInt("chunk_index"),
                  resultSet.getString("body"),
                  resultSet.getInt("char_start_offset"),
                  resultSet.getInt("char_end_offset")));
        }
        return List.copyOf(chunkReads);
      }
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw databaseFailure("Could not read persisted chunks.", "Run `soma system embed`.", e);
    }
  }

  List<SearchHit> vectorSearch(
      Path databaseFile, List<String> projectNames, float[] queryVector, int limit) {
    validateEmbeddingVector(queryVector);
    var projectScope = uniqueNonBlankStrings(projectNames);
    if (projectScope.isEmpty() || limit <= 0) {
      return List.of();
    }
    var queryVectorJson = serializeVectorAsJson(queryVector);
    try (var connection = workspaceIndex.openVerifiedReadConnection(databaseFile)) {
      var vectorSearchHits = new ArrayList<SearchHit>();
      for (var projectName : projectScope) {
        var projectVectorCount = countProjectVectorRows(connection, projectName);
        if (projectVectorCount <= 0) {
          continue;
        }
        var chunkLimit = Math.min(limit, projectVectorCount);
        while (true) {
          var bestHitByPath = new LinkedHashMap<String, SearchHit>();
          for (var hit :
              searchProjectVectorHits(connection, projectName, queryVectorJson, chunkLimit)) {
            bestHitByPath.putIfAbsent(hit.virtualPath(), hit);
          }
          if (bestHitByPath.size() >= limit || chunkLimit == projectVectorCount) {
            vectorSearchHits.addAll(bestHitByPath.values().stream().limit(limit).toList());
            break;
          }
          chunkLimit = (int) Math.min(projectVectorCount, (long) chunkLimit * 2);
        }
      }
      return vectorSearchHits.stream()
          .sorted(
              Comparator.comparingDouble(SearchHit::score)
                  .reversed()
                  .thenComparing(SearchHit::project)
                  .thenComparing(SearchHit::path))
          .limit(limit)
          .toList();
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw databaseFailure("Could not search the vector index.", "Run `soma system embed`.", e);
    }
  }

  private List<SearchHit> searchProjectVectorHits(
      Connection connection, String project, String vector, int limit) throws SQLException {
    var sql =
        """
        WITH vector_hits AS (
            SELECT rowid AS embedding_id, distance
            FROM vectors
            WHERE embedding MATCH ? AND k = ? AND project_name = ?)
        SELECT d.project_name, d.path, d.title, e.content_hash, e.chunk_index,
               c.char_start_offset, c.char_end_offset, c.body AS evidence_body,
               dc.body AS document_body, vector_hits.distance
        FROM vector_hits
        JOIN embeddings AS e ON e.id = vector_hits.embedding_id
        JOIN documents AS d ON d.id = e.document_id
        JOIN chunks AS c
          ON c.content_hash = e.content_hash AND c.chunk_index = e.chunk_index
        JOIN contents AS dc ON dc.content_hash = e.content_hash
        WHERE d.extraction_status = 'ready'
        ORDER BY vector_hits.distance, d.project_name, d.path, e.chunk_index
        """;
    try (var statement = connection.prepareStatement(sql)) {
      statement.setString(1, vector);
      statement.setInt(2, limit);
      statement.setString(3, project);
      try (var rows = statement.executeQuery()) {
        var hits = new ArrayList<SearchHit>();
        while (rows.next()) {
          var rawDistance = rows.getDouble("distance");
          var distance =
              Double.isFinite(rawDistance) ? Math.max(0.0d, rawDistance) : Double.MAX_VALUE;
          hits.add(
              new SearchHit(
                  rows.getString("project_name"),
                  rows.getString("path"),
                  rows.getString("title"),
                  rows.getString("content_hash"),
                  rows.getString("evidence_body"),
                  rows.getString("document_body"),
                  rows.getInt("chunk_index"),
                  rows.getInt("char_start_offset"),
                  rows.getInt("char_end_offset"),
                  1.0d / (1.0d + distance)));
        }
        return hits;
      }
    }
  }

  private int countProjectVectorRows(Connection connection, String project) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT COUNT(*) FROM vectors WHERE project_name = ?")) {
      statement.setString(1, project);
      try (var rows = statement.executeQuery()) {
        return rows.next() ? rows.getInt(1) : 0;
      }
    }
  }

  private static String buildLexicalMatchExpression(LexicalQuery query) {
    var positive = new ArrayList<String>();
    var negative = new ArrayList<String>();
    for (var clause : query.clauses()) {
      if (clause.excluded()
          && clause.phrase()
          && requiresOriginalPhraseVerification(clause.text())) {
        continue;
      }
      var sqlClause =
          clause.phrase()
              ? buildLexicalPhraseClause(clause.text())
              : buildLexicalTermClause(clause.text());
      (clause.excluded() ? negative : positive).add(sqlClause);
    }
    var match = new StringBuilder(String.join(" AND ", positive));
    for (var clause : negative) {
      match.append(" NOT ").append(clause.contains(" AND ") ? "(" + clause + ")" : clause);
    }
    return match.toString();
  }

  private static String buildLexicalTermClause(String raw) {
    var tokens = LexicalProjector.tokens(raw);
    if (raw.indexOf('-') > 0 && tokens.size() > 1) {
      return quoteFtsToken(String.join(" ", tokens)) + "*";
    }
    return String.join(" AND ", tokens.stream().map(token -> quoteFtsToken(token) + "*").toList());
  }

  private static String buildLexicalPhraseClause(String raw) {
    var tokens = LexicalProjector.tokens(raw);
    if (requiresOriginalPhraseVerification(raw)) {
      return String.join(" AND ", tokens.stream().map(SqliteIndexQueries::quoteFtsToken).toList());
    }
    return quoteFtsToken(String.join(" ", tokens));
  }

  private static boolean requiresOriginalPhraseVerification(LexicalQuery query) {
    return query.clauses().stream()
        .anyMatch(clause -> clause.phrase() && requiresOriginalPhraseVerification(clause.text()));
  }

  private static boolean requiresOriginalPhraseVerification(String raw) {
    return LexicalProjector.containsCjk(raw);
  }

  private static boolean matchesOriginalPhrases(LexicalQuery query, String body) {
    var normalizedBody = normalizeOriginalText(body);
    for (var clause : query.clauses()) {
      if (!clause.phrase() || !requiresOriginalPhraseVerification(clause.text())) {
        continue;
      }
      var containsPhrase = normalizedBody.contains(normalizeOriginalText(clause.text()));
      if (containsPhrase == clause.excluded()) {
        return false;
      }
    }
    return true;
  }

  private static String normalizeOriginalText(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
  }

  private static String quoteFtsToken(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  static double bm25Score(double rawBm25) {
    var magnitude = Math.abs(rawBm25);
    if (Double.isNaN(magnitude)) {
      return 0.0d;
    }
    if (Double.isInfinite(magnitude)) {
      return 1.0d;
    }
    return magnitude / (1.0d + magnitude);
  }

  private static DocumentRead readDocumentRow(java.sql.ResultSet rows) throws SQLException {
    return new DocumentRead(
        rows.getString("project_name"),
        rows.getString("path"),
        rows.getString("content_hash"),
        rows.getString("title"),
        rows.getLong("source_size_bytes"),
        rows.getLong("source_mtime_ns"),
        parseFileType(rows.getString("file_type")),
        io.github.awesomedog.soma.domain.document.ExtractionStatus.valueOf(
            rows.getString("extraction_status").toUpperCase(Locale.ROOT)),
        rows.getLong("body_size_bytes"),
        rows.getString("body"));
  }

  private static SearchHit readLexicalSearchHit(java.sql.ResultSet rows) throws SQLException {
    var title = rows.getString("title");
    var path = rows.getString("path");
    var evidenceBody = rows.getString("evidence_body");
    return new SearchHit(
        rows.getString("project_name"),
        path,
        title,
        rows.getString("content_hash"),
        evidenceBody,
        evidenceBody,
        null,
        null,
        null,
        bm25Score(rows.getDouble("raw_bm25")));
  }
}
