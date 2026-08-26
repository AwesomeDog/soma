package io.github.awesomedog.soma.cli.system;

import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.common.Renderable;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.app.system.ArtifactProvisioning;
import io.github.awesomedog.soma.app.system.ContentExtraction;
import io.github.awesomedog.soma.app.system.EmbeddingGeneration;
import io.github.awesomedog.soma.app.system.IndexCleanup;
import io.github.awesomedog.soma.app.system.ProjectScanning;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.ProjectScopeMixin;
import io.github.awesomedog.soma.cli.common.SubcommandRequiredCommand;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.github.awesomedog.soma.exec.Invocation;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Prototype
@Command(
    name = "system",
    description = "Run diagnostics and maintenance; prefer sync for routine use.",
    addMethodSubcommands = true)
public final class SystemCommand extends SubcommandRequiredCommand {

  @Inject ArtifactProvisioning artifactProvisioning;

  @Inject ProjectScanning projectScanning;

  @Inject ContentExtraction contentExtraction;

  @Inject EmbeddingGeneration embeddingGeneration;

  @Inject IndexCleanup indexCleanup;

  @Inject WriteLock writeLock;

  @Inject ActiveWorkspace workspace;

  @Command(name = "pull", description = "Download missing artifacts, or export/import packages.")
  public int pull(
      @Option(
              names = "--refresh",
              arity = "0",
              fallbackValue = "true",
              description = "Reverify and redownload packages; conflicts with --export/--import.")
          boolean refresh,
      @Option(
              names = "--export",
              paramLabel = "<arc.zip>",
              description =
                  "Export all platform and shared packages; conflicts with --refresh/--import.")
          Path exportArchive,
      @Option(
              names = "--import",
              paramLabel = "<arc.zip>",
              description =
                  "Import current-platform and shared packages offline; conflicts with --refresh/--export.")
          Path importArchive) {
    var command = leafSpec();
    if (exportArchive != null && importArchive != null) {
      throw new CommandLine.ParameterException(
          command.commandLine(), "`--export` and `--import` are mutually exclusive.");
    }
    if (refresh && exportArchive != null) {
      throw new CommandLine.ParameterException(
          command.commandLine(), "`--refresh` cannot be used with `--export`.");
    }
    if (refresh && importArchive != null) {
      throw new CommandLine.ParameterException(
          command.commandLine(), "`--refresh` cannot be used with `--import`.");
    }

    var invocation = SomaCommand.invocation(command);
    Consumer<ProgressEvent> progress =
        event -> {
          if (event.completed() == null) {
            invocation.finishProgress();
            invocation.err().println("  " + event.message());
          } else {
            invocation.progress(event);
          }
        };
    var result =
        exportArchive != null
            ? artifactProvisioning.exportArchive(exportArchive, progress)
            : importArchive != null
                ? artifactProvisioning.importArchive(importArchive, progress)
                : artifactProvisioning.pull(refresh, progress);
    invocation.emit(result, OutputFormat.text);
    return 0;
  }

  @Command(
      name = "scan",
      description = {
        "Fully rescan every included file and rebuild the index.",
        "Always rereads files and commits in batches."
      })
  public int scan() {
    return locked(
        (token, invocation) ->
            projectScanning.scanAll(
                workspace.configFile(), workspace.dbFile(), token, progress(invocation)));
  }

  @Command(
      name = "extract",
      description =
          "Run PDF extraction, Office/EPUB-to-Markdown conversion, OCR, vision extraction, and transcription.")
  public int extract() {
    return locked(
        invocation -> artifactProvisioning.pull(false, progress(invocation)),
        (token, invocation) ->
            contentExtraction.extractPending(
                workspace.configFile(), workspace.dbFile(), token, progress(invocation)));
  }

  @Command(
      name = "embed",
      description = "Generate or refresh embeddings; omit -p to process all projects.")
  public int embed(@Mixin ProjectScopeMixin projectScope) {
    return locked(
        invocation -> artifactProvisioning.pull(false, progress(invocation)),
        (token, invocation) ->
            embeddingGeneration.generate(
                workspace.configFile(),
                workspace.dbFile(),
                projectScope.projects(),
                token,
                progress(invocation)));
  }

  @Command(
      name = "clean",
      description = "Clean orphaned index records; do not modify the processing cache.")
  public int clean() {
    return locked((token, invocation) -> indexCleanup.removeOrphans(workspace.dbFile(), token));
  }

  private int locked(BiFunction<WriteLock.Token, Invocation, Renderable> operation) {
    return locked(ignored -> {}, operation);
  }

  private int locked(
      Consumer<Invocation> beforeLock,
      BiFunction<WriteLock.Token, Invocation, Renderable> operation) {
    if (writeLock == null || workspace == null) {
      throw new IllegalStateException("write commands must run via CommandRunner");
    }
    var command = leafSpec();
    var invocation = SomaCommand.invocation(command);
    beforeLock.accept(invocation);
    try (var token = writeLock.acquire(workspace.lockFile(), command.qualifiedName())) {
      invocation.emit(operation.apply(token, invocation), OutputFormat.text);
      return 0;
    }
  }
}
