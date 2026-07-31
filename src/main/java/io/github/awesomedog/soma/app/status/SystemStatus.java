package io.github.awesomedog.soma.app.status;

import static io.github.awesomedog.soma.app.common.Renderable.requireTextFormat;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.Renderable;
import io.github.awesomedog.soma.app.ports.ArtifactProvisioner;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ProjectStats;
import io.github.awesomedog.soma.app.project.ProjectView;
import io.github.awesomedog.soma.domain.document.VirtualPath;
import io.github.awesomedog.soma.support.PathSupport;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Singleton
public final class SystemStatus {

  private final ConfigStore configStore;
  private final ArtifactProvisioner artifactProvisioner;
  private final WorkspaceIndex workspaceIndex;

  public SystemStatus(
      ConfigStore configStore,
      ArtifactProvisioner artifactProvisioner,
      WorkspaceIndex workspaceIndex) {
    this.configStore = configStore;
    this.artifactProvisioner = artifactProvisioner;
    this.workspaceIndex = workspaceIndex;
  }

  public StatusResult status(
      String workspace,
      String source,
      Path configFile,
      Path databaseFile,
      Path logFile,
      Path lockFile) {
    var config = configStore.load(configFile);
    var artifactStates = artifactProvisioner.inspect();
    var projectNames = config.projects().stream().map(project -> project.name().value()).toList();
    var indexInspection = inspectIndexHealth(databaseFile, projectNames);
    var warnings = new ArrayList<>(indexInspection.warnings());
    var indexStatus = indexInspection.indexStatus();
    var projectStats = indexInspection.projectStats();
    var projectStatsAvailable = "ready".equals(indexStatus);
    var projectViews =
        config.projects().stream()
            .map(
                project ->
                    ProjectView.from(
                        project,
                        projectStatsAvailable
                            ? projectStats.getOrDefault(
                                project.name().value(), ProjectStats.empty())
                            : null))
            .toList();
    warnings.addAll(projectHealthWarnings(projectViews, projectStatsAvailable));
    var missingArtifactCount = artifactStates.stream().filter(state -> !state.available()).count();
    if (missingArtifactCount > 0) {
      warnings.add(
          missingArtifactCount + " managed artifact(s) are missing. Run `soma system pull`.");
    }
    return new StatusResult(
        workspace,
        source,
        PathSupport.toPortableString(configFile),
        Files.isRegularFile(configFile),
        PathSupport.toPortableString(databaseFile),
        Files.isRegularFile(databaseFile),
        readFileSize(databaseFile, warnings),
        PathSupport.toPortableString(logFile),
        PathSupport.toPortableString(lockFile),
        projectViews,
        artifactStates,
        indexStatus,
        warnings);
  }

  private IndexInspection inspectIndexHealth(Path databaseFile, List<String> projectNames) {
    if (!Files.isRegularFile(databaseFile)) {
      return unavailableIndex("missing", "Index database is missing. Run `soma sync`.");
    }
    if (!Files.isReadable(databaseFile)) {
      return unavailableIndex(
          "unreadable",
          "Index database is not readable. Check its filesystem permissions, then run `soma sync`.");
    }
    try {
      return new IndexInspection(
          "ready", workspaceIndex.projectStats(databaseFile, projectNames), List.of());
    } catch (AppException e) {
      var warning = e.error().message();
      if (e.error().remediation() != null && !e.error().remediation().isBlank()) {
        warning += " " + e.error().remediation();
      }
      return unavailableIndex("incompatible", warning);
    }
  }

  private static IndexInspection unavailableIndex(String status, String warning) {
    return new IndexInspection(status, Map.of(), List.of(warning));
  }

  private static List<String> projectHealthWarnings(
      List<ProjectView> projects, boolean projectStatsAvailable) {
    if (projects.isEmpty()) {
      return List.of("No projects are configured. Run `soma project add <root>`.");
    }
    if (!projectStatsAvailable) {
      return List.of();
    }
    var warnings = new ArrayList<String>();
    var totalDocumentCount = projects.stream().mapToLong(view -> view.stats().documents()).sum();
    if (totalDocumentCount == 0) {
      warnings.add("No indexed documents were found. Run `soma sync`.");
    }
    projects.forEach(project -> warnings.addAll(indexGapWarnings(project)));
    return List.copyOf(warnings);
  }

