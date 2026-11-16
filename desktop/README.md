# desktop — casca Electron (Etapa 1)

Nova casca do estudoTron 3000 que **substituirá** o frontend JavaFX quando houver paridade funcional (ver `docs/adr/0004-casca-electron-e-vidro-webgl2.md`). O app JavaFX em `frontend/` continua intacto e é o que vale até lá.

## O que a Etapa 1 faz

- Inicia o backend Python como processo filho (mesmo protocolo do `BackendManager.java`: porta livre, token por variável de ambiente, espera do `/health`, `--exit-on-stdin-eof`).
- Esquema próprio `app://local/`: serve a interface (`renderer/`) e faz **proxy** de `app://local/api/*` para o backend, **injetando o token no processo principal**. A página nunca vê o token nem a porta.
- Janela com `contextIsolation`, `sandbox`, sem `nodeIntegration`, CSP restrita, navegação limitada ao próprio esquema.
- Página mínima de verificação (não é a interface final).

## Comandos (PowerShell)

```powershell
cd desktop
node node_modules\typescript\bin\tsc -p tsconfig.json     # compila src/ -> dist/
node_modules\electron\dist\electron.exe .                  # abre a casca
node_modules\electron\dist\electron.exe . --selftest       # autoteste (janela oculta, banco temporário)
```

Variáveis: `ESTUDOTRON_PYTHON` (padrão: `backend/.venv`), `ESTUDOTRON_BACKEND_DIR`, `ESTUDOTRON_DATA_DIR` (o autoteste usa uma pasta temporária; não toca no banco real).

Observação do ambiente: nesta máquina o `node` não é encontrado pelos *shims* do `npm`/`npx` no Git Bash; use PowerShell ou chame `node ...` direto.

## Autoteste (`--selftest`)

Verifica: contexto isolado; ponte sem token/porta; `GET /api/health`; o proxy sobrescreve um token falso; `GET /api/workspace`; corpo JSON repassado; path traversal bloqueado; SSE repassado em streaming (5 eventos em ~600 ms, em 5 leituras, não em bloco); backend sem token responde 401 e escuta só em 127.0.0.1. Sai com código 0/1 e imprime `SELFTEST_RESULT {...}`.

## Limites conhecidos desta etapa

- O SSE foi provado contra um servidor SSE local de teste (mesma rota de repasse), **não** contra o `/chat/stream` real (exigiria chave de IA no Credential Manager).
- Não há empacotamento (electron-builder/PyInstaller) nem assinatura de código ainda.
- Tauri/WebView2 não foi medido; a escolha do Electron é provisória (ver ADR).
