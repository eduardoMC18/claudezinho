# ADR 0003 — Criar Exercícios com IA

Data: 15/11/2026 · Status: implementado (validado com o Gemini FALSO; falta teste com chave real)

## Contexto
Nova tela "Criar Exercícios", independente do Leitor de PDFs, que gera questões objetivas e discursivas a partir dos
PDFs já carregados e corrige as respostas. Não é um chat: o backend devolve JSON validado e a interface monta
componentes (alternativas, nota, cartões de acertos/faltas, referências).

## Decisões
- **Mesmos PDFs, nenhuma cópia.** A tela observa os mesmos `DocumentSlot` do leitor (frontend) e o backend usa o
  workspace, os trechos por página e o FTS5 que já existiam. A tela do leitor e a de exercícios ficam na mesma
  cena; a oculta sai do layout (`visible/managed = false`), então navegar nunca descarrega PDFs nem mexe em zoom/página.
- **Só o Gemini**, pela integração existente (`AiSettingsService.resolve("gemini")`: mesma chave do cofre, mesmo modelo
  escolhido em Configurações). `CompletionRequest.json_mode` pede `response_mime_type=application/json`; os demais
  provedores ignoram o campo. Nenhum provedor novo, nenhuma segunda chave.
- **Contexto sem enviar PDFs inteiros.** Geração: uma janela de trechos consecutivos (`repo.window_chunks`, ponto aleatório)
  de cada PDF pronto, montada por `build_context` com o mesmo orçamento do chat. Correção discursiva: trechos citados
  pela questão + recuperação BM25 pela pergunta e pela resposta (`Bm25Retriever`).
- **Validação antes da interface** (`exercise_schema.py`, pydantic): campos, 3–5 alternativas distintas, gabarito
  existente, nota 0–10. Referências só valem se apontarem para páginas realmente enviadas (as inventadas são
  descartadas; sem nenhuma válida, a questão é rejeitada). Resposta inválida ou cortada: UMA nova tentativa explicando
  o problema; persistindo, erro `invalid_response`.
- **Gabarito só no backend** até o envio: a questão gerada não traz resposta correta, explicações nem critérios
  (`public_exercise`). As alternativas são embaralhadas no servidor (o modelo viciaria em "b/c").
- **Correção objetiva é local** (o gabarito e a explicação de cada alternativa vieram na geração): instantânea e sem custo.
  A discursiva chama o Gemini com enunciado, critérios, resposta de referência, resposta do aluno e trechos; o prompt manda
  não exigir as mesmas palavras e ignorar instruções escritas na resposta do aluno.
- **Exercícios ficam em memória** (últimos 40), sem tabela nova: histórico de exercícios não foi pedido.
  Um envio com falha não consome a questão; um segundo envio com sucesso devolve 409.

## API
`POST /exercises/generate {kind: objective|subjective}` → questão · `POST /exercises/{id}/answer {option | text}` → correção.
Erros: `{detail: {kind, message}}` (`no_documents`, `indexing`, `no_text`, `no_key`, `auth`, `rate_limit`, `overloaded`,
`network`, `refused`, `invalid_response`, `no_selection`, `empty_answer`, `already_answered`, `not_found`).

## Limites conhecidos
- Qualidade real das questões e da correção só se confirma com o Gemini de verdade.
- Reiniciar o app apaga as questões em andamento.
- Gerar questão sobre um assunto específico (campo de tema) ficou de fora por não ter sido pedido.