  private static List<String> indexGapWarnings(ProjectView project) {
    var warnings = new ArrayList<String>();
    var stats = project.stats();
    var prefix = "Project `" + project.name() + "` ";
    if (stats.pending() > 0) {
      warnings.add(
          prefix + "has " + stats.pending() + " pending extraction document(s). Run `soma sync`.");
    }
    if (stats.failed() > 0) {
      warnings.add(
          prefix
              + "has "
              + stats.failed()
              + " failed extraction document(s). Inspect the log, resolve the issue, then run "
              + "`soma sync`.");
    }
    if (stats.ready() > stats.lexical()) {
      warnings.add(prefix + "has ready documents missing lexical index rows. Run `soma sync`.");
    }
    if (stats.ready() > 0 && stats.chunks() == 0) {
      warnings.add(prefix + "has ready documents but no chunks. Run `soma sync`.");
    }
    if (stats.chunks() > stats.embeddings()) {
      warnings.add(prefix + "has chunks missing embeddings. Run `soma system embed`.");
    }
    if (stats.embeddings() != stats.vectors()) {
      warnings.add(prefix + "has embedding/vector count mismatch. Run `soma system embed`.");
    }
    return List.copyOf(warnings);
  }

  private static long readFileSize(Path file, List<String> warnings) {
    try {
      return Files.isRegularFile(file) ? Files.size(file) : 0;
    } catch (IOException e) {
      warnings.add("Could not read index database size: " + e.getMessage());
      return 0;
    }
  }

  private record IndexInspection(
      String indexStatus, Map<String, ProjectStats> projectStats, List<String> warnings) {}

