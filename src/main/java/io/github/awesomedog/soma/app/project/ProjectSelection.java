package io.github.awesomedog.soma.app.project;

import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static io.github.awesomedog.soma.app.common.AppError.Code.NOT_FOUND;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class ProjectSelection {

  private ProjectSelection() {}

  public static List<ProjectName> resolveExplicitProjectNames(
      SomaConfig config, List<String> requestedNames) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(requestedNames, "requestedNames");
    var selectedNames = new LinkedHashSet<ProjectName>();
    for (var requestedName : requestedNames) {
      final ProjectName projectName;
      try {
        projectName = new ProjectName(requestedName);
      } catch (IllegalArgumentException e) {
        throw new AppException(
            INVALID_REQUEST,
            "Invalid project name: " + requestedName,
            "Run `soma project list` to see configured projects.",
            e);
      }
      var project = config.projectByCanonicalName(projectName.value());
      if (project == null) {
        throw new AppException(
            NOT_FOUND,
            "Project not found: " + projectName,
            "Run `soma project list` to see configured projects.");
      }
      selectedNames.add(project.name());
    }
    return List.copyOf(selectedNames);
  }
}
