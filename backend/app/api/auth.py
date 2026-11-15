"""Autenticação por token de sessão.

O token é gerado pelo frontend a cada execução e repassado ao backend por
variável de ambiente. A API só escuta em 127.0.0.1.
"""

import secrets

from fastapi import Header, HTTPException, Query, Request, status

TOKEN_HEADER = "X-Estudotron-Token"


def _check(request: Request, supplied: str | None) -> None:
    expected: str = request.app.state.token
    if not supplied or not secrets.compare_digest(supplied, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Token inválido ou ausente")


def require_token(
    request: Request,
    x_token: str | None = Header(default=None, alias=TOKEN_HEADER),
) -> None:
    """Exige o token no cabeçalho (chamadas da API)."""
    _check(request, x_token)


def require_token_header_or_query(
    request: Request,
    x_token: str | None = Header(default=None, alias=TOKEN_HEADER),
    token: str | None = Query(default=None),
) -> None:
    """Aceita o token no cabeçalho ou na query.

    Usado somente pelo endpoint que entrega o PDF, porque o PDF.js carrega o
    arquivo por URL e não consegue enviar cabeçalhos customizados.
    """
    _check(request, x_token or token)