  @Serdeable
  public record StatusResult(
      String workspace,
      String source,
      String configFile,
      boolean configExists,
      String databaseFile,
      boolean databaseExists,
      long databaseSize,
      String logFile,
      String lockFile,
      List<ProjectView> projects,
      List<ArtifactProvisioner.ArtifactState> artifacts,
      String indexStatus,
      List<String> warnings)
      implements Renderable {

    public StatusResult {
      projects = List.copyOf(projects);
      artifacts = List.copyOf(artifacts);
      warnings = List.copyOf(warnings);
    }

    @Override
    public void render(OutputFormat format, PrintWriter out) {
      requireTextFormat(format);
      var projectStatsAvailable = projects.stream().allMatch(project -> project.stats() != null);
      var projectTotals =
          projectStatsAvailable ? calculateProjectTotals(projects) : ProjectStats.empty();

      out.println("Soma Status");
      out.println("===========");
      out.println();
      renderWorkspace(out);
      renderIndex(out);
      renderProjects(out, projectStatsAvailable, projectTotals);
      renderArtifacts(out);

      printSection(out, "Health Warnings");
      if (warnings.isEmpty()) {
        out.println("  No health warnings");
        return;
      }
      warnings.forEach(warning -> out.println("  ! " + warning));
      out.println();
      renderTips(out, projectTotals);
    }

    private void renderWorkspace(PrintWriter out) {
      printSection(out, "Workspace");
      out.println("  Name:             " + workspace);
      out.println("  Source:           " + source);
      out.println("  Config:           " + configFile + (configExists ? "" : " (missing)"));
      out.println("  Index DB:         " + databaseFile);
      out.println("  Log:              " + logFile);
      out.println("  Lock:             " + lockFile);
      out.println();
    }

    private void renderIndex(PrintWriter out) {
      printSection(out, "Index");
      out.println("  Status:           " + indexStatus);
      out.println("  Database:         " + databaseFile + (databaseExists ? "" : " (missing)"));
      if (databaseExists) {
        out.println("  Size:             " + DisplayFormat.bytes(databaseSize));
      }
      out.println("  Compatible:       " + ("ready".equals(indexStatus) ? "yes" : "no"));
      out.println();
    }

    private void renderProjects(
        PrintWriter out, boolean projectStatsAvailable, ProjectStats projectTotals) {
      printSection(out, "Projects");
      if (projects.isEmpty()) {
        out.println("  No projects configured");
        out.println();
      } else {
        out.println("  Configured:       " + projects.size());
        if (projectStatsAvailable) {
          renderStats(out, "  ", projectTotals);
        }
      }
      for (var project : projects) {
        renderProject(out, project);
      }
      if (!projects.isEmpty()) {
        out.println();
      }
    }

    private static void renderProject(PrintWriter out, ProjectView project) {
      out.println();
      out.printf("  %-20s %s%n", project.name(), new VirtualPath(project.name(), ""));
      out.println("    Root:           " + project.root());
      out.println("    Default search scope: " + (project.defaultSearch() ? "yes" : "no"));
      if (project.stats() == null) {
        out.println("    Stats:          unknown (index database unavailable)");
        return;
      }
      renderStats(out, "    ", project.stats());
      if (project.stats().updatedAt() != null && !project.stats().updatedAt().isBlank()) {
        out.println("    Last updated:   " + project.stats().updatedAt());
      }
    }

    private static void renderStats(PrintWriter out, String indent, ProjectStats stats) {
      out.printf(
          "%-20s%d total, %d ready, %d pending, %d failed%n",
          indent + "Documents:", stats.documents(), stats.ready(), stats.pending(), stats.failed());
      out.printf(
          "%-20s%d lexical, %d chunks, %d embeddings, %d vectors%n",
          indent + "Index:", stats.lexical(), stats.chunks(), stats.embeddings(), stats.vectors());
    }

    private void renderArtifacts(PrintWriter out) {
      printSection(out, "Managed Artifacts");
      var artifactNameWidth =
          Math.max(
              18,
              artifacts.stream()
                  .mapToInt(artifact -> (artifact.id() + ":").length() + 1)
                  .max()
                  .orElse(18));
      for (var artifact : artifacts) {
        out.printf(
            String.format("  %%-%ds%%s (%%s)%%n", artifactNameWidth),
            artifact.id() + ":",
            artifact.available() ? "installed" : "missing",
            artifact.version());
        out.print("  " + " ".repeat(artifactNameWidth) + artifact.path());
        if (artifact.available()) {
          out.print(" [" + DisplayFormat.bytes(artifact.sizeBytes()) + "]");
        }
        out.println();
      }
      if (artifacts.isEmpty()) {
        out.println("  No managed artifacts for this platform");
      }
      out.println();
    }

    private void renderTips(PrintWriter out, ProjectStats projectTotals) {
      printSection(out, "Tips");
      if (projects.isEmpty()) {
        out.println("  * Add a project:        soma project add <root>");
      }
      if (!"ready".equals(indexStatus)) {
        out.println("  * Refresh the index:    soma sync");
      }
      if (projectTotals.pending() > 0) {
        out.println("  * Process pending docs: soma sync");
      }
      if (projectTotals.chunks() > projectTotals.embeddings()) {
        out.println("  * Generate embeddings: soma system embed");
      }
      if (artifacts.stream().anyMatch(artifact -> !artifact.available())) {
        out.println("  * Install artifacts:    soma system pull");
      }
    }

    private static ProjectStats calculateProjectTotals(List<ProjectView> projects) {
      return new ProjectStats(
          projects.stream().mapToLong(project -> project.stats().documents()).sum(),
          projects.stream().mapToLong(project -> project.stats().ready()).sum(),
          projects.stream().mapToLong(project -> project.stats().pending()).sum(),
          projects.stream().mapToLong(project -> project.stats().failed()).sum(),
          projects.stream().mapToLong(project -> project.stats().lexical()).sum(),
          projects.stream().mapToLong(project -> project.stats().chunks()).sum(),
          projects.stream().mapToLong(project -> project.stats().embeddings()).sum(),
          projects.stream().mapToLong(project -> project.stats().vectors()).sum(),
          null);
    }

    private static void printSection(PrintWriter out, String title) {
      out.println(title);
      out.println("-".repeat(title.length()));
    }
  }
}
