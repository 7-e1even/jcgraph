-- jcgraph unified code graph schema (format-agnostic: jar/war/class/jmod/java)
-- The graph is an INDEX of coordinates + relationships. Source/method bodies are
-- NOT stored here; they are read from file_path (source) or decompiled on demand
-- (bytecode). See io.jcgraph.content.ContentResolver.

CREATE TABLE IF NOT EXISTS schema_versions (
    version     INTEGER PRIMARY KEY,
    applied_at  INTEGER NOT NULL,
    description TEXT
);
INSERT OR IGNORE INTO schema_versions(version, applied_at, description)
VALUES(1, strftime('%s', 'now') * 1000, 'Initial schema');

CREATE TABLE IF NOT EXISTS nodes (
    id          TEXT PRIMARY KEY,   -- C:<internal> | M:<owner>#<name><desc> | F:<owner>#<name>
    kind        TEXT NOT NULL,      -- class|interface|enum|annotation|method|field
    name        TEXT NOT NULL,      -- simple name
    owner       TEXT,               -- declaring class internal name (slashes)
    descriptor  TEXT,               -- JVM descriptor for methods/fields
    file_path   TEXT,               -- abs path to .class (bytecode) or .java (source)
    start_line  INTEGER DEFAULT 0,
    end_line    INTEGER DEFAULT 0,
    entry_kind  TEXT,               -- HTTP|SERVLET|FILTER|MQ|MAIN|ASYNC, null for non-entries
    synthetic   INTEGER DEFAULT 0   -- 1 if ACC_SYNTHETIC|ACC_BRIDGE (compiler-generated)
);
CREATE INDEX IF NOT EXISTS idx_nodes_name        ON nodes(name);
CREATE INDEX IF NOT EXISTS idx_nodes_kind        ON nodes(kind);
CREATE INDEX IF NOT EXISTS idx_nodes_owner       ON nodes(owner);
CREATE INDEX IF NOT EXISTS idx_nodes_entry_kind  ON nodes(entry_kind);
CREATE INDEX IF NOT EXISTS idx_nodes_synthetic   ON nodes(synthetic);

CREATE VIRTUAL TABLE IF NOT EXISTS nodes_fts USING fts5(
    id UNINDEXED,
    name,
    owner,
    descriptor
);

CREATE TABLE IF NOT EXISTS edges (
    source     TEXT NOT NULL,
    target     TEXT NOT NULL,
    kind       TEXT NOT NULL,       -- contains|calls|extends|implements|overrides|references
    line       INTEGER DEFAULT 0,
    origin     TEXT,
    provenance TEXT,                -- how the edge was derived (asm|source-name|...)
    PRIMARY KEY (source, target, kind)
);
CREATE INDEX IF NOT EXISTS idx_edges_source ON edges(source, kind);
CREATE INDEX IF NOT EXISTS idx_edges_target ON edges(target, kind);

CREATE TABLE IF NOT EXISTS files (
    path         TEXT PRIMARY KEY,   -- abs path on disk (extracted .class or .java)
    origin       TEXT,
    container    TEXT,               -- original jar/war/jmod it came from (if any)
    language     TEXT,
    content_hash TEXT,
    size         INTEGER DEFAULT 0,
    modified_at  INTEGER DEFAULT 0,
    indexed_at   INTEGER DEFAULT 0,
    errors       TEXT
);
CREATE INDEX IF NOT EXISTS idx_files_origin ON files(origin);
CREATE INDEX IF NOT EXISTS idx_files_language ON files(language);
CREATE INDEX IF NOT EXISTS idx_files_modified_at ON files(modified_at);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);
