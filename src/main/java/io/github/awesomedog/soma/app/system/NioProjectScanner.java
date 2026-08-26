package io.github.awesomedog.soma.app.system;

import static io.github.awesomedog.soma.app.common.AppError.Code.INVALID_REQUEST;
import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.document.FileSignatures;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.PathSupport;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.eclipse.jgit.ignore.IgnoreNode;

@Singleton
public final class NioProjectScanner {

  private static final Set<String> HARD_SKIPPED_DIRECTORIES =
      Set.of(".git", ".hg", ".svn", ".soma", "node_modules");
  private static final int TYPE_PREFIX_BYTES = 8192;
  private static final double MAX_TEXT_CONTROL_RATIO = 0.02d;

  public ScanResult scan(
      ProjectConfig project,
      Map<String, SourceMetadata> indexedFiles,
      Consumer<String> warnings,
      Consumer<Long> progress) {
    var root = project.root();
    if (!Files.isDirectory(root) || !Files.isReadable(root)) {
      throw new AppException(
          INVALID_REQUEST,
          "Project root is not a readable directory: " + root,
          "Choose an existing readable directory.");
    }

    try {
      var readFiles = new ArrayList<ReadFile>();
      var unchangedDocumentPaths = new ArrayList<String>();
      var scan =
          new ScanContext(
              root,
              project.ignoreFiles(),
              project.include().stream().map(Glob::compile).toList(),
              project.exclude().stream().map(Glob::compile).toList(),
              indexedFiles,
              readFiles,
              unchangedDocumentPaths,
              warnings == null ? ignored -> {} : warnings,
              progress == null ? ignored -> {} : progress);
      var ignores = project.ignoreFiles() ? List.of(readIgnoreFile(root)) : List.<IgnoreFrame>of();
      scanDirectory(scan, root, ignores);
      readFiles.sort(Comparator.comparing(ReadFile::documentPath));
      unchangedDocumentPaths.sort(Comparator.naturalOrder());
      return new ScanResult(readFiles, unchangedDocumentPaths);
    } catch (IOException e) {
      throw new AppException(
          OPERATION_FAILED,
          "Could not scan project root: " + root,
          "Check filesystem permissions, then run `soma sync` again.",
          e);
    }
  }

  private void scanDirectory(ScanContext scan, Path directory, List<IgnoreFrame> ignoreFrames)
      throws IOException {
    var children = new ArrayList<Path>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      stream.forEach(children::add);
    }
    children.sort(Comparator.comparing(path -> relativePath(scan.root(), path)));

