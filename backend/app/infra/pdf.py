"""Acesso a PDFs via PyMuPDF."""

from dataclasses import dataclass
from pathlib import Path

import pymupdf  # PyMuPDF


class InvalidPdfError(ValueError):
    pass


@dataclass(frozen=True)
class PdfInfo:
    pages: int
    title: str | None


def inspect_pdf(path: Path) -> PdfInfo:
    """Abre o PDF e devolve metadados básicos. Levanta InvalidPdfError se inválido."""
    try:
        with pymupdf.open(path) as doc:
            if not doc.is_pdf:
                raise InvalidPdfError("O arquivo não é um PDF")
            if doc.needs_pass:
                raise InvalidPdfError("PDF protegido por senha não é suportado")
            title = (doc.metadata or {}).get("title") or None
            return PdfInfo(pages=doc.page_count, title=title)
    except InvalidPdfError:
        raise
    except Exception as exc:  # fitz levanta vários tipos para arquivos corrompidos
        raise InvalidPdfError(f"Não foi possível abrir o PDF: {exc}") from exc
