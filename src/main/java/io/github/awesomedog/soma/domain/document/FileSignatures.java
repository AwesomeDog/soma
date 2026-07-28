package io.github.awesomedog.soma.domain.document;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class FileSignatures {

  private FileSignatures() {}

  public static FileType detect(byte[] prefix) {
    Objects.requireNonNull(prefix, "prefix");
    if (startsWith(prefix, ascii("%PDF"))) {
      return FileType.PDF;
    }
    if (imageMediaType(prefix) != null) {
      return FileType.IMAGE;
    }
    if (startsWith(prefix, ascii("OggS"))
        && containsSubsequence(prefix, new byte[] {(byte) 0x80, 't', 'h', 'e', 'o', 'r', 'a'})) {
      return FileType.VIDEO;
    }
    if (isRiffType(prefix, "WAVE")
        || startsWith(prefix, ascii("ID3"))
        || startsWith(prefix, ascii("OggS"))
        || startsWith(prefix, ascii("fLaC"))
        || hasMpegAudioFrameSync(prefix)) {
      return FileType.AUDIO;
    }
    if (isRiffType(prefix, "AVI ")
        || hasIsoBaseMediaBrand(prefix)
        || startsWith(prefix, new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3})
        || startsWith(prefix, new byte[] {0x00, 0x00, 0x01, (byte) 0xba})
        || startsWith(prefix, new byte[] {0x00, 0x00, 0x01, (byte) 0xb3})) {
      return FileType.VIDEO;
    }
    return FileType.OTHER;
  }

  public static String imageMediaType(byte[] bytes) {
    Objects.requireNonNull(bytes, "bytes");
    if (startsWith(bytes, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a})) {
      return "image/png";
    }
    if (isJpeg(bytes)) {
      return "image/jpeg";
    }
    if (startsWith(bytes, ascii("GIF87a")) || startsWith(bytes, ascii("GIF89a"))) {
      return "image/gif";
    }
    if (startsWith(bytes, ascii("BM"))) {
      return "image/bmp";
    }
    if (startsWith(bytes, new byte[] {'I', 'I', 0x2a, 0x00})
        || startsWith(bytes, new byte[] {'M', 'M', 0x00, 0x2a})) {
      return "image/tiff";
    }
    if (isRiffType(bytes, "WEBP")) {
      return "image/webp";
    }
    if (!hasIsoBaseMediaBrand(bytes)) {
      return null;
    }
    return switch (new String(bytes, 8, 4, StandardCharsets.US_ASCII)) {
      case "avif", "avis" -> "image/avif";
      case "heic", "heix", "hevc", "hevx" -> "image/heic";
      case "mif1", "msf1" -> "image/heif";
      default -> null;
    };
  }

  private static boolean isJpeg(byte[] prefix) {
    return prefix.length >= 3
        && unsigned(prefix[0]) == 0xff
        && unsigned(prefix[1]) == 0xd8
        && unsigned(prefix[2]) == 0xff;
  }

  private static boolean isRiffType(byte[] prefix, String type) {
    return prefix.length >= 12
        && startsWith(prefix, ascii("RIFF"))
        && prefix[8] == type.charAt(0)
        && prefix[9] == type.charAt(1)
        && prefix[10] == type.charAt(2)
        && prefix[11] == type.charAt(3);
  }

  private static boolean hasMpegAudioFrameSync(byte[] prefix) {
    return prefix.length >= 2
        && unsigned(prefix[0]) == 0xff
        && (unsigned(prefix[1]) & 0xe0) == 0xe0;
  }

  private static boolean hasIsoBaseMediaBrand(byte[] prefix) {
    return prefix.length >= 12
        && prefix[4] == 'f'
        && prefix[5] == 't'
        && prefix[6] == 'y'
        && prefix[7] == 'p';
  }

  private static boolean startsWith(byte[] value, byte[] expected) {
    if (value.length < expected.length) {
      return false;
    }
    for (var index = 0; index < expected.length; index++) {
      if (value[index] != expected[index]) {
        return false;
      }
    }
    return true;
  }

  private static boolean containsSubsequence(byte[] value, byte[] expected) {
    for (var offset = 0; offset <= value.length - expected.length; offset++) {
      var matches = true;
      for (var index = 0; index < expected.length; index++) {
        if (value[offset + index] != expected[index]) {
          matches = false;
          break;
        }
      }
      if (matches) {
        return true;
      }
    }
    return false;
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }
}
