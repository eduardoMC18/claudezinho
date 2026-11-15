# estudoTron 3000

Assistente pessoal para estudos e projetos acadêmicos. Programa desktop instalado no computador do usuário (inicialmente Windows 11).

**Primeira funcionalidade:** carregar dois arquivos PDF, visualizá-los lado a lado e conversar com uma IA (Claude) sobre o conteúdo deles.

## Status atual

- Fase: **Fase 2 (IA) implementada em 13/11/2026 e validada com servidores Anthropic e Gemini FALSOS** (pytest 70/70, `mvn test` 36/36, e o app real rodando ponta a ponta contra o servidor falso: configurações, chave no Credential Manager, pergunta, streaming, Markdown, citações clicáveis, parar, erro, nova conversa). **Nenhum provedor foi testado contra a API real** (não há chaves neste ambiente): o primeiro teste real é "Configurações → colar a chave → Testar conexão", para cada provedor. **Gemini adicionado em 14/11/2026** (ver a seção de IA).
- Fases 0 e 1 (PDFs lado a lado, SQLite/FTS5, sessão restaurada) concluídas antes.
- Fase 0 concluída antes (PDF.js 3.11.174 legacy validado na WebView).
- Banco: `%APPDATA%\estudoTron3000\estudotron.db` (troque a pasta com `ESTUDOTRON_DATA_DIR` ou `-Destudotron.data.dir=...`). Migrações em `backend/app/infra/migrations/` (PyInstaller deve empacotá-las junto com `app/static`).
- Um PDF é "sem texto" (`no_text`) quando menos de 10% das páginas têm 20+ caracteres de texto.
- Toolchain instalado em 12/11/2026: JDK 21 (Temurin, via winget), Python 3.12 (winget), Maven 3.9.9 em `C:\Users\pczao\tools\apache-maven-3.9.9`. Variáveis de usuário: `JAVA_HOME`, `MAVEN_OPTS` (usa o repositório de certificados do Windows, pois o Java não confiava no certificado da rede). Terminais abertos antes disso precisam ser reabertos.
- Apoio a testes: `-Destudotron.autoload=<pdf1>` e `-Destudotron.autoload.b=<pdf2>` carregam PDFs ao iniciar; o JS do visualizador reporta status no stdout como `[viewer] ...`.
- Fases 3+ só começam com autorização explícita do usuário.
- **Nome oficial: estudoTron 3000** (constante `AppInfo.NAME`, um único lugar para trocar). "StudyDesk" apareceu num pedido de 13/11/2026 e foi revertido pelo usuário no mesmo dia; o nome "StudyDesk" não é mais usado em nenhum arquivo.
- **Interface redesenhada em 13/11/2026** (FXML + controllers + componentes + CSS; tema atual: Liquid Glass neutro): capturas em `docs/screenshots/`. Chat fica **à direita** (PDF 1 | PDF 2 | Chat). `-Dstudydesk.script=@arquivo.txt` roda um roteiro de capturas/cliques/digitação (um passo por linha; ver `dev/ScreenshotScript` e o README).
- **Barra lateral minimalista (15/11/2026):** painel de vidro à esquerda (`sidebar.fxml` + `SidebarController`) só com a marca e a opção "Leitor de PDFs" (selecionada; reaproveita a tela atual em `contentHost`). Recolhe/expande com animação (232 → 76 px, preferência em `java.util.prefs`); recolhida mostra só ícones, com tooltip. Recolhe sozinha abaixo de 1320 px de janela. Largura mínima da janela agora 1140 px. A marca saiu do cabeçalho. Capturas `docs/screenshots/sb-*`.
- **Criar Exercícios (15/11/2026):** segunda tela da barra lateral (Leitor de PDFs | Criar Exercícios), sem chat: início com os PDFs carregados como cartões, duas modalidades (objetivos e subjetivos), questão, correção estruturada (selo de acerto/erro, explicação por alternativa, nota em anel, cartões de acertos/faltas, resposta ideal, referências clicáveis que abrem o PDF na página). Só usa o Gemini (mesma chave e modelo de Configurações). Ver "Exercícios com IA" abaixo e `docs/adr/0003-exercicios-com-ia.md`. Validado com o Gemini FALSO (pytest 104/104, `mvn test` 45/45 e o app rodando ponta a ponta); **não testado com o Gemini real**.
- **IA (Fase 2):** ver a seção "IA: provedores, recuperação e citações" e `docs/adr/0002-ia-provedores-recuperacao-e-citacoes.md`. Modelo padrão do catálogo: `claude-opus-5-5` (regra da skill claude-api quando o usuário não escolhe); mais baratos no mesmo catálogo: Sonnet 5.5 (~metade do custo) e Haiku 5.5 (~1/40). O usuário escolhe em Configurações.
- **Ainda não testado por mãos humanas:** seletor de arquivos, arrastar e soltar, divisores, hover/foco, e TODA a IA com chave real (resposta, custo mostrado, `fallbacks`, como o modelo cita páginas).

