import json
from collections.abc import Callable, Iterator
from pathlib import Path

import pymupdf
import pytest
from fastapi.testclient import TestClient

from app.infra.secrets import MemorySecretStore
from app.main import create_app
from app.providers.anthropic_provider import AnthropicProvider
from app.providers.gemini_provider import GeminiProvider
from app.providers.registry import ProviderRegistry
from tests.fake_anthropic import FakeAnthropicServer
from tests.fake_gemini import FakeGeminiServer

TOKEN = "token-de-teste"
HEADERS = {"X-Estudotron-Token": TOKEN}
API_KEY = "sk-ant-api03-CHAVE-SECRETA-DE-TESTE-123456"


def inline(fn: Callable[[], None]) -> None:
    """Executor de teste: roda a ingestão na hora, sem thread."""
    fn()


@pytest.fixture(scope="session")
def fake_anthropic() -> Iterator[FakeAnthropicServer]:
    server = FakeAnthropicServer().start()
    yield server
    server.stop()


@pytest.fixture(scope="session")
def fake_gemini() -> Iterator[FakeGeminiServer]:
    server = FakeGeminiServer().start()
    yield server
    server.stop()


@pytest.fixture()
def secrets() -> MemorySecretStore:
    return MemorySecretStore()


@pytest.fixture()
def db_path(tmp_path: Path) -> Path:
    return tmp_path / "data" / "teste.db"


def build_app(db_path: Path, secrets: MemorySecretStore, fake: FakeAnthropicServer,
              fake_gemini: FakeGeminiServer | None = None):
    gemini_url = fake_gemini.base_url if fake_gemini else "http://127.0.0.1:9"  # sem servidor: não é usado
    registry = ProviderRegistry({
        "anthropic": AnthropicProvider(base_url=fake.base_url),
        "gemini": GeminiProvider(base_url=gemini_url),
    })
    return create_app(TOKEN, db_path=db_path, executor=inline, secrets=secrets, registry=registry)


@pytest.fixture()
def client(db_path: Path, secrets: MemorySecretStore, fake_anthropic: FakeAnthropicServer,
           fake_gemini: FakeGeminiServer) -> TestClient:
    fake_anthropic.requests.clear()
    fake_gemini.requests.clear()
    return TestClient(build_app(db_path, secrets, fake_anthropic, fake_gemini))


@pytest.fixture()
def make_pdf(tmp_path: Path) -> Callable[..., Path]:
    def _make(name: str = "exemplo.pdf", pages: int = 3, text: bool = True) -> Path:
        path = tmp_path / name
        doc = pymupdf.open()
        for i in range(pages):
            page = doc.new_page()
            if text:
                page.insert_text((72, 72), f"Pagina {i + 1} sobre fotossintese e clorofila", fontsize=12)
        doc.save(path)
        doc.close()
        return path

    return _make


@pytest.fixture()
def make_topic_pdf(tmp_path: Path) -> Callable[..., Path]:
    """PDF em que cada página fala de um assunto diferente (para testar a recuperação)."""
    def _make(name: str, topics: list[str]) -> Path:
        path = tmp_path / name
        doc = pymupdf.open()
        for topic in topics:
            page = doc.new_page()
            page.insert_textbox(pymupdf.Rect(60, 60, 540, 780), topic, fontsize=11)
        doc.save(path)
        doc.close()
        return path

    return _make


def parse_sse(raw: str) -> list[tuple[str, dict]]:
    events = []
    for block in raw.split("\n\n"):
        block = block.strip()
        if not block:
            continue
        name, data = "", ""
        for line in block.splitlines():
            if line.startswith("event:"):
                name = line[6:].strip()
            elif line.startswith("data:"):
                data += line[5:].strip()
        events.append((name, json.loads(data)))
    return events
