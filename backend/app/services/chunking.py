"""Divisão do texto de uma página em trechos (chunks) com sobreposição.

Cada trecho pertence a UMA página (a referência de página nunca se perde). Dentro da
página, janelas de ~MAX_CHARS caracteres avançam com sobreposição de ~OVERLAP_CHARS, para
que uma ideia cortada na fronteira apareça inteira em pelo menos um trecho. Os cortes
preferem fim de frase, depois espaço (nunca no meio de uma palavra).
"""

import re

# Mude esta versão ao alterar o algoritmo: documentos já indexados são reindexados na abertura.
CHUNKER_VERSION = 2

MAX_CHARS = 1000
OVERLAP_CHARS = 200
_SENTENCE_WINDOW = 160  # procura fim de frase nos últimos N caracteres da janela

_PARAGRAPH_BREAK = re.compile(r"\n\s*\n")
_SPACES = re.compile(r"[ \t\r\f\v]+")


def normalize(text: str) -> str:
    """Compacta espaços e mantém quebras entre parágrafos."""
    paragraphs = [_SPACES.sub(" ", p.replace("\n", " ")).strip() for p in _PARAGRAPH_BREAK.split(text)]
    return "\n".join(p for p in paragraphs if p)


def split_page_text(text: str, max_chars: int = MAX_CHARS, overlap: int = OVERLAP_CHARS) -> list[str]:
    clean = normalize(text)
    if not clean:
        return []
    if len(clean) <= max_chars:
        return [clean]

    overlap = min(overlap, max_chars // 2)
    chunks: list[str] = []
    start, total = 0, len(clean)
    while start < total:
        end = min(start + max_chars, total)
        if end < total:
            end = _best_cut(clean, start, end)
        piece = clean[start:end].strip()
        if piece:
            chunks.append(piece)
        if end >= total:
            break
        # Recua `overlap` caracteres e alinha ao início de uma palavra.
        next_start = max(end - overlap, start + 1)
        space = clean.find(" ", next_start, end)
        start = space + 1 if space != -1 else end
    return chunks


def _best_cut(text: str, start: int, end: int) -> int:
    """Melhor ponto de corte em (start, end]: fim de frase > espaço > corte seco."""
    window_start = max(start + 1, end - _SENTENCE_WINDOW)
    for i in range(end - 1, window_start - 1, -1):
        if text[i] in ".!?;:" and i + 1 < len(text) and text[i + 1] in " \n":
            return i + 1
    space = text.rfind(" ", start + 1, end)
    return space if space > start else end
