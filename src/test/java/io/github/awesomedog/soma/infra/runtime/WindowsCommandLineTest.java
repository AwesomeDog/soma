package io.github.awesomedog.soma.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WindowsCommandLineTest {

  private static final String MANGLED = "C:\\WPS\uFFFD\uFFFD\\\uFFFD\uFFFD";
  private static final String REAL = "C:\\WPS云盘\\公司范本";

  @Test
  void prefersWideArgumentsWhenTheyLineUp() {
    String[] narrow = {"project", "add", MANGLED, "--name", "law"};
    String[] wide = {"soma.exe", "project", "add", REAL, "--name", "law"};
    assertThat(WindowsCommandLine.reconcile(narrow, wide))
        .containsExactly("project", "add", REAL, "--name", "law");
  }

  @Test
  void keepsOriginalArgumentsWhenCountDiffers() {
    String[] narrow = {"project", "add", MANGLED};
    String[] wide = {"java.exe", "-cp", "x", "Main", "project", "add", REAL};
    assertThat(WindowsCommandLine.reconcile(narrow, wide)).isSameAs(narrow);
  }

  @Test
  void keepsOriginalArgumentsWhenAsciiArgumentsDisagree() {
    String[] narrow = {"project", "add", MANGLED};
    String[] wide = {"soma.exe", "project", "remove", REAL};
    assertThat(WindowsCommandLine.reconcile(narrow, wide)).isSameAs(narrow);
  }

  @Test
  void isNoOpOffWindowsOrForNull() {
    assertThat(WindowsCommandLine.arguments(null)).isNull();
  }
}
