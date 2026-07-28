package io.github.awesomedog.soma.infra.runtime;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.COPY_ATTRIBUTES;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.common.ProgressEvent.WorkUnit;
import io.github.awesomedog.soma.app.ports.ArtifactProvisioner;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.HostPlatform;
import io.github.awesomedog.soma.support.PathSupport;
import io.micronaut.serde.ObjectMapper;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;

@Singleton
public final class ManagedArtifacts implements ArtifactProvisioner {

  private static final String MANIFEST = "/artifacts/artifacts.json";
  private static final int BUFFER_BYTES = 8192;
  private static final int MAX_LINK_BYTES = 4096;
  private static final long PROGRESS_INTERVAL_NANOS = Duration.ofMillis(150).toNanos();
  private static final LocalDateTime ARCHIVE_ENTRY_TIME = LocalDateTime.of(1980, 1, 1, 0, 0);

  private final Path root;
  private final List<Artifact> allArtifacts;
  private final List<Artifact> currentArtifacts;
  private final HttpClient http;

  public ManagedArtifacts() {
    this(
        PathSupport.somaDataDirectory(),
        HostPlatform.current().id(),
        loadManifest(),
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
  }

  ManagedArtifacts(Path root, String hostPlatform, String manifest, HttpClient http) {
    this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    Objects.requireNonNull(hostPlatform, "platform");
    this.http = Objects.requireNonNull(http, "http");
    var parsed = parseManifest(Objects.requireNonNull(manifest, "manifest"));
    this.allArtifacts = List.copyOf(parsed.artifacts());
    this.currentArtifacts =
        allArtifacts.stream()
            .filter(
                artifact ->
                    "all".equals(artifact.platform()) || hostPlatform.equals(artifact.platform()))
            .toList();
    cleanRetiredLiveTrees();
  }

  @Override
  public PullReport pull(boolean refresh, Consumer<ProgressEvent> progress) {
    Objects.requireNonNull(progress, "progress");
    try {
      if (!refresh && currentArtifacts.stream().allMatch(this::isAvailable)) {
        return report(Set.of(), true);
      }
      var downloaded = ensurePackages(currentArtifacts, progress);
      rebuildLive();
      return report(downloaded, false);
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      restoreInterrupt(e);
      throw new AppException(
          OPERATION_FAILED,
          "Managed artifact pull failed.",
          "Check network access, disk space then retry. If this machine is offline, consider `soma system pull --import <arc.zip>`.",
          e);
    }
  }

  @Override
  public ArchiveReport exportArchive(Path archive, Consumer<ProgressEvent> progress) {
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(progress, "progress");
    var output = archive.toAbsolutePath().normalize();
    Path temporary = null;
    try {
      if (output.getFileName() == null) {
        throw new IOException("Artifact archive path must name a ZIP file");
      }
      ensurePackages(allArtifacts, progress);
      Files.createDirectories(output.getParent());
      temporary = temporarySibling(output, "writing");
      writePackageArchive(temporary, allArtifacts);
      Files.move(temporary, output, REPLACE_EXISTING, ATOMIC_MOVE);
      temporary = null;
      return new ArchiveReport(
          "Exported managed artifacts", PathSupport.toPortableString(output), allArtifacts.size());
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      restoreInterrupt(e);
      throw new AppException(
          OPERATION_FAILED,
          "Managed artifact export failed.",
          "Check disk space, network access, and the destination path, then retry.",
          e);
    } finally {
      deleteFileQuietly(temporary);
    }
  }

  @Override
  public ArchiveReport importArchive(Path archive, Consumer<ProgressEvent> progress) {
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(progress, "progress");
    var input = archive.toAbsolutePath().normalize();
    try {
      var packages = currentArtifacts.stream().map(Artifact::sha256).toList();
      var unavailable = new ArrayList<String>();
      var imported = 0;
      Files.createDirectories(packagesDirectory());
      try (var zip = new ZipFile(input.toFile())) {
        var entries = zipEntries(zip, packages);
        for (var index = 0; index < currentArtifacts.size(); index++) {
          var artifact = currentArtifacts.get(index);
          var sha256 = artifact.sha256();
          var matches = entries.getOrDefault(sha256, List.of());
          if (matches.size() != 1 || matches.getFirst().isDirectory()) {
            unavailable.add(artifact.id() + " " + artifact.version());
            continue;
          }
          progress.accept(
              ProgressEvent.message(
                  "Importing %s %s (%d/%d)"
                      .formatted(
                          artifact.id(), artifact.version(), index + 1, currentArtifacts.size())));
          var temporary = temporaryPackage(sha256, "importing");
          try {
            copyImportedPackage(zip, matches.getFirst(), temporary, sha256);
            Files.move(temporary, packagePath(sha256), REPLACE_EXISTING, ATOMIC_MOVE);
            imported++;
          } catch (IOException e) {
            unavailable.add(artifact.id() + " " + artifact.version() + ": " + e.getMessage());
          } finally {
            deleteFileQuietly(temporary);
          }
        }
      }
      if (!unavailable.isEmpty()) {
        throw new AppException(
            OPERATION_FAILED,
            "Imported %d of %d managed artifacts; live was not changed.%nCould not import:%n  - %s"
                .formatted(imported, currentArtifacts.size(), String.join("\n  - ", unavailable)),
            "Possible causes include missing, duplicate, or invalid archive entries, insufficient "
                + "disk space, filesystem permissions, or unsupported atomic moves. Check the "
                + "archive and local storage, or run `soma system pull` to download and rebuild "
                + "the artifacts.");
      }
      rebuildLive();
      return new ArchiveReport(
          "Imported managed artifacts", PathSupport.toPortableString(input), packages.size());
    } catch (AppException e) {
      throw e;
    } catch (Exception e) {
      throw new AppException(
          OPERATION_FAILED,
          "Managed artifact import failed.",
          "Check ZIP completeness and integrity, disk space, filesystem permissions, and atomic-move "
              + "support, then retry.",
          e);
    }
  }

  @Override
  public List<ArtifactState> inspect() {
    var states = new ArrayList<ArtifactState>(currentArtifacts.size());
    for (var artifact : currentArtifacts) {
      var path = liveEntry(liveDirectory(), artifact);
      var available = isAvailable(artifact);
      states.add(
          new ArtifactState(
              artifact.id(),
              artifact.version(),
              PathSupport.toPortableString(path),
              available,
              available ? fileSize(path) : 0));
    }
    return List.copyOf(states);
  }

  public Map<String, Path> ensurePresent(String... artifactIds) {
    Objects.requireNonNull(artifactIds, "ids");
    if (artifactIds.length == 0) {
      throw new IllegalArgumentException("At least one managed artifact is required");
    }
    var required = requiredArtifacts(artifactIds);
    if (required.values().stream().anyMatch(artifact -> !isAvailable(artifact))) {
      pull(true);
    }
    var paths = new LinkedHashMap<String, Path>();
    for (var entry : required.entrySet()) {
      if (!isAvailable(entry.getValue())) {
        throw new AppException(
            OPERATION_FAILED,
            "Managed artifact is unavailable: " + entry.getKey(),
            "Run `soma system pull --refresh`, then retry.");
      }
      paths.put(entry.getKey(), liveEntry(liveDirectory(), entry.getValue()));
    }
    return Map.copyOf(paths);
  }

  public String artifactRecipeId(String... artifactIds) {
    Objects.requireNonNull(artifactIds, "ids");
    if (artifactIds.length == 0) {
      throw new IllegalArgumentException("At least one managed artifact is required");
    }
    var parts = new ArrayList<String>();
    parts.add("artifacts");
    parts.add("v1");
    for (var artifactId : artifactIds) {
      var artifact = requireArtifact(artifactId);
      parts.add(RecipeId.of("artifact", "v1", artifact.id(), artifact.version()));
    }
    return RecipeId.of(parts.toArray(String[]::new));
  }

  private void rebuildLive() throws IOException {
    Files.createDirectories(root);
    var staging = root.resolve(".live-" + UUID.randomUUID());
    Files.createDirectory(staging);
    try {
      for (var artifact : currentArtifacts) {
        materialize(artifact, staging);
      }
      for (var artifact : currentArtifacts) {
        var entry = liveEntry(staging, artifact);
        if (!isUsableEntry(entry, artifact.executable())) {
          throw new IOException("Artifact did not produce its main entry: " + artifact.entry());
        }
      }
      publishLive(staging);
    } finally {
      deleteTreeQuietly(staging);
    }
  }

  private Set<String> ensurePackages(Iterable<Artifact> artifacts, Consumer<ProgressEvent> progress)
      throws IOException, InterruptedException {
    var downloaded = new HashSet<String>();
    for (var artifact : artifacts) {
      if (Files.isRegularFile(packagePath(artifact.sha256()), NOFOLLOW_LINKS)) {
        progress.accept(
            ProgressEvent.message("Verifying " + artifact.id() + " " + artifact.version() + "..."));
      }
      if (packageIsInValid(artifact.sha256())) {
        downloadPackage(artifact, progress);
        downloaded.add(artifact.sha256());
      }
    }
    return Set.copyOf(downloaded);
  }

  private void downloadPackage(Artifact artifact, Consumer<ProgressEvent> progress)
      throws IOException, InterruptedException {
    Files.createDirectories(packagesDirectory());
    var temporary = temporaryPackage(artifact.sha256(), "downloading");
    try {
      var request =
          HttpRequest.newBuilder(URI.create(artifact.url()))
              .timeout(Duration.ofHours(2))
              .GET()
              .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        response.body().close();
        throw new IOException("HTTP " + response.statusCode() + " downloading " + artifact.url());
      }
      var digest = Hashing.newSha256Digest();
      var totalBytes = response.headers().firstValueAsLong("content-length").orElse(-1L);
      try (var source = response.body();
          var target = Files.newOutputStream(temporary, CREATE_NEW)) {
        copyDownload(source, target, digest, artifact, totalBytes, progress);
      }
      var actual = Hashing.hex(digest.digest());
      if (!artifact.sha256().equals(actual)) {
        throw new IOException(
            "SHA-256 mismatch for "
                + artifact.id()
                + ": expected "
                + artifact.sha256()
                + ", got "
                + actual);
      }
      Files.move(temporary, packagePath(artifact.sha256()), REPLACE_EXISTING, ATOMIC_MOVE);
    } finally {
      deleteFileQuietly(temporary);
    }
  }

