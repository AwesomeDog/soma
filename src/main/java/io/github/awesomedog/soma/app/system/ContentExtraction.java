package io.github.awesomedog.soma.app.system;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.ContentExtractor;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.domain.document.VirtualPath;
import io.github.awesomedog.soma.domain.search.LexicalProjector;
import io.github.awesomedog.soma.support.Hashing;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class ContentExtraction {

  private static final Logger LOG = LoggerFactory.getLogger(ContentExtraction.class);

  private final ConfigStore configStore;
  private final WorkspaceIndex workspaceIndex;
  private final ContentExtractor extractor;

  public ContentExtraction(
      ConfigStore configStore, WorkspaceIndex workspaceIndex, ContentExtractor extractor) {
    this.configStore = Objects.requireNonNull(configStore, "configStore");
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
    this.extractor = Objects.requireNonNull(extractor, "extractor");
  }

  public OperationReport extractPending(
      Path configFile, Path databaseFile, WriteLock.Token token, Consumer<ProgressEvent> progress) {
    return extractPending(configStore.load(configFile), databaseFile, token, progress);
  }

  OperationReport extractPending(
      SomaConfig config,
      Path databaseFile,
      WriteLock.Token token,
      Consumer<ProgressEvent> progress) {
    var startNanos = System.nanoTime();
    var progressEvents = progress == null ? (Consumer<ProgressEvent>) ignored -> {} : progress;
    workspaceIndex.openExistingForWrite(databaseFile, token);
    var desiredRecipeIds = desiredRecipeIds();
    workspaceIndex.invalidateExtractionForRecipeChanges(desiredRecipeIds);
    workspaceIndex.rebuildLexicalIndexForRecipe(LexicalProjector.recipeId());
    var extractionWorkItems = workspaceIndex.extractionWork();
    if (extractionWorkItems.isEmpty()) {
      return new OperationReport(
              "extract", "No pending rich/media extractions.", Map.of("processed", 0))
          .withDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
    }
    var projectConfigByName = mapProjectConfigsByName(config.projects());
    var extractionSummary =
        processExtractionWork(
            extractionWorkItems, projectConfigByName, desiredRecipeIds, progressEvents);
    return completedExtractionReport(extractionWorkItems.size(), extractionSummary)
        .withDuration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos));
  }

  private ExtractionSummary processExtractionWork(
      List<WorkspaceIndex.ExtractionWork> extractionWorkItems,
      Map<String, ProjectConfig> projectConfigByName,
      Map<FileType, String> desiredRecipeIds,
      Consumer<ProgressEvent> progressEvents) {
    var extractedDocumentCount = 0;
    var failedDocumentCount = 0;
    var skippedDocumentCount = 0;
    for (var position = 0; position < extractionWorkItems.size(); position++) {
      var extractionWorkItem = extractionWorkItems.get(position);
      var virtualDocumentPath =
          new VirtualPath(extractionWorkItem.project(), extractionWorkItem.path()).toString();
      progressEvents.accept(
          ProgressEvent.message(
              formatExtractionProgress(
                  position + 1, extractionWorkItems.size(), virtualDocumentPath, "Extracting")));
      var extractionOutcome =
          processExtractionWorkItem(
              extractionWorkItem,
              projectConfigByName.get(extractionWorkItem.project()),
              desiredRecipeIds.get(extractionWorkItem.fileType()),
              virtualDocumentPath);
      switch (extractionOutcome.disposition()) {
        case EXTRACTED -> extractedDocumentCount++;
        case FAILED -> failedDocumentCount++;
        case SKIPPED -> skippedDocumentCount++;
      }
      progressEvents.accept(
          ProgressEvent.message(
              formatExtractionProgress(
                  position + 1,
                  extractionWorkItems.size(),
                  virtualDocumentPath,
                  extractionOutcome.progressStatus())));
    }
    return new ExtractionSummary(extractedDocumentCount, failedDocumentCount, skippedDocumentCount);
  }

  private static OperationReport completedExtractionReport(
      int processedDocumentCount, ExtractionSummary extractionSummary) {
    return new OperationReport(
        "extract",
        "Processed "
            + processedDocumentCount
            + " rich/media document(s): "
            + extractionSummary.extractedDocumentCount()
            + " extracted, "
            + extractionSummary.failedDocumentCount()
            + " failed, "
            + extractionSummary.skippedDocumentCount()
            + " skipped.",
        Map.of(
            "processed",
            processedDocumentCount,
            "extracted",
            extractionSummary.extractedDocumentCount(),
            "failed",
            extractionSummary.failedDocumentCount(),
            "skipped",
            extractionSummary.skippedDocumentCount()));
  }

  private ExtractionOutcome processExtractionWorkItem(
      WorkspaceIndex.ExtractionWork extractionWorkItem,
      ProjectConfig projectConfig,
      String expectedRecipeId,
      String virtualDocumentPath) {
    requireNotInterrupted();
    final ContentExtractor.Extraction extractionResult;
    final String extractedBody;
    try {
      if (projectConfig == null) {
        throw new AppException(OPERATION_FAILED, "Project is not configured.", null);
      }
      var sourceFile = projectConfig.root().resolve(extractionWorkItem.path()).normalize();
      if (!sourceFile.startsWith(projectConfig.root()) || !Files.isRegularFile(sourceFile)) {
        throw new AppException(OPERATION_FAILED, "Source file is missing.", null);
      }
      extractionResult = extractor.extract(sourceFile, extractionWorkItem.fileType());
      extractedBody = extractionResult.body() == null ? "" : extractionResult.body().strip();
      if (extractedBody.isBlank()) {
        throw new AppException(OPERATION_FAILED, "Extraction produced no searchable text.", null);
      }
    } catch (Exception failure) {
      if (Thread.currentThread().isInterrupted()) {
        throw extractionInterrupted(failure);
      }
      LOG.warn(
          "Content extraction failed for {} ({}): {}",
          virtualDocumentPath,
          extractionWorkItem.fileType().value(),
          errorMessageOrFallback(failure),
          failure);
      return recordExtractionFailure(
          extractionWorkItem, "Failed: " + errorMessageOrFallback(failure));
    }

    requireNotInterrupted();
    if (!Objects.equals(extractionWorkItem.sourceHash(), extractionResult.sourceHash())) {
      LOG.warn("Content extraction skipped for {}: source file changed.", virtualDocumentPath);
      return new ExtractionOutcome(ExtractionDisposition.SKIPPED, "Skipped: source changed");
    }
    if (!Objects.equals(expectedRecipeId, extractor.recipeId(extractionWorkItem.fileType()))) {
      LOG.warn(
          "Content extraction skipped for {}: managed artifacts changed.", virtualDocumentPath);
      return new ExtractionOutcome(ExtractionDisposition.SKIPPED, "Skipped: artifacts changed");
    }
    publishExtractedContent(extractionWorkItem, extractedBody);
    return new ExtractionOutcome(ExtractionDisposition.EXTRACTED, "Extracted");
  }

  private EnumMap<FileType, String> desiredRecipeIds() {
    var desiredRecipeIds = new EnumMap<FileType, String>(FileType.class);
    for (var type : List.of(FileType.PDF, FileType.IMAGE, FileType.AUDIO, FileType.VIDEO)) {
      desiredRecipeIds.put(type, extractor.recipeId(type));
    }
    return desiredRecipeIds;
  }

  private static Map<String, ProjectConfig> mapProjectConfigsByName(
      List<ProjectConfig> projectConfigs) {
    var configsByProjectName = new HashMap<String, ProjectConfig>();
    for (var projectConfig : projectConfigs) {
      configsByProjectName.put(projectConfig.name().value(), projectConfig);
    }
    return configsByProjectName;
  }

  private void publishExtractedContent(
      WorkspaceIndex.ExtractionWork extractionWorkItem, String extractedBody) {
    workspaceIndex.publishExtraction(
        extractionWorkItem.documentId(), Hashing.sha256HexUtf8(extractedBody), extractedBody);
  }

  private ExtractionOutcome recordExtractionFailure(
      WorkspaceIndex.ExtractionWork extractionWorkItem, String progressStatus) {
    workspaceIndex.failExtraction(extractionWorkItem.documentId());
    return new ExtractionOutcome(ExtractionDisposition.FAILED, progressStatus);
  }

  private static String errorMessageOrFallback(Throwable extractionFailure) {
    return extractionFailure.getMessage() == null || extractionFailure.getMessage().isBlank()
        ? "Processing failed"
        : extractionFailure.getMessage();
  }

  private static void requireNotInterrupted() {
    if (Thread.currentThread().isInterrupted()) {
      throw extractionInterrupted(null);
    }
  }

  private static AppException extractionInterrupted(Throwable cause) {
    return new AppException(
        OPERATION_FAILED,
        "Content extraction was interrupted.",
        "Retry `soma system extract`.",
        cause);
  }

  private static String formatExtractionProgress(
      int currentDocument, int totalDocuments, String virtualDocumentPath, String progressStatus) {
    return "["
        + currentDocument
        + "/"
        + totalDocuments
        + "] "
        + virtualDocumentPath
        + " - "
        + progressStatus;
  }

  private enum ExtractionDisposition {
    EXTRACTED,
    FAILED,
    SKIPPED
  }

  private record ExtractionOutcome(ExtractionDisposition disposition, String progressStatus) {}

  private record ExtractionSummary(
      int extractedDocumentCount, int failedDocumentCount, int skippedDocumentCount) {}
}
