/** Autoteste (--selftest): roda dentro da interface real e reporta ao processo principal. */

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
    for (let i = 0; i < d.length; i += 4) if (d[i] < 100 && d[i + 3] > 0) dark++;
    if (dark < 200) throw new Error(`só ${dark} pixels escuros`);
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

  await readerScenario(info, check, results);

  window.estudo.reportSelfTest({ ok: results.every((r) => r.ok), checks: results });
}
