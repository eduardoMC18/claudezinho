"""Regras do workspace: dois slots (A e B), cada um com no máximo um PDF."""

import hashlib
import uuid
from pathlib import Path

from app.domain.models import (
    DEFAULT_WORKSPACE_ID,
    SLOTS,
    STATUS_PROCESSING,
    Document,
    WorkspaceError,
)
from app.infra.documents_repo import DocumentRepository
from app.infra.pdf import InvalidPdfError, inspect_pdf
from app.services.ingestion import IngestionService

FILE_OK = "ok"
FILE_MODIFIED = "modified"
FILE_MISSING = "missing"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


class WorkspaceService:
    def __init__(self, repo: DocumentRepository, ingestion: IngestionService) -> None:
        self._repo = repo
        self._ingestion = ingestion

    def get_slots(self) -> dict[str, Document | None]:
        return {slot: self._repo.get_by_slot(DEFAULT_WORKSPACE_ID, slot) for slot in SLOTS}

    def get_document(self, doc_id: str) -> Document:
        doc = self._repo.get(doc_id)
        if doc is None:
            raise WorkspaceError(WorkspaceError.NOT_FOUND, "Documento não encontrado")
        return doc

    def set_slot(self, slot: str, path_text: str) -> Document:
        """Carrega um PDF no slot, substituindo o anterior, e inicia a indexação."""
        self._check_slot(slot)
        path = Path(path_text)
        if path.suffix.lower() != ".pdf":
            raise WorkspaceError(WorkspaceError.BAD_REQUEST, "Apenas arquivos .pdf são aceitos")
        if not path.is_file():
            raise WorkspaceError(WorkspaceError.NOT_FOUND, "Arquivo não encontrado")
        try:
            info = inspect_pdf(path)
            stat = path.stat()
            digest = sha256_file(path)
        except InvalidPdfError as exc:
            raise WorkspaceError(WorkspaceError.INVALID_PDF, str(exc)) from exc
        except OSError as exc:
            raise WorkspaceError(WorkspaceError.BAD_REQUEST, f"Não foi possível ler o arquivo: {exc}") from exc

        doc = Document(
            id=uuid.uuid4().hex,
            workspace_id=DEFAULT_WORKSPACE_ID,
            slot=slot,
            path=str(path.resolve()),
            name=path.name,
            sha256=digest,
            size_bytes=stat.st_size,
            mtime_ns=stat.st_mtime_ns,
            pages=info.pages,
            title=info.title,
            status=STATUS_PROCESSING,
            pages_done=0,
            error=None,
        )
        self._repo.replace_in_slot(doc)
        self._ingestion.start(doc.id)
        return doc

    def clear_slot(self, slot: str) -> None:
        self._check_slot(slot)
        self._repo.clear_slot(DEFAULT_WORKSPACE_ID, slot)

    @staticmethod
    def file_state(doc: Document) -> str:
        """Compara o arquivo em disco com o que foi carregado (tamanho/mtime e, se preciso, hash)."""
        path = Path(doc.path)
        try:
            stat = path.stat()
            if stat.st_size == doc.size_bytes and stat.st_mtime_ns == doc.mtime_ns:
                return FILE_OK
            return FILE_OK if sha256_file(path) == doc.sha256 else FILE_MODIFIED
        except OSError:
            return FILE_MISSING

    @staticmethod
    def _check_slot(slot: str) -> None:
        if slot not in SLOTS:
            raise WorkspaceError(WorkspaceError.NOT_FOUND, "Slot inválido (use A ou B)")
