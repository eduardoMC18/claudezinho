"""Canvas LMS FALSO (servidor HTTP local) para testes sem tocar numa conta institucional.

Imita os endpoints oficiais usados pelo app: /users/self, /courses, /courses/:id/modules, módulos/itens (paginação por
cabeçalho Link), /courses/:id/files, /files/:id, páginas e o download com redirecionamento para outro "host" (CDN),
que NÃO deve receber o token. Também grava as requisições (para provar que o token não vaza).

    python -m tests.fake_canvas 18772      # sobe o servidor (token: TOKEN_OK)
"""

import json
import re
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

import pymupdf

TOKEN_OK = "1234~TOKEN-DE-TESTE-DO-CANVAS-ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"


def make_pdf_bytes(topic: str, pages: int = 3) -> bytes:
    doc = pymupdf.open()
    for i in range(pages):
        page = doc.new_page()
        page.insert_textbox(pymupdf.Rect(60, 60, 540, 780), f"{topic} pagina {i + 1}. Conteudo sobre {topic}.", fontsize=12)
    data = doc.tobytes()
    doc.close()
    return data


class FakeCanvasServer:
    def __init__(self, per_page_cap: int = 100) -> None:
        self.token = TOKEN_OK
        self.per_page_cap = per_page_cap
        self.requests: list[dict] = []
        self.expired = False
        self.rate_limit_hits = 0  # as próximas N requisições devolvem "Rate Limit Exceeded"
        self.file_status: dict[int, int] = {}  # id do arquivo -> HTTP forçado em /files/:id
        self.download_status: dict[int, int] = {}  # id do arquivo -> HTTP forçado no download
        self.truncate_download: set[int] = set()
        self.extra_courses = 0
        self.courses: list[dict] = []
        self.modules: dict[int, list[dict]] = {}
        self.items: dict[int, list[dict]] = {}
        self.files: dict[int, dict] = {}
        self.blobs: dict[int, bytes] = {}
        self.pages: dict[tuple[int, str], str] = {}
        self._seed()
        self._httpd: ThreadingHTTPServer | None = None
        self.port = 0

    # ------------------------------------------------------------------ dados
    def add_file(self, fid: int, name: str, ctype: str = "application/pdf", data: bytes | None = None,
                 modified: str = "2026-03-01T10:00:00Z", **extra) -> None:
        if data is None:
            data = make_pdf_bytes(name.rsplit(".", 1)[0]) if ctype == "application/pdf" else b"conteudo"
        self.blobs[fid] = data
        self.files[fid] = {"id": fid, "display_name": name, "filename": name.replace(" ", "+"), "content-type": ctype,
                           "size": len(data), "modified_at": modified, "updated_at": modified, **extra}

    def update_file(self, fid: int, data: bytes, modified: str) -> None:
        self.blobs[fid] = data
        self.files[fid].update(size=len(data), modified_at=modified, updated_at=modified)

    def _seed(self) -> None:
        self.courses = [
            {"id": 101, "name": "Cálculo I", "course_code": "MAT101", "sis_course_id": "2026-1-MAT101",
             "workflow_state": "available", "term": {"name": "2026/1"}},
            {"id": 102, "name": "Física I", "course_code": "FIS101", "workflow_state": "available"},
            {"id": 103, "name": "Curso Vazio", "course_code": "VAZ", "workflow_state": "unpublished"},
            {"id": 104, "access_restricted_by_date": True},  # sem nome: o aluno não tem acesso ainda
        ]
        self.modules = {
            101: [{"id": 1001, "name": "Módulo 1 — Introdução", "position": 1, "items_count": 7},
                  {"id": 1002, "name": "Módulo 2 — Limites", "position": 2, "items_count": 1},
                  {"id": 1003, "name": "Módulo 3 — Derivadas", "position": 3, "items_count": 0, "state": "locked"}],
            102: [{"id": 2001, "name": "Cinemática", "position": 1, "items_count": 1}],
            103: [],
        }
        self.add_file(5001, "Aula 01 - Introdução.pdf")
        self.add_file(5002, "Planilha.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
        self.add_file(5003, "Lista de exercícios.pdf")
        self.add_file(5004, "Gabarito.pdf", locked_for_user=True)
        self.add_file(5005, "Aula 02 - Limites.pdf")
        self.add_file(5006, "Aula 02b - Continuidade.pdf")
        self.add_file(6001, "Aula 02 - Movimento uniforme.pdf")
        self.add_file(7001, "Resumo do curso.pdf")
        self.items = {
            1001: [
                {"id": 9001, "type": "SubHeader", "title": "Material de aula", "indent": 0},
                {"id": 9002, "type": "File", "title": "Aula 01 - Introdução.pdf", "content_id": 5001, "indent": 1},
                {"id": 9003, "type": "File", "title": "Planilha.docx", "content_id": 5002, "indent": 1},
                {"id": 9004, "type": "Page", "title": "Leituras", "page_url": "leituras", "indent": 0},
                {"id": 9005, "type": "ExternalUrl", "title": "Vídeo no YouTube", "external_url": "https://example.com", "indent": 0},
                {"id": 9006, "type": "Assignment", "title": "Tarefa 1", "content_id": 1, "indent": 0},
                {"id": 9007, "type": "File", "title": "Gabarito.pdf", "content_id": 5004, "indent": 1},
                {"id": 9008, "type": "File", "title": "Aula 01 - Introdução.pdf (de novo)", "content_id": 5001, "indent": 1},
            ],
            1002: [{"id": 9010, "type": "File", "title": "Aula 02 - Limites.pdf", "content_id": 5005, "indent": 0},
                   {"id": 9011, "type": "File", "title": "Aula 02b - Continuidade.pdf", "content_id": 5006, "indent": 0}],
            1003: [],
            2001: [{"id": 9020, "type": "File", "title": "Aula 02 - Movimento uniforme.pdf", "content_id": 6001, "indent": 0}],
        }
        self.pages = {(101, "leituras"): '<p>Leia o <a href="/courses/101/files/5003/download?wrap=1">PDF da lista</a>.</p>'}

    def all_courses(self) -> list[dict]:
        extra = [{"id": 300 + i, "name": f"Disciplina extra {i:03d}", "course_code": f"EX{i}", "workflow_state": "available"}
                 for i in range(self.extra_courses)]
        return self.courses + extra

    # ------------------------------------------------------------------ servidor
    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    @property
    def cdn_url(self) -> str:
        return f"http://localhost:{self.port}"  # outro "host": o token não pode acompanhar

    def start(self, port: int = 0) -> "FakeCanvasServer":
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args) -> None:  # silencioso
                pass

            def _send(self, status: int, body: bytes, ctype: str = "application/json", headers: dict | None = None) -> None:
                self.send_response(status)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                self.wfile.write(body)

            def _json(self, status: int, data, headers: dict | None = None) -> None:
                self._send(status, json.dumps(data).encode(), headers=headers)

            def do_GET(self) -> None:  # noqa: N802
                outer._handle(self)

        self._httpd = ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.port = self._httpd.server_address[1]
        threading.Thread(target=self._httpd.serve_forever, daemon=True).start()
        return self

    def stop(self) -> None:
        if self._httpd:
            self._httpd.shutdown()
            self._httpd.server_close()

    # ------------------------------------------------------------------ rotas
    def _handle(self, h) -> None:
        parts = urlsplit(h.path)
        query = parse_qs(parts.query)
        auth = h.headers.get("Authorization")
        self.requests.append({"path": parts.path, "query": parts.query, "auth": auth, "host": h.headers.get("Host"),
                              "range": h.headers.get("Range")})
        path = parts.path

        if path == "/__control":  # só para os testes ponta a ponta (o app nunca chama isto)
            cmd = query.get("cmd", [""])[0]
            fid = int(query.get("id", ["0"])[0] or 0)
            if cmd == "update":
                self.update_file(fid, make_pdf_bytes(query.get("topic", ["Novo conteudo"])[0], pages=int(query.get("pages", ["4"])[0])),
                                 "2026-09-01T12:00:00Z")
            elif cmd == "expire":
                self.expired = True
            elif cmd == "restore":
                self.expired = False
                self.file_status.clear()
                self.download_status.clear()
            elif cmd == "status":
                self.file_status[fid] = int(query.get("code", ["404"])[0])
            return h._json(200, {"ok": True})

        if path.startswith("/cdn/"):  # "CDN": autorizado pela URL assinada, nunca pelo token
            if auth:
                return h._json(400, {"errors": [{"message": "token vazou para o CDN"}]})
            fid = int(path.split("/")[2])
            if self.download_status.get(fid):
                return h._json(self.download_status[fid], {"errors": []})
            data = self.blobs.get(fid)
            if data is None:
                return h._json(404, {"errors": []})
            if fid in self.truncate_download:  # promete mais bytes do que envia
                h.send_response(200)
                h.send_header("Content-Type", "application/pdf")
                h.send_header("Content-Length", str(len(data) + 5000))
                h.end_headers()
                h.wfile.write(data[: len(data) // 2])
                h.close_connection = True
                return
            return h._send(200, data, "application/pdf")

        if path.startswith("/files/") and path.endswith("/download"):  # download no host do Canvas: exige o token
            if auth != f"Bearer {self.token}" or self.expired:
                return h._json(401, {"errors": [{"message": "Invalid access token."}]})
            fid = int(path.split("/")[2])
            return h._send(302, b"", headers={"Location": f"{self.cdn_url}/cdn/{fid}/{fid}.pdf?sig=abc"})

        if not path.startswith("/api/v1/"):
            return h._json(404, {"errors": []})
        if auth != f"Bearer {self.token}" or self.expired:
            return h._json(401, {"errors": [{"message": "Invalid access token."}]})
        if self.rate_limit_hits > 0:
            self.rate_limit_hits -= 1
            return h._send(403, b"403 Forbidden (Rate Limit Exceeded)", "text/plain", {"X-Rate-Limit-Remaining": "0"})

        route = path[len("/api/v1"):]
        if route == "/users/self":
            return h._json(200, {"id": 77, "name": "Aluna de Teste", "short_name": "Aluna"})
        if route == "/courses":
            return self._paged(h, self.all_courses(), query)
        m = re.fullmatch(r"/courses/(\d+)/modules", route)
        if m:
            cid = int(m.group(1))
            if cid not in self.modules:
                return h._json(404, {"errors": []})
            return self._paged(h, self.modules[cid], query)
        m = re.fullmatch(r"/courses/(\d+)/modules/(\d+)/items", route)
        if m:
            mid = int(m.group(2))
            if mid not in self.items:
                return h._json(404, {"errors": []})
            return self._paged(h, self.items[mid], query)
        m = re.fullmatch(r"/courses/(\d+)/files", route)
        if m:
            pdfs = [f for f in self.files.values() if f["content-type"] == "application/pdf" and f["id"] in (5001, 5003, 5005, 7001)]
            return self._paged(h, [self._file_out(f) for f in pdfs], query)
        m = re.fullmatch(r"/courses/(\d+)/pages/(.+)", route)
        if m:
            body = self.pages.get((int(m.group(1)), m.group(2)))
            return h._json(200, {"body": body}) if body is not None else h._json(404, {"errors": []})
        m = re.fullmatch(r"(?:/courses/\d+)?/files/(\d+)", route)
        if m:
            fid = int(m.group(1))
            if self.file_status.get(fid):
                return h._json(self.file_status[fid], {"errors": [{"message": "x"}]})
            f = self.files.get(fid)
            return h._json(200, self._file_out(f)) if f else h._json(404, {"errors": []})
        return h._json(404, {"errors": []})

    def _file_out(self, f: dict) -> dict:
        out = dict(f)
        out["url"] = f"{self.base_url}/files/{f['id']}/download?download_frd=1&verifier=xyz"
        return out

    def _paged(self, h, rows: list[dict], query: dict) -> None:
        per = min(int(query.get("per_page", ["10"])[0]), self.per_page_cap)
        page = int(query.get("page", ["1"])[0])
        chunk = rows[(page - 1) * per: page * per]
        links = []
        base = f"{self.base_url}{urlsplit(h.path).path}"
        if page * per < len(rows):
            q = {k: v for k, v in query.items() if k != "page"}
            qs = "&".join(f"{k}={x}" for k, vs in q.items() for x in vs)
            links.append(f'<{base}?{qs}&page={page + 1}>; rel="next"')
        h._json(200, chunk, {"Link": ", ".join(links)} if links else None)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 18772
    server = FakeCanvasServer().start(port)
    print(f"Canvas falso em {server.base_url} (token: {TOKEN_OK})", flush=True)
    threading.Event().wait()
