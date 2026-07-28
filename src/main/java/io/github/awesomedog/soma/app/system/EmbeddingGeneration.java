package io.github.awesomedog.soma.app.system;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.common.ProgressEvent.WorkUnit;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.app.project.ProjectSelection;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Singleton
public final class EmbeddingGeneration {

  private static final int BATCH_SIZE = 32;

  private final ConfigStore configStore;
  private final WorkspaceIndex workspaceIndex;
  private final SearchModels searchModels;
  private final SemanticChunker chunker;

  public EmbeddingGeneration(
      ConfigStore configStore, WorkspaceIndex workspaceIndex, SearchModels searchModels) {
    this.configStore = Objects.requireNonNull(configStore, "configStore");
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
    this.searchModels = Objects.requireNonNull(searchModels, "searchModels");
    this.chunker = new SemanticChunker(searchModels);
  }

  public OperationReport generate(
      Path configFile,
      Path databaseFile,
      List<String> requestedProjects,
      WriteLock.Token token,
      Consumer<ProgressEvent> progress) {
    return generate(configStore.load(configFile), databaseFile, requestedProjects, token, progress);
  }

  OperationReport generate(
      SomaConfig config,
      Path databaseFile,
      List<String> requestedProjects,
      WriteLock.Token token,
      Consumer<ProgressEvent> progress) {
    var events = progress == null ? (Consumer<ProgressEvent>) ignored -> {} : progress;
    var startNanos = System.nanoTime();
    var projects = selectedProjects(config, requestedProjects);
    workspaceIndex.openExistingForWrite(databaseFile, token);

    var metadata = searchModels.embeddingMetadata();
    validateModel(metadata);
    workspaceIndex.resetSemanticIndexForRecipe(semanticRecipeId(metadata));

    var chunkingWork = workspaceIndex.chunkingWork(projects);
    if (!chunkingWork.isEmpty()) {
      events.accept(
          ProgressEvent.update("Chunking documents", 0, chunkingWork.size(), WorkUnit.FILES));
      for (var position = 0; position < chunkingWork.size(); position++) {
        var work = chunkingWork.get(position);
        workspaceIndex.writeChunks(work.contentHash(), chunker.plan(work.body(), metadata));
        events.accept(
            ProgressEvent.update(
                "Chunking documents", position + 1, chunkingWork.size(), WorkUnit.FILES));
      }
    }

    var embeddingWork = workspaceIndex.embeddingWork(projects);
    if (embeddingWork.isEmpty()) {
      return new OperationReport(
          "embed",
          "All ready documents already have embeddings.",
          Map.of("documents", 0, "chunks", 0));
    }

    for (var start = 0; start < embeddingWork.size(); start += BATCH_SIZE) {
      var batch = embeddingWork.subList(start, Math.min(start + BATCH_SIZE, embeddingWork.size()));
      writeEmbeddingBatch(batch, metadata);
      events.accept(
          ProgressEvent.update(
              "Embedding ready documents",
              Math.min(start + BATCH_SIZE, embeddingWork.size()),
              embeddingWork.size(),
              WorkUnit.CHUNKS));
    }
    return completedReport(embeddingWork, startNanos);
  }

  private void writeEmbeddingBatch(
      List<WorkspaceIndex.EmbeddingWork> work, SearchModels.EmbeddingMetadata metadata) {
    var inputs = new ArrayList<EmbeddingInput>(work.size());
    for (var item : work) {
      var text = formatDocumentEmbeddingInput(item.title(), item.chunkBody());
      var tokenCount = searchModels.countTokens(text);
      if (tokenCount > metadata.maxInputTokens()) {
        throw new AppException(
            OPERATION_FAILED,
            "A document chunk exceeds the embedding model context.",
            "Run `soma sync`, then retry.");
      }
      inputs.add(new EmbeddingInput(item, text, tokenCount));
    }

    var vectors = searchModels.embedBatch(inputs.stream().map(EmbeddingInput::text).toList());
    if (vectors == null || vectors.size() != inputs.size()) {
      throw new AppException(
          OPERATION_FAILED,
          "The managed embedding runtime returned an incomplete batch.",
          "Run `soma sync`, then retry.");
    }
    var writes = new ArrayList<WorkspaceIndex.EmbeddingWrite>(inputs.size());
    for (var position = 0; position < inputs.size(); position++) {
      var input = inputs.get(position);
      writes.add(
          new WorkspaceIndex.EmbeddingWrite(
              input.work().documentId(),
              input.work().chunkIndex(),
              input.tokenCount(),
              vectors.get(position)));
    }
    workspaceIndex.writeEmbeddings(writes);
  }

  private static OperationReport completedReport(
      List<WorkspaceIndex.EmbeddingWork> work, long startNanos) {
    var documentCount =
        (int) work.stream().map(WorkspaceIndex.EmbeddingWork::documentId).distinct().count();
    var counts = new LinkedHashMap<String, Integer>();
    counts.put("documents", documentCount);
    counts.put("chunks", work.size());
    return new OperationReport(
        "embed",
        "Done! Embedded "
            + work.size()
            + " chunks from "
            + documentCount
            + " documents in "
            + DisplayFormat.duration(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))
            + ".",
        counts);
  }

  private List<String> selectedProjects(SomaConfig config, List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return config.projects().stream().map(project -> project.name().value()).toList();
    }
    return ProjectSelection.resolveExplicitProjectNames(config, requested).stream()
        .map(ProjectName::value)
        .toList();
  }

  private void validateModel(SearchModels.EmbeddingMetadata metadata) {
    if (metadata == null
        || metadata.dimensions() != SearchModels.VECTOR_DIMENSIONS
        || !chunker.supports(metadata)) {
      throw new AppException(
          OPERATION_FAILED,
          "The managed embedding model is incompatible with this Soma index.",
          "Run `soma sync`, then retry.");
    }
  }

  private String semanticRecipeId(SearchModels.EmbeddingMetadata metadata) {
    return RecipeId.of(
        "semantic.index",
        "v1",
        chunker.recipeId(metadata),
        metadata.embeddingModelRecipeId(),
        metadata.tokenizerRecipeId(),
        "dimensions=" + metadata.dimensions(),
        "max_input=" + metadata.maxInputTokens(),
        "formatter=title: <title-or-none> | text: <body>");
  }

  private static String formatDocumentEmbeddingInput(String title, String body) {
    return "title: " + (title == null || title.isBlank() ? "none" : title) + " | text: " + body;
  }

  private record EmbeddingInput(WorkspaceIndex.EmbeddingWork work, String text, int tokenCount) {}
}
