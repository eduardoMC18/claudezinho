'use strict';
// Etapa 1: página mínima que prova a cadeia casca -> proxy -> backend (sem token na interface).

const list = document.getElementById('checks');
const results = [];

function add(name, ok, detail) {
  results.push({ name, ok, detail });
  const li = document.createElement('li');
  li.className = ok ? 'ok' : 'fail';
  li.innerHTML = '<span class="mark"></span><span class="n"></span><small></small>';
  li.querySelector('.mark').textContent = ok ? '✓' : '✗';
  li.querySelector('.n').textContent = name;
  li.querySelector('small').textContent = detail ?? '';
  list.appendChild(li);
}

async function check(name, fn) {
  try {
    const detail = await fn();
    add(name, true, detail);
  } catch (e) {
    add(name, false, String(e.message ?? e));
  }
}

async function run() {
  const info = await window.estudo.info();

  await check('Contexto isolado (sem Node na página)', async () => {
    if (typeof require !== 'undefined' || typeof process !== 'undefined') throw new Error('Node exposto à página');
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
    const j = await r.json();
    if (r.status !== 200 || j.status !== 'ok') throw new Error(`HTTP ${r.status}`);
    return 'HTTP 200';
  });

  await check('Página não consegue impor outro token', async () => {
    const r = await fetch('/api/health', { headers: { 'X-Estudotron-Token': 'falso' } });
    if (r.status !== 200) throw new Error(`HTTP ${r.status} (o proxy deveria sobrescrever o token)`);
    return 'proxy sobrescreve o cabeçalho';
  });

  await check('GET /api/workspace (dados reais do backend)', async () => {
    const r = await fetch('/api/workspace');
    const j = await r.json();
    if (r.status !== 200) throw new Error(`HTTP ${r.status}`);
    return `HTTP 200 · chaves: ${Object.keys(j).join(', ')}`;
  });

  await check('Corpo JSON repassado (PUT com caminho inexistente)', async () => {
    const r = await fetch('/api/workspace/slots/A', {
      method: 'PUT',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ path: 'C:\\nao-existe\\x.pdf' }),
    });
    if (r.status === 401 || r.status >= 500) throw new Error(`HTTP ${r.status}`);
    return `HTTP ${r.status} (rejeitado pelo backend, corpo chegou)`;
  });

  await check('Sem path traversal nos arquivos da interface', async () => {
    const statuses = [];
    for (const p of ['/..%2fpackage.json', '/%2e%2e/package.json', '/..%5cpackage.json']) {
      const r = await fetch(p);
      statuses.push(r.status);
      if (r.status === 200) throw new Error(`vazou ${p}`);
    }
    return 'HTTP ' + statuses.join(', ');
  });

  if (info.selfTest) {
    await check('SSE repassado em streaming (não em bloco)', async () => {
      const t0 = performance.now();
      const res = await fetch('/__selftest/sse');
      const reader = res.body.getReader();
      const dec = new TextDecoder();
      const arrivals = [];
      let text = '';
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        arrivals.push(performance.now() - t0);
        text += dec.decode(value, { stream: true });
      }
      const ticks = (text.match(/event: tick/g) ?? []).length;
      const spread = arrivals.at(-1) - arrivals[0];
      if (ticks !== 5) throw new Error(`recebi ${ticks} de 5 eventos`);
      if (arrivals.length < 3 || spread < 300) throw new Error(`chegou em bloco (${arrivals.length} leituras, ${Math.round(spread)} ms)`);
      return `${ticks} eventos em ${arrivals.length} leituras, espalhados por ${Math.round(spread)} ms`;
    });
  }

  const okAll = results.every((r) => r.ok);
  document.getElementById('summary').textContent = okAll ? 'Tudo certo.' : 'Há falhas.';
  if (info.selfTest) window.estudo.reportSelfTest({ ok: okAll, checks: results });
}

run();
