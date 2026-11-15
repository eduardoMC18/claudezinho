"""Provedor Google Gemini (SDK oficial `google-genai`)."""

import os
import threading
import time
from collections.abc import Iterator

import httpx
from google import genai
from google.genai import errors as genai_errors
from google.genai import types

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

TIMEOUT_MS = 120_000
# Picos de demanda do Gemini devolvem 503/500 de forma intermitente: tenta de novo com espera crescente
# (1 s, 2 s, 4 s) antes de desistir. 429 (cota) NÃO é repetido: esperar não resolve.
RETRY = types.HttpRetryOptions(attempts=4, initial_delay=1.0, max_delay=8.0, exp_base=2.0,
                               http_status_codes=[500, 502, 503, 504])
# Motivos de parada que significam "o filtro de segurança bloqueou a resposta".
_REFUSAL_REASONS = {"SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY"}


def _translate(error: genai_errors.APIError) -> ProviderError:
    """Converte exceções do SDK em ProviderError (mensagens sem dados sensíveis)."""
    code = getattr(error, "code", None) or 0
    message = str(getattr(error, "message", "") or "")
    lowered = message.lower()
    if "api key not valid" in lowered or "api_key_invalid" in lowered or code in (401, 403):
        return ProviderError(ERROR_AUTH, "Chave da API inválida, revogada ou sem permissão para este modelo.")
    if code == 404:
        return ProviderError(ERROR_NOT_FOUND, "Modelo não encontrado ou indisponível para esta chave.")
    if code == 429:
        return ProviderError(ERROR_RATE_LIMIT, "Limite de requisições ou cota atingido. Aguarde um pouco e tente de novo.")
    if code >= 500:
        detail = f" Resposta do Google: {message}" if message else ""
        return ProviderError(ERROR_OVERLOADED, f"O Gemini está sobrecarregado ou indisponível no momento (erro {code}); "
                                              "o app já tentou 4 vezes. Tente de novo em instantes ou troque o modelo "
                                              f"em Configurações (ex.: Gemini 3.5 Flash-Lite).{detail}")
    if code == 400:
        return ProviderError(ERROR_BAD_REQUEST, f"A API recusou o pedido: {message}")
    return ProviderError(ERROR_UNKNOWN, f"Erro da API ({code}): {message}")


def _network_error(exc: Exception) -> ProviderError | None:
    if isinstance(exc, httpx.TimeoutException):
        return ProviderError(ERROR_NETWORK, "A conexão com o Gemini expirou. Verifique a internet e tente de novo.")
    if isinstance(exc, httpx.TransportError):
        return ProviderError(ERROR_NETWORK, "Não foi possível conectar ao Gemini. Verifique a internet.")
    return None


def _text_of(chunk: types.GenerateContentResponse) -> str:
    """Texto visível do pedaço (ignora o raciocínio interno, marcado como 'thought')."""
    parts: list[str] = []
    for candidate in chunk.candidates or []:
        for part in (candidate.content.parts if candidate.content and candidate.content.parts else []):
            if part.text and not part.thought:
                parts.append(part.text)
    return "".join(parts)


class GeminiProvider(AIProvider):
    id = "gemini"

    def __init__(self, base_url: str | None = None) -> None:
        # base_url só é usado em testes (servidor falso); em produção fica o endereço oficial.
        self._base_url = base_url or os.environ.get("GOOGLE_GEMINI_BASE_URL")

    def _client(self, api_key: str) -> genai.Client:
        options = types.HttpOptions(base_url=self._base_url, timeout=TIMEOUT_MS, retry_options=RETRY) \
            if self._base_url else types.HttpOptions(timeout=TIMEOUT_MS, retry_options=RETRY)
        return genai.Client(api_key=api_key, http_options=options)

    def stream(self, api_key: str, request: CompletionRequest,
               cancel: threading.Event) -> Iterator[ProviderEvent]:
        client = self._client(api_key)
        contents = [
            types.Content(role="model" if m.role == "assistant" else "user", parts=[types.Part(text=m.content)])
            for m in request.messages
        ]
        config = types.GenerateContentConfig(system_instruction=request.system, max_output_tokens=request.max_tokens,
                                             response_mime_type="application/json" if request.json_mode else None)

        finish_reason: str | None = None
        blocked = False
        usage = Usage()
        stream = None
        try:
            stream = client.models.generate_content_stream(model=request.model, contents=contents, config=config)
            for chunk in stream:
                if cancel.is_set():
                    break
                text = _text_of(chunk)
                if text:
                    yield TextDelta(text)
                if chunk.prompt_feedback and chunk.prompt_feedback.block_reason:
                    blocked = True
                for candidate in chunk.candidates or []:
                    if candidate.finish_reason:
                        finish_reason = getattr(candidate.finish_reason, "name", str(candidate.finish_reason))
                meta = chunk.usage_metadata
                if meta:
                    usage = Usage(
                        input_tokens=meta.prompt_token_count or 0,
                        # a cobrança de saída inclui o raciocínio interno (thinking)
                        output_tokens=(meta.candidates_token_count or 0) + (meta.thoughts_token_count or 0),
                    )
        except genai_errors.APIError as exc:
            raise _translate(exc) from None
        except Exception as exc:  # erros de rede do httpx, etc.
            translated = _network_error(exc)
            if translated is None:
                raise ProviderError(ERROR_UNKNOWN, "Erro inesperado ao falar com o Gemini.") from None
            raise translated from None
        finally:
            close = getattr(stream, "close", None)
            if callable(close):
                try:
                    close()
                except Exception:
                    pass

        if cancel.is_set():
            yield Finished(cancelled=True)
            return
        if blocked or finish_reason in _REFUSAL_REASONS:
            raise ProviderError(ERROR_REFUSED, "O modelo bloqueou a resposta (filtros de segurança do Gemini).")
        yield Finished(usage=usage, truncated=finish_reason == "MAX_TOKENS")

    def test_connection(self, api_key: str, model: str) -> ConnectionTestResult:
        client = self._client(api_key)
        started = time.monotonic()
        try:
            client.models.generate_content(
                model=model,
                contents="Responda apenas: ok",
                config=types.GenerateContentConfig(max_output_tokens=512),  # folga para o raciocínio interno
            )
        except genai_errors.APIError as exc:
            return ConnectionTestResult(ok=False, message=_translate(exc).message)
        except Exception as exc:
            translated = _network_error(exc)
            return ConnectionTestResult(ok=False, message=(translated.message if translated
                                                           else "Erro inesperado ao falar com o Gemini."))
        elapsed = int((time.monotonic() - started) * 1000)
        return ConnectionTestResult(ok=True, message=f"Conexão estabelecida com o modelo {model}.", latency_ms=elapsed)
