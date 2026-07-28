package io.github.awesomedog.soma.infra.config;

import static io.github.awesomedog.soma.app.common.AppError.Code.CONFIG_ERROR;
import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.domain.config.ContextConfig;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.support.PathSupport;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;

@Singleton
public final class YamlConfigStore implements ConfigStore {

  private static final DateTimeFormatter BACKUP_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final LoadSettings LOAD_SETTINGS = LoadSettings.builder().build();
  private static final DumpSettings DUMP_SETTINGS =
      DumpSettings.builder()
          .setDefaultFlowStyle(FlowStyle.BLOCK)
          .setIndent(2)
          .setUseUnicodeEncoding(true)
          .setDereferenceAliases(true)
          .build();

  @Override
  public SomaConfig load(Path configFile) {
    var normalizedConfigFile = normalizeConfigFile(configFile);
    if (!Files.exists(normalizedConfigFile)) {
      return SomaConfig.empty();
    }
    try {
      if (!Files.isRegularFile(normalizedConfigFile) || !Files.isReadable(normalizedConfigFile)) {
        throw new IOException("configuration is not a readable file");
      }
      return parse(
          new Load(LOAD_SETTINGS)
              .loadFromString(Files.readString(normalizedConfigFile, StandardCharsets.UTF_8)),
          normalizedConfigFile);
    } catch (Exception e) {
      throw invalid(normalizedConfigFile, e);
    }
  }

  @Override
  public SomaConfig loadOrBackupResetForUpdate(Path configFile) {
    var normalizedConfigFile = normalizeConfigFile(configFile);
    try {
      return load(normalizedConfigFile);
    } catch (AppException invalid) {
      if (!Files.isRegularFile(normalizedConfigFile)) {
        throw invalid;
      }
      try {
        var backupFile = nextBackup(normalizedConfigFile);
        Files.copy(normalizedConfigFile, backupFile);
        save(normalizedConfigFile, SomaConfig.empty());
        return SomaConfig.empty();
      } catch (IOException backupResetFailure) {
        invalid.addSuppressed(backupResetFailure);
        throw invalid;
      }
    }
  }

  @Override
  public void save(Path configFile, SomaConfig config) {
    var normalizedConfigFile = normalizeConfigFile(configFile);
    Path temporaryConfigFile = null;
    try {
      Files.createDirectories(normalizedConfigFile.getParent());
      temporaryConfigFile =
          Files.createTempFile(
              normalizedConfigFile.getParent(), normalizedConfigFile.getFileName() + ".", ".tmp");
      Files.writeString(
          temporaryConfigFile,
          new Dump(DUMP_SETTINGS).dumpToString(toYaml(config, normalizedConfigFile)),
          StandardCharsets.UTF_8);
      Files.move(temporaryConfigFile, normalizedConfigFile, REPLACE_EXISTING, ATOMIC_MOVE);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException(
          CONFIG_ERROR,
          "Could not write Soma configuration: " + normalizedConfigFile,
          "Check file permissions and available disk space, then retry.",
          e);
    } finally {
      if (temporaryConfigFile != null) {
        try {
          Files.deleteIfExists(temporaryConfigFile);
        } catch (IOException ignored) {
          // Best-effort cleanup after publication or failure.
        }
      }
    }
  }

