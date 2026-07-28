package io.github.awesomedog.soma.app.ports;

import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.common.Renderable;
import io.micronaut.serde.annotation.Serdeable;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public interface ArtifactProvisioner {

  default PullReport pull(boolean refresh) {
    return pull(refresh, ignored -> {});
  }

  PullReport pull(boolean refresh, Consumer<ProgressEvent> progressReporter);

  ArchiveReport exportArchive(Path archive, Consumer<ProgressEvent> progressReporter);

  ArchiveReport importArchive(Path archive, Consumer<ProgressEvent> progressReporter);

  List<ArtifactState> inspect();

  @Serdeable
  record PullReport(List<Entry> artifacts, boolean fastLocalCheck) implements Renderable {

    public PullReport {
      artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
    }

    @Override
    public void render(OutputFormat format, PrintWriter out) {
      if (format != OutputFormat.text) {
        throw new IllegalArgumentException("Artifact pull results support only text output.");
      }
      out.println(text());
    }

    public String syncText() {
      if (fastLocalCheck) {
        return "Managed artifacts are available (fast local check).";
      }
      return artifacts.isEmpty() ? "Managed artifacts refreshed\n  (none)" : text();
    }

    private String text() {
      var text =
          new StringBuilder(
              fastLocalCheck
                  ? "Managed artifacts already available"
                  : "Managed artifacts refreshed");
      for (var artifact : artifacts) {
        var status =
            fastLocalCheck ? "fast local check" : artifact.updated() ? "downloaded" : "verified";
        text.append('\n')
            .append("  - ")
            .append(artifact.url())
            .append(" -> ")
            .append(artifact.path())
            .append(" (")
            .append(DisplayFormat.bytes(artifact.sizeBytes()))
            .append(", ")
            .append(status)
            .append(')');
      }
      return text.toString();
    }
  }

  @Serdeable
  record Entry(
      String id, String version, String url, String path, long sizeBytes, boolean updated) {}

  @Serdeable
  record ArtifactState(String id, String version, String path, boolean available, long sizeBytes) {}

  @Serdeable
  record ArchiveReport(String summary, String archive, int packages) implements Renderable {

    public ArchiveReport {
      Objects.requireNonNull(summary, "summary");
      Objects.requireNonNull(archive, "archive");
      if (packages < 0) {
        throw new IllegalArgumentException("Package count must not be negative");
      }
    }

    @Override
    public void render(OutputFormat format, PrintWriter out) {
      Renderable.requireTextFormat(format);
      out.printf("%s: %s (%d packages).%n", summary, archive, packages);
    }
  }
}
