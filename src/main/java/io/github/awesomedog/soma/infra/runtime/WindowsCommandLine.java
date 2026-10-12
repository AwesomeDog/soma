package io.github.awesomedog.soma.infra.runtime;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import io.github.awesomedog.soma.support.HostPlatform;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.Arrays;

/**
 * Recovers the real command-line arguments on Windows.
 *
 * <p>A native image receives {@code main} arguments from the C runtime's narrow {@code argv}, which
 * Windows encodes in the process ANSI code page (e.g. CP936 on Chinese systems). The image then
 * decodes those bytes with the {@code sun.jnu.encoding} baked in at build time (UTF-8 since
 * v0.10.3), so any non-ASCII argument is turned into U+FFFD replacement characters. Re-reading the
 * UTF-16 command line via {@code GetCommandLineW}/{@code CommandLineToArgvW} avoids the lossy round
 * trip entirely.
 */
public final class WindowsCommandLine {

  private WindowsCommandLine() {}

  /** Returns the UTF-16 arguments on Windows, or {@code args} unchanged when unavailable. */
  public static String[] arguments(String[] args) {
    if (args == null || !HostPlatform.current().isWindows()) {
      return args;
    }
    try {
      return reconcile(args, readWideArgv());
    } catch (Throwable failure) {
      return args;
    }
  }

  /**
   * Picks the wide arguments only when they line up with what the runtime passed to {@code main}:
   * same count (after dropping the program name) and identical wherever the narrow argument is pure
   * ASCII. Anything else (e.g. running on a JVM launcher, or runtime options consumed by the image)
   * keeps the original arguments.
   */
  static String[] reconcile(String[] args, String[] wideArgv) {
    if (wideArgv == null || wideArgv.length != args.length + 1) {
      return args;
    }
    var wide = Arrays.copyOfRange(wideArgv, 1, wideArgv.length);
    for (int i = 0; i < args.length; i++) {
      if (isAscii(args[i]) && !args[i].equals(wide[i])) {
        return args;
      }
    }
    return wide;
  }

  private static boolean isAscii(String value) {
    return value.chars().allMatch(c -> c < 0x80);
  }

  private static String[] readWideArgv() throws Throwable {
    var linker = Linker.nativeLinker();
    var kernel32 = SymbolLookup.libraryLookup("Kernel32", Arena.global());
    var shell32 = SymbolLookup.libraryLookup("Shell32", Arena.global());
    var getCommandLine =
        linker.downcallHandle(
            kernel32.find("GetCommandLineW").orElseThrow(), FunctionDescriptor.of(ADDRESS));
    var commandLineToArgv =
        linker.downcallHandle(
            shell32.find("CommandLineToArgvW").orElseThrow(),
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    var localFree =
        linker.downcallHandle(
            kernel32.find("LocalFree").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS));

    try (var arena = Arena.ofConfined()) {
      var commandLine = (MemorySegment) getCommandLine.invokeExact();
      var count = arena.allocate(JAVA_INT);
      var argv = (MemorySegment) commandLineToArgv.invokeExact(commandLine, count);
      if (argv.address() == 0) {
        return null;
      }
      try {
        int argc = count.get(JAVA_INT, 0);
        var pointers = argv.reinterpret(ADDRESS.byteSize() * argc);
        var result = new String[argc];
        for (int i = 0; i < argc; i++) {
          result[i] = readUtf16(pointers.getAtIndex(ADDRESS, i));
        }
        return result;
      } finally {
        var ignored = (MemorySegment) localFree.invokeExact(argv);
      }
    }
  }

  private static String readUtf16(MemorySegment pointer) {
    var chars = pointer.reinterpret(Long.MAX_VALUE);
    var builder = new StringBuilder();
    for (long i = 0; ; i++) {
      char c = chars.getAtIndex(JAVA_CHAR, i);
      if (c == 0) {
        return builder.toString();
      }
      builder.append(c);
    }
  }
}
