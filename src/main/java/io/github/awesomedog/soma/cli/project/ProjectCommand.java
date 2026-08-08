package io.github.awesomedog.soma.cli.project;

import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static io.github.awesomedog.soma.app.common.AppError.Code.NOT_FOUND;
import static io.github.awesomedog.soma.app.common.Renderable.requireTextFormat;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.Renderable;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.DocumentRead;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ProjectStats;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.app.project.ProjectView;
import io.github.awesomedog.soma.app.system.OperationReport;
import io.github.awesomedog.soma.app.system.ProjectScanning;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.SubcommandRequiredCommand;
import io.github.awesomedog.soma.domain.config.ContextConfig;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig.DuplicateProjectNameException;
import io.github.awesomedog.soma.domain.document.DocumentPath;
import io.github.awesomedog.soma.domain.document.VirtualPath;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.github.awesomedog.soma.exec.Invocation;
import io.github.awesomedog.soma.support.PathSupport;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Prototype
@Command(name = "project", description = "Manage projects.", addMethodSubcommands = true)
public final class ProjectCommand extends SubcommandRequiredCommand {

  @Inject ConfigStore configStore;
  @Inject WorkspaceIndex workspaceIndex;
  @Inject WriteLock writeLock;
  @Inject ProjectScanning projectScanning;
  @Inject ActiveWorkspace workspace;

  @Command(
      name = "list",
      aliases = "ls",
      description = "List configured project attributes from YAML.")
  public int list(
      @Option(
              names = "--default-search",
              arity = "0",
              fallbackValue = "true",
              description = "Only projects in the default search scope.")
          boolean defaultSearchOnly) {
    var projects =
        configStore.load(workspace.configFile()).projects().stream()
            .filter(project -> !defaultSearchOnly || project.defaultSearch())
            .map(project -> ProjectView.from(project, null))
            .toList();
    return emit(new ProjectList(projects, false));
  }

  @Command(name = "files", description = "List one project's files by literal path prefix.")
  public int files(
      @Parameters(
              index = "0",
              paramLabel = "<path>",
              description = "Project name or path prefix. Accepts Virtual and filesystem paths.")
          String path) {
    return emit(new ProjectFiles(projectFiles(configStore.load(workspace.configFile()), path)));
  }

  @Command(
      name = "show",
      description = "Show project root, globs, search scope, and index statistics.")
  public int show(
      @Parameters(
              index = "0",
              paramLabel = "<name>",
              description = "Project name.",
              converter = ProjectNameConverter.class)
          ProjectName projectName) {
    var project = requireProject(configStore.load(workspace.configFile()).projects(), projectName);
    var name = projectName.value();
    var stats =
        workspaceIndex
            .projectStats(workspace.dbFile(), List.of(name))
            .getOrDefault(name, ProjectStats.empty());
    return emit(new ProjectList(List.of(ProjectView.from(project, stats)), true));
  }

  @Command(
      name = "add",
      description = {
        "Add a project and index matching files.",
        "Writes config, then incrementally scans projects; the change remains if scanning fails."
      })
  public int add(
      @Parameters(
              index = "0",
              paramLabel = "<root>",
              description = {"Project root directory.", "Relative and ~ paths are accepted."})
          String requestedRoot,
      @Option(
              names = "--name",
              paramLabel = "<name>",
              description = "Project name; defaults to the root basename.",
              converter = ProjectNameConverter.class)
          ProjectName requestedName,
      @Option(
              names = "--include",
              paramLabel = "<glob>",
              defaultValue = "**/*",
              description = {
                "Files to index; repeatable (default: ${DEFAULT-VALUE}).",
                "Globs are root-relative."
              })
          List<String> include,
      @Option(
              names = "--exclude",
              paramLabel = "<glob>",
              description = {"Files to skip; repeatable.", "Globs are root-relative."})
          List<String> exclude,
      @Option(
              names = "--no-ignore-files",
              arity = "0",
              fallbackValue = "true",
              description = {"Do not respect ignore files.", "Includes .gitignore-style files."})
          boolean noIgnoreFiles,
      @Option(
              names = "--no-default-search",
              arity = "0",
              fallbackValue = "true",
              description = {
                "Exclude from the default search scope.",
                "Projects are included by default."
              })
          boolean noDefaultSearch) {
    return add(
        SomaCommand.invocation(spec),
        requestedRoot,
        requestedName,
        include,
        exclude,
        noIgnoreFiles,
        noDefaultSearch);
  }

