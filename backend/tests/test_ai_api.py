"""Configurações de IA, segurança das chaves e o fluxo completo do chat (servidor Anthropic falso)."""

import logging
from pathlib import Path

from fastapi.testclient import TestClient

from app.infra.log_safety import RedactSecretsFilter
from app.infra.secrets import MemorySecretStore, api_key_secret_name, redact
from tests.conftest import API_KEY, HEADERS, parse_sse
from tests.fake_anthropic import key_from_headers

TOPICS_A = ["Introdução ao curso.", "A mitocôndria produz ATP na respiração celular.", "O núcleo guarda o DNA."]
TOPICS_B = ["Ecologia geral.", "Fotossíntese nos cloroplastos.", "Mitocôndrias em células vegetais produzem energia."]


def put_slot(client: TestClient, slot: str, path: Path):
    return client.put(f"/workspace/slots/{slot}", json={"path": str(path)}, headers=HEADERS)


def save_key(client: TestClient, key: str = API_KEY):
    return client.put("/settings/ai/providers/anthropic/key", json={"api_key": key}, headers=HEADERS)


def load_docs(client: TestClient, make_topic_pdf) -> None:
    put_slot(client, "A", make_topic_pdf("bio.pdf", TOPICS_A))
    put_slot(client, "B", make_topic_pdf("plantas.pdf", TOPICS_B))


def ask(client: TestClient, content: str, **extra):
    response = client.post("/chat/stream", json={"content": content, **extra}, headers=HEADERS)
    return response


def events_of(response) -> list[tuple[str, dict]]:
    assert response.status_code == 200, response.text
    return parse_sse(response.text)


# ---------------------------------------------------------------- configurações / chaves

def test_settings_overview_never_contains_keys(client: TestClient, secrets: MemorySecretStore) -> None:
    before = client.get("/settings/ai", headers=HEADERS).json()
    assert before["default_provider"] == "anthropic"
    provider = before["providers"][0]
    assert provider["has_key"] is False
    assert {m["id"] for m in provider["models"]} >= {"claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5"}

    assert save_key(client).status_code == 204
    after = client.get("/settings/ai", headers=HEADERS)
    assert after.json()["providers"][0]["has_key"] is True
    assert API_KEY not in after.text
    assert secrets.get(api_key_secret_name("anthropic")) == API_KEY   # foi para o cofre


def test_key_is_not_stored_in_sqlite(client: TestClient, db_path: Path) -> None:
    save_key(client)
    assert API_KEY.encode() not in db_path.read_bytes()
    wal = db_path.with_name(db_path.name + "-wal")
    if wal.exists():
        assert API_KEY.encode() not in wal.read_bytes()


def test_key_validation_and_removal(client: TestClient, secrets: MemorySecretStore) -> None:
    assert save_key(client, "   ").status_code == 400
    assert save_key(client, 'sk-ant "com aspas"').status_code == 400
    assert client.put("/settings/ai/providers/nope/key", json={"api_key": "x"}, headers=HEADERS).status_code == 404
    save_key(client)
    assert client.delete("/settings/ai/providers/anthropic/key", headers=HEADERS).status_code == 204
    assert client.get("/settings/ai", headers=HEADERS).json()["providers"][0]["has_key"] is False
    assert secrets.get(api_key_secret_name("anthropic")) is None


def test_preferences_are_validated_and_persisted(client: TestClient) -> None:
    ok = client.put("/settings/ai", json={"default_provider": "anthropic",
                                          "models": {"anthropic": "claude-haiku-5-5"}}, headers=HEADERS)
    assert ok.json()["providers"][0]["selected_model"] == "claude-haiku-5-5"
    bad = client.put("/settings/ai", json={"models": {"anthropic": "modelo-que-nao-existe"}}, headers=HEADERS)
    assert bad.status_code == 404
    assert client.put("/settings/ai", json={"default_provider": "x"}, headers=HEADERS).status_code == 404


def test_connection_test_uses_typed_key_without_saving_it(client: TestClient, fake_anthropic, secrets) -> None:
    result = client.post("/settings/ai/providers/anthropic/test", json={"api_key": API_KEY}, headers=HEADERS).json()
    assert result["ok"] is True and "claude-opus-5-5" in result["message"]
    assert key_from_headers(fake_anthropic.requests[-1]["headers"]) == API_KEY
    assert secrets.get(api_key_secret_name("anthropic")) is None

    nothing = client.post("/settings/ai/providers/anthropic/test", json={}, headers=HEADERS).json()
    assert nothing["ok"] is False and "chave" in nothing["message"].lower()

    bad = client.post("/settings/ai/providers/anthropic/test", json={"api_key": "ERRO401"}, headers=HEADERS).json()
    assert bad["ok"] is False and "inválida" in bad["message"]


