from dataclasses import dataclass

SLOTS = ("A", "B")

STATUS_PROCESSING = "processing"
STATUS_READY = "ready"
STATUS_NO_TEXT = "no_text"  # PDF sem texto extraível (escaneado)
STATUS_ERROR = "error"

DEFAULT_WORKSPACE_ID = 1


@dataclass(frozen=True)
class Document:
    id: str
    workspace_id: int
    slot: str
    path: str
    name: str
    sha256: str
    size_bytes: int
    mtime_ns: int
    pages: int
    title: str | None
    status: str
    pages_done: int
    error: str | None
    source: str = "local"  # local | canvas
    source_json: str | None = None  # metadados de origem (Canvas), JSON
    last_page: int = 1


class ChatError(Exception):
    """Erro antes de a conversa começar (nada é gravado). `kind` vira código para a interface."""

    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


class WorkspaceError(Exception):
    """Erro de regra de negócio. `kind` é mapeado para HTTP na camada de API."""

    NOT_FOUND = "not_found"
    BAD_REQUEST = "bad_request"
    INVALID_PDF = "invalid_pdf"

    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


class CanvasError(Exception):
    """Falha ao falar com o Canvas. `kind`: not_connected, auth, forbidden, not_found, rate_limit, network,
    bad_response, not_pdf, too_large, bad_request, changed, storage."""

    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message
