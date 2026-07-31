package io.github.awesomedog.soma.app.search;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;
import static io.github.awesomedog.soma.app.search.DocumentSearch.firstNonBlank;
import static io.github.awesomedog.soma.app.search.DocumentSearch.hasText;
import static io.github.awesomedog.soma.app.search.DocumentSearch.nullToEmpty;
import static io.github.awesomedog.soma.app.search.DocumentSearch.queryWithIntent;
import static io.github.awesomedog.soma.app.search.SearchResultAssembler.normalize;
import static io.github.awesomedog.soma.app.search.SearchResultAssembler.positiveSearchTerms;
import static io.github.awesomedog.soma.app.search.SearchResultAssembler.searchTerms;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.app.ports.SearchModels;
import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.domain.config.SomaConfig;
import io.github.awesomedog.soma.domain.search.LexicalQuery;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

final class HybridSearch {

  private static final double RRF_K = 60.0;
  private static final double RANK_ONE_BONUS = 0.05;
  private static final double RANK_TWO_THREE_BONUS = 0.02;
  private static final int RERANK_CANDIDATE_LIMIT = 40;
  private static final double TOP_THREE_FUSION_PRIOR_WEIGHT = 0.75;
  private static final double TOP_TEN_FUSION_PRIOR_WEIGHT = 0.60;
  private static final double REMAINING_FUSION_PRIOR_WEIGHT = 0.40;
  private final CandidateRetriever candidateRetriever;
  private final WorkspaceIndex workspaceIndex;
  private final SearchModels searchModels;

  HybridSearch(
      CandidateRetriever candidateRetriever,
      WorkspaceIndex workspaceIndex,
      SearchModels searchModels) {
    this.candidateRetriever = Objects.requireNonNull(candidateRetriever, "candidateRetriever");
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
    this.searchModels = Objects.requireNonNull(searchModels, "searchModels");
  }

  int minimumCandidateLimit() {
    return RERANK_CANDIDATE_LIMIT;
  }

  List<DocumentSearch.Match> search(
      Path databaseFile,
      SomaConfig config,
      List<String> projectNames,
      DocumentSearch.Request request,
      int candidateLimit,
      Consumer<String> progress) {
    var resolvedInputs = resolveInputs(request, progress);
    var queries = buildCandidateQueries(request, resolvedInputs);
    progress.accept(
        "Searching " + queries.size() + " " + plural(queries.size(), "query", "queries") + "...");

    var rankings =
        retrieveCandidates(databaseFile, projectNames, queries, candidateLimit, progress);
    if (request.verbose()) {
      traceCandidateRetrieval(rankings, progress);
    }
    var fused = fuse(config, rankings);
    if (fused.isEmpty()) {
      return List.of();
    }
    if (request.verbose()) {
      traceFusion(fused, progress);
    }
    return rerank(databaseFile, fused, request, progress).stream().map(Candidate::toMatch).toList();
  }

  private List<CandidateRanking> retrieveCandidates(
      Path databaseFile,
      List<String> projectNames,
      List<CandidateQuery> queries,
      int candidateLimit,
      Consumer<String> progress) {
    var rankings = new ArrayList<CandidateRanking>(queries.size());
    for (var query : queries) {
      if (!query.usesVectorSearch()) {
        rankings.add(
            new CandidateRanking(
                query,
                candidateRetriever.lexical(
                    databaseFile, projectNames, query.lexicalQuery(), candidateLimit)));
      }
    }

    var vectorQueries = queries.stream().filter(CandidateQuery::usesVectorSearch).toList();
    if (vectorQueries.isEmpty()) {
      return List.copyOf(rankings);
    }
    progress.accept(
        "Embedding "
            + vectorQueries.size()
            + " "
            + plural(vectorQueries.size(), "query", "queries")
            + "...");
    var embeddings =
        candidateRetriever.embedQueries(
            vectorQueries.stream().map(CandidateQuery::embeddingText).toList());
    progress.accept("Embedding done.");

    for (var index = 0; index < vectorQueries.size(); index++) {
      var query = vectorQueries.get(index);
      var hits =
          candidateRetriever.vector(
              databaseFile, projectNames, embeddings.get(index), candidateLimit);
      candidateRetriever.requireVectorHits(hits);
      rankings.add(new CandidateRanking(query, hits));
    }
    return List.copyOf(rankings);
  }

