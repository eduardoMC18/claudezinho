"""Configuração da IA: provedor padrão, modelo por provedor, chaves (no cofre) e teste de conexão."""

import re
from dataclasses import dataclass

from app.domain.models import ChatError
from app.infra.secrets import SecretStore, api_key_secret_name
from app.infra.settings_repo import SettingsRepository
from app.providers.base import AIProvider, ConnectionTestResult
from app.providers.catalog import Catalog, ModelInfo, ProviderInfo
from app.providers.registry import ProviderRegistry

KEY_DEFAULT_PROVIDER = "ai.default_provider"
_KEY_MAX_LENGTH = 512
_KEY_ALLOWED = re.compile(r"^[A-Za-z0-9_\-\.]+$")


class SettingsError(Exception):
    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


@dataclass(frozen=True)
class ResolvedProvider:
    provider: AIProvider
    provider_info: ProviderInfo
    model: ModelInfo
    api_key: str  # só existe em memória durante a chamada; nunca é devolvido pela API


def _model_setting(provider_id: str) -> str:
    return f"ai.model.{provider_id}"


class AiSettingsService:
    def __init__(self, catalog: Catalog, registry: ProviderRegistry,
                 settings: SettingsRepository, secrets: SecretStore) -> None:
        self._catalog = catalog
        self._registry = registry
        self._settings = settings
        self._secrets = secrets

    # ----- leitura (nunca inclui chaves) -----

    def _providers(self) -> list[ProviderInfo]:
        return [p for p in self._catalog.providers if self._registry.get(p.id) is not None]

    def default_provider_id(self) -> str:
        stored = self._settings.get(KEY_DEFAULT_PROVIDER)
        ids = {p.id for p in self._providers()}
        if stored in ids:
            return stored
        return self._catalog.default_provider if self._catalog.default_provider in ids else next(iter(ids), "")

    def selected_model_id(self, provider: ProviderInfo) -> str:
        stored = self._settings.get(_model_setting(provider.id))
        return stored if stored and provider.model(stored) else provider.default_model

    def overview(self) -> dict:
        return {
            "default_provider": self.default_provider_id(),
            "providers": [
                {
                    "id": p.id,
                    "display_name": p.display_name,
                    "key_hint": p.key_hint,
                    "has_key": self._has_key(p.id),
                    "selected_model": self.selected_model_id(p),
                    "models": [
                        {
                            "id": m.id, "label": m.label, "description": m.description,
                            "context_window": m.context_window,
                            "price_input_per_mtok": m.price_input_per_mtok,
                            "price_output_per_mtok": m.price_output_per_mtok,
                        }
                        for m in p.models
                    ],
                }
                for p in self._providers()
            ],
        }

    def _has_key(self, provider_id: str) -> bool:
        try:
            return self._secrets.has(api_key_secret_name(provider_id))
        except Exception:
            return False

    # ----- escrita -----

    def set_preferences(self, default_provider: str | None, models: dict[str, str] | None) -> None:
        if default_provider is not None:
            if default_provider not in {p.id for p in self._providers()}:
                raise SettingsError("unknown_provider", "Provedor desconhecido.")
            self._settings.set(KEY_DEFAULT_PROVIDER, default_provider)
        for provider_id, model_id in (models or {}).items():
            provider = self._catalog.provider(provider_id)
            if provider is None or provider.model(model_id) is None:
                raise SettingsError("unknown_model", f"Modelo '{model_id}' não existe para '{provider_id}'.")
            self._settings.set(_model_setting(provider_id), model_id)

    def save_key(self, provider_id: str, api_key: str) -> None:
        self._require_provider(provider_id)
        key = self._validate_key(api_key)
        self._secrets.set(api_key_secret_name(provider_id), key)

    def remove_key(self, provider_id: str) -> None:
        self._require_provider(provider_id)
        self._secrets.delete(api_key_secret_name(provider_id))

    def test_connection(self, provider_id: str, model_id: str | None, api_key: str | None) -> ConnectionTestResult:
        """Testa com a chave digitada (se vier) ou com a salva. Nada é gravado."""
        provider_info = self._require_provider(provider_id)
        provider = self._registry.get(provider_id)
        key = self._validate_key(api_key) if api_key else self._secrets.get(api_key_secret_name(provider_id))
        if not key:
            return ConnectionTestResult(ok=False, message="Nenhuma chave informada nem salva para este provedor.")
        model = model_id or self.selected_model_id(provider_info)
        if provider_info.model(model) is None:
            raise SettingsError("unknown_model", f"Modelo '{model}' não existe para '{provider_id}'.")
        return provider.test_connection(key, model)

    # ----- uso pelo chat -----

    def resolve(self, provider_id: str | None, model_id: str | None) -> ResolvedProvider:
        provider_id = provider_id or self.default_provider_id()
        provider_info = self._catalog.provider(provider_id) if provider_id else None
        provider = self._registry.get(provider_id) if provider_id else None
        if provider_info is None or provider is None:
            raise ChatError("no_provider", "Nenhum provedor de IA disponível.")
        model = provider_info.model(model_id or self.selected_model_id(provider_info))
        if model is None:
            raise ChatError("bad_model", "O modelo escolhido não existe no catálogo.")
        key = self._secrets.get(api_key_secret_name(provider_id))
        if not key:
            raise ChatError("no_key", f"Nenhuma chave de API configurada para {provider_info.display_name}. "
                                      "Abra Configurações e informe a chave.")
        return ResolvedProvider(provider, provider_info, model, key)

    # ----- auxiliares -----

    def _require_provider(self, provider_id: str) -> ProviderInfo:
        info = self._catalog.provider(provider_id)
        if info is None or self._registry.get(provider_id) is None:
            raise SettingsError("unknown_provider", "Provedor desconhecido.")
        return info

    @staticmethod
    def _validate_key(api_key: str) -> str:
        key = (api_key or "").strip()
        if not key:
            raise SettingsError("invalid_key", "A chave está vazia.")
        if len(key) > _KEY_MAX_LENGTH or not _KEY_ALLOWED.match(key):
            raise SettingsError("invalid_key", "A chave tem caracteres inválidos. Cole só a chave, sem espaços ou aspas.")
        return key
