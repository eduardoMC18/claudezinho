"""Servidor HTTP que imita a API do Google Gemini (generateContent / streamGenerateContent com SSE).

O SDK oficial `google-genai` conversa com ele de verdade (via GOOGLE_GEMINI_BASE_URL), então o código
do provedor é exercitado inteiro, sem gastar créditos. A resposta cita páginas reais lendo os rótulos
<trecho ref="A p.12"> do contexto recebido (mesma lógica do servidor falso da Anthropic).

Palavras-gatilho na pergunta: ERRO401 (chave inválida), ERRO429, ERRO529 (503), RECUSA (bloqueio de
segurança no meio da resposta), INVENTA (cita página inexistente), LENTO (resposta demorada).
"""

import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler

from tests.fake_anthropic import _QuietServer, build_answer


_TASK = re.compile(r"\[\[TAREFA:(\w+)\]\]")
_REF = re.compile(r'<trecho ref="([AB]) p\.(\d+)">')


def build_exercise_answer(text: str) -> str | None:
    """Resposta JSON para os pedidos de exercícios (marcador [[TAREFA:...]] no texto).

    Gatilhos no material/resposta: JSONRUIM (devolve texto que não é JSON), SEMREF (cita página inexistente),
    ERRADO / PARCIAL (nota baixa / média na correção), INVENTA (inclui uma referência inexistente a mais).
    """
    task = _TASK.search(text)
    if not task:
        return None
    if "JSONRUIM" in text:
        return "Claro! Aqui está a questão que você pediu (sem JSON)."
    refs = [f"{slot} p.{page}" for slot, page in dict.fromkeys(_REF.findall(text))] or ["A p.1"]
    ref = ["A p.999"] if "SEMREF" in text else refs[:2]
    if task.group(1) == "GERAR_OBJETIVA":
        return json.dumps({
            "statement": "Qual é a função principal da mitocôndria na célula, segundo o material?",
            "difficulty": "média",
            "options": [
                {"text": "Armazenar o material genético da célula", "explanation": "Isso é função do núcleo."},
                {"text": "Realizar a fotossíntese", "explanation": "Fotossíntese ocorre nos cloroplastos."},
                {"text": "Produzir ATP pela respiração celular", "explanation": "Correta: a mitocôndria produz ATP."},
                {"text": "Sintetizar a parede celular", "explanation": "A parede celular é de plantas, não da mitocôndria."},
            ],
            "correct": "c", "explanation": "A mitocôndria é a organela responsável pela respiração celular e produz ATP.",
            "references": ref}, ensure_ascii=False)
    if task.group(1) == "GERAR_DISCURSIVA":
        return json.dumps({
            "statement": "Explique como a mitocôndria e o cloroplasto contribuem para o fluxo de energia nas células.",
            "difficulty": "dificil",
            "criteria": ["Mitocôndria produz ATP", "Cloroplasto converte luz em energia química", "Relação entre os dois"],
            "model_answer": "A mitocôndria produz ATP pela respiração celular e o cloroplasto converte luz solar em glicose.",
            "references": ref}, ensure_ascii=False)
    answer = text.split("<resposta_do_estudante>")[-1]
    score, verdict = (2.0, "incorrect") if "ERRADO" in answer else (5.0, "partial") if "PARCIAL" in answer         else (8.5, "correct")
    return json.dumps({
        "score": score, "verdict": verdict, "summary": "Avaliação de teste da resposta.",
        "strengths": ["Citou a produção de ATP"] if score > 3 else [],
        "missing": ["Relacionar com o cloroplasto"] if score < 8 else [],
        "corrections": [{"concept": "Respiração celular", "explanation": "Ocorre na mitocôndria."}] if score < 3 else [],
        "ideal_answer": "A mitocôndria produz ATP e o cloroplasto converte luz em energia química, em conjunto.",
        "references": refs[:1] + (["A p.999"] if "INVENTA" in answer else [])}, ensure_ascii=False)


def key_from_headers(headers) -> str:
    return (headers.get("x-goog-api-key") or "").strip()