  private static void copyDownload(
      InputStream source,
      java.io.OutputStream target,
      MessageDigest digest,
      Artifact artifact,
      long totalBytes,
      Consumer<ProgressEvent> progress)
      throws IOException {
    var buffer = new byte[BUFFER_BYTES];
    var received = 0L;
    var lastReported = -1L;
    var lastReportNanos = 0L;
    int count;
    String message = "Downloading " + artifact.id() + " " + artifact.version();
    while ((count = source.read(buffer)) >= 0) {
      target.write(buffer, 0, count);
      digest.update(buffer, 0, count);
      received += count;
      var nowNanos = System.nanoTime();
      if (nowNanos - lastReportNanos >= PROGRESS_INTERVAL_NANOS) {
        progress.accept(ProgressEvent.update(message, received, totalBytes, WorkUnit.BYTES));
        lastReported = received;
        lastReportNanos = nowNanos;
      }
    }
    if (received != lastReported) {
      progress.accept(ProgressEvent.update(message, received, totalBytes, WorkUnit.BYTES));
    }
  }

  private void materialize(Artifact artifact, Path staging) throws IOException {
    var targetRoot = artifactRoot(staging, artifact);
    Files.createDirectories(targetRoot);
    switch (artifact.format()) {
      case "file" -> materializeFile(packagePath(artifact.sha256()), liveEntry(staging, artifact));
      case "zip", "tar.gz", "tar.xz" ->
          extractArchive(artifact, packagePath(artifact.sha256()), targetRoot);
      default -> throw new IOException("Unsupported artifact format: " + artifact.format());
    }
    if (artifact.executable()) {
      ensureExecutable(liveEntry(staging, artifact));
    }
  }

