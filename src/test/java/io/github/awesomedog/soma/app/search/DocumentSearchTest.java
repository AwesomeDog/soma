package io.github.awesomedog.soma.app.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.ConfigStore;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.domain.config.ContextConfig;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.domain.search.LexicalQuery;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class DocumentSearchTest {

  private static final Path CONFIG = Path.of("config.yml");
  private static final Path DATABASE = Path.of("workspace.sqlite");

  @Test
  void hybridManualInputsOverrideOnlyTheirExpansionBranch() {
    var index = new RecordingIndex();
    var searchModels = new RecordingSearchModels();
    searchModels.expansion =
        new SearchModels.Expansion(
            List.of("expanded lexical ignored"),
            List.of("expanded semantic"),
            List.of("expanded hypothetical"));
    var contentHash = hash(1);
    index.vectorHits =
        List.of(hit("docs", "guide.md", contentHash, "original query", "original query", 0.9));
    index.chunks = List.of(new WorkspaceIndex.ChunkRead(contentHash, 0, "original query", 0, 14));
    service(config(), index, searchModels)
        .search(
            CONFIG,
            DATABASE,
            hybridRequest(
                "original query", "manual exact", null, null, "migration intent", false, false),
            null);

    assertThat(searchModels.expansionInputs)
        .containsExactly("original query\nQuery intent: migration intent");
    assertThat(index.lexicalCalls)
        .extracting(
            call ->
                call.query().clauses().stream()
                    .filter(clause -> !clause.phrase() && !clause.excluded())
                    .map(LexicalQuery.Clause::text)
                    .toList())
        .containsExactly(List.of("original", "query"), List.of("manual", "exact"));
    assertThat(searchModels.embeddingInputs)
        .containsExactly(
            "task: search result | query: original query\nQuery intent: migration intent",
            "task: search result | query: expanded semantic",
            "task: search result | query: expanded hypothetical");
  }

  @Test
  void hybridPassesContextToRerankingWithoutUsingItToChooseEvidence() {
    var docs = project("docs", true);
    var contextText = "alpha bravo charlie delta echo foxtrot";
    var config =
        new SomaConfig(1, List.of(docs), List.of(new ContextConfig(docs.name(), "/", contextText)));
    var index = new RecordingIndex();
    var contentHash = hash(1);
    index.lexicalHits =
        List.of(hit("docs", "guide.md", contentHash, "needle body", "needle body", 0.9));
    index.chunks =
        List.of(
            new WorkspaceIndex.ChunkRead(contentHash, 0, contextText, 0, contextText.length()),
            new WorkspaceIndex.ChunkRead(contentHash, 1, "needle answer", 50, 63));
    var searchModels = new RecordingSearchModels();

    service(config, index, searchModels)
        .search(
            CONFIG, DATABASE, hybridRequest(null, "needle", null, null, null, false, false), null);

    assertThat(searchModels.rerankCandidates.getFirst())
        .containsExactly("Context:\n" + contextText + "\n\nDocument excerpt:\nneedle answer");
  }

  @Test
  void hybridUsesPositiveShortAndCjkTermsToChooseEvidence() {
    var index = new RecordingIndex();
    var contentHash = hash(1);
    index.lexicalHits = List.of(hit("docs", "guide.md", contentHash, "body", "body", 0.9));
    index.chunks =
        List.of(
            new WorkspaceIndex.ChunkRead(contentHash, 0, "wrong", 0, 5),
            new WorkspaceIndex.ChunkRead(contentHash, 1, "go 中文", 6, 11));
    var searchModels = new RecordingSearchModels();

    service(config(), index, searchModels)
        .search(
            CONFIG,
            DATABASE,
            hybridRequest(null, "go 中文 -wrong", null, null, null, false, false),
            null);

    assertThat(searchModels.rerankCandidates.getFirst()).containsExactly("go 中文");
  }

  @Test
  void hybridPreservesVectorEvidenceWhenLexicalHitCreatedCandidate() {
    var index = new RecordingIndex();
    var contentHash = hash(1);
    var documentBody = "unrelated first chunk\nsemantic answer";
    index.lexicalHits =
        List.of(hit("docs", "guide.md", contentHash, documentBody, documentBody, 0.9));
    index.vectorHits =
        List.of(
            vectorHit("docs", "guide.md", contentHash, "semantic answer", documentBody, 1, 0.8));
    index.chunks =
        List.of(
            new WorkspaceIndex.ChunkRead(contentHash, 0, "unrelated first chunk", 0, 21),
            new WorkspaceIndex.ChunkRead(contentHash, 1, "semantic answer", 22, 37));
    var searchModels = new RecordingSearchModels();

    service(config(), index, searchModels)
        .search(
            CONFIG,
            DATABASE,
            hybridRequest(null, "literal mismatch", "semantic query", null, null, false, false),
            null);

    assertThat(searchModels.rerankCandidates.getFirst()).containsExactly("semantic answer");
  }

  @Test
  void hybridUsesSearchInputBeforeIntentToChooseEvidence() {
    var index = new RecordingIndex();
    var contentHash = hash(1);
    index.lexicalHits = List.of(hit("docs", "guide.md", contentHash, "body", "body", 0.9));
    index.chunks =
        List.of(
            new WorkspaceIndex.ChunkRead(contentHash, 0, "background details", 0, 18),
            new WorkspaceIndex.ChunkRead(contentHash, 1, "primary answer", 19, 33));
    var searchModels = new RecordingSearchModels();

    service(config(), index, searchModels)
        .search(
            CONFIG,
            DATABASE,
            hybridRequest(null, "primary", null, null, "background", false, false),
            null);

    assertThat(searchModels.rerankCandidates.getFirst()).containsExactly("primary answer");
  }

  @Test
  void validatesLexicalInputWhenSearchScopeIsEmpty() {
    assertThatThrownBy(
            () ->
                service(
                        new SomaConfig(1, List.of(), List.of()),
                        new RecordingIndex(),
                        new RecordingSearchModels())
                    .search(
                        CONFIG,
                        DATABASE,
                        hybridRequest(null, "-excluded", null, null, null, false, false),
                        null))
        .isInstanceOfSatisfying(
            AppException.class,
            error -> assertThat(error.error().code()).isEqualTo(AppError.Code.INVALID_REQUEST));
  }

  @Test
  void canonicalizesAndDeduplicatesExplicitProjectScope() {
    var index = new RecordingIndex();

    service(config(), index, new RecordingSearchModels())
        .search(CONFIG, DATABASE, lexicalRequest("needle", List.of("docs", "docs!")), null);

    assertThat(index.lexicalCalls)
        .extracting(LexicalCall::projects)
        .containsExactly(List.of("docs"));
  }

  @Test
  void rejectsMissingExplicitProject() {
    assertThatThrownBy(
            () ->
                service(config(), new RecordingIndex(), new RecordingSearchModels())
                    .search(CONFIG, DATABASE, lexicalRequest("needle", List.of("missing")), null))
        .isInstanceOfSatisfying(
            AppException.class,
            error -> assertThat(error.error().code()).isEqualTo(AppError.Code.NOT_FOUND));
  }

  @Test
  void rejectsInvalidExplicitProjectName() {
    assertThatThrownBy(
            () ->
                service(config(), new RecordingIndex(), new RecordingSearchModels())
                    .search(CONFIG, DATABASE, lexicalRequest("needle", List.of("!!!")), null))
        .isInstanceOfSatisfying(
            AppException.class,
            error -> assertThat(error.error().code()).isEqualTo(AppError.Code.INVALID_REQUEST));
  }

  private static DocumentSearch service(
      SomaConfig config, RecordingIndex index, RecordingSearchModels searchModels) {
    return new DocumentSearch(new StaticConfig(config), index.proxy(), searchModels);
  }

  private static DocumentSearch.Request hybridRequest(
      String query,
      String lexical,
      String vector,
      String hyde,
      String intent,
      boolean noLimit,
      boolean verbose) {
    return new DocumentSearch.Request(
        DocumentSearch.Mode.hybrid,
        query,
        lexical,
        vector,
        hyde,
        intent,
        List.of(),
        10,
        noLimit,
        false,
        false,
        false,
        verbose);
  }

  private static DocumentSearch.Request lexicalRequest(String query, List<String> projects) {
    return new DocumentSearch.Request(
        DocumentSearch.Mode.lexical,
        query,
        null,
        null,
        null,
        null,
        projects,
        10,
        false,
        false,
        false,
        false,
        false);
  }

  private static SomaConfig config() {
    return new SomaConfig(1, List.of(project("docs", true), project("notes", false)), List.of());
  }

  private static ProjectConfig project(String name, boolean defaultSearch) {
    var root =
        Path.of(System.getProperty("java.io.tmpdir"), "soma-search-" + name).toAbsolutePath();
    return new ProjectConfig(
        new ProjectName(name), root, List.of("**/*"), List.of(), defaultSearch, false);
  }

  private static WorkspaceIndex.SearchHit hit(
      String project,
      String path,
      String contentHash,
      String evidenceBody,
      String documentBody,
      double score) {
    return new WorkspaceIndex.SearchHit(
        project,
        path,
        title(path),
        contentHash,
        evidenceBody,
        documentBody,
        null,
        null,
        null,
        score);
  }

  private static WorkspaceIndex.SearchHit vectorHit(
      String project,
      String path,
      String contentHash,
      String evidenceBody,
      String documentBody,
      int chunkIndex,
      double score) {
    var startOffset = documentBody.indexOf(evidenceBody);
    return new WorkspaceIndex.SearchHit(
        project,
        path,
        title(path),
        contentHash,
        evidenceBody,
        documentBody,
        chunkIndex,
        startOffset,
        startOffset + evidenceBody.length(),
        score);
  }

  private static String title(String path) {
    var filename = path.substring(path.lastIndexOf('/') + 1);
    var extension = filename.lastIndexOf('.');
    return extension < 0 ? filename : filename.substring(0, extension);
  }

  private static String hash(int number) {
    return String.format(Locale.ROOT, "%064x", number);
  }

  private record StaticConfig(SomaConfig config) implements ConfigStore {

    @Override
    public SomaConfig load(Path ignored) {
      return config;
    }

    @Override
    public SomaConfig loadOrBackupResetForUpdate(Path ignored) {
      throw new AssertionError("Unexpected config update load");
    }

    @Override
    public void save(Path ignored, SomaConfig ignoredConfig) {
      throw new AssertionError("Unexpected config save");
    }
  }

  private record LexicalCall(List<String> projects, LexicalQuery query, int limit) {}

  private static final class RecordingIndex implements InvocationHandler {

    private final WorkspaceIndex proxy =
        (WorkspaceIndex)
            Proxy.newProxyInstance(
                WorkspaceIndex.class.getClassLoader(), new Class<?>[] {WorkspaceIndex.class}, this);
    private final List<LexicalCall> lexicalCalls = new ArrayList<>();
    private List<WorkspaceIndex.SearchHit> lexicalHits = List.of();
    private List<WorkspaceIndex.SearchHit> vectorHits = List.of();
    private List<WorkspaceIndex.ChunkRead> chunks = List.of();

    WorkspaceIndex proxy() {
      return proxy;
    }

    @Override
    public Object invoke(Object ignored, Method method, Object[] arguments) {
      return switch (method.getName()) {
        case "lexicalSearch" -> {
          var projects = strings(arguments[1]);
          var limit = (Integer) arguments[3];
          lexicalCalls.add(new LexicalCall(projects, (LexicalQuery) arguments[2], limit));
          yield lexicalHits.stream().limit(limit).toList();
        }
        case "vectorSearch" -> {
          var limit = (Integer) arguments[3];
          yield vectorHits.stream().limit(limit).toList();
        }
        case "chunks" -> {
          var requested = strings(arguments[1]);
          var requestedSet = new LinkedHashSet<>(requested);
          yield chunks.stream()
              .filter(chunk -> requestedSet.contains(chunk.contentHash()))
              .toList();
        }
        case "toString" -> "RecordingIndex";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == arguments[0];
        default -> throw new AssertionError("Unexpected index call: " + method);
      };
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
      return List.copyOf((List<String>) value);
    }
  }

  private static final class RecordingSearchModels implements SearchModels {

    private final List<String> embeddingInputs = new ArrayList<>();
    private final List<String> expansionInputs = new ArrayList<>();
    private final List<List<String>> rerankCandidates = new ArrayList<>();
    private Expansion expansion = new Expansion(List.of(), List.of(), List.of());

    @Override
    public EmbeddingMetadata embeddingMetadata() {
      return new EmbeddingMetadata("model-recipe", "tokenizer-recipe", 768, 2048);
    }

    @Override
    public int countTokens(String input) {
      return 1;
    }

    @Override
    public float[] embed(String input) {
      embeddingInputs.add(input);
      return new float[768];
    }

    @Override
    public Expansion expand(String query) {
      expansionInputs.add(query);
      return expansion;
    }

    @Override
    public List<RerankScore> rerank(String query, List<String> candidateTexts, int limit) {
      rerankCandidates.add(List.copyOf(candidateTexts));
      var scores = new ArrayList<RerankScore>(candidateTexts.size());
      for (var index = 0; index < candidateTexts.size(); index++) {
        scores.add(new RerankScore(index, 0.5));
      }
      return List.copyOf(scores);
    }
  }
}
