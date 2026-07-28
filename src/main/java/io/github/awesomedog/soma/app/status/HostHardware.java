package io.github.awesomedog.soma.app.status;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;

import io.github.awesomedog.soma.support.HostPlatform;
import io.micronaut.serde.annotation.Serdeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.ToLongFunction;

public final class HostHardware {

  private static final long MEBIBYTE = 1024L * 1024L;
  private static final int COMMAND_TIMEOUT_SECONDS = 3;
  private static final int MAX_COMMAND_OUTPUT_BYTES = 1024 * 1024;
  private static final String WINDOWS_PROBE =
      """
      $ErrorActionPreference = 'SilentlyContinue'
      [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
      $gpuMemory = @{}
      Get-ItemProperty 'Registry::HKEY_LOCAL_MACHINE\\SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}\\0*' -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.DriverDesc -and $_.'HardwareInformation.qwMemorySize') {
          $vram = $_.'HardwareInformation.qwMemorySize'
          if ($vram -is [byte[]] -and $vram.Length -ge 8) { $vram = [BitConverter]::ToUInt64($vram, 0) }
          $gpuMemory[$_.DriverDesc] = [uint64]$vram
        }
      }
      $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
      $system = Get-CimInstance Win32_ComputerSystem
      $tab = [char]9
      Write-Output ('CPU' + $tab + $cpu.Name.Trim())
      Write-Output ('MEMORY' + $tab + [uint64]$system.TotalPhysicalMemory)
      Get-CimInstance Win32_VideoController | Where-Object { $null -eq $_.ConfigManagerErrorCode -or $_.ConfigManagerErrorCode -eq 0 } | ForEach-Object {
        $vram = if ($gpuMemory.ContainsKey($_.Name)) { [string]$gpuMemory[$_.Name] } else { '-1' }
        Write-Output ('GPU' + $tab + $_.Name.Trim() + $tab + $vram)
      }
      """;

  private HostHardware() {}

  public static HostInfo inspect() {
    try {
      return inspectHost();
    } catch (RuntimeException e) {
      return new HostInfo("unknown", "unknown", 0, -1, List.of());
    }
  }

  private static HostInfo inspectHost() {
    var platform = HostPlatform.current();
    var logicalCores = Runtime.getRuntime().availableProcessors();
    return switch (platform.operatingSystem()) {
      case DARWIN -> inspectMac(logicalCores, platform.id());
      case LINUX -> inspectLinux(logicalCores, platform.id());
      case WINDOWS -> inspectWindows(logicalCores, platform.id());
      case UNKNOWN ->
          new HostInfo(
              platform.id(), System.getProperty("os.arch", "unknown"), logicalCores, -1, List.of());
    };
  }

  private static HostInfo inspectMac(int logicalCores, String platform) {
    var system = run(List.of("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string", "hw.memsize"));
    var cpu = system.isEmpty() ? "Apple Silicon" : system.getFirst();
    var memory = system.size() < 2 ? -1 : parseLong(system.get(1));
    var graphics =
        parseMacGraphics(
            run(List.of("/usr/sbin/system_profiler", "SPDisplaysDataType")), cpu, memory);
    return new HostInfo(platform, cpu, logicalCores, memory, graphics);
  }

  private static HostInfo inspectLinux(int logicalCores, String platform) {
    var cpuInfo = readLines(Path.of("/proc/cpuinfo"));
    var memoryInfo = readLines(Path.of("/proc/meminfo"));
    var graphics =
        parseNvidiaGraphics(
            run(
                List.of(
                    "nvidia-smi",
                    "--query-gpu=name,memory.total",
                    "--format=csv,noheader,nounits")));
    if (graphics.isEmpty()) {
      graphics =
          parsePciGraphics(
              run(List.of("lspci", "-D")),
              bus -> readLong(Path.of("/sys/bus/pci/devices", bus, "mem_info_vram_total")));
    }
    return new HostInfo(
        platform, parseLinuxCpu(cpuInfo), logicalCores, parseLinuxMemory(memoryInfo), graphics);
  }