  private static void materializeFile(Path source, Path target) throws IOException {
    Files.createDirectories(target.getParent());
    try {
      Files.createLink(target, source);
    } catch (IOException | UnsupportedOperationException e) {
      Files.copy(source, target, COPY_ATTRIBUTES);
    }
  }

  private static void extractArchive(Artifact artifact, Path archive, Path targetRoot)
      throws IOException {
    var links = new ArrayList<SymbolicLink>();
    var directoryModes = new ArrayList<FileMode>();
    try (var input = openArchive(artifact.format(), archive)) {
      ArchiveEntry entry;
      while ((entry = input.getNextEntry()) != null) {
        if (!input.canReadEntryData(entry)) {
          throw new IOException("Archive entry cannot be read: " + entry.getName());
        }
        var kind = archiveEntryKind(entry);
        var relative = strippedArchivePath(entry.getName(), artifact.stripComponents());
        if (relative == null) {
          continue;
        }
        var target = targetRoot.resolve(relative).normalize();
        if (!target.startsWith(targetRoot.toAbsolutePath().normalize())) {
          throw new IOException("Archive entry escapes its target directory: " + entry.getName());
        }
        switch (kind) {
          case DIRECTORY -> {
            Files.createDirectories(target);
            directoryModes.add(new FileMode(target, archiveMode(entry)));
          }
          case SYMBOLIC_LINK -> {
            Files.createDirectories(target.getParent());
            links.add(new SymbolicLink(target, symbolicLinkTarget(entry, input)));
          }
          case FILE -> {
            Files.createDirectories(target.getParent());
            try (var output = Files.newOutputStream(target, CREATE_NEW)) {
              input.transferTo(output);
            }
            applyMode(target, archiveMode(entry));
          }
        }
      }
    }
    createSafeSymbolicLinks(targetRoot, links);
    directoryModes.sort(
        Comparator.comparingInt((FileMode value) -> value.path().getNameCount()).reversed());
    for (var directory : directoryModes) {
      applyMode(directory.path(), directory.mode());
    }
  }

