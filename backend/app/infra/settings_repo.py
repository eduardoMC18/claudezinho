from app.infra.db import Database


class SettingsRepository:
    """Preferências não secretas (tabela `setting`). Chaves de API NÃO ficam aqui."""

    def __init__(self, db: Database) -> None:
        self._db = db

    def get(self, key: str) -> str | None:
        with self._db.session() as conn:
            row = conn.execute("SELECT value FROM setting WHERE key = ?", (key,)).fetchone()
        return row["value"] if row else None

    def set(self, key: str, value: str) -> None:
        with self._db.session() as conn:
            conn.execute(
                "INSERT INTO setting (key, value) VALUES (?, ?) "
                "ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (key, value),
            )
