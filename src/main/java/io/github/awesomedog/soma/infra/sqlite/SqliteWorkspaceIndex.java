package io.github.awesomedog.soma.infra.sqlite;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;
import static io.github.awesomedog.soma.app.common.AppError.Code.WRITE_LOCKED;
import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.closeConnectionQuietly;
import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.deleteDatabase;
import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.isCorruptDatabase;
import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.isSchemaIncompatible;
import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.loadBundledSchema;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import io.github.awesomedog.soma.domain.search.LexicalQuery;
import io.github.awesomedog.soma.infra.sqlite.SqliteSupport.BundledSchema;
import io.github.awesomedog.soma.support.HostPlatform;
import io.github.awesomedog.soma.support.PathSupport;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.sqlite.SQLiteConnection;

@Singleton
public final class SqliteWorkspaceIndex implements WorkspaceIndex, AutoCloseable {

  private static final String SCHEMA_RESOURCE = "/db/schema.sql";
  private static final String SCHEMA_HASH_KEY = "database.schema.sha256";
  private static final String SQLITE_VEC_VERSION = "0.1.9";
  static final int WRITE_BATCH_SIZE = 100;
  private static final Map<String, String> SQLITE_VEC_BINARIES =
      Map.of(
          "darwin-arm64", "vec0.dylib",
          "linux-x86_64", "vec0.so",
          "windows-x86_64", "vec0.dll");

  private final Path runtimeDataDirectory;
  private final String hostPlatform;
  private final SqliteDocumentStore documents;
  private final SqliteSemanticStore semantics;
  private final SqliteIndexQueries queries;
  private Connection writeConnection;
  private Path openDatabaseFile;
  private WriteLock.Token openWriteLock;

  @Inject
  public SqliteWorkspaceIndex() {
    this(PathSupport.somaDataDirectory(), HostPlatform.current().id());
  }

  SqliteWorkspaceIndex(Path runtimeDataDirectory, String hostPlatform) {
    this.runtimeDataDirectory = Objects.requireNonNull(runtimeDataDirectory, "dataDirectory");
    this.hostPlatform = Objects.requireNonNull(hostPlatform, "platform");
    this.documents = new SqliteDocumentStore(this);
    this.semantics = new SqliteSemanticStore(this);
    this.queries = new SqliteIndexQueries(this);
  }

  @Override
  public synchronized void openOrRebuildForScan(Path databaseFile, WriteLock.Token writeLock) {
    requireWriteLock(writeLock, "Opening or rebuilding the workspace index");
    var file = normalizeDatabaseFile(databaseFile);
    close();
    if (Files.exists(file) && !Files.isRegularFile(file)) {
      throw createOpenOrRebuildFailure(
          new IOException("workspace index path is not a regular file: " + file));
    }

    Connection connection = null;
    var rebuilding = !Files.isRegularFile(file);
    try {
      Files.createDirectories(file.getParent());
      var schema = readBundledSchema();
      if (!rebuilding) {
        try {
          connection = openConnection(file);
        } catch (SQLException e) {
          if (!isCorruptDatabase(e)) {
            throw e;
          }
          rebuilding = true;
        }
        if (!rebuilding) {
          try {
            rebuilding = !schema.sha256().equals(readStoredSchemaHash(connection));
          } catch (SQLException e) {
            if (!isSchemaIncompatible(e)) {
              throw e;
            }
            rebuilding = true;
          }
        }
      }

      if (rebuilding) {
        closeConnectionQuietly(connection, null);
        connection = null;
        deleteDatabase(file);
        connection = openConnection(file);
        if (!applyBundledSchema(connection, schema)) {
          connection = openConnection(file);
        }
      }
      writeConnection = connection;
      openDatabaseFile = file;
      openWriteLock = writeLock;
    } catch (Exception e) {
      cleanupFailedIndexOpenOrRebuild(connection, file, rebuilding, e);
      throw createOpenOrRebuildFailure(e);
    }
  }

