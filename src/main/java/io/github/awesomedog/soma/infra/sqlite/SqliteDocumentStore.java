package io.github.awesomedog.soma.infra.sqlite;

import static io.github.awesomedog.soma.domain.document.ExtractionStatus.READY;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.WRITE_BATCH_SIZE;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.bindNullableString;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.databaseFailure;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.invalidData;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.parseFileType;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.placeholders;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.requireNonBlank;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.requireSingleRowUpdated;

import io.github.awesomedog.soma.app.ports.WorkspaceIndex.DocumentScanReport;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.DocumentSnapshot;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.DocumentWrite;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ExtractionWork;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.domain.search.LexicalProjector;
import io.github.awesomedog.soma.support.Hashing;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

// Owns document synchronization, extraction publication, and FTS writes.
final class SqliteDocumentStore {

  private static final String PDF_RECIPE_KEY = "active.extraction.pdf_recipe_id";
  private static final String OFFICE_RECIPE_KEY = "active.extraction.office_recipe_id";
  private static final String EPUB_RECIPE_KEY = "active.extraction.epub_recipe_id";
  private static final String IMAGE_RECIPE_KEY = "active.extraction.image_recipe_id";
  private static final String MEDIA_RECIPE_KEY = "active.extraction.media_recipe_id";
  private static final String LEXICAL_RECIPE_KEY = "active.lexical_recipe_id";

  private final SqliteWorkspaceIndex workspaceIndex;

  SqliteDocumentStore(SqliteWorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
  }

  List<DocumentSnapshot> documentSnapshots() {
    var snapshots = new ArrayList<DocumentSnapshot>();
    try (var statement = workspaceIndex.requireWriteConnection().createStatement();
        var rows =
            statement.executeQuery(
                """
                SELECT id, project_name, path, source_mtime_ns, source_size_bytes
                FROM documents
                ORDER BY project_name, path
                """)) {
      while (rows.next()) {
        snapshots.add(
            new DocumentSnapshot(
                rows.getLong("id"),
                rows.getString("project_name"),
                rows.getString("path"),
                rows.getLong("source_mtime_ns"),
                rows.getLong("source_size_bytes")));
      }
      return List.copyOf(snapshots);
    } catch (SQLException e) {
      throw databaseFailure("Could not read indexed document metadata.", "Run `soma sync`.", e);
    }
  }

  DocumentScanReport applyScan(
      List<DocumentWrite> inspectedDocuments,
      List<Long> removedDocumentIds,
      int unchanged,
      Consumer<Long> progress) {
    var documents = List.copyOf(Objects.requireNonNull(inspectedDocuments, "inspectedDocuments"));
    var removedIds = List.copyOf(Objects.requireNonNull(removedDocumentIds, "removedDocumentIds"));
    Objects.requireNonNull(progress, "progress");
    if (unchanged < 0) {
      throw invalidData("Unchanged document count must not be negative.");
    }
    documents.forEach(SqliteDocumentStore::validateDocument);
    try {
      var upserted = 0;
      var metadataUpdated = 0;
      long completed = 0;
      for (var start = 0; start < documents.size(); start += WRITE_BATCH_SIZE) {
        var end = Math.min(start + WRITE_BATCH_SIZE, documents.size());
        var batch = applyDocumentBatch(documents.subList(start, end));
        upserted += batch.upserted();
        metadataUpdated += batch.metadataUpdated();
        completed += end - start;
        progress.accept(completed);
      }
      for (var start = 0; start < removedIds.size(); start += WRITE_BATCH_SIZE) {
        var end = Math.min(start + WRITE_BATCH_SIZE, removedIds.size());
        deleteDocumentsInBatch(removedIds.subList(start, end));
        completed += end - start;
        progress.accept(completed);
      }
      return new DocumentScanReport(upserted, metadataUpdated, unchanged, removedIds.size());
    } catch (SQLException e) {
      throw databaseFailure("Could not synchronize indexed documents.", "Run `soma sync`.", e);
    }
  }

  private DocumentBatchReport applyDocumentBatch(List<DocumentWrite> documents)
      throws SQLException {
    return workspaceIndex.executeTransaction(
        connection -> {
          var upserted = 0;
          var metadataUpdated = 0;
          for (var document : documents) {
            if (updateSourceMetadataIfContentMatches(connection, document)) {
              metadataUpdated++;
            } else {
              persistDocument(connection, document);
              upserted++;
            }
          }
          return new DocumentBatchReport(upserted, metadataUpdated);
        });
  }

