package io.github.awesomedog.soma.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.awesomedog.soma.exec.Invocation;
import java.util.List;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class SomaCommandTest {

  @Test
  void acceptsEveryDocumentedCommandAndOptionForm() {
    assertAccepted(workspaceProjectAndSyncCommandForms());
    assertAccepted(searchCommandForms());
    assertAccepted(searchGetServerAndContextCommandForms());
    assertAccepted(initAndSystemCommandForms());
    assertAccepted(httpCommandForms());
  }

  private static List<String[]> workspaceProjectAndSyncCommandForms() {
    return List.of(
        args("-w", "work", "-v", "--no-color", "status"),
        args("project", "list"),
        args("project", "ls", "--default-search"),
        args("project", "files", "soma://notes/src"),
        args(
            "project",
            "add",
            ".",
            "--name",
            "source",
            "--include",
            "**/*.java",
            "--include",
            "**/*.md",
            "--exclude",
            "target/**",
            "--exclude",
            ".git/**",
            "--no-ignore-files",
            "--no-default-search"),
        args("project", "update", "docs", "archive", "--default-search"),
        args("project", "update", "docs", "--no-default-search"),
        args("project", "remove", "archive"),
        args("project", "rename", "docs", "product-docs"),
        args("project", "show", "docs"),
        args("sync"));
  }

  private static List<String[]> searchCommandForms() {
    return List.of(
        args(
            "search",
            "how does auth work",
            "--lex",
            "auth",
            "--vec",
            "authentication flow",
            "--hyde",
            "The document describes authentication.",
            "--intent",
            "API authentication",
            "-p",
            "docs",
            "-p",
            "runbooks",
            "--limit",
            "10",
            "--full",
            "--line-number",
            "-f",
            "json"),
        args("s", "--lex", "CAP theorem", "--no-limit", "--format", "paths"),
        args("search", "hybrid", "lexical", "--intent", "literal subcommand name"),
        args("search", "h", "--hyde", "A hypothetical passage."),
        args("search", "lexical", "deployment guide", "-p", "docs", "--limit", "10"),
        args("s", "l", "rate limiter", "--full", "--line-number"),
        args("search", "vector", "what happens when a pod crashes", "--intent", "operations"),
        args("s", "v", "login failures", "-f", "md"));
  }

  private static List<String[]> searchGetServerAndContextCommandForms() {
    return List.of(
        args(
            "get",
            "@a1b2c3",
            "docs/api.md",
            "./indexed-note.md",
            "--start-line",
            "120",
            "--max-lines",
            "40",
            "--line-number",
            "--max-size",
            "20480",
            "--format",
            "csv"),
        args("get", "docs/api.md", "--format", "paths", "--max-size", "1"),
        args("context", "list"),
        args("context", "ls", "-p", "docs", "-p", "runbooks"),
        args("context", "-p", "docs", "set", "/api", "API documentation"),
        args("context", "-p", "docs", "remove", "/api"));
  }

  private static List<String[]> initAndSystemCommandForms() {
    return List.of(
        args("init"),
        args("system", "pull"),
        args("system", "pull", "--refresh"),
        args("system", "pull", "--export", "soma-artifacts.zip"),
        args("system", "pull", "--import", "soma-artifacts.zip"),
        args("system", "scan"),
        args("system", "extract"),
        args("system", "embed", "-p", "docs", "-p", "runbooks"),
        args("system", "clean"));
  }

  private static List<String[]> httpCommandForms() {
    return List.of(
        args("server", "--port", "8080", "--auto-sync"),
        args("server", "http", "--port", "8181", "--auto-sync"),
        args("server", "--port", "8080", "--auto-sync", "http"),
        args("server", "--port", "8080", "--auto-sync", "http", "--port", "8181", "--auto-sync"));
  }

  private static void assertAccepted(List<String[]> commandForms) {
    for (var argv : commandForms) {
      assertThat(commandLine().parseArgs(argv)).as(String.join(" ", argv)).isNotNull();
    }
  }

  private static CommandLine commandLine() {
    var invocation = Invocation.captured();
    return new CommandLine(new SomaCommand(invocation))
        .setOut(invocation.out())
        .setErr(invocation.err());
  }

  private static String[] args(String... args) {
    return args;
  }
}
