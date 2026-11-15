"""Citações: a IA escreve rótulos como [A p.12]; o backend valida contra os trechos
recuperados e os converte em links clicáveis. Páginas inventadas são descartadas.

Formato final no texto (Markdown): [(arquivo.pdf, p. 12)](ref:A:12)
"""

import re
from dataclasses import dataclass

from app.services.retrieval import RetrievedChunk

_TAG = re.compile(
    r"^\[\s*([AaBb])\s*[:,]?\s*(?:p(?:[áa]gs?|gs?)?\.?\s*)?(\d+(?:\s*(?:,|;|e|and|-|–|—)\s*\d+)*)\s*\]$"
)
_LINK = re.compile(r"\[\([^\]]*?, p\. (\d+)\)\]\(ref:([AB]):\d+\)")
MAX_TAG_LENGTH = 40
MAX_RANGE = 5


@dataclass(frozen=True)
class Citation:
    slot: str
    page: int
    file_name: str
    document_id: str

    def to_dict(self) -> dict:
        return {"slot": self.slot, "page": self.page, "file_name": self.file_name, "document_id": self.document_id}


def allowed_citations(chunks: list[RetrievedChunk]) -> dict[tuple[str, int], Citation]:
    """Páginas que podem ser citadas = páginas dos trechos realmente enviados à IA."""
    return {(c.slot, c.page): Citation(c.slot, c.page, c.file_name, c.document_id) for c in chunks}


def _pages_from(raw: str) -> list[int]:
    numbers = [int(n) for n in re.findall(r"\d+", raw)]
    if len(numbers) == 2 and re.search(r"[-–—]", raw) and 0 < numbers[1] - numbers[0] <= MAX_RANGE:
        return list(range(numbers[0], numbers[1] + 1))
    return numbers


class CitationRewriter:
    """Reescreve citações em fluxo (texto chegando aos poucos), sem cortar um rótulo ao meio.

    Espaços logo antes de um colchete ficam "retidos" até se saber se vem uma citação: se ela for
    inválida e for removida, o espaço some junto (sem deixar "texto ." no resultado).
    """

    def __init__(self, allowed: dict[tuple[str, int], Citation]) -> None:
        self._allowed = allowed
        self._buffer = ""
        self._held = ""
        self.citations: list[Citation] = []   # válidas, únicas, na ordem de aparição
        self.invalid_count = 0

    def feed(self, text: str) -> str:
        out: list[str] = []
        for ch in text:
            if self._buffer:
                if ch == "[":  # novo colchete: o anterior não era uma citação
                    self._emit(out, self._buffer)
                    self._buffer = "["
                    continue
                self._buffer += ch
                if ch == "]":
                    resolved = self._resolve(self._buffer)
                    self._buffer = ""
                    if resolved:
                        self._emit(out, resolved)
                    else:
                        self._held = ""   # citação inválida removida: descarta o espaço antes dela
                elif len(self._buffer) > MAX_TAG_LENGTH:
                    self._emit(out, self._buffer)
                    self._buffer = ""
            elif ch == "[":
                self._buffer = "["
            elif ch in " 	":
                self._held += ch
            else:
                self._emit(out, ch)
        return "".join(out)

    def finish(self) -> str:
        rest = self._held + self._buffer
        self._held = self._buffer = ""
        return rest

    def _emit(self, out: list[str], text: str) -> None:
        out.append(self._held + text)
        self._held = ""

    def _resolve(self, raw: str) -> str:
        match = _TAG.match(raw)
        if not match:
            return raw  # colchetes comuns do Markdown
        slot = match.group(1).upper()
        links: list[str] = []
        for page in _pages_from(match.group(2)):
            citation = self._allowed.get((slot, page))
            if citation is None:
                self.invalid_count += 1
                continue
            if citation not in self.citations:
                self.citations.append(citation)
            label = citation.file_name.replace("[", "(").replace("]", ")")
            links.append(f"[({label}, p. {citation.page})](ref:{slot}:{citation.page})")
        return " ".join(links)


def to_history_text(content: str) -> str:
    """Volta os links de citação para rótulos curtos ([A p.12]) ao reenviar o histórico à IA."""
    return _LINK.sub(lambda m: f"[{m.group(2)} p.{m.group(1)}]", content)
