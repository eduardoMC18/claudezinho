# desktop — casca Electron + interface React (Etapas 1 e 2)

Nova casca do estudoTron 3000 que **substituirá** o frontend JavaFX quando houver paridade funcional (ver `docs/adr/0004-casca-electron-e-vidro-webgl2.md`). O app JavaFX em `frontend/` continua intacto e é o oficial até lá.

## Estrutura

```
desktop/
├── src/main/        processo principal (tsc → dist/): backend.ts (sobe o Python), protocol.ts (app://local + proxy com token), main.ts
├── src/preload/     ponte mínima (sem token, sem porta, sem Node)
├── src/renderer/    interface React + TypeScript (Vite → renderer/)
│   ├── glass/       motor de vidro WebGL2: engine.ts, shaders.ts, spring.ts, GlassProvider, useGlass
│   ├── ui/          primitivos: GlassPanel (Film/Lens), GlassButton (Lens/Primary/Film), Sidebar, ícones
│   ├── screens/     Settings (API real de /settings/ai), Placeholder (telas ainda não migradas)
│   ├── styles/      tokens.css (inclui o material de reserva) e app.css
│   ├── api.ts       cliente do backend (tudo por /api/*)
│   └── selftest.ts  autoteste que roda dentro da interface real
└── renderer/        saída do build (não versionada)
```

## Etapa 1 — casca

- Inicia o backend Python (mesmo protocolo do `BackendManager.java`: porta livre, token por variável de ambiente, `/health`, `--exit-on-stdin-eof`).
- Esquema próprio `app://local/`: serve a interface e faz **proxy** de `app://local/api/*` para o backend **injetando o token no processo principal**; a página nunca vê token nem porta. Repasse em streaming (SSE).
- Janela com `contextIsolation`, `sandbox`, sem `nodeIntegration`, CSP restrita, navegação só dentro do esquema.

## Etapa 2 — sistema de design

- **Motor de vidro WebGL2** (canvas atrás do DOM): três níveis de material. **Lens** (refração por bisel, dispersão cromática, especular que segue o cursor, mola ao pressionar), **Film** (blur por LOD, sem refração), **Solid** (DOM opaco, fora do motor). Uso: `<GlassPanel>` e `<GlassButton variant="lens|primary|film">`.
- Regras embutidas: só o item ativo da barra e os controles são Lens; fundo animado só com a janela em foco e a 30 quadros/s em repouso; superfícies ordenadas por profundidade no DOM; limite de 40 superfícies.
- **Material de reserva** (CSS opaco/blur) quando não há WebGL2, quando o contexto é perdido (e volta ao ser restaurado) e com `prefers-reduced-transparency` / `forced-colors`.
- **Telas:** barra lateral, **Configurações** funcional (provedores, chave via Credential Manager, testar conexão, escolher modelo e provedor padrão), e placeholders honestos para Leitor de PDFs e Criar Exercícios (Etapa 3).

## Etapa 3 — Leitor de PDFs

- **PDF.js 6.5 no Chromium** (não mais o build legacy do WebKit), com camada de texto selecionável. Dados (CMaps, fontes padrão, wasm, ICC) copiados para `renderer/pdfjs/` por `scripts/copy-pdfjs-assets.mjs`.
- `viewer/pdfController.ts`: um controlador por painel (estado nunca compartilhado). Rolagem **virtualizada** (só as páginas perto da área visível têm canvas e texto; as distantes são liberadas), âncora ao dar zoom, tamanho de todas as páginas medido em segundo plano.
- **Ajuste automático à largura:** a página mais larga nunca é cortada; refaz ao redimensionar o painel/janela/divisor. Zoom manual (−/+) desliga o ajuste; **Ajustar** o religa.
- Dois painéis lado a lado com **divisor ajustável** (arrastar ou setas), navegação por campo de página, ‹ ›, e rolagem; indicadores de estado como no JavaFX (indexando n/N, pronto, sem texto pesquisável, arquivo ausente/alterado, erro).
- **Carregar/substituir/remover** por botão ("Carregar PDF 1/2" e "Substituir"), seletor nativo (`dialog:pickPdf`) e **arrastar e soltar** (`webUtils.getPathForFile` no preload). Sessão restaurada de `GET /workspace`. Progresso de indexação por polling de `GET /documents/{id}`.
- CSP ampliada só no necessário: `wasm-unsafe-eval`, `worker-src 'self' blob:`, `font-src`/`img-src` com `blob:`/`data:`.
- Autoteste gera PDFs com o PyMuPDF do backend (`tools/make_test_pdfs.py`): `texto.pdf` (24 págs., a 7ª em paisagem), `outro.pdf` (8) e `escaneado.pdf` (3, sem texto).

