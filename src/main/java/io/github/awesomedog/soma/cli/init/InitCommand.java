package io.github.awesomedog.soma.cli.init;

import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.init.WorkspaceInitializer;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.CliCommand;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

@Prototype
@Command(
    name = "init",
    description = {
      "Create a directory-local workspace.",
      "Rejected in the home directory; an existing valid workspace is left unchanged."
    })
public final class InitCommand extends CliCommand {

  @Inject WorkspaceInitializer workspaceInitializer;

  @Inject ActiveWorkspace workspace;

  @Override
  public Integer call() {
    var invocation = SomaCommand.invocation(spec);
    invocation.emit(workspaceInitializer.initialize(workspace.configFile()), OutputFormat.text);
    return 0;
  }
}
