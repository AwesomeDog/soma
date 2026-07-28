package io.github.awesomedog.soma.app.init;

import static io.github.awesomedog.soma.app.common.AppError.Code.CONFIG_ERROR;
import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static io.github.awesomedog.soma.app.common.Renderable.requireTextFormat;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.Renderable;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.support.PathSupport;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;

@Singleton
public final class WorkspaceInitializer {

  private final ConfigStore configStore;

  public WorkspaceInitializer(ConfigStore configStore) {
    this.configStore = configStore;
  }

  public InitResult initialize(Path configFile) {
    configFile = configFile.toAbsolutePath().normalize();
    var root = configFile.getParent().getParent();
    var home = PathSupport.userHomeDirectory();
    if (root.equals(home)) {
      throw new AppException(
          INVALID_REQUEST,
          "Soma cannot initialize a directory-local workspace in the home directory.",
          "Run `soma init` from a project directory.");
    }
    try {
      Files.createDirectories(configFile.getParent());
      var temporaryConfigFile =
          Files.createTempFile(configFile.getParent(), configFile.getFileName() + ".", ".tmp");
      try {
        configStore.save(temporaryConfigFile, SomaConfig.empty());
        Files.createLink(configFile, temporaryConfigFile);
      } finally {
        try {
          Files.deleteIfExists(temporaryConfigFile);
        } catch (IOException ignored) {
          // Best-effort cleanup after publication or failure.
        }
      }
    } catch (FileAlreadyExistsException e) {
      throw new AppException(
          CONFIG_ERROR,
          "Directory-local workspace already exists: " + configFile,
          "Use the existing workspace or remove it explicitly before retrying.",
          e);
    } catch (IOException e) {
      throw new AppException(
          CONFIG_ERROR,
          "Could not write Soma configuration: " + configFile,
          "Check file permissions and available disk space, then retry.",
          e);
    }
    return new InitResult(PathSupport.toPortableString(configFile));
  }

  @Serdeable
  public record InitResult(String configFile) implements Renderable {

    @Override
    public void render(OutputFormat format, PrintWriter out) {
      requireTextFormat(format);
      out.println("Directory-local workspace created.");
      out.println("  Config: " + configFile);
    }
  }
}