  private static HostInfo inspectWindows(int logicalCores, String platform) {
    var systemRoot = System.getenv("SystemRoot");
    var powershell =
        systemRoot == null || systemRoot.isBlank()
            ? "powershell.exe"
            : Path.of(systemRoot, "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
                .toString();
    return parseWindows(
        run(
            List.of(
                powershell, "-NoLogo", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_PROBE)),
        logicalCores,
        platform);
  }

  static List<GpuInfo> parseMacGraphics(List<String> lines, String fallbackName, long memory) {
    var graphics = new ArrayList<GpuInfo>();
    String name = null;
    String cores = null;
    for (var line : lines) {
      var value = line.trim();
      if (value.startsWith("Chipset Model:")) {
        addMacGpu(graphics, name, cores, memory);
        name = value.substring("Chipset Model:".length()).trim();
        cores = null;
      } else if (name != null && value.startsWith("Total Number of Cores:")) {
        cores = value.substring("Total Number of Cores:".length()).trim();
      }
    }
    addMacGpu(graphics, name, cores, memory);
    if (graphics.isEmpty() && !unknown(fallbackName)) {
      graphics.add(new GpuInfo(fallbackName, memory, true));
    }
    return List.copyOf(graphics);
  }

  private static void addMacGpu(List<GpuInfo> graphics, String name, String cores, long memory) {
    if (unknown(name)) {
      return;
    }
    var displayName = unknown(cores) ? name : name + " (" + cores + " GPU cores)";
    graphics.add(new GpuInfo(displayName, memory, true));
  }

  static String parseLinuxCpu(List<String> lines) {
    var model = value(lines, "model name");
    return unknown(model) ? System.getProperty("os.arch", "unknown") : model;
  }

  static long parseLinuxMemory(List<String> lines) {
    var total = value(lines, "MemTotal");
    if (unknown(total)) {
      return -1;
    }
    var parts = total.split("\\s+");
    try {
      return Math.multiplyExact(Long.parseLong(parts[0]), 1024L);
    } catch (ArithmeticException | NumberFormatException e) {
      return -1;
    }
  }

  static List<GpuInfo> parseNvidiaGraphics(List<String> lines) {
    var graphics = new ArrayList<GpuInfo>();
    for (var line : lines) {
      var separator = line.lastIndexOf(',');
      if (separator < 1) {
        continue;
      }
      var name = line.substring(0, separator).trim();
      var mebibytes = parseLong(line.substring(separator + 1));
      if (!unknown(name)) {
        graphics.add(new GpuInfo(name, mebibytes < 0 ? -1 : mebibytes * MEBIBYTE, false));
      }
    }
    return List.copyOf(graphics);
  }

  static List<GpuInfo> parsePciGraphics(List<String> lines, ToLongFunction<String> vramBytes) {
    var graphics = new ArrayList<GpuInfo>();
    var displayClasses =
        List.of(" VGA compatible controller: ", " 3D controller: ", " Display controller: ");
    for (var line : lines) {
      for (var displayClass : displayClasses) {
        var marker = line.indexOf(displayClass);
        if (marker < 0) {
          continue;
        }
        var bus = line.substring(0, line.indexOf(' ')).trim();
        var name = line.substring(marker + displayClass.length()).trim();
        var vram = vramBytes.applyAsLong(bus);
        graphics.add(new GpuInfo(name, vram, false));
        break;
      }
    }
    return List.copyOf(graphics);
  }

  static HostInfo parseWindows(List<String> lines, int logicalCores, String platform) {
    var cpu = System.getProperty("os.arch", "unknown");
    var memory = -1L;
    var graphics = new ArrayList<GpuInfo>();
    for (var line : lines) {
      var fields = line.split("\\t", -1);
      if (fields.length >= 2 && "CPU".equals(fields[0])) {
        cpu = fields[1].trim();
      } else if (fields.length >= 2 && "MEMORY".equals(fields[0])) {
        memory = parseLong(fields[1]);
      } else if (fields.length >= 3 && "GPU".equals(fields[0]) && !unknown(fields[1])) {
        graphics.add(new GpuInfo(fields[1].trim(), parseLong(fields[2]), false));
      }
    }
    return new HostInfo(platform, cpu, logicalCores, memory, graphics);
  }

  private static List<String> run(List<String> command) {
    Process process = null;
    try {
      var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
      builder.environment().put("LC_ALL", "C");
      builder.environment().put("LANG", "C");
      process = builder.start();
      var input = process.getInputStream();
      var output =
          new FutureTask<>(
              () -> {
                try (input) {
                  var bytes = input.readNBytes(MAX_COMMAND_OUTPUT_BYTES + 1);
                  input.transferTo(OutputStream.nullOutputStream());
                  return bytes;
                }
              });
      Thread.startVirtualThread(output);
      var completed = process.waitFor(COMMAND_TIMEOUT_SECONDS, SECONDS);
      if (!completed) {
        process.destroyForcibly().waitFor();
      }
      var bytes = output.get();
      if (!completed || process.exitValue() != 0 || bytes.length > MAX_COMMAND_OUTPUT_BYTES) {
        return List.of();
      }
      return new String(bytes, UTF_8).lines().toList();
    } catch (IOException | SecurityException | ExecutionException e) {
      return List.of();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return List.of();
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static List<String> readLines(Path path) {
    try {
      return Files.readAllLines(path, UTF_8);
    } catch (IOException | SecurityException e) {
      return List.of();
    }
  }

  private static long readLong(Path path) {
    try {
      return parseLong(Files.readString(path, UTF_8));
    } catch (IOException | SecurityException e) {
      return -1;
    }
  }

  private static String value(List<String> lines, String key) {
    for (var line : lines) {
      var separator = line.indexOf('=');
      if (separator < 0) {
        separator = line.indexOf(':');
      }
      if (separator > 0 && key.equalsIgnoreCase(line.substring(0, separator).trim())) {
        return line.substring(separator + 1).trim();
      }
    }
    return null;
  }

  private static long parseLong(String value) {
    try {
      return Long.parseLong(value.trim());
    } catch (NullPointerException | NumberFormatException e) {
      return -1;
    }
  }

  private static boolean unknown(String value) {
    return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value);
  }

  @Serdeable
  public record HostInfo(
      String platform, String cpu, int logicalCores, long memoryBytes, List<GpuInfo> graphics) {

    public HostInfo {
      graphics = List.copyOf(graphics);
    }
  }

  @Serdeable
  public record GpuInfo(String name, long vramBytes, boolean sharedMemory) {}
}
