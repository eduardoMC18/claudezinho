/** Autoteste (--selftest): roda dentro da interface real e reporta ao processo principal. */
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { Markdown } from './chat/Markdown';

interface Check { name: string; ok: boolean; detail?: string }

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

async function waitFor<T>(fn: () => T | null | undefined | false, ms: number): Promise<T | null> {
  const end = performance.now() + ms;
  while (performance.now() < end) {
    const v = fn();
    if (v) return v;
    await sleep(100);
  }
  return null;
}

type CheckFn = (name: string, fn: () => Promise<string | void>) => Promise<void>;

const nav = (id: string) => document.querySelector<HTMLElement>(`[data-nav="${id}"]`)!.click();
const pane = (s: 'A' | 'B') => document.querySelector<HTMLElement>(`[data-pane="${s}"]`)!;
const setInput = (el: HTMLInputElement, v: string) => {
  Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(el, v);
  el.dispatchEvent(new Event('input', { bubbles: true }));
};
const pct = (s: 'A' | 'B') => parseInt(pane(s).querySelector('.zoom')!.textContent ?? '0', 10);
const pageField = (s: 'A' | 'B') => pane(s).querySelector<HTMLInputElement>('.pager input')!;
const click = (s: 'A' | 'B', sel: string) => pane(s).querySelector<HTMLElement>(sel)!.click();
const reader = () => (window as unknown as { __reader: { setPath(s: string, p: string): Promise<void>; clear(s: string): Promise<void> } }).__reader;

// ---------- Etapa 4: chat (contra o servidor Gemini FALSO) ----------

const chatEl = () => document.querySelector<HTMLElement>('.chat');
const ta = () => chatEl()!.querySelector<HTMLTextAreaElement>('textarea')!;
const setTa = (v: string) => {
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(ta(), v);
  ta().dispatchEvent(new Event('input', { bubbles: true }));
};
const bubbles = () => [...chatEl()!.querySelectorAll<HTMLElement>('.bubble')];
const lastAssistant = () => [...chatEl()!.querySelectorAll<HTMLElement>('.bubble.assistant')].pop();
const act = (a: string) => chatEl()!.querySelector<HTMLElement>(`[data-action="${a}"]`)!;
const selectValue = (sel: HTMLSelectElement, v: string) => {
  Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value')!.set!.call(sel, v);
  sel.dispatchEvent(new Event('change', { bubbles: true }));
};
async function ask(text: string): Promise<void> {
  setTa(text);
  await waitFor(() => !act('send')?.hasAttribute('disabled'), 2000);
  act('send').click();
}
const idle = () => !!chatEl()?.querySelector('[data-action="send"]') && !ta().disabled;

