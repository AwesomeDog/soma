package io.github.awesomedog.soma.domain.recipe;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.awesomedog.soma.support.Hashing;
import java.nio.ByteBuffer;
import java.util.Objects;

public final class RecipeId {

  private RecipeId() {}

  public static String of(String... parts) {
    Objects.requireNonNull(parts, "parts");
    if (parts.length == 0) {
      throw new IllegalArgumentException("Recipe ID requires at least one part");
    }
    var digest = Hashing.newSha256Digest();
    for (var part : parts) {
      var bytes = Objects.requireNonNull(part, "recipe part").getBytes(UTF_8);
      digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
      digest.update(bytes);
    }
    return Hashing.hex(digest.digest());
  }

  public static boolean isInvalid(String value) {
    return !Hashing.isLowercaseSha256(value);
  }
}
