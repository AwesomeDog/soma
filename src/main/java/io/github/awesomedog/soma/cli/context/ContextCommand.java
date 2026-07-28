package io.github.awesomedog.soma.cli.context;

import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static io.github.awesomedog.soma.app.common.Renderable.requireTextFormat;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.Renderable;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.app.project.ProjectSelection;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.ProjectScopeMixin;
import io.github.awesomedog.soma.cli.common.SubcommandRequiredCommand;
import io.github.awesomedog.soma.domain.config.ContextConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Parameters;

@Prototype
@Command(
    name = "context",
    description = "Manage global or project path context.",
    addMethodSubcommands = true)
public final class ContextCommand extends SubcommandRequiredCommand {

  @Mixin ProjectScopeMixin projectScope;

  @Inject ConfigStore configStore;

  @Inject WriteLock writeLock;

  @Inject ActiveWorkspace workspace;

  @Command(name = "list", aliases = "ls", description = "List contexts; omit -p to include all.")
  public int list() {
    var config = configStore.load(workspace.configFile());
    var projectFilter = explicitProjectNames(config);
    var includeAllContexts = projectFilter.isEmpty();
    var contexts =
        config.context().stream()
            .filter(
                context ->
                    includeAllContexts
                        || context.project() != null && projectFilter.contains(context.project()))
            .map(ContextRow::from)
            .toList();
    emit(
        new ContextResult(
            contexts.isEmpty() ? "No contexts configured." : "Configured Contexts", contexts));
    return 0;
  }

  @Command(
      name = "set",
      description = "Add or replace context for a path; omit -p for global context.")
  public int set(
      @Parameters(
              index = "0",
              paramLabel = "<path>",
              description =
                  "Scope starting with /; no trailing slash except /, which matches every document.")
          String path,
      @Parameters(index = "1", paramLabel = "<text>", description = "Nonblank context text.")
          String text) {
    return editContext("set", path, text);
  }

  @Command(
      name = "remove",
      description = "Remove context; omit -p for global context; missing entries are ignored.")
  public int remove(
      @Parameters(
              index = "0",
              paramLabel = "<path>",
              description =
                  "Scope starting with /; no trailing slash except /, which matches every document.")
          String path) {
    return editContext("remove", path, null);
  }

  private int editContext(String action, String path, String text) {
    var removing = action.equals("remove");
    try (var ignored = writeLock.acquire(workspace.lockFile(), "soma context " + action)) {
      var config = configStore.loadOrBackupResetForUpdate(workspace.configFile());
      var targetProjectNames = explicitProjectNames(config);
      var targetsGlobalContext = targetProjectNames.isEmpty();
      var normalizedPath = normalizeContextPath(path);
      if (!removing && (text == null || text.isBlank())) {
        throw new AppException(
            INVALID_REQUEST, "Context text must not be blank.", "Provide context text.");
      }
      var matches =
          config.context().stream()
              .filter(
                  context ->
                      isTargeted(context, targetsGlobalContext, targetProjectNames, normalizedPath))
              .toList();
      var replacements =
          removing
              ? List.<ContextConfig>of()
              : targetsGlobalContext
                  ? List.of(new ContextConfig(null, normalizedPath, text))
                  : targetProjectNames.stream()
                      .map(project -> new ContextConfig(project, normalizedPath, text))
                      .toList();
      if (!removing || !matches.isEmpty()) {
        var updated = new ArrayList<>(config.context());
        updated.removeIf(
            context ->
                isTargeted(context, targetsGlobalContext, targetProjectNames, normalizedPath));
        updated.addAll(replacements);
        configStore.save(
            workspace.configFile(), new SomaConfig(config.version(), config.projects(), updated));
      }
      var changed = removing ? matches : replacements;
      emit(
          new ContextResult(
              removing
                  ? changed.isEmpty() ? "No context removed." : "Context removed:"
                  : "Context set:",
              changed.stream().map(ContextRow::from).toList()));
      return 0;
    }
  }

  private List<ProjectName> explicitProjectNames(SomaConfig config) {
    return ProjectSelection.resolveExplicitProjectNames(config, projectScope.projects());
  }

  private static boolean isTargeted(
      ContextConfig context,
      boolean targetsGlobalContext,
      List<ProjectName> targetProjectNames,
      String path) {
    return context.path().equals(path)
        && (targetsGlobalContext
            ? context.project() == null
            : context.project() != null && targetProjectNames.contains(context.project()));
  }

  private static String normalizeContextPath(String path) {
    try {
      return ContextConfig.normalizeAndValidatePath(path);
    } catch (IllegalArgumentException e) {
      throw new AppException(
          INVALID_REQUEST,
          "Invalid context path: " + path,
          "Use `/` or an absolute project path without a trailing slash.",
          e);
    }
  }

  private void emit(ContextResult result) {
    SomaCommand.invocation(spec).emit(result, OutputFormat.text);
  }

  @Serdeable
  public record ContextRow(String project, String path, String text) {

    private static ContextRow from(ContextConfig context) {
      return new ContextRow(
          context.project() == null ? null : context.project().value(),
          context.path(),
          context.text());
    }
  }

  @Serdeable
  public record ContextResult(String summary, List<ContextRow> contexts) implements Renderable {

    public ContextResult {
      contexts = List.copyOf(contexts);
    }

    @Override
    public void render(OutputFormat format, PrintWriter out) {
      requireTextFormat(format);
      out.println(summary);
      for (var context : contexts) {
        out.println(
            "  "
                + (context.project() == null ? "<global>" : context.project())
                + " "
                + context.path());
        out.println("    " + context.text());
      }
    }
  }
}
