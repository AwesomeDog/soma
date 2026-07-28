package io.github.awesomedog.soma.app.ports;

import java.nio.file.Path;

public interface WriteLock {

  Token acquire(Path lockFile, String commandName);

  interface Token extends AutoCloseable {

    @Override
    void close();
  }
}
