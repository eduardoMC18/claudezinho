"""Exercícios com IA: geração estruturada, correção objetiva/discursiva e falhas, contra o Gemini falso."""

import pytest
from fastapi.testclient import TestClient

from app.services.exercise_schema import (
    ExerciseFormatError,
    GradeDraft,
    ObjectiveDraft,
    parse_draft,
    resolve_references,
)
from tests.conftest import HEADERS
from tests.test_gemini import TOPICS_A, TOPICS_B, load_docs, save_key


def generate(client: TestClient, kind: str):
    return client.post("/exercises/generate", json={"kind": kind}, headers=HEADERS)


def answer(client: TestClient, exercise_id: str, **body):
    return client.post(f"/exercises/{exercise_id}/answer", json=body, headers=HEADERS)


def error_of(response) -> dict:
    return response.json()["detail"]


@pytest.fixture()
def ready(client: TestClient, make_topic_pdf) -> TestClient:
    save_key(client)
    load_docs(client, make_topic_pdf)
    return client


def option_id(exercise: dict, fragment: str) -> str:
    return next(o["id"] for o in exercise["options"] if fragment in o["text"])


# ----- pré-condições -----

def test_requires_documents_and_key(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    r = generate(client, "objective")
    assert r.status_code == 400 and error_of(r)["kind"] == "no_documents"

    load_docs(client, make_topic_pdf)
    client.delete("/settings/ai/providers/gemini/key", headers=HEADERS)
    r = generate(client, "objective")
    assert r.status_code == 400 and error_of(r)["kind"] == "no_key"
    assert "Gemini" in error_of(r)["message"]


def test_scanned_documents_are_refused(client: TestClient, make_pdf) -> None:
    save_key(client)
    client.put("/workspace/slots/A", json={"path": str(make_pdf("scan.pdf", pages=2, text=False))}, headers=HEADERS)
    r = generate(client, "objective")
    assert r.status_code == 400 and error_of(r)["kind"] == "no_text"


def test_unknown_kind_and_unknown_exercise(ready: TestClient) -> None:
    assert generate(ready, "oral").status_code == 400
    assert answer(ready, "nao-existe", option="a").status_code == 404


# ----- objetiva -----

def test_objective_generation_hides_the_answer_key_and_uses_only_retrieved_snippets(
        ready: TestClient, fake_gemini, fake_anthropic) -> None:
    r = generate(ready, "objective")
    assert r.status_code == 200, r.text
    q = r.json()
    assert q["kind"] == "objective" and q["difficulty"] == "media"          # "média" normalizado
    assert [o["id"] for o in q["options"]] == ["a", "b", "c", "d"]
    assert {"correct", "explanation", "references", "criteria"}.isdisjoint(q)    # sem gabarito
    assert all(set(o) == {"id", "text"} for o in q["options"])               # sem explicações das alternativas
    assert q["usage"]["input_tokens"] > 0

    sent = fake_gemini.requests[-1]
    assert sent["body"]["generationConfig"]["responseMimeType"] == "application/json"
    prompt = sent["body"]["contents"][-1]["parts"][0]["text"]
    assert '<trecho ref="A p.' in prompt and '<trecho ref="B p.' in prompt   # os dois PDFs entram
    assert fake_anthropic.requests == []                                       # só o Gemini é usado


def test_objective_correct_answer(ready: TestClient) -> None:
    q = generate(ready, "objective").json()
    chosen = option_id(q, "Produzir ATP")
    r = answer(ready, q["id"], option=chosen)
    assert r.status_code == 200, r.text
    result = r.json()
    assert result["correct"] is True and result["selected"] == chosen == result["correct_option"]
    assert "ATP" in result["explanation"]
    assert [o["is_correct"] for o in result["options"]].count(True) == 1
    assert all(o["explanation"] for o in result["options"])
    assert result["references"] and {"slot", "page", "file_name", "document_id"} <= set(result["references"][0])
    assert result["references"][0]["file_name"] in ("bio.pdf", "plantas.pdf")


def test_objective_wrong_answer_explains_the_chosen_option(ready: TestClient) -> None:
    q = generate(ready, "objective").json()
    wrong = option_id(q, "fotossíntese")
    result = answer(ready, q["id"], option=wrong).json()
    assert result["correct"] is False and result["selected"] == wrong
    assert result["correct_option"] == option_id(q, "Produzir ATP") != wrong
    chosen = next(o for o in result["options"] if o["id"] == wrong)
    assert "cloroplastos" in chosen["explanation"] and chosen["is_correct"] is False


def test_objective_answer_is_locked_after_submit_but_invalid_selection_is_not(ready: TestClient) -> None:
    q = generate(ready, "objective").json()
    assert answer(ready, q["id"]).status_code == 400
    bad = answer(ready, q["id"], option="z")
    assert bad.status_code == 400 and error_of(bad)["kind"] == "no_selection"
    assert answer(ready, q["id"], option="a").status_code == 200       # a tentativa inválida não travou a questão
    again = answer(ready, q["id"], option="b")
    assert again.status_code == 409 and error_of(again)["kind"] == "already_answered"


def test_options_are_shuffled_between_questions(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    positions = {option_id(generate(client, "objective").json(), "Produzir ATP") for _ in range(12)}
    assert len(positions) > 1


# ----- discursiva -----

def test_subjective_flow_with_structured_grading(ready: TestClient, fake_gemini) -> None:
    q = generate(ready, "subjective").json()
    assert q["kind"] == "subjective" and q["options"] == [] and "mitocôndria" in q["statement"]
    assert {"criteria", "model_answer", "references"}.isdisjoint(q)         # critérios só vão ao corretor

    text = "A mitocôndria produz ATP na respiração celular e o cloroplasto faz fotossíntese."
    r = answer(ready, q["id"], text=text)
    assert r.status_code == 200, r.text
    g = r.json()
    assert g["verdict"] == "correct" and g["score"] == 8.5
    assert g["summary"] and g["strengths"] and g["ideal_answer"] and g["criteria"]
    assert g["references"] and g["references"][0]["slot"] in ("A", "B")

    prompt = fake_gemini.requests[-1]["body"]["contents"][-1]["parts"][0]["text"]
    assert text in prompt and "<criterios_esperados>" in prompt and "<trecho ref=" in prompt
    assert "<questao>" in prompt and "<resposta_de_referencia>" in prompt

    again = answer(ready, q["id"], text=text)
    assert again.status_code == 409


@pytest.mark.parametrize("marker, verdict, score", [("ERRADO", "incorrect", 2.0), ("PARCIAL", "partial", 5.0)])
def test_subjective_levels(ready: TestClient, marker: str, verdict: str, score: float) -> None:
    q = generate(ready, "subjective").json()
    g = answer(ready, q["id"], text=f"Minha resposta {marker} sobre o assunto.").json()
    assert g["verdict"] == verdict and g["score"] == score
    if verdict == "incorrect":
        assert g["corrections"] and g["corrections"][0]["concept"]


def test_subjective_invented_reference_is_removed(ready: TestClient) -> None:
    q = generate(ready, "subjective").json()
    g = answer(ready, q["id"], text="Resposta com INVENTA no meio, sobre mitocôndria.").json()
    assert all(ref["page"] != 999 for ref in g["references"])
    assert g["references"]


def test_subjective_empty_answer_is_rejected_without_locking(ready: TestClient) -> None:
    q = generate(ready, "subjective").json()
    r = answer(ready, q["id"], text="   ")
    assert r.status_code == 400 and error_of(r)["kind"] == "empty_answer"
    assert answer(ready, q["id"], text="Resposta de verdade sobre o tema.").status_code == 200


def test_grading_still_works_after_pdfs_are_removed(ready: TestClient) -> None:
    q = generate(ready, "subjective").json()
    ready.delete("/workspace/slots/A", headers=HEADERS)
    ready.delete("/workspace/slots/B", headers=HEADERS)
    r = answer(ready, q["id"], text="Mitocôndria produz ATP e cloroplasto capta luz.")
    assert r.status_code == 200 and r.json()["references"]


# ----- falhas do Gemini -----

def test_invalid_json_is_retried_once_then_reported(client: TestClient, make_topic_pdf, fake_gemini) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    client.put("/workspace/slots/A", json={"path": str(make_topic_pdf("ruim.pdf", ["JSONRUIM texto com mais de vinte caracteres para indexar."]))},
               headers=HEADERS)
    fake_gemini.requests.clear()
    r = generate(client, "objective")
    assert r.status_code == 502 and error_of(r)["kind"] == "invalid_response"
    assert len(fake_gemini.requests) == 2          # uma nova tentativa com a rejeição explicada
    assert "rejeitada" in fake_gemini.requests[-1]["body"]["contents"][-1]["parts"][0]["text"]


def test_reference_to_unsent_page_invalidates_the_question(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    client.put("/workspace/slots/A", json={"path": str(make_topic_pdf("sem.pdf", ["SEMREF conteúdo com mais de vinte caracteres para indexar."]))},
               headers=HEADERS)
    r = generate(client, "subjective")
    assert r.status_code == 502 and error_of(r)["kind"] == "invalid_response"


@pytest.mark.parametrize("marker, status, kind", [("ERRO429", 502, "rate_limit"), ("ERRO529", 502, "overloaded")])
def test_provider_errors_are_reported(client: TestClient, make_topic_pdf, marker: str, status: int,
                                      kind: str) -> None:
    save_key(client)
    client.put("/workspace/slots/A", json={"path": str(make_topic_pdf("e.pdf", [f"{marker} conteúdo com mais de vinte caracteres para indexar."]))},
               headers=HEADERS)
    r = generate(client, "objective")
    assert r.status_code == status and error_of(r)["kind"] == kind and error_of(r)["message"]


def test_invalid_key_is_reported_as_auth(client: TestClient, make_topic_pdf) -> None:
    save_key(client, key="ERRO401")
    load_docs(client, make_topic_pdf)
    r = generate(client, "objective")
    assert r.status_code == 502 and error_of(r)["kind"] == "auth"


def test_failed_grading_can_be_retried(client: TestClient, make_topic_pdf, fake_gemini) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    q = generate(client, "subjective").json()
    r = answer(client, q["id"], text="ERRO429 resposta qualquer sobre mitocôndria")
    assert r.status_code == 502 and error_of(r)["kind"] == "rate_limit"
    ok = answer(client, q["id"], text="Resposta normal sobre a mitocôndria e o cloroplasto.")
    assert ok.status_code == 200          # a falha não consumiu a questão


def test_no_secret_in_exercise_responses(ready: TestClient) -> None:
    from tests.test_gemini import GEMINI_KEY
    q = generate(ready, "objective")
    assert GEMINI_KEY not in q.text
    assert GEMINI_KEY not in answer(ready, q.json()["id"], option="a").text


def test_requires_token(client: TestClient) -> None:
    assert client.post("/exercises/generate", json={"kind": "objective"}).status_code == 401


# ----- validação do formato (unidade) -----

OBJECTIVE = ('{"statement": "Qual é a função da mitocôndria?", "options": [{"text": "a1", "explanation": "e1"}, '
             '{"text": "b2", "explanation": "e2"}, {"text": "c3", "explanation": "e3"}], "correct": "%s", '
             '"explanation": "porque sim", "references": ["A p.1"]}')


def test_parse_accepts_fences_and_loose_answer_key() -> None:
    draft = parse_draft(ObjectiveDraft, "```json\n" + OBJECTIVE % "B)" + "\n```")
    assert draft.correct_index == 1 and draft.difficulty is None


@pytest.mark.parametrize("raw", [
    "sem json nenhum",
    OBJECTIVE % "z",                                              # gabarito inexistente
    OBJECTIVE.replace('"b2"', '"a1"') % "a",                      # alternativas repetidas
    OBJECTIVE.replace('"references": ["A p.1"]', '"references": []') % "a",
    '{"statement": "curto"}',
])
def test_parse_rejects_bad_structures(raw: str) -> None:
    with pytest.raises(ExerciseFormatError):
        parse_draft(ObjectiveDraft, raw)


def test_grade_score_range_and_level() -> None:
    base = ('{"score": %s, "summary": "ok", "ideal_answer": "resposta ideal completa", "references": []}')
    assert parse_draft(GradeDraft, base % '"7,5"').level == "correct"
    assert parse_draft(GradeDraft, base % "4").level == "partial"
    with pytest.raises(ExerciseFormatError):
        parse_draft(GradeDraft, base % "11")


def test_inline_page_tags_are_stripped_from_text() -> None:
    draft = parse_draft(ObjectiveDraft, OBJECTIVE.replace("função da mitocôndria?", "função [A p.3] da mitocôndria?") % "a")
    assert "[A p.3]" not in draft.statement


def test_resolve_references_counts_invented_pages() -> None:
    from app.services.citations import Citation
    allowed = {("A", 2): Citation("A", 2, "x.pdf", "d1")}
    valid, invalid = resolve_references(["A p.2", "[A p.2]", "A p.9", "lixo", "B p.1"], allowed)
    assert [c.page for c in valid] == [2] and invalid == 3


def test_topics_are_loaded() -> None:
    assert TOPICS_A and TOPICS_B
