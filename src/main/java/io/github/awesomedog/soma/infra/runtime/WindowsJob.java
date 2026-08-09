package io.github.awesomedog.soma.infra.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.awesomedog.soma.support.HostPlatform;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

/** Ensures Soma-managed runtimes exit when Soma is forcibly terminated on Windows. */
public final class WindowsJob {

  private static final int JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS = 9;
  private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x00002000;
  // JOBOBJECT_EXTENDED_LIMIT_INFORMATION in the Windows 64-bit ABI.
  private static final int EXTENDED_LIMIT_INFORMATION_SIZE = 144;
  private static final long LIMIT_FLAGS_OFFSET = 16;

  private static MemorySegment processLifetimeHandle = MemorySegment.NULL;

  private WindowsJob() {}

  public static synchronized void installForCurrentProcess() throws IOException {
    if (!HostPlatform.current().isWindows()) {
      return;
    }
    if (processLifetimeHandle.address() != 0) {
      return;
    }

    var linker = Linker.nativeLinker();
    var symbols = SymbolLookup.libraryLookup("Kernel32", Arena.global());
    var createJobObject =
        downcall(
            linker, symbols, "CreateJobObjectW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    var setInformationJobObject =
        downcall(
            linker,
            symbols,
            "SetInformationJobObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    var getCurrentProcess =
        downcall(linker, symbols, "GetCurrentProcess", FunctionDescriptor.of(ADDRESS));
    var assignProcessToJobObject =
        downcall(
            linker,
            symbols,
            "AssignProcessToJobObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    try {
      var jobHandle =
          (MemorySegment) createJobObject.invokeExact(MemorySegment.NULL, MemorySegment.NULL);
      require(jobHandle.address() != 0, "CreateJobObjectW");

      try (var arena = Arena.ofConfined()) {
        var information = arena.allocate(EXTENDED_LIMIT_INFORMATION_SIZE, Long.BYTES);
        information.set(JAVA_INT, LIMIT_FLAGS_OFFSET, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
        require(
            (int)
                    setInformationJobObject.invokeExact(
                        jobHandle,
                        JOB_OBJECT_EXTENDED_LIMIT_INFORMATION_CLASS,
                        information,
                        EXTENDED_LIMIT_INFORMATION_SIZE)
                != 0,
            "SetInformationJobObject");
      }

      var processHandle = (MemorySegment) getCurrentProcess.invokeExact();
      require(
          (int) assignProcessToJobObject.invokeExact(jobHandle, processHandle) != 0,
          "AssignProcessToJobObject");
      // Keep the only job handle open until Windows tears down the Soma process.
      processLifetimeHandle = jobHandle;
    } catch (IOException | RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new IOException("Could not create Windows job object", e);
    }
  }

  private static void require(boolean success, String operation) throws IOException {
    if (!success) {
      throw new IOException(operation + " failed");
    }
  }

  private static MethodHandle downcall(
      Linker linker, SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
    var symbol =
        symbols
            .find(name)
            .orElseThrow(() -> new IllegalStateException("Kernel32 symbol not found: " + name));
    return linker.downcallHandle(symbol, descriptor);
  }
}
