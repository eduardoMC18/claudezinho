"""Exercícios gerados pela IA a partir dos PDFs carregados (sem chat).

Fluxo:
  gerar   → documentos prontos → janela de trechos de cada PDF (repo) → contexto com orçamento
          → Gemini em modo JSON → validação (schema + referências só a páginas enviadas) → questão
  objetiva → correção LOCAL (o gabarito e as explicações vieram junto na geração e ficam só no backend
             até o envio da resposta)
  discursiva → trechos citados pela questão + recuperação BM25 pela pergunta e pela resposta → Gemini em
             modo JSON → validação → correção estruturada

Os exercícios ficam numa memória limitada (não são gravados em disco). A chave da API nunca sai do cofre.
"""

import logging
import random
import threading
import uuid
from collections import OrderedDict
from dataclasses import dataclass, field

from app.domain.models import STATUS_PROCESSING, STATUS_READY, ChatError, Document
from app.infra.documents_repo import DocumentRepository
from app.providers.base import CompletionRequest, Finished, ProviderMessage, TextDelta, Usage
from app.providers.catalog import ChatTuning
from app.services import exercise_prompts as prompts
from app.services.ai_settings import AiSettingsService, ResolvedProvider
from app.services.citations import Citation, allowed_citations
from app.services.context import ContextResult, build_context
from app.services.exercise_schema import (
    ExerciseFormatError,
    GradeDraft,
    ObjectiveDraft,
    SubjectiveDraft,
    OPTION_LETTERS,
    parse_draft,
    resolve_references,
)
from app.services.retrieval import RetrievedChunk, Retriever
from app.services.workspace import WorkspaceService

log = logging.getLogger(__name__)

# Exercícios usam sempre o Gemini (o modelo é o escolhido em Configurações para ele).
EXERCISE_PROVIDER = "gemini"
KIND_OBJECTIVE = "objective"
KIND_SUBJECTIVE = "subjective"
MAX_ATTEMPTS = 2            # a resposta do modelo é pedida de novo uma vez se vier fora do formato
MAX_STORED = 40
RECENT_STATEMENTS = 6
WINDOW_CHUNKS = 6           # trechos consecutivos de cada PDF usados para criar uma questão
GRADE_PER_DOCUMENT = 3      # trechos extras por documento, buscados pela pergunta e pela resposta
MAX_ANSWER_CHARS = 6000
MIN_ANSWER_CHARS = 3


class ExerciseError(ChatError):
    """Falha controlada: `kind` e `message` vão para a interface."""


@dataclass
class Exercise:
    id: str
    kind: str
    draft: ObjectiveDraft | SubjectiveDraft
    order: list[int]                         # objetiva: posição embaralhada → índice no rascunho
    references: list[Citation]
    chunks: list[RetrievedChunk]             # trechos enviados à IA na geração (para a correção)
    model: str
    usage: dict
    answered: bool = False
    lock: threading.Lock = field(default_factory=threading.Lock)


def _usage_dict(resolved: ResolvedProvider, usage: Usage) -> dict:
    return {"input_tokens": usage.input_tokens, "output_tokens": usage.output_tokens,
            "cost_usd": resolved.model.cost_usd(usage.input_tokens, usage.output_tokens)}


def _sum_usage(a: dict, b: dict) -> dict:
    cost = None if a["cost_usd"] is None or b["cost_usd"] is None else a["cost_usd"] + b["cost_usd"]
    return {"input_tokens": a["input_tokens"] + b["input_tokens"],
            "output_tokens": a["output_tokens"] + b["output_tokens"], "cost_usd": cost}


