# Soma Java Implementation Plan

# 1. Product Definition

## 1.1 Contracts

New Java CLI and local service (no backward compatibility). Contracts override this plan in order:

1. `docs/specs/prd.md`
2. `docs/specs/data-model.md`
3. `docs/specs/config.yml`

## 1.2 First Milestone

`project add → index text files → search lexical → get`.

---

# 2. Architecture

## 2.1 Technology and Build

Allowed dependencies only (additions require explicit declaration/docs):

- Java 25; single Maven module.
- Micronaut Platform BOM.
- Micronaut Runtime/Inject, HTTP Server/Client, Serde (Jackson), Validation, picocli, SQL JDBC, Test.
- Direct JDBC + handwritten SQL for SQLite/FTS5/sqlite-vec (`org.xerial:sqlite-jdbc` + sqlite-vec).
- SnakeYAML Engine, Apache Commons Compress, XZ for Java (`org.tukaani:xz`), JGit `IgnoreNode`, Logback Classic,
  AssertJ, ArchUnit.
- GraalVM Native Image: Windows x64, Linux x64, macOS ARM64.

Direct SQL via Micronaut datasources + explicit transactions (no Micronaut Data). One writer connection (no pool) for
workspace index; writes serialized by app lock. Machine-global cache uses short independent transactions. Enable `WAL`
+ `busy_timeout` on all connections.

Micronaut handles DI, lifecycle, HTTP, serialization, validation, config, scheduling, executors, and tests. Build
picocli commands with `MicronautFactory` for bean injection.

## 2.2 Package Layout

```text
src/main/java/<base-package>
├── SomaApplication.java
├── exec                        # command runtime: per-run execution state
│   ├── CommandRunner.java      # THE one place the command tree is built; error/exit mapping
│   ├── Invocation.java         # per-run I/O + progress; stderr progress bar only on TTY
│   ├── ActiveWorkspace.java    # @Singleton; holds paths only (name/source/config/db/log/lock)
│   ├── WorkspaceResolver.java  # flag > directory-local (.soma) > default-workspace env > XDG 'main'
│   └── Render.java             # renders result objects per format
├── cli                         # command runtime: picocli tree and terminal commands
│   ├── SomaCommand.java        # root: -w/-v/--no-color (+ env), subcommand registry
│   ├── common                  # global options, events, @Mixins
│   ├── project                 # ProjectCommand method subcommands
│   ├── search
│   ├── get                     # direct read-only target resolution + rendering
│   ├── context                 # ContextCommand method subcommands
│   ├── status
│   ├── system
│   └── server
├── http                        # POST /api/run and other adapters
│   ├── RunController.java      # HTTP binding/JSON conversion → RpcRunner
│   ├── RunRequest.java
│   ├── RpcRunner.java          # validation, captured execution, error mapping, timing, request ID
│   ├── RunRequestMapper.java   # structured request + adapter allowlist → argv
│   ├── RunResponse.java
│   └── HealthController.java
├── app                         # use cases, command DTOs, transactions
│   ├── common                  # events, display/target helpers, result shape, errors
│   ├── ports                   # ConfigStore, WriteLock, repositories, extract/embed/LLM/runtime
│   ├── project                 # Shared project result view
│   ├── indexing                # scan/chunk/embed orchestration; derived-state audits
│   ├── sync
│   ├── search
│   ├── status
│   └── system                  # maintenance use cases + concrete NIO/JGit project scanner
├── domain
│   ├── config                  # SomaConfig, ProjectConfig, ContextConfig
│   ├── project                 # ProjectName, ProjectRelativePath, default-search-scope rules
│   ├── document                # DocumentPath, file type/status metadata
│   ├── chunking                # recipes, breakpoints, plans, token budgets
│   ├── search                  # SearchMode, SearchScope, LexicalQuery, hits
│   └── naming                  # shared name canonicalization
├── infra
│   ├── config                  # YAML I/O, XDG paths, re-read per command, atomic writes
│   ├── sqlite                  # schema init, JDBC repositories, FTS5, sqlite-vec
│   ├── extraction              # text/PDF/Office/EPUB/OCR/vision/transcription
│   ├── embedding               # generation and vector serialization
│   ├── llm                     # HyDE, query expansion, reranking clients
│   ├── locking                 # FileWriteLock; implements app/ports/WriteLock (Path value in)
│   ├── logging                 # programmatic Logback (receives a Path, not a bean)
│   ├── runtime                 # managed-artifact provisioning and processes
│   └── cache                   # machine-global cache path/schema and impls
└── support                     # stateless zero-business utilities (hashing, JSON, IO...)

docs/specs                      # authoritative build inputs
├── schema.sql                  # packaged as db/schema.sql
├── cache-schema.sql            # packaged as db/cache-schema.sql
├── artifacts.json              # packaged as artifacts/artifacts.json
└── sqlite-vec.xml              # bundles the host sqlite-vec library

src/main/resources
├── web/index.html
...

src/test/java/<base-package>
├── domain
├── application
...
```

