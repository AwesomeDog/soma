package io.github.awesomedog.soma.infra.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WindowsCommandLineTest {

  private static final String MANGLED = "C:\\WPS\uFFFD\uFFFD\\\uFFFD\uFFFD";
  private static final String REAL = "C:\\WPS云盘\\公司范本";
  private static final String FLATTENED = "C:\\WPS??\\??";

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
  void keepsOriginalArgumentsWhenTheWideReadFailed() {
    String[] narrow = {"project", "add", MANGLED};
    assertThat(WindowsCommandLine.reconcile(narrow, null)).isSameAs(narrow);
  }

  @Test
  void keepsArgumentsTheCodePageFlattenedToQuestionMarks() {
    String[] narrow = {"project", "add", FLATTENED};
    String[] wide = {"soma.exe", "project", "add", REAL};
    assertThat(WindowsCommandLine.reconcile(narrow, wide)).isSameAs(narrow);
  }

  @Test
  void isNoOpForNullArguments() {
    assertThat(WindowsCommandLine.arguments(null)).isNull();
  }

  @Test
  void leavesAsciiArgumentsAlone() {
    // Holds everywhere: off Windows there is nothing to fix, and on Windows an all-ASCII command
    // line is short-circuited before any native call.
    String[] args = {"project", "add", "C:\\soma\\notes", "--name", "law"};
    assertThat(WindowsCommandLine.arguments(args)).isSameAs(args);
  }
}