  private List<CandidateQuery> buildCandidateQueries(
      DocumentSearch.Request request, ResolvedInputs inputs) {
    var queries = new ArrayList<CandidateQuery>();
    if (hasText(request.query())) {
      queries.add(
          CandidateQuery.lexical(
              "positional", 2.0, candidateRetriever.parseLexical(request.query(), false)));
    }
    for (var input : inputs.lexicalInputs()) {
      queries.add(
          CandidateQuery.lexical(
              "lex",
              1.0,
              candidateRetriever.parseLexical(input, inputs.lexicalInputsFromExpansion())));
    }
    if (hasText(request.query())) {
      queries.add(
          CandidateQuery.vector(
              "positional", 2.0, queryWithIntent(request.query(), request.intent())));
    }
    for (var input : inputs.vectorInputs()) {
      queries.add(
          CandidateQuery.vector(
              "vec",
              1.0,
              hasText(request.vectorInput()) ? queryWithIntent(input, request.intent()) : input));
    }
    for (var passage : inputs.hydePassages()) {
      queries.add(
          CandidateQuery.vector(
              "hyde",
              1.0,
              hasText(request.hydeInput()) ? queryWithIntent(passage, request.intent()) : passage));
    }
    return List.copyOf(queries);
  }

  private ResolvedInputs resolveInputs(DocumentSearch.Request request, Consumer<String> progress) {
    var expansion = expandIfNeeded(request, progress);
    var lexicalInputs =
        resolveBranchInputs(
            request.lexicalInput(),
            expansion == null ? null : expansion.lexical(),
            request.query());
    var vectorInputs =
        resolveBranchInputs(
            request.vectorInput(), expansion == null ? null : expansion.vector(), request.query());
    var hydePassages =
        resolveBranchInputs(
            request.hydeInput(), expansion == null ? null : expansion.hyde(), request.query());
    requireCompleteExpansion(request, lexicalInputs, vectorInputs, hydePassages);
    return new ResolvedInputs(
        lexicalInputs,
        vectorInputs,
        hydePassages,
        expansion != null && !hasText(request.lexicalInput()));
  }

  private SearchModels.Expansion expandIfNeeded(
      DocumentSearch.Request request, Consumer<String> progress) {
    if (!requiresExpansion(request)) {
      return null;
    }
    progress.accept("Expanding query...");
    var expansion = searchModels.expand(queryWithIntent(request.query(), request.intent()));
    progress.accept("Expanded query.");
    return expansion == null
        ? new SearchModels.Expansion(List.of(), List.of(), List.of())
        : expansion;
  }

  private static boolean requiresExpansion(DocumentSearch.Request request) {
    return hasText(request.query())
        && (!hasText(request.lexicalInput())
            || !hasText(request.vectorInput())
            || !hasText(request.hydeInput()));
  }

  private static List<String> resolveBranchInputs(
      String providedInput, List<String> expandedInputs, String query) {
    var provided = hasText(providedInput);
    return removeDuplicateInputs(
        provided ? List.of(providedInput) : expandedInputs, provided ? null : query);
  }

  private static void requireCompleteExpansion(
      DocumentSearch.Request request,
      List<String> lexicalInputs,
      List<String> vectorInputs,
      List<String> hydePassages) {
    if (!hasText(request.query())) {
      return;
    }
    var missing = new ArrayList<String>();
    if (!hasText(request.lexicalInput()) && lexicalInputs.isEmpty()) {
      missing.add("lex");
    }
    if (!hasText(request.vectorInput()) && vectorInputs.isEmpty()) {
      missing.add("vec");
    }
    if (!hasText(request.hydeInput()) && hydePassages.isEmpty()) {
      missing.add("hyde");
    }
    if (!missing.isEmpty()) {
      throw new AppException(
          OPERATION_FAILED,
          "Query expansion did not return required " + String.join(", ", missing) + " inputs.",
          "Retry, or pass `--lex`, `--vec`, and `--hyde` manually.");
    }
  }