Keep classes package-private unless another package truly needs them.

`cli` and `exec` are two packages in one `command runtime` architectural layer. Dependencies between them are
internal to that layer.

## 2.3 Dependency Direction

```
SomaApplication / http ──► command runtime ──► app ──► domain ◄── infra
                              (`cli` + `exec`)   (use cases) (core)    (adapters)
                                                    │
                                                    └─ ports (interfaces) ◄─┘
support ◄── everyone (depends on nothing)
```

**Rules**:

1. `domain` depends on nothing (except possibly `support`).
2. `app` depends on `domain`; replaceable outbound needs use interfaces in `app/ports`. Application-owned local
   filesystem workflows may use JDK NIO and JGit directly.
3. `infra` implements `app/ports`; never imports the command runtime.
4. `http` uses the command runtime; inbound code may also depend on `app`/`domain`.
5. No layer imports an outer one.

Simple read-only commands may resolve targets and read data directly in the CLI leaf; shared, state-changing, or
independently reusable workflows remain `app` use cases.

Ports only for replaceable external boundaries (storage, LLM, embed, extract). Local project scanning stays concrete in
`app/system`; internal infra connections stay concrete.

## 2.4 Native Image and Builds

Result records/envelopes use `@Serdeable`; picocli-codegen supplies reflection config.

- Native builds bind `native:compile-no-fork` only when profile sets `skipNativeBuild=false`.
- Bundle `sqlite-vec` in `prepare-package` via profile platform properties.
- Native adds `--enable-native-access=ALL-UNNAMED`.

---

# 3. Execution Model

## 3.1 Startup Flow

**CLI path:**

```text
main(args) → boot Micronaut → detect tty → Invocation.cli(tty)
  → CommandRunner.run(argv, inv)
    → new SomaCommand(inv)
    → new CommandLine(root, new MicronautFactory(ctx)) + setExpandAtFiles(false)
    → picocli parses argv → leaf.call()
      → var invocation = SomaCommand.invocation(spec)   # the ONLY accessor
      → app use case → invocation.emit(result, format)
  → exit code
```

**HTTP path** (`server http` is itself a leaf command):

```text
POST /api/run → RunController
  → Micronaut Serde → typed RunRequest + HTTP allowlist
  → RpcRunner applies Mapping Rules → argv (positionals after "--")
  → Invocation.captured()             # fresh per request; captured streams, non-TTY
  → CommandRunner.run(argv, inv)      # re-enters the same kernel
  → exitCode + captured streams → RunResponse envelope
```

`CommandRunner` is the only command-tree builder. Fresh tree + factory per run. CLI uses real TTY; the HTTP adapter
uses captured non-TTY invocations and supplies its command allowlist; positionals follow `--`.

`ApplicationContext` starts beans only. HTTP leaf configures port, starts `EmbeddedServer` (bind `127.0.0.1` only),
blocks until shutdown.

`--auto-sync`: after bind, run one captured sync then schedule fixed 1h delay. Logs failures and continues; lock
prevents overlap.

## 3.2 Invocation

Per-run I/O object; created once, never shared. Passed as an argument; subcommands access it via
`SomaCommand.invocation(spec)`.

```java
// SomaCommand.java (root)
@Command(name = "soma", subcommands = { ... })
public final class SomaCommand implements Callable<Integer> {
  private final Invocation invocation;

  public SomaCommand(Invocation invocation) {
    this.invocation = Objects.requireNonNull(invocation, "invocation");
  }

  public static Invocation invocation(CommandSpec spec) {
    if (spec != null && spec.root().userObject() instanceof SomaCommand soma) {
      return soma.invocation;
    }
    throw new IllegalStateException("not under SomaCommand tree - run via CommandRunner");
  }
}

// Leaf command example
@Prototype
@Command(name = "lexical", aliases = "l")
public final class SearchLexicalCommand extends CliCommand {
  @ParentCommand SearchCommand parent;
  @Spec CommandSpec spec;
  @Parameters(index = "0") String query;

  @Override
  public Integer call() {
    SomaCommand.invocation(spec).emit(store.searchLexical(query, ...), out.format());
    return 0;
  }
}
```