  @Override
  public synchronized void openExistingForWrite(Path databaseFile, WriteLock.Token writeLock) {
    requireWriteLock(writeLock, "Opening the workspace index");
    var file = normalizeDatabaseFile(databaseFile);
    if (hasOpenWriteConnection(file, writeLock)) {
      return;
    }
    close();
    if (!Files.isRegularFile(file)) {
      throw createIncompatibleIndexError(null);
    }

    Connection connection = null;
    try {
      connection = openConnection(file);
      if (!readBundledSchema().sha256().equals(readStoredSchemaHash(connection))) {
        throw createIncompatibleIndexError(null);
      }
      writeConnection = connection;
      openDatabaseFile = file;
      openWriteLock = writeLock;
    } catch (AppException e) {
      closeConnectionQuietly(connection, e);
      throw e;
    } catch (Exception e) {
      closeConnectionQuietly(connection, e);
      throw createIncompatibleIndexError(e);
    }
  }

  @Override
  public synchronized void resetForFullScan() {
    try {
      clearTableRowsInBatches("vectors");
      clearTableRowsInBatches("embeddings");
      clearTableRowsInBatches("chunks");
      clearTableRowsInBatches("fts_index");
      clearTableRowsInBatches("documents");
      clearTableRowsInBatches("contents");
      executeTransaction(
          connection -> {
            try (var statement =
                connection.prepareStatement("DELETE FROM soma_meta WHERE key <> ?")) {
              statement.setString(1, SCHEMA_HASH_KEY);
              statement.executeUpdate();
            }
            return null;
          });
    } catch (SQLException e) {
      throw databaseFailure(
          "Could not clear the workspace index for a full scan.",
          "Run `soma sync` after fixing the database.",
          e);
    }
  }

  @Override
  public synchronized void rebuildLexicalIndexForRecipe(String desiredRecipeId) {
    documents.rebuildLexicalIndexForRecipe(desiredRecipeId);
  }

  @Override
  public synchronized List<DocumentSnapshot> documentSnapshots() {
    return documents.documentSnapshots();
  }

  @Override
  public synchronized DocumentScanReport applyScan(
      List<DocumentWrite> inspectedDocuments,
      List<Long> removedDocumentIds,
      int unchanged,
      Consumer<Long> progress) {
    return documents.applyScan(inspectedDocuments, removedDocumentIds, unchanged, progress);
  }

  @Override
  public synchronized void invalidateExtractionForRecipeChanges(
      Map<FileType, String> desiredRecipeIds) {
    documents.invalidateExtractionForRecipeChanges(desiredRecipeIds);
  }

  @Override
  public synchronized List<ExtractionWork> extractionWork() {
    return documents.extractionWork();
  }

  @Override
  public synchronized void publishExtraction(
      long documentId, String contentHash, String extractedBody) {
    documents.publishExtraction(documentId, contentHash, extractedBody);
  }

  @Override
  public synchronized void failExtraction(long documentId) {
    documents.failExtraction(documentId);
  }

  @Override
  public synchronized void resetSemanticIndexForRecipe(String desiredRecipeId) {
    semantics.resetSemanticIndexForRecipe(desiredRecipeId);
  }

  @Override
  public synchronized List<ChunkingWork> chunkingWork(List<String> projectNames) {
    return semantics.chunkingWork(projectNames);
  }

  @Override
  public synchronized void writeChunks(String contentHash, List<ChunkWrite> chunks) {
    semantics.writeChunks(contentHash, chunks);
  }

  @Override
  public synchronized List<EmbeddingWork> embeddingWork(List<String> projectNames) {
    return semantics.embeddingWork(projectNames);
  }

  @Override
  public synchronized void writeEmbeddings(List<EmbeddingWrite> embeddings) {
    semantics.writeEmbeddings(embeddings);
  }

  @Override
  public synchronized int cleanOrphans() {
    try {
      return executeTransaction(
          connection -> {
            try (var statement =
                connection.prepareStatement(
                    """
                    DELETE FROM contents
                    WHERE NOT EXISTS (
                        SELECT 1 FROM documents AS d
                        WHERE d.content_hash = contents.content_hash)
                    """)) {
              return statement.executeUpdate();
            }
          });
    } catch (SQLException e) {
      throw databaseFailure("Could not clean orphaned index records.", "Run `soma sync`.", e);
    }
  }