  private SomaConfig parse(Object yamlDocument, Path configFile) {
    if (yamlDocument == null) {
      return SomaConfig.empty();
    }
    if (!(yamlDocument instanceof Map<?, ?> yamlRoot)) {
      throw new IllegalArgumentException("configuration root must be a mapping");
    }

    var localWorkspaceRoot = localWorkspaceRoot(configFile);
    var projectConfigs = new ArrayList<ProjectConfig>();
    for (var projectEntry : readList(yamlRoot.get("projects"), "projects")) {
      if (!(projectEntry instanceof Map<?, ?> projectFields)) {
        throw new IllegalArgumentException("project entry must be a mapping");
      }
      projectConfigs.add(
          new ProjectConfig(
              new ProjectName(requiredText(projectFields, "name")),
              resolveProjectRoot(requiredText(projectFields, "root"), localWorkspaceRoot),
              readStringList(projectFields.get("include"), List.of("**/*"), "include"),
              readStringList(projectFields.get("exclude"), List.of(), "exclude"),
              readBoolean(projectFields.get("default-search"), true, "default-search"),
              readBoolean(projectFields.get("ignore-files"), true, "ignore-files")));
    }

    var contextsByIdentity = new LinkedHashMap<ContextKey, ContextConfig>();
    for (var contextEntry : readList(yamlRoot.get("context"), "context")) {
      if (!(contextEntry instanceof Map<?, ?> contextFields)) {
        throw new IllegalArgumentException("context entry must be a mapping");
      }
      var projectText = optionalText(contextFields.get("project"), "project");
      var contextConfig =
          new ContextConfig(
              projectText == null ? null : new ProjectName(projectText),
              requiredText(contextFields, "path"),
              requiredText(contextFields, "text"));
      replaceContextWithSameIdentity(contextsByIdentity, contextConfig);
    }
    return new SomaConfig(
        readConfigVersion(yamlRoot.get("version")),
        projectConfigs,
        List.copyOf(contextsByIdentity.values()));
  }

  private static void replaceContextWithSameIdentity(
      Map<ContextKey, ContextConfig> contextsByIdentity, ContextConfig context) {
    contextsByIdentity.put(new ContextKey(context.project(), context.path()), context);
  }

  private static Map<String, Object> toYaml(SomaConfig config, Path configFile) {
    var yamlRoot = new LinkedHashMap<String, Object>();
    yamlRoot.put("version", config.version());
    var localWorkspaceRoot = localWorkspaceRoot(configFile);
    var projectEntries = new ArrayList<Map<String, Object>>();
    for (var project : config.projects()) {
      var projectFields = new LinkedHashMap<String, Object>();
      projectFields.put("name", project.name().value());
      projectFields.put("root", projectRootForYaml(project.root(), localWorkspaceRoot));
      projectFields.put("include", project.include());
      projectFields.put("exclude", project.exclude());
      projectFields.put("default-search", project.defaultSearch());
      projectFields.put("ignore-files", project.ignoreFiles());
      projectEntries.add(projectFields);
    }
    yamlRoot.put("projects", projectEntries);

    var contextEntries = new ArrayList<Map<String, Object>>();
    for (var context : config.context()) {
      var contextFields = new LinkedHashMap<String, Object>();
      contextFields.put("project", context.project() == null ? null : context.project().value());
      contextFields.put("path", context.path());
      contextFields.put("text", context.text());
      contextEntries.add(contextFields);
    }
    yamlRoot.put("context", contextEntries);
    return yamlRoot;
  }

  private static int readConfigVersion(Object yamlVersionValue) {
    if (yamlVersionValue == null) {
      return SomaConfig.CURRENT_VERSION;
    }
    if (yamlVersionValue instanceof Number number) {
      return new BigDecimal(number.toString()).intValueExact();
    }
    throw new IllegalArgumentException(
        "configuration version must be the integer " + SomaConfig.CURRENT_VERSION);
  }

  private static List<?> readList(Object yamlValue, String fieldName) {
    if (yamlValue == null) {
      return List.of();
    }
    if (yamlValue instanceof List<?> list) {
      return list;
    }
    throw new IllegalArgumentException("`" + fieldName + "` must be a list");
  }

  private static List<String> readStringList(
      Object yamlValue, List<String> defaultValues, String fieldName) {
    if (yamlValue == null) {
      return defaultValues;
    }
    if (!(yamlValue instanceof List<?> list)) {
      throw new IllegalArgumentException("`" + fieldName + "` must be a list");
    }
    var stringValues = new ArrayList<String>(list.size());
    for (var listEntry : list) {
      if (!(listEntry instanceof String textValue)) {
        throw new IllegalArgumentException("`" + fieldName + "` entries must be strings");
      }
      stringValues.add(textValue);
    }
    return List.copyOf(stringValues);
  }

