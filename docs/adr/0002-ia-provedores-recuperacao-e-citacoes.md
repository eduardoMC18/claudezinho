# ADR 0002: provedores de IA independentes, recuperação por BM25 e citações validadas

**Status:** aceito (Fase 2). **Não validado com a API real** (sem chave no ambiente de desenvolvimento).

## Decisões
1. **Provedores atrás de `AIProvider`**, sem estado de chave; o catálogo (`catalog.json`) concentra modelos, preços e limites. Adicionar um provedor = classe + registro + catálogo.
2. **SDK oficial `anthropic`** (dependência nova, justificada: streaming, tipos de erro, retentativas e compatibilidade com os parâmetros atuais dos modelos Claude 5).
3. **Chaves no Windows Credential Manager** via `keyring` (dependência nova); a API nunca devolve a chave.
4. **Recuperação por texto (BM25/FTS5)** atrás da interface `Retriever`; colunas de embedding reservadas na tabela `chunk`. Busca por documento + visão geral para perguntas de resumo/comparação.
5. **Chunks de ~1000 caracteres com sobreposição de ~200, sempre dentro de uma página**, para a citação nunca perder a página. Frases que cruzam a fronteira entre páginas não são unidas (limitação conhecida).
6. **Citações validadas no backend:** a IA escreve rótulos `[A p.N]`; só páginas dos trechos enviados são aceitas e o resto é descartado. A interface recebe links `ref:SLOT:PAGINA`.
7. **SSE** para o streaming; parar é uma chamada separada (o texto parcial é mantido).
8. **Modelo padrão `claude-opus-5-5`**, configurável; Sonnet 5.5 e Haiku 5.5 no catálogo como opções mais baratas.

## Consequências e limitações
- Sem embeddings, perguntas conceituais com vocabulário diferente do texto podem não achar o trecho (a IA então diz que não encontrou nos trechos recuperados).
- O custo mostrado é estimado pelo catálogo (sem cache de prompt e sem a faixa de preço acima de 100K tokens do Haiku).
- PDFs escaneados não funcionam (sem OCR).

## Adendo (14/11/2026): Gemini

O Google Gemini entrou como segundo provedor sem mudar o chat, a recuperação nem as citações: classe `GeminiProvider` + registro + `catalog.json` (a interface não tem texto específico de provedor). Dependência nova justificada: SDK oficial `google-genai` (streaming, tipos de erro e compatibilidade com os modelos atuais). Pontos específicos: o raciocínio interno é cobrado como saída; bloqueios de segurança viram erro `refused`; preços temporários ficam só no catálogo.
