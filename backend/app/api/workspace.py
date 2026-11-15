from fastapi import APIRouter, Depends, Path, Request, status

from app.api.auth import require_token
from app.api.errors import to_http
from app.api.schemas import DocumentOut, SetSlotRequest, WorkspaceOut
from app.domain.models import WorkspaceError
from app.services.workspace import WorkspaceService

router = APIRouter(prefix="/workspace", dependencies=[Depends(require_token)])

SlotParam = Path(pattern="^[AB]$", description="A (esquerda) ou B (direita)")


def _service(request: Request) -> WorkspaceService:
    return request.app.state.workspace


@router.get("", response_model=WorkspaceOut)
def get_workspace(request: Request) -> WorkspaceOut:
    service = _service(request)
    return WorkspaceOut(
        slots={
            slot: DocumentOut.from_domain(doc, service.file_state(doc)) if doc else None
            for slot, doc in service.get_slots().items()
        }
    )


@router.put("/slots/{slot}", response_model=DocumentOut)
def set_slot(body: SetSlotRequest, request: Request, slot: str = SlotParam) -> DocumentOut:
    service = _service(request)
    try:
        doc = service.set_slot(slot, body.path)
    except WorkspaceError as exc:
        raise to_http(exc) from exc
    return DocumentOut.from_domain(doc, service.file_state(doc))


@router.delete("/slots/{slot}", status_code=status.HTTP_204_NO_CONTENT)
def clear_slot(request: Request, slot: str = SlotParam) -> None:
    try:
        _service(request).clear_slot(slot)
    except WorkspaceError as exc:
        raise to_http(exc) from exc
