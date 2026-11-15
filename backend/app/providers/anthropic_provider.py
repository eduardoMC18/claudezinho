"""Provedor Anthropic Claude (SDK oficial `anthropic`)."""

import os
import threading
import time
from collections.abc import Iterator

import anthropic

from app.providers.base import (
    ERROR_AUTH,
    ERROR_BAD_REQUEST,
    ERROR_NETWORK,
    ERROR_NOT_FOUND,
    ERROR_OVERLOADED,
    ERROR_RATE_LIMIT,
    ERROR_REFUSED,
    ERROR_UNKNOWN,
    AIProvider,
    CompletionRequest,
    ConnectionTestResult,
    Finished,
    ProviderError,
    ProviderEvent,
    TextDelta,
    Usage,
)

# Fallback do lado do servidor: se os classificadores de segurança recusarem o pedido,
# a API repete a chamada em um modelo reserva dentro da mesma requisição.
FALLBACK_BETA = "server-side-fallback-2026-07-01"


def _translate(error: anthropic.APIError) -> ProviderError:
    """Converte exceções do SDK em ProviderError (mensagens sem dados sensíveis)."""
    if isinstance(error, anthropic.AuthenticationError):
        return ProviderError(ERROR_AUTH, "Chave da API inválida ou revogada.")
    if isinstance(error, anthropic.PermissionDeniedError):
        return ProviderError(ERROR_AUTH, "A chave não tem permissão para usar este modelo.")
    if isinstance(error, anthropic.NotFoundError):
        return ProviderError(ERROR_NOT_FOUND, "Modelo não encontrado ou indisponível para esta chave.")
    if isinstance(error, anthropic.RateLimitError):
        return ProviderError(ERROR_RATE_LIMIT, "Limite de requisições atingido. Aguarde um pouco e tente de novo.")
    if isinstance(error, anthropic.BadRequestError):
        return ProviderError(ERROR_BAD_REQUEST, f"A API recusou o pedido: {error.message}")
    if isinstance(error, anthropic.APITimeoutError):
        return ProviderError(ERROR_NETWORK, "A conexão com a Anthropic expirou. Verifique a internet e tente de novo.")
    if isinstance(error, anthropic.APIConnectionError):
        return ProviderError(ERROR_NETWORK, "Não foi possível conectar à Anthropic. Verifique a internet.")
    if isinstance(error, anthropic.APIStatusError):
        if error.status_code >= 500:
            return ProviderError(ERROR_OVERLOADED, "O serviço da Anthropic está indisponível ou sobrecarregado. Tente de novo em instantes.")
        return ProviderError(ERROR_UNKNOWN, f"Erro da API ({error.status_code}): {error.message}")
    return ProviderError(ERROR_UNKNOWN, "Erro inesperado ao falar com a Anthropic.")


class AnthropicProvider(AIProvider):
    id = "anthropic"

    def __init__(self, base_url: str | None = None) -> None:
        # base_url só é usado em testes (servidor falso); em produção fica o endereço oficial.
        self._base_url = base_url or os.environ.get("ANTHROPIC_BASE_URL")

    def _client(self, api_key: str) -> anthropic.Anthropic:
        return anthropic.Anthropic(
            api_key=api_key,
            base_url=self._base_url,
            max_retries=2,
            timeout=anthropic.Timeout(120.0, connect=10.0),
        )

    def stream(self, api_key: str, request: CompletionRequest,
               cancel: threading.Event) -> Iterator[ProviderEvent]:
        client = self._client(api_key)
        params: dict = {
            "model": request.model,
            "max_tokens": request.max_tokens,
            "system": request.system,
            "messages": [{"role": m.role, "content": m.content} for m in request.messages],
        }
        if request.effort:
            params["output_config"] = {"effort": request.effort}

        try:
            if request.refusal_fallback:
                manager = client.beta.messages.stream(betas=[FALLBACK_BETA], fallbacks="default", **params)
            else:
                manager = client.messages.stream(**params)
            with manager as stream:
                for text in stream.text_stream:
                    if cancel.is_set():
                        yield Finished(cancelled=True)
                        return
                    yield TextDelta(text)
                if cancel.is_set():
                    yield Finished(cancelled=True)
                    return
                final = stream.get_final_message()
        except anthropic.APIError as exc:
            raise _translate(exc) from None

        if final.stop_reason == "refusal":
            raise ProviderError(ERROR_REFUSED, "O modelo recusou responder a este pedido (filtros de segurança).")
        yield Finished(
            usage=Usage(input_tokens=final.usage.input_tokens, output_tokens=final.usage.output_tokens),
            truncated=final.stop_reason == "max_tokens",
        )

    def test_connection(self, api_key: str, model: str) -> ConnectionTestResult:
        client = self._client(api_key)
        started = time.monotonic()
        try:
            client.messages.create(
                model=model,
                max_tokens=1024,  # com o raciocínio sempre ligado, precisa de folga além da palavra "ok"
                output_config={"effort": "low"},
                messages=[{"role": "user", "content": "Responda apenas: ok"}],
            )
        except anthropic.APIError as exc:
            error = _translate(exc)
            return ConnectionTestResult(ok=False, message=error.message)
        elapsed = int((time.monotonic() - started) * 1000)
        return ConnectionTestResult(ok=True, message=f"Conexão estabelecida com o modelo {model}.", latency_ms=elapsed)
