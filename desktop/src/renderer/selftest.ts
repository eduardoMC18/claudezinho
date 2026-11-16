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

  window.estudo.reportSelfTest({ ok: results.every((r) => r.ok), checks: results });
}
