package io.github.awesomedog.soma.domain.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.awesomedog.soma.domain.project.ProjectName;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SomaConfigTest {

  @Test
  void resolvesGlobalThenProjectContextFromShorterToLongerPrefixes() {
    var docs = project("docs");
    var notes = project("notes");
    var config =
        new SomaConfig(
            1,
            List.of(docs, notes),
            List.of(
                context(null, "/", "global root"),
                context(null, "/api", "global api"),
                context(docs.name(), "/", "docs root"),
                context(docs.name(), "/api", "docs api"),
                context(docs.name(), "/api/internal", "docs internal"),
                context(notes.name(), "/", "notes root")));

    assertThat(config.effectiveContext("docs", "api/internal/guide.md"))
        .isEqualTo(
            """
            global root

            global api

            docs root

            docs api

            docs internal""");
    assertThat(config.effectiveContext("docs", "apix/guide.md"))
        .isEqualTo("global root\n\ndocs root");
    assertThat(config.effectiveContext("docs", null)).isEqualTo("global root\n\ndocs root");
  }

  @Test
  void rejectsContextValuesThatCannotMatchUsefulDocumentPaths() {
    assertThatThrownBy(() -> context(null, "relative", "text"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> context(null, "/api/", "text"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> context(null, "/api", " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalizesWindowsGlobSeparatorsBeforeConfigurationIsStored() {
    var project =
        new ProjectConfig(
            new ProjectName("docs"),
            Path.of(System.getProperty("java.io.tmpdir"), "soma-globs").toAbsolutePath(),
            List.of("guides\\api.md"),
            List.of(),
            true,
            true);

    assertThat(project.include())
        .containsExactly(File.separatorChar == '\\' ? "guides/api.md" : "guides\\api.md");
  }

  private static ProjectConfig project(String name) {
    return new ProjectConfig(
        new ProjectName(name),
        Path.of(System.getProperty("java.io.tmpdir"), "soma-context-" + name).toAbsolutePath(),
        List.of("**/*"),
        List.of(),
        true,
        true);
  }

  private static ContextConfig context(ProjectName project, String path, String text) {
    return new ContextConfig(project, path, text);
  }
}
