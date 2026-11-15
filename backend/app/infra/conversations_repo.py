"""Persistência de conversas e mensagens (SQLite)."""

import json
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

from app.infra.db import Database


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


@dataclass
class StoredMessage:
    id: str
    conversation_id: str
    role: str
    content: str
    created_at: str
    provider: str | None = None
    model: str | None = None
    status: str = "complete"
    error: str | None = None
    error_kind: str | None = None
    references: list[dict] = field(default_factory=list)
    usage: dict | None = None


@dataclass(frozen=True)
class StoredConversation:
    id: str
    workspace_id: int
    title: str
    created_at: str
    updated_at: str


def _to_message(row) -> StoredMessage:
    return StoredMessage(
        id=row["id"], conversation_id=row["conversation_id"], role=row["role"], content=row["content"],
        created_at=row["created_at"], provider=row["provider"], model=row["model"], status=row["status"],
        error=row["error"], error_kind=row["error_kind"],
        references=json.loads(row["references_json"] or "[]"),
        usage=json.loads(row["usage_json"]) if row["usage_json"] else None,
    )


class ConversationRepository:
    def __init__(self, db: Database) -> None:
        self._db = db

    # ----- conversas -----

    def create(self, workspace_id: int) -> StoredConversation:
        conv = StoredConversation(uuid.uuid4().hex, workspace_id, "", now_iso(), now_iso())
        with self._db.session() as conn:
            conn.execute(
                "INSERT INTO conversation (id, workspace_id, title, created_at, updated_at) VALUES (?,?,?,?,?)",
                (conv.id, conv.workspace_id, conv.title, conv.created_at, conv.updated_at),
            )
        return conv

    def get(self, conversation_id: str) -> StoredConversation | None:
        with self._db.session() as conn:
            row = conn.execute("SELECT * FROM conversation WHERE id = ?", (conversation_id,)).fetchone()
        return StoredConversation(**{k: row[k] for k in row.keys()}) if row else None

    def latest(self, workspace_id: int) -> StoredConversation | None:
        with self._db.session() as conn:
            row = conn.execute(
                "SELECT * FROM conversation WHERE workspace_id = ? ORDER BY updated_at DESC, rowid DESC LIMIT 1",
                (workspace_id,),
            ).fetchone()
        return StoredConversation(**{k: row[k] for k in row.keys()}) if row else None

    def list_all(self, workspace_id: int) -> list[tuple[StoredConversation, int]]:
        with self._db.session() as conn:
            rows = conn.execute(
                "SELECT c.*, (SELECT count(*) FROM message m WHERE m.conversation_id = c.id) AS n "
                "FROM conversation c WHERE c.workspace_id = ? ORDER BY c.updated_at DESC, c.rowid DESC",
                (workspace_id,),
            ).fetchall()
        return [(StoredConversation(**{k: r[k] for k in r.keys() if k != "n"}), r["n"]) for r in rows]

    def set_title_if_empty(self, conversation_id: str, title: str) -> None:
        with self._db.session() as conn:
            conn.execute("UPDATE conversation SET title = ? WHERE id = ? AND title = ''", (title, conversation_id))

    def touch(self, conversation_id: str) -> None:
        with self._db.session() as conn:
            conn.execute("UPDATE conversation SET updated_at = ? WHERE id = ?", (now_iso(), conversation_id))

    # ----- mensagens -----

    def add_message(self, message: StoredMessage) -> None:
        with self._db.session() as conn:
            conn.execute(
                "INSERT INTO message (id, conversation_id, role, content, provider, model, status, error, "
                "error_kind, references_json, usage_json, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (message.id, message.conversation_id, message.role, message.content, message.provider,
                 message.model, message.status, message.error, message.error_kind,
                 json.dumps(message.references, ensure_ascii=False),
                 json.dumps(message.usage) if message.usage else None, message.created_at),
            )
            conn.execute("UPDATE conversation SET updated_at = ? WHERE id = ?", (now_iso(), message.conversation_id))

    def update_message(self, message: StoredMessage) -> None:
        with self._db.session() as conn:
            conn.execute(
                "UPDATE message SET content = ?, status = ?, error = ?, error_kind = ?, references_json = ?, "
                "usage_json = ?, provider = ?, model = ? WHERE id = ?",
                (message.content, message.status, message.error, message.error_kind,
                 json.dumps(message.references, ensure_ascii=False),
                 json.dumps(message.usage) if message.usage else None,
                 message.provider, message.model, message.id),
            )

    def messages(self, conversation_id: str) -> list[StoredMessage]:
        with self._db.session() as conn:
            rows = conn.execute(
                "SELECT * FROM message WHERE conversation_id = ? ORDER BY rowid", (conversation_id,)
            ).fetchall()
        return [_to_message(r) for r in rows]

    def mark_streaming_as_cancelled(self) -> None:
        """Mensagens que ficaram 'streaming' (app fechado no meio) viram 'cancelled'."""
        with self._db.session() as conn:
            conn.execute("UPDATE message SET status = 'cancelled' WHERE status = 'streaming'")
