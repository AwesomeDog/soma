package io.github.awesomedog.soma.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

public final class Hashing {

  private static final int BUFFER_BYTES = 8192;
  private static final HexFormat HEX = HexFormat.of();
  private static final Pattern LOWERCASE_SHA_256 = Pattern.compile("[0-9a-f]{64}");

  private Hashing() {}

  public static MessageDigest newSha256Digest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  public static String sha256Hex(byte[] value) {
    return hex(newSha256Digest().digest(Objects.requireNonNull(value, "value")));
  }

  public static String sha256HexUtf8(String value) {
    return sha256Hex(Objects.requireNonNull(value, "value").getBytes(UTF_8));
  }

  public static String sha256Hex(Path file) throws IOException {
    Objects.requireNonNull(file, "file");
    var digest = newSha256Digest();
    try (var input = Files.newInputStream(file)) {
      var buffer = new byte[BUFFER_BYTES];
      int read;
      while ((read = input.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
    }
    return hex(digest.digest());
  }

  public static String hex(byte[] value) {
    return HEX.formatHex(Objects.requireNonNull(value, "value"));
  }

  public static boolean isLowercaseSha256(String value) {
    return value != null && LOWERCASE_SHA_256.matcher(value).matches();
  }
}