  private static List<Candidate> fuse(SomaConfig config, List<CandidateRanking> rankings) {
    var candidatesByPath = new LinkedHashMap<String, Candidate>();
    for (var ranking : rankings) {
      var seenPaths = new LinkedHashSet<String>();
      var rank = 0;
      for (var hit : ranking.hits()) {
        if (!hasText(hit.documentBody())) {
          continue;
        }
        if (!seenPaths.add(hit.virtualPath())) {
          continue;
        }
        rank++;
        var candidate =
            candidatesByPath.computeIfAbsent(
                hit.virtualPath(),
                ignored -> new Candidate(hit, config.effectiveContext(hit.project(), hit.path())));
        var contribution = ranking.query().weight() / (RRF_K + rank);
        candidate.weightedRrfScore += contribution;
        candidate.bestSourceRank = Math.min(candidate.bestSourceRank, rank);
      }
    }

    var ranked = new ArrayList<>(candidatesByPath.values());
    for (var candidate : ranked) {
      candidate.bestRankBonus =
          candidate.bestSourceRank == 1
              ? RANK_ONE_BONUS
              : candidate.bestSourceRank <= 3 ? RANK_TWO_THREE_BONUS : 0.0;
      candidate.fusionScore = candidate.weightedRrfScore + candidate.bestRankBonus;
      candidate.finalScore = candidate.fusionScore;
    }
    ranked.sort(Candidate.FUSION_ORDER);
    for (var index = 0; index < ranked.size(); index++) {
      ranked.get(index).fusionRank = index + 1;
    }
    return List.copyOf(ranked);
  }

  private List<Candidate> rerank(
      Path databaseFile,
      List<Candidate> fused,
      DocumentSearch.Request request,
      Consumer<String> progress) {
    var rerankCount = Math.min(RERANK_CANDIDATE_LIMIT, fused.size());
    var candidates = selectEvidence(databaseFile, fused.subList(0, rerankCount), request);
    progress.accept(
        "Reranking "
            + candidates.size()
            + " "
            + plural(candidates.size(), "chunk", "chunks")
            + "...");
    var rerankScores =
        searchModels.rerank(
            buildRerankingQuery(request),
            candidates.stream().map(HybridSearch::formatRerankingDocument).toList(),
            candidates.size());
    if (rerankScores == null || rerankScores.size() != candidates.size()) {
      throw new AppException(
          OPERATION_FAILED,
          "Reranking did not score every candidate.",
          "Retry, narrow the query or project scope, or use a smaller `--limit`.");
    }

    var reranked =
        new ArrayList<>(blend(candidates, rerankScores, request.verbose() ? progress : null));
    if (fused.size() > rerankCount) {
      reranked.addAll(fused.subList(rerankCount, fused.size()));
    }
    progress.accept("Reranking done.");
    return List.copyOf(reranked);
  }

  private static List<Candidate> blend(
      List<Candidate> candidates,
      List<SearchModels.RerankScore> rerankScores,
      Consumer<String> trace) {
    var reranked = new ArrayList<Candidate>(rerankScores.size());
    var scoredIndexes = new boolean[candidates.size()];
    for (var rerankScore : rerankScores) {
      if (rerankScore == null
          || rerankScore.candidateIndex() < 0
          || rerankScore.candidateIndex() >= candidates.size()
          || scoredIndexes[rerankScore.candidateIndex()]
          || !Double.isFinite(rerankScore.score())) {
        throw new AppException(
            OPERATION_FAILED,
            "Reranking returned invalid candidate scores.",
            "Retry, or check the managed search runtime.");
      }
      scoredIndexes[rerankScore.candidateIndex()] = true;
      var candidate = candidates.get(rerankScore.candidateIndex());
      var fusionRank = candidate.fusionRank;
      var fusionPriorScore = 1.0 / fusionRank;
      var fusionPriorWeight =
          fusionRank <= 3
              ? TOP_THREE_FUSION_PRIOR_WEIGHT
              : fusionRank <= 10 ? TOP_TEN_FUSION_PRIOR_WEIGHT : REMAINING_FUSION_PRIOR_WEIGHT;
      candidate.finalScore =
          fusionPriorWeight * fusionPriorScore + (1.0 - fusionPriorWeight) * rerankScore.score();
      reranked.add(candidate);
      if (trace != null && fusionRank <= 5) {
        trace.accept(
            "└─ Blend "
                + candidate.hit.virtualPath()
                + " "
                + percent(fusionPriorWeight)
                + "*"
                + DisplayFormat.score(fusionPriorScore)
                + " + "
                + percent(1.0 - fusionPriorWeight)
                + "*"
                + DisplayFormat.score(rerankScore.score())
                + " = "
                + DisplayFormat.score(candidate.finalScore));
      }
    }
    reranked.sort(Candidate.FINAL_ORDER);
    return List.copyOf(reranked);
  }