async function chatScenario(info: Awaited<ReturnType<EstudoBridge['info']>>, check: CheckFn): Promise<void> {
  const KEY = 'TESTE-FAKE-CHAVE-123456';
  await waitFor(() => pane('A').querySelector('[data-status="ready"]') && pane('B').querySelector('[data-status="ready"]'), 20000);

  await check('IA falsa ativa e cofre em memória (nunca toca no Credential Manager)', async () => {
    if (!info.fakeAi) throw new Error('servidor de IA falso não está ativo');
    const s = (await (await fetch('/api/settings/ai')).json()) as { providers: Array<{ id: string; has_key: boolean }> };
    const g = s.providers.find((p) => p.id === 'gemini');
    if (!g) throw new Error('provedor gemini ausente');
    if (g.has_key) throw new Error('já existe chave do gemini: abortando para não sobrescrever uma chave real');
    return 'gemini sem chave; backend com ESTUDOTRON_SECRET_STORE=memory';
  });

  await check('Markdown seguro: sem HTML bruto, sem javascript:, citação vira botão', async () => {
    const html = renderToStaticMarkup(createElement(Markdown, {
      text: '<script>alert(1)</script> [x](javascript:alert(1)) **b** *i* `c` [(f.pdf, p. 3)](ref:A:3) [l](https://example.com)\n\n- um\n- dois\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```js\nlet x = "<b>";\n```',
    }));
    const must: Array<[string, boolean]> = [
      ['sem <script> literal', !html.includes('<script')],
      ['texto escapado', html.includes('&lt;script&gt;')],
      ['sem href javascript', !/href="javascript/i.test(html)],
      ['negrito', html.includes('<strong>b</strong>')],
      ['itálico', html.includes('<em>i</em>')],
      ['código inline', html.includes('<code>c</code>')],
      ['citação vira botão', html.includes('class="cite"') && html.includes('data-slot="A"') && html.includes('data-page="3"')],
      ['link https seguro', html.includes('href="https://example.com"') && html.includes('rel="noreferrer noopener"')],
      ['lista', html.includes('<ul><li>um</li><li>dois</li></ul>')],
      ['tabela', html.includes('<table>') && html.includes('<th>a</th>') && html.includes('<td>2</td>')],
      ['bloco de código escapado', html.includes('&lt;b&gt;') && !html.includes('<b>')],
    ];
    const bad = must.filter(([, ok]) => !ok).map(([n]) => n);
    if (bad.length) throw new Error('falhou: ' + bad.join(', '));
    return `${must.length} propriedades`;
  });

  await check('Sem chave: o erro aparece e o texto volta ao campo (nada é gravado)', async () => {
    const before = bubbles().length;
    await ask('ping sem chave');
    const n = await waitFor(() => chatEl()!.querySelector('.chat-notice'), 8000);
    if (!n) throw new Error('aviso não apareceu');
    if (ta().value !== 'ping sem chave') throw new Error(`texto não voltou ao campo (“${ta().value}”)`);
    if (bubbles().length !== before) throw new Error('uma mensagem foi gravada mesmo sem chave');
    const t = n.textContent ?? '';
    setTa('');
    chatEl()!.querySelector<HTMLElement>('.chat-notice .link')?.click();
    return t.replace(/\s+/g, ' ').slice(0, 90);
  });

  // salva uma chave FALSA no cofre em memória e escolhe o provedor Gemini
  await check('Salvar chave falsa (memória) e selecionar o Gemini no seletor', async () => {
    const r = await fetch('/api/settings/ai/providers/gemini/key', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ api_key: KEY }) });
    if (r.status !== 204) throw new Error(`PUT chave: HTTP ${r.status}`);
    const sel = chatEl()!.querySelector<HTMLSelectElement>('[data-sel="provider"]')!;
    selectValue(sel, 'gemini');
    await sleep(200);
    if (sel.value !== 'gemini') throw new Error('seletor não mudou');
    const m = chatEl()!.querySelector<HTMLSelectElement>('[data-sel="model"]')!;
    return `provedor ${sel.value}, modelo ${m.value}`;
  });

  await check('Resposta em streaming: texto cresce aos poucos e termina completa', async () => {
    const before = bubbles().length;
    const sizes: number[] = [];
    let obs: MutationObserver | null = null;
    const started = new Promise<void>((res) => {
      obs = new MutationObserver(() => {
        const b = lastAssistant();
        if (b && bubbles().length > before) { sizes.push(b.textContent?.length ?? 0); res(); }
      });
      obs.observe(chatEl()!, { childList: true, subtree: true, characterData: true });
    });
    await ask('O que é o mergesort? Compare os dois documentos');
    await started;
    const ok = await waitFor(() => lastAssistant()?.getAttribute('data-status') === 'complete', 30000);
    obs!.disconnect();
    if (!ok) throw new Error(`não completou (status ${lastAssistant()?.getAttribute('data-status')})`);
    const distinct = new Set(sizes).size;
    if (distinct < 3) throw new Error(`o texto não cresceu progressivamente (${distinct} tamanhos)`);
    return `${distinct} tamanhos distintos durante a chegada; ${lastAssistant()!.textContent!.length} caracteres no fim`;
  });
  const first = lastAssistant()!;
  await check('Markdown da resposta: título, tabela e código renderizados', async () => {
    const h = first.querySelector('h3, h4, h5, h6')?.textContent; // "##" vira h4 dentro da bolha
    if (h !== 'Resposta de teste') throw new Error(`título: ${h}`);
    if (!first.querySelector('table th')) throw new Error('sem tabela');
    if (!first.querySelector('pre code')?.textContent?.includes("print('olá')")) throw new Error('sem bloco de código');
    return 'h3, table e pre/code';
  });
  await check('Rodapé mostra provedor/modelo e custo da resposta', async () => {
    const t = first.querySelector('.bubble-foot')?.textContent ?? '';
    if (!/Gemini/i.test(t) || !t.includes('US$')) throw new Error(`rodapé: “${t}”`);
    return t;
  });
  await window.estudo.capture('6-chat-resposta');

  await check('Citação clicável leva o PDF à página citada', async () => {
    const cites = [...first.querySelectorAll<HTMLElement>('.cite')];
    if (!cites.length) throw new Error('nenhuma citação na resposta');
    const c = cites.find((x) => x.dataset.slot === 'A') ?? cites[0];
    const slot = c.dataset.slot as 'A' | 'B', page = Number(c.dataset.page);
    if (!/p\. \d+\)$/.test(c.textContent ?? '')) throw new Error(`rótulo inesperado: ${c.textContent}`);
    // afasta o PDF da página citada antes de clicar
    const away = page === 24 ? 1 : 24;
    const f = pageField(slot);
    setInput(f, String(away));
    f.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await waitFor(() => pageField(slot).value === String(away), 5000);
    c.click();
    const ok = await waitFor(() => pageField(slot).value === String(page), 6000);
    if (!ok) {
      const sc = pane(slot).querySelector<HTMLElement>('.pdf-scroller')!;
      const st = (sc as unknown as { __pdf?: { state: unknown } }).__pdf?.state;
      throw new Error(`PDF ficou na página ${pageField(slot).value}, esperava ${page}; scrollTop=${Math.round(sc.scrollTop)}; estado=${JSON.stringify(st)}; aviso="${chatEl()!.querySelector('.chat-notice')?.textContent ?? ''}"; doc=${pane(slot).querySelector('.name')?.textContent}`);
    }
    return `${c.textContent} → ${slot === 'A' ? 'PDF 1' : 'PDF 2'} na página ${page}`;
  });
  await check('Chips de referência abaixo das mensagens também navegam', async () => {
    const chips = [...chatEl()!.querySelectorAll<HTMLElement>('.refs [data-ref]')];
    if (!chips.length) throw new Error('sem chips de referência');
    const chip = chips[chips.length - 1];
    const [slot, page] = chip.dataset.ref!.split(':') as ['A' | 'B', string];
    const away = page === '1' ? '2' : '1';
    const f = pageField(slot);
    setInput(f, away);
    f.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    await waitFor(() => pageField(slot).value === away, 5000);
    chip.click();
    if (!(await waitFor(() => pageField(slot).value === page, 6000))) throw new Error('chip não navegou');
    return `${chips.length} chip(s); ${chip.textContent}`;
  });

  await check('Parar: texto parcial é mantido e o chat volta a aceitar perguntas', async () => {
    const before = bubbles().length;
    await ask('LENTO explique o mergesort em detalhes');
    // espera a bolha NOVA (a anterior também tem texto) começar a receber a resposta
    const got = await waitFor(() => bubbles().length >= before + 2 && (lastAssistant()?.querySelector('.md')?.textContent?.length ?? 0) > 5, 15000);
    if (!got) throw new Error('a resposta lenta não começou');
    const len0 = lastAssistant()!.textContent!.length;
    // a resposta continua em andamento ao trocar de tela
    nav('settings');
    await sleep(700);
    nav('reader');
    await waitFor(() => chatEl() && lastAssistant(), 5000);
    const grew = await waitFor(() => lastAssistant()!.textContent!.length > len0, 4000);
    if (!grew) throw new Error(`a resposta não continuou durante a troca de tela (len0=${len0}, agora=${lastAssistant()!.textContent!.length}, status=${lastAssistant()!.getAttribute('data-status')}, botão=${chatEl()!.querySelector('[data-action="stop"]') ? 'parar' : 'enviar'})`);
    act('stop').click();
    const cancelled = await waitFor(() => lastAssistant()?.getAttribute('data-status') === 'cancelled', 10000);
    if (!cancelled) throw new Error(`status ${lastAssistant()?.getAttribute('data-status')}`);
    if (!lastAssistant()!.textContent!.includes('interrompida')) throw new Error('sem a marca "interrompida"');
    if (!(await waitFor(idle, 5000))) throw new Error('campo continua bloqueado');
    return `parcial mantido (${lastAssistant()!.querySelector('.md')!.textContent!.length} caracteres), resposta seguiu durante a troca de tela`;
  });

  await check('Erro do provedor (429): mensagem clara na bolha e o chat segue utilizável', async () => {
    await ask('ERRO429 teste de limite');
    const ok = await waitFor(() => lastAssistant()?.getAttribute('data-status') === 'error', 20000);
    if (!ok) throw new Error(`status ${lastAssistant()?.getAttribute('data-status')}`);
    const m = lastAssistant()!.querySelector('.msg.error')?.textContent ?? '';
    if (m.length < 10) throw new Error('sem mensagem de erro');
    if (!(await waitFor(idle, 5000))) throw new Error('campo bloqueado após o erro');
    return m.slice(0, 100);
  });
  await window.estudo.capture('7-chat-parar-e-erro');

  await check('Citação inventada é removida e a interface avisa', async () => {
    await ask('INVENTA mergesort');
    const ok = await waitFor(() => lastAssistant()?.getAttribute('data-status') === 'complete', 30000);
    if (!ok) throw new Error(`status ${lastAssistant()?.getAttribute('data-status')}`);
    const b = lastAssistant()!;
    if ([...b.querySelectorAll<HTMLElement>('.cite')].some((c) => c.dataset.page === '999')) throw new Error('a página 999 virou link');
    if (!b.textContent!.includes('inexistente')) throw new Error('sem aviso de citação removida');
    return 'página 999 removida; aviso exibido';
  });

  await check('Nova conversa mantém a antiga no histórico e permite reabri-la', async () => {
    act('new-chat').click();
    if (!(await waitFor(() => bubbles().length === 0, 5000))) throw new Error('a tela não limpou');
    act('history').click();
    const items = await waitFor(() => { const l = chatEl()!.querySelectorAll<HTMLElement>('[data-conv]'); return l.length >= 2 ? [...l] : null; }, 6000);
    if (!items) throw new Error('histórico não mostra 2 conversas');
    const old = items.find((i) => i.textContent!.includes('mergesort'));
    if (!old) throw new Error('conversa antiga não listada');
    old.click();
    const back = await waitFor(() => bubbles().length >= 4 && chatEl()!.querySelector('.bubble.assistant .cite'), 6000);
    if (!back) throw new Error(`conversa não reabriu (${bubbles().length} bolhas)`);
    return `${items.length} conversas; reaberta com ${bubbles().length} mensagens e citações`;
  });

  await check('Documento substituído: clicar na citação antiga avisa em vez de navegar', async () => {
    const cite = [...chatEl()!.querySelectorAll<HTMLElement>('.bubble.assistant .cite')].find((c) => c.dataset.slot === 'A');
    if (!cite) throw new Error('sem citação do PDF 1 na conversa reaberta');
    await reader().setPath('A', pdfs0().outro); // outro documento no slot A
    await waitFor(() => pane('A').querySelector('.name')?.textContent === 'outro.pdf', 10000);
    cite.click();
    const w = await waitFor(() => chatEl()!.querySelector('.chat-notice'), 4000);
    if (!w || !/substitu/i.test(w.textContent ?? '')) throw new Error('aviso de documento substituído não apareceu');
    return w.textContent!.replace(/\s+/g, ' ').slice(0, 110);
  });

  await check('Botão "Chat" oculta e mostra o painel; os PDFs reajustam a largura', async () => {
    const w0 = pane('B').getBoundingClientRect().width;
    document.querySelector<HTMLElement>('[data-action="toggle-chat"]')!.click();
    await sleep(500);
    if (chatEl()) throw new Error('o painel não sumiu');
    const w1 = pane('B').getBoundingClientRect().width;
    if (w1 <= w0 + 50) throw new Error(`PDFs não ocuparam o espaço (${Math.round(w0)} → ${Math.round(w1)} px)`);
    document.querySelector<HTMLElement>('[data-action="toggle-chat"]')!.click();
    if (!(await waitFor(() => chatEl(), 3000))) throw new Error('o painel não voltou');
    return `largura de um PDF: ${Math.round(w0)} → ${Math.round(w1)} px com o chat oculto`;
  });

  await check('A chave nunca aparece no DOM nem nas respostas da API', async () => {
    if (document.documentElement.outerHTML.includes(KEY)) throw new Error('chave no DOM');
    const t = await (await fetch('/api/settings/ai')).text();
    if (t.includes(KEY)) throw new Error('GET /settings/ai devolveu a chave');
    const conv = await (await fetch('/api/conversations/current')).text();
    if (conv.includes(KEY)) throw new Error('a chave apareceu no histórico');
    return 'ausente em DOM, /settings/ai e /conversations';
  });
  await sleep(300);
  await window.estudo.capture('8-chat-historico');
}

