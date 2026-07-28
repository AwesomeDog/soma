package io.github.awesomedog.soma.infra.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.domain.config.ContextConfig;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.support.PathSupport;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YamlConfigStoreTest {

  @TempDir Path tempDir;

  @Test
  void roundTripsTheCanonicalConfigurationShape() throws Exception {
    var file = tempDir.resolve("main.yml");
    var store = new YamlConfigStore();
    var project =
        new ProjectConfig(
            new ProjectName("Docs"),
            tempDir.resolve("docs").toAbsolutePath(),
            List.of("**/*.md"),
            List.of("build/**"),
            false,
            true);
    var config =
        new SomaConfig(
            1, List.of(project), List.of(new ContextConfig(project.name(), "/api", "API docs")));

    store.save(file, config);

    assertThat(store.load(file)).isEqualTo(config);
    assertThat(Files.readString(file))
        .contains(
            "version: 1",
            project.root().toString().replace(File.separatorChar, '/'),
            "default-search: false",
            "ignore-files: true");
  }

  @Test
  void storesDirectoryLocalProjectRootsAsDotPathsAndResolvesThemAfterAMove() throws Exception {
    var originalWorkspace = Files.createDirectories(tempDir.resolve("original"));
    var nestedRoot = Files.createDirectories(originalWorkspace.resolve("docs"));
    var file = originalWorkspace.resolve(".soma/local.yml");
    var store = new YamlConfigStore();
    var config =
        new SomaConfig(
            1,
            List.of(project("workspace", originalWorkspace), project("docs", nestedRoot)),
            List.of());

    store.save(file, config);

    assertThat(Files.readString(file)).contains("root: .", "root: ./docs");
    assertThat(store.load(file)).isEqualTo(config);

    var movedWorkspace = tempDir.resolve("moved");
    Files.move(originalWorkspace, movedWorkspace);

    assertThat(store.load(movedWorkspace.resolve(".soma/local.yml")).projects())
        .extracting(ProjectConfig::root)
        .containsExactly(movedWorkspace, movedWorkspace.resolve("docs"));
  }

  @Test
  void rejectsNonDotAndEscapingRootsInDirectoryLocalConfiguration() throws Exception {
    var file = tempDir.resolve("workspace/.soma/local.yml");
    Files.createDirectories(file.getParent());
    for (var configuredRoot :
        List.of(
            "docs",
            "~/docs",
            PathSupport.normalizePathSeparators(tempDir.resolve("docs").toString()),
            "./../outside")) {
      Files.writeString(
          file,
          """
          version: 1
          projects:
            - name: docs
              root: %s
          context: []
          """
              .formatted(configuredRoot));

      assertThatThrownBy(() -> new YamlConfigStore().load(file))
          .isInstanceOfSatisfying(
              AppException.class,
              failure -> assertThat(failure.error().code()).isEqualTo(AppError.Code.CONFIG_ERROR));
    }
  }

  @Test
  void rejectsSavingAnExternalRootWithoutChangingDirectoryLocalConfiguration() throws Exception {
    var workspace = Files.createDirectories(tempDir.resolve("workspace"));
    var file = workspace.resolve(".soma/local.yml");
    var store = new YamlConfigStore();
    var original = new SomaConfig(1, List.of(project("workspace", workspace)), List.of());
    store.save(file, original);
    var originalYaml = Files.readString(file);

    var externalRoot = Files.createDirectories(tempDir.resolve("external"));
    var invalid = new SomaConfig(1, List.of(project("external", externalRoot)), List.of());

    assertThatThrownBy(() -> store.save(file, invalid))
        .isInstanceOfSatisfying(
            AppException.class,
            failure -> assertThat(failure.error().code()).isEqualTo(AppError.Code.INVALID_REQUEST));
    assertThat(Files.readString(file)).isEqualTo(originalYaml);
  }

  @Test
  void writesUnicodeAsUtf8WithoutYamlAliases() throws Exception {
    var file = tempDir.resolve("main.yml");
    var store = new YamlConfigStore();
    var unicodeText = "\u4e2d\u6587\u4e0a\u4e0b\u6587";
    var config =
        new SomaConfig(
            1,
            List.of(
                new ProjectConfig(
                    new ProjectName("docs"),
                    tempDir.resolve("docs").toAbsolutePath(),
                    List.of("**/*"),
                    List.of(),
                    true,
                    true),
                new ProjectConfig(
                    new ProjectName("notes"),
                    tempDir.resolve("notes").toAbsolutePath(),
                    List.of("**/*"),
                    List.of(),
                    true,
                    true)),
            List.of(new ContextConfig(null, "/", unicodeText)));

    store.save(file, config);

    var bytes = Files.readAllBytes(file);
    assertThat(bytes).containsSubsequence(unicodeText.getBytes(StandardCharsets.UTF_8));
    assertThat(new String(bytes, StandardCharsets.UTF_8))
        .contains("exclude: []", unicodeText)
        .doesNotContain("&id", "*id");
    assertThat(store.load(file)).isEqualTo(config);
  }

  @Test
  void expandsTildePrefixedProjectRootsWithEitherSeparatorWhenLoading() throws Exception {
    var file = tempDir.resolve("main.yml");
    for (var configuredRoot : List.of("~/docs", "~\\docs")) {
      Files.writeString(
          file,
          """
          version: 1
          projects:
            - name: docs
              root: %s
          context: []
          """
              .formatted(configuredRoot));

      var config = new YamlConfigStore().load(file);

      assertThat(config.projects().getFirst().root())
          .isEqualTo(
              Path.of(System.getProperty("user.home", "."))
                  .toAbsolutePath()
                  .normalize()
                  .resolve("docs"));
    }
  }

  @Test
  void rejectsRelativeProjectRootsInConfiguration() throws Exception {
    var file = tempDir.resolve("main.yml");
    Files.writeString(
        file,
        """
        version: 1
        projects:
          - name: docs
            root: docs
        context: []
        """);

    assertThatThrownBy(() -> new YamlConfigStore().load(file)).isInstanceOf(AppException.class);
  }

  @Test
  void reportsInvalidReadsAndBacksUpThenResetsThemOnlyForAWriter() throws Exception {
    var file = tempDir.resolve("main.yml");
    Files.writeString(file, "projects: [");
    var store = new YamlConfigStore();

    assertThatThrownBy(() -> store.load(file)).isInstanceOf(AppException.class);
    assertThat(store.loadOrBackupResetForUpdate(file)).isEqualTo(SomaConfig.empty());
    try (var files = Files.list(tempDir)) {
      assertThat(files.map(path -> path.getFileName().toString()))
          .anyMatch(name -> name.startsWith("main.yml.invalid-") && name.endsWith(".bak"));
    }
    assertThat(store.load(file)).isEqualTo(SomaConfig.empty());
  }

  @Test
  void keepsTheLastContextWithTheSameIdentity() throws Exception {
    var file = tempDir.resolve("main.yml");
    Files.writeString(
        file,
        """
        version: 1
        projects: []
        context:
          - path: /api
            text: first
          - path: /api
            text: last
        """);

    assertThat(new YamlConfigStore().load(file).context())
        .containsExactly(new ContextConfig(null, "/api", "last"));
  }

  private static ProjectConfig project(String name, Path root) {
    return new ProjectConfig(
        new ProjectName(name), root.toAbsolutePath(), List.of("**/*"), List.of(), true, true);
  }
}
