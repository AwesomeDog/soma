package io.github.awesomedog.soma.infra.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.search.DocumentSearch;
import io.github.awesomedog.soma.domain.config.ProjectConfig;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.project.ProjectName;
import io.github.awesomedog.soma.exec.ActiveWorkspace;
import io.github.awesomedog.soma.exec.CommandRunner;
import io.github.awesomedog.soma.exec.Invocation;
import io.github.awesomedog.soma.infra.config.YamlConfigStore;
import io.github.awesomedog.soma.infra.embedding.ManagedSearchModels;
import io.github.awesomedog.soma.infra.logging.Logging;
import io.github.awesomedog.soma.support.HostPlatform;
import io.micronaut.context.ApplicationContext;
import io.micronaut.context.RuntimeBeanDefinition;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchCommandIntegrationTest {

  private static final String ALPHA_BODY =
      """
      # Alpha Deployment Guide
      introductory line
      UniqueNeedle appears in the rate limiter using redis cache.
      closing alpha details
      """;

  @TempDir Path temporaryDirectory;

  private Path databaseFile;
  private SqliteWorkspaceIndex index;
  private RecordingSearchModels searchModels;
  private ApplicationContext context;
  private CommandRunner runner;

  @BeforeEach
  void setUp() throws Exception {
    var docsRoot = Files.createDirectories(temporaryDirectory.resolve("docs"));
    var archiveRoot = Files.createDirectories(temporaryDirectory.resolve("archive"));
    writeSearchDocuments(docsRoot, archiveRoot);

    var configFile = temporaryDirectory.resolve("config/soma/main.yml");
    databaseFile = temporaryDirectory.resolve("state/soma/main.sqlite");
    new YamlConfigStore()
        .save(
            configFile,
            new SomaConfig(
                1,
                List.of(project("docs", docsRoot, true), project("archive", archiveRoot, false)),
                List.of()));

    index =
        new SqliteWorkspaceIndex(temporaryDirectory.resolve("data"), HostPlatform.current().id());
    searchModels = new RecordingSearchModels();
    context =
        ApplicationContext.builder()
            .singletons(workspace(temporaryDirectory))
            .beanDefinitions(indexBean(), searchModelsBean())
            .start();
    runner = context.getBean(CommandRunner.class);

    var scan = run("system", "scan");
    assertThat(scan.exitCode()).isZero();
    assertThat(databaseFile).isRegularFile();
  }

  private static void writeSearchDocuments(Path docsRoot, Path archiveRoot) throws Exception {
    Files.writeString(docsRoot.resolve("alpha.md"), ALPHA_BODY);
    Files.writeString(
        docsRoot.resolve("beta.md"),
        """
        # Beta Deployment Guide
        introductory line
        The rate limiter uses memory cache.
        beta closing details
        """);
    Files.writeString(
        docsRoot.resolve("gamma.md"),
        """
        # Gamma Deployment Runbook
        introductory line
        The rate limiter uses disk cache.
        gamma closing details
        """);
    Files.writeString(
        archiveRoot.resolve("old.md"),
        """
        # Archived Deployment Guide
        ArchiveOnly material describes the rate limiter.
        """);
  }

  @AfterEach
  void tearDown() {
    if (context != null) {
      context.close();
    }
    if (index != null) {
      index.close();
    }
    Logging.close();
  }

  @Test
  void searchesTheRealFtsIndexWithScopesQuerySyntaxAndLimits() {
    var defaultScope = search(run("search", "lexical", "deployment", "--no-limit"));
    assertThat(paths(defaultScope))
        .containsExactlyInAnyOrder(
            "soma://docs/alpha.md", "soma://docs/beta.md", "soma://docs/gamma.md")
        .noneMatch(path -> path.contains("archive"));

    var archive =
        search(run("search", "lexical", "deployment", "--project", "archive", "--no-limit"));
    assertThat(paths(archive)).containsExactly("soma://archive/old.md");

    assertThat(paths(search(run("search", "lexical", "deplo", "--no-limit"))))
        .containsExactlyInAnyOrder(
            "soma://docs/alpha.md", "soma://docs/beta.md", "soma://docs/gamma.md");
    assertThat(paths(search(run("search", "lexical", "\"rate limiter\"", "--no-limit"))))
        .containsExactlyInAnyOrder(
            "soma://docs/alpha.md", "soma://docs/beta.md", "soma://docs/gamma.md");
    assertThat(paths(search(run("search", "lexical", "\"rate limiter\" -redis", "--no-limit"))))
        .containsExactlyInAnyOrder("soma://docs/beta.md", "soma://docs/gamma.md");
    assertThat(
            paths(
                search(
                    run("search", "lexical", "\"rate limiter\" -\"memory cache\"", "--no-limit"))))
        .containsExactlyInAnyOrder("soma://docs/alpha.md", "soma://docs/gamma.md");

    assertThat(search(run("search", "lexical", "deployment", "--limit", "1")).results()).hasSize(1);
    assertThat(search(run("search", "lexical", "deployment", "--no-limit")).results()).hasSize(3);
  }

  @Test
  void searchesRealVectorsAndRunsTheFullyManualHybridPipeline() throws Exception {
    assertRealVectorSearchUsesEmbeddedIndex();

    searchModels.reset();
    var hybridResult =
        run(
            "search",
            "hybrid",
            "--lex",
            "deployment",
            "--vec",
            "semantic alpha",
            "--hyde",
            "hypothetical alpha deployment",
            "--limit",
            "2",
            "--format",
            "json");
    var hybrid = search(hybridResult);
    assertThat(hybrid.mode()).isEqualTo("hybrid");
    assertThat(paths(hybrid)).first().isEqualTo("soma://docs/alpha.md");
    assertThat(searchModels.expandedInputs).isEmpty();
    assertThat(searchModels.embeddedInputs)
        .containsExactly(
            "task: search result | query: semantic alpha",
            "task: search result | query: hypothetical alpha deployment");
    assertThat(searchModels.rerankCalls)
        .singleElement()
        .satisfies(
            call -> {
              assertThat(call.query()).isEqualTo("deployment");
              assertThat(call.candidates()).isNotEmpty();
              assertThat(call.limit()).isEqualTo(call.candidates().size());
            });
  }

  private void assertRealVectorSearchUsesEmbeddedIndex() throws Exception {
    var beforeEmbed = run("search", "vector", "semantic alpha");
    assertThat(beforeEmbed.exitCode()).isEqualTo(1);

    var embed = run("system", "embed");
    assertThat(embed.exitCode()).isZero();
    assertThat(vectorCount(List.of("docs", "archive"))).isEqualTo(4);

    searchModels.reset();
    var vector =
        search(run("search", "vector", "semantic alpha", "--no-limit", "--format", "json"));
    assertThat(vector.mode()).isEqualTo("vector");
    assertThat(paths(vector))
        .startsWith("soma://docs/alpha.md")
        .doesNotContain("soma://archive/old.md");
    assertThat(searchModels.expandedInputs).isEmpty();
    assertThat(searchModels.embeddedInputs)
        .containsExactly("task: search result | query: semantic alpha");
    assertThat(searchModels.rerankCalls).isEmpty();
  }

  @Test
  void vectorIntentChangesTheEmbeddingAndCandidateRecall() {
    assertThat(run("system", "embed").exitCode()).isZero();

    searchModels.reset();
    var alpha = search(run("search", "vector", "semantic", "--intent", "alpha", "--limit", "1"));
    var beta = search(run("search", "vector", "semantic", "--intent", "beta", "--limit", "1"));

    assertThat(paths(alpha)).containsExactly("soma://docs/alpha.md");
    assertThat(paths(beta)).containsExactly("soma://docs/beta.md");
    assertThat(searchModels.embeddedInputs)
        .containsExactly(
            "task: search result | query: semantic\nQuery intent: alpha",
            "task: search result | query: semantic\nQuery intent: beta");
  }

  private RuntimeBeanDefinition<WorkspaceIndex> indexBean() {
    return RuntimeBeanDefinition.builder(WorkspaceIndex.class, () -> index)
        .replaces(SqliteWorkspaceIndex.class)
        .build();
  }

  private RuntimeBeanDefinition<SearchModels> searchModelsBean() {
    return RuntimeBeanDefinition.builder(SearchModels.class, () -> searchModels)
        .replaces(ManagedSearchModels.class)
        .build();
  }

  private static ActiveWorkspace workspace(Path root) throws Exception {
    var resolverType = Class.forName("io.github.awesomedog.soma.exec.WorkspaceResolver");
    var environment =
        Map.of(
            "XDG_CONFIG_HOME", root.resolve("config").toString(),
            "XDG_STATE_HOME", root.resolve("state").toString());
    var resolverConstructor =
        accessible(resolverType.getDeclaredConstructor(Map.class, Path.class, Path.class));
    var resolver = resolverConstructor.newInstance(environment, root, root.resolve("home"));
    var workspaceConstructor =
        accessible(ActiveWorkspace.class.getDeclaredConstructor(resolverType));
    return workspaceConstructor.newInstance(resolver);
  }

  private static <T> Constructor<T> accessible(Constructor<T> constructor) {
    constructor.setAccessible(true);
    return constructor;
  }

  private static ProjectConfig project(String name, Path root, boolean defaultSearch) {
    return new ProjectConfig(
        new ProjectName(name), root, List.of("**/*.md"), List.of(), defaultSearch, false);
  }

  private long vectorCount(List<String> projectNames) {
    return index.projectStats(databaseFile, projectNames).values().stream()
        .mapToLong(stats -> stats.vectors())
        .sum();
  }

  private CommandResult run(String... arguments) {
    var invocation = Invocation.captured();
    return new CommandResult(runner.run(arguments, invocation), invocation);
  }

  private static DocumentSearch.Result search(CommandResult result) {
    assertThat(result.exitCode()).isZero();
    assertThat(result.invocation().result()).isInstanceOf(DocumentSearch.Result.class);
    return (DocumentSearch.Result) result.invocation().result();
  }

  private static List<String> paths(DocumentSearch.Result result) {
    return result.results().stream().map(DocumentSearch.Item::virtualPath).toList();
  }

  private record CommandResult(int exitCode, Invocation invocation) {}

  private static final class RecordingSearchModels implements SearchModels {

    private final List<String> expandedInputs = new ArrayList<>();
    private final List<String> embeddedInputs = new ArrayList<>();
    private final List<RerankCall> rerankCalls = new ArrayList<>();

    @Override
    public EmbeddingMetadata embeddingMetadata() {
      return new EmbeddingMetadata(
          "integration-model-recipe", "integration-tokenizer-recipe", 768, 2048);
    }

    @Override
    public int countTokens(String input) {
      return Math.max(1, input.length() / 4);
    }

    @Override
    public float[] embed(String input) {
      embeddedInputs.add(input);
      var lower = input.toLowerCase(Locale.ROOT);
      var vector = new float[768];
      if (lower.contains("alpha")) {
        vector[0] = 1.0f;
      } else if (lower.contains("beta")) {
        vector[1] = 1.0f;
      } else if (lower.contains("gamma")) {
        vector[2] = 1.0f;
      } else if (lower.contains("archive")) {
        vector[3] = 1.0f;
      } else {
        vector[4] = 1.0f;
      }
      return vector;
    }

    @Override
    public Expansion expand(String query) {
      expandedInputs.add(query);
      return new Expansion(List.of(query), List.of(query), List.of(query));
    }

    @Override
    public List<RerankScore> rerank(String query, List<String> candidateTexts, int limit) {
      rerankCalls.add(new RerankCall(query, List.copyOf(candidateTexts), limit));
      var scores = new ArrayList<RerankScore>(candidateTexts.size());
      for (var position = 0; position < candidateTexts.size(); position++) {
        var score =
            candidateTexts.get(position).toLowerCase(Locale.ROOT).contains("alpha")
                ? 1.0
                : 0.1 / (position + 1);
        scores.add(new RerankScore(position, score));
      }
      return List.copyOf(scores);
    }

    private void reset() {
      expandedInputs.clear();
      embeddedInputs.clear();
      rerankCalls.clear();
    }
  }

  private record RerankCall(String query, List<String> candidates, int limit) {}
}