// ---------- Etapa 5: exercícios (contra o servidor Gemini FALSO) ----------

const exEl = () => document.querySelector<HTMLElement>('.exercises');
const stage = () => exEl()?.getAttribute('data-stage');
const exId = () => exEl()?.querySelector<HTMLElement>('[data-exercise-id]')?.dataset.exerciseId;
const exAct = (a: string) => exEl()!.querySelector<HTMLElement>(`[data-action="${a}"]`)!;
const setAnswer = (v: string) => {
  const t = exEl()!.querySelector<HTMLTextAreaElement>('textarea[aria-label="Sua resposta"]')!;
  Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value')!.set!.call(t, v);
  t.dispatchEvent(new Event('input', { bubbles: true }));
};
const exText = () => exEl()?.textContent ?? '';

const backBtn = () => document.querySelector<HTMLElement>('[data-action="back"]'); // fica no cabeçalho, fora de .exercises

async function startMode(k: 'objective' | 'subjective'): Promise<void> {
  if (!(await waitFor(() => exEl(), 4000))) throw new Error('a tela de exercícios não montou');
  if (stage() !== 'home') (backBtn() ?? exEl()!.querySelector<HTMLElement>('[data-action="home"]'))?.click();
  await waitFor(() => stage() === 'home', 3000);
  const b = await waitFor(() => { const e = exEl()?.querySelector<HTMLButtonElement>(`[data-mode="${k}"]`); return e && !e.disabled ? e : null; }, 8000);
  if (!b) throw new Error(`modalidade ${k} indisponível`);
  b.click();
  if (!(await waitFor(() => exEl()?.querySelector(`[data-kind="${k}"]`), 20000))) throw new Error(`a questão ${k} não apareceu (estágio ${stage()}; ${exText().slice(0, 120)})`);
}

