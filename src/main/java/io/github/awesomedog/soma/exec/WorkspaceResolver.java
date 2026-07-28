package io.github.awesomedog.soma.exec;

import static io.github.awesomedog.soma.app.common.AppError.Code.CONFIG_ERROR;
import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.domain.naming.NameCanonicalizer;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.PathSupport;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

@Singleton
final class WorkspaceResolver {

  private static final String DEFAULT_WORKSPACE_NAME = "main";
  private static final String SOMA_DIRECTORY = ".soma";
  private static final String LOCAL_CONFIG = "local.yml";
  private static final String LOCAL_DATABASE = "local.sqlite";
  private static final int LOCAL_WORKSPACE_DIGEST_BYTES = 8;

  private final Map<String, String> environmentVariables;
  private final Path workingDirectory;
  private final Path userHomeDirectory;

  @Inject
  WorkspaceResolver() {
    this(
        System.getenv(), Path.of("").toAbsolutePath().normalize(), PathSupport.userHomeDirectory());
  }

  WorkspaceResolver(Map<String, String> environment, Path workingDirectory, Path homeDirectory) {
    this.environmentVariables = Map.copyOf(Objects.requireNonNull(environment, "environment"));
    this.workingDirectory =
        absolutePath(Objects.requireNonNull(workingDirectory, "workingDirectory"));
    this.userHomeDirectory = absolutePath(Objects.requireNonNull(homeDirectory, "homeDirectory"));
  }

  WorkspaceSelection resolveWorkspace(String requestedWorkspaceName) {
    var configHome = configHome();
    var stateHome = stateHome();

    if (requestedWorkspaceName != null) {
      return namedWorkspace(
          canonicalWorkspaceName(requestedWorkspaceName),
          ActiveWorkspace.Source.FLAG,
          configHome,
          stateHome);
    }

    var localRoot = findLocalRoot();
    if (localRoot != null) {
      return directoryLocalWorkspace(localRoot, stateHome);
    }

    var defaultWorkspaceEnvironment = environmentVariables.get("SOMA_DEFAULT_WORKSPACE");
    if (hasText(defaultWorkspaceEnvironment)) {
      return namedWorkspace(
          canonicalWorkspaceName(defaultWorkspaceEnvironment),
          ActiveWorkspace.Source.ENVIRONMENT,
          configHome,
          stateHome);
    }

    return namedWorkspace(
        DEFAULT_WORKSPACE_NAME, ActiveWorkspace.Source.DEFAULT, configHome, stateHome);
  }

  WorkspaceSelection resolveDirectoryLocalWorkspaceForInit() {
    return directoryLocalWorkspace(workingDirectory, stateHome());
  }

  private WorkspaceSelection namedWorkspace(
      String workspaceName,
      ActiveWorkspace.Source selectionSource,
      Path configHome,
      Path stateHome) {
    return new WorkspaceSelection(
        workspaceName,
        selectionSource,
        configHome.resolve(workspaceName + ".yml"),
        stateHome.resolve(workspaceName + ".sqlite"),
        logFile(stateHome, workspaceName),
        lockFile(stateHome, workspaceName));
  }

  private WorkspaceSelection directoryLocalWorkspace(Path workspaceRoot, Path stateHome) {
    var normalizedRoot = absolutePath(workspaceRoot);
    var localWorkspaceName = "local-" + shortSha256Hex(normalizedRoot.toString());
    var localDirectory = normalizedRoot.resolve(SOMA_DIRECTORY);
    return new WorkspaceSelection(
        localWorkspaceName,
        ActiveWorkspace.Source.DIRECTORY_LOCAL,
        localDirectory.resolve(LOCAL_CONFIG),
        localDirectory.resolve(LOCAL_DATABASE),
        logFile(stateHome, localWorkspaceName),
        lockFile(stateHome, localWorkspaceName));
  }

  private Path findLocalRoot() {
    for (var cursor = workingDirectory; cursor != null; cursor = cursor.getParent()) {
      var somaDirectory = cursor.resolve(SOMA_DIRECTORY);
      var localConfigFile = somaDirectory.resolve(LOCAL_CONFIG);
      if (!Files.exists(localConfigFile, NOFOLLOW_LINKS)) {
        continue;
      }
      if (!Files.isRegularFile(localConfigFile) || !Files.isReadable(localConfigFile)) {
        throw new AppException(
            CONFIG_ERROR,
            "Workspace config is not readable: " + localConfigFile,
            "Fix the file permissions and retry.");
      }
      return cursor;
    }
    return null;
  }

  private Path configHome() {
    return xdgHome("XDG_CONFIG_HOME", ".config").resolve("soma");
  }

  private Path stateHome() {
    return xdgHome("XDG_STATE_HOME", ".local/state").resolve("soma");
  }

  private Path xdgHome(String environmentVariable, String fallback) {
    return PathSupport.xdgHome(
        environmentVariables, environmentVariable, userHomeDirectory, fallback);
  }

  private static Path logFile(Path stateHome, String workspaceName) {
    return stateHome.resolve("logs").resolve(workspaceName + ".log");
  }

  private static Path lockFile(Path stateHome, String workspaceName) {
    return stateHome.resolve("locks").resolve(workspaceName + ".lock");
  }

  private Path absolutePath(Path path) {
    return (path.isAbsolute() ? path : Path.of("").toAbsolutePath().resolve(path)).normalize();
  }

  private static String canonicalWorkspaceName(String input) {
    var canonical = NameCanonicalizer.canonicalize(input);
    if (canonical.isEmpty()) {
      throw new AppException(
          INVALID_REQUEST,
          "Workspace name is empty after canonicalization.",
          "Choose a workspace name containing letters or numbers.");
    }
    return canonical;
  }

  private static String shortSha256Hex(String value) {
    return Hashing.sha256HexUtf8(value).substring(0, LOCAL_WORKSPACE_DIGEST_BYTES * 2);
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}

record WorkspaceSelection(
    String workspaceName,
    ActiveWorkspace.Source selectionSource,
    Path configFile,
    Path dbFile,
    Path logFile,
    Path lockFile) {

  WorkspaceSelection {
    Objects.requireNonNull(workspaceName, "workspaceName");
    Objects.requireNonNull(selectionSource, "selectionSource");
    configFile = normalized(configFile, "configFile");
    dbFile = normalized(dbFile, "dbFile");
    logFile = normalized(logFile, "logFile");
    lockFile = normalized(lockFile, "lockFile");
  }

  private static Path normalized(Path path, String label) {
    return Objects.requireNonNull(path, label).toAbsolutePath().normalize();
  }
}
