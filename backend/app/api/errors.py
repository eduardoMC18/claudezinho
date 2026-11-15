from fastapi import HTTPException, status

from app.domain.models import WorkspaceError

_STATUS_BY_KIND = {
    WorkspaceError.NOT_FOUND: status.HTTP_404_NOT_FOUND,
    WorkspaceError.BAD_REQUEST: status.HTTP_400_BAD_REQUEST,
    WorkspaceError.INVALID_PDF: 422,
}


def to_http(error: WorkspaceError) -> HTTPException:
    return HTTPException(_STATUS_BY_KIND.get(error.kind, 400), error.message)