  private int add(
      Invocation invocation,
      String requestedRoot,
      ProjectName requestedName,
      List<String> include,
      List<String> exclude,
      boolean noIgnoreFiles,
      boolean noDefaultSearch) {
    var root = projectRoot(requestedRoot);
    var name = requestedName == null ? projectName(root.getFileName()) : requestedName;
    var project =
        projectConfig(
            name,
            root,
            include == null ? List.of() : include,
            exclude == null ? List.of() : exclude,
            !noDefaultSearch,
            !noIgnoreFiles);
    return mutate(
        invocation,
        "add",
        "Added project: " + name,
        "Next: run `soma sync` to complete indexing for hybrid search.",
        edit -> edit.projects.add(project));
  }

  @Command(
      name = "update",
      description = {
        "Update project attributes.",
        "Writes config, then incrementally scans projects; the change remains if scanning fails."
      })
  public int update(
      @Parameters(
              arity = "1..*",
              paramLabel = "<names>",
              description = "Project names.",
              converter = ProjectNameConverter.class)
          List<ProjectName> requestedNames,
      @Option(
              names = "--default-search",
              negatable = true,
              required = true,
              arity = "0",
              description = "Add to the default search scope; use --no-default-search to remove.")
          boolean defaultSearch) {
    var names = requestedNames.stream().distinct().toList();
    return mutate(
        "update",
        "Updated projects: " + join(names),
        null,
        edit -> {
          names.forEach(edit::require);
          edit.projects.replaceAll(
              project ->
                  names.contains(project.name())
                      ? project.withDefaultSearch(defaultSearch)
                      : project);
        });
  }

  @Command(
      name = "remove",
      description = {
        "Remove a project.",
        "Also removes contexts that reference it.",
        "Writes config, then incrementally scans projects; the change remains if scanning fails."
      })
  public int remove(
      @Parameters(
              index = "0",
              paramLabel = "<name>",
              description = "Project name.",
              converter = ProjectNameConverter.class)
          ProjectName name) {
    return mutate(
        "remove",
        "Removed project: " + name,
        null,
        edit -> {
          edit.require(name);
          edit.projects.removeIf(project -> project.name().equals(name));
          edit.contexts.removeIf(context -> Objects.equals(context.project(), name));
        });
  }

  @Command(
      name = "rename",
      description = {
        "Rename a project.",
        "Also updates contexts that reference the old name.",
        "Writes config, then incrementally scans projects; the change remains if scanning fails."
      })
  public int rename(
      @Parameters(
              index = "0",
              paramLabel = "<old>",
              description = "Current project name.",
              converter = ProjectNameConverter.class)
          ProjectName oldName,
      @Parameters(
              index = "1",
              paramLabel = "<new>",
              description = "New project name.",
              converter = ProjectNameConverter.class)
          ProjectName newName) {
    return mutate(
        "rename",
        "Renamed project: "
            + oldName
            + " -> "
            + newName
            + "\nVirtual paths: "
            + new VirtualPath(oldName.value(), "")
            + " -> "
            + new VirtualPath(newName.value(), ""),
        null,
        edit -> {
          edit.require(oldName);
          edit.projects.replaceAll(
              project -> project.name().equals(oldName) ? project.withName(newName) : project);
          edit.contexts.replaceAll(
              context ->
                  Objects.equals(context.project(), oldName)
                      ? new ContextConfig(newName, context.path(), context.text())
                      : context);
        });
  }

  private int mutate(
      String action, String message, String nextStep, Consumer<ConfigEdit> mutation) {
    return mutate(SomaCommand.invocation(spec), action, message, nextStep, mutation);
  }

