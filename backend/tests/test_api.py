"""API de workspace/documentos de ponta a ponta (ingestão inline)."""

from pathlib import Path

from fastapi.testclient import TestClient

from tests.conftest import HEADERS, TOKEN, build_app


def put_slot(client: TestClient, slot: str, path: Path):
    return client.put(f"/workspace/slots/{slot}", json={"path": str(path)}, headers=HEADERS)


def test_endpoints_require_token(client: TestClient) -> None:
    assert client.get("/health").status_code == 401
    assert client.get("/workspace").status_code == 401
    assert client.get("/workspace", headers={"X-Estudotron-Token": "errado"}).status_code == 401
    assert client.get("/health", headers=HEADERS).json() == {"status": "ok"}


def test_empty_workspace(client: TestClient) -> None:
    assert client.get("/workspace", headers=HEADERS).json() == {"slots": {"A": None, "B": None}}


def test_load_two_documents(client: TestClient, make_pdf) -> None:
    a = put_slot(client, "A", make_pdf("a.pdf", pages=3))
    b = put_slot(client, "B", make_pdf("b.pdf", pages=5))
    assert a.status_code == 200 and b.status_code == 200
    assert a.json()["slot"] == "A" and a.json()["pages"] == 3
    assert b.json()["slot"] == "B" and b.json()["pages"] == 5

    slots = client.get("/workspace", headers=HEADERS).json()["slots"]
    assert slots["A"]["name"] == "a.pdf" and slots["A"]["status"] == "ready"
    assert slots["B"]["name"] == "b.pdf" and slots["B"]["pages_done"] == 5
    assert slots["A"]["file_state"] == "ok"


def test_replace_slot_changes_document(client: TestClient, make_pdf) -> None:
    first = put_slot(client, "A", make_pdf("um.pdf")).json()
    second = put_slot(client, "A", make_pdf("dois.pdf")).json()
    assert first["id"] != second["id"]
    assert client.get(f"/documents/{first['id']}", headers=HEADERS).status_code == 404
    assert client.get("/workspace", headers=HEADERS).json()["slots"]["A"]["name"] == "dois.pdf"


def test_remove_slot(client: TestClient, make_pdf) -> None:
    doc = put_slot(client, "B", make_pdf()).json()
    assert client.delete("/workspace/slots/B", headers=HEADERS).status_code == 204
    assert client.get("/workspace", headers=HEADERS).json()["slots"]["B"] is None
    assert client.get(f"/documents/{doc['id']}/file", params={"token": TOKEN}).status_code == 404
    # remover slot vazio é inofensivo
    assert client.delete("/workspace/slots/B", headers=HEADERS).status_code == 204


def test_invalid_slot_is_rejected(client: TestClient, make_pdf) -> None:
    assert put_slot(client, "C", make_pdf()).status_code == 422
    assert client.delete("/workspace/slots/Z", headers=HEADERS).status_code == 422


def test_file_endpoint_serves_pdf_with_query_token(client: TestClient, make_pdf) -> None:
    doc = put_slot(client, "A", make_pdf()).json()
    resp = client.get(f"/documents/{doc['id']}/file", params={"token": TOKEN})
    assert resp.status_code == 200
    assert resp.headers["content-type"] == "application/pdf"
    assert resp.content.startswith(b"%PDF")
    assert client.get(f"/documents/{doc['id']}/file").status_code == 401


def test_bad_inputs(client: TestClient, tmp_path: Path) -> None:
    assert put_slot(client, "A", tmp_path / "nao-existe.pdf").status_code == 404
    txt = tmp_path / "a.txt"
    txt.write_text("x")
    assert put_slot(client, "A", txt).status_code == 400
    fake = tmp_path / "falso.pdf"
    fake.write_text("isto nao e um pdf")
    assert put_slot(client, "A", fake).status_code == 422
    assert client.get("/workspace", headers=HEADERS).json()["slots"]["A"] is None


def test_failed_replacement_keeps_previous_document(client: TestClient, make_pdf, tmp_path: Path) -> None:
    put_slot(client, "A", make_pdf("bom.pdf"))
    fake = tmp_path / "falso.pdf"
    fake.write_text("nao e pdf")
    assert put_slot(client, "A", fake).status_code == 422
    assert client.get("/workspace", headers=HEADERS).json()["slots"]["A"]["name"] == "bom.pdf"


def test_file_state_modified_and_missing(client: TestClient, make_pdf) -> None:
    path = make_pdf("vivo.pdf")
    put_slot(client, "A", path)

    path.write_bytes(path.read_bytes() + b"\n% alterado\n")
    assert client.get("/workspace", headers=HEADERS).json()["slots"]["A"]["file_state"] == "modified"

    path.unlink()
    assert client.get("/workspace", headers=HEADERS).json()["slots"]["A"]["file_state"] == "missing"


def test_session_is_restored_after_restart(db_path: Path, secrets, fake_anthropic, make_pdf) -> None:
    first = TestClient(build_app(db_path, secrets, fake_anthropic))
    put_slot(first, "A", make_pdf("a.pdf"))
    put_slot(first, "B", make_pdf("b.pdf"))

    second = TestClient(build_app(db_path, secrets, fake_anthropic))
    slots = second.get("/workspace", headers=HEADERS).json()["slots"]
    assert slots["A"]["name"] == "a.pdf"
    assert slots["B"]["name"] == "b.pdf"


def test_viewer_is_served(client: TestClient) -> None:
    assert client.get("/viewer/index.html").status_code == 200
    assert client.get("/viewer/pdfjs/pdf.min.js").status_code == 200