def _chunk(text: str, finish: str | None = None, usage: bool = False) -> dict:
    candidate: dict = {"content": {"role": "model", "parts": [{"text": text}]}, "index": 0}
    if finish:
        candidate["finishReason"] = finish
    payload: dict = {"candidates": [candidate], "modelVersion": "fake-gemini", "responseId": "r1"}
    if usage:
        payload["usageMetadata"] = {"promptTokenCount": 410, "candidatesTokenCount": 60,
                                    "thoughtsTokenCount": 25, "totalTokenCount": 495}
    return payload


class FakeGeminiServer:
    def __init__(self, port: int = 0) -> None:
        self.requests: list[dict] = []
        self.attempts: dict[str, int] = {}
        self._server = _QuietServer(("127.0.0.1", port), self._handler())
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self._server.server_address[1]}"

    def start(self) -> "FakeGeminiServer":
        self._thread.start()
        return self

    def stop(self) -> None:
        self._server.shutdown()
        self._server.server_close()

    def _handler(self):
        outer = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *args) -> None:
                pass

            def _json(self, status: int, payload: dict) -> None:
                body = json.dumps(payload).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _error(self, code: int, status: str, message: str) -> None:
                self._json(code, {"error": {"code": code, "message": message, "status": status}})

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", 0))
                body = json.loads(self.rfile.read(length) or b"{}")
                outer.requests.append({
                    "path": self.path,
                    "headers": {k.lower(): v for k, v in self.headers.items()},
                    "body": body,
                })
                if ":generateContent" not in self.path and ":streamGenerateContent" not in self.path:
                    return self._error(404, "NOT_FOUND", "models/x is not found")

                contents = body.get("contents", [])
                last_user = next((c for c in reversed(contents) if c.get("role") == "user"), {})
                text = "".join(p.get("text", "") for p in last_user.get("parts", []))
                key = key_from_headers(self.headers)

                if "ERRO401" in text or key == "ERRO401":
                    return self._error(400, "INVALID_ARGUMENT", "API key not valid. Please pass a valid API key.")
                if "ERRO429" in text:
                    return self._error(429, "RESOURCE_EXHAUSTED", "Resource has been exhausted (e.g. check quota).")
                if "FLAKY" in text:  # 503 nas duas primeiras tentativas, depois funciona
                    outer.attempts[text] = outer.attempts.get(text, 0) + 1
                    if outer.attempts[text] <= 2:
                        return self._error(503, "UNAVAILABLE", "This model is currently experiencing high demand.")
                if "ERRO529" in text:
                    return self._error(503, "UNAVAILABLE", "The model is overloaded. Please try again later.")

                if ":streamGenerateContent" not in self.path:
                    return self._json(200, {**_chunk("ok", "STOP"),
                                            "usageMetadata": {"promptTokenCount": 8, "candidatesTokenCount": 2,
                                                              "totalTokenCount": 10}})

                answer = build_exercise_answer(text) or build_answer(text)
                slow = "LENTO" in text
                refusal = "RECUSA" in text
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Transfer-Encoding", "chunked")
                self.end_headers()

                def send(payload: dict) -> None:
                    data = f"data: {json.dumps(payload)}\r\n\r\n".encode()
                    self.wfile.write(f"{len(data):x}\r\n".encode() + data + b"\r\n")
                    self.wfile.flush()

                try:
                    pieces = [answer[i:i + 7] for i in range(0, len(answer), 7)]
                    for index, piece in enumerate(pieces):
                        if refusal and index == 2:
                            send({"candidates": [{"finishReason": "SAFETY", "index": 0}],
                                  "usageMetadata": {"promptTokenCount": 410, "totalTokenCount": 410}})
                            break
                        last = index == len(pieces) - 1
                        send(_chunk(piece, "STOP" if last else None, usage=last))
                        if slow:
                            time.sleep(0.15)
                    self.wfile.write(b"0\r\n\r\n")
                    self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    pass

        return Handler


def main() -> None:
    """python -m tests.fake_gemini [porta] — sobe o servidor falso para testes manuais."""
    import sys
    server = FakeGeminiServer(int(sys.argv[1]) if len(sys.argv) > 1 else 0).start()
    print(f"Servidor falso do Gemini em {server.base_url}", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        server.stop()


if __name__ == "__main__":
    main()
