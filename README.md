# estudoTron 3000

Assistente pessoal para estudos: dois PDFs lado a lado + chat com IA (Claude) que responde usando os dois documentos e cita arquivo e página. Veja `CLAUDE.md` para a visão geral e `docs/` para as decisões.

## Pré-requisitos (Windows)
- JDK 21 (`java -version`)
- Maven 3.9+ (`mvn -v`)
- Python 3.11+ (recomendado 3.12)

Se o Maven falhar com `PKIX path building failed`, o Java não confia no certificado da sua rede (antivírus/proxy). Use o repositório do Windows:
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT -Djavax.net.ssl.trustStore=NUL`

## Backend (FastAPI)
```powershell
cd backend
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements-dev.txt
.\.venv\Scripts\python -m pytest
```

## Frontend (JavaFX)
O frontend sobe o backend sozinho (usa `backend\.venv` se existir).
```powershell
cd frontend
mvn test
mvn javafx:run
```

## Usar a IA (primeira vez)
1. Abra o app, carregue um ou dois PDFs e espere o indicador do cabeçalho ficar "pronto".
2. Clique em **Configurações** (ícone no canto superior direito), escolha o **provedor** (Claude ou Gemini) e cole a chave da API dele em "Chave da API" → **Salvar chave**. Cada provedor tem a sua chave; a do Gemini é gerada no Google AI Studio e começa com `AIza`.
   A chave vai para o Gerenciador de Credenciais do Windows e não é exibida de novo.
3. **Testar conexão** confirma chave, rede e modelo (faz uma chamada mínima real).
4. Escolha o **modelo** na mesma tela. O custo por milhão de tokens aparece abaixo da lista (Claude: Opus 5.5 é o padrão e Haiku 5.5 é bem mais barato; Gemini: 3.5 Flash-Lite é o padrão e o mais barato).
5. No chat, o seletor do cabeçalho troca de provedor a qualquer momento (cada resposta mostra o modelo que a gerou). Pergunte (Enter envia, Shift+Enter quebra linha). Clique nas citações `(arquivo.pdf, p. 8)` para ir à página.

Para adicionar modelos ou ajustar limites/preços sem mexer no código, edite `backend/app/providers/catalog.json` (ou crie um `catalog.json` em `%APPDATA%\estudoTron3000\`, que tem prioridade).

## Testar a IA sem gastar créditos
O backend tem servidores falsos da Anthropic e do Gemini para testes (`backend/tests/fake_anthropic.py`, `fake_gemini.py`). Para o Gemini use `python -m tests.fake_gemini 18771` e `$env:GOOGLE_GEMINI_BASE_URL="http://127.0.0.1:18771"`. Exemplo com a Anthropic:
```powershell
cd backend
.\.venv\Scripts\python -m tests.fake_anthropic 18770      # terminal 1
$env:ANTHROPIC_BASE_URL="http://127.0.0.1:18770"          # terminal 2
cd ..\frontend; mvn javafx:run
```
No app, salve qualquer chave (ex.: `sk-ant-teste`). Palavras na pergunta provocam situações: `ERRO401` (chave inválida), `ERRO429`, `ERRO529`, `RECUSA`, `INVENTA` (cita página inexistente, que o backend remove), `LENTO` (resposta demorada, para testar o botão parar). Depois, remova a chave em Configurações.

## Verificação visual (desenvolvimento)
Capturas e interações roteirizadas, sem clicar à mão. Crie um arquivo com um passo por linha (aceita espaços e acentos; `#` comenta) e aponte para ele:
```powershell
$env:JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstudydesk.script=@C:\temp\roteiro.txt"
cd frontend; mvn javafx:run
```
Passos: `wait:ms`, `size:LxA`, `shot:arquivo.png` (janela principal), `shot2:arquivo.png` (janela secundária, ex.: Configurações), `close2`, `click:#id` / `click:#id[1]` / `click:.classe[0]`, `text:#id=valor`, `press:#id=ENTER+SHIFT`, `dump:#id`, `exit`.
Também há `-Destudotron.autoload=<pdf1>` e `-Destudotron.autoload.b=<pdf2>` (carregam PDFs ao iniciar) e `-Destudotron.data.dir=<pasta>` (usa outro banco).