## Design: Liquid Glass (reformulado em 15/11/2026)

O tema pastel (verde/ciano/magenta, cartões brancos) foi rejeitado: "parece um dashboard convencional". A versão atual é um **Liquid Glass neutro**: ambiente branco/cinza-frio, vidro realmente translúcido e acento azul só no que é primário/foco. 100% JavaFX, sem dependências novas. Capturas em `docs/screenshots/lg-*`.

- **Por que o vidro "aparece":** com fundo uniforme não há o que desfocar. `AmbientBackground` desenha luz branca + toques frios e, **só na imagem desfocada**, esferas e anéis de prata/grafite (não aparecem nas frestas entre painéis). Ele se captura em 1/4 da resolução, aplica `GaussianBlur` uma vez e publica `frostProperty`; cada `GlassCard` mostra o pedaço que fica atrás dele **ampliado 4,5% (`LENS`, refração simulada)**. Só é refeito quando a janela muda de tamanho (debounce de 140 ms).
- **Material (CSS):** preenchimento quase transparente (`.glass` 14% branco) + reflexo (specular) no topo + brilho no canto + retorno de luz embaixo; **rim light** (borda branca forte em cima-esquerda/baixo-direita, apagada nas laterais) + fio interno (espessura) + fio escuro externo; sombra fria difusa em nó irmão (nunca efeito no pai de uma WebView).
- **Níveis:** `.glass` painéis (mais transparente) → `.glass-bar` cabeçalho (mais leitoso) → controles (botões, campos, chips: relevo e sombra de contato) → selecionado (vidro mais claro e elevado, sem cor: item ativo da barra, opção escolhida, chip ligado) → primário (vidro azul translúcido, o único colorido). Campos são "vidro afundado". Estados (certo/erro/aviso) são vidro tingido translúcido, nunca bloco chapado.
- **PDFs sempre sólidos:** `.viewer-host` e a página do visualizador são cinza-claro opacos; a lógica do PDF.js não mudou.
- **Tokens** no `.root` (`-g-text*`, `-g-blue`, `-g-accent-ink`, `-g-danger/success/warning`). Popups (menu do ComboBox, tooltip) não enxergam as variáveis: cores por extenso.
- **Movimento** (`GlassMotion`): hover = vidro clareia (`-g-hl`) e sobe 2,5%; clique afunda 4%; telas entram com fade + deslize. Nada anima em loop com a janela parada.
- **Ícones:** SVG de traço (Lucide) em grafite.
- **Limites do JavaFX:** o desfoque é do ambiente (não de outros painéis, que não se sobrepõem); só um `-fx-effect` por nó (sem sombra interna: simulada com gradientes); sem transições em CSS (feitas em Java); a lente é uma ampliação uniforme, não uma refração nas bordas; a barra de título é a do Windows.
- Verificação visual: `-Dstudydesk.script=@arquivo` (passos `click`, `text`, `scroll`, `shot`, `shot2`...).

## Stack obrigatória

| Camada | Tecnologia |
|---|---|
| Frontend | Java 21 + JavaFX |
| Build Java | Maven |
| Backend | Python + FastAPI |
| Banco | SQLite (com FTS5) |
| Visualização de PDF | PDF.js dentro de `WebView` do JavaFX (usar build "legacy") |
| Processamento de PDF | PyMuPDF |
| Comunicação | API REST local (+ SSE para streaming da IA) |
| IA | Claude (API da Anthropic), atrás de uma abstração `AIProvider` |
| Empacotamento futuro | jpackage (Java), PyInstaller (Python), WiX Toolset (instalador) |

