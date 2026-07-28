package io.github.awesomedog.soma.cli.get;

import static io.github.awesomedog.soma.app.common.Renderable.renderJson;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.Renderable;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.DocumentRead;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.CliCommand;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.document.DocumentPath;
import io.github.awesomedog.soma.domain.document.ExtractionStatus;
import io.github.awesomedog.soma.domain.document.VirtualPath;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.github.awesomedog.soma.exec.Invocation;
import io.github.awesomedog.soma.support.PathSupport;
import io.micronaut.context.annotation.Prototype;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Inject;
import java.io.PrintWriter;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Prototype
@Command(
    name = "get",
    description = {
      "Resolve targets and return indexed document content.",
      "Resolve targets as DocID, virtual/project path, then indexed filesystem path; globs are supported."
    })
public final class GetCommand extends CliCommand {

  private static final int PARTIAL_SUCCESS = 3;
  private static final Pattern DOC_ID =
      Pattern.compile("@[0-9a-f]{" + WorkspaceIndex.DOC_ID_LENGTH + "}");
  private static final Pattern FILE_SIZE = Pattern.compile("(?i)^(\\d+)(B|KB|KIB|MB|MIB|GB|GIB)?$");

  @Parameters(
      arity = "1..*",
      paramLabel = "<targets>",
      description = "Mixed @docid, soma://project/path, project/path, or filesystem paths.")
  List<String> targets;

  @Option(
      names = "--start-line",
      paramLabel = "<line>",
      description = "First line for every target, at least 1; conflicts with --format=paths.")
  Integer startLine;

  @Option(
      names = "--max-lines",
      paramLabel = "<num>",
      description = "Maximum lines per target, at least 1; conflicts with --format=paths.")
  Integer maxLines;

  @Option(
      names = "--line-number",
      arity = "0",
      fallbackValue = "true",
      description = "Include line numbers; conflicts with --format=paths.")
  boolean lineNumber;

  @Option(
      names = "--max-size",
      paramLabel = "<filesize>",
      defaultValue = "10240",
      description = {
        "Skip larger document content without failing (default: ${DEFAULT-VALUE} bytes).",
        "Bare values are bytes; units: B, KB, KiB, MB, MiB, GB, GiB."
      })
  String maxSize;

  @Option(
      names = {"-f", "--format"},
      paramLabel = "<format>",
      defaultValue = "text",
      description = {
        "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
        "The paths format returns resolved Virtual Paths only and conflicts with line options."
      })
  OutputFormat format;

  @Inject ConfigStore configStore;

  @Inject WorkspaceIndex workspaceIndex;

  @Inject ActiveWorkspace workspace;

  private SomaConfig config;
  private long maximumBytes;
  private Invocation invocation;
  private List<Item> items;
  private int successes;
  private int failures;

  @Override
  public Integer call() {
    validate();
    maximumBytes = parseFileSize(maxSize);
    config = configStore.load(workspace.configFile());
    invocation = SomaCommand.invocation(spec);
    items = new ArrayList<>();
    targets.forEach(this::resolveTarget);
    invocation.emit(new Result(List.copyOf(items)), format);
    return failures == 0
        ? CommandLine.ExitCode.OK
        : successes > 0 ? PARTIAL_SUCCESS : CommandLine.ExitCode.SOFTWARE;
  }

  private void resolveTarget(String target) {
    if (target == null || target.isBlank()) {
      fail("Target must not be blank.");
      return;
    }
    if (target.startsWith("@")) {
      resolveDocId(target);
      return;
    }
    var virtualInput = VirtualPath.parseInput(target);
    if (virtualInput.explicit()) {
      resolveVirtualPath(target, virtualInput);
      return;
    }
    if (virtualInput.hasPathSeparator()
        && !virtualInput.project().isEmpty()
        && config.projectByCanonicalName(virtualInput.project()) != null) {
      resolveVirtualPath(target, virtualInput);
      return;
    }
    resolveFilesystemPath(target);
  }

