package io.github.awesomedog.soma.infra.cache;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.github.awesomedog.soma.domain.recipe.RecipeId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessingCacheTest {

  private static final String SOURCE_HASH = "a".repeat(64);
  private static final String PDF_RECIPE = RecipeId.of("pdf.text", "v1");
  private static final String OTHER_RECIPE = RecipeId.of("pdf.text", "v2");

  @TempDir Path temporaryDirectory;

  @Test
  void initializesLazilyAndKeepsKeysAndSchemaIndependent() throws Exception {
    var database = temporaryDirectory.resolve("data/soma/cache.sqlite");
    var cache = new ProcessingCache(database);

    assertThat(database).doesNotExist();
    try (var resource = ProcessingCache.class.getResourceAsStream("/db/cache-schema.sql")) {
      assertThat(resource).isNotNull();
      assertThat(new String(resource.readAllBytes(), UTF_8))
          .isEqualTo(Files.readString(Path.of("docs/specs/cache-schema.sql"), UTF_8));
    }
    assertThat(cache.read("pdf.text", PDF_RECIPE, SOURCE_HASH)).isEmpty();
    assertThat(database).isRegularFile();

    cache.write("pdf.text", PDF_RECIPE, SOURCE_HASH, "cached text");

    assertThat(cache.read("pdf.text", PDF_RECIPE, SOURCE_HASH)).contains("cached text");
    assertThat(cache.read("image.describe", PDF_RECIPE, SOURCE_HASH)).isEmpty();
    assertThat(cache.read("pdf.text", OTHER_RECIPE, SOURCE_HASH)).isEmpty();
    assertThat(cache.read("pdf.text", PDF_RECIPE, "b".repeat(64))).isEmpty();

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
      assertThat(queryStrings(connection, "SELECT name FROM sqlite_master WHERE type = 'table'"))
          .containsExactlyInAnyOrder("cache_meta", "process_cache");
      assertThat(queryStrings(connection, "SELECT name FROM sqlite_master WHERE type = 'trigger'"))
          .containsExactly("trg_process_cache_evict");
      assertThat(queryString(connection, "PRAGMA journal_mode")).isEqualTo("wal");
      assertThat(queryString(connection, "PRAGMA auto_vacuum")).isEqualTo("2");
      assertThat(
              queryString(
                  connection, "SELECT value FROM cache_meta WHERE key = 'cache.schema.sha256'"))
          .isEqualTo(bundledSchemaHash());
    }
  }

  @Test
  void recreatesAnIncompatibleOrCorruptCache() throws Exception {
    var database = temporaryDirectory.resolve("cache.sqlite");
    var cache = new ProcessingCache(database);
    cache.write("pdf.text", PDF_RECIPE, SOURCE_HASH, "old value");

    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE cache_meta SET value = 'wrong' WHERE key = 'cache.schema.sha256'");
    }

    assertThat(cache.read("pdf.text", PDF_RECIPE, SOURCE_HASH)).isEmpty();
    assertThat(rowCount(database)).isZero();

    Files.writeString(database, "not a sqlite database");

    assertThat(cache.read("pdf.text", PDF_RECIPE, SOURCE_HASH)).isEmpty();
    cache.write("pdf.text", PDF_RECIPE, SOURCE_HASH, "new value");
    assertThat(cache.read("pdf.text", PDF_RECIPE, SOURCE_HASH)).contains("new value");
  }

  @Test
  void failuresRemainMissesAndSkippedWrites() throws Exception {
    var parent = Files.writeString(temporaryDirectory.resolve("not-a-directory"), "file");
    var cache = new ProcessingCache(parent.resolve("cache.sqlite"));

    assertThat(cache.read("pdf.text", PDF_RECIPE, SOURCE_HASH)).isEmpty();
    assertThatCode(() -> cache.write("pdf.text", PDF_RECIPE, SOURCE_HASH, "ignored"))
        .doesNotThrowAnyException();
  }

  private static int rowCount(Path database) throws Exception {
    try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        var rows =
            connection.createStatement().executeQuery("SELECT count(*) FROM process_cache")) {
      assertThat(rows.next()).isTrue();
      return rows.getInt(1);
    }
  }

  private static String queryString(java.sql.Connection connection, String sql) throws Exception {
    try (var rows = connection.createStatement().executeQuery(sql)) {
      assertThat(rows.next()).isTrue();
      return rows.getString(1);
    }
  }

  private static java.util.List<String> queryStrings(java.sql.Connection connection, String sql)
      throws Exception {
    var values = new java.util.ArrayList<String>();
    try (var rows = connection.createStatement().executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return java.util.List.copyOf(values);
  }

  private static String bundledSchemaHash() throws Exception {
    try (var input = ProcessingCache.class.getResourceAsStream("/db/cache-schema.sql")) {
      assertThat(input).isNotNull();
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
    }
  }
}