class ExerciseService:
    def __init__(self, workspace: WorkspaceService, repo: DocumentRepository, retriever: Retriever,
                 ai: AiSettingsService, tuning: ChatTuning, rng: random.Random | None = None) -> None:
        self._workspace = workspace
        self._repo = repo
        self._retriever = retriever
        self._ai = ai
        self._tuning = tuning
        self._rng = rng or random.Random()
        self._items: OrderedDict[str, Exercise] = OrderedDict()
        self._recent: list[str] = []
        self._lock = threading.Lock()

    # ----- geração -----

    def generate(self, kind: str) -> tuple[Exercise, list[dict]]:
        if kind not in (KIND_OBJECTIVE, KIND_SUBJECTIVE):
            raise ExerciseError("bad_kind", "Tipo de exercício desconhecido.")
        documents = self._usable_documents()
        resolved = self._ai.resolve(EXERCISE_PROVIDER, None)

        context = self._generation_context(documents)
        allowed = allowed_citations(context.chunks)
        task = prompts.TASK_OBJECTIVE if kind == KIND_OBJECTIVE else prompts.TASK_SUBJECTIVE
        system = prompts.SYSTEM_OBJECTIVE if kind == KIND_OBJECTIVE else prompts.SYSTEM_SUBJECTIVE
        schema = ObjectiveDraft if kind == KIND_OBJECTIVE else SubjectiveDraft
        with self._lock:
            recent = list(self._recent)
        turn = prompts.build_generation_turn(task, context.text, recent)

        draft, usage = self._ask_structured(resolved, system, turn, schema, allowed)
        references, _ = resolve_references(draft.references, allowed)

        order = list(range(len(draft.options))) if kind == KIND_OBJECTIVE else []
        if kind == KIND_OBJECTIVE:
            self._rng.shuffle(order)
        exercise = Exercise(uuid.uuid4().hex, kind, draft, order, references, context.chunks,
                            resolved.model.id, usage)
        self._remember(exercise)
        return exercise, [c.to_dict() for c in references]

    def _usable_documents(self) -> list[Document]:
        documents = [d for d in self._workspace.get_slots().values() if d is not None]
        if not documents:
            raise ExerciseError("no_documents", "Carregue pelo menos um PDF no Leitor de PDFs para criar exercícios.")
        for doc in documents:
            if doc.status == STATUS_PROCESSING:
                raise ExerciseError("indexing", f"“{doc.name}” ainda está sendo indexado ({doc.pages_done}/{doc.pages} "
                                                "páginas). Aguarde terminar.")
        ready = [d for d in documents if d.status == STATUS_READY]
        if not ready:
            raise ExerciseError("no_text", "Os documentos carregados não têm texto pesquisável (parecem escaneados), "
                                           "então a IA não consegue criar exercícios a partir deles.")
        return ready

    def _generation_context(self, documents: list[Document]) -> ContextResult:
        per_slot: dict[str, list[RetrievedChunk]] = {}
        for doc in documents:
            rows = self._repo.window_chunks(doc.id, WINDOW_CHUNKS, self._rng.random())
            per_slot[doc.slot] = [RetrievedChunk(r.id, doc.id, doc.slot, doc.name, r.page, r.text, 0.0) for r in rows]
        context = build_context(per_slot, documents, self._tuning.context_token_budget)
        if not context.chunks:
            raise ExerciseError("no_text", "Não foi possível ler texto dos documentos carregados.")
        return context

    # ----- resposta / correção -----

    def answer(self, exercise_id: str, option: str | None, text: str | None) -> dict:
        exercise = self._get(exercise_id)
        with exercise.lock:                      # duplo clique não corrige duas vezes
            if exercise.answered:
                raise ExerciseError("already_answered", "Esta questão já foi respondida. Gere uma nova questão.")
            if exercise.kind == KIND_OBJECTIVE:
                result = self._grade_objective(exercise, option)
            else:
                result = self._grade_subjective(exercise, text)
            exercise.answered = True             # só depois de a correção existir (falha deixa tentar de novo)
        return result

    def _grade_objective(self, exercise: Exercise, option: str | None) -> dict:
        draft: ObjectiveDraft = exercise.draft  # type: ignore[assignment]
        letters = OPTION_LETTERS[: len(draft.options)]
        selected = (option or "").strip().lower()
        if len(selected) != 1 or selected not in letters:
            raise ExerciseError("no_selection", "Selecione uma alternativa antes de enviar.")
        options = []
        correct_letter = ""
        for position, source in enumerate(exercise.order):
            letter = OPTION_LETTERS[position]
            is_correct = source == draft.correct_index
            correct_letter = letter if is_correct else correct_letter
            options.append({"id": letter, "text": draft.options[source].text,
                            "explanation": draft.options[source].explanation, "is_correct": is_correct})
        return {
            "exercise_id": exercise.id, "kind": KIND_OBJECTIVE, "correct": selected == correct_letter,
            "selected": selected, "correct_option": correct_letter, "explanation": draft.explanation,
            "options": options, "difficulty": draft.difficulty,
            "references": [c.to_dict() for c in exercise.references],
        }

    def _grade_subjective(self, exercise: Exercise, text: str | None) -> dict:
        draft: SubjectiveDraft = exercise.draft  # type: ignore[assignment]
        student = (text or "").strip()
        if len(student) < MIN_ANSWER_CHARS:
            raise ExerciseError("empty_answer", "Escreva sua resposta antes de enviar.")
        if len(student) > MAX_ANSWER_CHARS:
            raise ExerciseError("too_long", f"A resposta é longa demais (máximo {MAX_ANSWER_CHARS} caracteres).")

        documents = self._usable_documents_or_empty()
        resolved = self._ai.resolve(EXERCISE_PROVIDER, None)
        context = self._grading_context(exercise, draft, student, documents)
        allowed = allowed_citations(context.chunks)
        turn = prompts.build_grade_turn(context.text, draft.statement, draft.criteria, draft.model_answer, student)
        grade, usage = self._ask_structured(resolved, prompts.SYSTEM_GRADE, turn, GradeDraft, allowed)

        references, _ = resolve_references(grade.references, allowed)
        if not references:                       # a correção não citou nada válido: usa as fontes da questão
            references = exercise.references
        return {
            "exercise_id": exercise.id, "kind": KIND_SUBJECTIVE, "verdict": grade.level,
            "score": round(grade.score, 1), "summary": grade.summary, "strengths": grade.strengths,
            "missing": grade.missing,
            "corrections": [{"concept": c.concept, "explanation": c.explanation} for c in grade.corrections],
            "ideal_answer": grade.ideal_answer, "criteria": draft.criteria, "difficulty": draft.difficulty,
            "references": [c.to_dict() for c in references],
            "usage": usage,
        }

    def _usable_documents_or_empty(self) -> list[Document]:
        return [d for d in self._workspace.get_slots().values() if d is not None and d.status == STATUS_READY]

    def _grading_context(self, exercise: Exercise, draft: SubjectiveDraft, student: str,
                         documents: list[Document]) -> ContextResult:
        """Trechos que sustentam a questão + os mais parecidos com a pergunta e com a resposta do estudante."""
        cited = {(c.slot, c.page) for c in exercise.references}
        per_slot: dict[str, list[RetrievedChunk]] = {}
        seen: set[int] = set()
        for chunk in exercise.chunks:
            if (chunk.slot, chunk.page) in cited and chunk.chunk_id not in seen:
                per_slot.setdefault(chunk.slot, []).append(chunk)
                seen.add(chunk.chunk_id)
        if documents:
            extra = self._retriever.retrieve(f"{draft.statement} {student}", documents, GRADE_PER_DOCUMENT, None)
            for slot, chunks in extra.items():
                for chunk in chunks:
                    if chunk.chunk_id not in seen:
                        per_slot.setdefault(slot, []).append(chunk)
                        seen.add(chunk.chunk_id)
        # Se os PDFs foram removidos depois da geração, ainda dá para corrigir com os trechos guardados.
        context = build_context(per_slot, documents, self._tuning.context_token_budget)
        if not context.chunks:
            raise ExerciseError("no_context", "Não há mais trechos dos PDFs para corrigir esta questão. "
                                              "Gere uma nova questão.")
        return context

    # ----- chamada ao modelo, com validação -----

    def _ask_structured(self, resolved: ResolvedProvider, system: str, user_turn: str, schema, allowed):
        total = {"input_tokens": 0, "output_tokens": 0, "cost_usd": 0.0}
        problem = ""
        for attempt in range(MAX_ATTEMPTS):
            turn = user_turn if not problem else (
                f"{user_turn}\n\nA resposta anterior foi rejeitada ({problem}). Responda de novo, só com o JSON válido "
                "no formato pedido.")
            text, usage, truncated = self._complete(resolved, system, turn)
            total = _sum_usage(total, _usage_dict(resolved, usage))
            try:
                if truncated:
                    raise ExerciseFormatError("a resposta foi cortada por limite de tamanho")
                draft = parse_draft(schema, text)
                if schema is not GradeDraft:
                    valid, _ = resolve_references(draft.references, allowed)
                    if not valid:
                        raise ExerciseFormatError("nenhuma referência válida aos trechos enviados")
                return draft, total
            except ExerciseFormatError as exc:
                problem = str(exc)
                log.warning("Resposta estruturada inválida (tentativa %d): %s", attempt + 1, problem)
        raise ExerciseError("invalid_response", "A IA devolveu uma resposta fora do formato esperado. "
                                                "Tente de novo em instantes.")

    @staticmethod
    def _complete(resolved: ResolvedProvider, system: str, user_turn: str) -> tuple[str, Usage, bool]:
        request = CompletionRequest(
            model=resolved.model.id, system=system, messages=[ProviderMessage("user", user_turn)],
            max_tokens=resolved.model.max_output_tokens, effort=resolved.model.effort,
            refusal_fallback=resolved.model.refusal_fallback, json_mode=True)
        parts: list[str] = []
        usage, truncated = Usage(), False
        for event in resolved.provider.stream(resolved.api_key, request, threading.Event()):
            if isinstance(event, TextDelta):
                parts.append(event.text)
            elif isinstance(event, Finished):
                usage, truncated = event.usage, event.truncated
        return "".join(parts), usage, truncated

    # ----- memória -----

    def _get(self, exercise_id: str) -> Exercise:
        with self._lock:
            exercise = self._items.get(exercise_id)
        if exercise is None:
            raise ExerciseError("not_found", "Questão não encontrada (ela pode ter expirado). Gere uma nova.")
        return exercise

    def _remember(self, exercise: Exercise) -> None:
        with self._lock:
            self._items[exercise.id] = exercise
            while len(self._items) > MAX_STORED:
                self._items.popitem(last=False)
            self._recent.append(exercise.draft.statement)
            del self._recent[:-RECENT_STATEMENTS]


def public_exercise(exercise: Exercise) -> dict:
    """O que a interface recebe ao gerar: enunciado e alternativas, SEM gabarito nem explicações."""
    draft = exercise.draft
    options = [{"id": OPTION_LETTERS[pos], "text": draft.options[src].text}  # type: ignore[union-attr]
               for pos, src in enumerate(exercise.order)]
    return {"id": exercise.id, "kind": exercise.kind, "statement": draft.statement, "difficulty": draft.difficulty,
            "options": options, "model": exercise.model, "usage": exercise.usage}

