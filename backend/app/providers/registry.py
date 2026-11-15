from app.providers.anthropic_provider import AnthropicProvider
from app.providers.base import AIProvider
from app.providers.gemini_provider import GeminiProvider


class ProviderRegistry:
    """Provedores disponíveis. Para adicionar um novo, registre-o em `default_registry`."""

    def __init__(self, providers: dict[str, AIProvider]) -> None:
        self._providers = dict(providers)

    def get(self, provider_id: str) -> AIProvider | None:
        return self._providers.get(provider_id)

    def ids(self) -> list[str]:
        return list(self._providers)


def default_registry() -> ProviderRegistry:
    return ProviderRegistry({
        "anthropic": AnthropicProvider(),
        "gemini": GeminiProvider(),
    })
