"""Orquestra uma pergunta ao chat:

pergunta → documentos carregados → busca de trechos → contexto (com orçamento) → provedor
→ resposta em fluxo, com citações validadas → histórico no SQLite.

Dois passos: `prepare` (valida tudo; se falhar, nada é gravado) e `run` (grava e transmite).
"""

import logging
import threading
import uuid
from collections.abc import Iterator
from dataclasses import dataclass

from app.domain.models import DEFAULT_WORKSPACE_ID, STATUS_NO_TEXT, STATUS_PROCESSING, ChatError, Document
from app.infra.conversations_repo import ConversationRepository, StoredConversation, StoredMessage, now_iso
from app.providers.base import (
    ERROR_UNKNOWN,
    CompletionRequest,
    Finished,
    ProviderError,
    ProviderMessage,
    TextDelta,
)
from app.providers.catalog import ChatTuning
from app.services.ai_settings import AiSettingsService, ResolvedProvider
from app.services.citations import CitationRewriter, allowed_citations, to_history_text
from app.services.context import ContextResult, build_context, estimate_tokens
from app.services.prompts import SYSTEM_PROMPT, build_user_turn
from app.services.retrieval import Retriever
from app.services.workspace import WorkspaceService

log = logging.getLogger(__name__)

TITLE_MAX = 60
PREVIOUS_QUESTIONS_FOR_RETRIEVAL = 1


@dataclass(frozen=True)
class ChatEvent:
    type: str  # meta | delta | done | error
    data: dict


@dataclass
class PreparedChat:
    conversation: StoredConversation
    content: str
    resolved: ResolvedProvider
    request: CompletionRequest
    context: ContextResult


def message_to_dict(m: StoredMessage) -> dict:
    return {
        "id": m.id, "conversation_id": m.conversation_id, "role": m.role, "content": m.content,
        "provider": m.provider, "model": m.model, "status": m.status, "error": m.error,
        "error_kind": m.error_kind, "references": m.references, "usage": m.usage, "created_at": m.created_at,
    }