  private int mutate(
      Invocation invocation,
      String action,
      String message,
      String nextStep,
      Consumer<ConfigEdit> mutation) {
    try (WriteLock.Token token =
        writeLock.acquire(workspace.lockFile(), "soma project " + action)) {
      var edit = new ConfigEdit(configStore.loadOrBackupResetForUpdate(workspace.configFile()));
      final SomaConfig updated;
      try {
        mutation.accept(edit);
        updated = edit.result();
      } catch (DuplicateProjectNameException failure) {
        throw new AppException(
            INVALID_REQUEST,
            failure.getMessage(),
            "add".equals(action) ? "Run again with `--name <different-name>`." : null,
            failure);
      } catch (IllegalArgumentException failure) {
        throw invalid(failure.getMessage(), failure);
      }
      configStore.save(workspace.configFile(), updated);
      invocation.err().println("Project configuration was saved; the YAML change is preserved.");
      try {
        var scan =
            projectScanning.scanIncrementally(
                updated, workspace.dbFile(), token, progress(invocation));
        var stats =
            workspaceIndex
                .projectStats(
                    workspace.dbFile(),
                    updated.projects().stream().map(project -> project.name().value()).toList())
                .values();
        var counts = new LinkedHashMap<>(scan.counts());
        counts.put("ready", Math.toIntExact(stats.stream().mapToLong(ProjectStats::ready).sum()));
        counts.put(
            "pending", Math.toIntExact(stats.stream().mapToLong(ProjectStats::pending).sum()));
        counts.put("failed", Math.toIntExact(stats.stream().mapToLong(ProjectStats::failed).sum()));
        var summary =
            "%s%nScan: %d ready, %d pending, %d failed"
                .formatted(
                    message,
                    counts.getOrDefault("ready", 0),
                    counts.getOrDefault("pending", 0),
                    counts.getOrDefault("failed", 0));
        if (nextStep != null) {
          summary += "%n%s".formatted(nextStep);
        }
        invocation.emit(
            new OperationReport("project." + action, summary, counts), OutputFormat.text);
        return 0;
      } catch (AppException failure) {
        throw new AppException(
            failure.error().code(),
            failure.error().message(),
            "Project configuration was saved. Fix the reported problem, then run `soma sync`.",
            failure);
      }
    }
  }

  private int emit(Renderable result) {
    SomaCommand.invocation(spec).emit(result, OutputFormat.text);
    return 0;
  }

  private List<DocumentRead> projectFiles(SomaConfig config, String requested) {
    if (requested == null || requested.isBlank()) {
      throw invalid("Project files requires a path.", null);
    }
    var input = VirtualPath.parseInput(requested);
    var project = config.projectByCanonicalName(input.project());
    if (project != null) {
      var prefix = input.hasPathSeparator() ? documentPrefix(input.path()) : "";
      return projectFiles(project, prefix);
    }
    if (input.explicit()) {
      throw new AppException(NOT_FOUND, "Project not found: " + input.project(), null);
    }

    final Path path;
    try {
      path = PathSupport.resolveUserPath(requested);
    } catch (RuntimeException e) {
      throw invalid("Invalid filesystem path: " + requested, e);
    }
    var projectRelativePath = config.mapSourcePath(path);
    if (projectRelativePath != null) {
      return projectFiles(projectRelativePath.project(), projectRelativePath.relativePath());
    }
    throw new AppException(NOT_FOUND, "No project contains path: " + requested, null);
  }

  private List<DocumentRead> projectFiles(ProjectConfig project, String prefix) {
    return workspaceIndex.listDocuments(workspace.dbFile(), project.name().value(), prefix);
  }

