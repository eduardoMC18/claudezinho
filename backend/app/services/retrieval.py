"""Recuperação de trechos relevantes.

Hoje: busca textual por relevância (BM25 do SQLite FTS5). A interface `Retriever` permite
trocar ou combinar por busca vetorial no futuro sem mexer no restante do chat — a tabela
`chunk` já tem as colunas `embedding`/`embedding_model` reservadas para isso.
"""

import re
from dataclasses import dataclass
from typing import Protocol

from app.domain.models import Document
from app.infra.documents_repo import ChunkRow, DocumentRepository


@dataclass(frozen=True)
class RetrievedChunk:
    chunk_id: int
    document_id: str
    slot: str
    file_name: str
    page: int
    text: str
    score: float  # menor = mais relevante


class Retriever(Protocol):
    def retrieve(self, query: str, documents: list[Document], per_document: int,
                 previous_queries: list[str] | None = None) -> dict[str, list[RetrievedChunk]]:
        """Devolve, para cada slot ("A"/"B"), trechos em ordem de relevância."""
        ...


_STOPWORDS = frozenset("""
a o as os um uma uns umas de do da dos das em no na nos nas por para com sem sob sobre entre ate ao aos
e ou mas que se como qual quais quando onde quem cujo isso isto esse essa esses essas este esta estes estas
eu tu ele ela nos voce voces eles elas meu minha seu sua nosso nossa me te lhe
ser estar ter haver fazer ir foi era sao eh esta estao tem ha pode podem deve devem fala falam diz dizem
muito mais menos tambem ja nao sim so apenas ainda depois antes entao porque pois cada todo toda todos todas
documento documentos pdf arquivo arquivos texto primeiro segundo ambos dois duas qual quais
the a an of to in on for with and or but that this these those is are was were be been it its as at by from
what which who how why when where do does did can could should would about between
""".split())

_INTENT_OVERVIEW = re.compile(
    r"\b(resum\w*|compar\w*|diferen\w*|contradi\w*|vis[aã]o geral|panorama|sintet\w*|conclus\w*|overview|summar\w*|differ\w*|contrast\w*)\b",
    re.IGNORECASE,
)
_WORD = re.compile(r"[^\W_]+", re.UNICODE)

MAX_QUERY_TERMS = 14
MIN_HITS_BEFORE_OVERVIEW = 3


def _strip_accents(text: str) -> str:
    import unicodedata
    return "".join(c for c in unicodedata.normalize("NFD", text) if unicodedata.category(c) != "Mn")


def query_terms(text: str) -> list[str]:
    """Palavras relevantes da pergunta (sem stopwords, sem repetições, na ordem)."""
    seen: list[str] = []
    for word in _WORD.findall(text.lower()):
        plain = _strip_accents(word)
        if plain in _STOPWORDS or (len(word) < 3 and not word.isdigit()):
            continue
        if word not in seen:
            seen.append(word)
    return seen[:MAX_QUERY_TERMS]


def build_match_expression(terms: list[str]) -> str | None:
    """Expressão FTS5 'termo* OR termo* ...' com radical simples (tira 1–2 letras finais).

    Cada termo vai entre aspas, então caracteres especiais do usuário nunca viram sintaxe FTS5.
    """
    parts = []
    for term in terms:
        stem = term[:-2] if len(term) >= 7 else term[:-1] if len(term) >= 5 else term
        parts.append('"' + stem.replace('"', '') + '"*')
    return " OR ".join(parts) if parts else None


class Bm25Retriever:
    def __init__(self, repo: DocumentRepository) -> None:
        self._repo = repo

    def retrieve(self, query: str, documents: list[Document], per_document: int,
                 previous_queries: list[str] | None = None) -> dict[str, list[RetrievedChunk]]:
        terms = query_terms(query)
        # Pergunta complementar ("e no segundo?"): termos da pergunta anterior entram depois dos atuais.
        for earlier in reversed(previous_queries or []):
            for term in query_terms(earlier):
                if term not in terms and len(terms) < MAX_QUERY_TERMS:
                    terms.append(term)
            break
        match = build_match_expression(terms)
        overview = bool(_INTENT_OVERVIEW.search(query))

        result: dict[str, list[RetrievedChunk]] = {}
        for doc in documents:
            hits: list[ChunkRow] = []
            if match:
                try:
                    hits = self._repo.search_chunks(doc.id, match, per_document)
                except Exception:  # expressão FTS inválida jamais deve derrubar o chat
                    hits = []
            chosen = list(hits)
            if overview or len(hits) < MIN_HITS_BEFORE_OVERVIEW:
                spread = self._repo.spread_chunks(doc.id, max(2, per_document // 2 if overview else per_document))
                known = {c.id for c in chosen}
                chosen.extend(c for c in spread if c.id not in known)
            result[doc.slot] = [
                RetrievedChunk(c.id, doc.id, doc.slot, doc.name, c.page, c.text, c.score)
                for c in chosen[:per_document + (per_document // 2 if overview else 0)]
            ]
        return result
