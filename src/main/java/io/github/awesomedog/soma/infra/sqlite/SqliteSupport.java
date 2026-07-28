package io.github.awesomedog.soma.infra.sqlite;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.awesomedog.soma.support.Hashing;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

public final class SqliteSupport {

  private SqliteSupport() {}

  public static BundledSchema loadBundledSchema(
      Class<?> owner, String resource, String missingMessage) throws IOException {
    try (var input = Objects.requireNonNull(owner, "owner").getResourceAsStream(resource)) {
      if (input == null) {
        throw new IOException(missingMessage + resource);
      }
      var bytes = input.readAllBytes();
      return new BundledSchema(new String(bytes, UTF_8), Hashing.sha256Hex(bytes));
    }
  }

  public static void deleteDatabase(Path file) throws IOException {
    Files.deleteIfExists(file);
    Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-wal"));
    Files.deleteIfExists(file.resolveSibling(file.getFileName() + "-shm"));
  }

  public static void closeConnectionQuietly(Connection connection, Exception failure) {
    if (connection == null) {
      return;
    }
    try {
      connection.close();
    } catch (SQLException closeError) {
      if (failure != null) {
        failure.addSuppressed(closeError);
      }
    }
  }

  public static boolean isCorruptDatabase(SQLException error) {
    if (!(error instanceof SQLiteException sqlite)) {
      return false;
    }
    var code = sqlite.getResultCode();
    return code == SQLiteErrorCode.SQLITE_NOTADB || code.name().startsWith("SQLITE_CORRUPT");
  }

  public static boolean isSchemaIncompatible(SQLException error) {
    return isCorruptDatabase(error)
        || error instanceof SQLiteException sqlite
            && (sqlite.getResultCode() == SQLiteErrorCode.SQLITE_ERROR
                || sqlite.getResultCode() == SQLiteErrorCode.SQLITE_SCHEMA);
  }

  public record BundledSchema(String sql, String sha256) {}
}
