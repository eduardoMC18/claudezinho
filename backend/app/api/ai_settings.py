"""Configurações de IA. A API NUNCA devolve chaves — só se existe uma salva (`has_key`)."""

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel

from app.api.auth import require_token
from app.infra.secrets import SecretStoreError
from app.services.ai_settings import AiSettingsService, SettingsError

router = APIRouter(prefix="/settings/ai", dependencies=[Depends(require_token)])


class PreferencesRequest(BaseModel):
    default_provider: str | None = None
    models: dict[str, str] | None = None


class KeyRequest(BaseModel):
    api_key: str

    def __repr__(self) -> str:  # nunca imprime a chave (logs, tracebacks)
        return "KeyRequest(api_key='***')"


class TestRequest(BaseModel):
    model: str | None = None
    api_key: str | None = None  # opcional: testa a chave digitada sem salvá-la

    def __repr__(self) -> str:
        return f"TestRequest(model={self.model!r}, api_key={'***' if self.api_key else None})"


def _service(request: Request) -> AiSettingsService:
    return request.app.state.ai_settings


def _http(exc: Exception) -> HTTPException:
    if isinstance(exc, SecretStoreError):
        return HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, {"kind": "secret_store", "message": str(exc)})
    if isinstance(exc, SettingsError):
        code = 404 if exc.kind.startswith("unknown") else 400
        return HTTPException(code, {"kind": exc.kind, "message": exc.message})
    raise exc


@router.get("")
def get_settings(request: Request) -> dict:
    return _service(request).overview()


@router.put("")
def put_preferences(body: PreferencesRequest, request: Request) -> dict:
    service = _service(request)
    try:
        service.set_preferences(body.default_provider, body.models)
    except (SettingsError, SecretStoreError) as exc:
        raise _http(exc) from exc
    return service.overview()


@router.put("/providers/{provider_id}/key", status_code=status.HTTP_204_NO_CONTENT)
def put_key(provider_id: str, body: KeyRequest, request: Request) -> None:
    try:
        _service(request).save_key(provider_id, body.api_key)
    except (SettingsError, SecretStoreError) as exc:
        raise _http(exc) from exc


@router.delete("/providers/{provider_id}/key", status_code=status.HTTP_204_NO_CONTENT)
def delete_key(provider_id: str, request: Request) -> None:
    try:
        _service(request).remove_key(provider_id)
    except (SettingsError, SecretStoreError) as exc:
        raise _http(exc) from exc


@router.post("/providers/{provider_id}/test")
def test_connection(provider_id: str, body: TestRequest, request: Request) -> dict:
    try:
        result = _service(request).test_connection(provider_id, body.model, body.api_key)
    except (SettingsError, SecretStoreError) as exc:
        raise _http(exc) from exc
    return {"ok": result.ok, "message": result.message, "latency_ms": result.latency_ms}
