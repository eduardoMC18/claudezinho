from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

from app.api import ai_settings, chat, documents, exercises, health, workspace
from app.config import default_db_path
from app.infra.conversations_repo import ConversationRepository
from app.infra.db import Database
from app.infra.documents_repo import DocumentRepository
from app.infra.secrets import KeyringSecretStore, SecretStore
from app.infra.settings_repo import SettingsRepository
from app.providers.catalog import Catalog, load_catalog
from app.providers.registry import ProviderRegistry, default_registry
from app.services.ai_settings import AiSettingsService
from app.services.chat import ChatService
from app.services.chunking import CHUNKER_VERSION
from app.services.exercises import ExerciseService
from app.services.ingestion import Executor, IngestionService, spawn_thread
from app.services.retrieval import Bm25Retriever
from app.services.workspace import WorkspaceService

STATIC_DIR = Path(__file__).resolve().parent / "static"
KEY_CHUNKER_VERSION = "chunker_version"


def create_app(
    token: str,
    db_path: Path | None = None,
    executor: Executor = spawn_thread,
    secrets: SecretStore | None = None,
    registry: ProviderRegistry | None = None,
    catalog: Catalog | None = None,
) -> FastAPI:
    """Monta a aplicação. `executor` define como a ingestão roda (thread; inline nos testes)."""
    # Sem docs/openapi públicos: a API é de uso interno do próprio programa.
    app = FastAPI(
        title="estudoTron 3000 backend",
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.token = token

    db = Database(db_path or default_db_path())
    repo = DocumentRepository(db)
    settings = SettingsRepository(db)
    conversations = ConversationRepository(db)
    catalog = catalog or load_catalog()

    ingestion = IngestionService(repo, executor)
    workspace_service = WorkspaceService(repo, ingestion)
    ai = AiSettingsService(catalog, registry or default_registry(), settings, secrets or KeyringSecretStore())

    app.state.workspace = workspace_service
    app.state.ai_settings = ai
    app.state.conversations = conversations
    retriever = Bm25Retriever(repo)
    app.state.chat = ChatService(workspace_service, retriever, conversations, ai, catalog.chat)
    app.state.exercises = ExerciseService(workspace_service, repo, retriever, ai, catalog.chat)

    # Manutenção na abertura: respostas interrompidas, indexações pendentes e mudança de chunking.
    conversations.mark_streaming_as_cancelled()
    if settings.get(KEY_CHUNKER_VERSION) != str(CHUNKER_VERSION):
        ingestion.reindex_all()
        settings.set(KEY_CHUNKER_VERSION, str(CHUNKER_VERSION))
    else:
        ingestion.resume_pending()

    app.include_router(health.router)
    app.include_router(workspace.router)
    app.include_router(documents.router)
    app.include_router(ai_settings.router)
    app.include_router(chat.router)
    app.include_router(exercises.router)

    # Visualizador PDF.js (arquivos estáticos públicos, sem dados do usuário).
    app.mount("/viewer", StaticFiles(directory=STATIC_DIR / "viewer", html=True), name="viewer")
    return app
