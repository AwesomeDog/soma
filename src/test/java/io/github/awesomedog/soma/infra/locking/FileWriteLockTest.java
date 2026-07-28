package io.github.awesomedog.soma.infra.locking;

import static java.nio.file.StandardOpenOption.WRITE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.support.HostPlatform;
import io.micronaut.context.ApplicationContext;
import io.micronaut.json.JsonMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileWriteLockTest {

  @TempDir Path tempDir;

  @Test
  void guardsTheJvmWritesOwnerMetadataAndReleasesIdempotently() throws Exception {
    try (var context = ApplicationContext.run()) {
      var lock = context.getBean(FileWriteLock.class);
      var lockFile = tempDir.resolve("locks/main.lock");
      var first = lock.acquire(lockFile, "soma sync");

      assertThat(Files.readString(lockFile))
          .contains("\"pid\":" + ProcessHandle.current().pid())
          .contains("\"command\":\"soma sync\"")
          .contains("\"acquiredAt\":");
      assertThatThrownBy(() -> lock.acquire(lockFile, "soma system scan"))
          .isInstanceOfSatisfying(
              AppException.class,
              error -> {
                assertThat(error.error().code()).isEqualTo(AppError.Code.WRITE_LOCKED);
                assertThat(error.error().details())
                    .isInstanceOfSatisfying(
                        FileWriteLock.Owner.class,
                        owner -> assertThat(owner.command()).isEqualTo("soma sync"));
              });

      var independentLock = new FileWriteLock(context.getBean(JsonMapper.class));
      assertThatThrownBy(() -> independentLock.acquire(lockFile, "soma context set"))
          .isInstanceOfSatisfying(
              AppException.class,
              error -> assertThat(error.error().code()).isEqualTo(AppError.Code.WRITE_LOCKED));

      first.close();
      first.close();
      try (var ignored = independentLock.acquire(lockFile, "soma context set")) {
        assertThat(lockFile).isRegularFile();
      }
      try (var ignored = lock.acquire(lockFile, "soma system scan")) {
        var owner =
            context
                .getBean(JsonMapper.class)
                .readValue(Files.readAllBytes(lockFile), FileWriteLock.Owner.class);
        assertThat(owner.command()).isEqualTo("soma system scan");
        assertThat(owner.pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(Instant.parse(owner.acquiredAt())).isBeforeOrEqualTo(Instant.now());
      }
    }
  }

  @Test
  void fastFailsAgainstAnotherProcessWithBestEffortOwnerInformation() throws Exception {
    var lockFile = tempDir.resolve("external/main.lock");
    var source = tempDir.resolve("LockHolder.java");
    Files.writeString(source, lockHolderSource());
    var process =
        new ProcessBuilder(javaExecutable(), source.toString(), lockFile.toString())
            .redirectErrorStream(true)
            .start();

    try (var output =
            new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        var input = process.getOutputStream();
        var context = ApplicationContext.run()) {
      assertThat(output.readLine()).isEqualTo("ready");
      var lock = context.getBean(FileWriteLock.class);

      assertExternalOwnerIsReported(lock, lockFile);

      try (var channel = FileChannel.open(lockFile, WRITE)) {
        channel.write(ByteBuffer.wrap(new byte[] {'x'}), 0);
      }
      assertThatThrownBy(() -> lock.acquire(lockFile, "soma context set"))
          .isInstanceOfSatisfying(
              AppException.class,
              error -> assertThat(error.error().code()).isEqualTo(AppError.Code.WRITE_LOCKED));

      input.write('\n');
      input.flush();
      assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).isZero();
      try (var ignored = lock.acquire(lockFile, "soma context set")) {
        assertThat(lockFile).isRegularFile();
      }
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private static void assertExternalOwnerIsReported(FileWriteLock lock, Path lockFile) {
    assertThatThrownBy(() -> lock.acquire(lockFile, "soma context set"))
        .isInstanceOfSatisfying(
            AppException.class,
            error -> assertThat(error.error().code()).isEqualTo(AppError.Code.WRITE_LOCKED));
  }

  private static String javaExecutable() {
    var executable = HostPlatform.current().isWindows() ? "java.exe" : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }

  private static String lockHolderSource() {
    return """
                import static java.nio.file.StandardOpenOption.CREATE;
                import static java.nio.file.StandardOpenOption.READ;
                import static java.nio.file.StandardOpenOption.WRITE;
                import java.nio.ByteBuffer;
                import java.nio.channels.FileChannel;
                import java.nio.charset.StandardCharsets;
                import java.nio.file.Files;
                import java.nio.file.Path;

                public class LockHolder {
                    public static void main(String[] args) throws Exception {
                        var path = Path.of(args[0]);
                        Files.createDirectories(path.getParent());
                        try (var channel = FileChannel.open(path, CREATE, READ, WRITE);
                                var ignored = channel.lock(Long.MAX_VALUE - 1, 1, false)) {
                            var json = ("{\\\"pid\\\":" + ProcessHandle.current().pid()
                                    + ",\\\"command\\\":\\\"soma system scan\\\""
                                    + ",\\\"acquiredAt\\\":\\\"2026-06-29T10:00:00Z\\\"}")
                                    .getBytes(StandardCharsets.UTF_8);
                            channel.truncate(0);
                            channel.position(0);
                            var content = ByteBuffer.wrap(json);
                            while (content.hasRemaining()) channel.write(content);
                            System.out.println("ready");
                            System.out.flush();
                            System.in.read();
                        }
                    }
                }
                """;
  }
}
