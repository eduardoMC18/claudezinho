# ADR 0005 — Integração com o Canvas LMS (biblioteca de PDFs da faculdade)

Data: 20/11/2026 · Status: implementada e validada contra um Canvas FALSO (nenhuma conta real foi usada).

## Auditoria (o que já existia e foi reaproveitado)

- Um PDF é um `Document` no SQLite (slot A/B, `path`, `sha256`, `pages`, `status`) e a ingestão (`IngestionService`) lê **`doc.path`** com PyMuPDF, divide em trechos por página e indexa no FTS5. Chat, exercícios, citações e busca só dependem de `Document` + `chunk`. O visualizador (PDF.js) carrega `GET /documents/{id}/file`.
- Conclusão: um PDF do Canvas só precisa virar um `Document` cujo `path` aponta para um arquivo local temporário. **Não existe um segundo sistema de documentos**: chat, exercícios, citações (nome do arquivo + página) e busca funcionam sem nenhuma alteração.

## Decisões

1. **Autenticação: token pessoal do Canvas** (Conta › Configurações › Novo token de acesso). Funciona em qualquer instituição, sem ação da TI. O token vai uma vez da tela de Configurações ao backend, é validado (`GET /api/v1/users/self`), guardado no **Credential Manager** (`canvas-token`, mesmo cofre das chaves de IA) e o campo é limpo. Nunca vai ao SQLite, a logs (padrão `\d+~...` e `Bearer ...` censurados), ao `localStorage`, ao DOM nem ao PDF.js. A senha da conta nunca é pedida.
2. **OAuth2 não foi implementado** porque exige que a TI da faculdade crie uma *Developer Key* (client_id/secret + URI de retorno) e isso não pode ser testado sem ela. Para ativar: o administrador cria a chave em Admin › Developer Keys com a URI de retorno do app; o backend então abre `/login/oauth2/auth`, recebe o `code` em `127.0.0.1` e troca em `/login/oauth2/token` (com refresh). O cofre, o cliente HTTP e a interface já estão separados para receber isso sem mudar o resto.
3. **O arquivo é baixado uma vez para um cache temporário** (`<pasta de dados>/canvas_cache/<id do documento>.pdf`), indexado e servido ao PDF.js pelo endpoint existente — que já suporta HTTP Range (`FileResponse`; o proxy do Electron repassa o cabeçalho). Motivo: o PyMuPDF precisa do arquivo inteiro para indexar, e as URLs assinadas do Canvas expiram (não servem para Range direto). Limites: 400 MB por PDF, 600 MB no total, 14 dias sem uso; limpeza na abertura e a cada download (órfãos, velhos, excedente). Se o cache for limpo, o arquivo é baixado de novo sozinho quando o PDF é aberto; se o conteúdo mudou no Canvas, o documento é reindexado.
4. **O token só acompanha pedidos ao domínio do Canvas.** Redirecionamentos são seguidos manualmente: a URL assinada do CDN (outro host) recebe o pedido SEM `Authorization`. Paginação (`Link: rel="next"`) recusa URLs de outro host.
5. **Atualização**: cada PDF guarda `modified_at|size` do Canvas. Ao abrir, uma consulta leve (`GET /files/:id`) detecta versão nova e a interface oferece "Atualizar" (baixa, compara o hash e, se mudou, apaga os trechos e reindexa, mantendo slot e id). Citações antigas de conversas passadas podem apontar para páginas da versão anterior.
6. **Cache de metadados** (`canvas_cache` no SQLite): disciplinas 6 h, módulos 1 h, itens 15 min; botão de atualizar força; "atualização automática" (liga/desliga em Configurações) só reconsulta com o cache vencido. Sem rede, mostra a última lista salva com aviso. Limite de requisições do Canvas: uma nova tentativa curta e depois mensagem clara.
7. **Itens de módulo**: `File` (PDF → abrível; outros tipos, bloqueados ou ocultos → listados, inativos, com o motivo), `Page` (a página é buscada e os PDFs referenciados nela aparecem como "Na página …"), `SubHeader` (subtítulo), `ExternalUrl/ExternalTool/Assignment/Quiz/Discussion` (inativos). Cada curso também tem "Arquivos do curso (PDFs)" (`/courses/:id/files`). Arquivos repetidos aparecem uma vez.
8. **Imagem da disciplina não é exibida**: a URL do Canvas exigiria o token no navegador. Os cartões usam iniciais.
9. **Desconectar** remove o token, o cache de metadados, o histórico, os PDFs do Canvas dos painéis (e seus trechos indexados) e o cache de arquivos. O token **não é revogado no Canvas** (a API não revoga tokens pessoais): a tela avisa para removê-lo em Conta › Configurações › Integrações aprovadas.

## Endpoints oficiais usados

`GET /api/v1/users/self` · `/courses?enrollment_state=active&include[]=term` · `/courses/:id/modules` · `/courses/:id/modules/:id/items?include[]=content_details` · `/courses/:id/files/:id` · `/courses/:id/files?content_types[]=application/pdf` · `/courses/:id/pages/:url` · URL de download do arquivo (redirecionada ao CDN).

## API local (todas exigem o token de sessão do app)

`GET/PUT/DELETE /canvas/connection|status` · `PUT /canvas/preferences` · `GET /canvas/courses` · `/canvas/courses/{id}/modules` · `/canvas/courses/{id}/modules/{mid|files}/items` · `GET /canvas/history` · `PUT /canvas/slots/{A|B}` · `POST /canvas/documents/{id}/prepare|refresh` · `PUT /documents/{id}/progress` (última página; vale também para PDFs locais).

## Persistência (migração `0003_canvas.sql`)

`document.source` (`local|canvas`), `document.source_json` (instituição, disciplina, código, ID do curso, módulo, ID e nome do arquivo, `remote_modified_at`; sem URLs nem credenciais), `document.last_page`, tabelas `canvas_cache` e `canvas_history`.

## Interface

Cada painel vazio mostra "PDF 1/2", "Abrir PDF local" (clique ou arrastar, como antes) e a biblioteca Disciplinas → Módulos → Materiais com trilha clicável. O estado de navegação é **por painel** (`localStorage`, sem dados sensíveis). Com um PDF aberto, o botão "Canvas" abre a biblioteca por cima (sem fechar o PDF) onde o usuário parou; "Substituir" continua sendo o seletor local; "Remover" fecha só aquele painel.

## Não verificado

Nenhuma chamada foi feita a um Canvas real (só ao servidor falso `backend/tests/fake_canvas.py`, que imita a forma documentada da API). Pontos que só um Canvas real confirma: o campo `content-type` do arquivo (o app aceita `.pdf` quando vem vazio ou `octet-stream`), instituições que bloqueiam tokens pessoais, arquivos com `locked_for_user` por data e o comportamento exato do limite de requisições.
