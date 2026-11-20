from typing import NoReturn

from fastapi import APIRouter, Depends, HTTPException, Path, Query, Request
from pydantic import BaseModel, Field

from app.api.auth import require_token
from app.api.schemas import DocumentOut, ProgressRequest
from app.domain.models import CanvasError
from app.services.canvas import CanvasService
from app.services.workspace import WorkspaceService

router = APIRouter(prefix="/canvas", dependencies=[Depends(require_token)])

_STATUS = {
    "not_connected": 409, "auth": 401, "forbidden": 403, "not_found": 404, "rate_limit": 429,
    "network": 502, "bad_response": 502, "not_pdf": 422, "too_large": 413, "bad_request": 400,
    "changed": 409, "storage": 500,
}


def to_http(exc: CanvasError) -> HTTPException:
    return HTTPException(_STATUS.get(exc.kind, 400), detail={"kind": exc.kind, "message": exc.message})


def _raise(exc: CanvasError) -> NoReturn:
    raise to_http(exc) from exc


def _svc(request: Request) -> CanvasService:
    return request.app.state.canvas


class ConnectRequest(BaseModel):
    base_url: str = Field(max_length=300)
    token: str = Field(max_length=500, repr=False)  # repr=False: o token nunca aparece em logs/erros

    def __repr__(self) -> str:  # pragma: no cover
        return f"ConnectRequest(base_url={self.base_url!r}, token=***)"


class PrefsRequest(BaseModel):
    auto_refresh: bool


class OpenRequest(BaseModel):
    course_id: int
    module_id: str = Field(max_length=40)
    file_id: int
    course_name: str = Field(default="", max_length=300)
    module_name: str = Field(default="", max_length=300)


@router.get("/status")
def status(request: Request) -> dict:
    return _svc(request).status()


@router.put("/connection")
def connect(body: ConnectRequest, request: Request) -> dict:
    try:
        return _svc(request).connect(body.base_url, body.token)
    except CanvasError as exc:
        _raise(exc)


@router.delete("/connection", status_code=204)
def disconnect(request: Request) -> None:
    try:
        _svc(request).disconnect()
    except CanvasError as exc:
        _raise(exc)


@router.put("/preferences")
def preferences(body: PrefsRequest, request: Request) -> dict:
    _svc(request).set_auto_refresh(body.auto_refresh)
    return _svc(request).status()


@router.get("/courses")
def courses(request: Request, refresh: bool = False) -> dict:
    try:
        return _svc(request).courses(refresh)
    except CanvasError as exc:
        _raise(exc)


@router.get("/courses/{course_id}/modules")
def modules(request: Request, course_id: int, refresh: bool = False) -> dict:
    try:
        return _svc(request).modules(course_id, refresh)
    except CanvasError as exc:
        _raise(exc)


@router.get("/courses/{course_id}/modules/{module_id}/items")
def items(request: Request, course_id: int, module_id: str = Path(pattern=r"^(\d+|files)$"),
          refresh: bool = False) -> dict:
    try:
        return _svc(request).items(course_id, module_id, refresh)
    except CanvasError as exc:
        _raise(exc)


@router.get("/history")
def history(request: Request) -> list[dict]:
    return _svc(request).history()


@router.put("/slots/{slot}", response_model=DocumentOut)
def open_in_slot(body: OpenRequest, request: Request, slot: str = Path(pattern="^[AB]$")) -> DocumentOut:
    try:
        doc = _svc(request).open_in_slot(slot, body.course_id, body.module_id, body.file_id,
                                         body.course_name, body.module_name)
    except CanvasError as exc:
        _raise(exc)
    workspace: WorkspaceService = request.app.state.workspace
    return DocumentOut.from_domain(doc, workspace.file_state(doc))


@router.post("/documents/{doc_id}/prepare")
def prepare(doc_id: str, request: Request) -> dict:
    try:
        return _svc(request).prepare(doc_id)
    except CanvasError as exc:
        _raise(exc)


@router.post("/documents/{doc_id}/refresh")
def refresh_document(doc_id: str, request: Request) -> dict:
    try:
        return _svc(request).refresh(doc_id)
    except CanvasError as exc:
        _raise(exc)


@router.put("/documents/{doc_id}/progress", status_code=204)
def progress(doc_id: str, body: ProgressRequest, request: Request) -> None:
    _svc(request).record_page(doc_id, body.page)
