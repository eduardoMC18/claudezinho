"""Provedor Gemini: SDK oficial `google-genai` contra o servidor falso, de ponta a ponta pelo chat."""

from pathlib import Path

from fastapi.testclient import TestClient

from app.infra.secrets import MemorySecretStore, api_key_secret_name, redact
from tests.conftest import HEADERS, parse_sse
from tests.fake_gemini import key_from_headers

GEMINI_KEY = "AIzaSyA-CHAVE-GEMINI-DE-TESTE-1234567890"
ANTHROPIC_KEY = "sk-ant-api03-CHAVE-SECRETA-DE-TESTE-123456"

TOPICS_A = ["Introdução ao curso.", "A mitocôndria produz ATP na respiração celular.", "O núcleo guarda o DNA."]
TOPICS_B = ["Ecologia geral.", "Fotossíntese nos cloroplastos.", "Mitocôndrias em células vegetais produzem energia."]


def save_key(client: TestClient, provider: str = "gemini", key: str = GEMINI_KEY):
    return client.put(f"/settings/ai/providers/{provider}/key", json={"api_key": key}, headers=HEADERS)


def load_docs(client: TestClient, make_topic_pdf) -> None:
    for slot, name, topics in (("A", "bio.pdf", TOPICS_A), ("B", "plantas.pdf", TOPICS_B)):
        client.put(f"/workspace/slots/{slot}", json={"path": str(make_topic_pdf(name, topics))}, headers=HEADERS)


def ask(client: TestClient, content: str, **extra):
    return client.post("/chat/stream", json={"content": content, "provider": "gemini", **extra}, headers=HEADERS)


def events_of(response):
    assert response.status_code == 200, response.text
    return parse_sse(response.text)


def test_gemini_appears_in_settings_with_its_own_key(client: TestClient, secrets: MemorySecretStore) -> None:
    overview = client.get("/settings/ai", headers=HEADERS).json()
    by_id = {p["id"]: p for p in overview["providers"]}
    assert set(by_id) == {"anthropic", "gemini"}
    assert by_id["gemini"]["display_name"] == "Gemini (Google)"
    assert by_id["gemini"]["selected_model"] == "gemini-3.5-flash-lite"
    assert {m["id"] for m in by_id["gemini"]["models"]} >= {"gemini-3.5-flash-lite", "gemini-4-hydrogen"}

    save_key(client)
    after = {p["id"]: p for p in client.get("/settings/ai", headers=HEADERS).json()["providers"]}
    assert after["gemini"]["has_key"] is True and after["anthropic"]["has_key"] is False   # chaves independentes
    assert secrets.get(api_key_secret_name("gemini")) == GEMINI_KEY
    assert GEMINI_KEY not in client.get("/settings/ai", headers=HEADERS).text


def test_gemini_can_be_default_provider_and_model(client: TestClient) -> None:
    result = client.put("/settings/ai", json={"default_provider": "gemini",
                                              "models": {"gemini": "gemini-3.8-flash"}}, headers=HEADERS).json()
    assert result["default_provider"] == "gemini"
    gemini = next(p for p in result["providers"] if p["id"] == "gemini")
    assert gemini["selected_model"] == "gemini-3.8-flash"
    assert client.put("/settings/ai", json={"models": {"gemini": "claude-opus-5-5"}}, headers=HEADERS).status_code == 404


def test_connection_test_uses_typed_key_without_saving(client: TestClient, fake_gemini, secrets) -> None:
    ok = client.post("/settings/ai/providers/gemini/test", json={"api_key": GEMINI_KEY}, headers=HEADERS).json()
    assert ok["ok"] is True and "gemini-3.5-flash-lite" in ok["message"]
    sent = fake_gemini.requests[-1]
    assert key_from_headers(sent["headers"]) == GEMINI_KEY
    assert ":generateContent" in sent["path"] and "gemini-3.5-flash-lite" in sent["path"]
    assert secrets.get(api_key_secret_name("gemini")) is None

    bad = client.post("/settings/ai/providers/gemini/test", json={"api_key": "ERRO401"}, headers=HEADERS).json()
    assert bad["ok"] is False and "inválida" in bad["message"]
    none = client.post("/settings/ai/providers/gemini/test", json={}, headers=HEADERS).json()
    assert none["ok"] is False


