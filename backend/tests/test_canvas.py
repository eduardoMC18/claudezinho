"""Integração com o Canvas LMS, testada contra o Canvas FALSO (nenhuma conta real é tocada)."""

import json
from collections.abc import Iterator
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.infra.secrets import MemorySecretStore, redact
from tests.conftest import HEADERS, build_app
from tests.fake_canvas import TOKEN_OK, FakeCanvasServer, make_pdf_bytes


@pytest.fixture()
def canvas() -> Iterator[FakeCanvasServer]:
    server = FakeCanvasServer().start()
    yield server
    server.stop()


@pytest.fixture()
def app_client(db_path, secrets, fake_anthropic, fake_gemini) -> TestClient:
    c = TestClient(build_app(db_path, secrets, fake_anthropic, fake_gemini))
    c.app.state.canvas._retry_wait = 0.0
    return c


@pytest.fixture()
def conn(app_client: TestClient, canvas: FakeCanvasServer) -> TestClient:
    """App com a conta do Canvas já conectada."""
    r = app_client.put("/canvas/connection", json={"base_url": canvas.base_url, "token": TOKEN_OK}, headers=HEADERS)
    assert r.status_code == 200, r.text
    return app_client


def get(c: TestClient, url: str):
    return c.get(url, headers=HEADERS)


def open_pdf(c: TestClient, slot: str, course: int, module: str, file_id: int):
    return c.put(f"/canvas/slots/{slot}", headers=HEADERS,
                 json={"course_id": course, "module_id": module, "file_id": file_id,
                       "course_name": "Curso", "module_name": "Módulo"})


# ---------------------------------------------------------------- autenticação e segurança

def test_connect_stores_token_only_in_secret_store(app_client, canvas, secrets: MemorySecretStore, db_path: Path) -> None:
    r = app_client.put("/canvas/connection", json={"base_url": canvas.base_url, "token": TOKEN_OK}, headers=HEADERS)
    assert r.status_code == 200
    body = r.json()
    assert body["connected"] is True and body["user_name"] == "Aluna de Teste"
    assert TOKEN_OK not in r.text and TOKEN_OK not in get(app_client, "/canvas/status").text
    assert secrets.get("canvas-token") == TOKEN_OK
    assert TOKEN_OK.encode() not in db_path.read_bytes()  # nunca no SQLite
    assert any(q["auth"] == f"Bearer {TOKEN_OK}" for q in canvas.requests)


def test_connect_rejects_invalid_token_and_stores_nothing(app_client, canvas, secrets) -> None:
    r = app_client.put("/canvas/connection", json={"base_url": canvas.base_url, "token": "tokeninvalido123"}, headers=HEADERS)
    assert r.status_code == 401 and r.json()["detail"]["kind"] == "auth"
    assert not secrets.has("canvas-token")
    assert get(app_client, "/canvas/status").json()["connected"] is False


@pytest.mark.parametrize("url", ["http://faculdade.instructure.com", "https://user:pw@faculdade.instructure.com", "", "ftp://x.com"])
def test_connect_rejects_unsafe_base_urls(app_client, url) -> None:
    r = app_client.put("/canvas/connection", json={"base_url": url, "token": TOKEN_OK}, headers=HEADERS)
    assert r.status_code == 400 and r.json()["detail"]["kind"] == "bad_request"


def test_connect_unreachable_host_is_network_error(app_client) -> None:
    r = app_client.put("/canvas/connection", json={"base_url": "http://127.0.0.1:9", "token": TOKEN_OK}, headers=HEADERS)
    assert r.status_code == 502 and r.json()["detail"]["kind"] == "network"


def test_canvas_endpoints_require_session_token(app_client) -> None:
    assert app_client.get("/canvas/status").status_code == 401
    assert app_client.get("/canvas/courses").status_code == 401


def test_not_connected_is_clear_error(app_client) -> None:
    r = get(app_client, "/canvas/courses")
    assert r.status_code == 409 and r.json()["detail"]["kind"] == "not_connected"


