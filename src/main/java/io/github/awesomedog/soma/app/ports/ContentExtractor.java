package io.github.awesomedog.soma.app.ports;

import io.github.awesomedog.soma.domain.document.FileType;
import java.nio.file.Path;

public interface ContentExtractor {

  String recipeId(FileType fileType);

  Extraction extract(Path sourceFile, FileType fileType);

  record Extraction(String sourceHash, String body) {}
}
