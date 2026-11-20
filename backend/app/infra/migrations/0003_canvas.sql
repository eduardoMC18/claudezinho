-- Integração com o Canvas LMS: origem dos documentos, cache de metadados e histórico.
-- O token do Canvas NUNCA fica aqui (vai para o cofre do sistema).

ALTER TABLE document ADD COLUMN source TEXT NOT NULL DEFAULT 'local';      -- local | canvas
ALTER TABLE document ADD COLUMN source_json TEXT;                           -- metadados de origem (Canvas)
ALTER TABLE document ADD COLUMN last_page INTEGER NOT NULL DEFAULT 1;

-- Cache de metadados (cursos, módulos, itens) com data da busca; a interface pode forçar a atualização.
CREATE TABLE canvas_cache (
    key        TEXT PRIMARY KEY,
    value_json TEXT NOT NULL,
    fetched_at REAL NOT NULL
);

-- Histórico de PDFs abertos pelo Canvas (último primeiro).
CREATE TABLE canvas_history (
    file_id     INTEGER NOT NULL,
    base_url    TEXT NOT NULL,
    course_id   INTEGER NOT NULL,
    course_name TEXT NOT NULL,
    module_id   TEXT NOT NULL,
    module_name TEXT NOT NULL,
    name        TEXT NOT NULL,
    last_page   INTEGER NOT NULL DEFAULT 1,
    opened_at   TEXT NOT NULL,
    PRIMARY KEY (base_url, file_id)
);