  private void resolveDocId(String target) {
    if (!DOC_ID.matcher(target).matches()) {
      fail("DocID must look like @a1b2c3");
      return;
    }
    var documents =
        workspaceIndex.findReadyDocumentsByDocId(workspace.dbFile(), target, bodyReadLimit());
    if (documents.isEmpty()) {
      fail("Document not found: " + target);
      return;
    }
    if (documents.size() > 1) {
      var suggestions = documents.stream().map(document -> "  " + document.virtualPath()).toList();
      fail(
          "Ambiguous DocID %s. Use a full Virtual Path:%n%s"
              .formatted(target, String.join(System.lineSeparator(), suggestions)));
      return;
    }
    addDocument(documents.getFirst());
  }

  private void resolveVirtualPath(String target, VirtualPath.Input input) {
    if (input.project().isEmpty() || !input.hasPathSeparator() || input.path().isEmpty()) {
      fail("Expected soma://project/path or project/path");
      return;
    }
    var project = config.projectByCanonicalName(input.project());
    if (project == null) {
      fail("Project not found: " + input.project());
      return;
    }

    final String documentPath;
    try {
      documentPath = new DocumentPath(input.path()).value();
    } catch (IllegalArgumentException e) {
      fail(e.getMessage());
      return;
    }
    if (!containsGlob(documentPath)) {
      readDocument(project, documentPath);
      return;
    }

    final var globSyntax = "glob:" + documentPath;
    final java.nio.file.PathMatcher glob;
    try {
      glob = FileSystems.getDefault().getPathMatcher(globSyntax);
    } catch (IllegalArgumentException e) {
      fail("Invalid Virtual Path glob: " + target + ": " + e.getMessage());
      return;
    }
    var matches = 0;
    for (var document :
        workspaceIndex.listDocuments(workspace.dbFile(), project.name().value(), "")) {
      if (glob.matches(Path.of(document.path()))) {
        matches++;
        readDocument(project, document.path());
      }
    }
    if (matches == 0) {
      fail("No documents matched: " + target);
    }
  }

  private void readDocument(ProjectConfig project, String documentPath) {
    workspaceIndex
        .findDocument(workspace.dbFile(), project.name().value(), documentPath, bodyReadLimit())
        .ifPresentOrElse(
            this::addDocument,
            () ->
                fail(
                    "Document not found: "
                        + new VirtualPath(project.name().value(), documentPath)));
  }

  private void resolveFilesystemPath(String target) {
    final Path path;
    try {
      path = PathSupport.resolveUserPath(target);
    } catch (RuntimeException e) {
      fail("Invalid file path: " + target + ": " + e.getMessage());
      return;
    }

    var projectRelativePath = config.mapSourcePath(path);
    if (projectRelativePath == null || projectRelativePath.relativePath().isEmpty()) {
      fail("Document not found in index: " + PathSupport.toPortableString(path));
      return;
    }
    readDocument(projectRelativePath.project(), projectRelativePath.relativePath());
  }

  private void addDocument(DocumentRead document) {
    if (document.status() != ExtractionStatus.READY) {
      fail(
          "Indexed document is not ready: %s (%s). Run `soma sync` after correcting extraction failures."
              .formatted(
                  document.virtualPath(), document.status().name().toLowerCase(Locale.ROOT)));
      return;
    }
    if (format == OutputFormat.paths) {
      add(new Item(document.virtualPath(), "", ""));
      return;
    }
    if (document.bodySizeBytes() > maximumBytes) {
      skip(tooLarge(document.virtualPath(), document.bodySizeBytes()));
      return;
    }
    var body = Objects.requireNonNull(document.body(), "document body");
    add(
        new Item(
            document.virtualPath(),
            selectLines(body),
            config.effectiveContext(document.project(), document.path())));
  }

  private String selectLines(String body) {
    if (startLine == null && maxLines == null && !lineNumber) {
      return body;
    }
    var lines = body.split("\\R", -1);
    var first = Math.min(lines.length, startLine == null ? 0 : startLine - 1);
    var last = maxLines == null ? lines.length : first + Math.min(lines.length - first, maxLines);
    var selected = new ArrayList<String>(Math.max(0, last - first));
    selected.addAll(Arrays.asList(lines).subList(first, last));
    if (selected.isEmpty()) {
      return "";
    }
    var text =
        lineNumber ? DisplayFormat.lineNumbers(selected, first + 1) : String.join("\n", selected);
    return text + "\n";
  }