## Etapa 4 — Chat com a IA

- Painel à direita (~30% da largura, 320–460 px), botão **Chat** no cabeçalho oculta/mostra e os PDFs reajustam o zoom sozinhos. Estado em `chat/useChat.ts`, **no App** (uma resposta em andamento continua ao trocar de tela).
- **Streaming real**: `POST /api/chat/stream` lido por `chat/sse.ts` (SSE sobre `fetch`, eventos `meta`, `delta`, `done`, `error`). **Parar** chama `POST /chat/messages/{id}/cancel` e mantém o texto parcial (status "interrompida"). Se a validação falhar antes de gravar (sem chave, sem PDF, indexando...), nada é gravado e o texto **volta ao campo**.
- Campo: Enter envia, Shift+Enter quebra linha, cresce até 5 linhas. Seletores de provedor e modelo; **Nova** conversa e **Histórico** (reabre conversas antigas, que continuam salvas).
- **Markdown seguro** (`chat/Markdown.tsx`): só elementos React, nunca HTML bruto; títulos, listas, citações, tabelas, blocos de código, negrito/itálico/código/links. Só `http(s)` abre fora do app; `javascript:`, `file:` etc. viram texto.
- **Citações**: `[(arquivo.pdf, p. 12)](ref:A:12)` vira botão; clicar leva o PDF à página (e o campo de página acompanha). Chips "Referências" abaixo das mensagens fazem o mesmo. Se o documento foi **substituído/removido** desde a resposta, a interface avisa em vez de navegar.
- Rodapé por resposta: provedor · modelo · custo (US$) · tokens; marcas "interrompida", "cortada pelo limite do modelo" e "N citação(ões) inexistente(s) removida(s)".
- Material: bolhas de texto são **Solid** (opacas, fora do motor de vidro, por serem texto longo); o campo, os seletores e os botões são Film/Lens/Primary.
- **Backend:** única mudança, `ESTUDOTRON_SECRET_STORE=memory` (cofre em memória, **só para testes**; o padrão continua sendo o Credential Manager). O autoteste a define sempre, para nunca tocar na sua chave real, e **recusa-se a rodar o fluxo de chave se já houver uma chave do Gemini**.
- Autoteste: sobe o servidor Gemini **FALSO** do backend (`tests/fake_gemini.py`; o SDK oficial conversa com ele via `GOOGLE_GEMINI_BASE_URL`), sem custo e sem chave real.

## Comandos (PowerShell)

```powershell
cd desktop
node node_modules\typescript\bin\tsc -p tsconfig.json            # processo principal -> dist/
node node_modules\typescript\bin\tsc -p tsconfig.renderer.json   # checagem de tipos da interface
node node_modules\vite\bin\vite.js build                         # interface -> renderer/
node scripts\copy-pdfjs-assets.mjs                               # dados do PDF.js -> renderer/pdfjs/ (depois do vite build)
node_modules\electron\dist\electron.exe .                        # abre o app
node_modules\electron\dist\electron.exe . --selftest             # autoteste (janela oculta, banco temporário)
node_modules\electron\dist\electron.exe . --selftest --show      # idem, com janela visível (inclui medição de quadros)
node_modules\electron\dist\electron.exe . --selftest "--shots=C:\pasta"   # idem, salvando capturas
```

Variáveis: `ESTUDOTRON_PYTHON` (padrão: `backend/.venv`), `ESTUDOTRON_BACKEND_DIR`, `ESTUDOTRON_DATA_DIR` (o autoteste usa pasta temporária; **não toca no banco real nem no Credential Manager**).

Nesta máquina o `node` não é achado pelos atalhos do `npm`/`npx` no Git Bash; use PowerShell ou chame `node ...` direto (os scripts do `package.json` funcionam em terminais com `node` no PATH).

## Autoteste

Casca: contexto isolado; ponte sem token/porta; proxy (`/api/health`, token falso sobrescrito, corpo JSON repassado); path traversal bloqueado; SSE em streaming (5 eventos em ~620 ms, em 5 leituras); backend sem token responde 401 e escuta só em 127.0.0.1.
Interface: motor de vidro inicia e desenha; navegar para Configurações lista 2 provedores e 7 modelos da API real; nenhuma chave visível na tela; escolher outro modelo grava e a tela reflete; superfícies dentro do limite; medição de quadros (com `--show`/`--shots`).