  private static boolean updateSourceMetadataIfContentMatches(
      Connection connection, DocumentWrite document) throws SQLException {
    var sql =
        """
        UPDATE documents
        SET source_mtime_ns = ?, source_size_bytes = ?, updated_at = CURRENT_TIMESTAMP
        WHERE project_name = ? AND path = ?
          AND source_hash IS ?
          AND title = ?
          AND file_type = ?
          AND ((? = 'pending' AND file_type IN ('pdf', 'office', 'epub', 'image', 'audio', 'video'))
               OR (content_hash IS ? AND extraction_status = ?))
        """;
    try (var statement = connection.prepareStatement(sql)) {
      statement.setLong(1, document.modifiedTimeNs());
      statement.setLong(2, document.sizeBytes());
      statement.setString(3, document.project());
      statement.setString(4, document.path());
      bindNullableString(statement, 5, document.sourceHash());
      statement.setString(6, document.title());
      statement.setString(7, document.fileType().value());
      statement.setString(8, document.status().value());
      bindNullableString(statement, 9, document.contentHash());
      statement.setString(10, document.status().value());
      return statement.executeUpdate() == 1;
    }
  }

  void rebuildLexicalIndexForRecipe(String desiredRecipeId) {
    try {
      if (!workspaceIndex.hasActiveRecipe(LEXICAL_RECIPE_KEY, desiredRecipeId)) {
        workspaceIndex.clearTableRowsInBatches("fts_index");
        workspaceIndex.publishActiveRecipe(LEXICAL_RECIPE_KEY, desiredRecipeId);
      }
      writeMissingLexicalRows();
    } catch (SQLException e) {
      throw databaseFailure("Could not rebuild the lexical index.", "Run `soma sync`.", e);
    }
  }

  void invalidateExtractionForRecipeChanges(Map<FileType, String> desiredRecipeIds) {
    Objects.requireNonNull(desiredRecipeIds, "desiredRecipeIds");
    var pdfRecipeId = Objects.requireNonNull(desiredRecipeIds.get(FileType.PDF), "PDF recipe");
    var officeRecipeId =
        Objects.requireNonNull(desiredRecipeIds.get(FileType.OFFICE), "Office recipe");
    var epubRecipeId = Objects.requireNonNull(desiredRecipeIds.get(FileType.EPUB), "EPUB recipe");
    var imageRecipeId =
        Objects.requireNonNull(desiredRecipeIds.get(FileType.IMAGE), "image recipe");
    var audioRecipeId =
        Objects.requireNonNull(desiredRecipeIds.get(FileType.AUDIO), "audio recipe");
    var videoRecipeId =
        Objects.requireNonNull(desiredRecipeIds.get(FileType.VIDEO), "video recipe");
    if (!audioRecipeId.equals(videoRecipeId)) {
      throw invalidData("Audio and video must use the same media recipe.");
    }
    try {
      invalidateExtractionForRecipeChange(PDF_RECIPE_KEY, pdfRecipeId, List.of(FileType.PDF));
      invalidateExtractionForRecipeChange(
          OFFICE_RECIPE_KEY, officeRecipeId, List.of(FileType.OFFICE));
      invalidateExtractionForRecipeChange(EPUB_RECIPE_KEY, epubRecipeId, List.of(FileType.EPUB));
      invalidateExtractionForRecipeChange(IMAGE_RECIPE_KEY, imageRecipeId, List.of(FileType.IMAGE));
      invalidateExtractionForRecipeChange(
          MEDIA_RECIPE_KEY, audioRecipeId, List.of(FileType.AUDIO, FileType.VIDEO));
    } catch (SQLException e) {
      throw databaseFailure(
          "Could not invalidate documents for extraction recipe changes.",
          "Run `soma system extract`.",
          e);
    }
  }

  List<ExtractionWork> extractionWork() {
    var sql =
        """
        SELECT id, project_name, path, file_type, source_hash
        FROM documents
        WHERE file_type IN ('pdf', 'office', 'epub', 'image', 'audio', 'video')
          AND extraction_status = 'pending'
          AND source_hash IS NOT NULL
        ORDER BY project_name, path
        """;
    try (var statement = workspaceIndex.requireWriteConnection().prepareStatement(sql)) {
      try (var resultSet = statement.executeQuery()) {
        var extractionTasks = new ArrayList<ExtractionWork>();
        while (resultSet.next()) {
          extractionTasks.add(
              new ExtractionWork(
                  resultSet.getLong("id"),
                  resultSet.getString("project_name"),
                  resultSet.getString("path"),
                  parseFileType(resultSet.getString("file_type")),
                  resultSet.getString("source_hash")));
        }
        return List.copyOf(extractionTasks);
      }
    } catch (SQLException e) {
      throw databaseFailure("Could not read extraction work.", "Run `soma sync`.", e);
    }
  }