  private void validate() {
    if (format == OutputFormat.paths && (startLine != null || maxLines != null || lineNumber)) {
      throw new CommandLine.ParameterException(
          spec.commandLine(),
          "`--format=paths` cannot be used with `--start-line`, `--max-lines`, or `--line-number`.");
    }
    if (startLine != null && startLine < 1) {
      throw new CommandLine.ParameterException(
          spec.commandLine(), "`--start-line` must be at least 1.");
    }
    if (maxLines != null && maxLines < 1) {
      throw new CommandLine.ParameterException(
          spec.commandLine(), "`--max-lines` must be at least 1.");
    }
  }

  private long parseFileSize(String input) {
    var match = FILE_SIZE.matcher(input == null ? "" : input.strip());
    if (!match.matches()) {
      throw new CommandLine.ParameterException(
          spec.commandLine(), "`--max-size` must be bytes or a value such as 10KB or 2MiB.");
    }
    try {
      var amount = Long.parseLong(match.group(1));
      if (amount < 1) {
        throw new CommandLine.ParameterException(
            spec.commandLine(), "`--max-size` must be at least 1 byte.");
      }
      var unit = match.group(2) == null ? "B" : match.group(2).toUpperCase(Locale.ROOT);
      var multiplier =
          switch (unit) {
            case "B" -> 1L;
            case "KB" -> 1_000L;
            case "KIB" -> 1_024L;
            case "MB" -> 1_000_000L;
            case "MIB" -> 1_048_576L;
            case "GB" -> 1_000_000_000L;
            case "GIB" -> 1_073_741_824L;
            default -> throw new IllegalStateException("unhandled file-size unit");
          };
      return Math.multiplyExact(amount, multiplier);
    } catch (ArithmeticException | NumberFormatException e) {
      throw new CommandLine.ParameterException(spec.commandLine(), "`--max-size` is too large.");
    }
  }

  private String tooLarge(String path, long size) {
    return "Skipped %s: content is too large (%d > %d bytes). Use a higher --max-size with `soma get`."
        .formatted(path, size, maximumBytes);
  }

  private long bodyReadLimit() {
    return format == OutputFormat.paths ? 0 : maximumBytes;
  }

  private static boolean containsGlob(String path) {
    return path.indexOf('*') >= 0
        || path.indexOf('?') >= 0
        || path.indexOf('{') >= 0
        || path.indexOf('[') >= 0;
  }

  private void add(Item item) {
    items.add(item);
    successes++;
  }

  private void skip(String warning) {
    successes++;
    invocation.err().println(warning);
  }

  private void fail(String diagnostic) {
    failures++;
    invocation.err().println(diagnostic);
  }

  @Serdeable
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Result(List<Item> items) implements Renderable {
    @Override
    public void render(OutputFormat format, PrintWriter out) {
      switch (format) {
        case text -> renderDocuments(out, false);
        case json -> renderJson(this, out, "Could not render get results as JSON.");
        case csv -> renderCsv(out);
        case md -> renderDocuments(out, true);
        case paths -> items.forEach(item -> out.println(item.virtualPath()));
      }
    }

    private void renderDocuments(PrintWriter out, boolean markdown) {
      for (var item : items) {
        if (markdown) {
          out.println("### " + item.virtualPath());
          out.println();
        } else if (items.size() > 1) {
          out.println("==> " + item.virtualPath() + " <==");
        }
        if (!item.context().isBlank()) {
          out.println("Context: " + item.context());
          out.println();
        }
        if (markdown) {
          out.println("```text");
        }
        out.print(item.body());
        if (!item.body().endsWith("\n")) {
          out.println();
        }
        if (markdown) {
          out.println("```");
          out.println();
        }
      }
    }

    private void renderCsv(PrintWriter out) {
      out.println("virtualPath,context,body");
      for (var item : items) {
        out.printf(
            "%s,%s,%s%n",
            DisplayFormat.csvCell(item.virtualPath()),
            DisplayFormat.csvCell(item.context()),
            DisplayFormat.csvCell(item.body()));
      }
    }
  }

  @Serdeable
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record Item(String virtualPath, String body, String context) {}
}
