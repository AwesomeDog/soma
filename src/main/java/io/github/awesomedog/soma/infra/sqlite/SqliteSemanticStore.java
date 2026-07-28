package io.github.awesomedog.soma.infra.sqlite;

import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.bindStringParameters;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.databaseFailure;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.invalidData;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.placeholders;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.requireNonBlank;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.serializeVectorAsJson;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.uniqueNonBlankStrings;
import static io.github.awesomedog.soma.infra.sqlite.SqliteWorkspaceIndex.validateEmbeddingVector;

import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ChunkWrite;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ChunkingWork;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.EmbeddingWork;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.EmbeddingWrite;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

// Owns chunk, embedding, and vector write-side persistence.
final class SqliteSemanticStore {

  private static final String SEMANTIC_RECIPE_KEY = "active.semantic_recipe_id";

  private final SqliteWorkspaceIndex workspaceIndex;

  SqliteSemanticStore(SqliteWorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
  }

  void resetSemanticIndexForRecipe(String desiredRecipeId) {
    try {
      if (workspaceIndex.hasActiveRecipe(SEMANTIC_RECIPE_KEY, desiredRecipeId)) {
        return;
      }
      workspaceIndex.executeTransaction(
          connection -> {
            executeUpdate(connection, "DELETE FROM vectors");
            executeUpdate(connection, "DELETE FROM chunks");
            workspaceIndex.publishActiveRecipe(connection, SEMANTIC_RECIPE_KEY, desiredRecipeId);
            return null;
          });
    } catch (SQLException e) {
      throw databaseFailure("Could not reset the semantic index.", "Run `soma system embed`.", e);
    }
  }

  List<ChunkingWork> chunkingWork(List<String> projects) {
    var projectScope = uniqueNonBlankStrings(projects);
    if (projectScope.isEmpty()) {
      return List.of();
    }
    var sql =
        """
        SELECT DISTINCT d.content_hash, c.body
        FROM documents AS d
        JOIN contents AS c ON c.content_hash = d.content_hash
        WHERE d.extraction_status = 'ready'
          AND d.project_name IN (%s)
          AND length(trim(c.body, char(9) || char(10) || char(13) || ' ')) > 0
          AND NOT EXISTS (
            SELECT 1 FROM chunks AS existing WHERE existing.content_hash = d.content_hash)
        ORDER BY d.content_hash
        """
            .formatted(placeholders(projectScope.size()));
    try (var statement = workspaceIndex.requireWriteConnection().prepareStatement(sql)) {
      bindStringParameters(statement, 1, projectScope);
      try (var resultSet = statement.executeQuery()) {
        var work = new ArrayList<ChunkingWork>();
        while (resultSet.next()) {
          work.add(
              new ChunkingWork(resultSet.getString("content_hash"), resultSet.getString("body")));
        }
        return List.copyOf(work);
      }
    } catch (SQLException e) {
      throw databaseFailure("Could not plan document chunking.", "Run `soma system embed`.", e);
    }
  }

