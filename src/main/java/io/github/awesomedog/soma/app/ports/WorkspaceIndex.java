package io.github.awesomedog.soma.app.ports;

import io.github.awesomedog.soma.domain.document.ExtractionStatus;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.domain.document.VirtualPath;
import io.github.awesomedog.soma.domain.search.LexicalQuery;
import io.micronaut.serde.annotation.Serdeable;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public interface WorkspaceIndex {

  int DOC_ID_LENGTH = 6;

  static String docId(String contentHash) {
    return contentHash == null || contentHash.length() < DOC_ID_LENGTH
        ? null
        : "@" + contentHash.substring(0, DOC_ID_LENGTH);
  }

  void openOrRebuildForScan(Path databaseFile, WriteLock.Token writeLock);

  void openExistingForWrite(Path databaseFile, WriteLock.Token writeLock);

  void resetForFullScan();

  void rebuildLexicalIndexForRecipe(String desiredRecipeId);

  List<DocumentSnapshot> documentSnapshots();

  DocumentScanReport applyScan(
      List<DocumentWrite> inspectedDocuments,
      List<Long> removedDocumentIds,
      int unchanged,
      Consumer<Long> progress);

  void invalidateExtractionForRecipeChanges(Map<FileType, String> desiredRecipeIds);

  List<ExtractionWork> extractionWork();

  void publishExtraction(long documentId, String contentHash, String extractedBody);

  void failExtraction(long documentId);

  void resetSemanticIndexForRecipe(String desiredRecipeId);

  List<ChunkingWork> chunkingWork(List<String> projectNames);

  void writeChunks(String contentHash, List<ChunkWrite> chunks);

  List<EmbeddingWork> embeddingWork(List<String> projectNames);

  void writeEmbeddings(List<EmbeddingWrite> embeddings);

  int cleanOrphans();

  List<DocumentRead> listDocuments(Path databaseFile, String projectName, String pathPrefix);

  Optional<DocumentRead> findDocument(
      Path databaseFile, String projectName, String documentPath, long maximumBodyBytes);

  List<DocumentRead> findReadyDocumentsByDocId(
      Path databaseFile, String docId, long maximumBodyBytes);

  Map<String, ProjectStats> projectStats(Path databaseFile, List<String> projectNames);

  List<SearchHit> lexicalSearch(
      Path databaseFile, List<String> projectNames, LexicalQuery lexicalQuery, int candidateLimit);

  List<ChunkRead> chunks(Path databaseFile, List<String> contentHashes);

  List<SearchHit> vectorSearch(
      Path databaseFile, List<String> projectNames, float[] queryVector, int candidateLimit);

  record DocumentWrite(
      String project,
      String path,
      String sourceHash,
      String contentHash,
      String title,
      long modifiedTimeNs,
      long sizeBytes,
      FileType fileType,
      ExtractionStatus status,
      String body) {}

  record DocumentScanReport(int upserted, int metadataUpdated, int unchanged, int removed) {}

  record DocumentSnapshot(
      long id, String project, String path, long modifiedTimeNs, long sizeBytes) {}

  record ExtractionWork(
      long documentId, String project, String path, FileType fileType, String sourceHash) {}

  record ChunkingWork(String contentHash, String body) {}

  record ChunkWrite(
      int index, int charStartOffset, int charEndOffset, String body, int tokenCount) {}

  record EmbeddingWork(
      long documentId,
      String project,
      String path,
      String title,
      String contentHash,
      int chunkIndex,
      String chunkBody) {}

  record EmbeddingWrite(long documentId, int chunkIndex, int inputTokenCount, float[] vector) {}

  @Serdeable
  record DocumentRead(
      String project,
      String path,
      String contentHash,
      String title,
      long sizeBytes,
      long modifiedTimeNs,
      FileType fileType,
      ExtractionStatus status,
      long bodySizeBytes,
      String body) {

    public String virtualPath() {
      return new VirtualPath(project, path).toString();
    }
  }

  @Serdeable
  record ProjectStats(
      long documents,
      long ready,
      long pending,
      long failed,
      long lexical,
      long chunks,
      long embeddings,
      long vectors,
      String updatedAt) {

    public static ProjectStats empty() {
      return new ProjectStats(0, 0, 0, 0, 0, 0, 0, 0, null);
    }
  }

  record ChunkRead(
      String contentHash, int index, String body, int charStartOffset, int charEndOffset) {}

  record SearchHit(
      String project,
      String path,
      String title,
      String contentHash,
      String evidenceBody,
      String documentBody,
      Integer chunkIndex,
      Integer evidenceStartOffset,
      Integer evidenceEndOffset,
      double score) {

    public String virtualPath() {
      return new VirtualPath(project, path).toString();
    }
  }
}
