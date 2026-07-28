package io.github.awesomedog.soma.infra.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ChunkWrite;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.DocumentWrite;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.EmbeddingWork;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.EmbeddingWrite;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.domain.document.ExtractionStatus;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import io.github.awesomedog.soma.domain.search.LexicalQuery;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.HostPlatform;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConnection;

class SqliteWorkspaceIndexTest {

  private static final WriteLock.Token WRITE_LOCK = () -> {};
  private static final Consumer<Long> NO_PROGRESS = ignored -> {};
  private static final String PDF_V1 = recipe("pdf-v1");
  private static final String PDF_V2 = recipe("pdf-v2");
  private static final String PDF_V3 = recipe("pdf-v3");
  private static final String IMAGE_V1 = recipe("image-v1");
  private static final String MEDIA_V1 = recipe("media-v1");
  private static final String SEMANTIC_V1 = recipe("semantic-v1");
  private static final String SEMANTIC_V2 = recipe("semantic-v2");

  @TempDir Path temporaryDirectory;

  private Path dataDirectory;
  private Path database;
  private SqliteWorkspaceIndex index;

  @BeforeEach
  void setUp() {
    dataDirectory = temporaryDirectory.resolve("data");
    database = temporaryDirectory.resolve("state/workspace.sqlite");
    index = new SqliteWorkspaceIndex(dataDirectory, HostPlatform.current().id());
  }

  @AfterEach
  void closeIndex() {
    index.close();
  }

  @Test
  void openOrRebuildForScanRecreatesAnIncompatibleIndexButOpenExistingForWriteNeverDoes()
      throws Exception {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    index.applyScan(List.of(ready("docs", "kept.md", "Kept body")), List.of(), 0, NO_PROGRESS);
    index.close();

    try (var connection = connection();
        var statement =
            connection.prepareStatement(
                "UPDATE soma_meta SET value = 'wrong' WHERE key = 'database.schema.sha256'")) {
      assertThat(statement.executeUpdate()).isOne();
    }

    assertThatThrownBy(() -> index.openExistingForWrite(database, WRITE_LOCK))
        .isInstanceOfSatisfying(
            AppException.class,
            error -> assertThat(error.error().code()).isEqualTo(AppError.Code.OPERATION_FAILED));
    try (var connection = connection();
        var statement =
            connection.prepareStatement(
                "SELECT value FROM soma_meta WHERE key = 'database.schema.sha256'");
        var rows = statement.executeQuery()) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("wrong");
    }

    index.openOrRebuildForScan(database, WRITE_LOCK);
    assertThat(
            index.lexicalSearch(
                database, List.of("docs"), LexicalQuery.parse("kept"), Integer.MAX_VALUE))
        .isEmpty();

