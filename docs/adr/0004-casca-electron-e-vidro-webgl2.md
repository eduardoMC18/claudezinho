# ADR 0004 — Casca Electron e vidro WebGL2 (proposta em execução)

Status: **em validação** (Etapas 0 e 1 feitas; decisão final de migrar depende das etapas seguintes).

## Contexto

O Liquid Glass em JavaFX é uma aproximação: o `DisplacementMap` roda na CPU, a imagem atrás do vidro é capturada uma vez, não há shader próprio (sem dispersão, sem refração por perfil de bisel, sem luz especular dinâmica) e o PDF.js roda num WebKit antigo (build legacy). Pedido do usuário: priorizar o melhor Liquid Glass possível, com liberdade para trocar de tecnologia.

## Decisão (provisória)

Frontend em **Electron + TypeScript** (React + Vite nas próximas etapas), com um **motor de vidro WebGL2 próprio** (canvas atrás do DOM, três níveis de material: Lens, Film, Solid). **Backend Python/FastAPI, SQLite, `catalog.json` e testes ficam como estão.** O PDF.js passa a rodar no Chromium (build atual, com camada de texto).

## Evidências até agora

- **Etapa 0** (`lab/glass-lab`): em Chromium com GPU real (RTX 3050), 60 quadros/s sem perdas com 16 superfícies; refração por pixel com dispersão e luz no cursor, impossível no JavaFX. Medida é cadência de quadros, não tempo de GPU; sem medição de memória, GPU ou CPU; sem teste em GPU integrada.
- **Etapa 1** (`desktop/`): casca inicia o backend, proxy com token só no processo principal, SSE em streaming, `--selftest` passando (ver `desktop/README.md`).

- **Etapa 2** (`desktop/src/renderer`): React + Vite + TS; motor de vidro como módulo (Lens/Film/Solid), barra lateral e tela de Configurações com a API real; fallback CSS. Autoteste no Electron real (GPU real, backend real): motor iniciou e desenhou, 2 provedores e 7 modelos listados, escolha de modelo gravada, 300 quadros com média 16,67 ms / p95 16,8 ms / máx 16,9 ms (15 superfícies, cadência travada em 60 Hz, não tempo de GPU). Capturas em `docs/screenshots/electron-etapa2-*.png`.

- **Etapa 3** (Leitor de PDFs): PDF.js 6.5 no Chromium com camada de texto, rolagem virtualizada, ajuste à largura, dois painéis independentes, substituir/remover, sessão restaurada. 27 checks no autoteste com PDFs reais gerados (24 págs., escaneado, 8 págs.), 0 falhas. Pendente de verificação humana: seletor nativo e arrastar-e-soltar real. Capturas `docs/screenshots/electron-etapa3-*.png`.

- **Etapa 4** (Chat): SSE em streaming real contra o backend, Markdown seguro (sem HTML bruto), citações clicáveis que levam o PDF à página, parar, histórico, seletor de provedor/modelo, aviso de documento substituído. 43 checks no autoteste contra o servidor Gemini FALSO, 0 falhas. Achado real corrigido: o campo de página não ressincronizava quando se voltava à mesma página (`nav` no estado do visualizador). Única mudança no backend: `ESTUDOTRON_SECRET_STORE=memory` (só testes). **Nenhum provedor real foi testado.** Capturas `docs/screenshots/electron-etapa4-*.png`.

- **Etapa 5** (Criar Exercícios): início, objetiva e subjetiva com correção estruturada (nota em anel), referências que abrem o Leitor na página, estados de erro. **Todas as telas do JavaFX têm equivalente funcional no Electron.** 55 checks no autoteste contra o Gemini FALSO, 0 falhas. Capturas `docs/screenshots/electron-etapa5-*.png`. Pendente para decidir a troca: teste manual (seletor, arrastar-e-soltar), chaves reais, empacotamento (electron-builder + PyInstaller, assinatura) e a medição de Tauri.

## Alternativas e por que ainda não decididas

- **Tauri/WebView2:** mesmo motor Chromium no Windows, instalador menor, mas exige Rust e o WebView2 se atualiza sozinho (shader pode variar entre máquinas); em macOS cairia no WKWebView sem o efeito fiel. **Não foi medido**; deve ser comparado com o mesmo laboratório antes da decisão final.
- **Manter JavaFX:** teto visual já atingido.
- **Qt Quick (PySide6):** refrata conteúdo vivo (inclusive PDF), mas perde o PDF.js, exige reescrever chat/Markdown em QML e empacotar Qt.

## Restrição de arquitetura descoberta na Etapa 0

O canvas fica atrás do DOM; qualquer contêiner opaco esconde o vidro (e os botões de vidro dentro dele). PDFs são o único conteúdo Solid; os quadros em volta são Film. Refratar a página de um PDF real exigiria passá-la como textura (fase opcional).

## Critério de parada

Se, nas próximas etapas, o resultado visual não superar claramente o JavaFX ou a paridade funcional (PDFs, chat, citações, exercícios) não for atingida, o JavaFX permanece e esta casca é descartada.
