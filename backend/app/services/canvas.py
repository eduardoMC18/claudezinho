"""Integração com o Canvas LMS: conexão, biblioteca (disciplinas → módulos → PDFs) e PDFs remotos.

Um PDF do Canvas vira um `Document` comum (slot A/B, indexação FTS5, chat, exercícios, citações): o conteúdo é baixado
UMA vez para um cache temporário com limite de tamanho/idade (`canvas_cache/`), indexado e servido ao PDF.js pelo
mesmo endpoint dos PDFs locais (com suporte a Range). O token fica no cofre do sistema e nunca sai do backend.
"""

import json
import logging
import os
import re
import time
import unicodedata
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

import httpx

from app.domain.models import DEFAULT_WORKSPACE_ID, SLOTS, STATUS_PROCESSING, CanvasError, Document
from app.infra.canvas_repo import CanvasRepository
from app.infra.documents_repo import DocumentRepository
from app.infra.pdf import InvalidPdfError, inspect_pdf
from app.infra.secrets import SecretStore, SecretStoreError
from app.infra.settings_repo import SettingsRepository
from app.services.canvas_client import CanvasClient, normalize_base_url
from app.services.ingestion import IngestionService
from app.services.workspace import sha256_file

log = logging.getLogger(__name__)

SECRET_NAME = "canvas-token"
K_BASE = "canvas.base_url"
K_USER = "canvas.user_name"
K_USER_ID = "canvas.user_id"
K_AUTO = "canvas.auto_refresh"

TTL_COURSES = 6 * 3600
TTL_MODULES = 3600
TTL_ITEMS = 15 * 60
CACHE_MAX_BYTES = 600 * 1024 * 1024
CACHE_MAX_AGE = 14 * 24 * 3600
PAGE_SCAN_LIMIT = 12  # páginas de módulo examinadas por listagem
META_WORKERS = 6

COURSE_FILES = "files"  # "módulo" virtual com os PDFs de "Arquivos" do curso
_FILE_LINK = re.compile(r"/files/(\d+)")
_PDF_TYPES = {"application/pdf", "application/x-pdf"}


def _sort_key(text: str) -> str:
    """Ordem alfabética sem acento ("Cálculo" antes de "Curso")."""
    return "".join(ch for ch in unicodedata.normalize("NFKD", text.lower()) if not unicodedata.combining(ch))


def is_pdf_meta(meta: dict) -> bool:
    ctype = str(meta.get("content-type") or meta.get("content_type") or "").lower().split(";")[0].strip()
    name = str(meta.get("filename") or meta.get("display_name") or "").lower()
    return ctype in _PDF_TYPES or (ctype in ("", "application/octet-stream") and name.endswith(".pdf"))


def file_version(meta: dict) -> str:
    return f"{meta.get('modified_at') or meta.get('updated_at') or ''}|{meta.get('size') or ''}"


def _blocked(meta: dict) -> str | None:
    if meta.get("locked_for_user") or meta.get("locked"):
        return "Arquivo bloqueado no Canvas"
    if meta.get("hidden_for_user") or meta.get("hidden"):
        return "Arquivo oculto no Canvas"
    return None


