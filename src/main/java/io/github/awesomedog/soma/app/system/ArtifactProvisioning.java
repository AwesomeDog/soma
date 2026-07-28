package io.github.awesomedog.soma.app.system;

import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.ports.ArtifactProvisioner;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

@Singleton
public final class ArtifactProvisioning {

  private final ArtifactProvisioner artifactProvisioner;

  public ArtifactProvisioning(ArtifactProvisioner artifactProvisioner) {
    this.artifactProvisioner = Objects.requireNonNull(artifactProvisioner, "artifactProvisioner");
  }

  public ArtifactProvisioner.PullReport pull(boolean refresh, Consumer<ProgressEvent> progress) {
    var events = progress == null ? (Consumer<ProgressEvent>) ignored -> {} : progress;
    return artifactProvisioner.pull(refresh, events);
  }

  public OperationReport pullAsOperationReport(Consumer<ProgressEvent> progress) {
    var report = pull(false, progress);
    return new OperationReport(
        "pull", report.syncText(), Map.of("artifacts", report.artifacts().size()));
  }

  public ArtifactProvisioner.ArchiveReport exportArchive(
      Path archive, Consumer<ProgressEvent> progress) {
    var events = progress == null ? (Consumer<ProgressEvent>) ignored -> {} : progress;
    return artifactProvisioner.exportArchive(archive, events);
  }

  public ArtifactProvisioner.ArchiveReport importArchive(
      Path archive, Consumer<ProgressEvent> progress) {
    var events = progress == null ? (Consumer<ProgressEvent>) ignored -> {} : progress;
    return artifactProvisioner.importArchive(archive, events);
  }
}