  private List<Candidate> selectEvidence(
      Path databaseFile, List<Candidate> candidates, DocumentSearch.Request request) {
    var hashes =
        candidates.stream()
            .map(candidate -> candidate.hit.contentHash())
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    var chunksByHash = new LinkedHashMap<String, List<WorkspaceIndex.ChunkRead>>();
    for (var chunk : workspaceIndex.chunks(databaseFile, hashes)) {
      chunksByHash.computeIfAbsent(chunk.contentHash(), ignored -> new ArrayList<>()).add(chunk);
    }
    var selectionQuery =
        firstNonBlank(
            request.intent(),
            request.query(),
            request.lexicalInput(),
            request.vectorInput(),
            request.hydeInput());
    var selected = new ArrayList<Candidate>(candidates.size());
    for (var candidate : candidates) {
      var chunks = chunksByHash.getOrDefault(candidate.hit.contentHash(), List.of());
      if (chunks.isEmpty()) {
        throw new AppException(
            OPERATION_FAILED,
            "Persisted chunks are missing for reranking evidence.",
            "Run `soma sync`, then retry.");
      }
      candidate.selectEvidence(bestChunk(chunks, selectionQuery, request.intent()));
      selected.add(candidate);
    }
    return List.copyOf(selected);
  }

  private static WorkspaceIndex.ChunkRead bestChunk(
      List<WorkspaceIndex.ChunkRead> chunks, String query, String intent) {
    var best = chunks.getFirst();
    var bestScore = evidenceScore(query, intent, best.body());
    for (var index = 1; index < chunks.size(); index++) {
      var candidate = chunks.get(index);
      var score = evidenceScore(query, intent, candidate.body());
      if (score > bestScore) {
        best = candidate;
        bestScore = score;
      }
    }
    return best;
  }

  private static double evidenceScore(String query, String intent, String body) {
    return matchingTermScore(positiveSearchTerms(query), body, 1.0)
        + matchingTermScore(searchTerms(intent), body, 0.5);
  }

  private static double matchingTermScore(List<String> queryTerms, String body, double weight) {
    if (queryTerms.isEmpty() || !hasText(body)) {
      return 0.0;
    }
    var normalizedBody = normalize(body);
    return queryTerms.stream().filter(normalizedBody::contains).count() * weight;
  }

  private static String formatRerankingDocument(Candidate candidate) {
    var evidence = nullToEmpty(candidate.hit.evidenceBody());
    return candidate.context.isBlank()
        ? evidence
        : "Context:\n" + candidate.context + "\n\nDocument excerpt:\n" + evidence;
  }

  private static String buildRerankingQuery(DocumentSearch.Request request) {
    var query =
        firstNonBlank(
            request.query(), request.lexicalInput(), request.vectorInput(), request.hydeInput());
    return hasText(request.intent()) ? request.intent() + "\n\n" + query : query;
  }

  private static List<String> removeDuplicateInputs(List<String> inputs, String queryToExclude) {
    if (inputs == null || inputs.isEmpty()) {
      return List.of();
    }
    var excluded = normalize(nullToEmpty(queryToExclude).strip());
    var normalizedInputs = new LinkedHashSet<String>();
    var unique = new ArrayList<String>();
    for (var input : inputs) {
      if (!hasText(input)) {
        continue;
      }
      var trimmed = input.strip();
      var normalized = normalize(trimmed);
      if (!normalized.equals(excluded) && normalizedInputs.add(normalized)) {
        unique.add(trimmed);
      }
    }
    return List.copyOf(unique);
  }

