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
