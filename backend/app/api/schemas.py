from pydantic import BaseModel

from app.domain.models import Document


class DocumentOut(BaseModel):
    id: str
    slot: str
    name: str
    pages: int
    title: str | None = None
    status: str  # processing | ready | no_text | error
    pages_done: int
    error: str | None = None
    file_state: str  # ok | modified | missing

    @classmethod
    def from_domain(cls, doc: Document, file_state: str) -> "DocumentOut":
        return cls(
            id=doc.id,
            slot=doc.slot,
            name=doc.name,
            pages=doc.pages,
            title=doc.title,
            status=doc.status,
            pages_done=doc.pages_done,
            error=doc.error,
            file_state=file_state,
        )


class WorkspaceOut(BaseModel):
    slots: dict[str, DocumentOut | None]


class SetSlotRequest(BaseModel):
    path: str
