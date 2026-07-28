package io.github.awesomedog.soma.http;

import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.server.types.files.SystemFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;

@Controller
public final class WebController {

  private static final MediaType UTF_8_PLAIN_TEXT =
      MediaType.of(MediaType.TEXT_PLAIN + "; charset=" + StandardCharsets.UTF_8.name());
  private final ConfigStore configStore;
  private final ActiveWorkspace workspace;

  public WebController(ConfigStore configStore, ActiveWorkspace workspace) {
    this.configStore = configStore;
    this.workspace = workspace;
  }

  @Get(uri = "/assets/{project}/{+path}", produces = MediaType.ALL)
  @ExecuteOn(TaskExecutors.BLOCKING)
  public HttpResponse<SystemFile> asset(String project, String path) {
    var configuredProject =
        configStore.load(workspace.configFile()).projects().stream()
            .filter(candidate -> candidate.name().value().equals(project))
            .findFirst();
    if (configuredProject.isEmpty()) {
      return HttpResponse.notFound();
    }

    var projectRoot = configuredProject.orElseThrow().root();
    final Path resolvedFile;
    try {
      var requestedFilePath = projectRoot.resolve(path).normalize();
      if (!requestedFilePath.startsWith(projectRoot)) {
        return HttpResponse.notFound();
      }

      var realProjectRoot = projectRoot.toRealPath();
      resolvedFile = requestedFilePath.toRealPath();
      if (!resolvedFile.startsWith(realProjectRoot) || !Files.isRegularFile(resolvedFile)) {
        return HttpResponse.notFound();
      }
    } catch (IOException | InvalidPathException | SecurityException e) {
      return HttpResponse.notFound();
    }
    var file = resolvedFile.toFile();
    var lowercaseFileName = file.getName().toLowerCase(Locale.ROOT);
    var systemFile =
        lowercaseFileName.endsWith(".md") || lowercaseFileName.endsWith(".markdown")
            ? new SystemFile(file, UTF_8_PLAIN_TEXT)
            : new SystemFile(file);
    return HttpResponse.ok(systemFile);
  }
}
