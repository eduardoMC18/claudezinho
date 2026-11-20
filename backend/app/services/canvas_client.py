"""Cliente HTTP da API oficial do Canvas LMS (REST v1).

- Autenticação: `Authorization: Bearer <token>` (token pessoal ou OAuth2). O token só existe aqui, no backend.
- Paginação: segue o cabeçalho `Link: rel="next"`, recusando URLs de outro host (o token nunca sai do domínio do Canvas).
- Limite de requisições: 429 / 403 "Rate Limit Exceeded" viram `CanvasError(rate_limit)` depois de uma nova tentativa curta.
"""

import logging
import re
import time
from collections.abc import Iterator
from pathlib import Path
from urllib.parse import urlsplit

import httpx

from app.domain.models import CanvasError

log = logging.getLogger(__name__)

API = "/api/v1"
PER_PAGE = 100
MAX_PAGES = 2000  # trava de segurança contra paginação infinita (2000 x 100 registros)
TIMEOUT = httpx.Timeout(20.0, connect=10.0)
MAX_PDF_BYTES = 400 * 1024 * 1024
_LOCAL_HOSTS = {"localhost", "127.0.0.1", "::1"}
_NEXT = re.compile(r'<([^>]+)>\s*;\s*rel="next"')


def normalize_base_url(raw: str) -> str:
    """Aceita 'faculdade.instructure.com' ou 'https://...'; devolve só a origem (https; http só em localhost)."""
    text = (raw or "").strip()
    if not text:
        raise CanvasError("bad_request", "Informe o endereço do Canvas da sua instituição.")
    if "://" not in text:
        text = "https://" + text
    try:
        parts = urlsplit(text)
        host = (parts.hostname or "").lower()
        port = parts.port
    except ValueError as exc:
        raise CanvasError("bad_request", "Endereço do Canvas inválido.") from exc
    if not host or parts.username or parts.password:
        raise CanvasError("bad_request", "Endereço do Canvas inválido (não inclua usuário nem senha).")
    if parts.scheme != "https" and not (parts.scheme == "http" and host in _LOCAL_HOSTS):
        raise CanvasError("bad_request", "O endereço do Canvas precisa usar https.")
    netloc = f"[{host}]" if ":" in host else host
    if port:
        netloc += f":{port}"
    return f"{parts.scheme}://{netloc}"


