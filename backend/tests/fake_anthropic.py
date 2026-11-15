"""Servidor HTTP que imita a API da Anthropic (Messages, com streaming SSE).

Usado nos testes e na validação ponta a ponta SEM gastar créditos: o SDK oficial conversa
com ele de verdade (via ANTHROPIC_BASE_URL), então o código do provedor é exercitado inteiro.

A resposta cita páginas reais lendo os rótulos <trecho ref="A p.12"> do contexto recebido.
Palavras-gatilho na pergunta: ERRO401, ERRO429, ERRO529, RECUSA, INVENTA (cita página
inexistente), LENTO (resposta demorada, para testar o botão parar).
"""

import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_REF = re.compile(r'<trecho ref="([AB]) p\.(\d+)">')
_TRECHO = re.compile(r'<trecho ref="([AB]) p\.(\d+)">\n(.*?)\n</trecho>', re.DOTALL)
_LONG_WORD = re.compile(r"\w{6,}")


def key_from_headers(headers) -> str:
    """O SDK 2.x envia a chave em 'Authorization: Bearer'; versões antigas usavam 'x-api-key'."""
    value = headers.get("x-api-key") or headers.get("authorization") or ""
    return value.removeprefix("Bearer ").strip()


def _best_ref(user_text: str, slot: str) -> tuple[str, str] | None:
    """Trecho do documento que mais compartilha palavras (6+ letras) com a pergunta."""
    question = user_text.split("Pergunta do usuário:")[-1].lower()
    words = set(_LONG_WORD.findall(question))
    best, best_score = None, -1
    for ref_slot, page, text in _TRECHO.findall(user_text):
        if ref_slot != slot:
            continue
        lowered = text.lower()
        score = sum(1 for w in words if w[:-2] in lowered)
        if score > best_score:
            best, best_score = (ref_slot, page), score
    return best


def build_answer(user_text: str) -> str:
    refs = list(dict.fromkeys(_REF.findall(user_text)))
    first_a, first_b = _best_ref(user_text, "A"), _best_ref(user_text, "B")
    lines = ["## Resposta de teste", ""]
    if first_a:
        lines.append(f"Segundo o primeiro documento, o tema aparece na página {first_a[1]} [A p.{first_a[1]}].")
    if first_b:
        lines.append(f"Já o segundo documento aborda o assunto na página {first_b[1]} [B p.{first_b[1]}].")
    if not refs:
        lines.append("Nos trechos recuperados não encontrei essa informação.")
    if "INVENTA" in user_text:
        lines.append("Isto cita uma página inexistente [A p.999].")
    lines += ["", "| Documento | Página |", "|---|---|"]
    lines += [f"| {slot} | {page} |" for slot, page in refs[:3]]
    lines += ["", "```python", "print('olá')", "```"]
    return "\n".join(lines)


class _QuietServer(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address) -> None:  # cliente que desconecta (botão parar) não é erro
        pass


class FakeAnthropicServer:
    def __init__(self, port: int = 0) -> None:
        self.requests: list[dict] = []
        self._server = _QuietServer(("127.0.0.1", port), self._handler())
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def start(self) -> "FakeAnthropicServer":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def _handler(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args) -> None:  # silencia o log
                pass

            def _json(self, status: int, payload: dict) -> None:
                body = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                outer.requests.append({
                    "path": self.path,
                    "headers": {k.lower(): v for k, v in self.headers.items()},
                    "body": body,
                })
                if not self.path.startswith("/v1/messages"):
                    return self._json(404, {"type": "error", "error": {"type": "not_found_error", "message": "x"}})

                last_user = next((m for m in reversed(body.get("messages", [])) if m["role"] == "user"), {})
                text = last_user.get("content", "") if isinstance(last_user.get("content"), str) else ""

                for trigger, status, etype, msg in (
                    ("ERRO401", 401, "authentication_error", "invalid x-api-key"),
                    ("ERRO429", 429, "rate_limit_error", "rate limited"),
                    ("ERRO529", 529, "overloaded_error", "Overloaded"),
                ):
                    if trigger in text or key_from_headers(self.headers) == trigger:
                        return self._json(status, {"type": "error", "error": {"type": etype, "message": msg}})

                if not body.get("stream"):
                    return self._json(200, {
                        "id": "msg_test", "type": "message", "role": "assistant", "model": body.get("model"),
                        "content": [{"type": "text", "text": "ok"}], "stop_reason": "end_turn",
                        "stop_sequence": None, "usage": {"input_tokens": 12, "output_tokens": 3},
                    })

                answer = build_answer(text)
                slow = "LENTO" in text
                refusal = "RECUSA" in text
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()

                def send(event: str, data: dict) -> None:
                    payload = f"event: {event}\ndata: {json.dumps(data)}\n\n".encode()
                    self.wfile.write(f"{len(payload):x}\r\n".encode() + payload + b"\r\n")
                    self.wfile.flush()

                try:
                    send("message_start", {"type": "message_start", "message": {
                        "id": "msg_test", "type": "message", "role": "assistant", "model": body.get("model"),
                        "content": [], "stop_reason": None, "stop_sequence": None,
                        "usage": {"input_tokens": 321, "output_tokens": 1}}})
                    send("content_block_start", {"type": "content_block_start", "index": 0,
                                                 "content_block": {"type": "text", "text": ""}})
                    pieces = [answer[i:i + 7] for i in range(0, len(answer), 7)]  # corta rótulos ao meio de propósito
                    for index, piece in enumerate(pieces):
                        if refusal and index == 2:
                            break
                        send("content_block_delta", {"type": "content_block_delta", "index": 0,
                                                     "delta": {"type": "text_delta", "text": piece}})
                        if slow:
                            time.sleep(0.15)
                    send("content_block_stop", {"type": "content_block_stop", "index": 0})
                    send("message_delta", {"type": "message_delta",
                                           "delta": {"stop_reason": "refusal" if refusal else "end_turn",
                                                     "stop_sequence": None},
                                           "usage": {"output_tokens": 77}})
                    send("message_stop", {"type": "message_stop"})
                    self.wfile.write(b"0\r\n\r\n")
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass  # o cliente parou de ler (botão parar)

        return Handler


def main() -> None:
    """python -m tests.fake_anthropic [porta] — sobe o servidor falso para testes manuais."""
    import sys
    server = FakeAnthropicServer(int(sys.argv[1]) if len(sys.argv) > 1 else 0).start()
    print(f"Servidor falso em {server.base_url}", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        server.stop()


if __name__ == "__main__":
    main()