  void publishExtraction(long documentId, String contentHash, String body) {
    requireNonBlank(contentHash, "content hash");
    Objects.requireNonNull(body, "body");
    if (!Hashing.sha256HexUtf8(body).equals(contentHash)) {
      throw invalidData("Extracted content hash does not match its body.");
    }
    try {
      workspaceIndex.executeTransaction(
          connection -> {
            var storedDocument = readStoredDocument(connection, documentId);
            upsertContent(connection, contentHash, body);
            publishDocumentExtraction(connection, storedDocument, documentId, contentHash, body);
            return null;
          });
    } catch (SQLException e) {
      throw databaseFailure(
          "Could not publish extracted content.", "Run `soma system extract`.", e);
    }
  }

  void failExtraction(long documentId) {
    try {
      workspaceIndex.executeTransaction(
          connection -> {
            try (var statement =
                connection.prepareStatement(
                    """
                    UPDATE documents
                    SET content_hash = NULL, extraction_status = 'failed',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """)) {
              statement.setLong(1, documentId);
              requireSingleRowUpdated(statement.executeUpdate(), "extraction document");
            }
            return null;
          });
    } catch (SQLException e) {
      throw databaseFailure(
          "Could not record extraction failure.", "Run `soma system extract`.", e);
    }
  }

  private void invalidateExtractionForRecipeChange(
      String key, String desiredRecipeId, List<FileType> fileTypes) throws SQLException {
    if (workspaceIndex.hasActiveRecipe(key, desiredRecipeId)) {
      return;
    }
    int updated;
    do {
      updated =
          workspaceIndex.executeTransaction(
              connection -> {
                var sql =
                    """
                    UPDATE documents
                    SET content_hash = NULL,
                        extraction_status = 'pending',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE id IN (
                        SELECT id
                        FROM documents
                        WHERE file_type IN (%s)
                          AND source_hash IS NOT NULL
                          AND extraction_status IN ('ready', 'failed')
                        LIMIT %d)
                    """
                        .formatted(placeholders(fileTypes.size()), WRITE_BATCH_SIZE);
                try (var statement = connection.prepareStatement(sql)) {
                  for (var index = 0; index < fileTypes.size(); index++) {
                    statement.setString(index + 1, fileTypes.get(index).value());
                  }
                  return statement.executeUpdate();
                }
              });
    } while (updated > 0);
    workspaceIndex.publishActiveRecipe(key, desiredRecipeId);
  }

  private void writeMissingLexicalRows() throws SQLException {
    var missing = new ArrayList<LexicalDocument>();
    try (var statement = workspaceIndex.requireWriteConnection().createStatement();
        var rows =
            statement.executeQuery(
                """
                SELECT d.id, d.title, d.path, c.body
                FROM documents AS d
                JOIN contents AS c ON c.content_hash = d.content_hash
                WHERE d.extraction_status = 'ready'
                  AND NOT EXISTS (SELECT 1 FROM fts_index WHERE rowid = d.id)
                ORDER BY d.id
                """)) {
      while (rows.next()) {
        missing.add(
            new LexicalDocument(
                rows.getLong("id"),
                rows.getString("title"),
                rows.getString("path"),
                rows.getString("body")));
      }
    }
    for (var start = 0; start < missing.size(); start += WRITE_BATCH_SIZE) {
      var batch = missing.subList(start, Math.min(start + WRITE_BATCH_SIZE, missing.size()));
      workspaceIndex.executeTransaction(
          connection -> {
            for (var document : batch) {
              insertFullTextIndexRow(
                  connection, document.id(), document.title(), document.path(), document.body());
            }
            return null;
          });
    }
  }

