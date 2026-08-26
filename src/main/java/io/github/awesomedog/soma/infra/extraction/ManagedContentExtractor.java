package io.github.awesomedog.soma.infra.extraction;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.github.awesomedog.soma.app.common.AppException;
import io.github.awesomedog.soma.app.ports.ContentExtractor;
import io.github.awesomedog.soma.domain.document.FileSignatures;
import io.github.awesomedog.soma.domain.document.FileType;
import io.github.awesomedog.soma.domain.recipe.RecipeId;
import io.github.awesomedog.soma.infra.cache.ProcessingCache;
import io.github.awesomedog.soma.infra.runtime.LlamaRuntime;
import io.github.awesomedog.soma.infra.runtime.LlamaRuntime.ModelRole;
import io.github.awesomedog.soma.infra.runtime.ManagedArtifacts;
import io.github.awesomedog.soma.infra.runtime.ManagedProcesses;
import io.github.awesomedog.soma.infra.runtime.ManagedRuntimeHttp;
import io.github.awesomedog.soma.support.Hashing;
import io.micronaut.json.tree.JsonNode;
import io.micronaut.serde.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class ManagedContentExtractor implements ContentExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(ManagedContentExtractor.class);
  private static final Duration PROCESS_TIMEOUT = Duration.ofMinutes(5);
  private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration HTTP_TIMEOUT = Duration.ofMinutes(5);
  private static final int MAX_FAILURE_DETAIL_CHARS = 500;
  private static final String TRUNCATED_DETAIL_SUFFIX = "...";

  private static final String PDF_CACHE_OPERATION = "pdf.text";
  private static final List<String> PDF_ARTIFACTS = List.of("pdfium");

  private static final String OFFICE_CACHE_OPERATION = "office.markdown";
  private static final String EPUB_CACHE_OPERATION = "epub.markdown";
  private static final String PANDOC_ARTIFACT = "pandoc";
  private static final List<String> PANDOC_ARTIFACTS = List.of(PANDOC_ARTIFACT);

  private static final int MAX_OCR_CLEANUP_INPUT_CHARS = 8_000;
  private static final int MAX_OCR_CLEANUP_OUTPUT_TOKENS = 2_048;
  private static final int MIN_CLEANED_OCR_CHARS = 1;
  private static final int MIN_CLEANED_OCR_LENGTH_DIVISOR = 4;
  private static final int MAX_CLEANED_OCR_LENGTH_MULTIPLIER = 3;
  private static final int MAX_CLEANED_OCR_EXTRA_CHARS = 200;
  private static final int MAX_DIRECT_VISION_IMAGE_BYTES = 10 * 1024 * 1024;
  private static final String VISION_IMAGE_SCALE_FILTER =
      "scale='min(2048,iw)':'min(2048,ih)':"
          + "force_original_aspect_ratio=decrease:force_divisible_by=2";
  private static final List<String> VISION_ARTIFACTS = ModelRole.VISION.runtimeArtifactIds();
  private static final List<String> OCR_ARTIFACTS =
      Stream.concat(Stream.of("rapidocr"), ModelRole.OCR_CLEANUP.runtimeArtifactIds().stream())
          .toList();
  private static final String VISION_PROMPT =
      "Describe this image for document search. Focus on visible text, objects, layout, "
          + "diagrams, screenshots, charts, and labels. Return concise searchable prose.";
  private static final String OCR_CLEANUP_PROMPT =
      """
      You are an expert OCR text post-processing engine. Your task is to clean and format raw OCR text that may contain English, Chinese, or a mix of both.

      ### CORE RULES:
      1. English Words: Insert missing spaces between concatenated English words (e.g., "UnitedKingdom" -> "United Kingdom", "Whatif" -> "What if").
      2. Chinese Text: DO NOT add spaces between Chinese characters. Remove unnecessary spaces within Chinese sentences.
      3. Mixed Text: Add exactly ONE space between Chinese characters and English words/numbers for better readability (e.g., "今天学习Python3" -> "今天学习 Python3").
      4. Line Breaks: Remove unnatural line breaks (hard returns) that split a single sentence or paragraph. Keep intentional paragraph breaks.
      5. Punctuation:
         - Use full-width punctuation (，。！？) for Chinese context.
         - Use half-width punctuation (, . ! ?) followed by a space for English context.
         - Fix mismatched quotes and brackets.
      6. OCR Noise: Fix obvious OCR character misrecognitions (e.g., '0' vs 'O', '1' vs 'l', '己' vs '已') ONLY when the context makes the error 100% obvious. Otherwise, keep the original character.

      ### STRICT CONSTRAINTS:
      - DO NOT rewrite, summarize, translate, or polish the text.
      - DO NOT change the original meaning or tone.
      - DO NOT add any conversational filler (e.g., "Here is the result:", "Sure").
      - Output ONLY the cleaned raw text. No markdown formatting (no ```text blocks).
      """;

  private static final String MEDIA_CACHE_OPERATION = "media.transcribe";
  private static final String FFMPEG_ARTIFACT = "ffmpeg";
  private static final String WHISPER_ARTIFACT = "whisper";
  private static final String WHISPER_MODEL_ARTIFACT = "whisper-base-model";
  private static final String DECODED_AUDIO_SAMPLE_RATE_HZ = "16000";
  private static final String DECODED_AUDIO_CHANNELS = "1";
  private static final List<String> MEDIA_ARTIFACTS =
      List.of(FFMPEG_ARTIFACT, WHISPER_ARTIFACT, WHISPER_MODEL_ARTIFACT);

  private final Function<String, Path> artifactResolver;
  private final Function<LlamaRuntime.Request, ManagedRuntimeHttp.Response> llama;
  private final Supplier<URI> ocrEndpointResolver;
  private final ObjectMapper json;
  private final HttpClient http;
  private final BiFunction<List<String>, Path, String> commandExecutor;
  private final Function<List<String>, String> artifactRecipeResolver;
  private final ProcessingCache cache;

  @Inject
  public ManagedContentExtractor(
      ManagedArtifacts artifacts,
      LlamaRuntime llamaRuntime,
      ManagedProcesses managedProcesses,
      ObjectMapper json,
      ProcessingCache cache) {
    var managedArtifacts = Objects.requireNonNull(artifacts, "artifacts");
    var processes = Objects.requireNonNull(managedProcesses, "managedProcesses");
    this.artifactResolver =
        artifactId -> managedArtifacts.ensurePresent(artifactId).get(artifactId);
    this.llama = Objects.requireNonNull(llamaRuntime, "llamaRuntime")::post;
    this.ocrEndpointResolver = processes::ensureOcrEndpoint;
    this.json = Objects.requireNonNull(json, "json");
    this.http = ManagedRuntimeHttp.newClient();
    this.commandExecutor = this::executeExternalProcess;
    this.artifactRecipeResolver =
        ids -> managedArtifacts.artifactRecipeId(ids.toArray(String[]::new));
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  ManagedContentExtractor(
      Function<String, Path> artifacts,
      Function<LlamaRuntime.Request, ManagedRuntimeHttp.Response> llama,
      Supplier<URI> ocrEndpointResolver,
      ObjectMapper json,
      HttpClient http,
      BiFunction<List<String>, Path, String> commands,
      ProcessingCache cache) {
    this.artifactResolver = Objects.requireNonNull(artifacts, "artifacts");
    this.llama = Objects.requireNonNull(llama, "llama");
    this.ocrEndpointResolver = Objects.requireNonNull(ocrEndpointResolver, "ocrEndpointResolver");
    this.json = Objects.requireNonNull(json, "json");
    this.http = Objects.requireNonNull(http, "http");
    this.commandExecutor = Objects.requireNonNull(commands, "commands");
    this.artifactRecipeResolver = ignored -> "";
    this.cache = Objects.requireNonNull(cache, "cache");
  }

  @Override
  public String recipeId(FileType fileType) {
    return switch (Objects.requireNonNull(fileType, "fileType")) {
      case PDF -> pdfRecipeId();
      case OFFICE -> officeRecipeId();
      case EPUB -> epubRecipeId();
      case IMAGE -> imageRecipeId();
      case AUDIO, VIDEO -> mediaRecipeId(fileType);
      case TEXT, OTHER ->
          throw new IllegalArgumentException(
              "File type does not have a managed content producer: " + fileType.value());
    };
  }

  @Override
  public Extraction extract(Path source, FileType fileType) {
    var input = requireReadableSourceFile(source);
    return switch (Objects.requireNonNull(fileType, "fileType")) {
      case PDF -> extractPdf(input);
      case OFFICE -> extractOffice(input);
      case EPUB -> extractEpub(input);
      case IMAGE -> extractImage(input);
      case AUDIO, VIDEO -> transcribeMedia(input, fileType);
      case TEXT, OTHER ->
          throw new AppException(
              OPERATION_FAILED, "Unsupported extraction file type: " + fileType.value(), null);
    };
  }

  private String pdfRecipeId() {
    return RecipeId.of(
        "pdf.text",
        "v1",
        "command=pdf --extract -i <source> -o <output>",
        "output=utf8-strip-nonempty",
        artifactRecipeId(PDF_ARTIFACTS));
  }

  private Extraction extractPdf(Path source) {
    var sourceHash = calculateSourceHash(source);
    var recipeId = pdfRecipeId();
    var cached = readNonBlankCache(PDF_CACHE_OPERATION, recipeId, sourceHash);
    if (cached.isPresent()) {
      return new Extraction(sourceHash, cached.get());
    }
    var executable = requireArtifact("pdfium");
    var output = createTemporaryOutputPath(".pdf.txt");
    try {
      executeCommand(
          List.of(
              executable.toString(),
              "pdf",
              "--extract",
              "-i",
              source.toString(),
              "-o",
              output.toString()),
          source.getParent());
      if (!Files.isRegularFile(output)) {
        throw operationFailed("PDF extraction completed without producing its output file.");
      }
      var body = readUtf8Text(output, "PDF extraction output").strip();
      requireNonBlankBody(body, "PDF extraction produced no searchable text: " + source);
      cache.write(PDF_CACHE_OPERATION, recipeId, sourceHash, body);
      return new Extraction(sourceHash, body);
    } finally {
      deleteTemporaryFileQuietly(output);
    }
  }

  private String officeRecipeId() {
    return RecipeId.of(
        "office.markdown",
        "v1",
        "command=pandoc --from=<format> --to=markdown --output=- <source>",
        "formats=docx,docm->docx;xlsx,xlsm->xlsx;pptx,pptm->pptx",
        "output=utf8-strip-nonempty",
        artifactRecipeId(PANDOC_ARTIFACTS));
  }

  private String epubRecipeId() {
    return RecipeId.of(
        "epub.markdown",
        "v1",
        "command=pandoc --from=epub --to=markdown --output=- <source>",
        "output=utf8-strip-nonempty",
        artifactRecipeId(PANDOC_ARTIFACTS));
  }

  private Extraction extractOffice(Path source) {
    return extractWithPandoc(source, FileType.OFFICE, OFFICE_CACHE_OPERATION, officeRecipeId());
  }

  private Extraction extractEpub(Path source) {
    return extractWithPandoc(source, FileType.EPUB, EPUB_CACHE_OPERATION, epubRecipeId());
  }

  private Extraction extractWithPandoc(
      Path source, FileType fileType, String cacheOperation, String recipeId) {
    var inputFormat = pandocInputFormat(source, fileType);
    var sourceHash = calculateSourceHash(source);
    var cached = readNonBlankCache(cacheOperation, recipeId, sourceHash);
    if (cached.isPresent()) {
      return new Extraction(sourceHash, cached.get());
    }
    var pandoc = requireArtifact(PANDOC_ARTIFACT);
    var body =
        executeCommand(
                List.of(
                    pandoc.toString(),
                    "--from=" + inputFormat,
                    "--to=markdown",
                    "--output=-",
                    source.toString()),
                source.getParent())
            .strip();
    requireNonBlankBody(body, "Pandoc conversion produced no searchable text: " + source);
    cache.write(cacheOperation, recipeId, sourceHash, body);
    return new Extraction(sourceHash, body);
  }

  private String pandocInputFormat(Path source, FileType fileType) {
    var fileName = source.getFileName().toString().toLowerCase(Locale.ROOT);
    var dot = fileName.lastIndexOf('.');
    var extension = dot < 0 ? "" : fileName.substring(dot + 1);
    return switch (fileType) {
      case OFFICE ->
          switch (extension) {
            case "docx", "docm" -> "docx";
            case "xlsx", "xlsm" -> "xlsx";
            case "pptx", "pptm" -> "pptx";
            default -> throw unsupportedPandocExtension(source, fileType);
          };
      case EPUB -> {
        if (!"epub".equals(extension)) {
          throw unsupportedPandocExtension(source, fileType);
        }
        yield "epub";
      }
      default ->
          throw new IllegalArgumentException(
              "File type does not have a Pandoc input format: " + fileType.value());
    };
  }

  private AppException unsupportedPandocExtension(Path source, FileType fileType) {
    return new AppException(
        OPERATION_FAILED,
        "Unsupported " + fileType.value() + " document extension: " + source,
        null);
  }

  private String imageRecipeId() {
    return RecipeId.of(
        "image.body",
        "v1",
        imageDescriptionRecipeId(),
        ocrRecipeId(),
        "separator=\\n\\n",
        "omit-empty=true");
  }

  private Extraction extractImage(Path source) {
    var bytes = readSourceBytes(source);
    var sourceHash = Hashing.sha256Hex(bytes);
    var vision = describeImage(source, bytes, sourceHash);
    var ocr = readAndCleanOcr(bytes, sourceHash);
    var sections = new ArrayList<String>(2);
    if (!vision.isBlank()) {
      sections.add(vision);
    }
    if (!ocr.isBlank()) {
      sections.add(ocr);
    }
    var body = String.join("\n\n", sections);
    requireNonBlankBody(body, "Image extraction produced no searchable text: " + source);
    return new Extraction(sourceHash, body);
  }

  private String describeImage(Path source, byte[] bytes, String sourceHash) {
    var recipeId = imageDescriptionRecipeId();
    var cached = readNonBlankCache("image.describe", recipeId, sourceHash);
    if (cached.isPresent()) {
      return cached.get();
    }
    var visionBytes =
        bytes.length > MAX_DIRECT_VISION_IMAGE_BYTES ? preprocessImage(source) : bytes;
    var dataUrl =
        "data:"
            + imageMediaType(visionBytes)
            + ";base64,"
            + Base64.getEncoder().encodeToString(visionBytes);
    var request =
        Map.of(
            "model",
            ModelRole.VISION.apiName(),
            "temperature",
            0.2,
            "max_tokens",
            512,
            "messages",
            List.of(
                Map.of(
                    "role",
                    "user",
                    "content",
                    List.of(
                        Map.of("type", "text", "text", VISION_PROMPT),
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl))))));
    var description =
        chatText(postLlama("/v1/chat/completions", request, "image vision"), "image vision")
            .strip();
    if (!description.isBlank()) {
      cache.write("image.describe", recipeId, sourceHash, description);
    }
    return description;
  }

  private byte[] preprocessImage(Path source) {
    var ffmpeg = requireArtifact(FFMPEG_ARTIFACT);
    var output = createTemporaryOutputPath(".vision.jpg");
    try {
      executeCommand(
          List.of(
              ffmpeg.toString(),
              "-y",
              "-v",
              "error",
              "-i",
              source.toString(),
              "-frames:v",
              "1",
              "-vf",
              VISION_IMAGE_SCALE_FILTER,
              "-map_metadata",
              "-1",
              "-q:v",
              "3",
              output.toString()),
          source.getParent());
      try {
        if (!Files.isRegularFile(output) || Files.size(output) == 0) {
          throw operationFailed("Image preprocessing completed without producing an image.");
        }
      } catch (IOException e) {
        throw operationFailed("Could not inspect the preprocessed image.", e);
      }
      return readSourceBytes(output);
    } finally {
      deleteTemporaryFileQuietly(output);
    }
  }

  private String readAndCleanOcr(byte[] bytes, String sourceHash) {
    var recipeId = ocrRecipeId();
    var cached =
        cache.read("ocr.text", recipeId, sourceHash).filter(value -> value.equals(value.strip()));
    if (cached.isPresent()) {
      return cached.get();
    }
    var rawOcr = readOcr(bytes);
    var ocr = rawOcr.isBlank() ? "" : cleanOcr(rawOcr).strip();
    cache.write("ocr.text", recipeId, sourceHash, ocr);
    return ocr;
  }

  private String imageDescriptionRecipeId() {
    return RecipeId.of(
        "image.describe",
        "v1",
        VISION_PROMPT,
        "model=" + ModelRole.VISION.apiName(),
        "temperature=0.2",
        "max_tokens=512",
        "response=chat-text-v1",
        artifactRecipeId(VISION_ARTIFACTS));
  }

  private String ocrRecipeId() {
    return RecipeId.of(
        "image.ocr",
        "v1",
        "raw-response-parser=v1",
        OCR_CLEANUP_PROMPT,
        "model=" + ModelRole.OCR_CLEANUP.apiName(),
        "temperature=0.1",
        "top_p=0.9",
        "input_chars=" + MAX_OCR_CLEANUP_INPUT_CHARS,
        "output_tokens=" + MAX_OCR_CLEANUP_OUTPUT_TOKENS,
        "minimum_chars=" + MIN_CLEANED_OCR_CHARS,
        "minimum_length_divisor=" + MIN_CLEANED_OCR_LENGTH_DIVISOR,
        "maximum_length_multiplier=" + MAX_CLEANED_OCR_LENGTH_MULTIPLIER,
        "maximum_extra_chars=" + MAX_CLEANED_OCR_EXTRA_CHARS,
        artifactRecipeId(OCR_ARTIFACTS));
  }

  private String readOcr(byte[] bytes) {
    var request = Map.of("image", Base64.getEncoder().encodeToString(bytes));
    var response = parseResponse(postOcr("/api/ocr", request, "image OCR"), "image OCR");
    var results = response.get("results");
    if (results == null || !results.isArray()) {
      throw invalidRuntimeResponse("image OCR");
    }
    var texts = new ArrayList<String>();
    for (var result : results.values()) {
      var textNode = result == null || !result.isObject() ? null : result.get("txt");
      if (textNode == null || !textNode.isString()) {
        throw invalidRuntimeResponse("image OCR");
      }
      var text = textNode.getStringValue().strip();
      if (!text.isBlank()) {
        texts.add(text);
      }
    }
    return String.join("\n", texts);
  }

  private String cleanOcr(String rawText) {
    var input =
        rawText.length() > MAX_OCR_CLEANUP_INPUT_CHARS
            ? rawText.substring(0, MAX_OCR_CLEANUP_INPUT_CHARS)
            : rawText;
    var request =
        Map.of(
            "model",
            ModelRole.OCR_CLEANUP.apiName(),
            "temperature",
            0.1,
            "top_p",
            0.9,
            "max_tokens",
            MAX_OCR_CLEANUP_OUTPUT_TOKENS,
            "messages",
            List.of(
                Map.of("role", "system", "content", OCR_CLEANUP_PROMPT),
                Map.of("role", "user", "content", input)));
    var candidate =
        chatText(postLlama("/v1/chat/completions", request, "OCR cleanup"), "OCR cleanup");
    return safeCleanedOcr(rawText, input, candidate);
  }

  private String postLlama(String path, Object payload, String operation) {
    return perform(
        "Managed runtime request failed during " + operation + ".",
        () ->
            requireSuccessful(
                llama.apply(new LlamaRuntime.Request(path, payload, HTTP_TIMEOUT)), operation));
  }

  private String postOcr(String path, Object payload, String operation) {
    return perform(
        "Managed runtime request failed during " + operation + ".",
        () -> {
          var endpoint = ocrEndpointResolver.get();
          if (endpoint == null) {
            throw operationFailed("Managed runtime returned no endpoint for " + operation + ".");
          }
          return requireSuccessful(
              ManagedRuntimeHttp.postJson(http, json, endpoint, path, payload, HTTP_TIMEOUT),
              operation);
        });
  }

  private String requireSuccessful(ManagedRuntimeHttp.Response response, String operation) {
    if (response == null) {
      throw operationFailed("Managed runtime returned no response for " + operation + ".");
    }
    if (!response.successful()) {
      LOG.warn(
          "Managed runtime returned HTTP {} for {}: {}",
          response.statusCode(),
          operation,
          response.body());
      throw operationFailed(
          "Managed runtime returned HTTP " + response.statusCode() + " for " + operation + ".");
    }
    return response.body();
  }

  private JsonNode parseResponse(String body, String operation) {
    return perform(
        "Managed runtime returned invalid JSON for " + operation + ".",
        () -> {
          var response = json.readValue(body == null ? "" : body, JsonNode.class);
          if (response == null || !response.isObject()) {
            throw invalidRuntimeResponse(operation);
          }
          return response;
        });
  }

  private String chatText(String body, String operation) {
    var response = parseResponse(body, operation);
    var choices = response.get("choices");
    if (choices == null || !choices.isArray() || choices.size() == 0) {
      throw invalidRuntimeResponse(operation);
    }
    var choice = choices.get(0);
    var message = choice == null || !choice.isObject() ? null : choice.get("message");
    var content = message == null || !message.isObject() ? null : message.get("content");
    if (content == null || !content.isString()) {
      throw invalidRuntimeResponse(operation);
    }
    return content.getStringValue();
  }

  private AppException invalidRuntimeResponse(String operation) {
    return operationFailed("Managed runtime returned an invalid response for " + operation + ".");
  }

  private String safeCleanedOcr(String original, String input, String candidate) {
    var cleaned = candidate == null ? "" : candidate.trim();
    cleaned = stripConversationalPrefix(cleaned).trim();
    cleaned = stripMarkdownFence(cleaned).trim();
    cleaned = stripConversationalPrefix(cleaned).trim();
    if (cleaned.isBlank()
        || cleaned.length()
            < Math.max(
                MIN_CLEANED_OCR_CHARS, input.trim().length() / MIN_CLEANED_OCR_LENGTH_DIVISOR)
        || cleaned.length()
            > input.length() * (long) MAX_CLEANED_OCR_LENGTH_MULTIPLIER
                + MAX_CLEANED_OCR_EXTRA_CHARS) {
      return original;
    }
    return original.length() > input.length()
        ? cleaned + original.substring(input.length())
        : cleaned;
  }

  private String stripMarkdownFence(String text) {
    var trimmed = text.trim();
    if (!trimmed.startsWith("```")) {
      return trimmed;
    }
    var firstNewline = trimmed.indexOf('\n');
    var lastFence = trimmed.lastIndexOf("```");
    return firstNewline >= 0 && lastFence > firstNewline
        ? trimmed.substring(firstNewline + 1, lastFence).trim()
        : trimmed;
  }

  private String stripConversationalPrefix(String text) {
    var trimmed = text.trim();
    var lower = trimmed.toLowerCase(Locale.ROOT);
    for (var prefix :
        List.of("here is the result:", "here's the result:", "sure:", "sure,", "result:")) {
      if (lower.startsWith(prefix)) {
        return trimmed.substring(prefix.length()).trim();
      }
    }
    return trimmed;
  }

  private String imageMediaType(byte[] bytes) {
    var mediaType = FileSignatures.imageMediaType(bytes);
    if (mediaType != null) {
      return mediaType;
    }
    throw new AppException(OPERATION_FAILED, "Unsupported image content.", null);
  }

  private String mediaRecipeId(FileType fileType) {
    if (fileType != FileType.AUDIO && fileType != FileType.VIDEO) {
      throw new IllegalArgumentException(
          "File type does not have a transcription recipe: " + fileType.value());
    }
    return RecipeId.of(
        "media.transcribe",
        "v1",
        "ffmpeg=-y -v error -i <source> -ar 16000 -ac 1 <wav>",
        "whisper=-m <model> -f <wav> --language auto --no-timestamps --no-prints",
        "output=utf8-strip-nonempty",
        artifactRecipeId(MEDIA_ARTIFACTS));
  }

  private Extraction transcribeMedia(Path source, FileType fileType) {
    var sourceHash = calculateSourceHash(source);
    var recipeId = mediaRecipeId(fileType);
    var cached = readNonBlankCache(MEDIA_CACHE_OPERATION, recipeId, sourceHash);
    if (cached.isPresent()) {
      return new Extraction(sourceHash, cached.get());
    }
    var ffmpeg = requireArtifact(FFMPEG_ARTIFACT);
    var whisper = requireArtifact(WHISPER_ARTIFACT);
    var model = requireArtifact(WHISPER_MODEL_ARTIFACT);
    var wav = createTemporaryFile(".wav");
    try {
      decodeAudio(source, ffmpeg, wav);
      var transcript = transcribeAudio(whisper, model, wav).strip();
      requireNonBlankBody(transcript, "Transcription produced no searchable text: " + source);
      cache.write(MEDIA_CACHE_OPERATION, recipeId, sourceHash, transcript);
      return new Extraction(sourceHash, transcript);
    } finally {
      deleteTemporaryFileQuietly(wav);
    }
  }

  private void decodeAudio(Path source, Path ffmpeg, Path wav) {
    executeCommand(
        List.of(
            ffmpeg.toString(),
            "-y",
            "-v",
            "error",
            "-i",
            source.toString(),
            "-ar",
            DECODED_AUDIO_SAMPLE_RATE_HZ,
            "-ac",
            DECODED_AUDIO_CHANNELS,
            wav.toString()),
        source.getParent());
    try {
      if (!Files.isRegularFile(wav) || Files.size(wav) == 0) {
        throw operationFailed("Audio decoding completed without producing audio.");
      }
    } catch (IOException e) {
      throw operationFailed("Could not inspect decoded audio.", e);
    }
  }

  private String transcribeAudio(Path whisper, Path model, Path wav) {
    return executeCommand(
        List.of(
            whisper.toString(),
            "-m",
            model.toString(),
            "-f",
            wav.toString(),
            "--language",
            "auto",
            "--no-timestamps",
            "--no-prints"),
        wav.getParent());
  }

  private Path requireReadableSourceFile(Path sourceFile) {
    var normalizedSourceFile =
        Objects.requireNonNull(sourceFile, "source").toAbsolutePath().normalize();
    if (!Files.isRegularFile(normalizedSourceFile) || !Files.isReadable(normalizedSourceFile)) {
      throw new AppException(
          OPERATION_FAILED, "Source file is not readable: " + normalizedSourceFile, null);
    }
    return normalizedSourceFile;
  }

  private byte[] readSourceBytes(Path sourceFile) {
    try {
      return Files.readAllBytes(sourceFile);
    } catch (IOException e) {
      throw new AppException(
          OPERATION_FAILED, "Could not read source file: " + sourceFile, null, e);
    }
  }

  private Path requireArtifact(String artifactId) {
    return perform(
        "Managed artifact is unavailable: " + artifactId + ".",
        () -> {
          var artifactPath = artifactResolver.apply(artifactId);
          if (artifactPath == null || !Files.isRegularFile(artifactPath)) {
            throw operationFailed("Managed artifact is unavailable: " + artifactId + ".");
          }
          return artifactPath.toAbsolutePath().normalize();
        });
  }

  private String artifactRecipeId(List<String> artifactIds) {
    return Objects.requireNonNull(
        artifactRecipeResolver.apply(List.copyOf(artifactIds)), "artifactRecipeId");
  }

  private String calculateSourceHash(Path sourceFile) {
    try {
      return Hashing.sha256Hex(sourceFile);
    } catch (IOException failure) {
      throw new AppException(
          OPERATION_FAILED, "Could not read source file: " + sourceFile, null, failure);
    }
  }

  private Optional<String> readNonBlankCache(String operation, String recipeId, String inputHash) {
    return cache
        .read(operation, recipeId, inputHash)
        .filter(value -> !value.isBlank() && value.equals(value.strip()));
  }

  private Path createTemporaryFile(String fileSuffix) {
    try {
      return Files.createTempFile("soma-extract-", fileSuffix);
    } catch (IOException e) {
      throw operationFailed("Could not create an extraction temporary file.", e);
    }
  }

  private Path createTemporaryOutputPath(String fileSuffix) {
    var outputPath = createTemporaryFile(fileSuffix);
    try {
      Files.delete(outputPath);
      return outputPath;
    } catch (IOException e) {
      throw operationFailed("Could not prepare an extraction output path.", e);
    }
  }

  private String readUtf8Text(Path filePath, String description) {
    try {
      return Files.readString(filePath, UTF_8);
    } catch (IOException e) {
      throw new AppException(
          OPERATION_FAILED, "Could not decode " + description + " as UTF-8.", null, e);
    }
  }

  private void requireNonBlankBody(String body, String message) {
    if (body == null || body.isBlank()) {
      throw new AppException(OPERATION_FAILED, message, null);
    }
  }

  private void deleteTemporaryFileQuietly(Path filePath) {
    if (filePath == null) {
      return;
    }
    try {
      Files.deleteIfExists(filePath);
    } catch (IOException ignored) {
      // Temporary files are best-effort cleanup after the primary result is known.
    }
  }

  private String executeCommand(List<String> commandLine, Path workingDirectory) {
    return perform(
        "Managed extraction command failed to run.",
        () -> commandExecutor.apply(List.copyOf(commandLine), workingDirectory));
  }

  private String executeExternalProcess(List<String> commandLine, Path workingDirectory) {
    Path stdout = null;
    Path stderr = null;
    Process process = null;
    try {
      stdout = createTemporaryFile(".stdout");
      stderr = createTemporaryFile(".stderr");
      var builder =
          new ProcessBuilder(commandLine)
              .redirectOutput(stdout.toFile())
              .redirectError(stderr.toFile());
      if (workingDirectory != null) {
        builder.directory(workingDirectory.toFile());
      }
      process = perform("Managed extraction command could not start.", builder::start);
      final boolean completed;
      try {
        completed = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        process.destroyForcibly();
        throw operationFailed("Managed extraction command was interrupted.", e);
      }
      if (!completed) {
        process.destroyForcibly();
        try {
          process.waitFor(PROCESS_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw operationFailed("Managed extraction command was interrupted while stopping.", e);
        }
        throw operationFailed("Managed extraction command timed out.");
      }
      return readCompletedProcessOutput(process, stdout, stderr);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      deleteTemporaryFileQuietly(stdout);
      deleteTemporaryFileQuietly(stderr);
    }
  }

  private String readCompletedProcessOutput(Process process, Path stdout, Path stderr) {
    var stdoutText = readProcessOutputText(stdout, "managed extraction stdout");
    var stderrText = readProcessOutputText(stderr, "managed extraction stderr");
    if (process.exitValue() == 0) {
      return stdoutText;
    }
    var failureDetail = firstNonBlank(stderrText, stdoutText);
    throw new AppException(
        OPERATION_FAILED,
        "Managed extraction command exited with code "
            + process.exitValue()
            + (failureDetail.isBlank() ? "." : ": " + truncate(failureDetail)),
        null);
  }

  private String readProcessOutputText(Path outputFile, String description) {
    try {
      return Files.readString(outputFile, UTF_8);
    } catch (IOException e) {
      throw operationFailed("Could not read " + description + ".", e);
    }
  }

  private String firstNonBlank(String first, String second) {
    var preferred = first == null ? "" : first.strip();
    return preferred.isBlank() ? (second == null ? "" : second.strip()) : preferred;
  }

  private String truncate(String value) {
    return value.length() <= MAX_FAILURE_DETAIL_CHARS
        ? value
        : value.substring(0, MAX_FAILURE_DETAIL_CHARS) + TRUNCATED_DETAIL_SUFFIX;
  }

  private AppException operationFailed(String message) {
    return new AppException(
        OPERATION_FAILED, message, "Run `soma system pull`, then retry extraction.");
  }

  private AppException operationFailed(String message, Throwable cause) {
    return new AppException(
        OPERATION_FAILED, message, "Run `soma system pull`, then retry extraction.", cause);
  }

  private <T> T perform(String failureMessage, Callable<T> operation) {
    try {
      return operation.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw operationFailed(failureMessage, e);
    } catch (Exception e) {
      throw operationFailed(failureMessage, e);
    }
  }
}
