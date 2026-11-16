"""Ponto de entrada: python -m app --port N [--exit-on-stdin-eof]

O token de sessão vem da variável de ambiente ESTUDOTRON_TOKEN (não por
argumento, para não aparecer na lista de processos).
"""

import argparse
import os
import sys
import threading

import uvicorn

from app.infra.log_safety import install_redaction
from app.infra.secrets import MemorySecretStore
from app.main import create_app

TOKEN_ENV = "ESTUDOTRON_TOKEN"
SECRET_STORE_ENV = "ESTUDOTRON_SECRET_STORE"


def _exit_when_stdin_closes() -> None:
    """Encerra o backend se o frontend (dono do pipe de stdin) morrer."""

    def watch() -> None:
        try:
            sys.stdin.buffer.read()  # bloqueia até EOF
        finally:
            os._exit(0)

    threading.Thread(target=watch, name="stdin-watchdog", daemon=True).start()


def main() -> None:
    parser = argparse.ArgumentParser(prog="estudotron-backend")
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--exit-on-stdin-eof", action="store_true")
    args = parser.parse_args()

    token = os.environ.get(TOKEN_ENV)
    if not token:
        sys.exit(f"Variável de ambiente {TOKEN_ENV} não definida")

    if args.exit_on_stdin_eof:
        _exit_when_stdin_closes()

    # ESTUDOTRON_SECRET_STORE=memory: cofre em memória, SOMENTE para testes automatizados (as chaves somem
    # ao fechar e nada é gravado no Credential Manager). Sem a variável, o padrão é o cofre do sistema.
    secrets = MemorySecretStore() if os.environ.get(SECRET_STORE_ENV) == "memory" else None
    app = create_app(token, secrets=secrets)
    config = uvicorn.Config(app, host="127.0.0.1", port=args.port, log_level="info")
    server = uvicorn.Server(config)
    config.configure_logging()
    install_redaction()  # depois do logging do uvicorn estar configurado
    server.run()


if __name__ == "__main__":
    main()