**Regra:** não substituir nenhuma dessas tecnologias sem antes explicar o motivo técnico importante e pedir aprovação do usuário.

## Arquitetura

- O JavaFX inicia o backend Python como processo filho: porta livre aleatória, token de sessão, espera do `/health` antes de liberar a UI, encerramento ao fechar o app.
- A API escuta **somente em 127.0.0.1** e exige o token de sessão em todas as chamadas.
- Só o backend fala com a internet, e apenas com o provedor de IA.
- Frontend (`com.estudotron`): `controller` (um controller por FXML, criados em código; FXML em `resources/com/estudotron/fxml`, sem `fx:controller`), `component` (reutilizáveis: `PdfViewerPane`, `DropZone`, `DocumentChip`, `MessageBubble`, `MarkdownView`, `ReferenceChip`, `Toast`, `Icons`), `markdown` (parser puro, testado), `viewmodel` (`DocumentSlot`, `DocumentText`, `ChatMessage`), `service` (`ChatService`, `NotificationService`, `FileDialogService`), `backend` (`ApiClient` com SSE, DTOs, interfaces `BackendApi`/`ChatApi`/`AiSettingsApi`, gerenciador do processo Python), CSS em `resources/com/estudotron/css/liquid-glass.css`. Sem regra de negócio na view; ícones são SVGPath no código (sem dependências).
- Backend em camadas: `api` (routers finos, Pydantic) → `services` (regras: workspace, ingestão, chunking, recuperação, contexto, citações, prompts, chat, configurações de IA) → `domain` (modelos, erros) / `infra` (SQLite e repositórios, PyMuPDF, cofre de segredos, filtro de logs) e `providers` (interface `AIProvider`, `anthropic_provider`, `registry`, `catalog.json`).
- Cada PDF é exibido em uma `WebView` própria com PDF.js (3.11.174 legacy). Os arquivos do visualizador são servidos pelo próprio backend (ver `docs/adr/0001-viewer-pdfjs-servido-pelo-backend.md`). Ponte Java ↔ JavaScript para navegar páginas e receber eventos.

## Tela principal (requisitos definidos pelo usuário)

**Área superior**
- Nome do programa.
- Botão para carregar o primeiro PDF e botão para carregar o segundo PDF.
- Indicadores dos documentos carregados (ex.: nome do arquivo, nº de páginas, estado: vazio / carregando / pronto / erro).
- Acesso às configurações.

**Área central**
- Dois visualizadores de PDF: primeiro PDF à esquerda, segundo à direita.
- Divisão redimensionável entre os documentos (`SplitPane`).
- Navegação entre páginas, zoom e rolagem **independentes** em cada visualizador.
- Possibilidade de **substituir** e **remover** cada documento.
- **Ajuste automático de zoom (adicionado pelo usuário):** cada visualizador ajusta o zoom à largura do próprio painel (a página mais larga nunca é cortada) sempre que o painel muda de tamanho (divisor, janela, chat). Zoom manual (+/−) desliga o ajuste; o botão "Ajustar" da barra do visualizador o religa.
- **Soltar PDF (adicionado pelo usuário):** com o slot vazio aparece um quadrado tracejado "Arraste um PDF aqui" (clicar abre o seletor). Arrastar um PDF para qualquer painel o carrega automaticamente; em painel já ocupado, substitui o documento. Os handlers de arrasto são filtros no `DocumentPanelController` para a WebView nunca receber o arquivo.

**Painel de chat com a IA:** fica à **direita** dos dois PDFs, na altura toda (divisor horizontal ajustável, ~30% da largura; ao redimensionar a janela os PDFs absorvem a diferença). O botão "Chat" do cabeçalho o oculta/mostra, e os PDFs reajustam o zoom sozinhos. Contém: histórico com mensagens do usuário e da IA visualmente distintas, campo de texto (Enter envia, Shift+Enter quebra linha; cresce até 5 linhas), botão enviar, seletor de provedor, indicador de carregamento e área de referências aos PDFs (clicar numa referência leva o visualizador à página).