  private static void traceCandidateRetrieval(
      List<CandidateRanking> rankings, Consumer<String> progress) {
    for (var ranking : rankings) {
      ranking.hits().stream()
          .limit(3)
          .forEach(
              hit ->
                  progress.accept(
                      "└─ "
                          + ranking.query().searchMethod()
                          + "/"
                          + ranking.query().inputKind()
                          + " "
                          + hit.virtualPath()
                          + " score="
                          + DisplayFormat.score(hit.score())));
    }
  }

  private static void traceFusion(List<Candidate> candidates, Consumer<String> progress) {
    progress.accept(
        "Fusion ranked "
            + candidates.size()
            + " "
            + plural(candidates.size(), "candidate", "candidates")
            + ".");
    candidates.stream()
        .limit(5)
        .forEach(
            candidate ->
                progress.accept(
                    "└─ Fusion #"
                        + candidate.fusionRank
                        + " "
                        + candidate.hit.virtualPath()
                        + " score="
                        + DisplayFormat.score(candidate.fusionScore)
                        + " rrf="
                        + DisplayFormat.score(candidate.weightedRrfScore)
                        + " bonus="
                        + DisplayFormat.score(candidate.bestRankBonus)));
  }

  private static String plural(int count, String singular, String plural) {
    return count == 1 ? singular : plural;
  }

  private static String percent(double value) {
    return Math.round(value * 100) + "%";
  }

  private record ResolvedInputs(
      List<String> lexicalInputs,
      List<String> vectorInputs,
      List<String> hydePassages,
      boolean lexicalInputsFromExpansion) {}

  private record CandidateQuery(
      String searchMethod,
      String inputKind,
      double weight,
      LexicalQuery lexicalQuery,
      String embeddingText) {

    static CandidateQuery lexical(String inputKind, double weight, LexicalQuery query) {
      return new CandidateQuery("lexical", inputKind, weight, query, null);
    }

    static CandidateQuery vector(String inputKind, double weight, String embeddingText) {
      return new CandidateQuery("vector", inputKind, weight, null, embeddingText);
    }

    boolean usesVectorSearch() {
      return embeddingText != null;
    }
  }

  private record CandidateRanking(CandidateQuery query, List<WorkspaceIndex.SearchHit> hits) {}

  private static final class Candidate {
    private static final Comparator<Candidate> FUSION_ORDER =
        Comparator.comparingDouble((Candidate candidate) -> candidate.fusionScore)
            .reversed()
            .thenComparing(candidate -> candidate.hit.virtualPath());
    private static final Comparator<Candidate> FINAL_ORDER =
        Comparator.comparingDouble((Candidate candidate) -> candidate.finalScore)
            .reversed()
            .thenComparing(candidate -> candidate.hit.virtualPath());

    private WorkspaceIndex.SearchHit hit;
    private final String context;
    private double weightedRrfScore;
    private int bestSourceRank = Integer.MAX_VALUE;
    private double bestRankBonus;
    private double fusionScore;
    private int fusionRank;
    private double finalScore;

    private Candidate(WorkspaceIndex.SearchHit hit, String context) {
      this.hit = hit;
      this.context = context;
    }

    private void selectEvidence(WorkspaceIndex.ChunkRead chunk) {
      hit =
          new WorkspaceIndex.SearchHit(
              hit.project(),
              hit.path(),
              hit.title(),
              hit.contentHash(),
              chunk.body(),
              hit.documentBody(),
              chunk.index(),
              chunk.charStartOffset(),
              chunk.charEndOffset(),
              hit.score());
    }

    private DocumentSearch.Match toMatch() {
      return new DocumentSearch.Match(
          new WorkspaceIndex.SearchHit(
              hit.project(),
              hit.path(),
              hit.title(),
              hit.contentHash(),
              hit.evidenceBody(),
              hit.documentBody(),
              hit.chunkIndex(),
              hit.evidenceStartOffset(),
              hit.evidenceEndOffset(),
              finalScore),
          context);
    }
  }
}
