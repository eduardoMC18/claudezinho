-- Esquema inicial (Fase 1)

CREATE TABLE workspace (
    id         INTEGER PRIMARY KEY,
    name       TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);
INSERT INTO workspace (id, name) VALUES (1, 'Padrão');

CREATE TABLE document (
    id           TEXT PRIMARY KEY,
    workspace_id INTEGER NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    slot         TEXT NOT NULL CHECK (slot IN ('A', 'B')),
    path         TEXT NOT NULL,
    name         TEXT NOT NULL,
    sha256       TEXT NOT NULL,
    size_bytes   INTEGER NOT NULL,
    mtime_ns     INTEGER NOT NULL,
    pages        INTEGER NOT NULL,
    title        TEXT,
    status       TEXT NOT NULL CHECK (status IN ('processing', 'ready', 'no_text', 'error')),
    pages_done   INTEGER NOT NULL DEFAULT 0,
    error        TEXT,
    created_at   TEXT NOT NULL DEFAULT (datetime('now')),
    UNIQUE (workspace_id, slot)
);

CREATE TABLE chunk (
    id          INTEGER PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    page        INTEGER NOT NULL,
    ord         INTEGER NOT NULL,
    text        TEXT NOT NULL
);
CREATE INDEX idx_chunk_doc_page ON chunk (document_id, page);

-- Índice de busca (usado pelo chat na Fase 2: recuperação por BM25).
CREATE VIRTUAL TABLE chunk_fts USING fts5(
    text,
    content = 'chunk',
    content_rowid = 'id',
    tokenize = 'unicode61 remove_diacritics 2'
);
CREATE TRIGGER chunk_ai AFTER INSERT ON chunk BEGIN
    INSERT INTO chunk_fts (rowid, text) VALUES (new.id, new.text);
END;
CREATE TRIGGER chunk_ad AFTER DELETE ON chunk BEGIN
    INSERT INTO chunk_fts (chunk_fts, rowid, text) VALUES ('delete', old.id, old.text);
END;

CREATE TABLE setting (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
