package io.github.awesomedog.soma.infra.runtime;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import io.github.awesomedog.soma.support.Hashing;
import io.github.awesomedog.soma.support.HostPlatform;
import io.github.awesomedog.soma.support.PathSupport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedArtifactsTest {

  @TempDir Path temporaryDirectory;

  @Test
  void normalPullTrustsMainEntriesWithoutReadingPackagesOrRepairingCompanions() throws Exception {
    var model = bytes("model");
    var archive =
        tar(
            "tar.gz",
            TarItem.file("bundle/tool", "tool", 0755),
            TarItem.file("bundle/companion.dat", "companion", 0644));
    var requests = new AtomicInteger();
    var server = server(Map.of("/model", model, "/tool", archive), requests);
    try {
      var root = temporaryDirectory.resolve("data");
      var specs =
          List.of(
              spec(
                  "model", "1", "all", url(server, "/model"), model, "file", 0, "model.bin", false),
              spec(
                  "tool",
                  "1",
                  "darwin-arm64",
                  url(server, "/tool"),
                  archive,
                  "tar.gz",
                  1,
                  "tool",
                  true));
      var artifacts = artifacts(root, specs);

      var initialReport = artifacts.pull(false);
      var modelEntry = liveEntry(root, specs.get(0));
      var toolRoot = liveRoot(root, specs.get(1));
      assertThat(initialReport.artifacts().getFirst().path())
          .isEqualTo(PathSupport.toPortableString(modelEntry));
      assertThat(artifacts.inspect().getFirst().path())
          .isEqualTo(PathSupport.toPortableString(modelEntry));
      assertThat(modelEntry).hasContent("model");
      assertThat(Files.isSameFile(packagePath(root, specs.get(0)), modelEntry)).isTrue();
      assertThat(toolRoot.resolve("tool")).isExecutable();
      assertThat(toolRoot.resolve("companion.dat")).hasContent("companion");

      Files.delete(packagePath(root, specs.get(0)));
      Files.delete(packagePath(root, specs.get(1)));
      Files.delete(toolRoot.resolve("companion.dat"));
      Files.writeString(toolRoot.resolve("user-added"), "keep", UTF_8);
      var before = requests.get();

      var report = artifacts.pull(false);

      assertThat(report.fastLocalCheck()).isTrue();
      assertThat(requests).hasValue(before);
      assertThat(toolRoot.resolve("companion.dat")).doesNotExist();
      assertThat(toolRoot.resolve("user-added")).hasContent("keep");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void ensurePresentRefreshesEveryCurrentArtifactWhenARequestedEntryIsMissing() throws Exception {
    var first = bytes("first");
    var secondArchive =
        tar(
            "tar.gz",
            TarItem.file("root/second", "second", 0644),
            TarItem.file("root/companion", "companion", 0644));
    var server = server(Map.of("/first", first, "/second", secondArchive), new AtomicInteger());
    var root = temporaryDirectory.resolve("data");
    var specs =
        List.of(
            spec("first", "1", "all", url(server, "/first"), first, "file", 0, "first", false),
            spec(
                "second",
                "1",
                "darwin-arm64",
                url(server, "/second"),
                secondArchive,
                "tar.gz",
                1,
                "second",
                false));
    var artifacts = artifacts(root, specs);
    artifacts.pull(false);
    server.stop(0);

    Files.delete(liveEntry(root, specs.get(0)));
    Files.delete(liveRoot(root, specs.get(1)).resolve("companion"));
    Files.writeString(liveRoot(root, specs.get(1)).resolve("extra"), "remove", UTF_8);

    assertThat(artifacts.ensurePresent("first"))
        .containsEntry("first", liveEntry(root, specs.get(0)));
    assertThat(liveRoot(root, specs.get(1)).resolve("companion")).hasContent("companion");
    assertThat(liveRoot(root, specs.get(1)).resolve("extra")).doesNotExist();
  }

  @Test
  void repairsMissingAndCorruptPackagesButNeverPublishesABadDownload() throws Exception {
    var one = bytes("one");
    var two = bytes("two");
    var requests = new AtomicInteger();
    var server = server(Map.of("/one", one, "/two", two), requests);
    try {
      var root = temporaryDirectory.resolve("repair");
      var specs =
          List.of(
              spec("one", "1", "all", url(server, "/one"), one, "file", 0, "one", false),
              spec("two", "1", "all", url(server, "/two"), two, "file", 0, "two", false));
      Files.createDirectories(root.resolve("packages"));
      Files.writeString(packagePath(root, specs.get(0)), "corrupt", UTF_8);

      artifacts(root, specs).pull(true);

      assertThat(requests).hasValue(2);
      assertThat(Hashing.sha256Hex(packagePath(root, specs.get(0))))
          .isEqualTo(specs.get(0).sha256());
      assertThat(Hashing.sha256Hex(packagePath(root, specs.get(1))))
          .isEqualTo(specs.get(1).sha256());

      var badRoot = temporaryDirectory.resolve("bad");
      Files.createDirectories(badRoot.resolve("live"));
      Files.writeString(badRoot.resolve("live/old"), "old", UTF_8);
      var expected = bytes("expected");
      var badSpec = spec("bad", "1", "all", url(server, "/two"), expected, "file", 0, "bad", false);

      assertThatThrownBy(() -> artifacts(badRoot, List.of(badSpec)).pull(true))
          .isInstanceOf(AppException.class)
          .extracting(error -> ((AppException) error).error().code())
          .isEqualTo(AppError.Code.OPERATION_FAILED);
      assertThat(packagePath(badRoot, badSpec)).doesNotExist();
      assertThat(badRoot.resolve("live/old")).hasContent("old");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void materializesEveryFormatWithStripComponentsLinksAndPermissions() throws Exception {
    var zip = zip(ZipItem.file("root/bin/zipped", "zip", 0755));
    var tarGz =
        tar(
            "tar.gz",
            TarItem.file("root/bin/target", "target", 0755),
            TarItem.link("root/bin/link", "target"));
    var tarXz = tar("tar.xz", TarItem.file("xz-tool", "xz", 0755));
    var direct = bytes("direct");
    var requests = new AtomicInteger();
    var server =
        server(Map.of("/zip", zip, "/tgz", tarGz, "/txz", tarXz, "/file", direct), requests);
    try {
      var root = temporaryDirectory.resolve("data");
      var specs =
          List.of(
              spec("zip", "1", "all", url(server, "/zip"), zip, "zip", 1, "bin/zipped", true),
              spec("tgz", "1", "all", url(server, "/tgz"), tarGz, "tar.gz", 1, "bin/link", true),
              spec("txz", "1", "all", url(server, "/txz"), tarXz, "tar.xz", 0, "xz-tool", true),
              spec("file", "1", "all", url(server, "/file"), direct, "file", 0, "direct", false));

      artifacts(root, specs).pull(true);

      assertThat(requests).hasValue(4);
      assertThat(liveEntry(root, specs.get(0))).hasContent("zip").isExecutable();
      assertThat(liveEntry(root, specs.get(1))).hasContent("target").isExecutable();
      if (!HostPlatform.current().isWindows()) {
        assertThat(liveEntry(root, specs.get(1))).isSymbolicLink();
      }
      assertThat(liveEntry(root, specs.get(2))).hasContent("xz").isExecutable();
      assertThat(liveEntry(root, specs.get(3))).hasContent("direct");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsTraversalEscapingLinksAndUnsupportedEntries() throws Exception {
    assertRejectedArchive(
        tar("tar.gz", TarItem.file("../escape", "escape", 0644)), "entry", "escape");
    assertRejectedArchive(
        tar(
            "tar.gz",
            TarItem.file("root/target", "target", 0644),
            TarItem.link("root/link", "../../escape")),
        "link",
        "escape");
    assertRejectedArchive(
        tar("tar.gz", TarItem.special("root/fifo", TarConstants.LF_FIFO)), "fifo", "escape");
  }

  @Test
  void importIsOfflineAndImportsOnlyCurrentPlatformPackages() throws Exception {
    var mac = bytes("mac");
    var windows = bytes("windows");
    var common = bytes("common");
    var requests = new AtomicInteger();
    var server = server(Map.of(), requests);
    try {
      var root = temporaryDirectory.resolve("data");
      var specs =
          List.of(
              spec("tool", "1", "darwin-arm64", url(server, "/mac"), mac, "file", 0, "tool", false),
              spec(
                  "tool",
                  "1",
                  "windows-x86_64",
                  url(server, "/windows"),
                  windows,
                  "file",
                  0,
                  "tool.exe",
                  true),
              spec("model", "1", "all", url(server, "/common"), common, "file", 0, "model", false));
      var archive = temporaryDirectory.resolve("import.zip");
      writeZip(
          archive,
          List.of(
              new ZipContent("packages/" + Hashing.sha256Hex(mac), mac),
              new ZipContent("packages/" + Hashing.sha256Hex(windows), windows),
              new ZipContent("packages/" + Hashing.sha256Hex(common), common),
              new ZipContent("../../ignored", bytes("ignored"))));

      var report = artifacts(root, specs).importArchive(archive, ignored -> {});

      assertThat(report.packages()).isEqualTo(2);
      assertThat(report.archive())
          .isEqualTo(PathSupport.toPortableString(archive.toAbsolutePath().normalize()));
      assertThat(requests).hasValue(0);
      assertThat(liveEntry(root, specs.get(0))).hasContent("mac");
      assertThat(liveEntry(root, specs.get(2))).hasContent("common");
      assertThat(liveRoot(root, specs.get(1))).doesNotExist();
      assertThat(temporaryDirectory.resolve("ignored")).doesNotExist();
      assertThat(Hashing.sha256Hex(packagePath(root, specs.get(0))))
          .isEqualTo(specs.get(0).sha256());
      assertThat(packagePath(root, specs.get(1))).doesNotExist();
      assertThat(Hashing.sha256Hex(packagePath(root, specs.get(2))))
          .isEqualTo(specs.get(2).sha256());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void importRejectsMissingDuplicateAndMismatchedPackages() throws Exception {
    var content = bytes("content");
    var spec =
        spec("model", "1", "all", "http://127.0.0.1:1/unused", content, "file", 0, "model", false);
    var name = "packages/" + spec.sha256();

    var missing = temporaryDirectory.resolve("missing.zip");
    writeZip(missing, List.of(new ZipContent("extra", content)));
    assertImportFailure(temporaryDirectory.resolve("missing"), spec, missing);

    var duplicate = temporaryDirectory.resolve("duplicate.zip");
    writeZip(duplicate, List.of(new ZipContent(name, content), new ZipContent(name, content)));
    assertImportFailure(temporaryDirectory.resolve("duplicate"), spec, duplicate);

    var mismatch = temporaryDirectory.resolve("mismatch.zip");
    writeZip(mismatch, List.of(new ZipContent(name, bytes("wrong"))));
    assertImportFailure(temporaryDirectory.resolve("mismatch"), spec, mismatch);
  }

  @Test
  void concurrentPullsLeaveVerifiedPackagesAndACompleteLiveTree() throws Exception {
    var archive =
        tar(
            "tar.gz",
            TarItem.file("root/tool", "tool", 0755),
            TarItem.file("root/companion", "companion", 0644));
    var model = bytes("model");
    var server = server(Map.of("/tool", archive, "/model", model), new AtomicInteger());
    try {
      var root = temporaryDirectory.resolve("data");
      var specs =
          List.of(
              spec("tool", "1", "all", url(server, "/tool"), archive, "tar.gz", 1, "tool", true),
              spec("model", "1", "all", url(server, "/model"), model, "file", 0, "model", false));
      var ready = new CountDownLatch(2);
      var release = new CountDownLatch(1);
      var successes = new AtomicInteger();
      try (var workers = Executors.newFixedThreadPool(2)) {
        var futures =
            List.of(
                workers.submit(() -> concurrentPull(root, specs, ready, release, successes)),
                workers.submit(() -> concurrentPull(root, specs, ready, release, successes)));
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        release.countDown();
        for (var future : futures) {
          future.get(20, TimeUnit.SECONDS);
        }
      }

      assertThat(successes.get()).isGreaterThanOrEqualTo(1);
      for (var spec : specs) {
        assertThat(Hashing.sha256Hex(packagePath(root, spec))).isEqualTo(spec.sha256());
        assertThat(liveEntry(root, spec)).isRegularFile();
      }
      assertThat(liveRoot(root, specs.get(0)).resolve("companion")).hasContent("companion");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void artifactRecipesDependOnlyOnIdsAndVersions() {
    var root = temporaryDirectory.resolve("data");
    var original =
        spec(
            "model", "1", "all", "https://example.test/one", bytes("one"), "file", 0, "one", false);
    var changedDelivery =
        new Spec(
            original.id(),
            original.version(),
            original.platform(),
            "https://example.test/two",
            Hashing.sha256Hex(bytes("two")),
            "zip",
            3,
            "different/path",
            true);
    var changedVersion =
        new Spec(
            original.id(),
            "2",
            original.platform(),
            original.url(),
            original.sha256(),
            original.format(),
            original.stripComponents(),
            original.entry(),
            original.executable());

    var recipe = artifacts(root, List.of(original)).artifactRecipeId("model");

    assertThat(recipe)
        .isEqualTo(RecipeId.of("artifacts", "v1", RecipeId.of("artifact", "v1", "model", "1")));
    assertThat(artifacts(root, List.of(changedDelivery)).artifactRecipeId("model"))
        .isEqualTo(recipe);
    assertThat(artifacts(root, List.of(changedVersion)).artifactRecipeId("model"))
        .isNotEqualTo(recipe);
  }

  private void assertRejectedArchive(byte[] archive, String entry, String escapedName)
      throws Exception {
    var root = temporaryDirectory.resolve("rejected-" + entry);
    var spec =
        spec("bad", "1", "all", "http://127.0.0.1:1/unused", archive, "tar.gz", 1, entry, false);
    writePackage(root, spec, archive);

    assertThatThrownBy(() -> artifacts(root, List.of(spec)).pull(true))
        .isInstanceOf(AppException.class);
    assertThat(temporaryDirectory.resolve(escapedName)).doesNotExist();
    assertThat(root.resolve("live")).doesNotExist();
  }

  private void assertImportFailure(Path root, Spec spec, Path archive) {
    assertThatThrownBy(() -> artifacts(root, List.of(spec)).importArchive(archive, ignored -> {}))
        .isInstanceOf(AppException.class)
        .extracting(error -> ((AppException) error).error().code())
        .isEqualTo(AppError.Code.OPERATION_FAILED);
    assertThat(packagePath(root, spec)).doesNotExist();
    assertThat(root.resolve("live")).doesNotExist();
  }

  private static void concurrentPull(
      Path root,
      List<Spec> specs,
      CountDownLatch ready,
      CountDownLatch release,
      AtomicInteger successes) {
    ready.countDown();
    try {
      release.await();
      artifacts(root, specs).pull(true);
      successes.incrementAndGet();
    } catch (AppException ignored) {
      // Lock-free publication permits either concurrent command to fail.
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static ManagedArtifacts artifacts(Path root, List<Spec> specs) {
    return new ManagedArtifacts(root, "darwin-arm64", manifest(specs), HttpClient.newHttpClient());
  }

  private static String manifest(List<Spec> specs) {
    return """
        {"version":1,"artifacts":[%s]}
        """
        .formatted(
            specs.stream()
                .map(
                    spec ->
                        """
                        {"id":"%s","version":"%s","platform":"%s","url":"%s",\
                        "sha256":"%s","format":"%s","stripComponents":%d,"entry":"%s",\
                        "executable":%s}
                        """
                            .formatted(
                                spec.id(),
                                spec.version(),
                                spec.platform(),
                                spec.url(),
                                spec.sha256(),
                                spec.format(),
                                spec.stripComponents(),
                                spec.entry(),
                                spec.executable()))
                .reduce((left, right) -> left + "," + right)
                .orElse(""));
  }

  private static Spec spec(
      String id,
      String version,
      String platform,
      String url,
      byte[] content,
      String format,
      int stripComponents,
      String entry,
      boolean executable) {
    return new Spec(
        id,
        version,
        platform,
        url,
        Hashing.sha256Hex(content),
        format,
        stripComponents,
        entry,
        executable);
  }

  private static HttpServer server(Map<String, byte[]> responses, AtomicInteger requests)
      throws IOException {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          requests.incrementAndGet();
          var body = responses.get(exchange.getRequestURI().getPath());
          if (body == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
          }
          exchange.sendResponseHeaders(200, body.length);
          try (var output = exchange.getResponseBody()) {
            output.write(body);
          }
        });
    server.start();
    return server;
  }

  private static String url(HttpServer server, String path) {
    return "http://127.0.0.1:" + server.getAddress().getPort() + path;
  }

  private static Path packagePath(Path root, Spec spec) {
    return root.resolve("packages").resolve(spec.sha256());
  }

  private static void writePackage(Path root, Spec spec, byte[] content) throws IOException {
    Files.createDirectories(root.resolve("packages"));
    Files.write(packagePath(root, spec), content);
  }

  private static Path liveRoot(Path root, Spec spec) {
    return root.resolve("live").resolve(spec.id()).resolve(spec.sha256().substring(0, 6));
  }

  private static Path liveEntry(Path root, Spec spec) {
    return liveRoot(root, spec).resolve(spec.entry());
  }

  private static byte[] bytes(String value) {
    return value.getBytes(UTF_8);
  }

  private static byte[] tar(String format, TarItem... items) throws IOException {
    var result = new ByteArrayOutputStream();
    try (var compressed =
            "tar.gz".equals(format)
                ? new GzipCompressorOutputStream(result)
                : new XZCompressorOutputStream(result);
        var tar = new TarArchiveOutputStream(compressed)) {
      for (var item : items) {
        var entry = new TarArchiveEntry(item.name(), item.type());
        entry.setMode(item.mode());
        if (item.linkTarget() != null) {
          entry.setLinkName(item.linkTarget());
        }
        if (item.type() == TarConstants.LF_NORMAL) {
          entry.setSize(item.content().length);
        }
        tar.putArchiveEntry(entry);
        if (item.type() == TarConstants.LF_NORMAL) {
          tar.write(item.content());
        }
        tar.closeArchiveEntry();
      }
    }
    return result.toByteArray();
  }

  private static byte[] zip(ZipItem... items) throws IOException {
    var result = new ByteArrayOutputStream();
    try (var zip = new ZipArchiveOutputStream(result)) {
      for (var item : items) {
        var entry = new ZipArchiveEntry(item.name());
        entry.setUnixMode(UnixStat.FILE_FLAG | item.mode());
        zip.putArchiveEntry(entry);
        zip.write(item.content());
        zip.closeArchiveEntry();
      }
    }
    return result.toByteArray();
  }

  private static void writeZip(Path path, List<ZipContent> entries) throws IOException {
    try (var output = Files.newOutputStream(path);
        var zip = new ZipArchiveOutputStream(output)) {
      for (var item : entries) {
        var entry = new ZipArchiveEntry(item.name());
        zip.putArchiveEntry(entry);
        zip.write(item.content());
        zip.closeArchiveEntry();
      }
    }
  }

  private record Spec(
      String id,
      String version,
      String platform,
      String url,
      String sha256,
      String format,
      int stripComponents,
      String entry,
      boolean executable) {}

  private record TarItem(String name, byte type, byte[] content, int mode, String linkTarget) {

    static TarItem file(String name, String content, int mode) {
      return new TarItem(name, TarConstants.LF_NORMAL, bytes(content), mode, null);
    }

    static TarItem link(String name, String target) {
      return new TarItem(name, TarConstants.LF_SYMLINK, new byte[0], 0777, target);
    }

    static TarItem special(String name, byte type) {
      return new TarItem(name, type, new byte[0], 0644, null);
    }
  }

  private record ZipItem(String name, byte[] content, int mode) {

    static ZipItem file(String name, String content, int mode) {
      return new ZipItem(name, bytes(content), mode);
    }
  }

  private record ZipContent(String name, byte[] content) {}
}
