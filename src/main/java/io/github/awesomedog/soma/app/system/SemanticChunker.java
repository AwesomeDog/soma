package io.github.awesomedog.soma.app.system;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

final class SemanticChunker {

  private static final int TARGET_TOKENS = 900;
  private static final int RESERVED_PREFIX_TOKENS = 128;
  private static final int MAX_OVERLAP_TOKENS = 96;
  private static final Pattern HEADING = Pattern.compile("^ {0,3}#{1,6}(?:\\s|$)");
  private static final Pattern FENCE = Pattern.compile("^ {0,3}`{3,}");

  private final SearchModels searchModels;

  SemanticChunker(SearchModels searchModels) {
    this.searchModels = Objects.requireNonNull(searchModels, "searchModels");
  }

  boolean supports(SearchModels.EmbeddingMetadata metadata) {
    return metadata != null && metadata.maxInputTokens() > RESERVED_PREFIX_TOKENS;
  }

  String recipeId(SearchModels.EmbeddingMetadata metadata) {
    return RecipeId.of(
        "chunk.semantic",
        "v1",
        metadata.tokenizerRecipeId(),
        "target=" + TARGET_TOKENS,
        "reserved=" + RESERVED_PREFIX_TOKENS,
        "max_input=" + metadata.maxInputTokens(),
        "boundaries=heading-fence-paragraph-line",
        "overlap=weak-only-max96");
  }

  List<WorkspaceIndex.ChunkWrite> plan(String body, SearchModels.EmbeddingMetadata metadata) {
    if (body == null || body.isBlank()) {
      return List.of();
    }
    var limit = Math.min(TARGET_TOKENS, metadata.maxInputTokens() - RESERVED_PREFIX_TOKENS);
    var structure = analyzeStructure(body);
    var chunks = new ArrayList<WorkspaceIndex.ChunkWrite>();
    var start = 0;
    var covered = 0;
    while (covered < body.length()) {
      var end = fit(body, start, Math.min(body.length(), start + limit * 3), limit);
      var strongBoundary = false;
      var fence = containing(end, structure.fences());
      if (fence != null) {
        if (fence.start() > start) {
          end = fence.start();
          strongBoundary = true;
        } else if (tokens(body, start, fence.end()) <= limit) {
          end = fence.end();
          strongBoundary = true;
        }
      }
      if (!strongBoundary && end < body.length()) {
        var boundary =
            preferred(
                structure.boundaries(), Math.max(covered + 1, start + (end - start) / 2), end);
        if (boundary != null) {
          end = boundary.offset();
          strongBoundary = boundary.priority() > 0;
        }
      }
      if (end <= covered || end <= start) {
        throw failure("Could not find a token-safe document chunk boundary.");
      }
      var text = body.substring(start, end);
      chunks.add(
          new WorkspaceIndex.ChunkWrite(
              chunks.size(), start, end, text, searchModels.countTokens(text)));
      covered = end;
      if (covered < body.length()) {
        start = strongBoundary ? covered : overlapStart(body, start, covered, limit);
      }
    }
    return List.copyOf(chunks);
  }

  private int fit(String body, int start, int end, int limit) {
    var tokenCount = tokens(body, start, end);
    while (tokenCount > limit) {
      var length = end - start;
      var estimate = start + (int) Math.max(1, (long) length * limit / tokenCount);
      end = safeEnd(body, Math.min(end - 1, estimate));
      if (end <= start) {
        throw failure("Could not fit one document character in the embedding model context.");
      }
      tokenCount = tokens(body, start, end);
    }
    return end;
  }

  private int overlapStart(String body, int start, int end, int limit) {
    var next = safeStart(body, end - Math.max(1, (end - start) / 10));
    var overlapLimit = Math.clamp(limit / 10, 1, MAX_OVERLAP_TOKENS);
    while (next < end && tokens(body, next, end) > overlapLimit) {
      next = safeStart(body, next + Math.max(1, (end - next) / 3));
    }
    var newline = body.indexOf('\n', next);
    if (newline >= 0 && newline + 1 < end) {
      next = newline + 1;
    }
    return next <= start || next >= end ? end : next;
  }

  private int tokens(String body, int start, int end) {
    return searchModels.countTokens(body.substring(start, end));
  }

  private static Structure analyzeStructure(String body) {
    var boundaries = new ArrayList<Boundary>();
    var fences = new ArrayList<Range>();
    var fenceStart = -1;
    for (var lineStart = 0; lineStart < body.length(); ) {
      var newline = body.indexOf('\n', lineStart);
      var lineEnd = newline < 0 ? body.length() : newline + 1;
      var contentEnd = newline < 0 ? lineEnd : newline;
      if (contentEnd > lineStart && body.charAt(contentEnd - 1) == '\r') {
        contentEnd--;
      }
      var line = body.substring(lineStart, contentEnd);
      if (FENCE.matcher(line).find()) {
        if (fenceStart < 0) {
          fenceStart = lineStart;
          boundaries.add(new Boundary(lineStart, 2));
        } else {
          fences.add(new Range(fenceStart, lineEnd));
          boundaries.add(new Boundary(lineEnd, 2));
          fenceStart = -1;
        }
      } else if (fenceStart < 0) {
        if (HEADING.matcher(line).find()) {
          boundaries.add(new Boundary(lineStart, 3));
        }
        boundaries.add(new Boundary(lineEnd, line.isBlank() ? 1 : 0));
      }
      lineStart = lineEnd;
    }
    if (fenceStart >= 0) {
      fences.add(new Range(fenceStart, body.length()));
    }
    return new Structure(List.copyOf(boundaries), List.copyOf(fences));
  }

  private static Boundary preferred(List<Boundary> boundaries, int minimum, int maximum) {
    Boundary selected = null;
    for (var boundary : boundaries) {
      if (boundary.offset() > maximum) {
        break;
      }
      if (boundary.offset() >= minimum
          && (selected == null
              || boundary.priority() > selected.priority()
              || (boundary.priority() == selected.priority()
                  && boundary.offset() > selected.offset()))) {
        selected = boundary;
      }
    }
    return selected;
  }

  private static Range containing(int offset, List<Range> ranges) {
    return ranges.stream()
        .filter(range -> offset > range.start() && offset < range.end())
        .findFirst()
        .orElse(null);
  }

  private static int safeEnd(String body, int offset) {
    return offset > 0
            && offset < body.length()
            && Character.isHighSurrogate(body.charAt(offset - 1))
            && Character.isLowSurrogate(body.charAt(offset))
        ? offset - 1
        : offset;
  }

  private static int safeStart(String body, int offset) {
    return offset > 0
            && offset < body.length()
            && Character.isHighSurrogate(body.charAt(offset - 1))
            && Character.isLowSurrogate(body.charAt(offset))
        ? offset + 1
        : offset;
  }

  private static AppException failure(String message) {
    return new AppException(OPERATION_FAILED, message, "Run `soma sync`, then retry.");
  }

  private record Boundary(int offset, int priority) {}

  private record Range(int start, int end) {}

  private record Structure(List<Boundary> boundaries, List<Range> fences) {}
}