class CanvasClient:
    def __init__(self, base_url: str, token: str, transport: httpx.BaseTransport | None = None,
                 retry_wait: float = 1.0) -> None:
        self.base_url = base_url
        self._host = urlsplit(base_url).netloc
        self._retry_wait = retry_wait
        self._http = httpx.Client(timeout=TIMEOUT, transport=transport, follow_redirects=False)
        self._auth = {"Authorization": f"Bearer {token}"}

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> "CanvasClient":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    # ---- núcleo ----
    def _same_host(self, url: str) -> bool:
        return urlsplit(url).netloc == self._host

    def _send(self, url: str, params: list | dict | None = None, stream: bool = False,
              with_auth: bool = True) -> httpx.Response:
        if with_auth and not self._same_host(url):
            raise CanvasError("bad_response", "O Canvas indicou um endereço de outro domínio; requisição bloqueada.")
        headers = dict(self._auth) if with_auth else {}
        attempts = 0
        while True:
            try:
                request = self._http.build_request("GET", url, params=params, headers=headers)
                response = self._http.send(request, stream=stream)
            except httpx.TimeoutException as exc:
                raise CanvasError("network", "O Canvas demorou demais para responder.") from exc
            except httpx.HTTPError as exc:
                raise CanvasError("network", "Não foi possível conectar ao Canvas. Verifique a internet e o endereço.") from exc
            if _rate_limited(response) and attempts < 1:
                attempts += 1
                response.close()
                time.sleep(self._retry_wait)
                continue
            return response

    def _raise_for(self, response: httpx.Response, what: str) -> None:
        code = response.status_code
        if code < 400:
            return
        body = _peek(response)
        response.close()
        if code == 401:
            raise CanvasError("auth", "O Canvas recusou o token (inválido, expirado ou revogado). Conecte a conta de novo.")
        if code == 429 or (code == 403 and "rate limit" in body.lower()):
            raise CanvasError("rate_limit", "O Canvas limitou as requisições. Aguarde alguns segundos e tente de novo.")
        if code == 403:
            raise CanvasError("forbidden", f"Sem permissão para acessar {what} no Canvas.")
        if code in (404, 410):
            raise CanvasError("not_found", f"{what[:1].upper()}{what[1:]} não existe mais ou foi removido no Canvas.")
        if code >= 500:
            raise CanvasError("network", f"O Canvas está indisponível no momento (erro {code}).")
        raise CanvasError("bad_response", f"Resposta inesperada do Canvas (erro {code}).")

    def get_json(self, path: str, params: list | dict | None = None, what: str = "o recurso"):
        response = self._send(self.base_url + API + path, params=params)
        self._raise_for(response, what)
        try:
            return response.json()
        except ValueError as exc:
            raise CanvasError("bad_response", "O Canvas devolveu uma resposta incompleta ou inválida.") from exc

    def paginate(self, path: str, params: list | None = None, what: str = "o recurso") -> Iterator[dict]:
        """Itera todos os registros de um endpoint paginado (cabeçalho Link rel=next)."""
        url: str | None = self.base_url + API + path
        query: list | None = [("per_page", str(PER_PAGE)), *(params or [])]
        for _ in range(MAX_PAGES):
            if url is None:
                return
            response = self._send(url, params=query)
            self._raise_for(response, what)
            try:
                data = response.json()
            except ValueError as exc:
                raise CanvasError("bad_response", "O Canvas devolveu uma resposta incompleta ou inválida.") from exc
            if not isinstance(data, list):
                raise CanvasError("bad_response", "O Canvas devolveu um formato inesperado de lista.")
            yield from (d for d in data if isinstance(d, dict))
            match = _NEXT.search(response.headers.get("link", ""))
            url, query = (match.group(1), None) if match else (None, None)

    # ---- endpoints oficiais ----
    def whoami(self) -> dict:
        """GET /api/v1/users/self — valida o token."""
        data = self.get_json("/users/self", what="o seu perfil")
        if not isinstance(data, dict) or "id" not in data:
            raise CanvasError("bad_response", "Esse endereço não parece ser um Canvas válido.")
        return data

    def courses(self) -> list[dict]:
        """GET /api/v1/courses (matrículas ativas)."""
        return list(self.paginate("/courses", [("enrollment_state", "active"), ("include[]", "term")],
                                  "a lista de disciplinas"))

    def modules(self, course_id: int) -> list[dict]:
        """GET /api/v1/courses/:id/modules"""
        return list(self.paginate(f"/courses/{course_id}/modules", what="os módulos"))

    def module_items(self, course_id: int, module_id: int) -> list[dict]:
        """GET /api/v1/courses/:id/modules/:id/items (com content_details)."""
        return list(self.paginate(f"/courses/{course_id}/modules/{module_id}/items",
                                  [("include[]", "content_details")], "os itens do módulo"))

    def file(self, file_id: int, course_id: int | None = None) -> dict:
        """GET /api/v1/courses/:cid/files/:id (respeita as permissões do curso) ou /files/:id."""
        path = f"/courses/{course_id}/files/{file_id}" if course_id else f"/files/{file_id}"
        data = self.get_json(path, what="o arquivo")
        if not isinstance(data, dict) or "id" not in data:
            raise CanvasError("bad_response", "O Canvas devolveu os dados do arquivo incompletos.")
        return data

    def course_pdfs(self, course_id: int) -> list[dict]:
        """GET /api/v1/courses/:id/files filtrado por PDF."""
        return list(self.paginate(f"/courses/{course_id}/files", [("content_types[]", "application/pdf")],
                                  "os arquivos do curso"))

    def page_body(self, course_id: int, page_url: str) -> str:
        """GET /api/v1/courses/:id/pages/:url — acha PDFs referenciados dentro de páginas de módulo."""
        data = self.get_json(f"/courses/{course_id}/pages/{page_url}", what="a página")
        return str(data.get("body") or "") if isinstance(data, dict) else ""

    # ---- download ----
    def download(self, url: str, dest: Path, max_bytes: int = MAX_PDF_BYTES) -> int:
        """Baixa o conteúdo para `dest`. Segue redirecionamentos manualmente: o token só acompanha pedidos ao
        domínio do Canvas (a URL assinada do CDN já traz a própria autorização)."""
        current = url
        for _ in range(6):
            response = self._send(current, stream=True, with_auth=self._same_host(current))
            if response.status_code in (301, 302, 303, 307, 308):
                target = response.headers.get("location")
                response.close()
                if not target:
                    raise CanvasError("bad_response", "Redirecionamento inválido do Canvas.")
                current = str(httpx.URL(current).join(target))
                continue
            try:
                self._raise_for(response, "o arquivo")
                total = 0
                try:
                    with dest.open("wb") as handle:
                        for block in response.iter_bytes(64 * 1024):
                            total += len(block)
                            if total > max_bytes:
                                raise CanvasError("too_large", "O PDF é grande demais para abrir direto do Canvas.")
                            handle.write(block)
                except httpx.HTTPError as exc:
                    raise CanvasError("network", "A transferência do arquivo foi interrompida. Tente de novo.") from exc
                except OSError as exc:
                    raise CanvasError("storage", f"Não foi possível gravar o arquivo temporário: {exc}") from exc
                return total
            finally:
                response.close()
        raise CanvasError("bad_response", "Redirecionamentos demais ao baixar o arquivo.")


def _rate_limited(response: httpx.Response) -> bool:
    if response.status_code == 429:
        return True
    if response.status_code == 403:
        remaining = response.headers.get("x-rate-limit-remaining")
        if remaining is not None and _to_float(remaining) <= 0:
            return True
        return "rate limit" in _peek(response).lower()
    return False


def _peek(response: httpx.Response) -> str:
    try:
        response.read()
        return response.text[:500]
    except Exception:
        return ""


def _to_float(value: str) -> float:
    try:
        return float(value)
    except ValueError:
        return 1.0