def test_redaction_helpers() -> None:
    assert redact(f"falhou com {API_KEY} no header") == "falhou com sk-ant-*** no header"
    record = logging.LogRecord("x", logging.ERROR, "f", 1, "erro %s", (API_KEY,), None)
    RedactSecretsFilter().filter(record)
    assert API_KEY not in record.getMessage()


def test_request_models_never_print_the_key() -> None:
    from app.api.ai_settings import KeyRequest, TestRequest
    assert API_KEY not in repr(KeyRequest(api_key=API_KEY))
    assert API_KEY not in repr(TestRequest(api_key=API_KEY))


# ---------------------------------------------------------------- erros antes de começar

def test_chat_without_documents_or_key_is_rejected_and_nothing_is_saved(client: TestClient, make_topic_pdf) -> None:
    r = ask(client, "olá")
    assert r.status_code == 400 and r.json()["detail"]["kind"] == "no_documents"

    load_docs(client, make_topic_pdf)
    r = ask(client, "olá")
    assert r.status_code == 400 and r.json()["detail"]["kind"] == "no_key"
    assert ask(client, "   ").json()["detail"]["kind"] == "empty"
    current = client.get("/conversations/current", headers=HEADERS).json()
    assert current["messages"] == []   # nada foi gravado


def test_chat_waits_for_indexing_and_rejects_scanned_pdfs(client: TestClient, make_pdf, secrets) -> None:
    save_key(client)
    put_slot(client, "A", make_pdf("escaneado.pdf", text=False))
    r = ask(client, "do que trata?")
    assert r.json()["detail"]["kind"] == "no_text"


# ---------------------------------------------------------------- fluxo completo

def test_full_chat_flow_streams_validated_citations(client: TestClient, make_topic_pdf, fake_anthropic) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    response = ask(client, "Compare como os dois documentos falam da mitocôndria. INVENTA")
    events = events_of(response)
    names = [n for n, _ in events]
    assert names[0] == "meta" and names[-1] == "done" and "delta" in names

    streamed = "".join(d["text"] for n, d in events if n == "delta")
    done = events[-1][1]["message"]
    assert done["content"] == streamed                       # o que foi transmitido é o que foi salvo
    assert done["status"] == "complete" and done["role"] == "assistant"
    assert done["provider"] == "anthropic" and done["model"] == "claude-opus-5-5"
    assert "[A p." not in done["content"] and "[B p." not in done["content"]   # rótulos viraram links
    assert "(bio.pdf, p. 2)" in done["content"] and "(plantas.pdf, p. 3)" in done["content"]
    assert "999" not in done["content"]                      # página inventada removida
    refs = {(r["slot"], r["page"], r["file_name"]) for r in done["references"]}
    assert refs == {("A", 2, "bio.pdf"), ("B", 3, "plantas.pdf")}
    assert done["usage"]["input_tokens"] == 321 and done["usage"]["output_tokens"] == 77
    assert done["usage"]["invalid_citations_removed"] == 1
    assert done["usage"]["cost_usd"] > 0

    # O que foi ENVIADO ao provedor: pergunta + trechos com páginas reais, nunca o PDF inteiro.
    sent = fake_anthropic.requests[-1]
    assert key_from_headers(sent["headers"]) == API_KEY
    assert "temperature" not in sent["body"] and sent["body"]["output_config"] == {"effort": "medium"}
    user_turn = sent["body"]["messages"][-1]["content"]
    assert '<trecho ref="A p.2">' in user_turn and '<trecho ref="B p.3">' in user_turn
    assert "<documentos>" in user_turn and "bio.pdf" in user_turn and "plantas.pdf" in user_turn
    assert "estudoTron" in sent["body"]["system"]


def test_history_is_stored_and_follow_up_gets_previous_turns(client: TestClient, make_topic_pdf, fake_anthropic) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    events_of(ask(client, "O que é a mitocôndria?"))
    events_of(ask(client, "E no segundo documento?"))

    current = client.get("/conversations/current", headers=HEADERS).json()
    roles = [m["role"] for m in current["messages"]]
    assert roles == ["user", "assistant", "user", "assistant"]
    assert current["title"].startswith("O que é a mitocôndria")
    first = current["messages"][0]
    assert first["id"] and first["created_at"].endswith("+00:00") and first["content"] == "O que é a mitocôndria?"

    body = fake_anthropic.requests[-1]["body"]
    contents = [m["content"] for m in body["messages"]]
    assert len(body["messages"]) == 3                    # pergunta anterior + resposta anterior + pergunta atual
    assert contents[0] == "O que é a mitocôndria?"
    assert "[A p.2]" in contents[1]                      # histórico volta aos rótulos curtos, sem links
    assert "](ref:" not in contents[1]


