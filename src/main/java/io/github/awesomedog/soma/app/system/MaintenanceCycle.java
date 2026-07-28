package io.github.awesomedog.soma.app.system;

import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.WriteLock;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

@Singleton
public final class MaintenanceCycle {

  private final ProjectScanning projectScanning;
  private final ContentExtraction contentExtraction;
  private final EmbeddingGeneration embeddingGeneration;
  private final IndexCleanup indexCleanup;
  private final ConfigStore configStore;

  public MaintenanceCycle(
      ConfigStore configStore,
      ProjectScanning projectScanning,
      ContentExtraction contentExtraction,
      EmbeddingGeneration embeddingGeneration,
      IndexCleanup indexCleanup) {
    this.configStore = Objects.requireNonNull(configStore, "configStore");
    this.projectScanning = Objects.requireNonNull(projectScanning, "projectScanning");
    this.contentExtraction = Objects.requireNonNull(contentExtraction, "contentExtraction");
    this.embeddingGeneration = Objects.requireNonNull(embeddingGeneration, "embeddingGeneration");
    this.indexCleanup = Objects.requireNonNull(indexCleanup, "indexCleanup");
  }

  public SyncReport sync(
      Path configFile,
      Path databaseFile,
      OperationReport artifactPullReport,
      WriteLock.Token token,
      Consumer<ProgressEvent> progress) {
    var progressEvents = progress == null ? (Consumer<ProgressEvent>) ignored -> {} : progress;
    var config = configStore.load(configFile);
    return new SyncReport(
        List.of(
            artifactPullReport,
            projectScanning.scanIncrementally(config, databaseFile, token, progressEvents),
            contentExtraction.extractPending(config, databaseFile, token, progressEvents),
            embeddingGeneration.generate(config, databaseFile, List.of(), token, progressEvents),
            indexCleanup.removeOrphans(databaseFile, token)));
  }
}
