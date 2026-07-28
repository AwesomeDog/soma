package io.github.awesomedog.soma.app.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class HostHardwareTest {

  @Test
  void parsesMacAppleSiliconGraphicsAsUnifiedMemory() {
    var graphics =
        HostHardware.parseMacGraphics(
            List.of(
                "    Chipset Model: Apple M4 Pro",
                "    Type: GPU",
                "    Total Number of Cores: 20"),
            "Apple M4 Pro",
            48L * 1024 * 1024 * 1024);

    assertThat(graphics)
        .containsExactly(
            new HostHardware.GpuInfo(
                "Apple M4 Pro (20 GPU cores)", 48L * 1024 * 1024 * 1024, true));
  }

  @Test
  void parsesLinuxCpuMemoryAndNvidiaGraphics() {
    assertThat(
            HostHardware.parseLinuxCpu(
                List.of("processor : 0", "model name : AMD Ryzen 9 9950X", "cpu cores : 16")))
        .isEqualTo("AMD Ryzen 9 9950X");
    assertThat(HostHardware.parseLinuxMemory(List.of("MemTotal:       65536000 kB")))
        .isEqualTo(65536000L * 1024);
    assertThat(
            HostHardware.parseNvidiaGraphics(
                List.of("NVIDIA GeForce RTX 4090, 24564", "NVIDIA T4, 15360")))
        .containsExactly(
            new HostHardware.GpuInfo("NVIDIA GeForce RTX 4090", 24564L * 1024 * 1024, false),
            new HostHardware.GpuInfo("NVIDIA T4", 15360L * 1024 * 1024, false));
    assertThat(
            HostHardware.parsePciGraphics(
                List.of("0000:03:00.0 VGA compatible controller: AMD Radeon RX 7900 XTX (rev c8)"),
                bus -> "0000:03:00.0".equals(bus) ? 24L * 1024 * 1024 * 1024 : -1))
        .containsExactly(
            new HostHardware.GpuInfo(
                "AMD Radeon RX 7900 XTX (rev c8)", 24L * 1024 * 1024 * 1024, false));
  }

  @Test
  void parsesWindowsPowerShellOutputWithoutInventingMissingVram() {
    var host =
        HostHardware.parseWindows(
            List.of(
                "CPU\tAMD Ryzen 9 9950X",
                "MEMORY\t68719476736",
                "GPU\tAMD Radeon RX 7900 XTX\t25769803776",
                "GPU\tIntel Graphics\t-1"),
            32,
            "windows-x86_64");

    assertThat(host.platform()).isEqualTo("windows-x86_64");
    assertThat(host.cpu()).isEqualTo("AMD Ryzen 9 9950X");
    assertThat(host.logicalCores()).isEqualTo(32);
    assertThat(host.memoryBytes()).isEqualTo(68719476736L);
    assertThat(host.graphics())
        .containsExactly(
            new HostHardware.GpuInfo("AMD Radeon RX 7900 XTX", 25769803776L, false),
            new HostHardware.GpuInfo("Intel Graphics", -1, false));
  }
}
