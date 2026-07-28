package io.github.awesomedog.soma.cli.sync;

import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.github.awesomedog.soma.app.system.ArtifactProvisioning;
import io.github.awesomedog.soma.app.system.MaintenanceCycle;
import io.github.awesomedog.soma.cli.SomaCommand;
import io.github.awesomedog.soma.cli.common.CliCommand;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.micronaut.context.annotation.Prototype;
import jakarta.inject.Inject;
import picocli.CommandLine.Command;

@Prototype
@Command(
    name = "sync",
    description = {"Run pull, incremental scan, extract, embed, and clean, then exit."})
public final class SyncCommand extends CliCommand {

  @Inject WriteLock writeLock;

  @Inject ActiveWorkspace workspace;

  @Inject ArtifactProvisioning artifactProvisioning;

  @Inject MaintenanceCycle maintenanceCycle;

  @Override
  public Integer call() {
    var invocation = SomaCommand.invocation(spec);
    var pull = artifactProvisioning.pullAsOperationReport(progress(invocation));
    try (var token = writeLock.acquire(workspace.lockFile(), spec.qualifiedName())) {
      invocation.emit(
          maintenanceCycle.sync(
              workspace.configFile(), workspace.dbFile(), pull, token, progress(invocation)),
          OutputFormat.text);
      return 0;
    }
  }
}
