package io.github.awesomedog.soma.exec;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.project.ProjectView;
import io.github.awesomedog.soma.cli.context.ContextCommand.ContextResult;
import io.github.awesomedog.soma.cli.context.ContextCommand.ContextRow;
import io.github.awesomedog.soma.cli.get.GetCommand;
import io.github.awesomedog.soma.cli.project.ProjectCommand.ProjectFiles;
import io.github.awesomedog.soma.cli.project.ProjectCommand.ProjectList;
import io.github.awesomedog.soma.infra.logging.Logging;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.PathSupport;
import io.micronaut.context.ApplicationContext;
import java.io.File;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandContractTest {

  @TempDir Path tempDir;

  @Test
  void persistsProjectMutationsAndScansTheWorkspace() throws Exception {
    var root = createProjectFixture();
    try (var context = context(workspace(tempDir))) {
      var runner = context.getBean(CommandRunner.class);

      assertInvalidIncludeGlobIsRejected(runner, root);
      addProjectAndAssertInitialIndex(runner, root);
      assertIndexedAndMultiFileGet(runner, root);
      assertVirtualPathGlobBehavior(runner, root);
      assertProjectFileListing(runner);
      assertAmbiguousDocumentGet(runner);
      assertUnavailableDocumentGet(runner, root);
      assertProjectList(runner);
      configureAndAssertProjectContext(runner);
      disableDefaultSearchAndAssertConfiguration(runner);
      renameProjectAndAssertContext(runner);
      removeProjectAndAssertCleanup(runner);
    } finally {
      Logging.close();
    }
  }

  @Test
  void managesGlobalAndRepeatedProjectContextsAtomically() throws Exception {
    var docsRoot = Files.createDirectories(tempDir.resolve("docs"));
    var runbooksRoot = Files.createDirectories(tempDir.resolve("runbooks"));
    var workspace = workspace(tempDir);
    try (var context = context(workspace)) {
      var runner = context.getBean(CommandRunner.class);
      assertThat(run(runner, "project", "add", docsRoot.toString(), "--name", "docs").exitCode())
          .isZero();
      assertThat(
              run(runner, "project", "add", runbooksRoot.toString(), "--name", "runbooks")
                  .exitCode())
          .isZero();

      assertThat(run(runner, "context", "set", "/", "global context").exitCode()).isZero();
      var sharedContext =
          run(runner, "context", "-p", "docs", "-p", "runbooks", "set", "/api", "shared context");
      assertThat(sharedContext.exitCode()).isZero();
      assertThat(run(runner, "context", "list").invocation().result())
          .isInstanceOfSatisfying(
              ContextResult.class,
              result ->
                  assertThat(result.contexts())
                      .containsExactly(
                          new ContextRow(null, "/", "global context"),
                          new ContextRow("docs", "/api", "shared context"),
                          new ContextRow("runbooks", "/api", "shared context")));

      assertThat(run(runner, "context", "set", "/api", "docs context", "-p", "docs").exitCode())
          .isZero();
      var docsContexts = run(runner, "context", "list", "-p", "docs");
      assertThat(docsContexts.exitCode()).isZero();
      assertThat(docsContexts.invocation().recordedError()).isNull();
      assertThat(docsContexts.invocation().result())
          .isInstanceOfSatisfying(
              ContextResult.class,
              result ->
                  assertThat(result.contexts())
                      .containsExactly(new ContextRow("docs", "/api", "docs context")));
      var runbookContexts = run(runner, "context", "list", "-p", "runbooks");
      assertThat(runbookContexts.exitCode()).isZero();
      assertThat(runbookContexts.invocation().recordedError()).isNull();
      assertThat(runbookContexts.invocation().result())
          .isInstanceOfSatisfying(
              ContextResult.class,
              result ->
                  assertThat(result.contexts())
                      .containsExactly(new ContextRow("runbooks", "/api", "shared context")));

      var removed = run(runner, "context", "remove", "/api", "-p", "docs", "-p", "runbooks");
      assertThat(removed.exitCode()).isZero();
      assertThat(removed.invocation().result())
          .isInstanceOfSatisfying(
              ContextResult.class, result -> assertThat(result.contexts()).hasSize(2));

      var configBeforeNoOp = Files.readString(workspace.configFile());
      var missing = run(runner, "context", "-p", "docs", "remove", "/missing");
      assertThat(missing.exitCode()).isZero();
      assertThat(missing.invocation().result())
          .isInstanceOfSatisfying(
              ContextResult.class, result -> assertThat(result.contexts()).isEmpty());
      assertThat(Files.readString(workspace.configFile())).isEqualTo(configBeforeNoOp);

      var unknown = run(runner, "context", "-p", "missing", "list");
      assertThat(unknown.exitCode()).isEqualTo(1);
      assertThat(unknown.invocation().recordedError().code()).isEqualTo(AppError.Code.NOT_FOUND);
    } finally {
      Logging.close();
    }
  }

  @Test
  void listsYamlProjectsWithoutReadingTheIndex() throws Exception {
    var root = Files.createDirectories(tempDir.resolve("docs"));
    var configFile = tempDir.resolve("config/soma/main.yml");
    var databaseFile = tempDir.resolve("state/soma/main.sqlite");
    Files.createDirectories(configFile.getParent());
    Files.createDirectories(databaseFile.getParent());
    Files.writeString(
        configFile,
        """
        version: 1
        projects:
          - name: docs
            root: %s
            include: ["**/*"]
            exclude: []
            default-search: true
            ignore-files: true
        context: []
        """
            .formatted(PathSupport.toPortableString(root)));
    Files.writeString(databaseFile, "not a sqlite database");

    try (var context = context(workspace(tempDir))) {
      var result = run(context.getBean(CommandRunner.class), "project", "list");

      assertThat(result.exitCode()).isZero();
      assertThat(result.invocation().result())
          .isInstanceOfSatisfying(
              ProjectList.class,
              projects -> assertThat(projects.projects().getFirst().stats()).isNull());
      assertThat(databaseFile).hasContent("not a sqlite database");
    } finally {
      Logging.close();
    }
  }

  private Path createProjectFixture() throws Exception {
    var root = Files.createDirectories(tempDir.resolve("My Files"));
    Files.writeString(root.resolve("indexed.txt"), "source body");
    Files.writeString(root.resolve("same-a.txt"), "shared body");
    Files.writeString(root.resolve("same-b.txt"), "shared body");
    Files.writeString(root.resolve("same-[a].txt"), "literal bracket body");
    Files.writeString(root.resolve("sized.txt"), "x".repeat(1536));
    Files.writeString(
        Files.createDirectories(root.resolve("100%_literal")).resolve("only.txt"),
        "literal prefix body");
    Files.writeString(root.resolve("manual.pdf"), "%PDF-1.7\n");
    Files.write(root.resolve("unsupported.bin"), new byte[] {0, 1, 2, 3});
    return root;
  }

  private static void assertInvalidIncludeGlobIsRejected(CommandRunner runner, Path root) {
    var invalidGlob =
        run(runner, "project", "add", root.toString(), "--name", "invalid", "--include", "[");
    assertThat(invalidGlob.exitCode()).isEqualTo(2);
    assertThat(invalidGlob.invocation().recordedError())
        .isInstanceOfSatisfying(
            AppError.class,
            error -> assertThat(error.code()).isEqualTo(AppError.Code.INVALID_REQUEST));
  }

  private static void addProjectAndAssertInitialIndex(CommandRunner runner, Path root) {
    var add = run(runner, "project", "add", root.toString(), "--name", "My Docs");
    assertThat(add.exitCode()).isZero();
  }

  private static void assertIndexedAndMultiFileGet(CommandRunner runner, Path root)
      throws Exception {
    var indexedFile = root.resolve("indexed.txt");
    Files.writeString(indexedFile, "changed filesystem body");
    var indexedGet = run(runner, "get", indexedFile.toString());
    assertThat(indexedGet.exitCode()).isZero();
    assertThat(indexedGet.invocation().result())
        .isInstanceOfSatisfying(
            GetCommand.Result.class,
            result ->
                assertThat(result.items())
                    .singleElement()
                    .satisfies(item -> assertThat(item.body()).isEqualTo("source body")));

    var multiGet = run(runner, "get", "My-Docs/same-a.txt", "My-Docs/same-b.txt");
    assertThat(multiGet.invocation().result())
        .isInstanceOfSatisfying(
            GetCommand.Result.class,
            result ->
                assertThat(result.items())
                    .containsExactly(
                        new GetCommand.Item("soma://My-Docs/same-a.txt", "shared body", ""),
                        new GetCommand.Item("soma://My-Docs/same-b.txt", "shared body", "")));
  }

  private static void assertVirtualPathGlobBehavior(CommandRunner runner, Path root) {
    var invalidPath =
        run(runner, "get", root.resolve("indexed.txt").toString(), "soma://My-Docs/../indexed.txt");
    assertThat(invalidPath.exitCode()).isEqualTo(3);
    assertThat(invalidPath.invocation().result())
        .isInstanceOfSatisfying(
            GetCommand.Result.class,
            result ->
                assertThat(result.items())
                    .singleElement()
                    .satisfies(item -> assertThat(item.body()).isEqualTo("source body")));

    var characterClass = run(runner, "get", "soma://My-Docs/same-[ab].txt");
    assertThat(characterClass.exitCode()).isZero();
    assertThat(characterClass.invocation().result())
        .isInstanceOfSatisfying(
            GetCommand.Result.class,
            result ->
                assertThat(result.items())
                    .extracting(GetCommand.Item::virtualPath)
                    .containsExactly("soma://My-Docs/same-a.txt", "soma://My-Docs/same-b.txt"));

    var escapedBracket = run(runner, "get", "soma://My-Docs/same-[[]a].txt");
    assertThat(escapedBracket.exitCode()).isZero();
    assertThat(escapedBracket.invocation().result())
        .isInstanceOfSatisfying(
            GetCommand.Result.class,
            result ->
                assertThat(result.items())
                    .singleElement()
                    .satisfies(item -> assertThat(item.body()).isEqualTo("literal bracket body")));

    var invalidGlob = run(runner, "get", "soma://My-Docs/same-[.txt");
    assertThat(invalidGlob.exitCode()).isEqualTo(1);
    assertThat(invalidGlob.invocation().recordedError()).isNull();
  }

  private static void assertProjectFileListing(CommandRunner runner) {
    var files = run(runner, "project", "files", "My-Docs");
    assertThat(files.exitCode()).isZero();
    assertThat(files.invocation().result())
        .isInstanceOfSatisfying(
            ProjectFiles.class,
            result ->
                assertThat(result.files())
                    .extracting(file -> file.virtualPath())
                    .contains("soma://My-Docs/indexed.txt", "soma://My-Docs/sized.txt"));

    var nonCanonicalProject = run(runner, "project", "files", "My Docs");
    assertThat(nonCanonicalProject.exitCode()).isEqualTo(1);

    var literalPrefix = run(runner, "project", "files", "My-Docs/100%_literal");
    assertThat(literalPrefix.invocation().result())
        .isInstanceOfSatisfying(
            ProjectFiles.class,
            result ->
                assertThat(result.files())
                    .extracting(file -> file.virtualPath())
                    .containsExactly("soma://My-Docs/100%_literal/only.txt"));
  }

  private static void assertAmbiguousDocumentGet(CommandRunner runner) {
    var target = "@" + Hashing.sha256HexUtf8("shared body").substring(0, 6);
    assertAmbiguousTextResult(runner, target);
  }

  private static void assertAmbiguousTextResult(CommandRunner runner, String target) {
    var result = run(runner, "get", target);
    assertThat(result.exitCode()).isEqualTo(1);
    assertThat(result.invocation().recordedError()).isNull();
  }

  private static void assertUnavailableDocumentGet(CommandRunner runner, Path root) {
    var pending = run(runner, "get", "soma://My-Docs/manual.pdf");
    assertThat(pending.exitCode()).isEqualTo(1);

    var missing = run(runner, "get", root.resolve("missing.txt").toString());
    assertThat(missing.exitCode()).isEqualTo(1);
    assertThat(missing.invocation().recordedError()).isNull();
  }

  private static void assertProjectList(CommandRunner runner) {
    var list = run(runner, "project", "list");
    assertThat(list.exitCode()).isZero();
    assertThat(list.invocation().result())
        .isInstanceOfSatisfying(
            ProjectList.class,
            data -> {
              assertThat(data.projects()).extracting(ProjectView::name).containsExactly("My-Docs");
              assertThat(data.projects().getFirst().stats()).isNull();
            });
  }

  private static void configureAndAssertProjectContext(CommandRunner runner) {
    assertThat(run(runner, "context", "-p", "My-Docs", "set", "/", "API docs").exitCode()).isZero();
    assertThat(run(runner, "context", "-p", "My-Docs", "list").invocation().result())
        .isInstanceOfSatisfying(
            ContextResult.class,
            result ->
                assertThat(result.contexts())
                    .containsExactly(new ContextRow("My-Docs", "/", "API docs")));
    assertThat(run(runner, "get", "My-Docs/indexed.txt").invocation().result())
        .isInstanceOfSatisfying(
            GetCommand.Result.class,
            result ->
                assertThat(result.items())
                    .singleElement()
                    .satisfies(
                        item -> {
                          assertThat(item.context()).isEqualTo("API docs");
                          assertThat(item.body()).isEqualTo("source body");
                        }));

    if (File.separatorChar == '\\') {
      assertWindowsContextPaths(runner);
    }
  }

  private static void assertWindowsContextPaths(CommandRunner runner) {
    assertThat(
            run(runner, "context", "-p", "My-Docs", "set", "\\api", "Windows API docs").exitCode())
        .isZero();
    assertThat(run(runner, "context", "-p", "My-Docs", "list").invocation().result())
        .isInstanceOfSatisfying(
            ContextResult.class,
            result ->
                assertThat(result.contexts())
                    .contains(new ContextRow("My-Docs", "/api", "Windows API docs")));

    var remove = run(runner, "context", "-p", "My-Docs", "remove", "\\api");
    assertThat(remove.exitCode()).isZero();
    assertThat(run(runner, "context", "-p", "My-Docs", "list").invocation().result())
        .isInstanceOfSatisfying(
            ContextResult.class,
            result ->
                assertThat(result.contexts())
                    .extracting(ContextRow::text)
                    .doesNotContain("Windows API docs"));
  }

  private static void disableDefaultSearchAndAssertConfiguration(CommandRunner runner) {
    var update = run(runner, "project", "update", "My-Docs", "--no-default-search");
    assertThat(update.exitCode()).isZero();
    assertThat(run(runner, "project", "show", "My-Docs").invocation().result())
        .isInstanceOfSatisfying(
            ProjectList.class,
            result ->
                assertThat(result.projects())
                    .singleElement()
                    .satisfies(project -> assertThat(project.defaultSearch()).isFalse()));
  }

  private static void renameProjectAndAssertContext(CommandRunner runner) {
    var rename = run(runner, "project", "rename", "My-Docs", "Renamed Docs");
    assertThat(rename.exitCode()).isZero();
    assertThat(run(runner, "context", "-p", "Renamed-Docs", "list").invocation().result())
        .isInstanceOfSatisfying(
            ContextResult.class,
            result ->
                assertThat(result.contexts())
                    .containsExactly(new ContextRow("Renamed-Docs", "/", "API docs")));
  }

  private static void removeProjectAndAssertCleanup(CommandRunner runner) {
    var remove = run(runner, "project", "remove", "Renamed-Docs");
    assertThat(remove.exitCode()).isZero();
    assertThat(run(runner, "project", "list").invocation().result())
        .isInstanceOfSatisfying(
            ProjectList.class, result -> assertThat(result.projects()).isEmpty());
    assertThat(run(runner, "context", "list").invocation().result())
        .isInstanceOfSatisfying(
            ContextResult.class, result -> assertThat(result.contexts()).isEmpty());
  }

  @Test
  void preservesProjectConfigAndReportsTheOriginalScanFailure() throws Exception {
    var docsRoot = Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(docsRoot.resolve("guide.txt"), "guide");
    var disappearingRoot = Files.createDirectories(tempDir.resolve("disappearing"));
    var disappearingFile = Files.writeString(disappearingRoot.resolve("note.txt"), "note");
    try (var context = context(workspace(tempDir))) {
      var runner = context.getBean(CommandRunner.class);
      assertThat(run(runner, "project", "add", docsRoot.toString(), "--name", "docs").exitCode())
          .isZero();
      assertThat(
              run(runner, "project", "add", disappearingRoot.toString(), "--name", "disappearing")
                  .exitCode())
          .isZero();
      Files.delete(disappearingFile);
      Files.delete(disappearingRoot);

      var update = run(runner, "project", "update", "docs", "--no-default-search");

      assertThat(update.exitCode()).isEqualTo(2);
      assertThat(update.invocation().recordedError())
          .isInstanceOfSatisfying(
              AppError.class,
              error -> {
                assertThat(error.code()).isEqualTo(AppError.Code.INVALID_REQUEST);
                assertThat(error.details()).isNull();
              });
      assertThat(run(runner, "project", "show", "docs").invocation().result())
          .isInstanceOfSatisfying(
              ProjectList.class,
              result ->
                  assertThat(result.projects())
                      .singleElement()
                      .satisfies(project -> assertThat(project.defaultSearch()).isFalse()));
    } finally {
      Logging.close();
    }
  }

  @Test
  void prefersIndexedContentForAProjectFileSymlinkTargetingOutsideTheRoot() throws Exception {
    var root = Files.createDirectories(tempDir.resolve("docs"));
    var outside = Files.writeString(tempDir.resolve("outside.txt"), "indexed body");
    var link = root.resolve("linked.txt");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | FileSystemException e) {
      Assumptions.abort("Symbolic links are not available: " + e.getMessage());
    }

    try (var context = context(workspace(tempDir))) {
      var runner = context.getBean(CommandRunner.class);
      assertThat(run(runner, "project", "add", root.toString(), "--name", "docs").exitCode())
          .isZero();

      Files.writeString(outside, "changed filesystem body");
      var result = run(runner, "get", link.toString());

      assertThat(result.exitCode()).isZero();
      assertThat(result.invocation().result())
          .isInstanceOfSatisfying(
              GetCommand.Result.class,
              data ->
                  assertThat(data.items())
                      .singleElement()
                      .satisfies(item -> assertThat(item.body()).isEqualTo("indexed body")));
    } finally {
      Logging.close();
    }
  }

  @Test
  void keepsPosixBackslashFileNamesDistinctFromNestedPaths() throws Exception {
    Assumptions.assumeFalse(File.separatorChar == '\\');
    var root = Files.createDirectories(tempDir.resolve("docs"));
    Files.createDirectories(root.resolve("a"));
    var literalFile = root.resolve("a\\b.md");
    Files.writeString(literalFile, "literal filename body");
    Files.writeString(root.resolve("a/b.md"), "nested path body");

    try (var context = context(workspace(tempDir))) {
      var runner = context.getBean(CommandRunner.class);
      assertThat(run(runner, "project", "add", root.toString(), "--name", "docs").exitCode())
          .isZero();

      var abbreviated = run(runner, "get", "docs/a\\b.md");
      var full = run(runner, "get", "soma://docs/a\\b.md");
      Files.writeString(literalFile, "changed filesystem body");
      var filesystem = run(runner, "get", literalFile.toString());

      assertThat(abbreviated.exitCode()).isZero();
      assertThat(abbreviated.invocation().result())
          .isInstanceOfSatisfying(
              GetCommand.Result.class,
              result ->
                  assertThat(result.items())
                      .singleElement()
                      .satisfies(
                          item -> assertThat(item.body()).isEqualTo("literal filename body")));
      assertThat(full.exitCode()).isZero();
      assertThat(full.invocation().result())
          .isInstanceOfSatisfying(
              GetCommand.Result.class,
              result ->
                  assertThat(result.items())
                      .singleElement()
                      .satisfies(
                          item -> assertThat(item.body()).isEqualTo("literal filename body")));
      assertThat(filesystem.exitCode()).isZero();
      assertThat(filesystem.invocation().result())
          .isInstanceOfSatisfying(
              GetCommand.Result.class,
              result ->
                  assertThat(result.items())
                      .singleElement()
                      .satisfies(
                          item -> assertThat(item.body()).isEqualTo("literal filename body")));
    } finally {
      Logging.close();
    }
  }

  @Test
  void initializesALocalWorkspaceIdempotentlyWhenWorkspaceOptionIsIgnored() throws Exception {
    var workspace = workspace(tempDir);
    try (var context = context(workspace)) {
      var runner = context.getBean(CommandRunner.class);

      var first = run(runner, "init", "--workspace", "other");
      assertThat(first.exitCode()).isZero();
      assertThat(first.invocation().recordedError()).isNull();
      assertThat(tempDir.resolve(".soma/local.yml")).isRegularFile();
      assertThat(tempDir.resolve(".soma/local.sqlite")).doesNotExist();
      var emptyConfig = Files.readString(tempDir.resolve(".soma/local.yml"));
      assertThat(emptyConfig).contains("projects: []");

      var second = run(runner, "init");
      assertThat(second.exitCode()).isZero();
      assertThat(second.invocation().recordedError()).isNull();
      assertThat(Files.readString(tempDir.resolve(".soma/local.yml"))).isEqualTo(emptyConfig);

      assertThat(run(runner, "project", "add", tempDir.toString(), "--name", "local").exitCode())
          .isZero();
      assertThat(tempDir.resolve(".soma/local.sqlite")).isRegularFile();
      var configured = Files.readString(tempDir.resolve(".soma/local.yml"));
      assertThat(configured).contains("name: local", "root: .");

      assertThat(run(runner, "init").exitCode()).isZero();
      assertThat(Files.readString(tempDir.resolve(".soma/local.yml"))).isEqualTo(configured);

    } finally {
      Logging.close();
    }
  }

  @Test
  void rejectsAnInvalidExistingLocalWorkspaceWithoutReplacingIt() throws Exception {
    var configFile = tempDir.resolve(".soma/local.yml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, "projects: [\n");
    try (var context = context(workspace(tempDir))) {
      var result = run(context.getBean(CommandRunner.class), "init");

      assertThat(result.exitCode()).isEqualTo(1);
      assertThat(result.invocation().recordedError())
          .isInstanceOfSatisfying(
              AppError.class,
              error -> assertThat(error.code()).isEqualTo(AppError.Code.CONFIG_ERROR));
      assertThat(configFile).hasContent("projects: [\n");
      assertThat(tempDir.resolve(".soma/local.sqlite")).doesNotExist();
    } finally {
      Logging.close();
    }
  }

  @Test
  void preservesFilesystemCaseForDirectoryLocalProjectRootsOnWindows() throws Exception {
    Assumptions.assumeTrue(File.separatorChar == '\\');
    Files.createDirectories(tempDir.resolve("Docs"));
    try (var context = context(workspace(tempDir))) {
      var runner = context.getBean(CommandRunner.class);

      assertThat(run(runner, "init").exitCode()).isZero();
      assertThat(
              run(runner, "project", "add", tempDir.resolve("docs").toString(), "--name", "docs")
                  .exitCode())
          .isZero();
      assertThat(Files.readString(tempDir.resolve(".soma/local.yml"))).contains("root: ./Docs");
    } finally {
      Logging.close();
    }
  }

  @Test
  void rejectsUnindexedLocalFiles() throws Exception {
    var file = tempDir.resolve("note.txt");
    Files.writeString(file, "alpha\nbeta\ngamma\n");
    try (var context = context(workspace(tempDir))) {
      var runner = context.getBean(CommandRunner.class);
      var result = run(runner, "get", file.toString());
      assertThat(result.exitCode()).isEqualTo(1);
      assertThat(result.invocation().recordedError()).isNull();
      assertThat(result.invocation().result())
          .isInstanceOfSatisfying(
              GetCommand.Result.class, data -> assertThat(data.items()).isEmpty());
    } finally {
      Logging.close();
    }
  }

  private ApplicationContext context(ActiveWorkspace workspace) {
    return ApplicationContext.builder().singletons(workspace).start();
  }

  private ActiveWorkspace workspace(Path root) {
    var environment =
        Map.of(
            "XDG_CONFIG_HOME", root.resolve("config").toString(),
            "XDG_STATE_HOME", root.resolve("state").toString());
    return new ActiveWorkspace(new WorkspaceResolver(environment, root, root.resolve("home")));
  }

  private static CommandResult run(CommandRunner runner, String... argv) {
    var invocation = Invocation.captured();
    return new CommandResult(runner.run(argv, invocation), invocation);
  }

  private record CommandResult(int exitCode, Invocation invocation) {}
}
