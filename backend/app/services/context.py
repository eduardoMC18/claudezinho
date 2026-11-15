"""Montagem do contexto enviado à IA, respeitando um orçamento de tokens.

A estimativa usa ~3 caracteres por token (conservadora para português). Os dois documentos
são intercalados por relevância para que ambos estejam representados até o orçamento acabar.
"""

import re
from dataclasses import dataclass

from app.domain.models import STATUS_NO_TEXT, Document
from app.services.retrieval import RetrievedChunk

CHARS_PER_TOKEN = 3
CHUNK_OVERHEAD_TOKENS = 14  # tags e rótulo de cada trecho

_OUR_TAGS = re.compile(r"</?\s*(trecho|trechos|documentos|documento)\b[^>]*>", re.IGNORECASE)


def estimate_tokens(text: str) -> int:
    return (len(text) + CHARS_PER_TOKEN - 1) // CHARS_PER_TOKEN


@dataclass(frozen=True)
class ContextResult:
    text: str
    chunks: list[RetrievedChunk]  # trechos efetivamente incluídos (fonte das citações permitidas)
    estimated_tokens: int
    dropped: int  # trechos recuperados que não couberam no orçamento


def _clean(text: str) -> str:
    return _OUR_TAGS.sub("", text)


def _interleave(per_slot: dict[str, list[RetrievedChunk]]) -> list[RetrievedChunk]:
    slots = sorted(per_slot)
    ordered: list[RetrievedChunk] = []
    longest = max((len(v) for v in per_slot.values()), default=0)
    for rank in range(longest):
        for slot in slots:
            if rank < len(per_slot[slot]):
                ordered.append(per_slot[slot][rank])
    return ordered


def build_context(per_slot: dict[str, list[RetrievedChunk]], documents: list[Document],
                  token_budget: int) -> ContextResult:
    chosen: list[RetrievedChunk] = []
    used, dropped = 0, 0
    for chunk in _interleave(per_slot):
        cost = estimate_tokens(chunk.text) + CHUNK_OVERHEAD_TOKENS
        if used + cost > token_budget:
            dropped += 1
            continue
        chosen.append(chunk)
        used += cost

    lines = ["<documentos>"]
    for doc in sorted(documents, key=lambda d: d.slot):
        note = " — SEM TEXTO EXTRAÍVEL (provavelmente escaneado)" if doc.status == STATUS_NO_TEXT else ""
        lines.append(f'{doc.slot} = "{doc.name}" ({doc.pages} páginas){note}')
    lines.append("</documentos>")
    lines.append("")
    lines.append("<trechos>")
    by_slot: dict[str, list[RetrievedChunk]] = {}
    for chunk in chosen:
        by_slot.setdefault(chunk.slot, []).append(chunk)
    for slot in sorted(by_slot):
        for chunk in sorted(by_slot[slot], key=lambda c: (c.page, c.chunk_id)):
            lines.append(f'<trecho ref="{slot} p.{chunk.page}">')
            lines.append(_clean(chunk.text))
            lines.append("</trecho>")
    if not chosen:
        lines.append("(nenhum trecho relevante foi encontrado nos documentos para esta pergunta)")
    lines.append("</trechos>")

    text = "\n".join(lines)
    return ContextResult(text=text, chunks=chosen, estimated_tokens=estimate_tokens(text), dropped=dropped)
