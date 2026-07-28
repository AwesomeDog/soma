package io.github.awesomedog.soma.http;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.github.awesomedog.soma.infra.config.YamlConfigStore;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@MicronautTest(environments = "assets")
@Property(name = "micronaut.server.host", value = "127.0.0.1")
class AssetControllerTest {

  @Inject
  @Client("/")
  HttpClient client;

  @Inject AssetConfigStore configs;
  @Inject ActiveWorkspace workspace;
  @Inject WebController controller;

  @TempDir Path tempDir;

  @BeforeEach
  void selectWorkspace() {
    workspace.selectWorkspace("asset-test");
    configs.set(SomaConfig.empty());
  }

  @Test
  void servesUnindexedProjectFilesAsRawBinary() throws Exception {
    var bytes = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};
    var image = tempDir.resolve("images/pixel.png");
    Files.createDirectories(image.getParent());
    Files.write(image, bytes);
    configs.set(config("docs", tempDir, List.of("**/*.md")));

    var response =
        client
            .toBlocking()
            .exchange(HttpRequest.GET("/assets/docs/images/pixel.png"), byte[].class);

    assertThat(response.status().getCode()).isEqualTo(HttpStatus.OK.getCode());
    assertThat(response.getContentType()).contains(MediaType.IMAGE_PNG_TYPE);
    assertThat(response.body()).containsExactly(bytes);
  }

  @Test
  void rejectsTraversalAndAbsolutePathsOutsideProjectRoot() throws Exception {
    var root = tempDir.resolve("project");
    var outside = tempDir.resolve("outside.txt");
    Files.createDirectories(root);
    Files.writeString(outside, "private");
    configs.set(config("docs", root));

    for (var path : List.of("../outside.txt", outside.toAbsolutePath().toString())) {
      assertThat(controller.asset("docs", path).code()).isEqualTo(HttpStatus.NOT_FOUND.getCode());
    }
  }

  @Test
  void rejectsSymbolicLinksWhoseTargetsAreOutsideProjectRoot() throws Exception {
    var root = tempDir.resolve("project");
    var outside = tempDir.resolve("outside.txt");
    Files.createDirectories(root);
    Files.writeString(outside, "private");
    try {
      Files.createSymbolicLink(root.resolve("outside-link.txt"), outside);
    } catch (IOException | UnsupportedOperationException e) {
      Assumptions.abort("Symbolic links are not available: " + e.getMessage());
    }
    configs.set(config("docs", root));

    assertThat(controller.asset("docs", "outside-link.txt").code())
        .isEqualTo(HttpStatus.NOT_FOUND.getCode());
  }

  private static SomaConfig config(String name, Path root) {
    return config(name, root, List.of("**/*"));
  }

  private static SomaConfig config(String name, Path root, List<String> include) {
    return new SomaConfig(
        1,
        List.of(new ProjectConfig(new ProjectName(name), root, include, List.of(), true, true)),
        List.of());
  }
}

@Singleton
@Requires(env = "assets")
@Replaces(YamlConfigStore.class)
final class AssetConfigStore implements ConfigStore {

  private SomaConfig config = SomaConfig.empty();

  void set(SomaConfig config) {
    this.config = config;
  }

  @Override
  public SomaConfig load(Path configFile) {
    return config;
  }

  @Override
  public SomaConfig loadOrBackupResetForUpdate(Path configFile) {
    return config;
  }

  @Override
  public void save(Path configFile, SomaConfig config) {
    this.config = config;
  }
}
