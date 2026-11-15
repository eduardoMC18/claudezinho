"""Rede de segurança: censura chaves de API que, por engano, apareçam em mensagens de log."""

import logging

from app.infra.secrets import redact


class RedactSecretsFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        try:
            message = record.getMessage()
        except Exception:  # formatação inválida: não derruba o log
            return True
        cleaned = redact(message)
        if cleaned != message:
            record.msg = cleaned
            record.args = ()
        return True


def install_redaction() -> None:
    """Anexa o filtro aos handlers do root e do uvicorn (chamado na inicialização)."""
    flt = RedactSecretsFilter()
    for name in ("", "uvicorn", "uvicorn.error", "uvicorn.access", "anthropic", "httpx2", "httpx"):
        logger = logging.getLogger(name)
        logger.addFilter(flt)
        for handler in logger.handlers:
            handler.addFilter(flt)
