package io.github.awesomedog.soma.infra.locking;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;
import static io.github.awesomedog.soma.app.common.AppError.Code.WRITE_LOCKED;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.WriteLock;
import io.micronaut.json.JsonMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class FileWriteLock implements WriteLock {

  private static final Logger LOG = LoggerFactory.getLogger(FileWriteLock.class);
  private static final long OWNER_METADATA_OFFSET = 0L;
  private static final long LOCK_POSITION = Long.MAX_VALUE - 1;
  private static final int LOCK_LENGTH_BYTES = 1;
  private static final int MAX_OWNER_BYTES = 64 * 1024;

  @Serdeable
  public record Owner(long pid, String command, String acquiredAt) {}

  private final JsonMapper jsonMapper;
  private final AtomicReference<Owner> currentJvmOwner = new AtomicReference<>();

  public FileWriteLock(JsonMapper jsonMapper) {
    this.jsonMapper = Objects.requireNonNull(jsonMapper, "json");
  }

  @Override
  public Token acquire(Path lockFile, String command) {
    var normalizedLockPath =
        Objects.requireNonNull(lockFile, "lockFile").toAbsolutePath().normalize();
    var commandName = Objects.requireNonNull(command, "command");
    var currentLockOwner =
        new Owner(ProcessHandle.current().pid(), commandName, Instant.now().toString());

    var existingJvmOwner = currentJvmOwner.compareAndExchange(null, currentLockOwner);
    if (existingJvmOwner != null) {
      throw lockConflict(existingJvmOwner);
    }

    FileChannel lockChannel = null;
    try {
      Files.createDirectories(normalizedLockPath.getParent());
      lockChannel = FileChannel.open(normalizedLockPath, CREATE, READ, WRITE);
      var acquiredFileLock = lockChannel.tryLock(LOCK_POSITION, LOCK_LENGTH_BYTES, false);
      if (acquiredFileLock == null) {
        var existingLockOwner = readOwnerMetadata(lockChannel);
        lockChannel.close();
        currentJvmOwner.compareAndSet(currentLockOwner, null);
        throw lockConflict(existingLockOwner);
      }

      try {
        writeOwnerMetadata(lockChannel, currentLockOwner);
      } catch (IOException e) {
        if (!acquiredFileLock.isValid()) {
          throw e;
        }
        LOG.warn("failed to write lock owner metadata for {}", normalizedLockPath, e);
      }
      return new LockToken(currentLockOwner, lockChannel, acquiredFileLock);
    } catch (OverlappingFileLockException ignored) {
      closeChannelQuietly(lockChannel);
      currentJvmOwner.compareAndSet(currentLockOwner, null);
      throw lockConflict(null);
    } catch (IOException | SecurityException e) {
      closeChannelQuietly(lockChannel);
      currentJvmOwner.compareAndSet(currentLockOwner, null);
      throw lockAcquisitionFailed(normalizedLockPath, e);
    }
  }

  private static AppException lockAcquisitionFailed(Path lockPath, Exception cause) {
    return new AppException(
        OPERATION_FAILED,
        "Soma could not acquire the workspace write lock: " + lockPath,
        "Check the lock directory permissions, then retry.",
        cause);
  }

  private void writeOwnerMetadata(FileChannel lockChannel, Owner lockOwner) throws IOException {
    var ownerMetadata = ByteBuffer.wrap(jsonMapper.writeValueAsBytes(lockOwner));
    lockChannel.truncate(0);
    lockChannel.position(OWNER_METADATA_OFFSET);
    while (ownerMetadata.hasRemaining()) {
      lockChannel.write(ownerMetadata);
    }
  }

  private Owner readOwnerMetadata(FileChannel lockChannel) {
    try {
      var metadataSize = Math.min(lockChannel.size(), MAX_OWNER_BYTES);
      if (metadataSize <= 0) {
        return null;
      }
      var ownerMetadata = ByteBuffer.allocate((int) metadataSize);
      var fileOffset = OWNER_METADATA_OFFSET;
      while (ownerMetadata.hasRemaining()) {
        var bytesRead = lockChannel.read(ownerMetadata, fileOffset);
        if (bytesRead < 0) {
          break;
        }
        if (bytesRead == 0) {
          break;
        }
        fileOffset += bytesRead;
      }
      if (ownerMetadata.position() == 0) {
        return null;
      }
      var ownerMetadataBytes = new byte[ownerMetadata.position()];
      ownerMetadata.flip();
      ownerMetadata.get(ownerMetadataBytes);
      return jsonMapper.readValue(ownerMetadataBytes, Owner.class);
    } catch (IOException | RuntimeException e) {
      LOG.warn("failed to read workspace lock owner metadata", e);
      return null;
    }
  }

  private static AppException lockConflict(Owner existingOwner) {
    var pid =
        existingOwner == null || existingOwner.pid() <= 0
            ? "unknown"
            : Long.toString(existingOwner.pid());
    var command = displayOrUnknown(existingOwner == null ? null : existingOwner.command());
    var acquiredAt = displayOrUnknown(existingOwner == null ? null : existingOwner.acquiredAt());
    var message =
        "Soma is already running a write operation in this workspace."
            + System.lineSeparator()
            + "  pid: "
            + pid
            + "   command: "
            + command
            + "   acquiredAt: "
            + acquiredAt;
    var remediation =
        "Wait for it to finish, or verify that this PID belongs to Soma before "
            + "stopping it. Then retry."
            + System.lineSeparator()
            + "  macOS/Linux: kill "
            + pid
            + "      Windows: taskkill /PID "
            + pid;
    return new AppException(new AppError(WRITE_LOCKED, message, remediation, existingOwner));
  }

  private static String displayOrUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static void closeChannelQuietly(FileChannel lockChannel) {
    if (lockChannel == null) {
      return;
    }
    try {
      lockChannel.close();
    } catch (IOException e) {
      LOG.warn("failed to close workspace write lock channel", e);
    }
  }

  private final class LockToken implements Token {

    private final Owner lockOwner;
    private final FileChannel lockChannel;
    private final FileLock acquiredFileLock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private LockToken(Owner lockOwner, FileChannel lockChannel, FileLock acquiredFileLock) {
      this.lockOwner = lockOwner;
      this.lockChannel = lockChannel;
      this.acquiredFileLock = acquiredFileLock;
    }

    @Override
    public void close() {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      try {
        lockChannel.close();
      } catch (IOException e) {
        LOG.warn("failed to close workspace write lock", e);
      }
      if (acquiredFileLock.isValid()) {
        LOG.error("workspace write lock may still be held; restart Soma before retrying writes");
        return;
      }
      currentJvmOwner.compareAndSet(lockOwner, null);
    }
  }
}
