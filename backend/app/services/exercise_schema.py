"""Formato estruturado (JSON) que o Gemini devolve para questões e correções, e sua validação.

Nada que vem do modelo chega à interface sem passar por aqui: campos faltando, tipos errados,
alternativas repetidas, gabarito que não existe e referências a páginas que não foram enviadas
à IA viram `ExerciseFormatError` (o serviço tenta de novo uma vez e, se persistir, informa o usuário).
"""

import json
import re
from typing import Annotated, Any

from pydantic import BaseModel, BeforeValidator, Field, ValidationError, field_validator, model_validator

from app.services.citations import Citation

MAX_TEXT = 4000
OPTION_LETTERS = "abcdefgh"

_DIFFICULTY = {
    "facil": "facil", "fácil": "facil", "easy": "facil", "basico": "facil", "básico": "facil",
    "media": "media", "média": "media", "medio": "media", "médio": "media", "medium": "media", "intermediario": "media",
    "intermediário": "media", "dificil": "dificil", "difícil": "dificil", "hard": "dificil", "avancado": "dificil",
    "avançado": "dificil",
}
_INLINE_TAG = re.compile(r"\s*\[\s*[AaBb]\s*[:,]?\s*(?:p(?:[áa]g\w*|g)?\.?\s*)?\d+[^\]]{0,30}\]")
_REF = re.compile(r"^\s*\[?\s*([AaBb])\s*[:,]?\s*(?:p(?:[áa]g\w*|g)?\.?\s*)?(\d+)\s*\]?\s*$")
_FENCE = re.compile(r"^```[a-zA-Z]*\s*|\s*```$")


class ExerciseFormatError(Exception):
    """A resposta do modelo não está no formato esperado."""


def _clean_text(value: Any) -> Any:
    if isinstance(value, str):
        return _INLINE_TAG.sub("", value).strip()
    return value


Text = Annotated[str, BeforeValidator(_clean_text), Field(min_length=2, max_length=MAX_TEXT)]


def _ref_to_text(value: Any) -> Any:
    if isinstance(value, dict):  # {"slot": "A", "page": 3} também é aceito
        return f"{value.get('slot', value.get('doc', ''))} p.{value.get('page', '')}"
    return value


class _Draft(BaseModel):
    difficulty: str | None = None
    references: list[Annotated[str, BeforeValidator(_ref_to_text)]] = Field(min_length=1, max_length=8)

    @field_validator("difficulty", mode="before")
    @classmethod
    def _difficulty(cls, value: Any) -> str | None:
        return _DIFFICULTY.get(str(value).strip().lower()) if value is not None else None


class OptionDraft(BaseModel):
    text: Text
    explanation: Text


class ObjectiveDraft(_Draft):
    statement: Text = Field(min_length=10)
    options: list[OptionDraft] = Field(min_length=3, max_length=5)
    correct: str
    explanation: Text

    @field_validator("correct", mode="before")
    @classmethod
    def _correct(cls, value: Any) -> str:
        return str(value).strip().lower().rstrip(").:").lstrip("(")

    @model_validator(mode="after")
    def _consistent(self) -> "ObjectiveDraft":
        texts = [o.text.strip().lower() for o in self.options]
        if len(set(texts)) != len(texts):
            raise ValueError("alternativas repetidas")
        if self.correct not in OPTION_LETTERS[: len(self.options)]:
            raise ValueError("o gabarito não corresponde a nenhuma alternativa")
        return self

    @property
    def correct_index(self) -> int:
        return OPTION_LETTERS.index(self.correct)


class SubjectiveDraft(_Draft):
    statement: Text = Field(min_length=10)
    criteria: list[Text] = Field(min_length=2, max_length=8)
    model_answer: Text = Field(min_length=10)


class Correction(BaseModel):
    concept: Text
    explanation: Text


class GradeDraft(BaseModel):
    score: float = Field(ge=0, le=10)
    verdict: str | None = None
    summary: Text
    strengths: list[Text] = Field(default_factory=list, max_length=8)
    missing: list[Text] = Field(default_factory=list, max_length=8)
    corrections: list[Correction] = Field(default_factory=list, max_length=8)
    ideal_answer: Text = Field(min_length=10)
    references: list[Annotated[str, BeforeValidator(_ref_to_text)]] = Field(default_factory=list, max_length=8)

    @field_validator("score", mode="before")
    @classmethod
    def _score(cls, value: Any) -> Any:
        if isinstance(value, str):
            value = value.strip().replace(",", ".").removesuffix("/10")
        return value

    @property
    def level(self) -> str:
        """correct | partial | incorrect — pelo veredito do modelo se válido; senão pela nota."""
        if self.verdict in ("correct", "partial", "incorrect"):
            return self.verdict
        return "correct" if self.score >= 7 else "partial" if self.score >= 4 else "incorrect"


def extract_json(raw: str) -> Any:
    """Acha o objeto JSON na resposta (tolera cercas ```json e texto antes/depois)."""
    text = _FENCE.sub("", (raw or "").strip())
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        raise ExerciseFormatError("a resposta do modelo não contém um objeto JSON")
    try:
        return json.loads(text[start:end + 1])
    except json.JSONDecodeError as exc:
        raise ExerciseFormatError(f"JSON inválido: {exc.msg}") from None


def parse_draft(model: type[BaseModel], raw: str) -> Any:
    try:
        return model.model_validate(extract_json(raw))
    except ValidationError as exc:
        first = exc.errors()[0]
        where = ".".join(str(p) for p in first.get("loc", ()))
        raise ExerciseFormatError(f"formato inesperado em '{where}': {first.get('msg')}") from None


def resolve_references(raw: list[str], allowed: dict[tuple[str, int], Citation]) -> tuple[list[Citation], int]:
    """Mantém só referências a páginas que realmente foram enviadas à IA; conta as inventadas."""
    valid: list[Citation] = []
    invalid = 0
    for item in raw:
        match = _REF.match(item)
        citation = allowed.get((match.group(1).upper(), int(match.group(2)))) if match else None
        if citation is None:
            invalid += 1
        elif citation not in valid:
            valid.append(citation)
    return valid, invalid
