package io.github.awesomedog.soma.domain.config;

import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.support.PathSupport;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record SomaConfig(int version, List<ProjectConfig> projects, List<ContextConfig> context) {

  public static final int CURRENT_VERSION = 1;

  public SomaConfig {
    if (version != CURRENT_VERSION) {
      throw new IllegalArgumentException("Unsupported config version: " + version);
    }
    projects = List.copyOf(Objects.requireNonNull(projects, "projects"));
    context = List.copyOf(Objects.requireNonNull(context, "context"));

    var projectNames = new HashSet<ProjectName>();
    for (var project : projects) {
      if (!projectNames.add(project.name())) {
        throw new DuplicateProjectNameException(project.name());
      }
    }

    var contextKeys = new HashSet<ContextKey>();
    for (var entry : context) {
      if (entry.project() != null && !projectNames.contains(entry.project())) {
        throw new IllegalArgumentException(
            "Context references unknown project: " + entry.project());
      }
      var key = new ContextKey(entry.project(), entry.path());
      if (!contextKeys.add(key)) {
        throw new IllegalArgumentException("Duplicate context: " + entry.path());
      }
    }
  }

  public static SomaConfig empty() {
    return new SomaConfig(CURRENT_VERSION, List.of(), List.of());
  }

  public List<ProjectName> defaultSearchProjects() {
    return projects.stream().filter(ProjectConfig::defaultSearch).map(ProjectConfig::name).toList();
  }

  public ProjectConfig projectByCanonicalName(String name) {
    return projects.stream()
        .filter(project -> project.name().value().equals(name))
        .findFirst()
        .orElse(null);
  }

  public ProjectRelativePath mapSourcePath(Path sourcePath) {
    var absolutePath =
        Objects.requireNonNull(sourcePath, "sourcePath").toAbsolutePath().normalize();
    for (var project : projects) {
      if (absolutePath.startsWith(project.root())) {
        return new ProjectRelativePath(
            project,
            PathSupport.normalizePathSeparators(
                project.root().relativize(absolutePath).toString()));
      }
    }
    return null;
  }

  public String effectiveContext(String project, String path) {
    var documentPath = "/" + (path == null ? "" : path);
    return context.stream()
        .filter(
            entry ->
                entry.project() == null
                    || project != null && project.equals(entry.project().value()))
        .filter(
            entry ->
                "/".equals(entry.path())
                    || documentPath.equals(entry.path())
                    || documentPath.startsWith(entry.path() + "/"))
        .sorted(
            Comparator.comparing((ContextConfig entry) -> entry.project() != null)
                .thenComparingInt(entry -> entry.path().length()))
        .map(ContextConfig::text)
        .collect(java.util.stream.Collectors.joining("\n\n"));
  }

  private record ContextKey(ProjectName project, String path) {}

  public static final class DuplicateProjectNameException extends IllegalArgumentException {

    private DuplicateProjectNameException(ProjectName projectName) {
      super("Duplicate project: " + projectName);
    }
  }

  public record ProjectRelativePath(ProjectConfig project, String relativePath) {}
}
