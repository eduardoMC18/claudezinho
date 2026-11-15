"""Chat (resposta em fluxo via Server-Sent Events) e histórico de conversas."""

import json
from collections.abc import Iterator

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from app.api.auth import require_token
from app.domain.models import ChatError
from app.services.chat import ChatService, message_to_dict

router = APIRouter(dependencies=[Depends(require_token)])

# Erros que acontecem antes de qualquer coisa ser gravada: viram resposta HTTP comum.
_STATUS_BY_KIND = {"no_conversation": 404}


class ChatRequest(BaseModel):
    content: str
    conversation_id: str | None = None
    provider: str | None = None
    model: str | None = None


def _service(request: Request) -> ChatService:
    return request.app.state.chat


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


@router.post("/chat/stream")
def stream_chat(body: ChatRequest, request: Request) -> StreamingResponse:
    service = _service(request)
    try:
        prepared = service.prepare(body.content, body.conversation_id, body.provider, body.model)
    except ChatError as exc:
        raise HTTPException(_STATUS_BY_KIND.get(exc.kind, 400),
                            {"kind": exc.kind, "message": exc.message}) from exc

    def events() -> Iterator[str]:
        for event in service.run(prepared):
            yield _sse(event.type, event.data)

    return StreamingResponse(events(), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@router.post("/chat/messages/{message_id}/cancel", status_code=status.HTTP_204_NO_CONTENT)
def cancel_message(message_id: str, request: Request) -> None:
    _service(request).cancel(message_id)  # idempotente: se já terminou, não há o que cancelar


@router.get("/conversations/current")
def current_conversation(request: Request) -> dict:
    service = _service(request)
    conversation = service.current_conversation()
    return {
        "id": conversation.id, "title": conversation.title, "created_at": conversation.created_at,
        "messages": [message_to_dict(m) for m in service.messages(conversation.id)],
    }


@router.post("/conversations", status_code=status.HTTP_201_CREATED)
def new_conversation(request: Request) -> dict:
    conversation = _service(request).new_conversation()
    return {"id": conversation.id, "title": conversation.title, "created_at": conversation.created_at, "messages": []}


@router.get("/conversations")
def list_conversations(request: Request) -> list[dict]:
    items = request.app.state.conversations.list_all(1)
    return [{"id": c.id, "title": c.title, "created_at": c.created_at, "updated_at": c.updated_at,
             "message_count": n} for c, n in items]


@router.get("/conversations/{conversation_id}/messages")
def conversation_messages(conversation_id: str, request: Request) -> list[dict]:
    service = _service(request)
    if request.app.state.conversations.get(conversation_id) is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Conversa não encontrada")
    return [message_to_dict(m) for m in service.messages(conversation_id)]