  @Override
  public List<DocumentRead> listDocuments(
      Path databaseFile, String projectName, String pathPrefix) {
    return queries.listDocuments(databaseFile, projectName, pathPrefix);
  }

  @Override
  public Optional<DocumentRead> findDocument(
      Path databaseFile, String projectName, String documentPath, long maximumBodyBytes) {
    return queries.findDocument(databaseFile, projectName, documentPath, maximumBodyBytes);
  }

  @Override
  public List<DocumentRead> findReadyDocumentsByDocId(
      Path databaseFile, String docId, long maximumBodyBytes) {
    return queries.findReadyDocumentsByDocId(databaseFile, docId, maximumBodyBytes);
  }

  @Override
  public Map<String, ProjectStats> projectStats(Path databaseFile, List<String> projectNames) {
    return queries.projectStats(databaseFile, projectNames);
  }

  @Override
  public List<SearchHit> lexicalSearch(
      Path databaseFile, List<String> projectNames, LexicalQuery lexicalQuery, int candidateLimit) {
    return queries.lexicalSearch(databaseFile, projectNames, lexicalQuery, candidateLimit);
  }

  @Override
  public List<ChunkRead> chunks(Path databaseFile, List<String> contentHashes) {
    return queries.chunks(databaseFile, contentHashes);
  }

  @Override
  public List<SearchHit> vectorSearch(
      Path databaseFile, List<String> projectNames, float[] queryVector, int candidateLimit) {
    return queries.vectorSearch(databaseFile, projectNames, queryVector, candidateLimit);
  }

  @Override
  @PreDestroy
  public synchronized void close() {
    if (writeConnection != null) {
      try {
        writeConnection.close();
      } catch (SQLException ignored) {
        // Shutdown cannot recover a failed JDBC close.
      }
    }
    writeConnection = null;
    openDatabaseFile = null;
    openWriteLock = null;
  }

  private boolean hasOpenWriteConnection(Path databaseFile, WriteLock.Token writeLock) {
    try {
      return writeConnection != null
          && !writeConnection.isClosed()
          && databaseFile.equals(openDatabaseFile)
          && writeLock == openWriteLock
          && Files.isRegularFile(databaseFile);
    } catch (SQLException ignored) {
      return false;
    }
  }

  Connection openVerifiedReadConnection(Path databaseFile) throws SQLException, IOException {
    var file = normalizeDatabaseFile(databaseFile);
    if (!Files.isRegularFile(file)) {
      throw createIncompatibleIndexError(null);
    }
    Connection connection = null;
    try {
      connection = openConnection(file);
      if (!readBundledSchema().sha256().equals(readStoredSchemaHash(connection))) {
        throw createIncompatibleIndexError(null);
      }
      return connection;
    } catch (Exception e) {
      closeConnectionQuietly(connection, e);
      if (e instanceof AppException appException) {
        throw appException;
      }
      throw createIncompatibleIndexError(e);
    }
  }

  Connection requireWriteConnection() throws SQLException {
    if (writeConnection == null
        || writeConnection.isClosed()
        || openDatabaseFile == null
        || !Files.isRegularFile(openDatabaseFile)) {
      throw new AppException(
          OPERATION_FAILED, "The workspace index is not open for writing.", "Run `soma sync`.");
    }
    return writeConnection;
  }

  void clearTableRowsInBatches(String table) throws SQLException {
    clearMatchingRowsInBatches(table, "1 = 1", List.of());
  }

  void clearMatchingRowsInBatches(String table, String condition, List<String> parameters)
      throws SQLException {
    int deleted;
    do {
      deleted =
          executeTransaction(
              connection -> {
                var sql =
                    "DELETE FROM "
                        + table
                        + " WHERE rowid IN (SELECT rowid FROM "
                        + table
                        + " WHERE "
                        + condition
                        + " LIMIT "
                        + WRITE_BATCH_SIZE
                        + ")";
                try (var statement = connection.prepareStatement(sql)) {
                  bindStringParameters(statement, 1, parameters);
                  return statement.executeUpdate();
                }
              });
    } while (deleted > 0);
  }

