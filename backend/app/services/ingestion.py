"""Ingestão de PDFs: extrai texto por página, divide em trechos e indexa (FTS5)."""

import logging
import sqlite3
import threading
from collections.abc import Callable

import pymupdf

from app.domain.models import STATUS_ERROR, STATUS_NO_TEXT, STATUS_PROCESSING, STATUS_READY
from app.infra.documents_repo import DocumentRepository
from app.services.chunking import split_page_text

log = logging.getLogger(__name__)

MIN_CHARS_FOR_TEXT_PAGE = 20
MIN_TEXT_PAGE_RATIO = 0.10  # abaixo disso o PDF é tratado como "sem texto" (escaneado)
PROGRESS_EVERY_PAGES = 5

Executor = Callable[[Callable[[], None]], None]


def spawn_thread(fn: Callable[[], None]) -> None:
    threading.Thread(target=fn, name="ingestion", daemon=True).start()


class IngestionService:
    def __init__(self, repo: DocumentRepository, executor: Executor = spawn_thread) -> None:
        self._repo = repo
        self._executor = executor

    def start(self, doc_id: str) -> None:
        self._executor(lambda: self.run(doc_id))

    def reindex_all(self) -> None:
        """Reindexa todos os documentos (usado quando o algoritmo de chunking muda)."""
        for doc_id in self._repo.all_ids():
            self._repo.delete_chunks(doc_id)
            self._repo.update_progress(doc_id, 0)
            self._repo.set_status(doc_id, STATUS_PROCESSING)
            self.start(doc_id)

    def resume_pending(self) -> None:
        """Reinicia documentos que ficaram 'processing' (app encerrado no meio)."""
        for doc_id in self._repo.processing_ids():
            self._repo.delete_chunks(doc_id)
            self._repo.update_progress(doc_id, 0)
            self.start(doc_id)

    def run(self, doc_id: str) -> None:
        doc = self._repo.get(doc_id)
        if doc is None:
            return
        try:
            pages_with_text = 0
            with pymupdf.open(doc.path) as pdf:
                for number, page in enumerate(pdf, start=1):
                    if number % PROGRESS_EVERY_PAGES == 1 and not self._repo.exists(doc_id):
                        return  # documento removido/substituído: cancela
                    chunks = split_page_text(page.get_text("text"))
                    if sum(len(c) for c in chunks) >= MIN_CHARS_FOR_TEXT_PAGE:
                        pages_with_text += 1
                    if chunks:
                        self._repo.add_chunks(doc_id, [(number, i, c) for i, c in enumerate(chunks)])
                    if number % PROGRESS_EVERY_PAGES == 0 or number == doc.pages:
                        self._repo.update_progress(doc_id, number)
            has_text = doc.pages > 0 and pages_with_text / doc.pages >= MIN_TEXT_PAGE_RATIO
            self._repo.set_status(doc_id, STATUS_READY if has_text else STATUS_NO_TEXT)
        except sqlite3.IntegrityError:
            return  # documento removido durante a ingestão
        except Exception as exc:
            log.exception("Falha na ingestão do documento %s", doc_id)
            self._repo.set_status(doc_id, STATUS_ERROR, str(exc))