    index.close();
    var missing = temporaryDirectory.resolve("state/missing.sqlite");
    assertThatThrownBy(() -> index.openExistingForWrite(missing, WRITE_LOCK))
        .isInstanceOf(AppException.class);
    assertThat(missing).doesNotExist();
  }

  @Test
  void documentBatchesAreAtomicAndWriteOnlyReadyDocumentsToFts() {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    index.resetForFullScan();

    var ready = ready("docs", "ready.md", "Alpha searchable body");
    var pending = pending("docs", "scan.pdf", FileType.PDF);
    index.applyScan(List.of(ready, pending), List.of(), 0, NO_PROGRESS);

    assertThat(index.chunkingWork(List.of("docs")))
        .extracting(work -> work.body())
        .containsExactly("Alpha searchable body");
    invalidateExtractionForRecipeChanges(PDF_V1);
    assertThat(index.extractionWork()).extracting(work -> work.path()).containsExactly("scan.pdf");
    assertThat(
            index.lexicalSearch(
                database, List.of("docs"), LexicalQuery.parse("alpha"), Integer.MAX_VALUE))
        .extracting(hit -> hit.path())
        .containsExactly("ready.md");
    assertThat(
            index.lexicalSearch(
                database,
                List.of("docs"),
                LexicalQuery.parse("alpha -searchable"),
                Integer.MAX_VALUE))
        .isEmpty();

    var first = ready("docs", "rolled-back.md", "First batch body");
    var invalid =
        new DocumentWrite(
            "docs",
            "invalid.md",
            null,
            "not-a-sha256",
            "invalid",
            1,
            1,
            FileType.TEXT,
            ExtractionStatus.READY,
            "Invalid body");
    assertThatThrownBy(() -> index.applyScan(List.of(first, invalid), List.of(), 0, NO_PROGRESS))
        .isInstanceOf(AppException.class);
    assertThat(
            index.lexicalSearch(
                database, List.of("docs"), LexicalQuery.parse("first"), Integer.MAX_VALUE))
        .isEmpty();
  }

  @Test
  void documentSyncReportsCompletedWriteAndDeleteBatches() {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var documents =
        IntStream.range(0, 101)
            .mapToObj(
                documentIndex ->
                    ready(
                        "docs",
                        "document-%03d.md".formatted(documentIndex),
                        "Body " + documentIndex))
            .toList();
    var progress = new ArrayList<Long>();

    index.applyScan(documents, List.of(), 0, progress::add);

    assertThat(progress).containsExactly(100L, 101L);
    var documentIds = index.documentSnapshots().stream().map(snapshot -> snapshot.id()).toList();
    progress.clear();

    index.applyScan(List.of(), documentIds, 0, progress::add);

    assertThat(progress).containsExactly(100L, 101L);
  }

  @Test
  void fullScanResetRemovesDerivedRowsAndRecipeState() throws Exception {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    index.applyScan(List.of(ready("docs", "ready.md", "Ready body")), List.of(), 0, NO_PROGRESS);
    invalidateExtractionForRecipeChanges(PDF_V1);
    index.resetSemanticIndexForRecipe(SEMANTIC_V1);

    index.resetForFullScan();

    assertThat(count("documents")).isZero();
    assertThat(count("contents")).isZero();
    assertThat(count("soma_meta")).isOne();
    try (var connection = connection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT key FROM soma_meta")) {
      assertThat(rows.next()).isTrue();
      assertThat(rows.getString(1)).isEqualTo("database.schema.sha256");
      assertThat(rows.next()).isFalse();
    }
  }

  @Test
  void cleanRemovesUnreferencedContentTreesAndPreservesSharedContent() throws Exception {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var first = ready("docs", "first.md", "Shared semantic body");
    var second = ready("docs", "second.md", "Shared semantic body");
    index.applyScan(List.of(first, second), List.of(), 0, NO_PROGRESS);
    index.writeChunks(first.contentHash(), List.of(chunk(first.body())));
    index.writeEmbeddings(
        index.embeddingWork(List.of("docs")).stream()
            .map(work -> embedding(work.documentId(), work.chunkIndex(), 1.0f))
            .toList());

    var firstId =
        index.documentSnapshots().stream()
            .filter(document -> document.path().equals(first.path()))
            .findFirst()
            .orElseThrow()
            .id();
    index.applyScan(List.of(), List.of(firstId), 1, NO_PROGRESS);

    assertThat(index.cleanOrphans()).isZero();
    assertThat(count("contents")).isOne();
    assertThat(count("chunks")).isOne();
    assertThat(count("embeddings")).isOne();
    assertThat(count("vectors")).isOne();
    assertThat(count("fts_index")).isOne();

    var secondId = index.documentSnapshots().getFirst().id();
    index.applyScan(List.of(), List.of(secondId), 0, NO_PROGRESS);

    assertThat(count("contents")).isOne();
    assertThat(count("chunks")).isOne();
    assertThat(count("embeddings")).isZero();
    assertThat(count("vectors")).isZero();
    assertThat(count("fts_index")).isZero();
    assertThat(index.cleanOrphans()).isOne();
    assertThat(count("contents")).isZero();
    assertThat(count("chunks")).isZero();
    assertThat(index.cleanOrphans()).isZero();
  }

  @Test
  void chunkWritesAreAtomicAndOnlyMissingContentIsPlanned() throws Exception {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var body = "x".repeat(250);
    var document = ready("docs", "large.md", body);
    index.applyScan(List.of(document), List.of(), 0, NO_PROGRESS);
    var chunks =
        IntStream.range(0, body.length())
            .mapToObj(position -> new ChunkWrite(position, position, position + 1, "x", 1))
            .toList();

    var invalid = new ArrayList<>(chunks);
    invalid.set(249, new ChunkWrite(248, 249, 250, "x", 1));

    assertThatThrownBy(() -> index.writeChunks(document.contentHash(), invalid))
        .isInstanceOf(AppException.class);
    assertThat(count("chunks")).isZero();
    assertThat(index.chunkingWork(List.of("docs"))).hasSize(1);

    index.writeChunks(document.contentHash(), chunks);
    assertThat(count("chunks")).isEqualTo(250);
    assertThat(index.chunkingWork(List.of("docs"))).isEmpty();
  }

  @Test
  void extractionRecipesInvalidateDerivedRowsEvenWhenTheBodyRepeats() throws Exception {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var pending = pending("media", "manual.pdf", FileType.PDF);
    index.applyScan(List.of(pending), List.of(), 0, NO_PROGRESS);
    invalidateExtractionForRecipeChanges(PDF_V1);
    var work = index.extractionWork().getFirst();

    var body = "Extracted alpha manual";
    var hash = Hashing.sha256HexUtf8(body);
    publishExtractionWithEmbedding(work.documentId(), body, hash);

    invalidateExtractionForRecipeChanges(PDF_V2);
    assertThat(index.extractionWork())
        .extracting(candidate -> candidate.documentId())
        .containsExactly(work.documentId());
    assertThat(vectorCount(List.of("media"))).isZero();
    assertThat(
            index.lexicalSearch(
                database, List.of("media"), LexicalQuery.parse("alpha"), Integer.MAX_VALUE))
        .isEmpty();

    index.publishExtraction(work.documentId(), hash, body);

    assertThat(index.embeddingWork(List.of("media"))).hasSize(1);
    var repeatedWork = index.embeddingWork(List.of("media")).getFirst();
    index.writeEmbeddings(List.of(embedding(repeatedWork.documentId(), 0, 1.0f)));
    assertThat(vectorCount(List.of("media"))).isOne();

    var changedBody = "Completely changed beta manual";
    var changedHash = Hashing.sha256HexUtf8(changedBody);
    invalidateExtractionForRecipeChanges(PDF_V3);
    index.publishExtraction(work.documentId(), changedHash, changedBody);

    assertThat(vectorCount(List.of("media"))).isZero();
    assertThat(
            index.lexicalSearch(
                database, List.of("media"), LexicalQuery.parse("alpha"), Integer.MAX_VALUE))
        .isEmpty();
    assertThat(
            index.lexicalSearch(
                database, List.of("media"), LexicalQuery.parse("beta"), Integer.MAX_VALUE))
        .extracting(hit -> hit.path())
        .containsExactly("manual.pdf");
  }

  @Test
  void incrementalDocumentSyncPreservesUnchangedDerivedStateAndInvalidatesChangedSources() {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var pending = indexRichDocumentWithEmbedding("manual.pdf", "Extracted manual");

    var unchanged = index.applyScan(List.of(), List.of(), 1, NO_PROGRESS);

    assertThat(unchanged.unchanged()).isOne();
    assertThat(unchanged.upserted()).isZero();
    assertThat(vectorCount(List.of("media"))).isOne();
    assertThat(index.extractionWork()).isEmpty();

    var touched = pendingWithSourceMetadata(pending, 2, 101);
    var metadataUpdated = index.applyScan(List.of(touched), List.of(), 0, NO_PROGRESS);

    assertThat(metadataUpdated.metadataUpdated()).isOne();
    assertThat(metadataUpdated.upserted()).isZero();
    assertThat(vectorCount(List.of("media"))).isOne();
    assertThat(index.extractionWork()).isEmpty();

    var changed =
        new DocumentWrite(
            touched.project(),
            touched.path(),
            Hashing.sha256HexUtf8("different raw source"),
            null,
            touched.title(),
            touched.modifiedTimeNs(),
            touched.sizeBytes(),
            touched.fileType(),
            touched.status(),
            null);
    var updated = index.applyScan(List.of(changed), List.of(), 0, NO_PROGRESS);

    assertThat(updated.upserted()).isOne();
    assertThat(vectorCount(List.of("media"))).isZero();
    assertThat(index.extractionWork())
        .extracting(candidate -> candidate.path())
        .containsExactly("manual.pdf");
    var removedIds = index.documentSnapshots().stream().map(snapshot -> snapshot.id()).toList();
    assertThat(index.applyScan(List.of(), removedIds, 0, NO_PROGRESS).removed()).isOne();
  }

  @Test
  void incrementalDocumentSyncInvalidatesRichContentWhenItsDerivedTitleChanges() {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var pending = indexRichDocumentWithEmbedding("manual.pdf", "Extracted manual");
    var retitled =
        new DocumentWrite(
            pending.project(),
            pending.path(),
            pending.sourceHash(),
            null,
            "New generated title",
            pending.modifiedTimeNs(),
            pending.sizeBytes(),
            pending.fileType(),
            pending.status(),
            null);

    var report = index.applyScan(List.of(retitled), List.of(), 0, NO_PROGRESS);

    assertThat(report.upserted()).isOne();
    assertThat(vectorCount(List.of("media"))).isZero();
    assertThat(index.findDocument(database, "media", "manual.pdf", Long.MAX_VALUE))
        .hasValueSatisfying(
            document -> {
              assertThat(document.title()).isEqualTo("New generated title");
              assertThat(document.status()).isEqualTo(ExtractionStatus.PENDING);
            });
    assertThat(index.extractionWork())
        .extracting(candidate -> candidate.path())
        .containsExactly("manual.pdf");
  }

  @Test
  void extractionRecipeChangesRetryFailedRichDocumentsWithSourceHashes() {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var pending = pending("media", "retry.pdf", FileType.PDF);
    index.applyScan(List.of(pending), List.of(), 0, NO_PROGRESS);
    invalidateExtractionForRecipeChanges(PDF_V1);
    var documentId = index.extractionWork().getFirst().documentId();
    index.failExtraction(documentId);

    assertThat(index.extractionWork()).isEmpty();
    assertThat(index.applyScan(List.of(), List.of(), 1, NO_PROGRESS).unchanged()).isOne();
    assertThat(index.extractionWork()).isEmpty();
    invalidateExtractionForRecipeChanges(PDF_V2);

    assertThat(index.extractionWork())
        .extracting(candidate -> candidate.documentId())
        .containsExactly(documentId);
  }

  @Test
  void incrementalDocumentSyncInvalidatesReadyRichContentAfterAFailedScan() {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var pending = pending("media", "unreadable.pdf", FileType.PDF);
    index.applyScan(List.of(pending), List.of(), 0, NO_PROGRESS);
    invalidateExtractionForRecipeChanges(PDF_V1);
    var documentId = index.extractionWork().getFirst().documentId();
    var body = "Previously extracted body";
    var hash = Hashing.sha256HexUtf8(body);
    index.publishExtraction(documentId, hash, body);
    index.writeChunks(hash, List.of(chunk(body)));
    index.writeEmbeddings(List.of(embedding(documentId, 0, 1.0f)));
    var failed =
        new DocumentWrite(
            pending.project(),
            pending.path(),
            null,
            null,
            pending.title(),
            pending.modifiedTimeNs(),
            pending.sizeBytes(),
            pending.fileType(),
            ExtractionStatus.FAILED,
            null);

    var report = index.applyScan(List.of(failed), List.of(), 0, NO_PROGRESS);

    assertThat(report.upserted()).isOne();
    assertThat(report.unchanged()).isZero();
    assertThat(vectorCount(List.of("media"))).isZero();
    assertThat(
            index.lexicalSearch(database, List.of("media"), LexicalQuery.parse("previously"), 10))
        .isEmpty();
  }

  @Test
  void semanticWritesRepairMissingVectorsValidateDimensionsAndRespectProjectScope()
      throws Exception {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    index.resetSemanticIndexForRecipe(SEMANTIC_V1);
    index.resetSemanticIndexForRecipe(SEMANTIC_V1);
    var firstSemanticWork = writeSemanticFixture().getFirst();

    assertInitialSemanticState();
    repairMissingVector(firstSemanticWork);
    assertInvalidEmbeddingDimensionRejected(firstSemanticWork);
    assertSemanticRecipeChangeClearsIndex();
  }

  @Test
  void vectorLimitCountsDocumentsInsteadOfChunks() {
    index.openOrRebuildForScan(database, WRITE_LOCK);
    var longDocument = ready("docs", "long.md", "abc");
    var shortDocument = ready("docs", "short.md", "z");
    index.applyScan(List.of(longDocument, shortDocument), List.of(), 0, NO_PROGRESS);
    index.writeChunks(
        longDocument.contentHash(),
        List.of(
            new ChunkWrite(0, 0, 1, "a", 1),
            new ChunkWrite(1, 1, 2, "b", 1),
            new ChunkWrite(2, 2, 3, "c", 1)));
    index.writeChunks(shortDocument.contentHash(), List.of(chunk(shortDocument.body())));
    index.writeEmbeddings(
        index.embeddingWork(List.of("docs")).stream()
            .map(
                work ->
                    embedding(
                        work.documentId(),
                        work.chunkIndex(),
                        work.title().equals("long") ? 1.0f : 0.0f))
            .toList());

    assertThat(index.vectorSearch(database, List.of("docs"), vector(1.0f), 2))
        .extracting(hit -> hit.virtualPath())
        .containsExactly("soma://docs/long.md", "soma://docs/short.md");
  }

  private void assertInitialSemanticState() throws Exception {
    assertThat(activeRecipe("active.semantic_recipe_id")).isEqualTo(SEMANTIC_V1);
    assertThat(vectorCount(List.of("one", "two"))).isEqualTo(2);
    var hits = index.vectorSearch(database, List.of("one"), vector(1.0f), 10);
    assertThat(hits).hasSize(1);
    assertThat(hits.getFirst().virtualPath()).isEqualTo("soma://one/one.md");
    assertThat(hits.getFirst().evidenceBody()).isEqualTo("One semantic body");
    assertThat(hits.getFirst().documentBody()).isEqualTo("One semantic body");
  }

  private void repairMissingVector(EmbeddingWork semanticWork) throws Exception {
    deleteVector(semanticWork.documentId());
    assertThat(index.embeddingWork(List.of("one", "two")))
        .extracting(item -> item.project())
        .containsExactly("one");
    index.writeEmbeddings(List.of(embedding(semanticWork.documentId(), 0, 1.0f)));
    assertThat(vectorCount(List.of("one"))).isOne();
  }

  private void assertInvalidEmbeddingDimensionRejected(EmbeddingWork semanticWork) {
    var invalidVector = new float[767];
    assertThatThrownBy(
            () ->
                index.writeEmbeddings(
                    List.of(new EmbeddingWrite(semanticWork.documentId(), 0, 1, invalidVector))))
        .isInstanceOf(AppException.class);
    assertThat(vectorCount(List.of("one", "two"))).isEqualTo(2);
  }

  private void assertSemanticRecipeChangeClearsIndex() throws Exception {
    index.resetSemanticIndexForRecipe(SEMANTIC_V1);
    assertThat(count("chunks")).isEqualTo(2);
    assertThat(vectorCount(List.of("one", "two"))).isEqualTo(2);
    index.resetSemanticIndexForRecipe(SEMANTIC_V2);
    assertThat(count("chunks")).isZero();
    assertThat(vectorCount(List.of("one", "two"))).isZero();
    assertThat(activeRecipe("active.semantic_recipe_id")).isEqualTo(SEMANTIC_V2);
  }

  private void publishExtractionWithEmbedding(long documentId, String body, String contentHash) {
    index.publishExtraction(documentId, contentHash, body);
    index.writeChunks(contentHash, List.of(chunk(body)));
    var work = index.embeddingWork(List.of("media")).getFirst();
    index.writeEmbeddings(List.of(embedding(work.documentId(), 0, 1.0f)));
    assertThat(vectorCount(List.of("media"))).isOne();
  }

  private DocumentWrite indexRichDocumentWithEmbedding(String path, String body) {
    var pending = pending("media", path, FileType.PDF);
    index.applyScan(List.of(pending), List.of(), 0, NO_PROGRESS);
    invalidateExtractionForRecipeChanges(PDF_V1);
    var work = index.extractionWork().getFirst();
    var contentHash = Hashing.sha256HexUtf8(body);
    index.publishExtraction(work.documentId(), contentHash, body);
    index.writeChunks(contentHash, List.of(chunk(body)));
    index.writeEmbeddings(
        List.of(embedding(index.embeddingWork(List.of("media")).getFirst().documentId(), 0, 1.0f)));
    return pending;
  }

  private DocumentWrite pendingWithSourceMetadata(
      DocumentWrite document, long modifiedTimeNs, long sizeBytes) {
    return new DocumentWrite(
        document.project(),
        document.path(),
        document.sourceHash(),
        null,
        document.title(),
        modifiedTimeNs,
        sizeBytes,
        document.fileType(),
        ExtractionStatus.PENDING,
        null);
  }

  private void invalidateExtractionForRecipeChanges(String pdfRecipeId) {
    index.invalidateExtractionForRecipeChanges(
        Map.of(
            FileType.PDF, pdfRecipeId,
            FileType.IMAGE, IMAGE_V1,
            FileType.AUDIO, MEDIA_V1,
            FileType.VIDEO, MEDIA_V1));
  }

  private String activeRecipe(String key) throws Exception {
    try (var connection = connection();
        var statement = connection.prepareStatement("SELECT value FROM soma_meta WHERE key = ?")) {
      statement.setString(1, key);
      try (var rows = statement.executeQuery()) {
        assertThat(rows.next()).isTrue();
        return rows.getString(1);
      }
    }
  }

  private static String recipe(String name) {
    return RecipeId.of("test", name);
  }

  private List<EmbeddingWork> writeSemanticFixture() {
    var one = ready("one", "one.md", "One semantic body");
    var two = ready("two", "two.md", "Two semantic body");
    index.applyScan(List.of(one, two), List.of(), 0, NO_PROGRESS);
    index.writeChunks(one.contentHash(), List.of(chunk(one.body())));
    index.writeChunks(two.contentHash(), List.of(chunk(two.body())));

    var work = index.embeddingWork(List.of("one", "two"));
    assertThat(work).hasSize(2);
    index.writeEmbeddings(
        work.stream()
            .map(
                item ->
                    embedding(
                        item.documentId(),
                        item.chunkIndex(),
                        item.project().equals("one") ? 1.0f : -1.0f))
            .toList());
    return work;
  }

  private void deleteVector(long documentId) throws Exception {
    try (var connection = connection();
        var statement =
            connection.prepareStatement(
                "DELETE FROM vectors WHERE rowid = "
                    + "(SELECT id FROM embeddings WHERE document_id = ?)")) {
      statement.setLong(1, documentId);
      assertThat(statement.executeUpdate()).isOne();
    }
  }

  private long vectorCount(List<String> projectNames) {
    return index.projectStats(database, projectNames).values().stream()
        .mapToLong(stats -> stats.vectors())
        .sum();
  }

  private DocumentWrite ready(String project, String path, String body) {
    var hash = Hashing.sha256HexUtf8(body);
    return new DocumentWrite(
        project,
        path,
        hash,
        hash,
        path.substring(0, path.lastIndexOf('.')),
        1,
        body.length(),
        FileType.TEXT,
        ExtractionStatus.READY,
        body);
  }

  private DocumentWrite pending(String project, String path, FileType fileType) {
    return new DocumentWrite(
        project,
        path,
        Hashing.sha256HexUtf8("source:" + project + "/" + path),
        null,
        path.substring(0, path.lastIndexOf('.')),
        1,
        100,
        fileType,
        ExtractionStatus.PENDING,
        null);
  }

  private ChunkWrite chunk(String body) {
    return new ChunkWrite(0, 0, body.length(), body, Math.max(1, body.split(" ").length));
  }

  private EmbeddingWrite embedding(long documentId, int chunkIndex, float firstValue) {
    return new EmbeddingWrite(documentId, chunkIndex, 2, vector(firstValue));
  }

  private float[] vector(float firstValue) {
    var vector = new float[768];
    vector[0] = firstValue;
    return vector;
  }

  private int count(String table) throws Exception {
    try (var connection = connection();
        var statement = connection.createStatement();
        var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
      return rows.next() ? rows.getInt(1) : 0;
    }
  }

  private Connection connection() throws Exception {
    var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
    var sqlite = connection.unwrap(SQLiteConnection.class);
    sqlite.getDatabase().enable_load_extension(true);
    try (var statement = connection.prepareStatement("SELECT load_extension(?)")) {
      statement.setString(1, sqliteVecLoadName().toString());
      statement.execute();
    } finally {
      sqlite.getDatabase().enable_load_extension(false);
    }
    try (var statement = connection.createStatement()) {
      statement.execute("PRAGMA busy_timeout = 5000");
    }
    return connection;
  }

  private Path sqliteVec() {
    var binary =
        switch (HostPlatform.current().id()) {
          case "darwin-arm64" -> "vec0.dylib";
          case "linux-x86_64" -> "vec0.so";
          case "windows-x86_64" -> "vec0.dll";
          default -> throw new IllegalStateException("unsupported test platform");
        };
    return dataDirectory
        .resolve("sqlite-vec/0.1.9")
        .resolve(HostPlatform.current().id())
        .resolve(binary);
  }

  private Path sqliteVecLoadName() {
    var library = sqliteVec();
    var fileName = library.getFileName().toString();
    return library.resolveSibling(fileName.substring(0, fileName.lastIndexOf('.')));
  }
}
