"""Catálogo de provedores e modelos (carregado de catalog.json).

Nenhum outro módulo contém ids de modelo ou preços: tudo vem daqui.
"""

import json
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from app.config import data_dir

BUNDLED_CATALOG = Path(__file__).resolve().parent / "catalog.json"
USER_CATALOG_NAME = "catalog.json"


@dataclass(frozen=True)
class ModelInfo:
    id: str
    label: str
    description: str
    context_window: int
    max_output_tokens: int
    effort: str | None
    price_input_per_mtok: float | None
    price_output_per_mtok: float | None
    refusal_fallback: bool

    def cost_usd(self, input_tokens: int, output_tokens: int) -> float | None:
        if self.price_input_per_mtok is None or self.price_output_per_mtok is None:
            return None
        return (input_tokens * self.price_input_per_mtok + output_tokens * self.price_output_per_mtok) / 1_000_000


@dataclass(frozen=True)
class ProviderInfo:
    id: str
    display_name: str
    key_hint: str
    default_model: str
    models: tuple[ModelInfo, ...]

    def model(self, model_id: str) -> ModelInfo | None:
        return next((m for m in self.models if m.id == model_id), None)


@dataclass(frozen=True)
class ChatTuning:
    context_token_budget: int
    history_token_budget: int
    chunks_per_document: int
    max_question_chars: int


@dataclass(frozen=True)
class Catalog:
    default_provider: str
    chat: ChatTuning
    providers: tuple[ProviderInfo, ...]

    def provider(self, provider_id: str) -> ProviderInfo | None:
        return next((p for p in self.providers if p.id == provider_id), None)


def _parse_model(raw: dict) -> ModelInfo:
    return ModelInfo(
        id=raw["id"],
        label=raw.get("label", raw["id"]),
        description=raw.get("description", ""),
        context_window=int(raw.get("context_window", 200_000)),
        max_output_tokens=int(raw.get("max_output_tokens", 4096)),
        effort=raw.get("effort"),
        price_input_per_mtok=raw.get("price_input_per_mtok"),
        price_output_per_mtok=raw.get("price_output_per_mtok"),
        refusal_fallback=bool(raw.get("refusal_fallback", False)),
    )


def parse_catalog(raw: dict) -> Catalog:
    chat = raw.get("chat", {})
    providers = []
    for provider_id, p in raw["providers"].items():
        models = tuple(_parse_model(m) for m in p["models"])
        default_model = p.get("default_model") or models[0].id
        if default_model not in {m.id for m in models}:
            raise ValueError(f"default_model '{default_model}' não existe em {provider_id}")
        providers.append(ProviderInfo(
            id=provider_id,
            display_name=p.get("display_name", provider_id),
            key_hint=p.get("key_hint", ""),
            default_model=default_model,
            models=models,
        ))
    return Catalog(
        default_provider=raw.get("default_provider", providers[0].id),
        chat=ChatTuning(
            context_token_budget=int(chat.get("context_token_budget", 6000)),
            history_token_budget=int(chat.get("history_token_budget", 3000)),
            chunks_per_document=int(chat.get("chunks_per_document", 8)),
            max_question_chars=int(chat.get("max_question_chars", 8000)),
        ),
        providers=tuple(providers),
    )


@lru_cache(maxsize=1)
def load_catalog() -> Catalog:
    """Catálogo do usuário (pasta de dados) tem prioridade sobre o que acompanha o programa."""
    user_file = data_dir() / USER_CATALOG_NAME
    path = user_file if user_file.is_file() else BUNDLED_CATALOG
    return parse_catalog(json.loads(path.read_text(encoding="utf-8")))
