package io.github.awesomedog.soma.app.system;

import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.common.ProgressEvent.WorkUnit;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.app.system.NioProjectScanner.ReadFile;
import io.github.awesomedog.soma.app.system.NioProjectScanner.SourceMetadata;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.document.ExtractionStatus;
import io.github.awesomedog.soma.domain.search.LexicalProjector;
import io.github.awesomedog.soma.support.Hashing;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Singleton
public final class ProjectScanning {

  private static final Pattern HEADING = Pattern.compile("(?m)^#{1,2}\\s+(.+)$");
  private static final Pattern EXTENSION = Pattern.compile("\\.[^.]+$");

  private final ConfigStore configStore;
  private final WorkspaceIndex workspaceIndex;
  private final NioProjectScanner scanner;

  public ProjectScanning(
      ConfigStore configStore, WorkspaceIndex workspaceIndex, NioProjectScanner scanner) {
    this.configStore = Objects.requireNonNull(configStore, "configStore");
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
    this.scanner = Objects.requireNonNull(scanner, "scanner");
  }

  public OperationReport scanAll(
      Path configFile, Path databaseFile, WriteLock.Token token, Consumer<ProgressEvent> progress) {
    return scan(configStore.load(configFile), databaseFile, token, progress, true);
  }

  public OperationReport scanIncrementally(
      SomaConfig config,
      Path databaseFile,
      WriteLock.Token token,
      Consumer<ProgressEvent> progress) {
    return scan(config, databaseFile, token, progress, false);
  }

  private OperationReport scan(
      SomaConfig config,
      Path databaseFile,
      WriteLock.Token token,
      Consumer<ProgressEvent> progress,
      boolean full) {
    var progressEvents = progress == null ? (Consumer<ProgressEvent>) ignored -> {} : progress;
    progressEvents.accept(ProgressEvent.message("Preparing index"));
    workspaceIndex.openOrRebuildForScan(databaseFile, token);

    var indexedDocuments =
        full
            ? new LinkedHashMap<String, Map<String, WorkspaceIndex.DocumentSnapshot>>()
            : snapshots();
    var readDocuments = new ArrayList<WorkspaceIndex.DocumentWrite>();
    var unchanged = 0;
    for (var project : config.projects()) {
      var projectName = project.name().value();
      var projectDocuments =
          indexedDocuments.computeIfAbsent(projectName, ignored -> new LinkedHashMap<>());
      progressEvents.accept(ProgressEvent.message("Scanning project " + projectName));
      var scan =
          scanner.scan(
              project,
              full ? Map.of() : sourceMetadata(projectDocuments),
              warning -> progressEvents.accept(ProgressEvent.message("Warning: " + warning)),
              count ->
                  progressEvents.accept(
                      ProgressEvent.update(
                          "Scanning project " + projectName, count, -1, WorkUnit.FILES)));

      for (var documentPath : scan.unchangedDocumentPaths()) {
        if (projectDocuments.remove(documentPath) == null) {
          throw new IllegalStateException(
              "unchanged document missing from index: " + projectName + "/" + documentPath);
        }
        unchanged++;
      }
      for (var file : scan.readFiles()) {
        projectDocuments.remove(file.documentPath());
        readDocuments.add(document(projectName, file));
      }
    }

    var removedDocumentIds = remainingDocumentIds(indexedDocuments);
    if (full) {
      progressEvents.accept(ProgressEvent.message("Clearing index"));
      workspaceIndex.resetForFullScan();
    }
    var synchronizationTotal = (long) readDocuments.size() + removedDocumentIds.size();
    progressEvents.accept(
        ProgressEvent.update("Updating index", 0, synchronizationTotal, WorkUnit.FILES));
    workspaceIndex.rebuildLexicalIndexForRecipe(LexicalProjector.recipeId());
    var applied =
        workspaceIndex.applyScan(
            readDocuments,
            removedDocumentIds,
            unchanged,
            completed ->
                progressEvents.accept(
                    ProgressEvent.update(
                        "Updating index", completed, synchronizationTotal, WorkUnit.FILES)));
    return full ? fullReport(readDocuments) : incrementalReport(readDocuments.size(), applied);
  }

