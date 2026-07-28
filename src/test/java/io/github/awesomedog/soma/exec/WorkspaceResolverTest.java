package io.github.awesomedog.soma.exec;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceResolverTest {

  private static final int LOCAL_WORKSPACE_DIGEST_BYTES = 8;

  @TempDir Path tempDir;

  @Test
  void resolvesFlagThenNearestLocalThenDefaultEnvironmentThenMain() throws Exception {
    var project = tempDir.resolve("project");
    var nested = project.resolve("src/module");
    Files.createDirectories(project.resolve(".soma"));
    Files.createDirectories(nested.resolve(".soma"));
    Files.writeString(project.resolve(".soma/local.yml"), "version: 1\n");

    var flag =
        resolver(environment("SOMA_DEFAULT_WORKSPACE", "environment"), nested)
            .resolveWorkspace(" 我的 工作区 ");
    var local =
        resolver(environment("SOMA_DEFAULT_WORKSPACE", "environment"), nested)
            .resolveWorkspace(null);
    var fromEnvironment =
        resolver(environment("SOMA_DEFAULT_WORKSPACE", "environment"), tempDir.resolve("outside"))
            .resolveWorkspace(null);
    var main = resolver(environment(), tempDir.resolve("outside")).resolveWorkspace(null);

    assertThat(flag.workspaceName()).isEqualTo("我的-工作区");
    assertThat(flag.selectionSource()).isEqualTo(ActiveWorkspace.Source.FLAG);
    assertThat(flag.configFile()).isEqualTo(tempDir.resolve("xdg-config/soma/我的-工作区.yml"));
    assertThat(flag.dbFile()).isEqualTo(tempDir.resolve("xdg-state/soma/我的-工作区.sqlite"));
    assertThat(flag.logFile()).isEqualTo(tempDir.resolve("xdg-state/soma/logs/我的-工作区.log"));
    assertThat(flag.lockFile()).isEqualTo(tempDir.resolve("xdg-state/soma/locks/我的-工作区.lock"));

    assertThat(local.workspaceName()).isEqualTo("local-" + shortSha256(project));
    assertThat(local.selectionSource()).isEqualTo(ActiveWorkspace.Source.DIRECTORY_LOCAL);
    assertThat(local.configFile()).isEqualTo(project.resolve(".soma/local.yml"));
    assertThat(local.dbFile()).isEqualTo(project.resolve(".soma/local.sqlite"));
    assertThat(local.logFile())
        .isEqualTo(tempDir.resolve("xdg-state/soma/logs/local-" + shortSha256(project) + ".log"));
    assertThat(local.lockFile())
        .isEqualTo(tempDir.resolve("xdg-state/soma/locks/local-" + shortSha256(project) + ".lock"));

    assertThat(fromEnvironment.workspaceName()).isEqualTo("environment");
    assertThat(fromEnvironment.selectionSource()).isEqualTo(ActiveWorkspace.Source.ENVIRONMENT);

    assertThat(main.workspaceName()).isEqualTo("main");
    assertThat(main.selectionSource()).isEqualTo(ActiveWorkspace.Source.DEFAULT);
    assertThat(main.configFile()).isEqualTo(tempDir.resolve("xdg-config/soma/main.yml"));
  }

  private WorkspaceResolver resolver(Map<String, String> environment, Path workingDirectory) {
    return new WorkspaceResolver(environment, workingDirectory, tempDir.resolve("home"));
  }

  private Map<String, String> environment(String... values) {
    var environment = new HashMap<String, String>();
    environment.put("XDG_CONFIG_HOME", tempDir.resolve("xdg-config").toString());
    environment.put("XDG_STATE_HOME", tempDir.resolve("xdg-state").toString());
    environment.put("XDG_DATA_HOME", tempDir.resolve("xdg-data").toString());
    for (var index = 0; index < values.length; index += 2) {
      environment.put(values[index], values[index + 1]);
    }
    return environment;
  }

  private static String shortSha256(Path path) throws Exception {
    var digest =
        MessageDigest.getInstance("SHA-256")
            .digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(digest, 0, LOCAL_WORKSPACE_DIGEST_BYTES);
  }
}
