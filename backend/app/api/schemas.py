import json

from pydantic import BaseModel

from app.domain.models import Document


_PUBLIC_SOURCE_KEYS = ("institution", "course_id", "course_name", "course_code", "module_id", "module_name",
                       "file_id", "display_name", "remote_modified_at")


def _canvas_info(doc: Document) -> dict | None:
    if doc.source != "canvas" or not doc.source_json:
        return None
    try:
        data = json.loads(doc.source_json)
    except ValueError:
        return None
    return {k: data.get(k) for k in _PUBLIC_SOURCE_KEYS}


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
    source: str = "local"  # local | canvas
    canvas: dict | None = None  # origem (instituição, disciplina, módulo, arquivo) quando source == canvas
    last_page: int = 1

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
            source=doc.source,
            canvas=_canvas_info(doc),
            last_page=doc.last_page,
        )


class WorkspaceOut(BaseModel):
    slots: dict[str, DocumentOut | None]


class SetSlotRequest(BaseModel):
    path: str


class ProgressRequest(BaseModel):
    page: int