  private static ArchiveInputStream<?> openArchive(String format, Path archive) throws IOException {
    var source = Files.newInputStream(archive);
    try {
      return switch (format) {
        case "zip" -> new ZipArchiveInputStream(source);
        case "tar.gz" -> new TarArchiveInputStream(new GzipCompressorInputStream(source));
        case "tar.xz" -> new TarArchiveInputStream(new XZCompressorInputStream(source));
        default -> throw new IOException("Unsupported artifact format: " + format);
      };
    } catch (IOException | RuntimeException e) {
      source.close();
      throw e;
    }
  }

  private static ArchiveEntryKind archiveEntryKind(ArchiveEntry entry) throws IOException {
    if (entry instanceof TarArchiveEntry tar) {
      if (tar.isDirectory()) {
        return ArchiveEntryKind.DIRECTORY;
      }
      if (tar.isSymbolicLink()) {
        return ArchiveEntryKind.SYMBOLIC_LINK;
      }
      if (tar.isLink()
          || tar.isFIFO()
          || tar.isCharacterDevice()
          || tar.isBlockDevice()
          || tar.isSparse()) {
        throw new IOException("Archive contains an unsupported entry: " + entry.getName());
      }
      if (tar.isFile()) {
        return ArchiveEntryKind.FILE;
      }
    } else if (entry instanceof ZipArchiveEntry zip) {
      if (zip.isUnixSymlink()) {
        return ArchiveEntryKind.SYMBOLIC_LINK;
      }
      var fileType = zip.getUnixMode() & UnixStat.FILE_TYPE_FLAG;
      if (fileType == UnixStat.DIR_FLAG || (fileType == 0 && zip.isDirectory())) {
        return ArchiveEntryKind.DIRECTORY;
      }
      if (fileType == 0 || fileType == UnixStat.FILE_FLAG) {
        return ArchiveEntryKind.FILE;
      }
    }
    throw new IOException("Archive contains an unsupported entry: " + entry.getName());
  }

  private static int archiveMode(ArchiveEntry entry) {
    if (entry instanceof TarArchiveEntry tar) {
      return tar.getMode();
    }
    return entry instanceof ZipArchiveEntry zip ? zip.getUnixMode() : 0;
  }

  private static String symbolicLinkTarget(ArchiveEntry entry, ArchiveInputStream<?> input)
      throws IOException {
    if (entry instanceof TarArchiveEntry tar) {
      return tar.getLinkName();
    }
    var content = input.readNBytes(MAX_LINK_BYTES + 1);
    if (content.length > MAX_LINK_BYTES) {
      throw new IOException("Archive symbolic link target is too long: " + entry.getName());
    }
    return new String(content, UTF_8);
  }

