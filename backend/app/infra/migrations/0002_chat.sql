-- Histórico de conversas e preparo para busca vetorial (Fase 2)

CREATE TABLE conversation (
    id           TEXT PRIMARY KEY,
    workspace_id INTEGER NOT NULL REFERENCES workspace(id) ON DELETE CASCADE,
    title        TEXT NOT NULL DEFAULT '',
    created_at   TEXT NOT NULL,
    updated_at   TEXT NOT NULL
);
CREATE INDEX idx_conversation_workspace ON conversation (workspace_id, updated_at);

CREATE TABLE message (
    id              TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role            TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT NOT NULL,
    provider        TEXT,
    model           TEXT,
    status          TEXT NOT NULL DEFAULT 'complete'
                    CHECK (status IN ('complete', 'streaming', 'cancelled', 'error')),
    error           TEXT,
    error_kind      TEXT,
    references_json TEXT NOT NULL DEFAULT '[]',   -- citações validadas: [{slot, page, file_name, document_id}]
    usage_json      TEXT,                          -- {input_tokens, output_tokens, cost_usd}
    created_at      TEXT NOT NULL
);
CREATE INDEX idx_message_conversation ON message (conversation_id);

-- Espaço para embeddings (busca vetorial futura). Hoje a recuperação usa só texto (BM25).
ALTER TABLE chunk ADD COLUMN embedding BLOB;
ALTER TABLE chunk ADD COLUMN embedding_model TEXT;