  private static String documentPrefix(String value) {
    if (value.isEmpty()) {
      return "";
    }
    try {
      return new DocumentPath(value).value();
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage(), e);
    }
  }

  private static Path projectRoot(String value) {
    if (value == null || value.isBlank()) {
      throw invalid("Project root must not be blank.", null);
    }
    final Path root;
    try {
      root = PathSupport.resolveUserPath(value);
    } catch (InvalidPathException | NullPointerException e) {
      throw invalid("Invalid project root: " + value, e);
    }
    if (!Files.isDirectory(root) || !Files.isReadable(root)) {
      throw invalid("Project root is not a readable directory: " + root, null);
    }
    try {
      return root.toRealPath(NOFOLLOW_LINKS);
    } catch (IOException | SecurityException e) {
      throw invalid("Project root is not a readable directory: " + root, e);
    }
  }

  private static ProjectName projectName(Path fileName) {
    try {
      return new ProjectName(fileName == null ? "" : fileName.toString());
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage(), e);
    }
  }

  private static ProjectConfig projectConfig(
      ProjectName name,
      Path root,
      List<String> include,
      List<String> exclude,
      boolean defaultSearch,
      boolean ignoreFiles) {
    try {
      return new ProjectConfig(name, root, include, exclude, defaultSearch, ignoreFiles);
    } catch (IllegalArgumentException e) {
      throw invalid(e.getMessage(), e);
    }
  }

  private static ProjectConfig requireProject(
      List<ProjectConfig> projects, ProjectName projectName) {
    var project =
        projects.stream()
            .filter(candidate -> candidate.name().equals(projectName))
            .findFirst()
            .orElse(null);
    if (project == null) {
      throw new AppException(NOT_FOUND, "Project not found: " + projectName, null);
    }
    return project;
  }

  private static String join(List<ProjectName> names) {
    return String.join(", ", names.stream().map(ProjectName::value).toList());
  }

  private static AppException invalid(String message, Throwable cause) {
    return new AppException(INVALID_REQUEST, message, null, cause);
  }

  public static final class ProjectNameConverter implements ITypeConverter<ProjectName> {

    @Override
    public ProjectName convert(String value) {
      return new ProjectName(value);
    }
  }

  private static final class ConfigEdit {
    private final int version;
    private final ArrayList<ProjectConfig> projects;
    private final ArrayList<ContextConfig> contexts;

    private ConfigEdit(SomaConfig config) {
      version = config.version();
      projects = new ArrayList<>(config.projects());
      contexts = new ArrayList<>(config.context());
    }

    private ProjectConfig require(ProjectName name) {
      return requireProject(projects, name);
    }

    private SomaConfig result() {
      return new SomaConfig(version, projects, contexts);
    }
  }

  @Serdeable
  public record ProjectFiles(List<DocumentRead> files) implements Renderable {
    @Override
    public void render(OutputFormat format, PrintWriter out) {
      requireTextFormat(format);
      files.forEach(file -> out.println(file.virtualPath()));
    }
  }

  @Serdeable
  public record ProjectList(List<ProjectView> projects, boolean show) implements Renderable {

    @Override
    public void render(OutputFormat format, PrintWriter out) {
      requireTextFormat(format);
      if (projects.isEmpty()) {
        out.println("No projects found. Run `soma project add .` to create one.");
        return;
      }
      if (!show) {
        out.printf("Projects (%d):%n%n", projects.size());
      }
      for (var project : projects) {
        out.println(
            show
                ? "Project: " + project.name()
                : project.name()
                    + " ("
                    + new VirtualPath(project.name(), "")
                    + ")"
                    + (project.defaultSearch() ? "" : " [not in default search scope]"));
        renderAttributes(out, project);
        if (!show) {
          out.println();
        }
      }
    }

    private static void renderAttributes(PrintWriter out, ProjectView project) {
      out.printf(
          "  Root:                 %s%n  Include:              %s%n  Exclude:              %s%n"
              + "  Ignore-files:         %s%n  Default search scope: %s%n",
          project.root(),
          String.join(", ", project.include()),
          project.exclude().isEmpty() ? "-" : String.join(", ", project.exclude()),
          project.ignoreFiles() ? "yes" : "no",
          project.defaultSearch() ? "yes" : "no");
      var stats = project.stats();
      if (stats == null) {
        out.println("  Documents:      -");
        out.println("  Index:          -");
        return;
      }
      out.printf(
          "  Documents:      %d total, %d ready, %d pending, %d failed%n",
          stats.documents(), stats.ready(), stats.pending(), stats.failed());
      out.printf(
          "  Index:          %d lexical, %d chunks, %d embeddings, %d vectors%n",
          stats.lexical(), stats.chunks(), stats.embeddings(), stats.vectors());
    }
  }
}
