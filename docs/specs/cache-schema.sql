-- Soma machine-global processing cache schema.
--
-- Bounded best-effort memoization; never a source of truth.
-- cache_key is the raw SHA-256 of domain, key version, operation, recipe ID,
-- and exact input hash. Cache only complete, validated successes.

-- Set auto_vacuum before tables; cache_meta controls schema compatibility.
PRAGMA journal_mode = WAL;             -- persistent setting
PRAGMA auto_vacuum = INCREMENTAL;      -- persistent setting
PRAGMA synchronous = NORMAL;           -- transient setting
PRAGMA busy_timeout = 5000;            -- transient setting

CREATE TABLE IF NOT EXISTS process_cache (
    -- Raw 32-byte request identity.
    cache_key BLOB NOT NULL CHECK (length(cache_key) = 32),
    -- Current operations: ocr.text, image.describe, pdf.text, media.transcribe,
    -- query.expand (including HyDE), query.embed, and rerank.
    operation TEXT NOT NULL CHECK (operation <> ''),
    -- Lowercase SHA-256 of ordered recipe parts: domain, explicit revision,
    -- effective parameters/prompts, and dependency recipe IDs.
    recipe_id TEXT NOT NULL CHECK (length(recipe_id) = 64 AND recipe_id = lower(recipe_id)),
    -- Lowercase SHA-256 of exact source bytes or canonical request bytes.
    input_hash TEXT NOT NULL CHECK (length(input_hash) = 64 AND input_hash = lower(input_hash)),
    -- Processor-owned encoding.
    payload BLOB NOT NULL,
    -- FIFO timestamp; cache hits do not update it.
    created_at INTEGER NOT NULL DEFAULT (unixepoch()) CHECK (created_at >= 0),
    PRIMARY KEY (cache_key)
) WITHOUT ROWID, STRICT;

-- Debug lookup by exact input identity.
CREATE INDEX IF NOT EXISTS idx_process_cache_input
ON process_cache(input_hash, operation, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_process_cache_created_at
ON process_cache(created_at);

-- Cap at 10,000,000 entries. On overflow, evict 10% of the cache at once.
CREATE TRIGGER IF NOT EXISTS trg_process_cache_evict
AFTER INSERT ON process_cache
WHEN (SELECT count(*) FROM process_cache) > 10000000
BEGIN
    DELETE FROM process_cache
    WHERE cache_key IN (
        SELECT cache_key
        FROM process_cache
        ORDER BY
            CASE
                WHEN operation IN ('ocr.text', 'image.describe', 'pdf.text', 'media.transcribe') THEN 1
                ELSE 0
            END,
            created_at,
            cache_key
        LIMIT (SELECT count(*) - 9000000 FROM process_cache)
    );
END;

-- cache.schema.sha256 stores this file's digest; missing/mismatch means recreate.
CREATE TABLE IF NOT EXISTS cache_meta (
    key TEXT NOT NULL CHECK (key <> ''),
    value TEXT NOT NULL,
    PRIMARY KEY (key)
) WITHOUT ROWID, STRICT;
