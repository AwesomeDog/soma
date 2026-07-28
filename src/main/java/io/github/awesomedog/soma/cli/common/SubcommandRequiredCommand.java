package io.github.awesomedog.soma.cli.common;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(synopsisSubcommandLabel = "COMMAND")
public abstract class SubcommandRequiredCommand extends CliCommand {

  @Override
  public Integer call() {
    throw new CommandLine.ParameterException(spec.commandLine(), "Missing required subcommand.");
  }
}
