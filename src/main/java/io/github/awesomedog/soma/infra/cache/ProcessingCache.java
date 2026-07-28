package io.github.awesomedog.soma.infra.cache;

import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.closeConnectionQuietly;
import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.deleteDatabase;
import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.isSchemaIncompatible;
import static io.github.awesomedog.soma.infra.sqlite.SqliteSupport.loadBundledSchema;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.awesomedog.soma.domain.recipe.RecipeId;
import io.github.awesomedog.soma.infra.sqlite.SqliteSupport.BundledSchema;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.PathSupport;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConnection;

@Singleton
public final class ProcessingCache {

  private static final Logger LOG = LoggerFactory.getLogger(ProcessingCache.class);
  private static final String SCHEMA_RESOURCE = "/db/cache-schema.sql";
  private static final String SCHEMA_HASH_KEY = "cache.schema.sha256";
  private static final int BUSY_TIMEOUT_MILLIS = 5000;

  private final Path databaseFile;
  private BundledSchema cachedSchema;

  @Inject
  public ProcessingCache() {
    this(PathSupport.somaStateCacheDirectory().resolve("cache.sqlite"));
  }

  public ProcessingCache(Path databaseFile) {
    this.databaseFile =
        Objects.requireNonNull(databaseFile, "databaseFile").toAbsolutePath().normalize();
  }

  public Optional<String> read(String operation, String recipeId, String inputHash) {
    var key = cacheKey(operation, recipeId, inputHash);
    try (var connection = openOrRecreateCompatibleCache();
        var statement =
            connection.prepareStatement(
                """
                SELECT operation, recipe_id, input_hash, payload
                FROM process_cache
                WHERE cache_key = ?
                """)) {
      statement.setBytes(1, key);
      try (var rows = statement.executeQuery()) {
        if (!rows.next()
            || !operation.equals(rows.getString("operation"))
            || !recipeId.equals(rows.getString("recipe_id"))
            || !inputHash.equals(rows.getString("input_hash"))) {
          return Optional.empty();
        }
        return decodeUtf8(rows.getBytes("payload"));
      }
    } catch (Exception failure) {
      deleteIncompatibleCacheDatabase(failure);
      LOG.debug("Processing cache read skipped for {}: {}", operation, failure.toString());
      return Optional.empty();
    }
  }

  public void write(String operation, String recipeId, String inputHash, String payload) {
    var key = cacheKey(operation, recipeId, inputHash);
    var encoded = Objects.requireNonNull(payload, "payload").getBytes(UTF_8);
    try (var connection = openOrRecreateCompatibleCache();
        var statement =
            connection.prepareStatement(
                """
                INSERT INTO process_cache(
                    cache_key, operation, recipe_id, input_hash, payload, created_at
                ) VALUES (?, ?, ?, ?, ?, unixepoch())
                ON CONFLICT(cache_key) DO UPDATE SET
                    operation = excluded.operation,
                    recipe_id = excluded.recipe_id,
                    input_hash = excluded.input_hash,
                    payload = excluded.payload,
                    created_at = unixepoch()
                """)) {
      statement.setBytes(1, key);
      statement.setString(2, operation);
      statement.setString(3, recipeId);
      statement.setString(4, inputHash);
      statement.setBytes(5, encoded);
      statement.executeUpdate();
    } catch (Exception failure) {
      deleteIncompatibleCacheDatabase(failure);
      LOG.debug("Processing cache write skipped for {}: {}", operation, failure.toString());
    }
  }

