package io.github.awesomedog.soma.cli.search;

import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.search.DocumentSearch;
import io.github.awesomedog.soma.app.search.DocumentSearch.Mode;
import io.github.awesomedog.soma.app.search.DocumentSearch.Request;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.CliCommand;
import io.github.awesomedog.soma.cli.common.HybridOptionsMixin;
import io.github.awesomedog.soma.cli.common.ProjectScopeMixin;
import io.github.awesomedog.soma.cli.common.SearchCommonMixin;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Prototype
@Command(
    name = "search",
    aliases = "s",
    description = {
      "Hybrid search using lexical, vector, and HyDE retrieval (default mode).",
      "Provide <query> or --lex/--vec/--hyde; omitting -p uses default search scope.",
      "Use explicit hybrid when the query equals a subcommand name."
    },
    addMethodSubcommands = true)
public final class SearchCommand extends CliCommand {

  @Parameters(
      arity = "0..1",
      paramLabel = "<query>",
      description = "Natural-language query expanded into lexical, vector, and HyDE inputs.")
  String query;

  @Mixin ProjectScopeMixin projectScope;

  @Mixin SearchCommonMixin common;

  @Mixin HybridOptionsMixin hybrid;

  @Inject DocumentSearch documentSearch;

  @Inject ActiveWorkspace workspace;

  @Override
  public Integer call() {
    common.validate(spec);
    return execute(
        Mode.hybrid,
        query,
        hybrid.lexicalInput(),
        hybrid.vectorInput(),
        hybrid.hydeInput(),
        hybrid.intent(),
        spec);
  }

  @Command(
      name = "hybrid",
      aliases = "h",
      description = {
        "Search using lexical, vector, and HyDE retrieval.",
        "Provide <query> or --lex/--vec/--hyde; omitting -p uses default search scope."
      })
  public int hybrid(
      @Parameters(
              arity = "0..1",
              paramLabel = "<query>",
              description =
                  "Natural-language query expanded into lexical, vector, and HyDE inputs.")
          String query,
      @Mixin HybridOptionsMixin options) {
    var command = validateExplicitSubcommand();
    return execute(
        Mode.hybrid,
        query,
        options.lexicalInput(),
        options.vectorInput(),
        options.hydeInput(),
        options.intent(),
        command);
  }

  @Command(
      name = "lexical",
      aliases = "l",
      description = {
        "Search by keywords, phrases, and exclusions.",
        "Omitting -p uses default search scope."
      })
  public int lexical(
      @Parameters(index = "0", paramLabel = "<query>", description = "Lexical query.")
          String query) {
    var command = validateExplicitSubcommand();
    return execute(Mode.lexical, query, null, null, null, null, command);
  }

  @Command(
      name = "vector",
      aliases = "v",
      description = {
        "Search by direct query embedding and semantic similarity.",
        "Omitting -p uses default search scope."
      })
  public int vector(
      @Parameters(
              index = "0",
              paramLabel = "<query>",
              description = "Natural-language text to embed directly.")
          String query,
      @Option(
              names = "--intent",
              paramLabel = "<text>",
              description = "Disambiguating background; not an extra retrieval input.")
          String intent) {
    var command = validateExplicitSubcommand();
    return execute(Mode.vector, query, null, null, null, intent, command);
  }

  private CommandSpec validateExplicitSubcommand() {
    var subcommand = leafSpec();
    if (query != null || hybrid.hasAnySearchOption()) {
      throw new CommandLine.ParameterException(
          subcommand.commandLine(),
          "Default hybrid inputs cannot be combined with an explicit search subcommand.");
    }
    common.validate(subcommand);
    return subcommand;
  }

  private int execute(
      Mode mode,
      String query,
      String lexicalInput,
      String vectorInput,
      String hydeInput,
      String intent,
      CommandSpec command) {
    var invocation = SomaCommand.invocation(command);
    var root = command.root().userObject();
    var verbose = root instanceof SomaCommand soma && soma.verbose();
    var request =
        new Request(
            mode,
            query,
            lexicalInput,
            vectorInput,
            hydeInput,
            intent,
            projectScope.projects(),
            common.limit(),
            common.unlimited(),
            common.fullDocuments(),
            common.includeLineNumbers(),
            common.format() == OutputFormat.paths,
            verbose);
    invocation.emit(
        documentSearch.search(
            workspace.configFile(),
            workspace.dbFile(),
            request,
            message -> invocation.progress(ProgressEvent.message(message))),
        common.format());
    return CommandLine.ExitCode.OK;
  }
}