def test_redaction_masks_canvas_token_and_bearer() -> None:
    assert TOKEN_OK not in redact(f"token {TOKEN_OK}")
    assert "SEGREDO" not in redact("Authorization: Bearer SEGREDOSEGREDOSEGREDO123456")


def test_disconnect_wipes_token_cache_slots_and_files(conn, canvas, secrets, db_path: Path, make_pdf) -> None:
    assert open_pdf(conn, "A", 101, "1001", 5001).status_code == 200
    local = conn.put("/workspace/slots/B", json={"path": str(make_pdf("local.pdf"))}, headers=HEADERS)
    assert local.status_code == 200
    cache = db_path.parent / "canvas_cache"
    assert list(cache.glob("*.pdf"))
    assert conn.delete("/canvas/connection", headers=HEADERS).status_code == 204
    assert not secrets.has("canvas-token")
    status = get(conn, "/canvas/status").json()
    assert status["connected"] is False and status["base_url"] in ("", None)
    assert not list(cache.glob("*"))
    slots = get(conn, "/workspace").json()["slots"]
    assert slots["A"] is None and slots["B"]["name"] == "local.pdf"  # PDF local não é afetado


# ---------------------------------------------------------------- disciplinas

def test_courses_filter_inaccessible_and_expose_metadata(conn) -> None:
    data = get(conn, "/canvas/courses").json()["data"]
    names = [c["name"] for c in data]
    assert names == ["Cálculo I", "Curso Vazio", "Física I"]  # o curso sem nome (restrito) fica de fora
    calc = next(c for c in data if c["id"] == 101)
    assert calc == {"id": 101, "name": "Cálculo I", "short_name": "MAT101", "code": "2026-1-MAT101",
                    "published": True, "state": "available", "term": "2026/1", "image_url": None}
    assert next(c for c in data if c["id"] == 103)["published"] is False