**Controles por documento (nativos do JavaFX, não da página PDF.js):** nome do arquivo, estado, remover, página anterior/próxima, campo "página / total", zoom −/+ e "Ajustar". Os PDFs ficam sempre lado a lado (largura mínima da janela 980 px, altura 560 px); a barra de controles quebra de linha se o painel ficar estreito. No cabeçalho, os indicadores passam de completos → compactos (< 1180 px) → ocultos (< 900 px).

Implicações de design: o estado de cada visualizador (página, zoom, posição) vive no seu próprio viewmodel e nunca é compartilhado. Remover/substituir um documento deve limpar o estado do visualizador, seu índice FTS5 e as citações associadas.

## IA: provedores, recuperação e citações

**Provedores (`backend/app/providers/`)**
- `AIProvider` (base.py): `stream(api_key, request, cancel)` e `test_connection(api_key, model)`. Provedores NÃO guardam a chave: ela é passada a cada chamada. Erros do SDK viram `ProviderError(kind, message)` (auth, rate_limit, network, overloaded, bad_request, not_found, refused).
- **Para adicionar um provedor:** (1) criar a classe `AIProvider`; (2) registrá-la em `registry.default_registry()`; (3) adicionar o provedor e seus modelos em `catalog.json`. Nada mais muda (a interface lista o que o catálogo e o registro têm).
- `catalog.json` é o ÚNICO lugar com ids de modelo, preços, `max_output_tokens`, `effort` e `refusal_fallback`, além dos orçamentos do chat (`context_token_budget`, `history_token_budget`, `chunks_per_document`). Um `catalog.json` na pasta de dados do usuário tem prioridade. O modelo escolhido por provedor fica na tabela `setting` (`ai.model.<provedor>`) e o provedor padrão em `ai.default_provider`.
- **Gemini (Google):** `gemini_provider.py`, SDK oficial `google-genai` (3.x), `generate_content_stream`; papéis `user`/`model`; o raciocínio interno (thinking) é ignorado no texto e COBRADO como saída (`thoughts_token_count` soma em `output_tokens`); parada por `SAFETY`/`PROHIBITED_CONTENT`/`BLOCKLIST` vira erro `refused`; `MAX_TOKENS` = truncada; chave inválida chega como HTTP 400 "API key not valid" e vira `auth`. Modelos do catálogo (ids e preços conferidos nas páginas do Google em 14/11/2026 e **testados com uma chave real em 14/11/2026**): `gemini-3.5-flash-lite` (PADRÃO: respondeu em ~0,7 s), `gemini-3.8-flash` (devolveu 503 "high demand" no teste real; por isso deixou de ser o padrão), `gemini-3.6-flash`, `gemini-4-hydrogen` (respondeu em ~6 s, com raciocínio). `gemini-3.1-pro-preview` foi REMOVIDO: a API real respondeu 404 (não existe para a chave). `models.list` da chave real também mostrou `gemini-3.7-flash`, `gemini-4-argon`, `gemini-4-carbon` (não incluídos). Chamadas 5xx são repetidas 4 vezes (1/2/4 s) antes de virar erro. Os preços de 3.8 Flash são promocionais até 31/12/2026 (depois US$ 1,50/7,50) e os do Gemini 4 Argon/Carbon dobram em 01/12/2026 — por isso ficam só no `catalog.json`. A janela de contexto de 1M só foi confirmada para o Hydrogen; os demais usam 1M por suposição (campo apenas informativo). `effort` não se aplica. Base URL de teste: `GOOGLE_GEMINI_BASE_URL`.
- Anthropic: SDK oficial `anthropic` (2.x; envia a chave em `Authorization: Bearer`), streaming, `output_config.effort`, sem `temperature`/prefill (removidos nos modelos atuais; raciocínio sempre ligado no Opus 5.5, por isso `max_tokens=16000`). Opus e Sonnet 5.5 usam `fallbacks="default"` com o beta `server-side-fallback-2026-07-01` (config `refusal_fallback`); Haiku 5.5 não.

