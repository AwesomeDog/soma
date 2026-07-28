package io.github.awesomedog.soma.cli.common;

import java.util.Arrays;
import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Option;

public final class ProjectScopeMixin {

  @Option(
      names = {"-p", "--project"},
      paramLabel = "<name>",
      description = "Restrict to named projects; repeatable.",
      scope = CommandLine.ScopeType.INHERIT)
  String[] projects;

  public List<String> projects() {
    return projects == null ? List.of() : Arrays.stream(projects).distinct().toList();
  }
}