  private Map<String, Map<String, WorkspaceIndex.DocumentSnapshot>> snapshots() {
    var indexedDocuments =
        new LinkedHashMap<String, Map<String, WorkspaceIndex.DocumentSnapshot>>();
    for (var snapshot : workspaceIndex.documentSnapshots()) {
      indexedDocuments
          .computeIfAbsent(snapshot.project(), ignored -> new LinkedHashMap<>())
          .put(snapshot.path(), snapshot);
    }
    return indexedDocuments;
  }

  private static Map<String, SourceMetadata> sourceMetadata(
      Map<String, WorkspaceIndex.DocumentSnapshot> documents) {
    var metadata = new HashMap<String, SourceMetadata>(documents.size());
    for (var document : documents.values()) {
      metadata.put(
          document.path(), new SourceMetadata(document.modifiedTimeNs(), document.sizeBytes()));
    }
    return metadata;
  }

  private static List<Long> remainingDocumentIds(
      Map<String, Map<String, WorkspaceIndex.DocumentSnapshot>> indexedDocuments) {
    return indexedDocuments.values().stream()
        .flatMap(documents -> documents.values().stream())
        .map(WorkspaceIndex.DocumentSnapshot::id)
        .toList();
  }

  private static WorkspaceIndex.DocumentWrite document(String project, ReadFile file) {
    var decodedText = file.decodedText();
    var status =
        decodedText != null
            ? ExtractionStatus.READY
            : file.sourceHash() != null ? ExtractionStatus.PENDING : ExtractionStatus.FAILED;
    var contentHash = decodedText == null ? null : Hashing.sha256HexUtf8(decodedText);
    return new WorkspaceIndex.DocumentWrite(
        project,
        file.documentPath(),
        contentHash == null ? file.sourceHash() : contentHash,
        contentHash,
        deriveDocumentTitle(decodedText, file.documentPath()),
        file.modifiedTimeNs(),
        file.sizeBytes(),
        file.fileType(),
        status,
        decodedText);
  }

  private static String deriveDocumentTitle(String decodedText, String documentPath) {
    if (decodedText != null && !decodedText.isBlank()) {
      var heading = HEADING.matcher(decodedText);
      if (heading.find() && !heading.group(1).isBlank()) {
        return heading.group(1).strip();
      }
    }
    var separator = documentPath.lastIndexOf('/');
    var fileName = separator < 0 ? documentPath : documentPath.substring(separator + 1);
    return EXTENSION.matcher(fileName).replaceFirst("");
  }

  private static OperationReport fullReport(List<WorkspaceIndex.DocumentWrite> documents) {
    var counts = new LinkedHashMap<String, Integer>();
    for (var status : ExtractionStatus.values()) {
      counts.put(
          status.value(),
          (int) documents.stream().filter(document -> document.status() == status).count());
    }
    return new OperationReport(
        "scan",
        "Scanned "
            + documents.size()
            + " document(s): "
            + counts.get("ready")
            + " ready, "
            + counts.get("pending")
            + " pending, "
            + counts.get("failed")
            + " failed.",
        counts);
  }

  private static OperationReport incrementalReport(
      int readFiles, WorkspaceIndex.DocumentScanReport applied) {
    var counts = new LinkedHashMap<String, Integer>();
    counts.put("upserted", applied.upserted());
    counts.put("metadataUpdated", applied.metadataUpdated());
    counts.put("unchanged", applied.unchanged());
    counts.put("removed", applied.removed());
    var changed = applied.upserted() + applied.metadataUpdated();
    return new OperationReport(
        "scan",
        "Scanned "
            + (readFiles + applied.unchanged())
            + " document(s): "
            + changed
            + " changed, "
            + applied.unchanged()
            + " unchanged, "
            + applied.removed()
            + " removed.",
        counts);
  }
}