**Chaves e segurança**
- Chave no Windows Credential Manager via `keyring` (serviço `estudoTron3000`, usuário `<provedor>-api-key`: `anthropic-api-key`, `gemini-api-key`; uma por provedor). Nunca em SQLite, arquivos, logs, nem devolvida pela API (`GET /settings/ai` só informa `has_key`). A interface envia a chave uma vez (`PUT /settings/ai/providers/{id}/key`), limpa o campo e não a guarda. `RedactSecretsFilter` censura padrões `sk-ant-…` e `AIza…` nos logs; `KeyRequest.__repr__` não imprime a chave. "Testar conexão" aceita a chave digitada sem salvá-la.

**Fluxo de uma pergunta (`services/chat.py`)**
1. `prepare` (valida; se falhar, nada é gravado e o texto volta ao campo): pergunta não vazia → há PDF carregado → nenhum ainda indexando → nem todos sem texto → provedor, modelo e chave.
2. Recuperação (`services/retrieval.py`, interface `Retriever`; hoje `Bm25Retriever` sobre FTS5): termos sem stopwords, radical simples (prefixo), expressão FTS5 sempre entre aspas; busca POR DOCUMENTO (os dois entram no contexto); perguntas de resumo/comparação/diferença misturam trechos espalhados do documento; termos da pergunta anterior entram em "e no segundo?". Colunas `chunk.embedding`/`embedding_model` reservadas para busca vetorial.
3. Chunking (`services/chunking.py`, `CHUNKER_VERSION=2`): ~1000 caracteres, sobreposição ~200, corte em fim de frase ou palavra, cada trecho em UMA página. Mudar a versão reindexa tudo na abertura.
4. Contexto (`services/context.py`): intercala A/B por relevância até o orçamento de tokens (~3 caracteres/token), agrupa por documento e página, neutraliza tags falsas; o prompt (`services/prompts.py`) manda citar só rótulos presentes e tratar o texto dos PDFs como dados, não instruções.
5. Histórico: turnos anteriores completos até `history_token_budget`, com citações de volta a rótulos curtos; erros e respostas interrompidas não voltam como contexto.
6. Resposta em SSE (`POST /chat/stream`: eventos `meta`, `delta`, `done`, `error`); parar = `POST /chat/messages/{id}/cancel` (o texto parcial é mantido, status `cancelled`); fechar a conexão também cancela.
7. **Citações (`services/citations.py`):** a IA escreve `[A p.12]`; `CitationRewriter` valida em fluxo contra as páginas dos trechos realmente enviados e converte em link Markdown `[(arquivo.pdf, p. 12)](ref:A:12)`. Páginas inventadas são removidas (contadas em `usage.invalid_citations_removed`). As referências da mensagem vêm só dos metadados dos trechos. Clicar leva o PDF à página; se o documento foi substituído desde então, avisa.
8. Histórico em SQLite: tabelas `conversation` e `message` (id, conteúdo, papel, data/hora UTC, provedor, modelo, status, erro, referências em JSON, uso/custo em JSON). "Nova conversa" cria outra e mantém as antigas (`GET /conversations`).

**Testes sem gastar créditos:** `backend/tests/fake_anthropic.py` e `fake_gemini.py` imitam as APIs (SSE) e os SDKs oficiais falam com eles via `ANTHROPIC_BASE_URL` / `GOOGLE_GEMINI_BASE_URL`; gatilhos na pergunta: `ERRO401`, `ERRO429`, `ERRO529`, `RECUSA`, `INVENTA`, `LENTO`. `python -m tests.fake_anthropic 18770` / `python -m tests.fake_gemini 18771` sobem os servidores para rodar o app inteiro.

## Exercícios com IA

