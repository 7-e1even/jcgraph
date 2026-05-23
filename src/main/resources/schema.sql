-- jcgraph unified code graph schema (format-agnostic: jar/war/class/jmod/java)
-- The graph is an INDEX of coordinates + relationships. Source/method bodies are
-- NOT stored here; they are read from file_path (source) or decompiled on demand
-- (bytecode). See io.jcgraph.content.ContentResolver.

CREATE TABLE IF NOT EXISTS nodes (
    id          TEXT PRIMARY KEY,   -- C:<internal> | M:<owner>#<name><desc> | F:<owner>#<name>
    kind        TEXT NOT NULL,      -- class|interface|enum|annotation|method|field
    name        TEXT NOT NULL,      -- simple name
    owner       TEXT,               -- declaring class internal name (slashes)
    descriptor  TEXT,               -- JVM descriptor for methods/fields
    signature   TEXT,               -- human-readable signature
    access      INTEGER DEFAULT 0,  -- ASM/JVM access flags
    file_path   TEXT,               -- abs path to .class (bytecode) or .java (source)
    origin      TEXT NOT NULL,      -- bytecode|source
    start_line  INTEGER DEFAULT 0,
    end_line    INTEGER DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_nodes_name  ON nodes(name);
CREATE INDEX IF NOT EXISTS idx_nodes_kind  ON nodes(kind);
CREATE INDEX IF NOT EXISTS idx_nodes_owner ON nodes(owner);

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

-- string constants pulled from the bytecode constant pool (cheap literal grep
-- without decompiling), and string literals from source.
CREATE TABLE IF NOT EXISTS strings (
    id     INTEGER PRIMARY KEY AUTOINCREMENT,
    owner  TEXT,   -- class internal name
    method TEXT,   -- method name+desc where the literal appears
    value  TEXT NOT NULL,
    origin TEXT
);
CREATE INDEX IF NOT EXISTS idx_strings_value ON strings(value);

CREATE TABLE IF NOT EXISTS files (
    path        TEXT PRIMARY KEY,   -- abs path on disk (extracted .class or .java)
    origin      TEXT,
    container   TEXT,               -- original jar/war/jmod it came from (if any)
    language    TEXT
);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT
);
