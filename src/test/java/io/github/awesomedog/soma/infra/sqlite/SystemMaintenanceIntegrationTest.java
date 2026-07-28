package io.github.awesomedog.soma.infra.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.ContentExtractor;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.app.system.ContentExtraction;
import io.github.awesomedog.soma.app.system.EmbeddingGeneration;
import io.github.awesomedog.soma.app.system.IndexCleanup;
import io.github.awesomedog.soma.app.system.MaintenanceCycle;
import io.github.awesomedog.soma.app.system.NioProjectScanner;
import io.github.awesomedog.soma.app.system.OperationReport;
import io.github.awesomedog.soma.app.system.ProjectScanning;
import io.github.awesomedog.soma.app.system.SyncReport;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.document.ExtractionStatus;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.HostPlatform;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SystemMaintenanceIntegrationTest {

  private static final WriteLock.Token WRITE_LOCK = () -> {};
  private static final String PDF_SOURCE = "%PDF-1.7\nfixture";
  private static final String PDF_SOURCE_HASH = Hashing.sha256HexUtf8(PDF_SOURCE);

  @TempDir Path temporaryDirectory;

  private Path database;
  private Path configFile;
  private Path docsRoot;
  private Path archiveRoot;
  private SqliteWorkspaceIndex index;
  private MutableExtractor extractor;
  private MutableSearchModels searchModels;
  private ProjectScanning projectScanning;
  private ContentExtraction contentExtraction;
  private EmbeddingGeneration embeddingGeneration;
  private IndexCleanup indexCleanup;
  private MaintenanceCycle maintenanceCycle;

  @BeforeEach
  void setUp() throws Exception {
    database = temporaryDirectory.resolve("state/main.sqlite");
    configFile = temporaryDirectory.resolve("config/main.yml");
    docsRoot = Files.createDirectories(temporaryDirectory.resolve("docs"));
    archiveRoot = Files.createDirectories(temporaryDirectory.resolve("archive"));
    Files.writeString(docsRoot.resolve("alpha.md"), "# Alpha Guide\nAlpha searchable text.");
    Files.write(docsRoot.resolve("scan.pdf"), PDF_SOURCE.getBytes(StandardCharsets.US_ASCII));
    Files.writeString(archiveRoot.resolve("gamma.md"), "# Gamma Notes\nGamma archive text.");

    var config =
        new SomaConfig(
            1, List.of(project("docs", docsRoot), project("archive", archiveRoot)), List.of());
    index =
        new SqliteWorkspaceIndex(temporaryDirectory.resolve("data"), HostPlatform.current().id());
    extractor = new MutableExtractor();
    searchModels = new MutableSearchModels();
    var configStore = new StaticConfig(config);
    projectScanning = new ProjectScanning(configStore, index, new NioProjectScanner());
    contentExtraction = new ContentExtraction(configStore, index, extractor);
    embeddingGeneration = new EmbeddingGeneration(configStore, index, searchModels);
    indexCleanup = new IndexCleanup(index);
    maintenanceCycle =
        new MaintenanceCycle(
            configStore, projectScanning, contentExtraction, embeddingGeneration, indexCleanup);
  }

  @AfterEach
  void closeIndex() {
    index.close();
  }

  @Test
  void runsMaintenanceAgainstTheCanonicalSchema() throws Exception {
    assertScanExtractionAndInitialEmbedding();
    assertArtifactChangesInvalidateAndRebuildEmbeddings();
    assertNoOpSyncSkipsExtractionAndEmbedding();
    assertCleanLeavesSearchStateIntact();
  }

  @Test
  void skipsExtractionResultsFromAChangedRecipeAndCompletesSync() {
    extractor.switchGenerationAfterExtraction = "other-generation";

    var sync =
        maintenanceCycle.sync(
            configFile, database, artifactPullReport(), WRITE_LOCK, ignored -> {});

    assertSkippedExtractionCompletesSync(sync);
    assertThat(index.extractionWork()).extracting(work -> work.path()).containsExactly("scan.pdf");
  }

  @Test
  void skipsExtractionResultsFromAChangedSourceAndCompletesSync() {
    extractor.sourceHash = Hashing.sha256HexUtf8("changed source");

    var sync =
        maintenanceCycle.sync(
            configFile, database, artifactPullReport(), WRITE_LOCK, ignored -> {});

    assertSkippedExtractionCompletesSync(sync);
    assertThat(index.extractionWork())
        .singleElement()
        .satisfies(
            work -> {
              assertThat(work.path()).isEqualTo("scan.pdf");
              assertThat(work.sourceHash()).isEqualTo(PDF_SOURCE_HASH);
            });
  }

  @Test
  void incrementalScanTrustsMetadataButFullScanReadsChangedContent() throws Exception {
    projectScanning.scanAll(configFile, database, WRITE_LOCK, ignored -> {});
    var source = docsRoot.resolve("alpha.md");
    var originalModifiedTime = Files.getLastModifiedTime(source);
    var replacement = "# Bravo Guide\nBravo searchable text.";
    assertThat(replacement.getBytes(StandardCharsets.UTF_8))
        .hasSameSizeAs(Files.readAllBytes(source));
    Files.writeString(source, replacement);
    Files.setLastModifiedTime(source, originalModifiedTime);

    var sync =
        maintenanceCycle.sync(
            configFile, database, artifactPullReport(), WRITE_LOCK, ignored -> {});

    assertThat(sync.phases().get(1).counts()).containsEntry("unchanged", 3);
    assertThat(
            index.findDocument(database, "docs", "alpha.md", Long.MAX_VALUE).orElseThrow().body())
        .contains("Alpha searchable text");

    projectScanning.scanAll(configFile, database, WRITE_LOCK, ignored -> {});

    assertThat(
            index.findDocument(database, "docs", "alpha.md", Long.MAX_VALUE).orElseThrow().body())
        .contains("Bravo searchable text");
  }

  @Test
  void incrementalScanDeletesMissingFiles() throws Exception {
    projectScanning.scanAll(configFile, database, WRITE_LOCK, ignored -> {});
    Files.delete(archiveRoot.resolve("gamma.md"));

    var sync =
        maintenanceCycle.sync(
            configFile, database, artifactPullReport(), WRITE_LOCK, ignored -> {});

    assertThat(sync.phases().get(1).counts()).containsEntry("removed", 1);
    assertThat(index.findDocument(database, "archive", "gamma.md", Long.MAX_VALUE)).isEmpty();
  }

  private void assertSkippedExtractionCompletesSync(SyncReport sync) {
    assertThat(sync.phases())
        .extracting(OperationReport::action)
        .containsExactly("pull", "scan", "extract", "embed", "clean");
    assertThat(sync.phases().get(2).counts())
        .containsEntry("extracted", 0)
        .containsEntry("failed", 0)
        .containsEntry("skipped", 1);
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(2);
  }

  @Test
  void syncRecordsExtractionExceptionsAndContinuesAllMaintenancePhases() throws Exception {
    writePdf("app-failure.pdf");
    writePdf("file-failure.pdf");
    writePdf("runtime-failure.pdf");
    writePdf("success.pdf");
    extractor.failures.put(
        "app-failure.pdf",
        new AppException(
            AppError.Code.OPERATION_FAILED, "Managed runtime rejected the image.", "Retry."));
    extractor.failures.put(
        "file-failure.pdf",
        new AppException(AppError.Code.OPERATION_FAILED, "Unsupported document content.", null));
    extractor.failures.put("runtime-failure.pdf", new IllegalStateException("Unexpected failure."));
    var sync =
        maintenanceCycle.sync(
            configFile, database, artifactPullReport(), WRITE_LOCK, ignored -> {});

    assertExtractionFailureResults(sync);
  }

  @Test
  void embeddingBatchFailuresReturnAnErrorAndRetryMissingWork() throws Exception {
    for (var position = 0; position < 32; position++) {
      Files.writeString(
          docsRoot.resolve("batch-%02d.md".formatted(position)),
          "# Batch " + position + "\nBATCH_MARKER_" + position);
    }
    Files.writeString(
        docsRoot.resolve("zz-embedding-failure.md"),
        "# Embedding Failure\nEMBEDDING_FAILURE_MARKER");
    searchModels.embeddingFailures.put(
        "EMBEDDING_FAILURE_MARKER",
        new AppException(AppError.Code.OPERATION_FAILED, "Embedding request failed.", "Retry."));
    projectScanning.scanAll(configFile, database, WRITE_LOCK, ignored -> {});
    contentExtraction.extractPending(configFile, database, WRITE_LOCK, ignored -> {});

    assertThatThrownBy(
            () ->
                embeddingGeneration.generate(
                    configFile, database, List.of(), WRITE_LOCK, ignored -> {}))
        .isInstanceOf(AppException.class);
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(32);

    searchModels.embeddingFailures.clear();
    var retry =
        embeddingGeneration.generate(configFile, database, List.of(), WRITE_LOCK, ignored -> {});
    assertThat(retry.counts()).containsEntry("documents", 4).containsEntry("chunks", 4);
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(36);
  }

  private void assertScanExtractionAndInitialEmbedding() {
    var scan = projectScanning.scanAll(configFile, database, WRITE_LOCK, ignored -> {});
    assertThat(scan.counts()).containsEntry("ready", 2).containsEntry("pending", 1);
    assertThat(index.extractionWork()).extracting(work -> work.path()).containsExactly("scan.pdf");

    var extraction =
        contentExtraction.extractPending(configFile, database, WRITE_LOCK, ignored -> {});
    assertThat(extraction.counts()).containsEntry("extracted", 1).containsEntry("failed", 0);
    var embedding =
        embeddingGeneration.generate(configFile, database, List.of(), WRITE_LOCK, ignored -> {});
    assertThat(embedding.counts()).containsEntry("documents", 3).containsEntry("chunks", 3);
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(3);
  }

  private void assertArtifactChangesInvalidateAndRebuildEmbeddings() {
    searchModels.embeddingModelRecipeId = RecipeId.of("test.embedding.model", "v2");
    var rebuilt =
        embeddingGeneration.generate(
            configFile, database, List.of("docs"), WRITE_LOCK, ignored -> {});
    assertThat(rebuilt.counts()).containsEntry("documents", 2);
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(2);

    extractor.generation = "v2";
    contentExtraction.extractPending(configFile, database, WRITE_LOCK, ignored -> {});
    assertThat(vectorCount(List.of("docs", "archive"))).isOne();

    extractor.generation = "v3";
    extractor.body = "Changed PDF body";
    contentExtraction.extractPending(configFile, database, WRITE_LOCK, ignored -> {});
    assertThat(vectorCount(List.of("docs", "archive"))).isOne();
    embeddingGeneration.generate(configFile, database, List.of("docs"), WRITE_LOCK, ignored -> {});
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(2);
    embeddingGeneration.generate(configFile, database, List.of(), WRITE_LOCK, ignored -> {});
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(3);
  }

  private void assertNoOpSyncSkipsExtractionAndEmbedding() {
    var extractionCalls = extractor.calls;
    var embeddingCalls = searchModels.embeddingCalls;
    var sync =
        maintenanceCycle.sync(
            configFile, database, artifactPullReport(), WRITE_LOCK, ignored -> {});
    assertThat(sync.phases())
        .extracting(OperationReport::action)
        .containsExactly("pull", "scan", "extract", "embed", "clean");
    assertThat(sync.phases().getFirst().counts()).containsEntry("artifacts", 0);
    assertThat(sync.phases().get(1).counts())
        .containsEntry("upserted", 0)
        .containsEntry("unchanged", 3);
    assertThat(extractor.calls).isEqualTo(extractionCalls);
    assertThat(searchModels.embeddingCalls).isEqualTo(embeddingCalls);
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(3);
  }

  private void assertCleanLeavesSearchStateIntact() {
    var vectorsBefore = vectorCount(List.of("docs", "archive"));
    var clean = indexCleanup.removeOrphans(database, WRITE_LOCK);
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(vectorsBefore);
    assertThat(clean.counts().values()).allMatch(value -> value == 0);
  }

  private long vectorCount(List<String> projectNames) {
    return index.projectStats(database, projectNames).values().stream()
        .mapToLong(stats -> stats.vectors())
        .sum();
  }

  private static ProjectConfig project(String name, Path root) {
    return new ProjectConfig(new ProjectName(name), root, List.of("**/*"), List.of(), true, false);
  }

  private void writePdf(String fileName) throws Exception {
    Files.write(docsRoot.resolve(fileName), PDF_SOURCE.getBytes(StandardCharsets.US_ASCII));
  }

  private void assertDocumentStatus(String path, ExtractionStatus expected) {
    assertThat(index.findDocument(database, "docs", path, Long.MAX_VALUE))
        .hasValueSatisfying(document -> assertThat(document.status()).isEqualTo(expected));
  }

  private void assertExtractionFailureResults(SyncReport sync) {
    assertThat(sync.phases())
        .extracting(OperationReport::action)
        .containsExactly("pull", "scan", "extract", "embed", "clean");
    assertThat(sync.phases().get(2).counts())
        .containsEntry("processed", 5)
        .containsEntry("extracted", 2)
        .containsEntry("failed", 3);
    assertDocumentStatus("app-failure.pdf", ExtractionStatus.FAILED);
    assertDocumentStatus("file-failure.pdf", ExtractionStatus.FAILED);
    assertDocumentStatus("runtime-failure.pdf", ExtractionStatus.FAILED);
    assertDocumentStatus("scan.pdf", ExtractionStatus.READY);
    assertDocumentStatus("success.pdf", ExtractionStatus.READY);
  }

  private record StaticConfig(SomaConfig config) implements ConfigStore {

    @Override
    public SomaConfig load(Path ignored) {
      return config;
    }

    @Override
    public SomaConfig loadOrBackupResetForUpdate(Path ignored) {
      return config;
    }

    @Override
    public void save(Path ignored, SomaConfig value) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class MutableExtractor implements ContentExtractor {

    private String generation = "v1";
    private String body = "PDF extracted body";
    private String switchGenerationAfterExtraction;
    private String sourceHash = PDF_SOURCE_HASH;
    private final Map<String, RuntimeException> failures = new HashMap<>();
    private int calls;

    @Override
    public String recipeId(FileType fileType) {
      var domain =
          switch (fileType) {
            case PDF -> "test.pdf";
            case IMAGE -> "test.image";
            case AUDIO, VIDEO -> "test.media";
            case TEXT, OTHER -> throw new IllegalArgumentException("unsupported test recipe");
          };
      return RecipeId.of(domain, generation);
    }

    @Override
    public Extraction extract(Path source, FileType fileType) {
      calls++;
      var failure = failures.get(source.getFileName().toString());
      if (failure != null) {
        throw failure;
      }
      if (switchGenerationAfterExtraction != null) {
        generation = switchGenerationAfterExtraction;
        switchGenerationAfterExtraction = null;
      }
      return new Extraction(sourceHash, body);
    }
  }

  private static final class MutableSearchModels implements SearchModels {

    private String embeddingModelRecipeId = RecipeId.of("test.embedding.model", "v1");
    private final Map<String, RuntimeException> embeddingFailures = new HashMap<>();
    private int embeddingCalls;

    @Override
    public EmbeddingMetadata embeddingMetadata() {
      return new EmbeddingMetadata(
          embeddingModelRecipeId, RecipeId.of("test.embedding.tokenizer", "v1"), 768, 2048);
    }

    @Override
    public int countTokens(String input) {
      return Math.max(1, input.length() / 4);
    }

    @Override
    public float[] embed(String input) {
      embeddingCalls++;
      throwMatchingFailure(input, embeddingFailures);
      var vector = new float[768];
      vector[0] = input.toLowerCase(java.util.Locale.ROOT).contains("alpha") ? 1.0f : -1.0f;
      return vector;
    }

    private static void throwMatchingFailure(String input, Map<String, RuntimeException> failures) {
      for (var failure : failures.entrySet()) {
        if (input.contains(failure.getKey())) {
          throw failure.getValue();
        }
      }
    }

    @Override
    public Expansion expand(String query) {
      return new Expansion(List.of("alph"), List.of("Alpha semantics"), List.of("Alpha guide"));
    }

    @Override
    public List<RerankScore> rerank(String query, List<String> candidateTexts, int limit) {
      var scores = new ArrayList<RerankScore>();
      for (var index = 0; index < candidateTexts.size(); index++) {
        var containsAlpha =
            candidateTexts.get(index).toLowerCase(java.util.Locale.ROOT).contains("alpha");
        scores.add(new RerankScore(index, containsAlpha ? 1.0 : 0.1));
      }
      return scores.stream()
          .sorted(java.util.Comparator.comparingDouble(RerankScore::score).reversed())
          .limit(limit)
          .toList();
    }
  }

  private static OperationReport artifactPullReport() {
    return new OperationReport(
        "pull", "Managed artifacts refreshed\n  (none)", Map.of("artifacts", 0));
  }
}