class CanvasService:
    def __init__(self, repo: DocumentRepository, canvas_repo: CanvasRepository, settings: SettingsRepository,
                 secrets: SecretStore, ingestion: IngestionService, cache_dir: Path,
                 transport: httpx.BaseTransport | None = None, retry_wait: float = 1.0) -> None:
        self._repo = repo
        self._store = canvas_repo
        self._settings = settings
        self._secrets = secrets
        self._ingestion = ingestion
        self._cache_dir = cache_dir
        self._transport = transport
        self._retry_wait = retry_wait

    # ------------------------------------------------------------------ conexão

    def status(self) -> dict[str, Any]:
        base = self._settings.get(K_BASE)
        try:
            has_token = self._secrets.has(SECRET_NAME)
        except SecretStoreError:
            has_token = False
        return {
            "connected": bool(base and has_token),
            "base_url": base,
            "user_name": self._settings.get(K_USER),
            "auto_refresh": self._settings.get(K_AUTO) != "0",
        }

    def connect(self, base_url: str, token: str) -> dict[str, Any]:
        base = normalize_base_url(base_url)
        token = (token or "").strip()
        if not token or re.search(r"\s", token):
            raise CanvasError("bad_request", "Cole o token de acesso do Canvas (sem espaços).")
        with CanvasClient(base, token, self._transport, self._retry_wait) as client:
            me = client.whoami()  # valida endereço e token ANTES de guardar qualquer coisa
        try:
            self._secrets.set(SECRET_NAME, token)
        except SecretStoreError as exc:
            raise CanvasError("storage", str(exc)) from exc
        if self._settings.get(K_BASE) not in (None, base):
            self._store.cache_clear()  # outra instituição: o cache antigo não vale mais
        self._settings.set(K_BASE, base)
        self._settings.set(K_USER, str(me.get("name") or me.get("short_name") or ""))
        self._settings.set(K_USER_ID, str(me.get("id")))
        return self.status()

    def disconnect(self) -> None:
        """Remove token, metadados, histórico, PDFs do Canvas dos slots e o cache de arquivos."""
        try:
            self._secrets.delete(SECRET_NAME)
        except SecretStoreError as exc:
            raise CanvasError("storage", str(exc)) from exc
        for doc in self._repo.by_source("canvas"):
            self._repo.clear_slot(doc.workspace_id, doc.slot)
        self._store.cache_clear()
        for key in (K_BASE, K_USER, K_USER_ID):
            self._settings.set(key, "")
        self._wipe_cache()

    def set_auto_refresh(self, enabled: bool) -> None:
        self._settings.set(K_AUTO, "1" if enabled else "0")

    def _credentials(self) -> tuple[str, str]:
        base = self._settings.get(K_BASE)
        try:
            token = self._secrets.get(SECRET_NAME)
        except SecretStoreError as exc:
            raise CanvasError("storage", str(exc)) from exc
        if not base or not token:
            raise CanvasError("not_connected", "Conecte sua conta do Canvas em Configurações.")
        return base, token

    def _client(self) -> CanvasClient:
        base, token = self._credentials()
        return CanvasClient(base, token, self._transport, self._retry_wait)

    # ------------------------------------------------------------------ biblioteca (com cache)

    def _cached(self, key: str, ttl: int, refresh: bool, fetch) -> dict[str, Any]:
        base = self._credentials()[0]
        key = f"{base}|{key}"
        hit = self._store.cache_get(key)
        auto = self._settings.get(K_AUTO) != "0"
        if hit and not refresh and (not auto or time.time() - hit[1] < ttl):
            return {"data": hit[0], "fetched_at": hit[1], "stale": False, "warning": None}
        try:
            data = fetch()
        except CanvasError as exc:
            if hit and exc.kind in ("network", "rate_limit"):  # sem conexão: mostra o que já se sabe
                return {"data": hit[0], "fetched_at": hit[1], "stale": True, "warning": exc.message}
            raise
        self._store.cache_set(key, data)
        return {"data": data, "fetched_at": time.time(), "stale": False, "warning": None}

    def courses(self, refresh: bool = False) -> dict[str, Any]:
        def fetch() -> list[dict]:
            with self._client() as client:
                raw = client.courses()
            out = []
            for c in raw:
                # sem nome = sem acesso (curso restrito por data ou sem permissão); removidos também ficam de fora
                if not c.get("name") or c.get("access_restricted_by_date") or c.get("workflow_state") == "deleted":
                    continue
                state = str(c.get("workflow_state") or "")
                out.append({
                    "id": int(c["id"]),
                    "name": str(c["name"]),
                    "short_name": str(c.get("course_code") or c["name"]),
                    "code": str(c.get("sis_course_id") or c.get("course_code") or ""),
                    "published": state == "available",
                    "state": state,
                    "term": (c.get("term") or {}).get("name") if isinstance(c.get("term"), dict) else None,
                    "image_url": None,  # imagens do Canvas exigiriam o token no navegador; usa-se a identificação visual
                })
            out.sort(key=lambda c: _sort_key(c["name"]))
            return out
        return self._cached("courses", TTL_COURSES, refresh, fetch)

    def modules(self, course_id: int, refresh: bool = False) -> dict[str, Any]:
        def fetch() -> list[dict]:
            with self._client() as client:
                raw = client.modules(course_id)
            mods = [{
                "id": str(m["id"]),
                "name": str(m.get("name") or f"Módulo {m['id']}"),
                "position": m.get("position") or 0,
                "items_count": m.get("items_count"),
                "locked": m.get("state") == "locked",
                "kind": "module",
            } for m in raw if "id" in m]
            mods.sort(key=lambda m: m["position"])
            mods.append({"id": COURSE_FILES, "name": "Arquivos do curso (PDFs)", "position": 10**6,
                         "items_count": None, "locked": False, "kind": "files"})
            return mods
        return self._cached(f"modules:{course_id}", TTL_MODULES, refresh, fetch)

    def items(self, course_id: int, module_id: str, refresh: bool = False) -> dict[str, Any]:
        def fetch() -> list[dict]:
            with self._client() as client:
                if module_id == COURSE_FILES:
                    return [self._file_item(f, 0, None) for f in client.course_pdfs(course_id) if is_pdf_meta(f)]
                return self._module_items(client, course_id, int(module_id))
        return self._cached(f"items:{course_id}:{module_id}", TTL_ITEMS, refresh, fetch)

    @staticmethod
    def _file_item(meta: dict, indent: int, via: str | None) -> dict:
        reason = _blocked(meta)
        pdf = is_pdf_meta(meta)
        if not pdf and not reason:
            reason = "Não é um PDF"
        return {
            "key": f"file:{meta['id']}", "kind": "pdf" if pdf else "file",
            "title": str(meta.get("display_name") or meta.get("filename") or f"Arquivo {meta['id']}"),
            "indent": indent, "file_id": int(meta["id"]), "size": meta.get("size"),
            "modified_at": meta.get("modified_at") or meta.get("updated_at"),
            "available": pdf and reason is None, "reason": reason, "via": via,
        }

    def _module_items(self, client: CanvasClient, course_id: int, module_id: int) -> list[dict]:
        raw = client.module_items(course_id, module_id)
        file_ids: list[int] = []
        for it in raw:
            if it.get("type") == "File" and it.get("content_id"):
                file_ids.append(int(it["content_id"]))
        pages = [it for it in raw if it.get("type") == "Page" and it.get("page_url")][:PAGE_SCAN_LIMIT]

        # PDFs referenciados dentro de páginas do módulo
        page_files: dict[str, list[int]] = {}
        for page in pages:
            try:
                body = client.page_body(course_id, str(page["page_url"]))
            except CanvasError as exc:
                if exc.kind in ("auth", "rate_limit", "network"):
                    raise
                continue
            ids = []
            for m in _FILE_LINK.finditer(body):
                fid = int(m.group(1))
                if fid not in ids:
                    ids.append(fid)
            page_files[str(page["page_url"])] = ids[:20]

        wanted = list(dict.fromkeys(file_ids + [f for ids in page_files.values() for f in ids]))
        metas: dict[int, dict | CanvasError] = {}

        def load(fid: int) -> tuple[int, dict | CanvasError]:
            try:
                return fid, client.file(fid, course_id)
            except CanvasError as exc:
                return fid, exc

        with ThreadPoolExecutor(max_workers=META_WORKERS) as pool:
            for fid, result in pool.map(load, wanted):
                metas[fid] = result
        for result in metas.values():
            if isinstance(result, CanvasError) and result.kind in ("auth", "rate_limit", "network"):
                raise result

        def file_entry(fid: int, indent: int, title: str, via: str | None) -> dict:
            meta = metas.get(fid)
            if isinstance(meta, dict):
                return self._file_item(meta, indent, via)
            reason = {"forbidden": "Sem permissão para este arquivo", "not_found": "Arquivo removido no Canvas"}.get(
                meta.kind if isinstance(meta, CanvasError) else "", "Arquivo indisponível")
            return {"key": f"file:{fid}", "kind": "file", "title": title, "indent": indent, "file_id": fid,
                    "size": None, "modified_at": None, "available": False, "reason": reason, "via": via}

        out: list[dict] = []
        seen: set[int] = set()
        for it in raw:
            kind, title = it.get("type"), str(it.get("title") or "Sem título")
            indent = int(it.get("indent") or 0)
            if kind == "File" and it.get("content_id"):
                fid = int(it["content_id"])
                if fid not in seen:
                    seen.add(fid)
                    out.append(file_entry(fid, indent, title, None))
            elif kind == "SubHeader":
                out.append(self._other(it, "header", title, indent, None))
            elif kind == "Page":
                out.append(self._other(it, "page", title, indent, "Página (sem PDF direto)"))
                for fid in page_files.get(str(it.get("page_url")), []):
                    if fid not in seen:
                        seen.add(fid)
                        out.append(file_entry(fid, indent + 1, f"Arquivo {fid}", f"Na página “{title}”"))
            elif kind in ("ExternalUrl", "ExternalTool"):
                out.append(self._other(it, "link", title, indent, "Link externo"))
            else:
                out.append(self._other(it, "other", title, indent, {"Assignment": "Tarefa", "Quiz": "Quiz",
                                       "Discussion": "Discussão"}.get(str(kind), "Não é um arquivo")))
        return out

    @staticmethod
    def _other(it: dict, kind: str, title: str, indent: int, reason: str | None) -> dict:
        return {"key": f"item:{it.get('id')}", "kind": kind, "title": title, "indent": indent, "file_id": None,
                "size": None, "modified_at": None, "available": False, "reason": reason, "via": None}

    def history(self) -> list[dict[str, Any]]:
        base = self._settings.get(K_BASE)
        return self._store.history_list(base) if base else []

    # ------------------------------------------------------------------ PDFs remotos

    def open_in_slot(self, slot: str, course_id: int, module_id: str, file_id: int,
                     course_name: str = "", module_name: str = "") -> Document:
        if slot not in SLOTS:
            raise CanvasError("bad_request", "Slot inválido (use A ou B)")
        base, token = self._credentials()
        with CanvasClient(base, token, self._transport, self._retry_wait) as client:
            meta = client.file(file_id, course_id)
            self._check_openable(meta)
            doc_id = uuid.uuid4().hex
            dest = self._cache_dir / f"{doc_id}.pdf"
            info, digest = self._download_verified(client, meta, dest)
        course = self._course_info(course_id)
        source = {
            "institution": urlsplit(base).hostname, "base_url": base,
            "course_id": course_id, "course_name": course_name or course.get("name", ""),
            "course_code": course.get("code", ""), "module_id": module_id,
            "module_name": module_name or ("Arquivos do curso" if module_id == COURSE_FILES else ""),
            "file_id": file_id, "file_name": str(meta.get("filename") or ""),
            "display_name": str(meta.get("display_name") or ""),
            "remote_modified_at": meta.get("modified_at") or meta.get("updated_at"),
            "remote_version": file_version(meta),
        }
        previous = self._repo.get_by_slot(DEFAULT_WORKSPACE_ID, slot)
        stat = dest.stat()
        doc = Document(
            id=doc_id, workspace_id=DEFAULT_WORKSPACE_ID, slot=slot, path=str(dest),
            name=str(meta.get("display_name") or meta.get("filename") or f"canvas-{file_id}.pdf"),
            sha256=digest, size_bytes=stat.st_size, mtime_ns=stat.st_mtime_ns, pages=info.pages, title=info.title,
            status=STATUS_PROCESSING, pages_done=0, error=None, source="canvas",
            source_json=json.dumps(source, ensure_ascii=False), last_page=1,
        )
        self._repo.replace_in_slot(doc)
        if previous and previous.source == "canvas":
            self._unlink(Path(previous.path))
        self._store.history_add(base_url=base, file_id=file_id, course_id=course_id,
                                course_name=source["course_name"], module_id=module_id,
                                module_name=source["module_name"], name=doc.name)
        self._ingestion.start(doc.id)
        self.sweep()
        return doc

    def prepare(self, doc_id: str) -> dict[str, Any]:
        """Garante que o PDF está disponível (baixa de novo se o cache expirou) e informa se há versão nova."""
        doc = self._canvas_doc(doc_id)
        local = Path(doc.path).is_file()
        try:
            base, token = self._credentials()
            with CanvasClient(base, token, self._transport, self._retry_wait) as client:
                meta = client.file(self._source(doc)["file_id"], self._source(doc)["course_id"])
                self._check_openable(meta)
                changed = file_version(meta) != self._source(doc).get("remote_version")
                if not local:
                    self._materialize(doc, client, meta)
                    return {"ok": True, "update_available": False, "refreshed": changed}
            return {"ok": True, "update_available": changed, "refreshed": False, "warning": None}
        except CanvasError as exc:
            if local and exc.kind in ("network", "rate_limit"):  # lê o que já está no disco; avisa que não deu para checar
                return {"ok": True, "update_available": False, "refreshed": False, "warning": exc.message}
            raise

    def refresh(self, doc_id: str) -> dict[str, Any]:
        """Baixa a versão atual do Canvas e, se mudou, reindexa o documento (mesmo slot e id)."""
        doc = self._canvas_doc(doc_id)
        base, token = self._credentials()
        with CanvasClient(base, token, self._transport, self._retry_wait) as client:
            src = self._source(doc)
            meta = client.file(src["file_id"], src["course_id"])
            self._check_openable(meta)
            if file_version(meta) == src.get("remote_version") and Path(doc.path).is_file():
                return {"ok": True, "updated": False}
            updated = self._materialize(doc, client, meta)
        return {"ok": True, "updated": updated}

    def record_page(self, doc_id: str, page: int) -> None:
        """Última página lida (restaurada ao reabrir a sessão); no Canvas também alimenta o histórico."""
        doc = self._repo.get(doc_id)
        if doc is None:
            return
        self._repo.set_last_page(doc_id, page)
        if doc.source == "canvas":
            try:
                src = self._source(doc)
                self._store.history_set_page(src["base_url"], src["file_id"], page)
            except (CanvasError, KeyError):
                pass

    def ensure_local(self, doc_id: str) -> Document:
        """Caminho do PDF no cache (baixa de novo se foi limpo). Usado pelo endpoint que serve o arquivo."""
        doc = self._canvas_doc(doc_id)
        path = Path(doc.path)
        if not path.is_file():
            src = self._source(doc)
            base, token = self._credentials()
            with CanvasClient(base, token, self._transport, self._retry_wait) as client:
                meta = client.file(src["file_id"], src["course_id"])
                self._check_openable(meta)
                self._materialize(doc, client, meta)
        else:
            try:
                os.utime(path)  # uso recente: o último a ser limpo
            except OSError:
                pass
        return doc

    # ---- internos
    def _materialize(self, doc: Document, client: CanvasClient, meta: dict) -> bool:
        """Baixa o arquivo para o caminho do documento. Se o conteúdo mudou, atualiza e reindexa. True se reindexou."""
        dest = Path(doc.path)
        tmp = dest.with_suffix(".part")
        info, digest = self._download_verified(client, meta, tmp)
        src = self._source(doc)
        src.update(remote_modified_at=meta.get("modified_at") or meta.get("updated_at"),
                   remote_version=file_version(meta))
        try:
            os.replace(tmp, dest)
        except OSError as exc:
            self._unlink(tmp)
            raise CanvasError("storage", f"Não foi possível atualizar o arquivo temporário: {exc}") from exc
        if digest == doc.sha256:
            self._repo.set_source_json(doc.id, json.dumps(src, ensure_ascii=False))
            return False
        stat = dest.stat()
        self._repo.update_content(doc.id, sha256=digest, size_bytes=stat.st_size, mtime_ns=stat.st_mtime_ns,
                                  pages=info.pages, title=info.title, source_json=json.dumps(src, ensure_ascii=False))
        self._ingestion.start(doc.id)
        return True

    def _download_verified(self, client: CanvasClient, meta: dict, dest: Path):
        url = meta.get("url")
        if not url:
            raise CanvasError("forbidden", "O Canvas não liberou o download deste arquivo (sem permissão).")
        try:
            self._cache_dir.mkdir(parents=True, exist_ok=True)
        except OSError as exc:
            raise CanvasError("storage", f"Não foi possível criar a pasta temporária: {exc}") from exc
        tmp = dest if dest.suffix == ".part" else dest.with_suffix(".part")
        try:
            client.download(str(url), tmp)
            with tmp.open("rb") as handle:
                if handle.read(5) != b"%PDF-":
                    raise CanvasError("not_pdf", "O arquivo baixado do Canvas não é um PDF válido.")
            try:
                info = inspect_pdf(tmp)
            except InvalidPdfError as exc:
                raise CanvasError("not_pdf", str(exc)) from exc
            digest = sha256_file(tmp)
            if dest != tmp:
                os.replace(tmp, dest)
            return info, digest
        except BaseException:
            self._unlink(tmp)
            raise

    @staticmethod
    def _check_openable(meta: dict) -> None:
        reason = _blocked(meta)
        if reason:
            raise CanvasError("forbidden", f"{reason}. Peça acesso ao professor.")
        if not is_pdf_meta(meta):
            raise CanvasError("not_pdf", "Este arquivo não é um PDF.")

    def _canvas_doc(self, doc_id: str) -> Document:
        doc = self._repo.get(doc_id)
        if doc is None:
            raise CanvasError("not_found", "Documento não encontrado")
        if doc.source != "canvas":
            raise CanvasError("bad_request", "O documento não veio do Canvas")
        return doc

    @staticmethod
    def _source(doc: Document) -> dict[str, Any]:
        try:
            data = json.loads(doc.source_json or "{}")
        except ValueError:
            data = {}
        if "file_id" not in data or "course_id" not in data:
            raise CanvasError("bad_response", "Os dados de origem do documento estão incompletos.")
        return data

    def _course_info(self, course_id: int) -> dict[str, Any]:
        base = self._settings.get(K_BASE)
        hit = self._store.cache_get(f"{base}|courses")
        for c in (hit[0] if hit else []):
            if c.get("id") == course_id:
                return c
        return {}

    # ------------------------------------------------------------------ cache temporário

    @staticmethod
    def _unlink(path: Path) -> None:
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass  # em uso (Windows): a limpeza seguinte remove

    def _wipe_cache(self) -> None:
        if self._cache_dir.is_dir():
            for f in self._cache_dir.iterdir():
                if f.is_file():
                    self._unlink(f)

    def sweep(self, max_bytes: int = CACHE_MAX_BYTES, max_age: float = CACHE_MAX_AGE) -> None:
        """Limpeza automática: órfãos, arquivos velhos e o excedente do limite (os mais antigos primeiro).
        Arquivos de documentos abertos podem ser baixados de novo, então também são elegíveis."""
        if not self._cache_dir.is_dir():
            return
        known = {Path(d.path).resolve() for d in self._repo.by_source("canvas")}
        now = time.time()
        keep: list[tuple[float, int, Path]] = []
        for f in self._cache_dir.iterdir():
            if not f.is_file():
                continue
            try:
                st = f.stat()
            except OSError:
                continue
            if f.suffix == ".part":
                if now - st.st_mtime > 3600:
                    self._unlink(f)
            elif f.resolve() not in known or now - st.st_mtime > max_age:
                self._unlink(f)
            else:
                keep.append((st.st_mtime, st.st_size, f))
        total = sum(size for _, size, _ in keep)
        for _, size, f in sorted(keep):
            if total <= max_bytes or len(keep) <= 1:
                break
            self._unlink(f)
            total -= size
