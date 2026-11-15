"""Contrato comum a todos os provedores de IA.

Para adicionar um provedor: implemente `AIProvider` em um módulo novo, registre-o em
`registry.py` e adicione-o ao `catalog.json`. Nada mais no programa precisa mudar.

Os provedores são SEM ESTADO quanto a chaves: a chave de API é recebida a cada chamada
(vinda do cofre do sistema) e nunca é guardada na instância.
"""

import threading
from abc import ABC, abstractmethod
from collections.abc import Iterator
from dataclasses import dataclass, field
from typing import Literal

Role = Literal["user", "assistant"]

# Categorias de erro tratadas pela interface
ERROR_AUTH = "auth"
ERROR_RATE_LIMIT = "rate_limit"
ERROR_NETWORK = "network"
ERROR_BAD_REQUEST = "bad_request"
ERROR_NOT_FOUND = "not_found"
ERROR_OVERLOADED = "overloaded"
ERROR_REFUSED = "refused"
ERROR_UNKNOWN = "unknown"


@dataclass(frozen=True)
class ProviderMessage:
    role: Role
    content: str


@dataclass(frozen=True)
class CompletionRequest:
    model: str
    system: str
    messages: list[ProviderMessage]
    max_tokens: int
    effort: str | None = None
    refusal_fallback: bool = False
    json_mode: bool = False  # pede saída em JSON (Gemini: response_mime_type); os demais seguem o prompt


@dataclass(frozen=True)
class Usage:
    input_tokens: int = 0
    output_tokens: int = 0


@dataclass(frozen=True)
class TextDelta:
    text: str


@dataclass(frozen=True)
class Finished:
    usage: Usage = field(default_factory=Usage)
    truncated: bool = False  # parou por limite de tokens de saída
    cancelled: bool = False


ProviderEvent = TextDelta | Finished


class ProviderError(Exception):
    """Erro de provedor já traduzido (sem dados sensíveis na mensagem)."""

    def __init__(self, kind: str, message: str) -> None:
        super().__init__(message)
        self.kind = kind
        self.message = message


@dataclass(frozen=True)
class ConnectionTestResult:
    ok: bool
    message: str
    latency_ms: int | None = None


class AIProvider(ABC):
    id: str

    @abstractmethod
    def stream(self, api_key: str, request: CompletionRequest,
               cancel: threading.Event) -> Iterator[ProviderEvent]:
        """Gera a resposta em pedaços. Deve parar quando `cancel` for acionado e
        levantar ProviderError (nunca exceções do SDK) em caso de falha."""

    @abstractmethod
    def test_connection(self, api_key: str, model: str) -> ConnectionTestResult:
        """Faz uma chamada mínima para validar chave, rede e modelo."""
