package io.github.awesomedog.soma.cli.init;

import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.init.WorkspaceInitializer;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.CliCommand;
import io.github.awesomedog.soma.cli.project.ProjectCommand;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

@Prototype
@Command(
    name = "init",
    description = {
      "Create a directory-local workspace and add the current directory as a project.",
      "Rejected in the home directory or if the file already exists."
    })
public final class InitCommand extends CliCommand {

  @Inject WorkspaceInitializer workspaceInitializer;

  @Inject ActiveWorkspace workspace;

  @Inject ProjectCommand projectCommand;

  @Override
  public Integer call() {
    var invocation = SomaCommand.invocation(spec);
    invocation.emit(workspaceInitializer.initialize(workspace.configFile()), OutputFormat.text);
    return projectCommand.addCurrentDirectory(invocation);
  }
}