  private void persistDocument(Connection connection, DocumentWrite document) throws SQLException {
    var ready = document.status() == READY;
    if (ready) {
      upsertContent(connection, document.contentHash(), document.body());
    }
    long documentId;
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO documents(
                project_name, path, source_hash, content_hash, title,
                source_mtime_ns, source_size_bytes, file_type, extraction_status, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(project_name, path) DO UPDATE SET
                source_hash = excluded.source_hash,
                content_hash = excluded.content_hash,
                title = excluded.title,
                source_mtime_ns = excluded.source_mtime_ns,
                source_size_bytes = excluded.source_size_bytes,
                file_type = excluded.file_type,
                extraction_status = excluded.extraction_status,
                updated_at = CURRENT_TIMESTAMP
            RETURNING id
            """)) {
      statement.setString(1, document.project());
      statement.setString(2, document.path());
      bindNullableString(statement, 3, document.sourceHash());
      bindNullableString(statement, 4, document.contentHash());
      statement.setString(5, document.title());
      statement.setLong(6, document.modifiedTimeNs());
      statement.setLong(7, document.sizeBytes());
      statement.setString(8, document.fileType().value());
      statement.setString(9, document.status().value());
      try (var rows = statement.executeQuery()) {
        if (!rows.next()) {
          throw new SQLException("document id missing after upsert");
        }
        documentId = rows.getLong(1);
      }
    }
    try (var delete = connection.prepareStatement("DELETE FROM fts_index WHERE rowid = ?")) {
      delete.setLong(1, documentId);
      delete.executeUpdate();
    }
    if (ready) {
      insertFullTextIndexRow(
          connection, documentId, document.title(), document.path(), document.body());
    }
  }

  private void deleteDocumentsInBatch(List<Long> documentIds) throws SQLException {
    workspaceIndex.executeTransaction(
        connection -> {
          try (var statement =
              connection.prepareStatement(
                  "DELETE FROM documents WHERE id IN (" + placeholders(documentIds.size()) + ")")) {
            for (var idIndex = 0; idIndex < documentIds.size(); idIndex++) {
              statement.setLong(idIndex + 1, documentIds.get(idIndex));
            }
            statement.executeUpdate();
          }
          return null;
        });
  }

  private void upsertContent(Connection connection, String contentHash, String body)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO contents(content_hash, body)
            VALUES (?, ?)
            ON CONFLICT(content_hash) DO UPDATE SET body = excluded.body
            """)) {
      statement.setString(1, contentHash);
      statement.setString(2, body);
      statement.executeUpdate();
    }
  }

  private void publishDocumentExtraction(
      Connection connection,
      StoredDocument document,
      long documentId,
      String contentHash,
      String body)
      throws SQLException {
    if (READY.value().equals(document.extractionStatus())
        && contentHash.equals(document.contentHash())) {
      try (var statement =
          connection.prepareStatement(
              """
              UPDATE documents
              SET updated_at = CURRENT_TIMESTAMP
              WHERE id = ?
              """)) {
        statement.setLong(1, documentId);
        requireSingleRowUpdated(statement.executeUpdate(), "extraction document");
      }
      return;
    }
    try (var statement =
        connection.prepareStatement(
            """
            UPDATE documents
            SET content_hash = ?, extraction_status = 'ready',
                updated_at = CURRENT_TIMESTAMP
            WHERE id = ?
            """)) {
      statement.setString(1, contentHash);
      statement.setLong(2, documentId);
      requireSingleRowUpdated(statement.executeUpdate(), "extraction document");
    }
    refreshFullTextIndex(connection, documentId, document.title(), document.path(), body);
  }

  private void refreshFullTextIndex(
      Connection connection, long documentId, String title, String path, String body)
      throws SQLException {
    try (var delete = connection.prepareStatement("DELETE FROM fts_index WHERE rowid = ?")) {
      delete.setLong(1, documentId);
      delete.executeUpdate();
    }
    insertFullTextIndexRow(connection, documentId, title, path, body);
  }

  private void insertFullTextIndexRow(
      Connection connection, long documentId, String title, String path, String body)
      throws SQLException {
    try (var insert =
        connection.prepareStatement("INSERT INTO fts_index(rowid, title, body) VALUES (?, ?, ?)")) {
      insert.setLong(1, documentId);
      insert.setString(2, LexicalProjector.toProjection(title + " " + path));
      insert.setString(3, LexicalProjector.toProjection(body));
      insert.executeUpdate();
    }
  }

  private StoredDocument readStoredDocument(Connection connection, long documentId)
      throws SQLException {
    try (var statement =
        connection.prepareStatement(
            """
            SELECT title, path, content_hash, extraction_status
            FROM documents WHERE id = ?
            """)) {
      statement.setLong(1, documentId);
      try (var rows = statement.executeQuery()) {
        if (rows.next()) {
          return new StoredDocument(
              rows.getString("title"),
              rows.getString("path"),
              rows.getString("content_hash"),
              rows.getString("extraction_status"));
        }
      }
    }
    throw new SQLException("document does not exist: " + documentId);
  }

  private static void validateDocument(DocumentWrite document) {
    Objects.requireNonNull(document, "document");
    requireNonBlank(document.project(), "project");
    requireNonBlank(document.path(), "path");
    Objects.requireNonNull(document.title(), "title");
    Objects.requireNonNull(document.fileType(), "fileType");
    Objects.requireNonNull(document.status(), "status");
    if (document.modifiedTimeNs() < 0 || document.sizeBytes() < 0) {
      throw invalidData("Document file metadata must not be negative.");
    }
    if (document.status() == READY) {
      requireNonBlank(document.contentHash(), "content hash");
      Objects.requireNonNull(document.body(), "body");
      if (!Hashing.sha256HexUtf8(document.body()).equals(document.contentHash())) {
        throw invalidData("Document content hash does not match its body.");
      }
    } else if (document.contentHash() != null || document.body() != null) {
      throw invalidData("Pending and failed documents must not carry indexed content.");
    }
  }

  private record StoredDocument(
      String title, String path, String contentHash, String extractionStatus) {}

  private record DocumentBatchReport(int upserted, int metadataUpdated) {}

  private record LexicalDocument(long id, String title, String path, String body) {}
}
