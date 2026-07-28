<p align="center">
  <img src="docs/specs/icon.svg" width="128" height="128" alt="Soma logo">
</p>

# Soma

Soma is a local knowledge-base search engine that understands your materials.

It scans multimedia files from configured projects, stores them in a local database, and uses a local AI to build a
"semantic fingerprint" of each file — essentially the AI's understanding of what's inside. This lets you search in
natural language or by exact keywords. Everything is accessible through CLI and HTTP service with a built-in web
interface.

**Mental model**: add a **project** → `sync` to make everything ready → `search` finds them.

## System Requirements

- **RAM**: minimum 8 GB for regular use. Multimedia features (OCR, vision, transcription, etc.) require at least 24 GB
  **while in use**.
- **GPU**: optional — everything runs on CPU, but a GPU speeds things up considerably

## Installation

Via Package Manager

```shell
# on Windows
winget install AwesomeDog.soma
# on macOS
brew tap AwesomeDog/tap && brew trust AwesomeDog/tap && brew install AwesomeDog/tap/soma
# on Linux
curl -fsSL https://github.com/AwesomeDog/soma/releases/latest/download/soma-linux-x64 -o soma
chmod +x soma && sudo mv soma /usr/local/bin/
```

Or

Download the single-file executable from [Releases](https://github.com/AwesomeDog/soma/releases).

## Quick Start

```bash
# 1. Add a project — point soma at a folder you want to search
soma project add ~/notes

# 2. Make it searchable (first run downloads models, may take a while).
#    Re-run whenever files change.
soma sync

# 3. Search in natural language or by exact keywords
soma search "how does auth work"
soma search lexical "authentication"

# Optional: built-in web UI (http://localhost:8181)
soma server --auto-sync

# Optional: soma init — keeps config + index in .soma/, so the search data travels with the directory
soma init

# Optional: read file content — by path or by the DocID shown in results
soma get notes/api.md
```

For the full specification and command reference, see [prd.md](docs/specs/prd.md).

## Development

### Toolchain

You need the following installed locally:

- **Java 25** that supports preview features
- **Maven 3.9+**
- **GraalVM 25** with the `native-image` tool available on `PATH`
- **Internet access on first build** to download Maven dependencies and the platform-specific `sqlite-vec` binary
  bundled during the build

### Dev Commands

```bash
# Test
mvn test
python3 tests/e2e_tests.py

# Build Windows x64 native executable to `target/soma-windows-x64.exe`
mvn -Pnative-windows-x64 -DskipTests clean package
# Build macOS ARM64 native executable to `target/soma-mac-arm64`
mvn -Pnative-mac-arm64 -DskipTests clean package
# Build Linux x64 native executable to `target/soma-linux-x64`
mvn -Pnative-linux-x64 -DskipTests clean package

# Run
./target/soma-mac-arm64 --help
# Run on some older Linux environments (because of sqlite-vec):
LD_PRELOAD=/usr/lib64/libm.so.6 soma sync

# Release
v=v0.9.1 && git tag -a "$v" -m "Release $v" && git push origin "$v" # Bump version & trigger CI
```

## Credits

Soma bundles [sqlite-vec](https://github.com/asg017/sqlite-vec), Copyright (c) 2024 Alex Garcia, under the
[MIT License](https://github.com/asg017/sqlite-vec/blob/v0.1.9/LICENSE-MIT).

Soma downloads and invokes several external command-line tools locally. We are grateful to the maintainers of
[pdfium-helper's `opencc-rs` CLI](https://github.com/laisuk/pdfium-helper),
[FFmpeg](https://ffmpeg.org/) and [Jellyfin FFmpeg](https://github.com/jellyfin/jellyfin-ffmpeg),
[llamafile's `whisperfile`](https://github.com/mozilla-ai/llamafile),
[RapidOCR-CLI](https://github.com/AwesomeDog/RapidOCR-CLI), and
[llama.cpp](https://github.com/ggml-org/llama.cpp) for powering PDF extraction, media processing, transcription, OCR,
and local model inference.

The local search pipeline also relies on model releases and quantizations from
[whisper.cpp](https://huggingface.co/ggerganov/whisper.cpp),
[EmbeddingGemma](https://huggingface.co/ggml-org/embeddinggemma-300M-GGUF),
[QMD Query Expansion](https://huggingface.co/tobil/qmd-query-expansion-1.7B-gguf),
[Qwen3 Reranker](https://huggingface.co/ggml-org/Qwen3-Reranker-0.6B-Q8_0-GGUF),
[Qwen3-4B-Instruct](https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF), and
[Qwen3-VL](https://huggingface.co/Qwen/Qwen3-VL-4B-Instruct-GGUF). Thanks to their authors, maintainers, and model
publishers for making private, local-first search possible.

**Soma** was inspired by [**QMD**](https://github.com/tobi/qmd), but features a ground-up new functionality, engine,
CLI, and web UI — all compiled to cross-platform native binaries via GraalVM. Huge thanks to the QMD project for
sparking the idea!