  <T> T executeTransaction(SqlWork<T> work) throws SQLException {
    var connection = requireWriteConnection();
    connection.setAutoCommit(false);
    Exception primaryFailure = null;
    var committed = false;
    try {
      var result = work.run(connection);
      connection.commit();
      committed = true;
      return result;
    } catch (SQLException | RuntimeException e) {
      primaryFailure = e;
      try {
        connection.rollback();
      } catch (SQLException rollbackError) {
        e.addSuppressed(rollbackError);
      }
      throw e;
    } finally {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException restoreError) {
        discardWriteConnection(connection, restoreError);
        if (primaryFailure != null) {
          primaryFailure.addSuppressed(restoreError);
        } else if (!committed) {
          throw restoreError;
        }
      }
    }
  }

  boolean hasActiveRecipe(String key, String recipeId) throws SQLException {
    requireNonBlank(key, "recipe key");
    requireRecipeId(recipeId);
    try (var statement =
        requireWriteConnection().prepareStatement("SELECT value FROM soma_meta WHERE key = ?")) {
      statement.setString(1, key);
      try (var rows = statement.executeQuery()) {
        return rows.next() && recipeId.equals(rows.getString(1));
      }
    }
  }

  void publishActiveRecipe(String key, String recipeId) throws SQLException {
    executeTransaction(
        connection -> {
          publishActiveRecipe(connection, key, recipeId);
          return null;
        });
  }

  void publishActiveRecipe(Connection connection, String key, String recipeId) throws SQLException {
    requireNonBlank(key, "recipe key");
    requireRecipeId(recipeId);
    try (var statement =
        connection.prepareStatement(
            """
            INSERT INTO soma_meta(key, value, updated_at)
            VALUES (?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(key) DO UPDATE SET
                value = excluded.value,
                updated_at = CURRENT_TIMESTAMP
            """)) {
      statement.setString(1, key);
      statement.setString(2, recipeId);
      statement.executeUpdate();
    }
  }

  private Connection openConnection(Path databaseFile) throws SQLException, IOException {
    var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
    try {
      try (var statement = connection.createStatement()) {
        statement.execute("PRAGMA journal_mode = WAL");
        statement.execute("PRAGMA foreign_keys = ON");
        statement.execute("PRAGMA busy_timeout = 5000");
      }
      loadSqliteVecExtension(connection);
      return connection;
    } catch (SQLException | IOException | RuntimeException e) {
      closeConnectionQuietly(connection, e);
      throw e;
    }
  }

  private void loadSqliteVecExtension(Connection connection) throws SQLException, IOException {
    var sqlite = connection.unwrap(SQLiteConnection.class);
    var extension = extractSqliteVecLibrary();
    sqlite.getDatabase().enable_load_extension(true);
    try (var statement = connection.prepareStatement("SELECT load_extension(?)")) {
      statement.setString(1, sqliteExtensionLoadName(extension));
      statement.execute();
    } finally {
      sqlite.getDatabase().enable_load_extension(false);
    }
    try (var statement = connection.createStatement();
        var result = statement.executeQuery("SELECT vec_version()")) {
      if (!result.next()) {
        throw new SQLException("sqlite-vec did not report its version");
      }
    }
  }

  private String sqliteExtensionLoadName(Path libraryPath) {
    var libraryFileName = SQLITE_VEC_BINARIES.get(hostPlatform);
    if (libraryFileName == null) {
      return libraryPath.toString();
    }
    var actualFileName = libraryPath.getFileName().toString();
    var suffixSeparator = libraryFileName.lastIndexOf('.');
    if (!actualFileName.equals(libraryFileName) || suffixSeparator <= 0) {
      return libraryPath.toString();
    }
    // SQLite appends the platform suffix when loading an extension by name.
    return libraryPath.resolveSibling(libraryFileName.substring(0, suffixSeparator)).toString();
  }

  private Path extractSqliteVecLibrary() throws IOException {
    var libraryFileName = SQLITE_VEC_BINARIES.get(hostPlatform);
    if (libraryFileName == null) {
      throw new IOException("sqlite-vec does not support host platform: " + hostPlatform);
    }
    var libraryResourcePath =
        "/sqlite-vec/" + SQLITE_VEC_VERSION + "/" + hostPlatform + "/" + libraryFileName;
    var libraryPath =
        runtimeDataDirectory
            .resolve("sqlite-vec")
            .resolve(SQLITE_VEC_VERSION)
            .resolve(hostPlatform)
            .resolve(libraryFileName);
    if (Files.isRegularFile(libraryPath) && Files.size(libraryPath) > 0) {
      return libraryPath;
    }

    Files.createDirectories(libraryPath.getParent());
    var temporaryLibraryPath =
        Files.createTempFile(libraryPath.getParent(), libraryFileName + ".", ".tmp");
    try {
      try (var input = SqliteWorkspaceIndex.class.getResourceAsStream(libraryResourcePath)) {
        if (input == null) {
          throw new IOException("bundled sqlite-vec library is missing: " + libraryResourcePath);
        }
        Files.copy(input, temporaryLibraryPath, REPLACE_EXISTING);
      }
      try {
        Files.move(temporaryLibraryPath, libraryPath, REPLACE_EXISTING, ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporaryLibraryPath, libraryPath, REPLACE_EXISTING);
      }
      return libraryPath;
    } finally {
      Files.deleteIfExists(temporaryLibraryPath);
    }
  }

  private static String readStoredSchemaHash(Connection connection) throws SQLException {
    try (var statement = connection.createStatement();
        var tables =
            statement.executeQuery(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'soma_meta'")) {
      if (!tables.next()) {
        return null;
      }
    }
    try (var statement = connection.prepareStatement("SELECT value FROM soma_meta WHERE key = ?")) {
      statement.setString(1, SCHEMA_HASH_KEY);
      try (var result = statement.executeQuery()) {
        return result.next() ? result.getString(1) : null;
      }
    }
  }

  private static boolean applyBundledSchema(Connection connection, BundledSchema schema)
      throws SQLException {
    var firstStatement = schema.sql().indexOf("CREATE TABLE");
    if (firstStatement < 0) {
      throw new SQLException("bundled workspace schema contains no tables");
    }
    connection.setAutoCommit(false);
    Exception primaryFailure = null;
    var committed = false;
    var connectionUsable = true;
    try {
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(schema.sql().substring(firstStatement));
      }
      try (var statement =
          connection.prepareStatement("INSERT INTO soma_meta(key, value) VALUES (?, ?)")) {
        statement.setString(1, SCHEMA_HASH_KEY);
        statement.setString(2, schema.sha256());
        statement.executeUpdate();
      }
      connection.commit();
      committed = true;
    } catch (SQLException | RuntimeException e) {
      primaryFailure = e;
      try {
        connection.rollback();
      } catch (SQLException rollbackError) {
        e.addSuppressed(rollbackError);
      }
      throw e;
    } finally {
      try {
        connection.setAutoCommit(true);
      } catch (SQLException restoreError) {
        connectionUsable = false;
        closeConnectionQuietly(connection, restoreError);
        if (primaryFailure != null) {
          primaryFailure.addSuppressed(restoreError);
        } else if (!committed) {
          throw restoreError;
        }
      }
    }
    return connectionUsable;
  }

  private static BundledSchema readBundledSchema() throws IOException {
    return loadBundledSchema(
        SqliteWorkspaceIndex.class, SCHEMA_RESOURCE, "bundled workspace schema is missing: ");
  }

  static void validateEmbeddingVector(float[] vector) {
    if (vector == null || vector.length != SearchModels.VECTOR_DIMENSIONS) {
      throw invalidData(
          "Embedding vectors must contain exactly " + SearchModels.VECTOR_DIMENSIONS + " values.");
    }
    for (var value : vector) {
      if (!Float.isFinite(value)) {
        throw invalidData("Embedding vectors must contain only finite values.");
      }
    }
  }

  static String serializeVectorAsJson(float[] vector) {
    var out = new StringBuilder("[");
    for (var valueIndex = 0; valueIndex < vector.length; valueIndex++) {
      if (valueIndex > 0) {
        out.append(',');
      }
      out.append(vector[valueIndex]);
    }
    return out.append(']').toString();
  }

  static void requireNonBlank(String value, String label) {
    if (value == null || value.isBlank()) {
      throw invalidData("The " + label + " must not be blank.");
    }
  }

  static AppException invalidData(String message) {
    return new AppException(
        OPERATION_FAILED, message, "Run the corresponding `soma system` command again.");
  }

  static void requireSingleRowUpdated(int updatedRowCount, String label) throws SQLException {
    if (updatedRowCount != 1) {
      throw new SQLException(label + " was not found");
    }
  }

  static List<String> uniqueNonBlankStrings(List<String> inputValues) {
    if (inputValues == null || inputValues.isEmpty()) {
      return List.of();
    }
    var uniqueValues = new LinkedHashSet<String>();
    for (var value : inputValues) {
      requireNonBlank(value, "project");
      uniqueValues.add(value);
    }
    return List.copyOf(uniqueValues);
  }

  static String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(count, "?"));
  }

  static void bindStringParameters(
      java.sql.PreparedStatement statement, int first, List<String> values) throws SQLException {
    for (var valueIndex = 0; valueIndex < values.size(); valueIndex++) {
      statement.setString(first + valueIndex, values.get(valueIndex));
    }
  }

  static void bindNullableString(
      java.sql.PreparedStatement statement, int parameterIndex, String value) throws SQLException {
    if (value == null) {
      statement.setNull(parameterIndex, Types.VARCHAR);
    } else {
      statement.setString(parameterIndex, value);
    }
  }

  static FileType parseFileType(String value) {
    return FileType.valueOf(value.toUpperCase(Locale.ROOT));
  }

  private static void requireRecipeId(String recipeId) {
    if (RecipeId.isInvalid(recipeId)) {
      throw invalidData("Recipe IDs must be lowercase SHA-256 values.");
    }
  }

  private static Path normalizeDatabaseFile(Path databaseFile) {
    return Objects.requireNonNull(databaseFile, "databaseFile").toAbsolutePath().normalize();
  }

  private static void requireWriteLock(WriteLock.Token writeLock, String action) {
    if (writeLock == null) {
      throw new AppException(
          WRITE_LOCKED, action + " requires the workspace write lock.", "Retry with `soma sync`.");
    }
  }

  private void discardWriteConnection(Connection connection, Exception failure) {
    if (writeConnection == connection) {
      writeConnection = null;
      openDatabaseFile = null;
      openWriteLock = null;
    }
    closeConnectionQuietly(connection, failure);
  }

  private static void cleanupFailedIndexOpenOrRebuild(
      Connection connection, Path databaseFile, boolean rebuilding, Exception failure) {
    closeConnectionQuietly(connection, failure);
    if (!rebuilding) {
      return;
    }
    try {
      deleteDatabase(databaseFile);
    } catch (IOException cleanupError) {
      failure.addSuppressed(cleanupError);
    }
  }

  private static AppException createOpenOrRebuildFailure(Exception cause) {
    return databaseFailure(
        "Could not open or rebuild the workspace index database.",
        "Check database permissions and sqlite-vec, then run `soma sync`.",
        cause);
  }

  private static AppException createIncompatibleIndexError(Exception cause) {
    var error =
        AppError.of(
            OPERATION_FAILED,
            "The workspace index database is missing, corrupt, or incompatible.",
            "Run `soma sync`.");
    return cause == null ? new AppException(error) : new AppException(error, cause);
  }

  static AppException databaseFailure(String message, String remediation, Exception cause) {
    return new AppException(OPERATION_FAILED, message, remediation, cause);
  }

  @FunctionalInterface
  interface SqlWork<T> {
    T run(Connection connection) throws SQLException;
  }
}