  private static Path strippedArchivePath(String name, int stripComponents) throws IOException {
    if (name == null || name.indexOf('\0') >= 0 || stripComponents < 0) {
      throw new IOException("Archive contains an invalid entry path");
    }
    var normalized = name.replace('\\', '/');
    if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
      throw new IOException("Archive contains an absolute entry path: " + name);
    }
    var segments = new ArrayList<String>();
    for (var segment : normalized.split("/")) {
      if (segment.isBlank() || ".".equals(segment)) {
        continue;
      }
      if ("..".equals(segment)) {
        throw new IOException("Archive entry escapes its target directory: " + name);
      }
      segments.add(segment);
    }
    if (segments.size() <= stripComponents) {
      return null;
    }
    return Path.of("", segments.subList(stripComponents, segments.size()).toArray(String[]::new));
  }

  private static void createSafeSymbolicLinks(Path root, List<SymbolicLink> links)
      throws IOException {
    var pending = new ArrayList<>(links);
    while (!pending.isEmpty()) {
      IOException lastFailure = null;
      var remaining = new ArrayList<SymbolicLink>();
      for (var link : pending) {
        var resolved = safeLinkTarget(root, link);
        if (!Files.exists(resolved)) {
          remaining.add(link);
          continue;
        }
        try {
          createSafeSymbolicLink(root, link, resolved);
        } catch (IOException e) {
          lastFailure = e;
          remaining.add(link);
        }
      }
      if (remaining.size() == pending.size()) {
        throw lastFailure != null
            ? lastFailure
            : new IOException("Archive symbolic link target is unavailable");
      }
      pending = remaining;
    }
  }

  private static Path safeLinkTarget(Path root, SymbolicLink link) throws IOException {
    var value = link.target();
    if (value == null || value.isBlank() || value.indexOf('\0') >= 0) {
      throw new IOException("Archive contains an empty symbolic link: " + link.path());
    }
    var normalized = value.replace('\\', '/');
    if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:.*")) {
      throw new IOException("Archive symbolic link is absolute: " + value);
    }
    var target = link.path().getParent().resolve(Path.of(normalized)).normalize();
    if (!target.startsWith(root.toAbsolutePath().normalize())) {
      throw new IOException("Archive symbolic link escapes its target directory: " + value);
    }
    return target;
  }

  private static void createSafeSymbolicLink(Path root, SymbolicLink link, Path resolved)
      throws IOException {
    var target = Path.of(link.target().replace('\\', '/'));
    try {
      Files.createSymbolicLink(link.path(), target);
      var realTarget = link.path().toRealPath();
      if (!realTarget.startsWith(root.toRealPath())) {
        Files.deleteIfExists(link.path());
        throw new IOException(
            "Archive symbolic link escapes its target directory: " + link.target());
      }
    } catch (UnsupportedOperationException e) {
      materializeSymbolicLink(root, link.path(), resolved, e);
    } catch (IOException e) {
      if (!HostPlatform.current().isWindows()) {
        throw e;
      }
      materializeSymbolicLink(root, link.path(), resolved, e);
    }
  }

  private static void materializeSymbolicLink(Path root, Path link, Path resolved, Exception cause)
      throws IOException {
    var realTarget = resolved.toRealPath();
    if (!realTarget.startsWith(root.toRealPath()) || !Files.isRegularFile(realTarget)) {
      throw new IOException("Archive symbolic link target is unavailable: " + resolved, cause);
    }
    Files.copy(realTarget, link, COPY_ATTRIBUTES);
  }

  private static void applyMode(Path path, int mode) throws IOException {
    if (mode == 0) {
      return;
    }
    var permissions = new HashSet<PosixFilePermission>();
    addPermission(permissions, mode, 0400, PosixFilePermission.OWNER_READ);
    addPermission(permissions, mode, 0200, PosixFilePermission.OWNER_WRITE);
    addPermission(permissions, mode, 0100, PosixFilePermission.OWNER_EXECUTE);
    addPermission(permissions, mode, 0040, PosixFilePermission.GROUP_READ);
    addPermission(permissions, mode, 0020, PosixFilePermission.GROUP_WRITE);
    addPermission(permissions, mode, 0010, PosixFilePermission.GROUP_EXECUTE);
    addPermission(permissions, mode, 0004, PosixFilePermission.OTHERS_READ);
    addPermission(permissions, mode, 0002, PosixFilePermission.OTHERS_WRITE);
    addPermission(permissions, mode, 0001, PosixFilePermission.OTHERS_EXECUTE);
    try {
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException ignored) {
      // Windows has no POSIX permissions.
    }
  }

  private static void addPermission(
      Set<PosixFilePermission> permissions, int mode, int bit, PosixFilePermission permission) {
    if ((mode & bit) != 0) {
      permissions.add(permission);
    }
  }

  private static void ensureExecutable(Path path) throws IOException {
    try {
      var permissions = new HashSet<>(Files.getPosixFilePermissions(path));
      permissions.add(PosixFilePermission.OWNER_EXECUTE);
      Files.setPosixFilePermissions(path, permissions);
    } catch (UnsupportedOperationException ignored) {
      // Windows selects executables by file type.
    }
  }

  private void publishLive(Path staging) throws IOException {
    var live = liveDirectory();
    var retired = root.resolve(".retired-live-" + UUID.randomUUID());
    var ownsRetired = false;
    try {
      try {
        Files.move(live, retired, ATOMIC_MOVE);
        ownsRetired = true;
      } catch (NoSuchFileException ignored) {
        // There is no previous live tree.
      }
      Files.move(staging, live, ATOMIC_MOVE);
    } catch (IOException publishFailure) {
      if (!Files.exists(live, NOFOLLOW_LINKS) && ownsRetired) {
        try {
          Files.move(retired, live, ATOMIC_MOVE);
          ownsRetired = false;
        } catch (IOException restoreFailure) {
          publishFailure.addSuppressed(restoreFailure);
        }
      }
      throw publishFailure;
    } finally {
      if (ownsRetired) {
        deleteTreeQuietly(retired);
      }
    }
  }

  private void writePackageArchive(Path archive, List<Artifact> artifacts) throws IOException {
    try (var output = Files.newOutputStream(archive, CREATE_NEW);
        var zip = new ZipOutputStream(output)) {
      for (var sha256 : artifacts.stream().map(Artifact::sha256).distinct().sorted().toList()) {
        if (packageIsInValid(sha256)) {
          throw new IOException("Package changed before export: " + sha256);
        }
        var entry = new ZipEntry("packages/" + sha256);
        entry.setTimeLocal(ARCHIVE_ENTRY_TIME);
        zip.putNextEntry(entry);
        try (var input = Files.newInputStream(packagePath(sha256))) {
          input.transferTo(zip);
        }
        zip.closeEntry();
      }
    }
  }

  private static Map<String, List<ZipEntry>> zipEntries(ZipFile zip, List<String> sha256s) {
    var requiredNames = new LinkedHashMap<String, String>();
    sha256s.forEach(sha256 -> requiredNames.put("packages/" + sha256, sha256));
    var found = new LinkedHashMap<String, List<ZipEntry>>();
    var entries = zip.entries();
    while (entries.hasMoreElements()) {
      var entry = entries.nextElement();
      var sha256 = requiredNames.get(entry.getName());
      if (sha256 == null) {
        continue;
      }
      found.computeIfAbsent(sha256, ignored -> new ArrayList<>()).add(entry);
    }
    return found;
  }

  private static void copyImportedPackage(
      ZipFile zip, ZipEntry entry, Path target, String expectedSha256) throws IOException {
    var digest = Hashing.newSha256Digest();
    try (var input = zip.getInputStream(entry);
        var output = Files.newOutputStream(target, CREATE_NEW)) {
      var buffer = new byte[BUFFER_BYTES];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        output.write(buffer, 0, count);
        digest.update(buffer, 0, count);
      }
    }
    var actual = Hashing.hex(digest.digest());
    if (!expectedSha256.equals(actual)) {
      throw new IOException(
          "Imported package SHA-256 mismatch: expected " + expectedSha256 + ", got " + actual);
    }
  }

  private PullReport report(Set<String> downloaded, boolean fastLocalCheck) throws IOException {
    var entries = new ArrayList<Entry>(currentArtifacts.size());
    for (var artifact : currentArtifacts) {
      var path = liveEntry(liveDirectory(), artifact);
      entries.add(
          new Entry(
              artifact.id(),
              artifact.version(),
              artifact.url(),
              PathSupport.toPortableString(path),
              Files.size(path),
              downloaded.contains(artifact.sha256())));
    }
    return new PullReport(entries, fastLocalCheck);
  }

  private Map<String, Artifact> requiredArtifacts(String[] artifactIds) {
    var required = new LinkedHashMap<String, Artifact>();
    for (var artifactId : artifactIds) {
      required.putIfAbsent(artifactId, requireArtifact(artifactId));
    }
    return required;
  }

  private Artifact requireArtifact(String artifactId) {
    if (artifactId == null || artifactId.isBlank()) {
      throw new IllegalArgumentException("Managed artifact id must not be blank");
    }
    return currentArtifacts.stream()
        .filter(artifact -> artifactId.equals(artifact.id()))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown managed artifact: " + artifactId));
  }

  private boolean packageIsInValid(String sha256) {
    var path = packagePath(sha256);
    try {
      return !Files.isRegularFile(path, NOFOLLOW_LINKS) || !sha256.equals(Hashing.sha256Hex(path));
    } catch (IOException e) {
      return true;
    }
  }

  private boolean isAvailable(Artifact artifact) {
    return isUsableEntry(liveEntry(liveDirectory(), artifact), artifact.executable());
  }

  private static boolean isUsableEntry(Path entry, boolean executable) {
    return Files.isRegularFile(entry)
        && (!executable || HostPlatform.current().isWindows() || Files.isExecutable(entry));
  }

  private Path artifactRoot(Path live, Artifact artifact) {
    var path = live.resolve(artifact.id()).resolve(artifact.sha256().substring(0, 6)).normalize();
    if (!path.startsWith(live.toAbsolutePath().normalize())) {
      throw new IllegalArgumentException("Artifact id escapes the live tree: " + artifact.id());
    }
    return path;
  }

  private Path liveEntry(Path live, Artifact artifact) {
    var artifactRoot = artifactRoot(live, artifact);
    var entry = artifactRoot.resolve(artifact.entry()).normalize();
    if (!entry.startsWith(artifactRoot)) {
      throw new IllegalArgumentException(
          "Artifact entry escapes the live tree: " + artifact.entry());
    }
    return entry;
  }

  private Path liveDirectory() {
    return root.resolve("live");
  }

  private Path packagesDirectory() {
    return root.resolve("packages");
  }

  private Path packagePath(String sha256) {
    return packagesDirectory().resolve(sha256);
  }

  private Path temporaryPackage(String sha256, String operation) {
    return packagesDirectory().resolve("." + sha256 + "." + operation + "-" + UUID.randomUUID());
  }

  private static Path temporarySibling(Path path, String operation) {
    return path.resolveSibling(
        "." + path.getFileName() + "." + operation + "-" + UUID.randomUUID());
  }

  private static long fileSize(Path path) {
    try {
      return Files.size(path);
    } catch (IOException e) {
      return 0;
    }
  }

  private static void deleteFileQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // Per-attempt cleanup is best effort.
    }
  }

  private static void deleteTreeQuietly(Path path) {
    if (path == null || !Files.exists(path, NOFOLLOW_LINKS)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      paths.sorted(Comparator.reverseOrder()).forEach(ManagedArtifacts::deleteFileQuietly);
    } catch (IOException ignored) {
      // Retired and failed staging cleanup is best effort.
    }
  }

  private void cleanRetiredLiveTrees() {
    try (var paths = Files.newDirectoryStream(root, ".retired-live-*")) {
      paths.forEach(ManagedArtifacts::deleteTreeQuietly);
    } catch (IOException ignored) {
      // Startup cleanup is best effort.
    }
  }

  private static void restoreInterrupt(Exception exception) {
    if (exception instanceof InterruptedException) {
      Thread.currentThread().interrupt();
    }
  }

  private static Manifest parseManifest(String manifest) {
    try {
      return ObjectMapper.getDefault().readValue(manifest, Manifest.class);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to deserialize the artifact manifest", e);
    }
  }

  private static String loadManifest() {
    try (var input = ManagedArtifacts.class.getResourceAsStream(MANIFEST)) {
      if (input == null) {
        throw new IllegalStateException("Missing artifact manifest: " + MANIFEST);
      }
      return new String(input.readAllBytes(), UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the artifact manifest", e);
    }
  }

  @Serdeable
  record Manifest(int version, List<Artifact> artifacts) {}

  @Serdeable
  record Artifact(
      String id,
      String version,
      String platform,
      String url,
      String sha256,
      String format,
      int stripComponents,
      String entry,
      boolean executable) {}

  private enum ArchiveEntryKind {
    FILE,
    DIRECTORY,
    SYMBOLIC_LINK
  }

  private record SymbolicLink(Path path, String target) {}

  private record FileMode(Path path, int mode) {}
}