## 3.3 Concurrency

Command beans `@Prototype`; each tree resolves leaves/collaborators via DI.

## 3.4 CLI Parsing and Options

Env booleans checked in code (`"1".equals(...)`).

## 3.5 HTTP Adapter

Converts HTTP JSON values and supplies the PRD HTTP allowlist to `RpcRunner`; framework binding errors stay in the
HTTP adapter.

---

# 4. Cross-Cutting Concerns

## 4.1 Output and Stream Discipline

Records implement `Renderable.render(format, ...)`. Use only `Invocation.out()/err()`; never `System.out`. `emit()`
produces structured data + stdout. HTTP captures streams. Enforce via ArchUnit.

## 4.2 Logging

Programmatic SLF4J/Logback (no console appender). Logs only to workspace file; user output via `Invocation`.

- Pass log path as value; confine Logback to `infra/logging`.
- `CommandRunner` creates 8-hex `runId` + MDC `{run, ws, cmd}` (copied to pools).
- Immediate flush; rotate ~10 MiB, keep 3 files. Fail open on file errors.
- No log-level selection in v1.

## 4.3 Error Handling

- `AppException` for known user-safe failures.
- Unexpected exceptions unchanged until boundary (`INTERNAL_ERROR`).
- No `Failures` helper or wrapper exception.
- `CommandRunner` catches `Exception` (not `Error`); only `main` has `Throwable` guard.
- Item/cache/cleanup/background handled only where recovery possible.
- Preserve the original exception message in user-facing errors; `--verbose` additionally shows the stack trace.
- Only `main` exits process; HTTP errors end request.

## 4.4 Write Locking

**Model**: Single exclusive OS file lock, writers only, fast-fail (no wait/retry/queue). Shows best-effort owner info;
OS releases on crash.

- One lock file per workspace (`ActiveWorkspace`).
- One in-JVM CAS guard.

**Port/Impl**: `WriteLock` port in `app/ports`; `FileWriteLock` in `infra/locking` (path as value, never imports
`ActiveWorkspace`).

- Token owns channel+lock; `close()` releases (idempotent, fail-closed on error).
- Writes owner JSON at offset 0 (far-byte lock for Windows readability).

```java
@Singleton
public final class FileWriteLock implements WriteLock {
  private static final long LOCK_POSITION = Long.MAX_VALUE - 1;

  @Serdeable
  public record Owner(long pid, String command, String acquiredAt) {}

  private final JsonMapper jsonMapper;
  private final AtomicReference<Owner> currentJvmOwner = new AtomicReference<>();

  public FileWriteLock(JsonMapper jsonMapper) { this.jsonMapper = Objects.requireNonNull(jsonMapper); }

  @Override
  public Token acquire(Path lockFile, String command) {
    // 1. CAS in-JVM guard on Owner(pid, command, now)
    // 2. Open FileChannel(CREATE|READ|WRITE), tryLock(LOCK_POSITION, 1, false)
    // 3. On null lock → read existing owner metadata, close, reset CAS, throw lockConflict
    // 4. Best-effort write owner JSON at offset 0 (warn on failure)
    // 5. Return LockToken(owner, channel, fileLock)
    // Cleanup channel + CAS on OverlappingFileLockException / IOException / SecurityException
  }

  private final class LockToken implements Token {
    // close(): idempotent via AtomicBoolean; close channel; if lock still valid → log error,
    // do NOT clear JVM owner (fail-closed); else CAS-clear owner
  }
}
```

**Who locks**:

- Locks: `sync`, `project add/update/remove/rename`, `context set/remove`, `system scan/extract/embed/clean`, RPC
  writes, auto-sync ticks (logs/skips busy).
- No lock: `init` (atomic create or read-only no-op), search/get/status/list/show, pull, startup, artifact provision, cache access. Readers use WAL +
  busy_timeout.
- Config RMW writers acquire the lock before reading and hold it through publication + index work.
- Non-reentrant: `sync` provisions then passes one token through phases; phases do short consistent batches.

