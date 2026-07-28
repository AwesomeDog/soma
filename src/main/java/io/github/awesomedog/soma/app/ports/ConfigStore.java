package io.github.awesomedog.soma.app.ports;

import io.github.awesomedog.soma.domain.config.SomaConfig;
import java.nio.file.Path;

public interface ConfigStore {

  SomaConfig load(Path configFile);

  SomaConfig loadOrBackupResetForUpdate(Path configFile);

  void save(Path configFile, SomaConfig config);
}
