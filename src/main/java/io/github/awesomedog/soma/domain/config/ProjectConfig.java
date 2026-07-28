package io.github.awesomedog.soma.domain.config;

import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.support.PathSupport;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record ProjectConfig(
    ProjectName name,
    Path root,
    List<String> include,
    List<String> exclude,
    boolean defaultSearch,
    boolean ignoreFiles) {

  public ProjectConfig {
    Objects.requireNonNull(name, "name");
    root = normalizedAbsoluteRoot(root);
    include = validGlobs(include, "include", false);
    exclude = validGlobs(exclude, "exclude", true);
  }

  public ProjectConfig withName(ProjectName value) {
    return new ProjectConfig(value, root, include, exclude, defaultSearch, ignoreFiles);
  }

  public ProjectConfig withDefaultSearch(boolean value) {
    return new ProjectConfig(name, root, include, exclude, value, ignoreFiles);
  }

  private static List<String> validGlobs(List<String> values, String label, boolean emptyAllowed) {
    Objects.requireNonNull(values, label);
    if (!emptyAllowed && values.isEmpty()) {
      throw new IllegalArgumentException("Project include must not be empty");
    }
    var normalizedValues = new ArrayList<String>(values.size());
    for (var value : values) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("Project " + label + " glob must not be empty");
      }
      value = PathSupport.normalizePathSeparators(value);
      try {
        FileSystems.getDefault().getPathMatcher("glob:" + value);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Invalid project " + label + " glob: " + value, e);
      }
      normalizedValues.add(value);
    }
    return List.copyOf(normalizedValues);
  }

  private static Path normalizedAbsoluteRoot(Path root) {
    Objects.requireNonNull(root, "root");
    if (!root.isAbsolute()) {
      throw new IllegalArgumentException("Project root must be absolute");
    }
    return root.normalize();
  }
}
