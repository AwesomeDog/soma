package io.github.awesomedog.soma.app.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.system.NioProjectScanner.ReadFile;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.support.Hashing;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NioProjectScannerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void recursiveMarkdownIncludeMatchesFilesAtTheProjectRoot() throws Exception {
    var root = Files.createDirectories(temporaryDirectory.resolve("Meetings"));
    Files.writeString(root.resolve("root.md"), "root\n");
    Files.writeString(root.resolve("root.txt"), "not markdown\n");

    var files = scan(new NioProjectScanner(), project(root, List.of("**/*.md"), List.of(), true));

    assertThat(files).extracting(ReadFile::documentPath).containsExactly("root.md");
  }

  @Test
  void appliesHardSkipsIgnoreIncludesAndExcludesInStablePathOrder() throws Exception {
    var root = Files.createDirectories(temporaryDirectory.resolve("docs"));
    Files.createDirectories(root.resolve("a"));
    Files.createDirectories(root.resolve("z"));
    Files.createDirectories(root.resolve("ignored-dir"));
    Files.createDirectories(root.resolve(".git"));
    Files.createDirectories(root.resolve(".hg"));
    Files.createDirectories(root.resolve(".svn"));
    Files.createDirectories(root.resolve(".soma"));
    Files.createDirectories(root.resolve("node_modules"));
    Files.writeString(root.resolve("README.md"), "root\n");
    Files.writeString(root.resolve("a/first.md"), "first\n");
    Files.writeString(root.resolve("a/skip.md"), "skip\n");
    Files.writeString(root.resolve("z/last.md"), "last\n");
    Files.writeString(root.resolve("ignored.md"), "ignored\n");
    Files.writeString(root.resolve("ignored-dir/hidden.md"), "ignored\n");
    Files.writeString(root.resolve("other.txt"), "other\n");
    Files.writeString(root.resolve(".git/hidden.md"), "hidden\n");
    Files.writeString(root.resolve(".hg/hidden.md"), "hidden\n");
    Files.writeString(root.resolve(".svn/hidden.md"), "hidden\n");
    Files.writeString(root.resolve(".soma/local.md"), "hidden\n");
    Files.writeString(root.resolve("node_modules/hidden.md"), "hidden\n");
    Files.writeString(root.resolve(".gitignore"), "ignored.md\nignored-dir/\n");

    var files =
        scan(
            new NioProjectScanner(),
            project(root, List.of("**/*.md"), List.of("**/skip.md"), true));

    assertThat(files)
        .extracting(ReadFile::documentPath)
        .containsExactly("README.md", "a/first.md", "z/last.md");
    assertThat(files)
        .allMatch(file -> file.decodedText() != null && file.fileType() == FileType.TEXT);
  }

  @Test
  void detectsMagicStrictUtf8AndControlHeavyBinaryContent() throws Exception {
    var root = Files.createDirectories(temporaryDirectory.resolve("docs"));
    Files.write(root.resolve("empty"), new byte[0]);
    Files.writeString(root.resolve("fake.pdf"), "plain text\n");
    Files.write(root.resolve("real-pdf.txt"), "%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
    Files.write(
        root.resolve("image.txt"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
    Files.write(root.resolve("audio.bin"), new byte[] {'I', 'D', '3', 0});
    Files.write(
        root.resolve("video.bin"),
        new byte[] {0, 0, 0, 20, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'});
    Files.write(root.resolve("invalid-utf8"), new byte[] {(byte) 0xc3, 0x28});
    Files.write(root.resolve("controls"), new byte[] {0, 1, 2, 3, 'a'});
    var boundary = "a".repeat(8191) + "界";
    Files.writeString(root.resolve("boundary.txt"), boundary);

    var files = scan(new NioProjectScanner(), project(root, List.of("**/*"), List.of(), false));

    assertThat(files)
        .extracting(ReadFile::documentPath, ReadFile::fileType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("audio.bin", FileType.AUDIO),
            org.assertj.core.groups.Tuple.tuple("boundary.txt", FileType.TEXT),
            org.assertj.core.groups.Tuple.tuple("controls", FileType.OTHER),
            org.assertj.core.groups.Tuple.tuple("empty", FileType.TEXT),
            org.assertj.core.groups.Tuple.tuple("fake.pdf", FileType.TEXT),
            org.assertj.core.groups.Tuple.tuple("image.txt", FileType.IMAGE),
            org.assertj.core.groups.Tuple.tuple("invalid-utf8", FileType.OTHER),
            org.assertj.core.groups.Tuple.tuple("real-pdf.txt", FileType.PDF),
            org.assertj.core.groups.Tuple.tuple("video.bin", FileType.VIDEO));
    assertThat(file(files, "empty").decodedText()).isEmpty();
    assertThat(file(files, "fake.pdf").decodedText()).isEqualTo("plain text\n");
    assertThat(file(files, "boundary.txt").decodedText()).isEqualTo(boundary);
    assertThat(file(files, "real-pdf.txt").sourceHash())
        .isEqualTo(Hashing.sha256HexUtf8("%PDF-1.7\n"));
    assertThat(files)
        .filteredOn(file -> file.fileType() != FileType.TEXT)
        .allMatch(
            file ->
                file.decodedText() == null
                    && (file.fileType() == FileType.OTHER || file.sourceHash() != null));
  }

  @Test
  void recordsUnreadableContentWhenMetadataCanStillBeRead() throws Exception {
    var root = Files.createDirectories(temporaryDirectory.resolve("docs"));
    var file = Files.writeString(root.resolve("private.txt"), "private\n");
    FileStore store = Files.getFileStore(file);
    Assumptions.assumeTrue(store.supportsFileAttributeView("posix"));
    Set<PosixFilePermission> original = Files.getPosixFilePermissions(file);
    try {
      Files.setPosixFilePermissions(file, Set.of());
      Assumptions.assumeFalse(Files.isReadable(file));

      assertThat(scan(new NioProjectScanner(), project(root, List.of("**/*"), List.of(), false)))
          .singleElement()
          .satisfies(
              scanned -> {
                assertThat(scanned.documentPath()).isEqualTo("private.txt");
                assertThat(scanned.sizeBytes()).isEqualTo(Files.size(file));
                assertThat(scanned.modifiedTimeNs()).isNotNegative();
                assertThat(scanned.sourceHash()).isNull();
                assertThat(scanned.decodedText()).isNull();
              });
    } finally {
      Files.setPosixFilePermissions(file, original);
    }
  }

  @Test
  void rejectsUnreadableOrMissingRootWithStructuredInvalidRequest() throws IOException {
    var missing = temporaryDirectory.resolve("missing").toAbsolutePath();

    assertThatThrownBy(
            () -> scan(new NioProjectScanner(), project(missing, List.of("**/*"), List.of(), true)))
        .isInstanceOfSatisfying(
            AppException.class,
            error -> assertThat(error.error().code()).isEqualTo(AppError.Code.INVALID_REQUEST));
  }

  private static ProjectConfig project(
      Path root, List<String> include, List<String> exclude, boolean ignoreFiles) {
    return new ProjectConfig(
        new ProjectName("docs"),
        root.toAbsolutePath().normalize(),
        include,
        exclude,
        true,
        ignoreFiles);
  }

  private static ReadFile file(List<ReadFile> files, String documentPath) {
    return files.stream()
        .filter(file -> file.documentPath().equals(documentPath))
        .findFirst()
        .orElseThrow();
  }

  private static List<ReadFile> scan(NioProjectScanner scanner, ProjectConfig project) {
    return scanner.scan(project, Map.of(), ignored -> {}, ignored -> {}).readFiles();
  }
}
