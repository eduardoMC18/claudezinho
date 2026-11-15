"""Exercícios gerados pela IA: gerar questão e enviar resposta (objetiva ou discursiva)."""

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from app.api.auth import require_token
from app.domain.models import ChatError
from app.providers.base import ProviderError
from app.services.exercises import ExerciseService, public_exercise

router = APIRouter(prefix="/exercises", dependencies=[Depends(require_token)])

_STATUS_BY_KIND = {"not_found": 404, "already_answered": 409}
# Falhas do serviço de IA (e respostas fora do formato) viram 502: o problema não é do pedido.
_UPSTREAM_KINDS = {"invalid_response"}


class GenerateRequest(BaseModel):
    kind: str  # objective | subjective


class AnswerRequest(BaseModel):
    option: str | None = None   # objetiva: id da alternativa ("a".."d")
    text: str | None = None     # discursiva: resposta escrita


def _service(request: Request) -> ExerciseService:
    return request.app.state.exercises


def _http(kind: str, message: str) -> HTTPException:
    code = 502 if kind in _UPSTREAM_KINDS else _STATUS_BY_KIND.get(kind, 400)
    return HTTPException(code, {"kind": kind, "message": message})


@router.post("/generate")
def generate(body: GenerateRequest, request: Request) -> dict:
    try:
        exercise, references = _service(request).generate(body.kind)
    except ChatError as exc:
        raise _http(exc.kind, exc.message) from exc
    except ProviderError as exc:
        raise HTTPException(502, {"kind": exc.kind, "message": exc.message}) from exc
    return public_exercise(exercise)


@router.post("/{exercise_id}/answer")
def answer(exercise_id: str, body: AnswerRequest, request: Request) -> dict:
    try:
        return _service(request).answer(exercise_id, body.option, body.text)
    except ChatError as exc:
        raise _http(exc.kind, exc.message) from exc
    except ProviderError as exc:
        raise HTTPException(502, {"kind": exc.kind, "message": exc.message}) from exc