@pytest.mark.parametrize("cap", [2, 100])
def test_courses_pagination_returns_everything(app_client, canvas, cap) -> None:
    canvas.per_page_cap = cap
    canvas.extra_courses = 250
    app_client.put("/canvas/connection", json={"base_url": canvas.base_url, "token": TOKEN_OK}, headers=HEADERS)
    data = get(app_client, "/canvas/courses").json()["data"]
    assert len(data) == 3 + 250
    assert len({c["id"] for c in data}) == len(data)
    pages = [q for q in canvas.requests if q["path"] == "/api/v1/courses"]
    assert len(pages) >= (253 // cap)


def test_courses_cached_until_manual_refresh(conn, canvas) -> None:
    get(conn, "/canvas/courses")
    before = sum(1 for q in canvas.requests if q["path"] == "/api/v1/courses")
    cached = get(conn, "/canvas/courses").json()
    assert sum(1 for q in canvas.requests if q["path"] == "/api/v1/courses") == before
    assert cached["stale"] is False
    canvas.courses.append({"id": 105, "name": "Álgebra", "course_code": "ALG", "workflow_state": "available"})
    assert len(get(conn, "/canvas/courses").json()["data"]) == 3  # ainda o cache
    assert len(get(conn, "/canvas/courses?refresh=true").json()["data"]) == 4


def test_courses_fall_back_to_stale_cache_when_offline(conn, canvas) -> None:
    get(conn, "/canvas/courses")
    canvas.stop()
    r = get(conn, "/canvas/courses?refresh=true")
    assert r.status_code == 200
    body = r.json()
    assert body["stale"] is True and body["warning"] and len(body["data"]) == 3


def test_courses_offline_without_cache_is_error(conn, canvas) -> None:
    canvas.stop()
    r = get(conn, "/canvas/courses")
    assert r.status_code == 502 and r.json()["detail"]["kind"] == "network"


def test_expired_session_returns_auth_error(conn, canvas) -> None:
    canvas.expired = True
    r = get(conn, "/canvas/courses?refresh=true")
    assert r.status_code == 401 and r.json()["detail"]["kind"] == "auth"


def test_rate_limit_retries_once_then_reports(conn, canvas) -> None:
    canvas.rate_limit_hits = 1
    assert get(conn, "/canvas/courses").status_code == 200  # a nova tentativa passou
    canvas.rate_limit_hits = 5
    stale = get(conn, "/canvas/courses?refresh=true").json()  # há cache: mostra o que já se sabe, avisando
    assert stale["stale"] is True and "limitou" in stale["warning"]
    canvas.rate_limit_hits = 5
    r = get(conn, "/canvas/courses/101/modules")  # sem cache: erro claro
    assert r.status_code == 429 and r.json()["detail"]["kind"] == "rate_limit"


# ---------------------------------------------------------------- módulos e itens

def test_modules_come_from_canvas_with_course_files_entry(conn) -> None:
    mods = get(conn, "/canvas/courses/101/modules").json()["data"]
    assert [m["name"] for m in mods[:3]] == ["Módulo 1 — Introdução", "Módulo 2 — Limites", "Módulo 3 — Derivadas"]
    assert mods[2]["locked"] is True
    assert mods[-1]["id"] == "files" and mods[-1]["kind"] == "files"


def test_course_without_modules_still_offers_course_files(conn) -> None:
    mods = get(conn, "/canvas/courses/103/modules").json()["data"]
    assert [m["id"] for m in mods] == ["files"]


def test_unknown_course_is_not_found(conn) -> None:
    r = get(conn, "/canvas/courses/999/modules")
    assert r.status_code == 404 and r.json()["detail"]["kind"] == "not_found"


def test_items_identify_pdfs_and_skip_other_types(conn) -> None:
    items = get(conn, "/canvas/courses/101/modules/1001/items").json()["data"]
    by_key = {i["key"]: i for i in items}
    assert by_key["file:5001"]["kind"] == "pdf" and by_key["file:5001"]["available"] is True
    assert by_key["file:5002"]["kind"] == "file" and by_key["file:5002"]["available"] is False
    assert by_key["file:5002"]["reason"] == "Não é um PDF"
    assert by_key["file:5004"]["available"] is False and "bloqueado" in by_key["file:5004"]["reason"].lower()
    kinds = {i["kind"] for i in items}
    assert {"header", "page", "link", "other", "pdf", "file"} <= kinds
    assert all(i["available"] is False for i in items if i["kind"] in ("header", "page", "link", "other"))
    assert [i["file_id"] for i in items if i["file_id"] == 5001] == [5001]  # arquivo repetido aparece uma vez


def test_items_find_pdfs_referenced_by_module_pages(conn) -> None:
    items = get(conn, "/canvas/courses/101/modules/1001/items").json()["data"]
    linked = next(i for i in items if i["file_id"] == 5003)
    assert linked["kind"] == "pdf" and linked["available"] is True
    assert linked["via"] and "Leituras" in linked["via"]
    assert linked["title"] == "Lista de exercícios.pdf"


def test_course_files_pseudo_module_lists_course_pdfs(conn) -> None:
    items = get(conn, "/canvas/courses/101/modules/files/items").json()["data"]
    assert {i["file_id"] for i in items} == {5001, 5003, 5005, 7001}
    assert all(i["kind"] == "pdf" and i["available"] for i in items)


def test_item_listing_survives_a_removed_file(conn, canvas) -> None:
    canvas.file_status[5001] = 404
    items = get(conn, "/canvas/courses/101/modules/1001/items?refresh=true").json()["data"]
    gone = next(i for i in items if i["file_id"] == 5001)
    assert gone["available"] is False and "removido" in gone["reason"].lower()


def test_empty_module(conn) -> None:
    assert get(conn, "/canvas/courses/101/modules/1003/items").json()["data"] == []


# ---------------------------------------------------------------- abrir PDFs

def test_open_pdf_creates_regular_indexed_document(conn, canvas, db_path: Path) -> None:
    r = open_pdf(conn, "A", 101, "1001", 5001)
    assert r.status_code == 200, r.text
    doc = r.json()
    assert doc["name"] == "Aula 01 - Introdução.pdf" and doc["slot"] == "A" and doc["pages"] == 3
    assert doc["source"] == "canvas" and doc["file_state"] == "ok"
    assert doc["canvas"]["course_id"] == 101 and doc["canvas"]["file_id"] == 5001
    assert doc["canvas"]["institution"] == "127.0.0.1"
    assert "url" not in json.dumps(doc) and TOKEN_OK not in json.dumps(doc)
    # indexação inline: o PDF remoto tem busca e páginas como um PDF local
    again = get(conn, f"/documents/{doc['id']}").json()
    assert again["status"] == "ready"
    repo = conn.app.state.workspace._repo
    hits = repo.search([doc["id"]], '"Introdução"')
    assert hits and hits[0][1] in (1, 2, 3)


def test_token_never_reaches_cdn_and_download_follows_redirect(conn, canvas) -> None:
    open_pdf(conn, "A", 101, "1001", 5001)
    cdn = [q for q in canvas.requests if q["path"].startswith("/cdn/")]
    assert cdn and all(q["auth"] is None for q in cdn)
    api = [q for q in canvas.requests if q["path"].startswith("/files/")]
    assert api and all(q["auth"] == f"Bearer {TOKEN_OK}" for q in api)


def test_pdf_is_streamed_with_range_support(conn) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    full = conn.get(f"/documents/{doc['id']}/file", headers=HEADERS)
    assert full.status_code == 200 and full.content[:5] == b"%PDF-"
    assert full.headers["accept-ranges"] == "bytes"
    part = conn.get(f"/documents/{doc['id']}/file", headers={**HEADERS, "Range": "bytes=0-99"})
    assert part.status_code == 206 and part.content == full.content[:100]
    assert part.headers["content-range"].startswith("bytes 0-99/")
    tail = conn.get(f"/documents/{doc['id']}/file", headers={**HEADERS, "Range": "bytes=-50"})
    assert tail.status_code == 206 and tail.content == full.content[-50:]
    assert conn.get(f"/documents/{doc['id']}/file").status_code == 401  # sem o token da sessão


def test_file_endpoint_does_not_use_canvas_token_from_client(conn, canvas) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    n = len(canvas.requests)
    conn.get(f"/documents/{doc['id']}/file", headers=HEADERS)
    assert len(canvas.requests) == n  # cache aquecido: nenhuma ida ao Canvas


def test_two_canvas_pdfs_side_by_side_from_different_courses(conn) -> None:
    a = open_pdf(conn, "A", 101, "1001", 5001).json()
    b = open_pdf(conn, "B", 102, "2001", 6001).json()
    slots = get(conn, "/workspace").json()["slots"]
    assert slots["A"]["id"] == a["id"] and slots["B"]["id"] == b["id"]
    assert slots["A"]["canvas"]["course_id"] == 101 and slots["B"]["canvas"]["course_id"] == 102
    # cada um serve o seu próprio conteúdo
    ca = conn.get(f"/documents/{a['id']}/file", headers=HEADERS).content
    cb = conn.get(f"/documents/{b['id']}/file", headers=HEADERS).content
    assert ca != cb


def test_two_pdfs_of_same_course_and_independent_replace_and_clear(conn, make_pdf) -> None:
    a = open_pdf(conn, "A", 101, "1001", 5001).json()
    b = open_pdf(conn, "B", 101, "1002", 5005).json()
    new_a = open_pdf(conn, "A", 101, "1001", 5003).json()  # substitui só o A
    slots = get(conn, "/workspace").json()["slots"]
    assert slots["A"]["id"] == new_a["id"] != a["id"] and slots["B"]["id"] == b["id"]
    assert conn.delete("/workspace/slots/A", headers=HEADERS).status_code == 204  # fecha só o A
    slots = get(conn, "/workspace").json()["slots"]
    assert slots["A"] is None and slots["B"]["id"] == b["id"]
    # e a nova navegação do A convive com um PDF local no B
    local = conn.put("/workspace/slots/B", json={"path": str(make_pdf("l.pdf"))}, headers=HEADERS).json()
    open_pdf(conn, "A", 102, "2001", 6001)
    slots = get(conn, "/workspace").json()["slots"]
    assert slots["B"]["id"] == local["id"] and slots["B"]["source"] == "local"


def test_replacing_a_canvas_pdf_removes_its_cached_file_and_chunks(conn, db_path: Path) -> None:
    a = open_pdf(conn, "A", 101, "1001", 5001).json()
    cache = db_path.parent / "canvas_cache"
    assert (cache / f"{a['id']}.pdf").is_file()
    open_pdf(conn, "A", 101, "1002", 5005)
    assert not (cache / f"{a['id']}.pdf").exists()
    repo = conn.app.state.workspace._repo
    assert repo.search([a["id"]], '"Introdução"') == []


@pytest.mark.parametrize("file_id,status,kind", [
    (5002, 422, "not_pdf"),       # não é PDF
    (5004, 403, "forbidden"),     # bloqueado
    (4040, 404, "not_found"),     # removido
])
def test_open_errors_are_clear(conn, file_id, status, kind) -> None:
    r = open_pdf(conn, "A", 101, "1001", file_id)
    assert r.status_code == status and r.json()["detail"]["kind"] == kind
    assert get(conn, "/workspace").json()["slots"]["A"] is None


def test_permission_denied_on_file_metadata(conn, canvas) -> None:
    canvas.file_status[5001] = 403
    r = open_pdf(conn, "A", 101, "1001", 5001)
    assert r.status_code == 403 and r.json()["detail"]["kind"] == "forbidden"


def test_download_expired_link_and_failed_transfer_leave_no_partial_file(conn, canvas, db_path: Path) -> None:
    canvas.download_status[5001] = 403  # URL assinada expirada
    r = open_pdf(conn, "A", 101, "1001", 5001)
    assert r.status_code == 403
    canvas.download_status.clear()
    canvas.truncate_download.add(5001)
    r = open_pdf(conn, "A", 101, "1001", 5001)
    assert r.status_code == 502 and r.json()["detail"]["kind"] == "network"
    assert not list((db_path.parent / "canvas_cache").glob("*"))
    assert get(conn, "/workspace").json()["slots"]["A"] is None


def test_file_that_is_not_really_a_pdf_is_rejected(conn, canvas, db_path: Path) -> None:
    canvas.add_file(8001, "Falso.pdf", data=b"<html>nao sou pdf</html>")
    r = open_pdf(conn, "A", 101, "1001", 8001)
    assert r.status_code == 422 and r.json()["detail"]["kind"] == "not_pdf"
    assert not list((db_path.parent / "canvas_cache").glob("*"))


def test_incomplete_api_response(conn, canvas) -> None:
    canvas.files[8002] = {"display_name": "sem id"}  # resposta sem o campo id
    canvas.file_status[8002] = 200
    r = open_pdf(conn, "A", 101, "1001", 8002)
    assert r.status_code in (404, 502)


# ---------------------------------------------------------------- cache temporário, atualização e sessão

def test_cleared_cache_is_downloaded_again_on_demand(conn, db_path: Path) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    for f in (db_path.parent / "canvas_cache").glob("*.pdf"):
        f.unlink()
    r = conn.get(f"/documents/{doc['id']}/file", headers=HEADERS)
    assert r.status_code == 200 and r.content[:5] == b"%PDF-"
    assert get(conn, f"/documents/{doc['id']}").json()["status"] == "ready"


def test_sweep_enforces_size_limit_age_and_orphans(conn, db_path: Path) -> None:
    import os
    import time
    a = open_pdf(conn, "A", 101, "1001", 5001).json()
    b = open_pdf(conn, "B", 101, "1002", 5005).json()
    cache = db_path.parent / "canvas_cache"
    (cache / "orfao.pdf").write_bytes(b"%PDF-1.4 lixo")
    svc = conn.app.state.canvas
    svc.sweep()
    assert not (cache / "orfao.pdf").exists()  # órfão
    old = time.time() - 100 * 24 * 3600
    os.utime(cache / f"{a['id']}.pdf", (old, old))
    svc.sweep()
    assert not (cache / f"{a['id']}.pdf").exists() and (cache / f"{b['id']}.pdf").exists()  # expirou por idade
    svc.sweep(max_bytes=1)
    assert not list(cache.glob("*.pdf")) or len(list(cache.glob("*.pdf"))) <= 1  # limite de tamanho
    # tudo que foi limpo volta sozinho ao abrir
    assert conn.get(f"/documents/{a['id']}/file", headers=HEADERS).status_code == 200


def test_remote_update_is_detected_and_reindexed(conn, canvas) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    ok = conn.post(f"/canvas/documents/{doc['id']}/prepare", headers=HEADERS).json()
    assert ok["ok"] is True and ok["update_available"] is False
    assert conn.post(f"/canvas/documents/{doc['id']}/refresh", headers=HEADERS).json()["updated"] is False

    canvas.update_file(5001, make_pdf_bytes("Termodinamica avancada", pages=5), "2026-04-01T09:00:00Z")
    assert conn.post(f"/canvas/documents/{doc['id']}/prepare", headers=HEADERS).json()["update_available"] is True
    assert conn.post(f"/canvas/documents/{doc['id']}/refresh", headers=HEADERS).json()["updated"] is True

    after = get(conn, f"/documents/{doc['id']}").json()
    assert after["pages"] == 5 and after["status"] == "ready"
    assert after["canvas"]["remote_modified_at"] == "2026-04-01T09:00:00Z"
    repo = conn.app.state.workspace._repo
    assert repo.search([doc["id"]], '"Termodinamica"')
    assert repo.search([doc["id"]], '"Introdução"') == []  # nada do texto antigo sobrou
    assert conn.post(f"/canvas/documents/{doc['id']}/prepare", headers=HEADERS).json()["update_available"] is False


def test_cache_miss_with_changed_remote_reindexes_automatically(conn, canvas, db_path: Path) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    for f in (db_path.parent / "canvas_cache").glob("*.pdf"):
        f.unlink()
    canvas.update_file(5001, make_pdf_bytes("Eletromagnetismo", pages=2), "2026-05-01T00:00:00Z")
    assert conn.get(f"/documents/{doc['id']}/file", headers=HEADERS).status_code == 200
    after = get(conn, f"/documents/{doc['id']}").json()
    assert after["pages"] == 2
    assert conn.app.state.workspace._repo.search([doc["id"]], '"Eletromagnetismo"')


def test_prepare_works_offline_when_file_is_cached_and_fails_when_not(conn, canvas, db_path: Path) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    canvas.stop()
    r = conn.post(f"/canvas/documents/{doc['id']}/prepare", headers=HEADERS)
    assert r.status_code == 200 and r.json()["ok"] is True and r.json()["warning"]
    for f in (db_path.parent / "canvas_cache").glob("*.pdf"):
        f.unlink()
    r = conn.post(f"/canvas/documents/{doc['id']}/prepare", headers=HEADERS)
    assert r.status_code == 502 and r.json()["detail"]["kind"] == "network"
    assert conn.get(f"/documents/{doc['id']}/file", headers=HEADERS).status_code == 502


def test_expired_auth_after_open_blocks_prepare_with_auth_error(conn, canvas) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    canvas.expired = True
    r = conn.post(f"/canvas/documents/{doc['id']}/prepare", headers=HEADERS)
    assert r.status_code == 401 and r.json()["detail"]["kind"] == "auth"


def test_file_removed_from_canvas_after_open(conn, canvas) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    canvas.file_status[5001] = 404
    r = conn.post(f"/canvas/documents/{doc['id']}/prepare", headers=HEADERS)
    assert r.status_code == 404 and r.json()["detail"]["kind"] == "not_found"
    # o que já está no disco continua legível (o leitor não trava)
    assert conn.get(f"/documents/{doc['id']}/file", headers=HEADERS).status_code == 200


def test_session_restored_after_restart_with_canvas_documents(conn, canvas, db_path, secrets, fake_anthropic, fake_gemini) -> None:
    a = open_pdf(conn, "A", 101, "1001", 5001).json()
    b = open_pdf(conn, "B", 102, "2001", 6001).json()
    assert conn.put(f"/documents/{a['id']}/progress", json={"page": 3}, headers=HEADERS).status_code == 204
    conn.close()

    reopened = TestClient(build_app(db_path, secrets, fake_anthropic, fake_gemini))  # novo processo, mesmo banco/cofre
    reopened.app.state.canvas._retry_wait = 0.0
    slots = get(reopened, "/workspace").json()["slots"]
    assert slots["A"]["id"] == a["id"] and slots["B"]["id"] == b["id"]
    assert slots["A"]["last_page"] == 3 and slots["B"]["last_page"] == 1
    assert slots["A"]["status"] == "ready" and slots["A"]["canvas"]["course_name"]
    assert reopened.get(f"/documents/{a['id']}/file", headers=HEADERS).status_code == 200
    assert get(reopened, "/canvas/status").json()["connected"] is True


def test_progress_and_history(conn) -> None:
    doc = open_pdf(conn, "A", 101, "1001", 5001).json()
    conn.put(f"/documents/{doc['id']}/progress", json={"page": 2}, headers=HEADERS)
    hist = get(conn, "/canvas/history").json()
    assert hist[0]["file_id"] == 5001 and hist[0]["last_page"] == 2 and hist[0]["course_name"] == "Curso"
    open_pdf(conn, "B", 102, "2001", 6001)
    assert [h["file_id"] for h in get(conn, "/canvas/history").json()][:2] == [6001, 5001]


def test_changing_institution_clears_cached_metadata(conn, canvas, secrets) -> None:
    get(conn, "/canvas/courses")
    other = FakeCanvasServer().start()
    try:
        other.courses = [{"id": 1, "name": "Outra faculdade", "course_code": "X", "workflow_state": "available"}]
        r = conn.put("/canvas/connection", json={"base_url": other.base_url, "token": TOKEN_OK}, headers=HEADERS)
        assert r.status_code == 200
        names = [c["name"] for c in get(conn, "/canvas/courses").json()["data"]]
        assert names == ["Outra faculdade"]
    finally:
        other.stop()


def test_auto_refresh_preference(conn, canvas) -> None:
    assert get(conn, "/canvas/status").json()["auto_refresh"] is True
    r = conn.put("/canvas/preferences", json={"auto_refresh": False}, headers=HEADERS)
    assert r.json()["auto_refresh"] is False


def test_local_pdf_still_works_and_remembers_last_page(app_client, make_pdf) -> None:
    doc = app_client.put("/workspace/slots/A", json={"path": str(make_pdf("x.pdf", pages=4))}, headers=HEADERS).json()
    assert doc["source"] == "local" and doc["canvas"] is None and doc["last_page"] == 1
    app_client.put(f"/documents/{doc['id']}/progress", json={"page": 4}, headers=HEADERS)
    assert get(app_client, "/workspace").json()["slots"]["A"]["last_page"] == 4


def test_chat_uses_canvas_and_local_pdf_together_with_file_name_and_page(conn, canvas, make_topic_pdf, fake_anthropic) -> None:
    from tests.conftest import API_KEY, parse_sse
    canvas.add_file(8100, "Biologia Celular.pdf", data=make_pdf_bytes("mitocondria produz ATP", pages=2))
    assert open_pdf(conn, "A", 101, "1001", 8100).status_code == 200
    local = make_topic_pdf("plantas.pdf", ["Ecologia geral.", "Mitocôndrias em células vegetais produzem energia."])
    assert conn.put("/workspace/slots/B", json={"path": str(local)}, headers=HEADERS).status_code == 200
    conn.put("/settings/ai/providers/anthropic/key", json={"api_key": API_KEY}, headers=HEADERS)
    r = conn.post("/chat/stream", json={"content": "O que a mitocondria produz?"}, headers=HEADERS)
    assert r.status_code == 200, r.text
    done = [d for n, d in parse_sse(r.text) if n == "done"][-1]
    refs = done.get("references") or done.get("message", {}).get("references") or []
    names = {ref["file_name"] for ref in refs}
    assert names <= {"Biologia Celular.pdf", "plantas.pdf"}  # só nomes de arquivos reais enviados à IA
    sent = json.dumps(fake_anthropic.requests[-1], ensure_ascii=False, default=str)
    assert "mitocondria produz ATP" in sent and "Mitocôndrias em células vegetais" in sent  # trechos dos DOIS documentos