    for (var child : children) {
      BasicFileAttributes attributes;
      try {
        attributes = Files.readAttributes(child, BasicFileAttributes.class, NOFOLLOW_LINKS);
      } catch (IOException | SecurityException e) {
        scan.warnings()
            .accept(
                "Skipped "
                    + relativePath(scan.root(), child)
                    + ": could not read file metadata: "
                    + e.getMessage());
        continue;
      }

      if (attributes.isSymbolicLink()) {
        if (Files.isDirectory(child)) {
          continue;
        }
        try {
          attributes = Files.readAttributes(child, BasicFileAttributes.class);
        } catch (IOException | SecurityException e) {
          scan.warnings()
              .accept(
                  "Skipped "
                      + relativePath(scan.root(), child)
                      + ": could not read file metadata: "
                      + e.getMessage());
          continue;
        }
      }

      if (attributes.isDirectory()) {
        if (HARD_SKIPPED_DIRECTORIES.contains(child.getFileName().toString())
            || scan.useIgnoreFiles() && ignored(ignoreFrames, child, true)) {
          continue;
        }
        var childFrames = ignoreFrames;
        if (scan.useIgnoreFiles()) {
          childFrames = new ArrayList<>(ignoreFrames);
          childFrames.add(readIgnoreFile(child));
        }
        scanDirectory(scan, child, childFrames);
        continue;
      }

      if (!attributes.isRegularFile()
          || scan.useIgnoreFiles() && ignored(ignoreFrames, child, false)) {
        continue;
      }
      var documentPath = relativePath(scan.root(), child);
      if (!matchesAny(scan.includes(), documentPath) || matchesAny(scan.excludes(), documentPath)) {
        continue;
      }

      var metadata = scan.indexedFiles().get(documentPath);
      var modifiedTimeNs = modifiedTimeNs(attributes.lastModifiedTime().toInstant());
      if (metadata != null
          && metadata.modifiedTimeNs() == modifiedTimeNs
          && metadata.sizeBytes() == attributes.size()) {
        scan.unchangedDocumentPaths().add(documentPath);
      } else {
        scan.readFiles()
            .add(readFile(child, documentPath, attributes.size(), modifiedTimeNs, scan.warnings()));
      }
      scan.progress().accept((long) scan.readFiles().size() + scan.unchangedDocumentPaths().size());
    }
  }

  private static ReadFile readFile(
      Path source,
      String documentPath,
      long sizeBytes,
      long modifiedTimeNs,
      Consumer<String> warnings) {
    if (!Files.isReadable(source)) {
      warnings.accept("Could not inspect " + documentPath + ": file is not readable.");
      return file(documentPath, FileType.OTHER, sizeBytes, modifiedTimeNs, null, null);
    }

    final byte[] prefix;
    try (var input = Files.newInputStream(source)) {
      prefix = input.readNBytes(TYPE_PREFIX_BYTES);
    } catch (IOException | SecurityException e) {
      warnings.accept("Could not inspect " + documentPath + ": " + e.getMessage());
      return file(documentPath, FileType.OTHER, sizeBytes, modifiedTimeNs, null, null);
    }

    var type = detectFileType(prefix, prefix.length >= sizeBytes, documentPath);
    if (type == FileType.OTHER) {
      return file(documentPath, type, sizeBytes, modifiedTimeNs, null, null);
    }
    if (type != FileType.TEXT) {
      try {
        return file(documentPath, type, sizeBytes, modifiedTimeNs, Hashing.sha256Hex(source), null);
      } catch (IOException | SecurityException e) {
        warnings.accept("Could not inspect " + documentPath + ": " + e.getMessage());
        return file(documentPath, type, sizeBytes, modifiedTimeNs, null, null);
      }
    }

    try {
      var decodedText = Files.readString(source, StandardCharsets.UTF_8);
      return hasAcceptableControlRatio(decodedText)
          ? file(documentPath, type, sizeBytes, modifiedTimeNs, null, decodedText)
          : file(documentPath, FileType.OTHER, sizeBytes, modifiedTimeNs, null, null);
    } catch (IOException | SecurityException e) {
      warnings.accept("Could not inspect " + documentPath + ": " + e.getMessage());
      return file(documentPath, type, sizeBytes, modifiedTimeNs, null, null);
    }
  }

  private static ReadFile file(
      String documentPath,
      FileType type,
      long sizeBytes,
      long modifiedTimeNs,
      String sourceHash,
      String decodedText) {
    return new ReadFile(documentPath, type, sizeBytes, modifiedTimeNs, sourceHash, decodedText);
  }

  private static FileType detectFileType(byte[] prefix, boolean complete, String documentPath) {
    var magic = FileSignatures.detect(prefix);
    if (magic != FileType.OTHER) {
      return magic;
    }
    var extensionType = documentTypeFromExtension(documentPath);
    if (extensionType != null) {
      return extensionType;
    }
    if (prefix.length == 0) {
      return FileType.TEXT;
    }
    try {
      return hasAcceptableControlRatio(decodeUtf8(prefix, complete))
          ? FileType.TEXT
          : FileType.OTHER;
    } catch (CharacterCodingException e) {
      return FileType.OTHER;
    }
  }

  private static FileType documentTypeFromExtension(String documentPath) {
    var dot = documentPath.lastIndexOf('.');
    var separator = documentPath.lastIndexOf('/');
    if (dot <= separator || dot == documentPath.length() - 1) {
      return null;
    }
    return switch (documentPath.substring(dot + 1).toLowerCase(Locale.ROOT)) {
      case "docx", "docm", "xlsx", "xlsm", "pptx", "pptm" -> FileType.OFFICE;
      case "epub" -> FileType.EPUB;
      default -> null;
    };
  }

  private static String decodeUtf8(byte[] bytes, boolean complete) throws CharacterCodingException {
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    var output = CharBuffer.allocate(bytes.length + 1);
    var result = decoder.decode(ByteBuffer.wrap(bytes), output, complete);
    if (result.isError()) {
      result.throwException();
    }
    if (complete) {
      result = decoder.flush(output);
      if (result.isError()) {
        result.throwException();
      }
    }
    output.flip();
    return output.toString();
  }

  private static boolean hasAcceptableControlRatio(String text) {
    if (text.isEmpty()) {
      return true;
    }
    var total = 0;
    var controls = 0;
    for (var offset = 0; offset < text.length(); ) {
      var codePoint = text.codePointAt(offset);
      offset += Character.charCount(codePoint);
      total++;
      if (Character.isISOControl(codePoint)
          && codePoint != '\n'
          && codePoint != '\r'
          && codePoint != '\t') {
        controls++;
      }
    }
    return (double) controls / total <= MAX_TEXT_CONTROL_RATIO;
  }

  private static boolean matchesAny(List<Glob> globs, String relativePath) {
    return globs.stream().anyMatch(glob -> glob.matches(relativePath));
  }

  private static IgnoreFrame readIgnoreFile(Path directory) throws IOException {
    var node = new IgnoreNode();
    var file = directory.resolve(".gitignore");
    if (Files.isRegularFile(file)) {
      try (var input = Files.newInputStream(file)) {
        node.parse(file.toString(), input);
      }
    }
    return new IgnoreFrame(directory, node);
  }

  private static boolean ignored(List<IgnoreFrame> frames, Path path, boolean directory) {
    for (var index = frames.size() - 1; index >= 0; index--) {
      var frame = frames.get(index);
      var result = frame.rules().checkIgnored(relativePath(frame.directory(), path), directory);
      if (result != null) {
        return result;
      }
    }
    return false;
  }

  private static String relativePath(Path root, Path path) {
    return PathSupport.normalizePathSeparators(root.relativize(path).toString());
  }

  private static long modifiedTimeNs(Instant instant) {
    try {
      return Math.max(
          0L,
          Math.addExact(
              Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano()));
    } catch (ArithmeticException e) {
      return instant.isBefore(Instant.EPOCH) ? 0L : Long.MAX_VALUE;
    }
  }

  public record SourceMetadata(long modifiedTimeNs, long sizeBytes) {}

  public record ScanResult(List<ReadFile> readFiles, List<String> unchangedDocumentPaths) {

    public ScanResult {
      readFiles = List.copyOf(readFiles);
      unchangedDocumentPaths = List.copyOf(unchangedDocumentPaths);
    }
  }

  public record ReadFile(
      String documentPath,
      FileType fileType,
      long sizeBytes,
      long modifiedTimeNs,
      String sourceHash,
      String decodedText) {}

  private record IgnoreFrame(Path directory, IgnoreNode rules) {}

  private record ScanContext(
      Path root,
      boolean useIgnoreFiles,
      List<Glob> includes,
      List<Glob> excludes,
      Map<String, SourceMetadata> indexedFiles,
      List<ReadFile> readFiles,
      List<String> unchangedDocumentPaths,
      Consumer<String> warnings,
      Consumer<Long> progress) {}

  private record Glob(PathMatcher matcher, PathMatcher rootMatcher) {

    static Glob compile(String pattern) {
      var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
      var rootMatcher =
          pattern.startsWith("**/")
              ? FileSystems.getDefault().getPathMatcher("glob:" + pattern.substring(3))
              : null;
      return new Glob(matcher, rootMatcher);
    }

    boolean matches(String relativePath) {
      var path = Path.of(relativePath);
      return matcher.matches(path) || rootMatcher != null && rootMatcher.matches(path);
    }
  }
}
