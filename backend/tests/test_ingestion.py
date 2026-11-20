from pathlib import Path

import pytest

from app.domain.models import STATUS_NO_TEXT, STATUS_PROCESSING, STATUS_READY
from app.infra.db import Database
from app.infra.documents_repo import DocumentRepository
from app.services.ingestion import IngestionService
from app.services.workspace import WorkspaceService


@pytest.fixture()
def repo(db_path: Path) -> DocumentRepository:
    return DocumentRepository(Database(db_path))


def make_service(repo: DocumentRepository) -> WorkspaceService:
    return WorkspaceService(repo, IngestionService(repo, executor=lambda fn: fn()))


def test_migrations_are_idempotent(db_path: Path) -> None:
    Database(db_path)
    db = Database(db_path)  # segunda abertura não reaplica nada
    with db.session() as conn:
        versions = [r[0] for r in conn.execute("SELECT version FROM schema_migrations")]
        tables = {r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert versions == [1, 2, 3]
    assert {"workspace", "document", "chunk", "chunk_fts", "setting", "conversation", "message"} <= tables


def test_text_pdf_is_indexed_and_searchable(repo: DocumentRepository, make_pdf) -> None:
    service = make_service(repo)
    doc = service.set_slot("A", str(make_pdf(pages=4)))
    stored = repo.get(doc.id)
    assert stored.status == STATUS_READY and stored.pages_done == 4

    hits = repo.search([doc.id], "clorofila")
    assert len(hits) == 4
    assert {page for _, page, _ in hits} == {1, 2, 3, 4}
    # sem acento / com acento casam igual (remove_diacritics)
    assert repo.search([doc.id], "fotossíntese")


def test_pdf_without_text_is_flagged(repo: DocumentRepository, make_pdf) -> None:
    doc = make_service(repo).set_slot("A", str(make_pdf(text=False)))
    assert repo.get(doc.id).status == STATUS_NO_TEXT
    assert repo.search([doc.id], "pagina") == []


def test_replacing_document_removes_old_chunks_from_index(repo: DocumentRepository, make_pdf) -> None:
    service = make_service(repo)
    old = service.set_slot("A", str(make_pdf("velho.pdf")))
    new = service.set_slot("A", str(make_pdf("novo.pdf")))
    assert repo.search([old.id], "clorofila") == []
    assert repo.search([new.id], "clorofila")
    with repo._db.session() as conn:  # noqa: SLF001 - verificação de integridade do índice
        assert conn.execute("SELECT count(*) FROM chunk WHERE document_id = ?", (old.id,)).fetchone()[0] == 0
        assert conn.execute("SELECT count(*) FROM chunk_fts WHERE chunk_fts MATCH 'clorofila'").fetchone()[0] == 3


def test_removed_document_cancels_ingestion(repo: DocumentRepository, make_pdf) -> None:
    started: list = []
    service = WorkspaceService(repo, IngestionService(repo, executor=started.append))
    doc = service.set_slot("A", str(make_pdf(pages=30)))
    assert repo.get(doc.id).status == STATUS_PROCESSING

    service.clear_slot("A")
    started[0]()  # a ingestão "tardia" roda depois da remoção e deve encerrar sem erro
    assert repo.get(doc.id) is None
    with repo._db.session() as conn:  # noqa: SLF001
        assert conn.execute("SELECT count(*) FROM chunk").fetchone()[0] == 0


def test_resume_pending_restarts_unfinished_documents(repo: DocumentRepository, make_pdf) -> None:
    started: list = []
    service = WorkspaceService(repo, IngestionService(repo, executor=started.append))
    doc = service.set_slot("A", str(make_pdf(pages=3)))
    assert repo.get(doc.id).status == STATUS_PROCESSING  # "app fechou" antes de terminar

    IngestionService(repo, executor=lambda fn: fn()).resume_pending()
    assert repo.get(doc.id).status == STATUS_READY
