package io.github.awesomedog.soma.cli;

import io.github.awesomedog.soma.cli.context.ContextCommand;
import io.github.awesomedog.soma.cli.get.GetCommand;
import io.github.awesomedog.soma.cli.init.InitCommand;
import io.github.awesomedog.soma.cli.project.ProjectCommand;
import io.github.awesomedog.soma.cli.search.SearchCommand;
import io.github.awesomedog.soma.cli.server.ServerCommand;
import io.github.awesomedog.soma.cli.status.StatusCommand;
import io.github.awesomedog.soma.cli.sync.SyncCommand;
import io.github.awesomedog.soma.cli.system.SystemCommand;
import io.github.awesomedog.soma.exec.Invocation;
import java.io.IOException;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(
    name = "soma",
    description = {
      "Local Search Engine.",
      "",
      "Quickstart:",
      "  soma project add <root>   # register a folder",
      "  soma sync                 # index it",
      "  soma search \"<query>\"     # find it",
      "",
      "Workspace: -w > directory-local > SOMA_DEFAULT_WORKSPACE > main."
    },
    versionProvider = SomaCommand.BuildInfoVersionProvider.class,
    synopsisSubcommandLabel = "COMMAND",
    subcommands = {
      ProjectCommand.class,
      SyncCommand.class,
      SearchCommand.class,
      GetCommand.class,
      ServerCommand.class,
      ContextCommand.class,
      StatusCommand.class,
      InitCommand.class,
      SystemCommand.class
    })
public final class SomaCommand implements Callable<Integer> {

  private static final String BUILD_INFO_RESOURCE = "/META-INF/soma-build.properties";

  private final Invocation invocation;

  @Spec CommandSpec spec;

  String requestedWorkspaceName;
  boolean verbose;
  boolean noColor;

  @Option(
      names = {"-h", "--help"},
      usageHelp = true,
      arity = "0",
      fallbackValue = "true",
      scope = CommandLine.ScopeType.INHERIT,
      description = "Show this help message and exit.")
  boolean helpRequested;

  @Option(
      names = {"-V", "--version"},
      versionHelp = true,
      arity = "0",
      fallbackValue = "true",
      scope = CommandLine.ScopeType.INHERIT,
      description = "Print version information and exit.")
  boolean versionRequested;

  private boolean workspaceSpecified;
  private boolean verboseSpecified;
  private boolean noColorSpecified;

  public SomaCommand(Invocation invocation) {
    this.invocation = Objects.requireNonNull(invocation, "invocation");
    if (!invocation.capturesOutput()) {
      verbose = "1".equals(System.getenv("SOMA_VERBOSE"));
      noColor = "1".equals(System.getenv("NO_COLOR"));
    }
  }

  public static final class BuildInfoVersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() throws IOException {
      var buildInfo = new Properties();
      try (var input = SomaCommand.class.getResourceAsStream(BUILD_INFO_RESOURCE)) {
        if (input == null) {
          throw new IOException("Missing build information: " + BUILD_INFO_RESOURCE);
        }
        buildInfo.load(input);
      }
      return new String[] {formatVersion(buildInfo)};
    }
  }

  static String formatVersion(Properties buildInfo) {
    var version = buildInfo.getProperty("version", "unknown");
    if ("tag".equals(buildInfo.getProperty("ref-type"))) {
      var tag = buildInfo.getProperty("ref", "");
      if (!tag.matches("v(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)")) {
        throw new IllegalStateException("Release tag must match vMAJOR.MINOR.PATCH: " + tag);
      }
      version = tag.substring(1);
    }

    var commit = buildInfo.getProperty("commit", "");
    if (commit.matches("[0-9a-f]{40}")) {
      commit = commit.substring(0, 12);
    } else {
      commit = "unknown";
    }
    return "soma " + version + " (commit " + commit + ")";
  }

  public static Invocation invocation(CommandSpec spec) {
    if (spec != null && spec.root().userObject() instanceof SomaCommand soma) {
      return soma.invocation;
    }
    throw new IllegalStateException("not under SomaCommand tree - run via CommandRunner");
  }

  @Option(
      names = {"-w", "--workspace"},
      paramLabel = "<name>",
      description = "Select workspace.",
      scope = CommandLine.ScopeType.INHERIT)
  void setWorkspace(String requestedWorkspaceName) {
    rejectRepeated(workspaceSpecified, "--workspace");
    this.requestedWorkspaceName = requestedWorkspaceName;
    workspaceSpecified = true;
  }

  @Option(
      names = {"-v", "--verbose"},
      description = "Show candidate retrieval traces and error stack traces (env: SOMA_VERBOSE=1).",
      arity = "0",
      fallbackValue = "true",
      scope = CommandLine.ScopeType.INHERIT)
  void setVerbose(boolean verbose) {
    rejectRepeated(verboseSpecified, "--verbose");
    this.verbose = verbose;
    verboseSpecified = true;
  }

  @Option(
      names = "--no-color",
      description = "Disable ANSI color (env: NO_COLOR=1).",
      arity = "0",
      fallbackValue = "true",
      scope = CommandLine.ScopeType.INHERIT)
  void setNoColor(boolean noColor) {
    rejectRepeated(noColorSpecified, "--no-color");
    this.noColor = noColor;
    noColorSpecified = true;
  }

  private void rejectRepeated(boolean alreadySpecified, String option) {
    if (alreadySpecified) {
      throw new CommandLine.ParameterException(
          spec.commandLine(), "Option `" + option + "` may be specified only once.");
    }
  }

  public String requestedWorkspaceName() {
    return workspaceSpecified ? requestedWorkspaceName : null;
  }

  public boolean verbose() {
    return verbose;
  }

  public boolean noColor() {
    return noColor;
  }

  @Override
  public Integer call() {
    throw new CommandLine.ParameterException(spec.commandLine(), "Missing required command.");
  }
}