async function exercisesScenario(info: Awaited<ReturnType<EstudoBridge['info']>>, check: CheckFn): Promise<void> {
  const pdfs = info.testPdfs!;
  const bothReady = () => pane('A').querySelector('[data-status="ready"]') && pane('B').querySelector('[data-status="ready"]');

  await check('Sem PDFs: o início explica e leva ao Leitor de PDFs', async () => {
    await reader().clear('A');
    await reader().clear('B');
    nav('exercises');
    const p = await waitFor(() => exEl()?.querySelector('[data-status-panel="info"]'), 6000);
    if (!p || !p.textContent!.includes('Carregue um PDF primeiro')) throw new Error('painel de PDF ausente não apareceu');
    if (exEl()!.querySelector('[data-mode]')) throw new Error('as modalidades não deveriam aparecer sem PDFs');
    exAct('to-reader').click();
    if (!(await waitFor(() => document.querySelector('.reader'), 4000))) throw new Error('não foi para o Leitor');
    return 'painel informativo + botão para o Leitor';
  });

  await reader().setPath('A', pdfs.texto);
  await reader().setPath('B', pdfs.outro);
  await waitFor(bothReady, 20000);

  await check('Início: PDFs como cartões, duas modalidades e aviso de envio ao Gemini', async () => {
    nav('exercises');
    const cards = await waitFor(() => { const c = exEl()?.querySelectorAll('[data-source]'); return c && c.length === 2 ? c : null; }, 8000);
    if (!cards) throw new Error('os dois PDFs não apareceram como cartões');
    const modes = await waitFor(() => { const m = [...(exEl()?.querySelectorAll<HTMLButtonElement>('[data-mode]') ?? [])]; return m.length === 2 && m.every((b) => !b.disabled) ? m : null; }, 8000);
    if (!modes) throw new Error('modalidades ausentes ou desabilitadas');
    if (!/Gemini \(Google\)/.test(exText())) throw new Error('sem o aviso de envio ao Gemini');
    return `${[...cards].map((c) => c.querySelector('b')!.textContent).join(' + ')}; modalidades habilitadas`;
  });
  await sleep(400);
  await window.estudo.capture('9-exercicios-inicio');

  let firstId = '';
  await check('Objetiva: gabarito não vem antes do envio e o botão só habilita com escolha', async () => {
    await startMode('objective');
    firstId = exId()!;
    const opts = [...exEl()!.querySelectorAll<HTMLElement>('[data-option]')];
    if (opts.length !== 4) throw new Error(`${opts.length} alternativas`);
    if (!exText().includes('mitocôndria')) throw new Error('enunciado ausente');
    if (exEl()!.querySelector('.opt-result') || /Correta:|correta/.test(exText().replace(/Escolha uma alternativa/g, ''))) throw new Error('o gabarito apareceu antes do envio');
    if (!exAct('submit').hasAttribute('disabled')) throw new Error('enviar deveria estar desabilitado sem escolha');
    const right = opts.find((o) => o.textContent!.includes('Produzir ATP'))!;
    right.click();
    await sleep(150);
    if (exAct('submit').hasAttribute('disabled')) throw new Error('enviar não habilitou após escolher');
    return `4 alternativas; sem gabarito; escolhida ${right.dataset.option!.toUpperCase()}`;
  });
  await check('Objetiva certa: banner, explicação por alternativa, referências e custo', async () => {
    exAct('submit').click();
    if (!(await waitFor(() => exEl()?.querySelector('[data-verdict="correct"]'), 15000))) throw new Error(`sem veredito (${exText().slice(0, 150)})`);
    const results = [...exEl()!.querySelectorAll<HTMLElement>('.opt-result')];
    if (results.length !== 4) throw new Error(`${results.length} alternativas na correção`);
    const correct = results.filter((r) => r.dataset.correct === '1');
    if (correct.length !== 1 || !correct[0].textContent!.includes('Produzir ATP')) throw new Error('alternativa correta errada');
    if (!correct[0].textContent!.includes('Correta: a mitocôndria produz ATP')) throw new Error('sem a explicação da alternativa');
    if (!/Por que a alternativa [A-D] é a correta/.test(exText())) throw new Error('sem o cartão "Por que..."');
    if (!exEl()!.querySelector('[data-ref]')) throw new Error('sem referências');
    if (!/US\$/.test(exText())) throw new Error('sem custo');
    return `1 correta de 4, explicações, ${exEl()!.querySelectorAll('[data-ref]').length} referência(s)`;
  });
  await window.estudo.capture('10-objetiva-correcao');
  await check('Backend recusa responder duas vezes (already_answered)', async () => {
    const r = await fetch(`/api/exercises/${firstId}/answer`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ option: 'a' }) });
    const j = (await r.json()) as { detail?: { kind?: string } };
    if (r.status !== 409 || j.detail?.kind !== 'already_answered') throw new Error(`HTTP ${r.status} ${JSON.stringify(j)}`);
    return 'HTTP 409 already_answered';
  });
  await check('Referência clicada abre o Leitor de PDFs na página citada', async () => {
    const chips = [...exEl()!.querySelectorAll<HTMLElement>('[data-ref]')];
    const chip = chips[chips.length - 1];
    const [slot, page] = chip.dataset.ref!.split(':') as ['A' | 'B', string];
    chip.click();
    if (!(await waitFor(() => document.querySelector('.reader'), 4000))) throw new Error('não trocou para o Leitor');
    if (!(await waitFor(() => pane(slot)?.querySelector('.pdf-page') && pageField(slot).value === page, 10000))) throw new Error(`PDF na página ${pageField(slot)?.value}, esperava ${page}`);
    return `${chip.textContent} → Leitor, PDF ${slot === 'A' ? '1' : '2'} na página ${page}`;
  });
  await check('"Nova questão" gera outra; a escolha errada mostra a correta', async () => {
    nav('exercises');
    await startMode('objective');
    const id = exId();
    const wrong = [...exEl()!.querySelectorAll<HTMLElement>('[data-option]')].find((o) => !o.textContent!.includes('Produzir ATP'))!;
    wrong.click();
    await sleep(150);
    exAct('submit').click();
    if (!(await waitFor(() => exEl()?.querySelector('[data-verdict="incorrect"]'), 15000))) throw new Error('sem veredito incorreto');
    if (!/a correta é [A-D]/.test(exText())) throw new Error('não indicou a correta');
    if (exEl()!.querySelectorAll('[data-picked="1"][data-correct="0"]').length !== 1) throw new Error('a escolha errada não foi marcada');
    exAct('next').click();
    const other = await waitFor(() => { const n = exId(); return n && n !== id ? n : null; }, 15000);
    if (!other) throw new Error('"Nova questão" não trouxe outra');
    return `escolha errada marcada; nova questão ${other.slice(0, 6)}… ≠ ${id!.slice(0, 6)}…`;
  });

  await check('Subjetiva: erro do provedor mantém o texto e permite reenviar', async () => {
    await startMode('subjective');
    if (!exAct('submit').hasAttribute('disabled')) throw new Error('enviar deveria estar desabilitado sem texto');
    setAnswer('ERRO429 teste de limite');
    await sleep(150);
    exAct('submit').click();
    const err = await waitFor(() => exEl()?.querySelector('.msg.error'), 12000);
    if (!err) throw new Error('mensagem de erro não apareceu');
    const t = exEl()!.querySelector<HTMLTextAreaElement>('textarea[aria-label="Sua resposta"]');
    if (!t || t.value !== 'ERRO429 teste de limite') throw new Error('o texto digitado foi perdido');
    return err.textContent!.slice(0, 90);
  });
  await check('Subjetiva correta: nota em anel, acertos, resposta ideal, critérios e referências', async () => {
    setAnswer('A mitocôndria produz ATP pela respiração celular.');
    await sleep(150);
    exAct('submit').click();
    if (!(await waitFor(() => exEl()?.querySelector('.grade[data-verdict="correct"]'), 15000))) throw new Error(`sem correção (${exText().slice(0, 140)})`);
    const ring = exEl()!.querySelector('.score-ring')!.getAttribute('aria-label');
    if (ring !== 'Nota 8,5 de 10') throw new Error(`anel: ${ring}`);
    const t = exText();
    for (const s of ['O que você acertou', 'Citou a produção de ATP', 'O que faltou', 'Como seria uma resposta mais completa', 'Critérios considerados']) if (!t.includes(s)) throw new Error(`faltou “${s}”`);
    if (exEl()!.querySelectorAll('.criterion-chip').length !== 3) throw new Error('critérios != 3');
    if (!exEl()!.querySelector('[data-ref]')) throw new Error('sem referências');
    return `${ring}; 3 critérios; ${exEl()!.querySelectorAll('[data-ref]').length} referência(s)`;
  });
  await window.estudo.capture('11-subjetiva-correcao');
  await check('Subjetiva errada: conceitos a corrigir; parcial: nota intermediária', async () => {
    exAct('next').click();
    if (!(await waitFor(() => exEl()?.querySelector('textarea[aria-label="Sua resposta"]'), 15000))) throw new Error('nova questão não apareceu');
    setAnswer('resposta ERRADO');
    await sleep(150);
    exAct('submit').click();
    if (!(await waitFor(() => exEl()?.querySelector('.grade[data-verdict="incorrect"]'), 15000))) throw new Error('sem veredito incorreto');
    const r1 = exEl()!.querySelector('.score-ring')!.getAttribute('aria-label');
    if (r1 !== 'Nota 2,0 de 10') throw new Error(`anel errado: ${r1}`);
    if (!exText().includes('Conceitos para corrigir') || !exText().includes('Respiração celular')) throw new Error('sem conceitos a corrigir');
    if (!exText().includes('Nenhum ponto da resposta se destacou desta vez.')) throw new Error('sem a mensagem de acertos vazios');
    exAct('next').click();
    await waitFor(() => exEl()?.querySelector('textarea[aria-label="Sua resposta"]'), 15000);
    setAnswer('resposta PARCIAL');
    await sleep(150);
    exAct('submit').click();
    if (!(await waitFor(() => exEl()?.querySelector('.grade[data-verdict="partial"]'), 15000))) throw new Error('sem veredito parcial');
    const r2 = exEl()!.querySelector('.score-ring')!.getAttribute('aria-label');
    if (r2 !== 'Nota 5,0 de 10') throw new Error(`anel parcial: ${r2}`);
    return `${r1} (incorreta) e ${r2} (parcial)`;
  });

  /** Volta ao início (de qualquer estágio) e espera a lista de modalidades. */
  const toHome = async () => {
    if (stage() !== 'home') (backBtn() ?? exEl()!.querySelector<HTMLElement>('[data-action="home"]'))?.click();
    if (!(await waitFor(() => stage() === 'home' && exEl()!.querySelector('[data-mode="objective"]'), 5000))) throw new Error(`não voltou ao início (estágio ${stage()})`);
  };

  await check('Gerando…: painel de carregamento; voltar antes de chegar ignora a resposta atrasada', async () => {
    await toHome();
    // atrasa a geração para a corrida ser determinística (o servidor falso responde quase na hora)
    const realFetch = window.fetch;
    window.fetch = async (input, init) => {
      if (String(input).includes('/exercises/generate')) await sleep(1500);
      return realFetch(input, init);
    };
    try {
      exEl()!.querySelector<HTMLElement>('[data-mode="objective"]')!.click();
      const loading = await waitFor(() => exEl()?.querySelector('[data-status-panel="loading"]'), 2000);
      if (!loading || !loading.textContent!.includes('Gerando sua questão')) throw new Error('painel de carregamento ausente');
      if (!loading.textContent!.includes('texto.pdf')) throw new Error('o carregamento não cita os PDFs lidos');
      const back = await waitFor(() => backBtn(), 1000);
      if (!back) throw new Error('botão Início ausente durante a geração');
      back.click(); // volta ANTES de a questão chegar
      await sleep(2800);
      if (stage() !== 'home') throw new Error(`apareceu o estágio ${stage()} depois de voltar`);
      return 'carregamento mostrado e resposta atrasada ignorada';
    } finally {
      window.fetch = realFetch;
    }
  });

  await check('Sem chave: erro claro com atalho para Configurações; chave de volta funciona', async () => {
    await toHome();
    const del = await fetch('/api/settings/ai/providers/gemini/key', { method: 'DELETE' });
    if (del.status !== 204) throw new Error(`DELETE chave: HTTP ${del.status}`);
    const mode = await waitFor(() => exEl()?.querySelector<HTMLElement>('[data-mode="objective"]'), 4000);
    if (!mode) throw new Error(`modalidade ausente (estágio ${stage()})`);
    mode.click();
    const p = await waitFor(() => exEl()?.querySelector('[data-status-panel="error"]'), 12000);
    if (!p) throw new Error(`sem painel de erro (estágio ${stage()})`);
    const title = p.querySelector('h2')!.textContent;
    if (title !== 'Chave do Gemini não configurada') throw new Error(`título: ${title}`);
    if (!exAct('to-settings') || !exAct('home')) throw new Error('faltam os botões');
    exAct('to-settings').click();
    if (!(await waitFor(() => document.querySelector('[data-provider]'), 5000))) throw new Error('não abriu Configurações');
    const put = await fetch('/api/settings/ai/providers/gemini/key', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ api_key: 'TESTE-FAKE-CHAVE-123456' }) });
    if (put.status !== 204) throw new Error(`PUT chave: HTTP ${put.status}`);
    nav('exercises');
    await startMode('objective');
    return `“${title}” → Configurações → chave restaurada → questão gerada`;
  });
}

/** --smoke (app empacotado): o essencial do Leitor com PDFs reais e os arquivos de dados do PDF.js. */
async function smokeReader(info: Awaited<ReturnType<EstudoBridge['info']>>, check: CheckFn): Promise<void> {
  const pdfs = info.testPdfs;
  await check('Dados do PDF.js presentes no app (wasm, fontes padrão, CMaps)', async () => {
    const need: Array<[string, string]> = [
      ['/pdfjs/wasm/jbig2.wasm', 'application/wasm'],
      ['/pdfjs/standard_fonts/FoxitFixed.pfb', ''],
      ['/pdfjs/cmaps/78-H.bcmap', ''],
    ];
    const out: string[] = [];
    for (const [url, type] of need) {
      const r = await fetch(url);
      if (r.status !== 200) throw new Error(`${url}: HTTP ${r.status}`);
      if (type && !(r.headers.get('content-type') ?? '').startsWith(type)) throw new Error(`${url}: tipo ${r.headers.get('content-type')}`);
      out.push(`${url.split('/').pop()} ${r.status}`);
    }
    return out.join(', ');
  });
  if (!pdfs?.texto || !pdfs.escaneado) {
    await check('PDFs de teste informados (--smoke-pdfs)', async () => { throw new Error('use --smoke-pdfs=<pasta> com texto.pdf e escaneado.pdf'); });
    return;
  }
  await waitFor(() => document.querySelector('[data-loaded="1"]'), 8000);
  await check('Leitor: PDF de 24 páginas renderiza, é indexado e tem camada de texto', async () => {
    await reader().setPath('A', pdfs.texto);
    if (!(await waitFor(() => pane('A').querySelector('.pdf-page[data-rendered="1"]'), 25000))) throw new Error('nenhuma página renderizada');
    if (!(await waitFor(() => pane('A').querySelector('[data-status="ready"]'), 25000))) throw new Error('não chegou a "pronto"');
    const c = pane('A').querySelector<HTMLCanvasElement>('.pdf-page[data-page="1"] canvas')!;
    const d = c.getContext('2d')!.getImageData(0, 0, c.width, c.height).data;
    let ink = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] < 200 && d[i + 3] > 0) ink++;
    if (ink < 200) throw new Error(`canvas quase em branco (${ink} pixels)`);
    if (!(await waitFor(() => pane('A').querySelector('.textLayer')?.textContent?.includes('MARCADOR-PAGINA-A-1'), 8000))) throw new Error('sem camada de texto');
    return `24 páginas, ${ink} pixels de texto, camada de texto ok`;
  });
  await check('Leitor: PDF escaneado avisa "sem texto pesquisável"', async () => {
    await reader().setPath('B', pdfs.escaneado);
    if (!(await waitFor(() => pane('B').querySelector('[data-status="no_text"]'), 25000))) throw new Error('estado no_text ausente');
    return pane('B').querySelector('.status')!.textContent ?? '';
  });
}

