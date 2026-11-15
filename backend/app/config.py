import os
from pathlib import Path

DATA_DIR_ENV = "ESTUDOTRON_DATA_DIR"


def data_dir() -> Path:
    """Pasta dos dados do usuário (banco). Pode ser trocada por ESTUDOTRON_DATA_DIR."""
    override = os.environ.get(DATA_DIR_ENV)
    if override:
        return Path(override)
    appdata = os.environ.get("APPDATA")
    base = Path(appdata) if appdata else Path.home()
    return base / "estudoTron3000"


def default_db_path() -> Path:
    return data_dir() / "estudotron.db"
