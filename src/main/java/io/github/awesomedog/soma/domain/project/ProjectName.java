package io.github.awesomedog.soma.domain.project;

import io.github.awesomedog.soma.domain.naming.NameCanonicalizer;
import jakarta.annotation.Nonnull;

public record ProjectName(String value) {

  public ProjectName {
    value = NameCanonicalizer.canonicalize(value);
    if (value.isEmpty()) {
      throw new IllegalArgumentException("Project name is empty after canonicalization");
    }
  }

  @Nonnull
  @Override
  public String toString() {
    return value;
  }
}