// ---------- Barra lateral recolhível e layout responsivo ----------

const sb = () => document.querySelector<HTMLElement>('.sidebar')!;
const sbToggle = () => document.querySelector<HTMLElement>('[data-action="toggle-sidebar"]')!;
const isCollapsed = () => sb().getAttribute('data-collapsed') === '1';
const W = (el: Element | null) => (el ? el.getBoundingClientRect().width : 0);
const noHScroll = () => document.documentElement.scrollWidth <= window.innerWidth + 1;
const navItems = () => [...document.querySelectorAll<HTMLElement>('.sidebar [data-nav]')];
const savedPref = () => { try { return localStorage.getItem('estudotron.sidebar'); } catch { return 'indisponível'; } };

/** Clica no botão e amostra o layout durante a transição (largura da barra, borda do conteúdo, rolagem horizontal). */
async function toggleAndSample(): Promise<{ widths: number[]; overlap: boolean; hscroll: boolean }> {
  const widths: number[] = [];
  let overlap = false, hscroll = false;
  sbToggle().click();
  const t0 = performance.now();
  while (performance.now() - t0 < 650) {
    const s = sb().getBoundingClientRect(), m = document.querySelector('.main')!.getBoundingClientRect();
    widths.push(Math.round(s.width * 10) / 10);
    if (m.left < s.right - 0.5) overlap = true;
    if (!noHScroll()) hscroll = true;
    await sleep(12);
  }
  return { widths, overlap, hscroll };
}