  void writeChunks(String contentHash, List<ChunkWrite> chunks) {
    requireNonBlank(contentHash, "content hash");
    var chunkWrites = List.copyOf(Objects.requireNonNull(chunks, "chunks"));
    for (var chunkIndex = 0; chunkIndex < chunkWrites.size(); chunkIndex++) {
      if (chunkWrites.get(chunkIndex).index() != chunkIndex) {
        throw invalidData("Document chunks must have contiguous indexes.");
      }
    }
    if (chunkWrites.isEmpty()) {
      return;
    }
    try {
      workspaceIndex.executeTransaction(
          connection -> {
            try (var insert =
                connection.prepareStatement(
                    """
                    INSERT INTO chunks(
                        content_hash, chunk_index, char_start_offset, char_end_offset,
                        body, token_count)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
              for (var chunk : chunkWrites) {
                insert.setString(1, contentHash);
                insert.setInt(2, chunk.index());
                insert.setInt(3, chunk.charStartOffset());
                insert.setInt(4, chunk.charEndOffset());
                insert.setString(5, chunk.body());
                insert.setInt(6, chunk.tokenCount());
                insert.addBatch();
              }
              insert.executeBatch();
            }
            return null;
          });
    } catch (SQLException e) {
      throw databaseFailure("Could not write document chunks.", "Run `soma system embed`.", e);
    }
  }

  List<EmbeddingWork> embeddingWork(List<String> projects) {
    var projectScope = uniqueNonBlankStrings(projects);
    if (projectScope.isEmpty()) {
      return List.of();
    }
    var sql =
        """
        SELECT d.id, d.project_name, d.title, d.content_hash,
               c.chunk_index, c.body AS chunk_body
        FROM documents AS d
        JOIN chunks AS c ON c.content_hash = d.content_hash
        LEFT JOIN embeddings AS e
         ON e.document_id = d.id
         AND e.content_hash = d.content_hash
         AND e.chunk_index = c.chunk_index
        LEFT JOIN vectors AS v ON v.rowid = e.id
        WHERE d.extraction_status = 'ready'
          AND d.project_name IN (%s)
          AND (e.id IS NULL OR v.rowid IS NULL)
        ORDER BY d.project_name, d.path, c.chunk_index
        """
            .formatted(placeholders(projectScope.size()));
    try (var statement = workspaceIndex.requireWriteConnection().prepareStatement(sql)) {
      bindStringParameters(statement, 1, projectScope);
      try (var resultSet = statement.executeQuery()) {
        var work = new ArrayList<EmbeddingWork>();
        while (resultSet.next()) {
          work.add(
              new EmbeddingWork(
                  resultSet.getLong("id"),
                  resultSet.getString("project_name"),
                  resultSet.getString("title"),
                  resultSet.getString("content_hash"),
                  resultSet.getInt("chunk_index"),
                  resultSet.getString("chunk_body")));
        }
        return List.copyOf(work);
      }
    } catch (SQLException e) {
      throw databaseFailure("Could not plan embedding work.", "Run `soma system embed`.", e);
    }
  }

  void writeEmbeddings(List<EmbeddingWrite> embeddings) {
    var embeddingWrites = List.copyOf(Objects.requireNonNull(embeddings, "embeddings"));
    embeddingWrites.forEach(embedding -> validateEmbeddingVector(embedding.vector()));
    if (embeddingWrites.isEmpty()) {
      return;
    }
    try {
      workspaceIndex.executeTransaction(
          connection -> {
            try (var metadata =
                    connection.prepareStatement(
                        """
                        INSERT INTO embeddings(
                            document_id, content_hash, chunk_index, input_token_count, embedded_at)
                        SELECT d.id, d.content_hash, ?, ?, CURRENT_TIMESTAMP
                        FROM documents AS d
                        WHERE d.id = ? AND d.extraction_status = 'ready'
                        ON CONFLICT(document_id, chunk_index) DO UPDATE SET
                            content_hash = excluded.content_hash,
                            input_token_count = excluded.input_token_count,
                            embedded_at = CURRENT_TIMESTAMP
                        RETURNING id
                        """);
                var deleteVector =
                    connection.prepareStatement("DELETE FROM vectors WHERE rowid = ?");
                var insertVector =
                    connection.prepareStatement(
                        """
                        INSERT INTO vectors(rowid, project_name, embedding)
                        SELECT ?, d.project_name, ?
                        FROM embeddings AS e
                        JOIN documents AS d ON d.id = e.document_id
                        WHERE e.id = ?
                        """)) {
              for (var embedding : embeddingWrites) {
                metadata.setInt(1, embedding.chunkIndex());
                metadata.setInt(2, embedding.inputTokenCount());
                metadata.setLong(3, embedding.documentId());
                final long id;
                try (var rows = metadata.executeQuery()) {
                  if (!rows.next()) {
                    throw new SQLException("embedding id missing after upsert");
                  }
                  id = rows.getLong(1);
                }
                deleteVector.setLong(1, id);
                deleteVector.executeUpdate();
                insertVector.setLong(1, id);
                insertVector.setString(2, serializeVectorAsJson(embedding.vector()));
                insertVector.setLong(3, id);
                insertVector.executeUpdate();
              }
            }
            return null;
          });
    } catch (SQLException e) {
      throw databaseFailure(
          "Could not write embeddings and vectors.", "Run `soma system embed`.", e);
    }
  }

  private static void executeUpdate(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.executeUpdate(sql);
    }
  }
}
