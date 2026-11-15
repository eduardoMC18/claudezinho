"""Conexão SQLite e migrações versionadas.

Cada operação usa sua própria conexão (os endpoints rodam em threads diferentes
e a ingestão roda em segundo plano). WAL permite leitura durante a escrita.
"""

import re
import sqlite3
from collections.abc import Iterator
from contextlib import contextmanager
from pathlib import Path

MIGRATIONS_DIR = Path(__file__).resolve().parent / "migrations"
_MIGRATION_NAME = re.compile(r"^(\d{4})_.+\.sql$")


class Database:
    def __init__(self, path: Path) -> None:
        self.path = path
        path.parent.mkdir(parents=True, exist_ok=True)
        self._migrate()

    def _open(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.path, timeout=30)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA foreign_keys = ON")
        return conn

    @contextmanager
    def session(self) -> Iterator[sqlite3.Connection]:
        """Transação: commit ao sair normalmente, rollback em exceção."""
        conn = self._open()
        try:
            yield conn
            conn.commit()
        except BaseException:
            conn.rollback()
            raise
        finally:
            conn.close()

    def _migrate(self) -> None:
        conn = self._open()
        try:
            conn.execute("PRAGMA journal_mode = WAL")
            conn.execute(
                "CREATE TABLE IF NOT EXISTS schema_migrations ("
                "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL DEFAULT (datetime('now')))"
            )
            conn.commit()
            applied = {row[0] for row in conn.execute("SELECT version FROM schema_migrations")}
            for file in sorted(MIGRATIONS_DIR.glob("*.sql")):
                match = _MIGRATION_NAME.match(file.name)
                if not match:
                    raise RuntimeError(f"Nome de migração inválido: {file.name}")
                version = int(match.group(1))
                if version in applied:
                    continue
                conn.executescript(file.read_text(encoding="utf-8"))
                conn.execute("INSERT INTO schema_migrations (version) VALUES (?)", (version,))
                conn.commit()
        finally:
            conn.close()