**Leitor (PDFs reais gerados):** slot A com 24 páginas renderiza e é indexado; canvas com pixels de texto; camada de texto com o conteúdo da página; ajuste à largura (maior página 506 px em painel de 534 px); navegar para a página 12 e por ‹ › e rolagem; zoom manual desliga o ajuste e "Ajustar" volta ao mesmo valor (60% → 72% → 60%); slot B com PDF escaneado avisa "sem texto pesquisável" sem alterar A; substituir B zera página e zoom; os dois painéis ficam lado a lado com divisor; remover A mostra a área de soltar e o backend limpa o slot sem afetar B; sessão restaurada ao voltar para a tela; virtualização (no máximo 14 canvas ao mesmo tempo para 24 páginas) com o vidro mantendo ~16,7 ms/quadro durante a rolagem (cadência).

**Chat (servidor Gemini falso):** Markdown com 11 propriedades de segurança/formatação; sem chave o erro aparece e o texto volta ao campo sem gravar nada; resposta chegando em 21 tamanhos distintos (streaming progressivo) e fechando completa; título, tabela e bloco de código renderizados; rodapé com provedor/modelo/custo (US$ 0,0003 · 410 em / 85 saída); clicar na citação leva o PDF da página 24 à 1 e os chips de referência também; **parar** mantém o parcial e a resposta continua ao trocar de tela; erro 429 vira mensagem clara e o chat segue utilizável; citação inventada (p. 999) é removida e a interface avisa; nova conversa mantém a antiga no histórico e reabre com citações; citação de documento substituído avisa em vez de navegar; o botão Chat oculta/mostra o painel e os PDFs reajustam (389 → 568 px); a chave falsa não aparece no DOM, em `/settings/ai` nem no histórico. Total no autoteste: 43 checks, 0 falhas.

## Limites conhecidos

- **Nenhum provedor foi testado contra a API real nesta migração** (sem chaves): tudo do chat foi validado com o servidor Gemini FALSO, como já era no JavaFX. O primeiro teste real continua sendo "Configurações → colar a chave → Testar conexão". Não vi, por exemplo, como o modelo real cita páginas, o custo real mostrado nem o comportamento de `fallbacks`.
- Só o Gemini falso foi exercitado de ponta a ponta (a Anthropic usa o mesmo backend, mas não rodei o `fake_anthropic` neste autoteste).
- A virtualização de páginas libera por **distância em pixels**, não por contagem: com zoom pequeno (painel estreito) chegam a ~20 canvas de 24 páginas ao mesmo tempo, o que é pouco em memória por serem páginas pequenas; a memória real não foi medida.

- **Não verificado automaticamente (exige mãos humanas):** o seletor nativo de arquivos, o **arrastar e soltar real** de um arquivo do Explorer (o autoteste usa o mesmo caminho de código `setPath`, mas não o evento de drop), o arrasto do divisor com o mouse, e os estados "arquivo ausente/alterado" na interface.
- Não testei PDFs com 100+ páginas nem PDFs grandes/complexos (imagens JPX, formulários); o limite de memória real do visualizador não foi medido (só a contagem de canvas).
- O PDF.js 6.5 removeu `isEvalSupported` e `PDFDocumentProxy.destroy()`; o controlador usa `loadingTask.destroy()`.

- O SSE foi provado contra um servidor SSE local de teste (mesma rota de repasse), não contra o `/chat/stream` real (exigiria chave de IA).
- A medição de quadros é de **cadência** (média 16,67 ms, p95 16,8 ms, máx 16,9 ms com 15 superfícies, 300 quadros, RTX 3050, 1440×900, DPR 1), travada em 60 Hz; não é tempo de GPU. Não medi memória/CPU/GPU, nem GPU integrada.
- "Salvar chave", "Remover chave" e "Testar conexão" não foram exercitados no autoteste (tocariam no Credential Manager e/ou na rede); os endpoints são os mesmos do JavaFX.
- Telas Leitor de PDFs e Criar Exercícios ainda são placeholders. Sem empacotamento nem assinatura de código.
- Tauri/WebView2 não foi medido; a escolha do Electron segue provisória.
