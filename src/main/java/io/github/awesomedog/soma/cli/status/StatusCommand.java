package io.github.awesomedog.soma.cli.status;

import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.status.SystemStatus;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.CliCommand;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

@Prototype
@Command(
    name = "status",
    description = "Show workspace paths, project statistics, warnings, and model information.")
public final class StatusCommand extends CliCommand {

  @Inject SystemStatus systemStatus;

  @Inject ActiveWorkspace workspace;

  @Override
  public Integer call() {
    SomaCommand.invocation(spec)
        .emit(
            systemStatus.status(
                workspace.workspaceName(),
                workspace.selectionSource().name().toLowerCase(java.util.Locale.ROOT),
                workspace.configFile(),
                workspace.dbFile(),
                workspace.logFile(),
                workspace.lockFile()),
            OutputFormat.text);
    return 0;
  }
}
