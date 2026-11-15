# ADR 0001: visualizador PDF.js servido pelo backend

**Status:** aceito (Fase 0). **Revisar** se o teste real na WebView apontar problema.

## Contexto
O PDF.js roda dentro de uma `WebView` do JavaFX (WebKit). Carregar a página por `file://` ou `jar:` costuma bloquear Web Workers e `fetch` do PDF, e o PDF.js precisa de uma origem coerente.

## Decisão
Os arquivos do visualizador (`backend/app/static/viewer/`, com o PDF.js **3.11.174 build legacy**, classic script, sem módulos ES) são servidos pelo próprio FastAPI em `http://127.0.0.1:<porta>/viewer/`. O PDF é buscado em `/documents/{id}/file?token=...` na mesma origem.

## Consequências
- Sem problemas de CORS/worker em `file://`; a stack continua a mesma (PDF.js + WebView + REST local).
- O token vai na query só nesse endpoint (o PDF.js não envia cabeçalhos customizados). A API escuta apenas em 127.0.0.1.
- O PyInstaller precisa empacotar `app/static` (tarefa da Fase 4).
- PDF.js 3.x foi escolhido por ser o mais seguro para o JS do WebKit do JavaFX; só migrar para 4.x+ com teste na WebView.