- **Backend:** `services/exercises.py` (`ExerciseService`), `exercise_schema.py` (validação pydantic do JSON do modelo), `exercise_prompts.py`, `api/exercises.py` (`POST /exercises/generate`, `POST /exercises/{id}/answer`). `CompletionRequest.json_mode` ativa `response_mime_type=application/json` no Gemini. Provedor fixo `gemini` (`EXERCISE_PROVIDER`).
- **Contexto:** geração = janela de trechos consecutivos de cada PDF pronto (`DocumentRepository.window_chunks`) com `build_context`; correção discursiva = trechos citados na questão + `Bm25Retriever` pela pergunta e resposta. Referências só valem para páginas enviadas à IA.
- **Gabarito só no backend** até o envio; alternativas embaralhadas no servidor; objetiva é corrigida localmente; discursiva pelo Gemini. Questões ficam em memória (últimas 40).
- **Frontend:** `ExercisesController` (fluxo e estados), `ExerciseHomeController` (início), componentes `ModeCard`, `SourceDocCard`, `OptionButton`, `ObjectiveExercisePane`/`SubjectiveExercisePane`, `GradeResultPane`, `ScoreRing`, `StatusPanel`; `ExerciseService` (assíncrono, testável) e `ExerciseApi`/`ExerciseDtos`. As duas telas ficam na mesma cena (a oculta sai do layout): os PDFs nunca são descarregados.
- **Testes sem custo:** `tests/fake_gemini.py` responde JSON de exercícios (marcador `[[TAREFA:...]]`); gatilhos no material/resposta: `JSONRUIM`, `SEMREF`, `ERRADO`, `PARCIAL`, `INVENTA`, além dos já existentes (`ERRO429`, `ERRO529`...). `-Dstudydesk.script` ganhou o passo `scroll:#id=fração`.

## Pipeline de PDF

1. PyMuPDF extrai o texto por página (com hash do arquivo para evitar reprocessamento).
2. O texto é segmentado em trechos (com sobreposição) que guardam documento e página de origem e são indexados em FTS5.
3. **Os PDFs podem ter mais de 100 páginas:** nunca enviar documentos inteiros; só trechos recuperados. Embeddings ficam como evolução futura.
4. PDFs sem texto extraível (escaneados): avisar o usuário. OCR está fora do escopo atual.

## Segurança e privacidade

- Chave da API de IA no Credential Manager do Windows (`keyring`). Nunca em arquivo de texto, SQLite, logs, repositório nem enviada de volta à interface (ver "IA: provedores, recuperação e citações").
- Avisar o usuário de que o conteúdo dos PDFs é enviado ao provedor de IA durante o chat.
- Os PDFs não são copiados: guarda-se caminho e hash, com aviso se o arquivo mudar ou sumir.

## Estrutura planejada do repositório

```
estudotron3000/
├── frontend/     # Maven + JavaFX
├── backend/      # FastAPI (app/{api,services,domain,infra}, tests/)
├── packaging/    # jpackage, PyInstaller, WiX (futuro)
└── docs/         # ADRs, contrato da API, modelo de dados
```

## Modelo de dados (SQLite)

Workspace, Document (posição A/B, caminho, hash, nº de páginas), Chunk (FTS5 + colunas de embedding reservadas), Conversation, Message (conteúdo, papel, data, provedor, modelo, status, erro, referências, uso), Setting. Migrações versionadas (`0001_initial`, `0002_chat`).

## Roteiro

- **Fase 0:** JavaFX abre, sobe o FastAPI e conversa com ele; PDF aberto na WebView com PDF.js legacy (valida o maior risco técnico).
- **Fase 1:** dois PDFs lado a lado, ingestão com PyMuPDF, persistência.
- **Fase 2:** chat com Claude, streaming, recuperação BM25, citações clicáveis, configuração do provedor. (**implementada**; falta validar com chave real)
- **Fase 3:** polimento (erros, PDFs sem texto, progresso de indexação) e testes.
- **Fase 4:** empacotamento (jpackage, PyInstaller, WiX).

## Riscos conhecidos

- A WebView do JavaFX (WebKit) tem JavaScript mais antigo: usar PDF.js legacy e validar cedo com PDF real e grande.
- Duas WebViews com PDF.js consomem memória: limitar cache e testar com PDFs de 100+ páginas.
- PyInstaller pode gerar falso positivo de antivírus (relevante só no empacotamento).

## Convenções de trabalho

- Responder e documentar em português (pt-BR). Identificadores de código em inglês.
- Decisões de arquitetura relevantes viram um ADR em `docs/`.
- Testes: pytest no backend (servidor Anthropic falso, nunca chamadas reais), JUnit no frontend (inclui cliente SSE contra servidor HTTP local) e verificação ponta a ponta com `-Dstudydesk.script`.
- Não commitar segredos. Não adicionar dependências sem justificar.
- Ao concluir cada fase, resumir o que foi feito e o que não foi verificado.
