-- Soma workspace index database schema.
-- All tables below live in the selected workspace index database.

-- SQLite base configuration. Apply these pragmas independently to both databases.
PRAGMA journal_mode = WAL;    -- persistent setting
PRAGMA foreign_keys = ON;     -- transient setting
PRAGMA busy_timeout = 5000;   -- transient setting

-- 1. contents
-- Content-addressable document bodies. Identical bodies share one row.
CREATE TABLE IF NOT EXISTS contents (
    -- Primary key. Full body hash, lowercase hex SHA-256.
    content_hash TEXT PRIMARY KEY,
    -- Full body. Text files use file content; media/rich types use extracted searchable text.
    body TEXT NOT NULL,
    -- Record creation time.
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (length(content_hash) = 64),
    CHECK (content_hash = lower(content_hash))
);

-- 2. documents
-- Indexed paths keyed by (project_name, path). Extraction recipes are global
-- per final rich/media pipeline and live in soma_meta.
CREATE TABLE IF NOT EXISTS documents (
    -- Internal stable PK, aligned with fts_index.rowid.
    id INTEGER PRIMARY KEY,
    -- Canonical project name from config (canonicalized before writes).
    project_name TEXT NOT NULL,
    -- Normalized project-relative path: '/'-separated, no leading '/'. A POSIX '\\' is a filename character.
    path TEXT NOT NULL,
    -- Lowercase SHA-256 of the original file bytes. Null when the source cannot be read or is unsupported.
    source_hash TEXT,
    -- References contents.content_hash. Null if extraction incomplete/failed.
    content_hash TEXT,
    -- System-generated display/search title.
    title TEXT NOT NULL DEFAULT '',
    -- Source mtime (Unix epoch ns) for incremental sync.
    source_mtime_ns INTEGER NOT NULL,
    -- Source size (bytes) for incremental sync and status display.
    source_size_bytes INTEGER NOT NULL,
    -- Application-defined lowercase identifier (for example: text, pdf).
    file_type TEXT NOT NULL DEFAULT 'text',
    -- ready | pending | failed.
    extraction_status TEXT NOT NULL DEFAULT 'ready',
    -- Time of most recent index update for this path.
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (project_name, path),
    FOREIGN KEY (content_hash)
        REFERENCES contents(content_hash)
        ON DELETE RESTRICT,
    CHECK (project_name <> ''),
    CHECK (path <> ''),
    CHECK (substr(path, 1, 1) <> '/'),
    CHECK (source_hash IS NULL OR (length(source_hash) = 64 AND source_hash = lower(source_hash))),
    CHECK (source_mtime_ns >= 0),
    CHECK (source_size_bytes >= 0),
    -- Keep the storage contract open to new file types without changing this schema.
    CHECK (file_type <> '' AND file_type NOT GLOB '*[^a-z0-9_]*'),
    CHECK (extraction_status IN ('ready', 'pending', 'failed')),
    -- Ready documents have searchable content; pending/failed documents do not.
    CHECK (
        (extraction_status = 'ready'
            AND content_hash IS NOT NULL)
        OR
        (extraction_status IN ('pending', 'failed')
            AND content_hash IS NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_documents_content_hash
ON documents(content_hash);

-- Supports project-scoped extraction-status scans.
CREATE INDEX IF NOT EXISTS idx_documents_project_status_id
ON documents(project_name, extraction_status, id);

-- 3. chunks
-- Sections of contents.body used for vector search and snippets, keyed by
-- (content_hash, chunk_index). The active semantic recipe is stored once in
-- soma_meta; a recipe change rebuilds all vector-derived tables.
CREATE TABLE IF NOT EXISTS chunks (
    -- References contents.content_hash.
    content_hash TEXT NOT NULL,
    -- Chunk sequence within content_hash, from 0.
    chunk_index INTEGER NOT NULL,
    -- Char offsets within the full body.
    char_start_offset INTEGER NOT NULL,
    char_end_offset INTEGER NOT NULL,
    -- Chunk text (derived segment of contents.body).
    body TEXT NOT NULL,
    -- Token count under the active chunker tokenizer (final embedding-input counts live in embeddings).
    token_count INTEGER NOT NULL,
    -- Chunk creation time.
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (content_hash, chunk_index),
    FOREIGN KEY (content_hash)
        REFERENCES contents(content_hash)
        ON DELETE CASCADE,
    CHECK (content_hash <> ''),
    CHECK (chunk_index >= 0),
    CHECK (char_start_offset >= 0),
    CHECK (char_end_offset >= char_start_offset),
    CHECK (token_count >= 0)
);

-- 4. embeddings
-- Document-scoped embedding metadata; floats live in vectors at
-- rowid = embeddings.id. Document scope maps shared content back to a specific
-- Virtual Path. The active semantic recipe is stored once in soma_meta.
CREATE TABLE IF NOT EXISTS embeddings (
    -- PK, aligned with vectors.rowid.
    id INTEGER PRIMARY KEY,
    -- References documents.id.
    document_id INTEGER NOT NULL,
    -- Body hash used to locate the referenced active chunk.
    content_hash TEXT NOT NULL,
    -- Chunk sequence within the active chunks for content_hash.
    chunk_index INTEGER NOT NULL,
    -- Token count of the formatted text actually sent to the model for this row.
    input_token_count INTEGER NOT NULL,
    -- Embedding generation time.
    embedded_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (document_id, chunk_index),
    FOREIGN KEY (document_id)
        REFERENCES documents(id)
        ON DELETE CASCADE,
    FOREIGN KEY (content_hash, chunk_index)
        REFERENCES chunks(content_hash, chunk_index)
        ON DELETE CASCADE,
    CHECK (document_id > 0),
    CHECK (content_hash <> ''),
    CHECK (chunk_index >= 0),
    CHECK (input_token_count >= 0)
);

CREATE INDEX IF NOT EXISTS idx_embeddings_chunk ON embeddings(content_hash, chunk_index);

-- 5. vectors
-- sqlite-vec data with rowid = embeddings.id. project_name enables scoped
-- pre-filtering; v1 uses 768 dimensions.
CREATE VIRTUAL TABLE IF NOT EXISTS vectors USING vec0(
    -- Partition key for project-scoped vector search pre-filtering.
    project_name TEXT partition key,
    -- Chunk vector, 768-dim active contract (normalized per search strategy).
    embedding float[768]
);
-- rowid (hidden): must match embeddings.id.

-- 6. fts_index
-- Rebuildable FTS5/BM25 index with rowid = documents.id. It stores app-generated
-- projections; the app owns multilingual tokenization, query rewriting,
-- highlighting, and original-text checks.
--
-- Ownership rules (scan/maintenance):
-- - Only ready documents have rows.
-- - Full `soma system scan` clears and rebuilds all rows for ready documents
--   in committed batches; concurrent readers may observe rebuild progress
--   because whole-operation atomic publication is not required.
--   It also clears derived recipe/rebuild metadata except the schema hash,
--   then republishes the active lexical recipe.
-- - Incremental scan refreshes changed/new/missing ready docs and removes
--   non-ready/deleted ones.
-- - The active lexical recipe lives in soma_meta. A recipe change clears this
--   table before the new recipe is published and projected rows are rebuilt.
-- - Triggers must not insert raw contents.body; they may only delete stale rows.
CREATE VIRTUAL TABLE IF NOT EXISTS fts_index USING fts5(
    -- Projected title text from the app lexical projector.
    title,
    -- Projected body text from the app lexical projector.
    body,
    tokenize = 'porter unicode61'
);
-- rowid (hidden): aligned with documents.id.

-- 7. soma_meta
-- Database metadata. A missing or mismatched database.schema.sha256 makes the
-- index incompatible and triggers a rebuild from bundled db/schema.sql during
-- write-lock initialization. Active processing recipes are stored under
-- active.* keys (for example, active.extraction.pdf_recipe_id).
-- Recipe values are lowercase 64-hex SHA-256 strings validated by the app.
CREATE TABLE IF NOT EXISTS soma_meta (
    -- PK. Metadata key, e.g. database.schema.sha256.
    key TEXT PRIMARY KEY,
    -- Metadata value.
    value TEXT NOT NULL,
    -- Last update time.
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (key <> ''),
    CHECK (value <> '')
);

-- Sync triggers and index maintenance.

-- FTS Lifecycle Triggers.
-- fts_index is never auto-populated. Scan code writes projected rows after
-- applying lexical projection rules; triggers only remove stale rows so
-- deleted/changed/non-ready documents stop being searchable before the next
-- projected write.
CREATE TRIGGER IF NOT EXISTS documents_ad
AFTER DELETE ON documents
BEGIN
    DELETE FROM fts_index WHERE rowid = old.id;
END;

CREATE TRIGGER IF NOT EXISTS documents_au
AFTER UPDATE OF project_name, path, content_hash, title, extraction_status ON documents
BEGIN
    DELETE FROM fts_index WHERE rowid = old.id;
END;

-- Vector Invalidation Triggers.
-- Deleting embedding metadata removes matching vectors rows; changes to document
-- identity/readiness delete document-scoped embeddings and cascade to vectors.
-- The app rebuilds chunks/embeddings/vectors if the document stays searchable.
CREATE TRIGGER IF NOT EXISTS embeddings_ad
AFTER DELETE ON embeddings
BEGIN
    DELETE FROM vectors WHERE rowid = old.id;
END;

CREATE TRIGGER IF NOT EXISTS documents_vector_invalidate_au
AFTER UPDATE OF project_name, content_hash, extraction_status ON documents
BEGIN
    DELETE FROM embeddings WHERE document_id = old.id;
END;