async function layoutScenario(info: Awaited<ReturnType<EstudoBridge['info']>>, check: CheckFn): Promise<void> {
  await window.estudo.resize(1440, 900); // garante janela restaurada e no tamanho de referência
  nav('reader');
  await waitFor(() => document.querySelector('.reader[data-loaded="1"]'), 8000);
  if (isCollapsed()) { sbToggle().click(); await sleep(500); }

  await check('Barra expandida: textos visíveis e botão discreto de recolher', async () => {
    const w = W(sb());
    if (Math.abs(w - 232) > 2) throw new Error(`largura ${w}`);
    const labels = [...sb().querySelectorAll<HTMLElement>('.label')];
    if (labels.some((l) => getComputedStyle(l).opacity !== '1' || W(l) < 30)) throw new Error('algum texto não está visível');
    if (sbToggle().getAttribute('aria-expanded') !== 'true') throw new Error('aria-expanded errado');
    return `${Math.round(w)} px, ${labels.length} textos visíveis`;
  });
  await window.estudo.capture('12-sidebar-expandida');
  const before = { main: W(document.querySelector('.main')), a: W(pane('A')), b: W(pane('B')), chat: W(document.querySelector('.chat')), pctA: pct('A') };

  await check('Recolher: largura anima de forma contínua, sem sobreposição nem rolagem horizontal', async () => {
    const r = await toggleAndSample();
    const ws = r.widths;
    for (let i = 1; i < ws.length; i++) if (ws[i] > ws[i - 1] + 0.6) throw new Error(`largura voltou a crescer: ${ws[i - 1]} → ${ws[i]}`);
    const steps = new Set(ws.filter((w) => w < 231 && w > 69)).size;
    if (steps < 5) throw new Error(`poucos quadros intermediários (${steps}): a transição não foi suave`);
    if (r.overlap) throw new Error('o conteúdo sobrepôs a barra durante a animação');
    if (r.hscroll) throw new Error('apareceu rolagem horizontal durante a animação');
    const end = W(sb());
    if (Math.abs(end - 68) > 1.5) throw new Error(`largura final ${end}`);
    return `232 → ${Math.round(end)} px em ${steps} quadros intermediários, sem sobreposição`;
  });
  await sleep(300);

  await check('Recolhida: só ícones, centralizados; item selecionado continua destacado', async () => {
    if (!isCollapsed()) throw new Error('não recolheu');
    const sbr = sb().getBoundingClientRect(), center = sbr.left + sbr.width / 2;
    for (const it of navItems()) {
      const ic = it.querySelector('svg')!.getBoundingClientRect();
      if (Math.abs(ic.left + ic.width / 2 - center) > 1.5) throw new Error(`ícone de ${it.dataset.nav} fora do centro`);
      const lab = it.querySelector<HTMLElement>('.label')!;
      if (getComputedStyle(lab).opacity !== '0' || W(lab) > 0.5) throw new Error('texto ainda visível');
    }
    const active = document.querySelector<HTMLElement>('.sidebar [aria-current="page"]')!;
    if (active.dataset.glass !== 'lens' || Math.abs(W(active) - 44) > 1) throw new Error('item ativo sem destaque de vidro');
    if (W(sb().querySelector('.foot')) > 0 && sb().querySelector<HTMLElement>('.foot')!.getBoundingClientRect().height > 0.5) throw new Error('rodapé visível');
    return `${navItems().length} ícones centralizados; ativo: ${active.dataset.nav} (lente 44×44)`;
  });
  await window.estudo.capture('13-sidebar-recolhida');

  await check('Dicas (tooltips) ao passar o mouse sobre cada ícone e sobre o botão', async () => {
    const seen: string[] = [];
    for (const el of [...navItems(), sbToggle()]) {
      el.dispatchEvent(new MouseEvent('mouseover', { bubbles: true }));
      const tip = await waitFor(() => document.querySelector<HTMLElement>('[role="tooltip"]'), 1000);
      if (!tip) throw new Error(`sem dica para ${el.getAttribute('aria-label')}`);
      if (tip.textContent !== el.getAttribute('aria-label')) throw new Error(`dica “${tip.textContent}” ≠ “${el.getAttribute('aria-label')}”`);
      const tr = tip.getBoundingClientRect();
      if (tr.left < sb().getBoundingClientRect().right || tr.right > window.innerWidth) throw new Error('dica fora do lugar ou cortada');
      seen.push(tip.textContent!);
      el.dispatchEvent(new MouseEvent('mouseout', { bubbles: true }));
      if (!(await waitFor(() => !document.querySelector('[role="tooltip"]'), 1000))) throw new Error('a dica não sumiu');
    }
    return seen.join(' · ');
  });

  await check('O espaço liberado vai para o conteúdo; PDFs equilibrados e chat confortável', async () => {
    await sleep(200);
    const now = { main: W(document.querySelector('.main')), a: W(pane('A')), b: W(pane('B')), chat: W(document.querySelector('.chat')) };
    const gain = now.main - before.main;
    if (Math.abs(gain - 164) > 4) throw new Error(`conteúdo ganhou ${gain.toFixed(1)} px (esperado ≈164)`);
    if (now.a - before.a < 40 || now.b - before.b < 40) throw new Error('os PDFs não aproveitaram o espaço');
    if (Math.abs(now.a - now.b) > now.a * 0.03) throw new Error(`PDFs desequilibrados (${Math.round(now.a)} × ${Math.round(now.b)})`);
    if (now.chat < 340 || now.chat > 460) throw new Error(`chat com ${Math.round(now.chat)} px`);
    return `conteúdo +${Math.round(gain)} px; PDFs ${Math.round(before.a)}→${Math.round(now.a)} e ${Math.round(before.b)}→${Math.round(now.b)} px; chat ${Math.round(before.chat)}→${Math.round(now.chat)} px`;
  });
  await check('Os PDFs reajustam o zoom à nova largura (página mais larga cabe)', async () => {
    const ok = await waitFor(() => pct('A') !== before.pctA && pane('A').querySelector('.zoom')!.getAttribute('data-fit') === '1', 3000);
    if (!ok) throw new Error(`zoom não mudou (${before.pctA}% → ${pct('A')}%)`);
    const sc = pane('A').querySelector<HTMLElement>('.pdf-scroller')!;
    const widest = Math.max(...[...pane('A').querySelectorAll<HTMLElement>('.pdf-page')].map((e) => e.offsetWidth));
    if (widest > sc.clientWidth) throw new Error('página cortada');
    return `${before.pctA}% → ${pct('A')}%`;
  });
  await check('Preferência gravada localmente', async () => {
    if (savedPref() !== 'collapsed') throw new Error(`localStorage = ${savedPref()}`);
    return 'estudotron.sidebar = collapsed';
  });
  await check('Vale para todas as telas (Configurações e Exercícios)', async () => {
    const out: string[] = [];
    for (const id of ['settings', 'exercises', 'reader']) {
      nav(id);
      await sleep(350);
      if (!isCollapsed() || Math.abs(W(sb()) - 68) > 1.5) throw new Error(`barra mudou na tela ${id}`);
      const m = document.querySelector('.main')!.getBoundingClientRect();
      if (m.left > 68 + 12 + 12 + 2) throw new Error(`conteúdo de ${id} não ocupou o espaço (left ${m.left})`);
      out.push(id);
    }
    await waitFor(() => document.querySelector('.reader[data-loaded="1"]'), 8000);
    return out.join(', ') + ': barra recolhida, conteúdo encostado';
  });
  await check('Expandir de novo (sem resíduos) e recolher outra vez', async () => {
    const r = await toggleAndSample();
    for (let i = 1; i < r.widths.length; i++) if (r.widths[i] < r.widths[i - 1] - 0.6) throw new Error('largura diminuiu ao expandir');
    if (r.overlap || r.hscroll) throw new Error('sobreposição ou rolagem horizontal ao expandir');
    if (Math.abs(W(sb()) - 232) > 2 || savedPref() !== 'expanded') throw new Error(`expandida: ${W(sb())} px, preferência ${savedPref()}`);
    if ([...sb().querySelectorAll<HTMLElement>('.nav .label, .nav-plain .label')].some((l) => getComputedStyle(l).opacity !== '1')) throw new Error('textos não voltaram');
    await toggleAndSample(); // deixa recolhida: a próxima execução prova que o estado volta após reiniciar
    if (!isCollapsed() || savedPref() !== 'collapsed') throw new Error('não recolheu de novo');
    return 'expandiu (232 px, textos de volta) e recolheu (68 px)';
  });

  // ---- resoluções ----
  const sizes: Array<[number, number]> = [[1440, 900], [1280, 800], [1100, 720], [980, 600]];
  for (const [w, h] of sizes) {
    await check(`Janela ${w}×${h}: nada cortado, PDFs lado a lado, chat acessível`, async () => {
      const got = await window.estudo.resize(w, h);
      await sleep(500);
      if (!noHScroll()) throw new Error('rolagem horizontal');
      const r = document.querySelector<HTMLElement>('.reader')!;
      const layout = r.dataset.layout;
      const a = pane('A').getBoundingClientRect(), b = pane('B').getBoundingClientRect();
      if (a.width < 280 || b.width < 280) throw new Error(`PDFs estreitos demais (${Math.round(a.width)} / ${Math.round(b.width)})`);
      if (b.right > window.innerWidth + 0.5 || !(a.right <= b.left)) throw new Error('PDFs fora da tela ou sobrepostos');
      const btn = document.querySelector<HTMLElement>('[data-action="toggle-chat"]')!.getBoundingClientRect();
      if (btn.right > window.innerWidth) throw new Error('botão Chat cortado');
      const tools = pane('A').querySelector<HTMLElement>('.tools')!.getBoundingClientRect();
      if (tools.right > a.right + 0.5) throw new Error('controles do PDF cortados');
      let chatInfo = '';
      if (layout === 'narrow') {
        if (!document.querySelector('.chat')) document.querySelector<HTMLElement>('[data-action="toggle-chat"]')!.click();
        const c = await waitFor(() => document.querySelector<HTMLElement>('.chat'), 2000);
        if (!c || c.dataset.mode !== 'overlay') throw new Error('o chat não abriu como painel sobreposto');
        await sleep(320); // fim da animação de entrada
        const cr = c.getBoundingClientRect();
        if (cr.right > window.innerWidth + 0.5 || cr.width < 300) throw new Error('painel do chat cortado');
        if (getComputedStyle(c).opacity !== '1') throw new Error('painel ainda transparente');
        // o vidro da GPU fica atrás do DOM: dentro do painel opaco os botões precisam do material em CSS
        const send = c.querySelector<HTMLElement>('[data-action="send"], [data-action="stop"]')!;
        const sr = send.getBoundingClientRect();
        if (sr.right > cr.right + 0.5 || sr.width < 30) throw new Error('botão de enviar fora do painel');
        const bg = getComputedStyle(send).backgroundColor;
        if (bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent') throw new Error('botão de enviar invisível sobre o painel opaco');
        for (const a of ['history', 'new-chat', 'close-chat']) {
          const b = getComputedStyle(c.querySelector<HTMLElement>(`[data-action="${a}"]`)!).backgroundColor;
          if (b === 'rgba(0, 0, 0, 0)') throw new Error(`botão ${a} sem fundo visível`);
        }
        await window.estudo.capture(`14-${w}x${h}-chat-sobreposto`);
        document.querySelector<HTMLElement>('[data-action="close-chat"]')!.click();
        if (!(await waitFor(() => !document.querySelector('.chat'), 2000))) throw new Error('não fechou');
        chatInfo = `chat sobreposto (${Math.round(cr.width)} px), abre e fecha`;
      } else {
        const c = document.querySelector<HTMLElement>('.chat');
        chatInfo = c ? `chat encaixado (${Math.round(W(c))} px)` : 'chat oculto';
      }
      await sleep(250);
      await window.estudo.capture(`14-${w}x${h}`);
      return `área ${got?.join('×')}; ${layout}; PDFs ${Math.round(a.width)} + ${Math.round(b.width)} px; ${chatInfo}`;
    });
  }
  await window.estudo.resize(1440, 900);
  void info;
}

let TEST_PDFS: Record<string, string> = {};
const pdfs0 = () => TEST_PDFS;

/** Cenário do Leitor com PDFs reais gerados pelo PyMuPDF (Etapa 3). */
async function readerScenario(info: Awaited<ReturnType<EstudoBridge['info']>>, check: CheckFn, results: Check[]): Promise<void> {
  const pdfs = info.testPdfs;
  await check('PDFs de teste gerados (PyMuPDF)', async () => {
    if (!pdfs?.texto || !pdfs.outro || !pdfs.escaneado) throw new Error('PDFs de teste não foram gerados');
    return Object.keys(pdfs).join(', ');
  });
  if (!pdfs) return;
  await waitFor(() => document.querySelector('[data-loaded="1"]'), 8000);
  await sleep(600);
  await window.estudo.capture('3-leitor-vazio');

  await check('Slot A: PDF de 24 páginas renderiza e é indexado', async () => {
    await reader().setPath('A', pdfs.texto);
    const p = await waitFor(() => pane('A').querySelector('.pdf-page[data-rendered="1"]'), 20000);
    if (!p) throw new Error('nenhuma página renderizada em 20 s');
    const ready = await waitFor(() => pane('A').querySelector('[data-status="ready"]'), 20000);
    if (!ready) throw new Error('documento não chegou a "pronto"');
    const t = pane('A').querySelector('.status')!.textContent!;
    if (!t.includes('24 páginas')) throw new Error(`status inesperado: ${t}`);
    return t;
  });
  await check('Página renderizada tem pixels (canvas não está em branco)', async () => {
    const c = pane('A').querySelector<HTMLCanvasElement>('.pdf-page[data-page="1"] canvas')!;
    const d = c.getContext('2d')!.getImageData(0, 0, c.width, c.height).data;
    let dark = 0;
    for (let i = 0; i < d.length; i += 4) if (d[i] < 200 && d[i + 3] > 0) dark++; // não-branco (com zoom pequeno o texto vira cinza)
    if (dark < 200) throw new Error(`só ${dark} pixels de texto`);
    return `${c.width}x${c.height}px, ${dark} pixels de texto`;
  });
  await check('Camada de texto selecionável com o conteúdo da página', async () => {
    const ok = await waitFor(() => pane('A').querySelector('.pdf-page[data-page="1"] .textLayer')?.textContent?.includes('MARCADOR-PAGINA-A-1'), 8000);
    if (!ok) throw new Error('texto da página 1 não apareceu na camada de texto');
    const cs = getComputedStyle(pane('A').querySelector('.textLayer span')!);
    return `spans selecionáveis (user-select: ${cs.userSelect})`;
  });
  await check('Ajuste automático: a página mais larga cabe no painel', async () => {
    const sc = pane('A').querySelector<HTMLElement>('.pdf-scroller')!;
    const widest = Math.max(...[...pane('A').querySelectorAll<HTMLElement>('.pdf-page')].map((e) => e.offsetWidth));
    if (pane('A').querySelector('.zoom')!.getAttribute('data-fit') !== '1') throw new Error('não está em modo ajuste');
    if (widest > sc.clientWidth) throw new Error(`página de ${widest}px não cabe em ${sc.clientWidth}px`);
    return `maior página ${widest}px em painel de ${sc.clientWidth}px (${pct('A')}%)`;
  });
  const fitPct = pct('A');

  await check('Navegar para a página 12 pelo campo de página', async () => {
    const f = pageField('A');
    setInput(f, '12');
    f.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    const ok = await waitFor(() => pane('A').querySelector('.pdf-page[data-page="12"][data-rendered="1"]') && pageField('A').value === '12', 8000);
    if (!ok) throw new Error(`campo mostra ${pageField('A').value}`);
    return 'página 12 renderizada e indicador atualizado';
  });
  await check('Botões de página e rolagem atualizam o indicador', async () => {
    click('A', '[aria-label="Próxima página"]');
    if (!(await waitFor(() => pageField('A').value === '13', 4000))) throw new Error(`esperava 13, vi ${pageField('A').value}`);
    click('A', '[aria-label="Página anterior"]');
    if (!(await waitFor(() => pageField('A').value === '12', 4000))) throw new Error(`esperava 12, vi ${pageField('A').value}`);
    const sc = pane('A').querySelector<HTMLElement>('.pdf-scroller')!;
    sc.scrollTop += sc.clientHeight * 1.6;
    if (!(await waitFor(() => Number(pageField('A').value) > 12, 4000))) throw new Error('rolar não mudou o indicador');
    return `indicador após rolar: ${pageField('A').value}`;
  });
  await check('Zoom manual desliga o ajuste; "Ajustar" o religa', async () => {
    const before = pct('A');
    click('A', '[aria-label="Aumentar zoom"]');
    await sleep(300);
    if (pct('A') <= before) throw new Error(`zoom não aumentou (${before}% → ${pct('A')}%)`);
    if (pane('A').querySelector('.zoom')!.getAttribute('data-fit') !== '0') throw new Error('ajuste continua ligado após zoom manual');
    const manual = pct('A');
    const btn = [...pane('A').querySelectorAll<HTMLElement>('button')].find((b) => b.textContent === 'Ajustar')!;
    btn.click();
    await sleep(300);
    if (pct('A') !== fitPct) throw new Error(`Ajustar voltou a ${pct('A')}%, esperava ${fitPct}%`);
    return `${before}% → ${manual}% (manual) → ${pct('A')}% (ajustar)`;
  });

  await check('Slot B independente: PDF escaneado avisa "sem texto" e A não muda', async () => {
    const aPage = pageField('A').value;
    await reader().setPath('B', pdfs.escaneado);
    const ok = await waitFor(() => pane('B').querySelector('[data-status="no_text"]'), 20000);
    if (!ok) throw new Error('estado no_text não apareceu');
    const t = pane('B').querySelector('.status')!.textContent!;
    if (!t.includes('sem texto pesquisável')) throw new Error(`aviso ausente: ${t}`);
    if (pageField('A').value !== aPage) throw new Error('a página de A mudou');
    return `B: ${t} · A segue na página ${aPage}`;
  });
  await check('Substituir B: novo documento zera página e zoom de B', async () => {
    click('B', '[aria-label="Aumentar zoom"]');
    await reader().setPath('B', pdfs.outro);
    const ok = await waitFor(() => pane('B').querySelector('.name')?.textContent === 'outro.pdf' && pane('B').querySelector('[data-status="ready"]'), 20000);
    if (!ok) throw new Error('B não mostra outro.pdf pronto');
    await waitFor(() => pane('B').querySelector('.pdf-page[data-rendered="1"]'), 10000);
    if (pageField('B').value !== '1') throw new Error(`página de B = ${pageField('B').value}`);
    if (pane('B').querySelector('.zoom')!.getAttribute('data-fit') !== '1') throw new Error('B não voltou ao ajuste');
    return `B: ${pane('B').querySelector('.status')!.textContent}`;
  });
  await check('Dois PDFs lado a lado com larguras próximas e divisor entre eles', async () => {
    const a = pane('A').getBoundingClientRect(), b = pane('B').getBoundingClientRect();
    const d = document.querySelector('.divider')!.getBoundingClientRect();
    if (!(a.right <= d.left + 1 && d.right <= b.left + 1)) throw new Error('painéis não estão lado a lado');
    return `A ${Math.round(a.width)}px | divisor | B ${Math.round(b.width)}px`;
  });
  await sleep(700);
  await window.estudo.capture('4-dois-pdfs');

  await check('Remover A: área de soltar volta e o backend limpa o slot', async () => {
    click('A', '[data-action="remove"]');
    const ok = await waitFor(() => pane('A').querySelector('.drop'), 8000);
    if (!ok) throw new Error('área de soltar não apareceu');
    const w = (await (await fetch('/api/workspace')).json()) as { slots: Record<string, { name: string } | null> };
    if (w.slots.A !== null) throw new Error('backend ainda tem o slot A');
    if (w.slots.B?.name !== 'outro.pdf') throw new Error('o slot B foi afetado');
    return 'A vazio, B intacto';
  });
  await check('Sessão restaurada ao voltar para a tela', async () => {
    nav('settings');
    await sleep(400);
    nav('reader');
    const ok = await waitFor(() => pane('B')?.querySelector('.name')?.textContent === 'outro.pdf' && pane('B').querySelector('.pdf-page'), 10000);
    if (!ok) throw new Error('B não voltou ao reabrir a tela');
    return 'B restaurado do backend';
  });

  await check('Rolagem virtualizada: poucas páginas com canvas (24 páginas)', async () => {
    await reader().setPath('A', pdfs.texto);
    await waitFor(() => pane('A').querySelector('.pdf-page[data-rendered="1"]'), 20000);
    const sc = pane('A').querySelector<HTMLElement>('.pdf-scroller')!;
    const eng = window.__glass?.engine;
    const f0 = eng ? eng.stats.dts.length : 0;
    let peak = 0;
    for (let y = 0; y <= sc.scrollHeight; y += 420) {
      sc.scrollTop = y;
      await sleep(30);
      peak = Math.max(peak, pane('A').querySelectorAll('canvas').length);
    }
    await sleep(700);
    const end = pane('A').querySelectorAll('canvas').length;
    if (end >= 24 || peak >= 24) throw new Error(`sem virtualização (pico ${peak}, final ${end})`);
    let extra = '';
    if (eng && info.windowVisible) {
      const d = eng.stats.dts.slice(f0).sort((a, b) => a - b);
      if (d.length > 20) extra = ` · quadros do vidro durante a rolagem: ${d.length}, média ${(d.reduce((s, x) => s + x, 0) / d.length).toFixed(2)} ms, p95 ${d[Math.floor(d.length * 0.95)].toFixed(2)} ms, máx ${d[d.length - 1].toFixed(2)} ms`;
    }
    return `de 24 páginas, no máximo ${peak} canvas ao mesmo tempo (final: ${end})${extra}`;
  });
  sc0(); // volta ao topo para a captura
  await sleep(600);
  await window.estudo.capture('5-pdfs-virtualizados');
  void results;

  function sc0() { pane('A').querySelector<HTMLElement>('.pdf-scroller')!.scrollTop = 0; }
}

export async function runSelfTest(): Promise<void> {
  const results: Check[] = [];
  const check = async (name: string, fn: () => Promise<string | void>) => {
    try { results.push({ name, ok: true, detail: (await fn()) ?? '' }); }
    catch (e) { results.push({ name, ok: false, detail: String((e as Error).message ?? e) }); }
  };
  const info = await window.estudo.info();
  const nav = (id: string) => document.querySelector<HTMLElement>(`[data-nav="${id}"]`)!.click();

  // primeiro de tudo: o estado da barra lateral ao abrir (prova da persistência entre reinícios)
  await check('Barra lateral ao iniciar', async () => {
    const el = await waitFor(() => document.querySelector('.sidebar'), 5000);
    if (!el) throw new Error('barra lateral ausente');
    const app = document.querySelector<HTMLElement>('.app')!;
    const kids = [...el.querySelectorAll<HTMLElement>('*')].map((k) => [k.className && typeof k.className === 'string' ? k.className : k.tagName, Math.round(k.getBoundingClientRect().width)] as const)
      .sort((a, b) => b[1] - a[1]).slice(0, 4).map(([n, w]) => `${n}:${w}`).join(', ');
    const dbg = `cols=${getComputedStyle(app).gridTemplateColumns}; sb=${W(el).toFixed(1)}/${(el as HTMLElement).offsetWidth}; maiores=${kids}`;
    return `${el.getAttribute('data-collapsed') === '1' ? 'collapsed' : 'expanded'}; preferência salva: ${savedPref() ?? 'nenhuma'} | ${dbg}`;
  });

  // ---- casca (Etapa 1) ----
  await check('Contexto isolado (sem Node na página)', async () => {
    const g = globalThis as unknown as Record<string, unknown>;
    if (typeof g.require !== 'undefined' || typeof g.process !== 'undefined') throw new Error('Node exposto à página');
    return `Electron ${info.electron} · Chromium ${info.chromium}`;
  });
  await check('Token não visível para a página', async () => {
    const keys = [...Object.keys(window.estudo), ...Object.keys(info)];
    if (keys.some((k) => /^(token|port|baseurl|backendport|backendtoken)$/i.test(k))) throw new Error('ponte expõe token/porta');
    if (JSON.stringify(info).match(/[0-9a-f]{64}/i)) throw new Error('info contém algo com formato de token');
    return 'chaves expostas: ' + keys.join(', ');
  });
  await check('GET /api/health pelo proxy', async () => {
    const r = await fetch('/api/health');
    if (r.status !== 200 || (await r.json()).status !== 'ok') throw new Error(`HTTP ${r.status}`);
    return 'HTTP 200';
  });
  await check('Página não consegue impor outro token', async () => {
    const r = await fetch('/api/health', { headers: { 'X-Estudotron-Token': 'falso' } });
    if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
  });
  await check('Corpo JSON repassado (PUT com caminho inexistente)', async () => {
    const r = await fetch('/api/workspace/slots/A', { method: 'PUT', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ path: 'C:\\nao-existe\\x.pdf' }) });
    if (r.status === 401 || r.status >= 500) throw new Error(`HTTP ${r.status}`);
    return `HTTP ${r.status}`;
  });
  await check('Sem path traversal nos arquivos da interface', async () => {
    const st: number[] = [];
    for (const p of ['/..%2fpackage.json', '/%2e%2e/package.json', '/..%5cpackage.json']) {
      const r = await fetch(p);
      st.push(r.status);
      if (r.status === 200 && !(r.headers.get('content-type') ?? '').includes('html')) throw new Error(`vazou ${p}`);
    }
    return 'HTTP ' + st.join(', ');
  });
  await check('SSE repassado em streaming (não em bloco)', async () => {
    const t0 = performance.now();
    const reader = (await fetch('/__selftest/sse')).body!.getReader();
    const dec = new TextDecoder();
    const at: number[] = [];
    let text = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      at.push(performance.now() - t0);
      text += dec.decode(value, { stream: true });
    }
    const ticks = (text.match(/event: tick/g) ?? []).length;
    const spread = at[at.length - 1] - at[0];
    if (ticks !== 5) throw new Error(`recebi ${ticks} de 5 eventos`);
    if (at.length < 3 || spread < 300) throw new Error(`chegou em bloco (${at.length} leituras)`);
    return `${ticks} eventos em ${at.length} leituras, ${Math.round(spread)} ms`;
  });

  // ---- sistema de design (Etapa 2) ----
  await check('Motor de vidro WebGL2 iniciou e desenha', async () => {
    const eng = await waitFor(() => (window.__glass?.engine?.ready ? window.__glass.engine : null), 5000);
    if (!eng) throw new Error(`motor indisponível (fallback=${window.__glass?.fallback})`);
    if (info.windowVisible) {
      const drew = await waitFor(() => eng.stats.frames > 5, 5000);
      if (!drew) throw new Error(`sem quadros (frames=${eng.stats.frames})`);
    } // janela oculta: o Chromium suspende o rAF; só confirmamos que o contexto e os shaders iniciaram
    if (eng.stats.shapes < 3) throw new Error(`poucas superfícies registradas (${eng.stats.shapes})`);
    return `${eng.stats.shapes} superfícies, ${eng.stats.frames} quadros`;
  });
  await window.estudo.capture('1-leitor');

  await check('Navegar para Configurações e listar provedores (API real)', async () => {
    nav('settings');
    const cards = await waitFor(() => { const c = document.querySelectorAll('[data-provider]'); return c.length >= 2 ? c : null; }, 8000);
    if (!cards) throw new Error('provedores não apareceram');
    const rows = document.querySelectorAll('[data-model]').length;
    if (rows < 3) throw new Error(`poucos modelos (${rows})`);
    return `${cards.length} provedores, ${rows} modelos`;
  });
  await check('Nenhuma chave visível na tela', async () => {
    const txt = document.body.innerText;
    if (/sk-ant-|AIza[0-9A-Za-z_-]{10}/.test(txt)) throw new Error('padrão de chave no DOM');
    for (const i of document.querySelectorAll<HTMLInputElement>('input[type=password]')) if (i.value) throw new Error('campo de chave preenchido');
  });
  await check('Escolher outro modelo grava no backend e a tela reflete', async () => {
    const row = document.querySelector<HTMLElement>('[data-provider] [data-model][aria-checked="false"]');
    if (!row) throw new Error('nenhum modelo não selecionado para clicar');
    const id = row.dataset.model!;
    row.click();
    const ok = await waitFor(() => document.querySelector(`[data-model="${CSS.escape(id)}"][aria-checked="true"]`), 5000);
    if (!ok) throw new Error(`modelo ${id} não ficou selecionado`);
    return `selecionado: ${id} (banco temporário do autoteste)`;
  });
  await check('Superfícies de vidro na tela de Configurações dentro do limite', async () => {
    const eng = window.__glass!.engine!;
    await sleep(500);
    if (eng.stats.shapes >= 40) throw new Error(`no limite do motor (${eng.stats.shapes}/40)`);
    return `${eng.stats.shapes}/40`;
  });
  await check('Medição de quadros (Configurações, cursor em movimento, 5 s)', async () => {
    if (!info.windowVisible) return 'IGNORADA: janela oculta suspende o rAF (use --show ou --shots)';
    const eng = window.__glass!.engine!;
    const rows = [...document.querySelectorAll<HTMLElement>('[data-provider] button')];
    const start = eng.stats.dts.length;
    const t0 = performance.now();
    while (performance.now() - t0 < 5000) {
      const p = (performance.now() - t0) / 5000;
      const b = rows[Math.floor(p * rows.length * 2) % rows.length].getBoundingClientRect();
      window.dispatchEvent(new PointerEvent('pointermove', { clientX: b.left + b.width * ((p * 9) % 1), clientY: b.top + b.height / 2 }));
      await sleep(16);
    }
    const d = eng.stats.dts.slice(Math.max(0, start)).sort((a, b) => a - b);
    if (d.length < 100) throw new Error(`poucos quadros (${d.length})`);
    const avg = d.reduce((s, x) => s + x, 0) / d.length;
    return `${d.length} quadros · média ${avg.toFixed(2)} ms · p95 ${d[Math.floor(d.length * 0.95)].toFixed(2)} ms · máx ${d[d.length - 1].toFixed(2)} ms · ${eng.stats.shapes} superfícies`;
  });
  await sleep(900);
  await window.estudo.capture('2-configuracoes');
  nav('exercises');
  await sleep(500);
  nav('reader');

  TEST_PDFS = info.testPdfs ?? {};
  if (info.smoke) {
    await smokeReader(info, check);
  } else {
    await readerScenario(info, check, results);
    await chatScenario(info, check);
    await exercisesScenario(info, check);
    await layoutScenario(info, check);
  }

  window.estudo.reportSelfTest({ ok: results.every((r) => r.ok), checks: results });
}