  private synchronized Connection openOrRecreateCompatibleCache() throws IOException, SQLException {
    var schema = bundledSchema();
    if (Files.exists(databaseFile) && !Files.isRegularFile(databaseFile)) {
      throw new IOException("processing cache path is not a regular file: " + databaseFile);
    }

    if (Files.isRegularFile(databaseFile)) {
      Connection connection = null;
      try {
        connection = openExistingCacheConnection();
        if (schema.sha256().equals(storedSchemaHash(connection))) {
          return connection;
        }
      } catch (SQLException failure) {
        closeConnectionQuietly(connection, failure);
        connection = null;
        if (!isSchemaIncompatible(failure)) {
          throw failure;
        }
      }
      closeConnectionQuietly(connection, null);
    }

    deleteDatabase(databaseFile);
    Files.createDirectories(databaseFile.getParent());
    Connection connection = null;
    try {
      connection = openNewCacheConnection();
      applySchema(connection, schema);
      return connection;
    } catch (SQLException | RuntimeException failure) {
      closeConnectionQuietly(connection, failure);
      try {
        deleteDatabase(databaseFile);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
  }

  private Connection openExistingCacheConnection() throws SQLException {
    return openCacheConnection(false);
  }

  private Connection openNewCacheConnection() throws SQLException {
    return openCacheConnection(true);
  }

  private Connection openCacheConnection(boolean initializeSchema) throws SQLException {
    var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile);
    try (var statement = connection.createStatement()) {
      if (initializeSchema) {
        configureNewDatabase(statement);
      }
      configureConnection(statement);
      return connection;
    } catch (SQLException failure) {
      closeConnectionQuietly(connection, failure);
      throw failure;
    }
  }

  private static void configureNewDatabase(Statement statement) throws SQLException {
    statement.execute("PRAGMA auto_vacuum = INCREMENTAL");
  }

  private static void configureConnection(Statement statement) throws SQLException {
    statement.execute("PRAGMA journal_mode = WAL");
    statement.execute("PRAGMA synchronous = NORMAL");
    statement.execute("PRAGMA busy_timeout = " + BUSY_TIMEOUT_MILLIS);
  }

  private static String storedSchemaHash(Connection connection) throws SQLException {
    try (var statement =
        connection.prepareStatement("SELECT value FROM cache_meta WHERE key = ?")) {
      statement.setString(1, SCHEMA_HASH_KEY);
      try (var rows = statement.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  private static void applySchema(Connection connection, BundledSchema schema) throws SQLException {
    var database = connection.unwrap(SQLiteConnection.class).getDatabase();
    var result = database._exec(schema.sql());
    if (result != 0) {
      database.throwex(result);
    }
    try (var statement =
        connection.prepareStatement("INSERT INTO cache_meta(key, value) VALUES (?, ?)")) {
      statement.setString(1, SCHEMA_HASH_KEY);
      statement.setString(2, schema.sha256());
      statement.executeUpdate();
    }
  }

  private synchronized BundledSchema bundledSchema() throws IOException {
    if (cachedSchema != null) {
      return cachedSchema;
    }
    cachedSchema =
        loadBundledSchema(
            ProcessingCache.class, SCHEMA_RESOURCE, "bundled processing cache schema is missing: ");
    return cachedSchema;
  }

  private void deleteIncompatibleCacheDatabase(Exception failure) {
    if (!(failure instanceof SQLException sql) || !isSchemaIncompatible(sql)) {
      return;
    }
    synchronized (this) {
      try {
        deleteDatabase(databaseFile);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
    }
  }

  private static byte[] cacheKey(String operation, String recipeId, String inputHash) {
    requireNonBlank(operation, "operation");
    if (RecipeId.isInvalid(recipeId)) {
      throw new IllegalArgumentException("recipeId must be lowercase SHA-256 hex");
    }
    if (!Hashing.isLowercaseSha256(inputHash)) {
      throw new IllegalArgumentException("inputHash must be lowercase SHA-256 hex");
    }
    return HexFormat.of()
        .parseHex(RecipeId.of("soma.processing-cache", "v1", operation, recipeId, inputHash));
  }

  private static Optional<String> decodeUtf8(byte[] payload) {
    if (payload == null) {
      return Optional.empty();
    }
    try {
      var decoder =
          UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT);
      return Optional.of(decoder.decode(ByteBuffer.wrap(payload)).toString());
    } catch (CharacterCodingException failure) {
      return Optional.empty();
    }
  }

  private static void requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
  }
}