def test_new_conversation_starts_empty_and_keeps_old_one(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    events_of(ask(client, "O que é a mitocôndria?"))
    old = client.get("/conversations/current", headers=HEADERS).json()["id"]

    created = client.post("/conversations", headers=HEADERS)
    assert created.status_code == 201 and created.json()["messages"] == []
    now = client.get("/conversations/current", headers=HEADERS).json()
    assert now["id"] == created.json()["id"] != old and now["messages"] == []

    listing = client.get("/conversations", headers=HEADERS).json()
    assert {c["id"] for c in listing} == {old, now["id"]}
    assert len(client.get(f"/conversations/{old}/messages", headers=HEADERS).json()) == 2


def test_model_choice_and_fallback_beta_come_from_the_catalog(client: TestClient, make_topic_pdf, fake_anthropic) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    events_of(ask(client, "mitocôndria?"))
    opus = fake_anthropic.requests[-1]
    assert opus["body"]["model"] == "claude-opus-5-5"
    assert "server-side-fallback-2026-07-01" in opus["headers"].get("anthropic-beta", "")
    assert opus["body"]["fallbacks"] == "default"

    client.put("/settings/ai", json={"models": {"anthropic": "claude-haiku-5-5"}}, headers=HEADERS)
    events_of(ask(client, "mitocôndria?"))
    haiku = fake_anthropic.requests[-1]
    assert haiku["body"]["model"] == "claude-haiku-5-5" and "fallbacks" not in haiku["body"]

    events_of(ask(client, "mitocôndria?", model="claude-sonnet-5-5"))      # escolha pontual no chat
    assert fake_anthropic.requests[-1]["body"]["model"] == "claude-sonnet-5-5"
    assert ask(client, "x", model="modelo-falso").json()["detail"]["kind"] == "bad_model"


# ---------------------------------------------------------------- falhas e interrupção

def test_provider_errors_are_saved_and_shown(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    for trigger, kind in (("ERRO401", "auth"), ("ERRO429", "rate_limit"), ("ERRO529", "overloaded")):
        events = events_of(ask(client, f"mitocôndria {trigger}"))
        name, data = events[-1]
        assert name == "error" and data["kind"] == kind and data["message"]
        assert data["assistant_message"]["status"] == "error"
    stored = client.get("/conversations/current", headers=HEADERS).json()["messages"]
    assert [m["status"] for m in stored if m["role"] == "assistant"] == ["error"] * 3
    # mensagens com erro não voltam como contexto nas próximas perguntas
    events_of(ask(client, "mitocôndria de novo"))


def test_refusal_is_reported_as_error_with_partial_text_kept(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    events = events_of(ask(client, "mitocôndria RECUSA"))
    name, data = events[-1]
    assert name == "error" and data["kind"] == "refused"
    assert data["assistant_message"]["content"]   # o trecho parcial foi preservado


def test_cancel_stops_the_answer_and_keeps_partial_text(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    service = client.app.state.chat
    prepared = service.prepare("mitocôndria LENTO", None, None, None)
    stream = service.run(prepared)
    meta = next(stream)
    assert meta.type == "meta"
    assistant_id = meta.data["assistant_message_id"]

    assert next(stream).type == "delta"
    assert service.cancel(assistant_id) is True
    rest = list(stream)
    assert rest[-1].type == "done"
    final = rest[-1].data["message"]
    assert final["status"] == "cancelled" and final["content"]
    assert final["usage"] is None            # sem números de uso inventados para respostas interrompidas
    stored = client.get("/conversations/current", headers=HEADERS).json()["messages"][-1]
    assert stored["status"] == "cancelled"
    assert service.cancel(assistant_id) is False   # já terminou


def test_client_disconnect_marks_message_cancelled(client: TestClient, make_topic_pdf) -> None:
    save_key(client)
    load_docs(client, make_topic_pdf)
    service = client.app.state.chat
    stream = service.run(service.prepare("mitocôndria LENTO", None, None, None))
    next(stream)
    next(stream)
    stream.close()                                   # conexão fechada pela interface
    stored = client.get("/conversations/current", headers=HEADERS).json()["messages"][-1]
    assert stored["status"] == "cancelled"


def test_interrupted_answers_from_a_crash_are_recovered_on_startup(client: TestClient, make_topic_pdf, db_path,
                                                                    secrets, fake_anthropic) -> None:
    from app.domain.models import DEFAULT_WORKSPACE_ID
    from app.infra.conversations_repo import StoredMessage, now_iso
    from tests.conftest import build_app

    repo = client.app.state.conversations
    conv = repo.create(DEFAULT_WORKSPACE_ID)
    repo.add_message(StoredMessage("m1", conv.id, "assistant", "parcial", now_iso(), status="streaming"))
    restarted = TestClient(build_app(db_path, secrets, fake_anthropic))
    assert restarted.app.state.conversations.messages(conv.id)[0].status == "cancelled"
