from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException, Request, status
from fastapi.responses import FileResponse

from app.api.auth import require_token, require_token_header_or_query
from app.api.errors import to_http
from app.api.schemas import DocumentOut
from app.domain.models import WorkspaceError

router = APIRouter(prefix="/documents")


@router.get("/{doc_id}", response_model=DocumentOut, dependencies=[Depends(require_token)])
def get_document(doc_id: str, request: Request) -> DocumentOut:
    service = request.app.state.workspace
    try:
        doc = service.get_document(doc_id)
    except WorkspaceError as exc:
        raise to_http(exc) from exc
    return DocumentOut.from_domain(doc, service.file_state(doc))


@router.get("/{doc_id}/file", dependencies=[Depends(require_token_header_or_query)])
def get_document_file(doc_id: str, request: Request) -> FileResponse:
    try:
        doc = request.app.state.workspace.get_document(doc_id)
    except WorkspaceError as exc:
        raise to_http(exc) from exc
    path = Path(doc.path)
    if not path.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, "Arquivo não encontrado no disco")
    return FileResponse(path, media_type="application/pdf")
