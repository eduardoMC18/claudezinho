"""Cache de metadados do Canvas e histórico de PDFs abertos (SQLite)."""

import json
import time
from datetime import datetime, timezone
from typing import Any

from app.infra.db import Database


class CanvasRepository:
    def __init__(self, db: Database) -> None:
        self._db = db

    # ---- cache ----
    def cache_get(self, key: str) -> tuple[Any, float] | None:
        with self._db.session() as conn:
            row = conn.execute("SELECT value_json, fetched_at FROM canvas_cache WHERE key = ?", (key,)).fetchone()
        return (json.loads(row["value_json"]), row["fetched_at"]) if row else None

    def cache_set(self, key: str, value: Any) -> None:
        with self._db.session() as conn:
            conn.execute(
                "INSERT INTO canvas_cache (key, value_json, fetched_at) VALUES (?,?,?) "
                "ON CONFLICT(key) DO UPDATE SET value_json = excluded.value_json, fetched_at = excluded.fetched_at",
                (key, json.dumps(value, ensure_ascii=False), time.time()),
            )

    def cache_clear(self) -> None:
        with self._db.session() as conn:
            conn.execute("DELETE FROM canvas_cache")
            conn.execute("DELETE FROM canvas_history")

    # ---- histórico ----
    def history_add(self, *, base_url: str, file_id: int, course_id: int, course_name: str,
                    module_id: str, module_name: str, name: str) -> None:
        now = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with self._db.session() as conn:
            conn.execute(
                "INSERT INTO canvas_history (file_id, base_url, course_id, course_name, module_id, module_name, name, opened_at) "
                "VALUES (?,?,?,?,?,?,?,?) ON CONFLICT(base_url, file_id) DO UPDATE SET "
                "course_id=excluded.course_id, course_name=excluded.course_name, module_id=excluded.module_id, "
                "module_name=excluded.module_name, name=excluded.name, opened_at=excluded.opened_at",
                (file_id, base_url, course_id, course_name, module_id, module_name, name, now),
            )

    def history_set_page(self, base_url: str, file_id: int, page: int) -> None:
        with self._db.session() as conn:
            conn.execute("UPDATE canvas_history SET last_page = ? WHERE base_url = ? AND file_id = ?",
                         (max(1, page), base_url, file_id))

    def history_list(self, base_url: str, limit: int = 20) -> list[dict[str, Any]]:
        with self._db.session() as conn:
            rows = conn.execute(
                "SELECT file_id, course_id, course_name, module_id, module_name, name, last_page, opened_at "
                "FROM canvas_history WHERE base_url = ? ORDER BY opened_at DESC LIMIT ?", (base_url, limit),
            ).fetchall()
        return [dict(r) for r in rows]