def test_chat_with_gemini_streams_validated_citations(client: TestClient, make_topic_pdf, fake_gemini,
                                                      fake_anthropic) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    events = events_of(ask(client, "Compare como os dois documentos falam da mitocôndria. INVENTA"))
    assert [n for n, _ in events][0] == "meta" and events[-1][0] == "done"

    done = events[-1][1]["message"]
    assert done["provider"] == "gemini" and done["model"] == "gemini-3.5-flash-lite"
    assert "(bio.pdf, p. 2)" in done["content"] and "(plantas.pdf, p. 3)" in done["content"]
    assert "999" not in done["content"] and "[A p." not in done["content"]
    assert {(r["slot"], r["page"]) for r in done["references"]} == {("A", 2), ("B", 3)}
    # uso: entrada 410; saída = 60 (resposta) + 25 (raciocínio interno, cobrado como saída)
    assert done["usage"]["input_tokens"] == 410 and done["usage"]["output_tokens"] == 85
    assert done["usage"]["cost_usd"] == (410 * 0.30 + 85 * 2.50) / 1_000_000
    assert done["usage"]["invalid_citations_removed"] == 1

    assert fake_anthropic.requests == []                       # nada foi para a Anthropic
    sent = fake_gemini.requests[-1]
    assert key_from_headers(sent["headers"]) == GEMINI_KEY
    assert ":streamGenerateContent" in sent["path"] and "gemini-3.5-flash-lite" in sent["path"]
    body = sent["body"]
    assert "estudoTron" in body["systemInstruction"]["parts"][0]["text"]
    assert body["generationConfig"]["maxOutputTokens"] == 16000
    user_turn = body["contents"][-1]["parts"][0]["text"]
    assert '<trecho ref="A p.2">' in user_turn and '<trecho ref="B p.3">' in user_turn


def test_follow_up_sends_history_with_gemini_roles(client: TestClient, make_topic_pdf, fake_gemini) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    events_of(ask(client, "O que é a mitocôndria?"))
    events_of(ask(client, "E no segundo documento?"))
    contents = fake_gemini.requests[-1]["body"]["contents"]
    assert [c["role"] for c in contents] == ["user", "model", "user"]    # "model", não "assistant"
    assert "[A p.2]" in contents[1]["parts"][0]["text"] and "](ref:" not in contents[1]["parts"][0]["text"]


def test_chat_picks_the_default_provider_and_each_message_records_it(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    save_key(client, "anthropic", ANTHROPIC_KEY)
    load_docs(client, make_topic_pdf)
    client.put("/settings/ai", json={"default_provider": "gemini"}, headers=HEADERS)
    first = events_of(client.post("/chat/stream", json={"content": "mitocôndria?"}, headers=HEADERS))
    assert first[-1][1]["message"]["provider"] == "gemini"
    second = events_of(client.post("/chat/stream", json={"content": "mitocôndria?", "provider": "anthropic"},
                                   headers=HEADERS))
    assert second[-1][1]["message"]["provider"] == "anthropic"
    stored = client.get("/conversations/current", headers=HEADERS).json()["messages"]
    assert [m["provider"] for m in stored if m["role"] == "assistant"] == ["gemini", "anthropic"]


def test_missing_gemini_key_is_reported_before_anything_is_saved(client: TestClient, make_topic_pdf) -> None:
    load_docs(client, make_topic_pdf)
    r = ask(client, "mitocôndria?")
    assert r.status_code == 400 and r.json()["detail"]["kind"] == "no_key"
    assert "Gemini" in r.json()["detail"]["message"]
    assert client.get("/conversations/current", headers=HEADERS).json()["messages"] == []


def test_gemini_errors_are_translated_and_saved(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    for trigger, kind in (("ERRO401", "auth"), ("ERRO429", "rate_limit"), ("ERRO529", "overloaded")):
        name, data = events_of(ask(client, f"mitocôndria {trigger}"))[-1]
        assert name == "error" and data["kind"] == kind and data["message"], trigger
        assert data["assistant_message"]["status"] == "error"


def test_transient_503_is_retried_and_the_answer_still_arrives(client: TestClient, make_topic_pdf, fake_gemini) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    events = events_of(ask(client, "mitocôndria FLAKY"))
    assert events[-1][0] == "done" and events[-1][1]["message"]["content"]
    stream_calls = [r for r in fake_gemini.requests if ":streamGenerateContent" in r["path"]]
    assert len(stream_calls) == 3                      # 2 falhas (503) + 1 sucesso


def test_persistent_overload_reports_a_helpful_message(client: TestClient, make_topic_pdf, fake_gemini) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    name, data = events_of(ask(client, "mitocôndria ERRO529"))[-1]
    assert name == "error" and data["kind"] == "overloaded"
    assert "503" in data["message"] and "troque o modelo" in data["message"]
    assert "overloaded" in data["message"].lower()       # mostra o motivo que o Google informou


def test_gemini_safety_block_is_a_refusal_and_keeps_partial_text(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    name, data = events_of(ask(client, "mitocôndria RECUSA"))[-1]
    assert name == "error" and data["kind"] == "refused"
    assert data["assistant_message"]["content"]


def test_cancel_stops_a_slow_gemini_answer(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    service = client.app.state.chat
    stream = service.run(service.prepare("mitocôndria LENTO", None, "gemini", None))
    meta = next(stream)
    assert next(stream).type == "delta"
    assert service.cancel(meta.data["assistant_message_id"]) is True
    final = list(stream)[-1].data["message"]
    assert final["status"] == "cancelled" and final["content"] and final["usage"] is None


def test_google_keys_are_redacted_from_logs() -> None:
    assert redact(f"falhou com {GEMINI_KEY} no header") == "falhou com AIza*** no header"
    assert redact(f"{ANTHROPIC_KEY} e {GEMINI_KEY}") == "sk-ant-*** e AIza***"


def test_gemini_key_is_not_stored_in_sqlite(client: TestClient, db_path: Path) -> None:
    save_key(client)
    assert GEMINI_KEY.encode() not in db_path.read_bytes()
