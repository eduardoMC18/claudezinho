"""Cofre de segredos (chaves de API).

Em produção usa o Windows Credential Manager (via `keyring`). Segredos nunca vão para o
SQLite, para arquivos de configuração, para logs nem de volta para a interface.
"""

import re
from typing import Protocol

import keyring
import keyring.errors

SERVICE_NAME = "estudoTron3000"

# Padrão de chaves conhecidas, usado para censurar qualquer vazamento acidental em logs.
_KEY_PATTERN = re.compile(r"sk-ant-[A-Za-z0-9_\-]{6,}|AIza[0-9A-Za-z_\-]{20,}")


class SecretStoreError(Exception):
    """O armazenamento seguro do sistema não está disponível."""


class SecretStore(Protocol):
    def get(self, name: str) -> str | None: ...
    def set(self, name: str, value: str) -> None: ...
    def delete(self, name: str) -> None: ...
    def has(self, name: str) -> bool: ...


class KeyringSecretStore:
    """Windows Credential Manager (ou o cofre equivalente de outro sistema)."""

    def get(self, name: str) -> str | None:
        try:
            return keyring.get_password(SERVICE_NAME, name)
        except keyring.errors.KeyringError as exc:
            raise SecretStoreError("O cofre de credenciais do sistema não está disponível.") from exc

    def set(self, name: str, value: str) -> None:
        try:
            keyring.set_password(SERVICE_NAME, name, value)
        except keyring.errors.KeyringError as exc:
            raise SecretStoreError("Não foi possível gravar no cofre de credenciais do sistema.") from exc

    def delete(self, name: str) -> None:
        try:
            keyring.delete_password(SERVICE_NAME, name)
        except keyring.errors.PasswordDeleteError:
            pass  # já não existia
        except keyring.errors.KeyringError as exc:
            raise SecretStoreError("Não foi possível remover do cofre de credenciais do sistema.") from exc

    def has(self, name: str) -> bool:
        return bool(self.get(name))


class MemorySecretStore:
    """Apenas para testes."""

    def __init__(self) -> None:
        self._data: dict[str, str] = {}

    def get(self, name: str) -> str | None:
        return self._data.get(name)

    def set(self, name: str, value: str) -> None:
        self._data[name] = value

    def delete(self, name: str) -> None:
        self._data.pop(name, None)

    def has(self, name: str) -> bool:
        return name in self._data


def api_key_secret_name(provider_id: str) -> str:
    return f"{provider_id}-api-key"


def redact(text: str) -> str:
    """Substitui qualquer chave reconhecível por um marcador."""
    return _KEY_PATTERN.sub(lambda m: "sk-ant-***" if m.group(0).startswith("sk-") else "AIza***", text)