  private static boolean readBoolean(Object yamlValue, boolean defaultValue, String fieldName) {
    if (yamlValue == null) {
      return defaultValue;
    }
    if (yamlValue instanceof Boolean booleanValue) {
      return booleanValue;
    }
    throw new IllegalArgumentException("`" + fieldName + "` must be a boolean");
  }

  private static String requiredText(Map<?, ?> fields, String fieldName) {
    var textValue = optionalText(fields.get(fieldName), fieldName);
    if (textValue == null) {
      throw new IllegalArgumentException("`" + fieldName + "` is required");
    }
    return textValue;
  }

  private static String optionalText(Object yamlValue, String fieldName) {
    if (yamlValue == null) {
      return null;
    }
    if (!(yamlValue instanceof String textValue)) {
      throw new IllegalArgumentException("`" + fieldName + "` must be a string");
    }
    return textValue.isBlank() ? null : textValue;
  }

  private static Path resolveProjectRoot(String configuredRoot, Path localWorkspaceRoot) {
    if (localWorkspaceRoot != null) {
      if (!configuredRoot.equals(".") && !configuredRoot.startsWith("./")) {
        throw new IllegalArgumentException(
            "Directory-local project root must be `.` or start with `./`: " + configuredRoot);
      }
      var resolvedRoot = localWorkspaceRoot.resolve(configuredRoot).normalize();
      if (!resolvedRoot.startsWith(localWorkspaceRoot)) {
        throw new IllegalArgumentException(
            "Directory-local project root must stay within the workspace: " + configuredRoot);
      }
      return resolvedRoot;
    }
    if (!isAbsoluteOrHomePath(configuredRoot)) {
      throw new IllegalArgumentException(
          "Project root in configuration must be absolute or start with `~`: " + configuredRoot);
    }
    return PathSupport.resolveUserPath(configuredRoot);
  }

  private static String projectRootForYaml(Path projectRoot, Path localWorkspaceRoot) {
    if (localWorkspaceRoot == null) {
      return PathSupport.normalizePathSeparators(projectRoot.toString());
    }
    if (!projectRoot.startsWith(localWorkspaceRoot)) {
      throw new AppException(
          INVALID_REQUEST,
          "Directory-local project root must stay within the workspace: " + projectRoot,
          "Choose the workspace directory or one of its descendants.");
    }
    var relativeRoot = localWorkspaceRoot.relativize(projectRoot);
    if (relativeRoot.toString().isEmpty()) {
      return ".";
    }
    return "./" + PathSupport.normalizePathSeparators(relativeRoot.toString());
  }

  private static Path localWorkspaceRoot(Path configFile) {
    var somaDirectory = configFile.getParent();
    if (configFile.getFileName() == null
        || !configFile.getFileName().toString().equals("local.yml")
        || somaDirectory == null
        || somaDirectory.getFileName() == null
        || !somaDirectory.getFileName().toString().equals(".soma")) {
      return null;
    }
    return somaDirectory.getParent();
  }

  private static boolean isAbsoluteOrHomePath(String value) {
    return Path.of(value).isAbsolute()
        || value.equals("~")
        || value.startsWith("~/")
        || value.startsWith("~\\");
  }

  private static Path nextBackup(Path configFile) {
    var backupStem =
        configFile.getFileName() + ".invalid-" + BACKUP_STAMP.format(Instant.now()) + ".bak";
    var backupFile = configFile.resolveSibling(backupStem);
    for (var suffix = 1; Files.exists(backupFile); suffix++) {
      backupFile = configFile.resolveSibling(backupStem + "." + suffix);
    }
    return backupFile;
  }

  private static Path normalizeConfigFile(Path configFile) {
    return configFile.toAbsolutePath().normalize();
  }

  private static AppException invalid(Path configFile, Exception cause) {
    return new AppException(
        CONFIG_ERROR,
        "Invalid Soma configuration: " + configFile,
        "Fix the YAML file and retry. A write command will back it up and replace it.",
        cause);
  }

  private record ContextKey(ProjectName project, String path) {}
}
