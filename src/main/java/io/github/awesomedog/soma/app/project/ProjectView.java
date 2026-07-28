package io.github.awesomedog.soma.app.project;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex.ProjectStats;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.support.PathSupport;
import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProjectView(
    String name,
    String root,
    List<String> include,
    List<String> exclude,
    boolean defaultSearch,
    boolean ignoreFiles,
    ProjectStats stats) {

  public static ProjectView from(ProjectConfig project, ProjectStats stats) {
    return new ProjectView(
        project.name().value(),
        PathSupport.toPortableString(project.root()),
        project.include(),
        project.exclude(),
        project.defaultSearch(),
        project.ignoreFiles(),
        stats);
  }
}
