import sqlite3
from collections.abc import Iterable
from dataclasses import dataclass

from app.domain.models import STATUS_PROCESSING, Document
from app.infra.db import Database

_COLUMNS = (
    "id, workspace_id, slot, path, name, sha256, size_bytes, mtime_ns, "
    "pages, title, status, pages_done, error"
)


@dataclass(frozen=True)
class ChunkRow:
    id: int
    document_id: str
    page: int
    ord: int
    text: str
    score: float


def _to_document(row: sqlite3.Row) -> Document:
    return Document(**{key: row[key] for key in row.keys()})


class DocumentRepository:
    def __init__(self, db: Database) -> None:
        self._db = db

    def get(self, doc_id: str) -> Document | None:
        with self._db.session() as conn:
            row = conn.execute(f"SELECT {_COLUMNS} FROM document WHERE id = ?", (doc_id,)).fetchone()
        return _to_document(row) if row else None

    def get_by_slot(self, workspace_id: int, slot: str) -> Document | None:
        with self._db.session() as conn:
            row = conn.execute(
                f"SELECT {_COLUMNS} FROM document WHERE workspace_id = ? AND slot = ?",
                (workspace_id, slot),
            ).fetchone()
        return _to_document(row) if row else None

    def exists(self, doc_id: str) -> bool:
        with self._db.session() as conn:
            return conn.execute("SELECT 1 FROM document WHERE id = ?", (doc_id,)).fetchone() is not None

    def replace_in_slot(self, doc: Document) -> None:
        """Remove o documento atual do slot (com seus trechos) e insere o novo, atomicamente."""
        with self._db.session() as conn:
            self._delete_slot(conn, doc.workspace_id, doc.slot)
            conn.execute(
                f"INSERT INTO document ({_COLUMNS}) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    doc.id, doc.workspace_id, doc.slot, doc.path, doc.name, doc.sha256,
                    doc.size_bytes, doc.mtime_ns, doc.pages, doc.title, doc.status,
                    doc.pages_done, doc.error,
                ),
            )

    def clear_slot(self, workspace_id: int, slot: str) -> bool:
        with self._db.session() as conn:
            return self._delete_slot(conn, workspace_id, slot)

    @staticmethod
    def _delete_slot(conn: sqlite3.Connection, workspace_id: int, slot: str) -> bool:
        row = conn.execute(
            "SELECT id FROM document WHERE workspace_id = ? AND slot = ?", (workspace_id, slot)
        ).fetchone()
        if row is None:
            return False
        # Apaga os trechos explicitamente para o índice FTS5 ser atualizado pelos triggers.
        conn.execute("DELETE FROM chunk WHERE document_id = ?", (row["id"],))
        conn.execute("DELETE FROM document WHERE id = ?", (row["id"],))
        return True

    def add_chunks(self, doc_id: str, rows: Iterable[tuple[int, int, str]]) -> None:
        """Insere (página, ordem, texto). Levanta sqlite3.IntegrityError se o documento sumiu."""
        with self._db.session() as conn:
            conn.executemany(
                "INSERT INTO chunk (document_id, page, ord, text) VALUES (?, ?, ?, ?)",
                [(doc_id, page, order, text) for page, order, text in rows],
            )

    def delete_chunks(self, doc_id: str) -> None:
        with self._db.session() as conn:
            conn.execute("DELETE FROM chunk WHERE document_id = ?", (doc_id,))

    def update_progress(self, doc_id: str, pages_done: int) -> None:
        with self._db.session() as conn:
            conn.execute("UPDATE document SET pages_done = ? WHERE id = ?", (pages_done, doc_id))

    def set_status(self, doc_id: str, status: str, error: str | None = None) -> None:
        with self._db.session() as conn:
            conn.execute("UPDATE document SET status = ?, error = ? WHERE id = ?", (status, error, doc_id))

    def processing_ids(self) -> list[str]:
        with self._db.session() as conn:
            rows = conn.execute("SELECT id FROM document WHERE status = ?", (STATUS_PROCESSING,)).fetchall()
        return [row["id"] for row in rows]

    def search_chunks(self, doc_id: str, match: str, limit: int) -> list["ChunkRow"]:
        """Busca BM25 (FTS5) em UM documento. `match` já deve ser uma expressão FTS5 válida.
        Menor `score` = mais relevante (convenção do bm25 do SQLite)."""
        with self._db.session() as conn:
            rows = conn.execute(
                "SELECT c.id, c.document_id, c.page, c.ord, c.text, bm25(chunk_fts) AS score "
                "FROM chunk_fts JOIN chunk c ON c.id = chunk_fts.rowid "
                "WHERE chunk_fts MATCH ? AND c.document_id = ? "
                "ORDER BY score LIMIT ?",
                (match, doc_id, limit),
            ).fetchall()
        return [ChunkRow(r["id"], r["document_id"], r["page"], r["ord"], r["text"], r["score"]) for r in rows]

    def spread_chunks(self, doc_id: str, count: int) -> list["ChunkRow"]:
        """`count` trechos espaçados por todo o documento (visão geral para resumos)."""
        with self._db.session() as conn:
            ids = [r["id"] for r in conn.execute(
                "SELECT id FROM chunk WHERE document_id = ? ORDER BY page, ord", (doc_id,))]
            if not ids or count <= 0:
                return []
            last = len(ids) - 1
            picks = sorted({ids[round(i * last / (count - 1))] if count > 1 else ids[0] for i in range(count)})
            marks = ",".join("?" * len(picks))
            rows = conn.execute(
                f"SELECT id, document_id, page, ord, text FROM chunk WHERE id IN ({marks}) ORDER BY page, ord",
                picks,
            ).fetchall()
        return [ChunkRow(r["id"], r["document_id"], r["page"], r["ord"], r["text"], 0.0) for r in rows]

    def window_chunks(self, doc_id: str, size: int, start_fraction: float) -> list["ChunkRow"]:
        """`size` trechos CONSECUTIVOS a partir de um ponto do documento (0.0 = início, 1.0 = fim).
        Dá material coeso e variável para criar exercícios sem nenhuma pergunta de busca."""
        with self._db.session() as conn:
            ids = [r["id"] for r in conn.execute(
                "SELECT id FROM chunk WHERE document_id = ? ORDER BY page, ord", (doc_id,))]
            if not ids or size <= 0:
                return []
            first = round(min(max(start_fraction, 0.0), 1.0) * max(len(ids) - size, 0))
            picks = ids[first:first + size]
            marks = ",".join("?" * len(picks))
            rows = conn.execute(
                f"SELECT id, document_id, page, ord, text FROM chunk WHERE id IN ({marks}) ORDER BY page, ord",
                picks,
            ).fetchall()
        return [ChunkRow(r["id"], r["document_id"], r["page"], r["ord"], r["text"], 0.0) for r in rows]

    def all_ids(self) -> list[str]:
        with self._db.session() as conn:
            return [r["id"] for r in conn.execute("SELECT id FROM document")]

    def search(self, doc_ids: list[str], query: str, limit: int = 8) -> list[tuple[str, int, str]]:
        """Busca BM25 nos trechos (base da recuperação do chat na Fase 2)."""
        if not doc_ids:
            return []
        marks = ",".join("?" * len(doc_ids))
        with self._db.session() as conn:
            rows = conn.execute(
                f"SELECT c.document_id, c.page, c.text FROM chunk_fts "
                f"JOIN chunk c ON c.id = chunk_fts.rowid "
                f"WHERE chunk_fts MATCH ? AND c.document_id IN ({marks}) "
                f"ORDER BY bm25(chunk_fts) LIMIT ?",
                (query, *doc_ids, limit),
            ).fetchall()
        return [(r["document_id"], r["page"], r["text"]) for r in rows]