class ChatService:
    def __init__(self, workspace: WorkspaceService, retriever: Retriever, conversations: ConversationRepository,
                 ai_settings: AiSettingsService, tuning: ChatTuning) -> None:
        self._workspace = workspace
        self._retriever = retriever
        self._conversations = conversations
        self._ai = ai_settings
        self._tuning = tuning
        self._active: dict[str, threading.Event] = {}
        self._lock = threading.Lock()

    # ----- conversas -----

    def current_conversation(self) -> StoredConversation:
        return (self._conversations.latest(DEFAULT_WORKSPACE_ID)
                or self._conversations.create(DEFAULT_WORKSPACE_ID))

    def new_conversation(self) -> StoredConversation:
        return self._conversations.create(DEFAULT_WORKSPACE_ID)

    def messages(self, conversation_id: str) -> list[StoredMessage]:
        return self._conversations.messages(conversation_id)

    def cancel(self, message_id: str) -> bool:
        with self._lock:
            event = self._active.get(message_id)
        if event is None:
            return False
        event.set()
        return True

    # ----- passo 1: validar e montar contexto (nada é gravado) -----

    def prepare(self, content: str, conversation_id: str | None, provider_id: str | None,
                model_id: str | None) -> PreparedChat:
        question = (content or "").strip()
        if not question:
            raise ChatError("empty", "Digite uma pergunta.")
        if len(question) > self._tuning.max_question_chars:
            raise ChatError("too_long", f"A pergunta é longa demais (máximo {self._tuning.max_question_chars} caracteres).")

        documents = self._loaded_documents()
        resolved = self._ai.resolve(provider_id, model_id)

        conversation = self._conversations.get(conversation_id) if conversation_id else self.current_conversation()
        if conversation is None:
            raise ChatError("no_conversation", "Conversa não encontrada.")

        history_messages = self._conversations.messages(conversation.id)
        previous_questions = [m.content for m in history_messages
                              if m.role == "user"][-PREVIOUS_QUESTIONS_FOR_RETRIEVAL:]

        retrieved = self._retriever.retrieve(question, documents, self._tuning.chunks_per_document, previous_questions)
        context = build_context(retrieved, documents, self._tuning.context_token_budget)

        messages = self._history_for_model(history_messages)
        messages.append(ProviderMessage("user", build_user_turn(context.text, question)))
        request = CompletionRequest(
            model=resolved.model.id,
            system=SYSTEM_PROMPT,
            messages=messages,
            max_tokens=resolved.model.max_output_tokens,
            effort=resolved.model.effort,
            refusal_fallback=resolved.model.refusal_fallback,
        )
        return PreparedChat(conversation, question, resolved, request, context)

    def _loaded_documents(self) -> list[Document]:
        documents = [d for d in self._workspace.get_slots().values() if d is not None]
        if not documents:
            raise ChatError("no_documents", "Carregue pelo menos um PDF para conversar sobre ele.")
        for doc in documents:
            if doc.status == STATUS_PROCESSING:
                raise ChatError("indexing", f"“{doc.name}” ainda está sendo indexado ({doc.pages_done}/{doc.pages} "
                                            "páginas). Aguarde terminar.")
        if all(d.status == STATUS_NO_TEXT for d in documents):
            raise ChatError("no_text", "Os documentos carregados não têm texto pesquisável (parecem escaneados), "
                                       "então a IA não consegue lê-los.")
        return documents

    def _history_for_model(self, stored: list[StoredMessage]) -> list[ProviderMessage]:
        """Turnos anteriores completos, dos mais recentes para trás, até o orçamento de tokens."""
        usable = [m for m in stored if m.status == "complete" and m.content.strip()]
        picked: list[ProviderMessage] = []
        used = 0
        for m in reversed(usable):
            text = to_history_text(m.content)
            cost = estimate_tokens(text)
            if used + cost > self._tuning.history_token_budget:
                break
            picked.append(ProviderMessage(m.role, text))
            used += cost
        picked.reverse()
        while picked and picked[0].role != "user":
            picked.pop(0)  # o histórico deve começar por uma fala do usuário
        return picked

    # ----- passo 2: gravar e transmitir -----

    def run(self, prepared: PreparedChat) -> Iterator[ChatEvent]:
        conversation, resolved = prepared.conversation, prepared.resolved
        user_message = StoredMessage(uuid.uuid4().hex, conversation.id, "user", prepared.content, now_iso())
        assistant = StoredMessage(uuid.uuid4().hex, conversation.id, "assistant", "", now_iso(),
                                  provider=resolved.provider_info.id, model=resolved.model.id, status="streaming")
        self._conversations.add_message(user_message)
        self._conversations.add_message(assistant)
        self._conversations.set_title_if_empty(conversation.id, prepared.content[:TITLE_MAX])

        cancel = threading.Event()
        with self._lock:
            self._active[assistant.id] = cancel

        rewriter = CitationRewriter(allowed_citations(prepared.context.chunks))
        parts: list[str] = []
        finished: Finished | None = None
        failure: ProviderError | None = None
        saved = False

        yield ChatEvent("meta", {
            "conversation_id": conversation.id,
            "user_message": message_to_dict(user_message),
            "assistant_message_id": assistant.id,
            "retrieved_chunks": len(prepared.context.chunks),
            "dropped_chunks": prepared.context.dropped,
        })
        try:
            for event in resolved.provider.stream(resolved.api_key, prepared.request, cancel):
                if isinstance(event, TextDelta):
                    out = rewriter.feed(event.text)
                    if out:
                        parts.append(out)
                        yield ChatEvent("delta", {"text": out})
                elif isinstance(event, Finished):
                    finished = event
            tail = rewriter.finish()
            if tail:
                parts.append(tail)
                yield ChatEvent("delta", {"text": tail})
        except ProviderError as exc:
            failure = exc
        except GeneratorExit:
            # A interface fechou a conexão (parar/fechar o app): guarda o que já chegou.
            self._finalize(assistant, parts, rewriter, resolved, None, None, cancelled=True)
            saved = True
            raise
        except Exception:  # erro inesperado: registra sem dados sensíveis e informa a interface
            log.exception("Erro inesperado no chat")
            failure = ProviderError(ERROR_UNKNOWN, "Erro inesperado ao gerar a resposta.")
        finally:
            with self._lock:
                self._active.pop(assistant.id, None)
            if not saved and failure is None and finished is None:
                self._finalize(assistant, parts, rewriter, resolved, None, None, cancelled=True)
                saved = True

        if not saved:
            cancelled = bool(finished and finished.cancelled) or cancel.is_set()
            self._finalize(assistant, parts, rewriter, resolved, finished, failure, cancelled=cancelled and failure is None)
        if failure is not None:
            yield ChatEvent("error", {"kind": failure.kind, "message": failure.message,
                                      "assistant_message": message_to_dict(assistant)})
        else:
            yield ChatEvent("done", {"message": message_to_dict(assistant)})

    def _finalize(self, assistant: StoredMessage, parts: list[str], rewriter: CitationRewriter,
                  resolved: ResolvedProvider, finished: Finished | None, failure: ProviderError | None,
                  cancelled: bool) -> None:
        assistant.content = "".join(parts)
        assistant.references = [c.to_dict() for c in rewriter.citations]
        if failure is not None:
            assistant.status, assistant.error, assistant.error_kind = "error", failure.message, failure.kind
        else:
            assistant.status = "cancelled" if cancelled else "complete"
        usage: dict = {}
        if finished is not None and not finished.cancelled:   # interrompida: o provedor não informa o uso
            usage = {
                "input_tokens": finished.usage.input_tokens,
                "output_tokens": finished.usage.output_tokens,
                "cost_usd": resolved.model.cost_usd(finished.usage.input_tokens, finished.usage.output_tokens),
            }
            if finished.truncated:
                usage["truncated"] = True
        if rewriter.invalid_count:
            usage["invalid_citations_removed"] = rewriter.invalid_count
        assistant.usage = usage or None
        self._conversations.update_message(assistant)