**Message**: Immediate `WRITE_LOCKED` with pid/command/acquiredAt (or unknown). Advise wait or manual PID check.

**Out of scope** (accept-and-warn): case-only name diffs, symlinked roots, network FS, lock-file tampering.

## 4.5 Managed Artifacts

Layout under `$XDG_DATA_HOME/soma/`: immutable downloads at `packages/<sha256>`; current derived tree at
`live/<id>/<sha6>/...`. Bundled `artifacts.json` supplies URL, SHA-256, format, strip count, entry path, and executable
status. No state file, marker, ETag, lock, database, or migration.

- **Pull (non-refresh)**: only checks current platform's main live entries. Missing entry triggers full refresh.
- **Refresh**: verify local packages → download missing/invalid → build sibling `.live-<uuid>` tree → validate all main
  entries → atomically publish via unique `.retired-live-<uuid>` directory.
- **Extraction**: direct files hard-linked from packages when possible, else copied. ZIP / tar.gz / tar.xz use Apache
  Commons Compress with strip-components, permission preservation, and traversal/symlink checks.
- **Export**: verify cached, download missing/invalid manifest packages for all platforms, write `packages/<sha256>`
  entries; never touches live.
- **Import**: independently verify and publish matching current-platform and shared packages; report every missing /
  duplicate / invalid; no HTTP; rebuild current platform's live tree only when nothing is unavailable.
- Package and live publication require atomic filesystem moves (no non-atomic fallback). Concurrent pulls may race;
  every successful publication remains complete.
- Callers use `ensurePresent(ids)`. Recipes hash only `id + version` under the `v1` recipe format; delivery fields and
  installed state excluded.

## 4.6 Machine-Global Processing Cache

- Lazy DB from packaged `cache-schema.sql`.
- Independent of workspaces/schema; validate SHA, recreate if bad.
- Short transactions; read fail = miss; write fail skips (no fail processing).
- Key = hash(domain/version + op + recipe_id + input_hash).
- Cap rows (trigger); prefer-evict expand/embed/rerank oldest.

## 4.7 Configuration Lifecycle

`ActiveWorkspace` holds paths only. Open/parse config + DB per run (after lock for writers).

---

# 5. Data & Search

## 5.1 Scanning

- Deterministic NIO walk, sorted by normalized relative path.
- JGit `IgnoreNode` (no Git binary or `.git` required).
- Filters: hard-skip VCS/generated → ignore files → include globs → exclude globs.
- Globs root-relative (`**/*.md` matches root files).
- `project add/update/remove/rename`: write config, then run the incremental scan path.
- No dir-symlink follow; file-symlink keeps path, indexes content.
- Empty text valid; rich → `pending`; unsupported binary → `failed`. Office and EPUB rich bodies are currently converted to Markdown by a managed external converter.
- Incremental `sync`: matching mtime and size skip content reads and hashing; inspect and hash new or metadata-changed
  files.
- Scanning is one concrete system collaborator, not a one-implementation port. It returns read files plus
  unchanged document paths.
- Incremental scan reads one `(id, project, path, mtime, size)` index snapshot. Seen paths are removed from the
  snapshot; remaining IDs are deleted. Inspected rows use a conditional metadata update when source identity and
  derived state match, otherwise an upsert invalidates stale derived rows.
- Full scan uses the same write path with an empty source snapshot after clearing materialized rows and derived recipe
  state. Writes and deletes commit in batches.

## 5.2 Paths

- `$XDG_CONFIG_HOME/soma/<workspace>.yml` accepts absolute/`~` roots. `.soma/local.yml` persists `.`/`./...` roots, rejects paths outside the workspace, and resolves them to absolute paths on access.
- Store document paths relative, `/`-separated always.
- Indexed paths = stable Soma IDs (not OS-native).

## 5.3 Lexical Search

SQLite FTS5 (`porter unicode61`); app owns tokenization.

- `body`: original; `title`/`body` FTS: projected tokens.
- **Projection**: NFKC → Latin/path/id split → CJK uni/bi/trigrams.
- **Query rewrite**: same norm; safe prefixes/phrases/exclusions; add project scope. Empty/exclusion-only →
  `INVALID_REQUEST`.

## 5.4 Search Read Path and Vector Scope

- Read only persisted chunks (no re-chunk on query).
- Vector search pre-filters by `vectors.project_name` (never post-filter global results).
