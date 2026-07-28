package io.github.awesomedog.soma.cli.common;

import io.github.awesomedog.soma.app.common.OutputFormat;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

public final class SearchCommonMixin {

  @Option(
      names = "--limit",
      paramLabel = "<num>",
      defaultValue = "20",
      description =
          "Maximum results, at least 1 (default: ${DEFAULT-VALUE}); conflicts with --no-limit.",
      scope = ScopeType.INHERIT)
  Integer limit;

  @Option(
      names = "--no-limit",
      arity = "0",
      fallbackValue = "true",
      description = "Return all matches; conflicts with --limit.",
      scope = ScopeType.INHERIT)
  boolean unlimited;

  @Option(
      names = "--full",
      arity = "0",
      fallbackValue = "true",
      description = "Return full bodies; incompatible with --format=paths.",
      scope = ScopeType.INHERIT)
  boolean fullDocuments;

  @Option(
      names = "--line-number",
      arity = "0",
      fallbackValue = "true",
      description = "Include line numbers; incompatible with --format=paths.",
      scope = ScopeType.INHERIT)
  boolean includeLineNumbers;

  @Option(
      names = {"-f", "--format"},
      paramLabel = "<format>",
      defaultValue = "text",
      description = {
        "Output format: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE}).",
        "The paths format returns paths only and conflicts with --full/--line-number."
      },
      scope = ScopeType.INHERIT)
  OutputFormat format;

  public Integer limit() {
    return limit;
  }

  public boolean unlimited() {
    return unlimited;
  }

  public boolean fullDocuments() {
    return fullDocuments;
  }

  public boolean includeLineNumbers() {
    return includeLineNumbers;
  }

  public OutputFormat format() {
    return format;
  }

  public void validate(CommandSpec command) {
    if (countMatchedOptions(command, "--limit", "--no-limit") > 1) {
      throw new CommandLine.ParameterException(
          command.commandLine(), "`--limit` and `--no-limit` are mutually exclusive.");
    }
    rejectRepeated(command, "--full");
    rejectRepeated(command, "--line-number");
    rejectRepeated(command, "--format");
  }

  private static void rejectRepeated(CommandSpec command, String option) {
    if (countMatchedOptions(command, option) > 1) {
      throw new CommandLine.ParameterException(
          command.commandLine(), "Option `" + option + "` may be specified only once.");
    }
  }

  private static int countMatchedOptions(CommandSpec command, String... options) {
    var parseResult = command.root().commandLine().getParseResult();
    var count = 0;
    while (parseResult != null) {
      for (var option : options) {
        if (parseResult.hasMatchedOption(option)) {
          count++;
        }
      }
      parseResult = parseResult.subcommand();
    }
    return count;
  }
}
