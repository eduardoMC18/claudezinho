import { app, BrowserWindow, dialog, ipcMain, shell } from 'electron';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { spawn, type ChildProcess } from 'node:child_process';
import net from 'node:net';
import { Backend, freePort, resolveBackendDir, resolvePython, startBackend, TOKEN_HEADER } from './backend';
import { HOST, installProtocol, registerScheme, SCHEME } from './protocol';

const SELFTEST = process.argv.includes('--selftest');
/** --shots=<pasta>: o autoteste salva capturas da janela (só em --selftest). */
const SHOTS_DIR = SELFTEST ? process.argv.find((a) => a.startsWith('--shots='))?.slice('--shots='.length) : undefined;
registerScheme(); // antes de ready

let backend: Backend | null = null;
let sseServer: http.Server | null = null;
let ssePort: number | null = null;
let mainWindow: BrowserWindow | null = null;
let quitting = false;

const RENDERER_DIR = path.resolve(__dirname, '..', '..', 'renderer');

if (!app.requestSingleInstanceLock() && !SELFTEST) {
  app.quit();
}

/** Servidor SSE local, só para provar que o proxy repassa o corpo em streaming (não em bloco). */
function startSseServer(): Promise<void> {
  return new Promise((resolve) => {
    sseServer = http.createServer((_req, res) => {
      res.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' });
      let n = 0;
      const timer = setInterval(() => {
        res.write(`event: tick\ndata: ${++n}\n\n`);
        if (n >= 5) { clearInterval(timer); res.end(); }
      }, 150);
    });
    sseServer.listen(0, '127.0.0.1', () => {
      ssePort = (sseServer!.address() as import('node:net').AddressInfo).port;
      resolve();
    });
  });
}

/** Verificações que só o processo principal consegue fazer (ele conhece o token). */
async function mainSideChecks(): Promise<Record<string, unknown>> {
  const b = backend!;
  const noToken = await fetch(`${b.baseUrl()}/health`);
  const withToken = await fetch(`${b.baseUrl()}/health`, { headers: { [TOKEN_HEADER]: b.token } });
  return {
    backendWithoutTokenStatus: noToken.status, // esperado 401
    backendWithTokenStatus: withToken.status, // esperado 200
    listensOnLoopbackOnly: b.baseUrl().startsWith('http://127.0.0.1:'),
  };
}

function createWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1140,
    minHeight: 560,
    // janela oculta suspende o rAF: capturas e medição de quadros exigem janela visível (--shots ou --show)
    show: !SELFTEST || !!SHOTS_DIR || process.argv.includes('--show'),
    backgroundColor: '#f4f4f6',
    title: 'estudoTron 3000',
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload', 'preload.js'),
      contextIsolation: true,
      sandbox: true,
      nodeIntegration: false,
      webSecurity: true,
      backgroundThrottling: !SELFTEST,
    },
  });
  // A interface só navega dentro do próprio esquema; links externos abrem no navegador do sistema.
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(`${SCHEME}://${HOST}/`)) e.preventDefault();
  });
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) void shell.openExternal(url);
    return { action: 'deny' };
  });
  // No autoteste a janela pode não ter foco: força o fundo animado para a medição de quadros.
  void win.loadURL(`${SCHEME}://${HOST}/index.html${SELFTEST ? '?alwaysAnimate=1' : ''}`);
  return win;
}

async function shutdown(code?: number): Promise<void> {
  if (quitting) return;
  quitting = true;
  sseServer?.close();
  fakeAi?.kill();
  await backend?.stop();
  backend = null;
  if (code !== undefined) app.exit(code);
  else app.quit();
}

/**
 * Autoteste: sobe o servidor Gemini FALSO do backend (tests/fake_gemini.py) e aponta o backend para ele.
 * O SDK oficial conversa de verdade com esse servidor, sem gastar créditos e sem chave real. O cofre de
 * segredos vira "memory" para o teste NUNCA tocar no Credential Manager do usuário.
 */
let fakeAi: ChildProcess | null = null;
async function startFakeAi(): Promise<void> {
  const dir = resolveBackendDir();
  const port = await freePort();
  fakeAi = spawn(resolvePython(dir), ['-m', 'tests.fake_gemini', String(port)], { cwd: dir, stdio: 'ignore', windowsHide: true });
  const deadline = Date.now() + 15_000;
  for (;;) {
    const up = await new Promise<boolean>((res) => {
      const s = net.connect(port, '127.0.0.1');
      s.once('connect', () => { s.destroy(); res(true); });
      s.once('error', () => res(false));
    });
    if (up) break;
    if (Date.now() > deadline) throw new Error('servidor Gemini falso não subiu');
    await new Promise((r) => setTimeout(r, 150));
  }
  process.env.GOOGLE_GEMINI_BASE_URL = `http://127.0.0.1:${port}`;
  process.env.ESTUDOTRON_SECRET_STORE = 'memory';
}

/** Autoteste: gera PDFs de teste com o PyMuPDF do backend (pasta temporária). */
let testPdfs: Record<string, string> | null = null;
function makeTestPdfs(): Record<string, string> {
  const backendDir = process.env.ESTUDOTRON_BACKEND_DIR ?? path.resolve(__dirname, '..', '..', '..', 'backend');
  const venv = path.join(backendDir, '.venv', 'Scripts', 'python.exe');
  const python = process.env.ESTUDOTRON_PYTHON ?? (fs.existsSync(venv) ? venv : 'python');
  const script = path.resolve(__dirname, '..', '..', 'tools', 'make_test_pdfs.py');
  const out = fs.mkdtempSync(path.join(os.tmpdir(), 'estudotron-pdfs-'));
  const stdout = execFileSync(python, [script, out], { encoding: 'utf8' });
  return JSON.parse(stdout.trim().split('\n').pop()!);
}

ipcMain.handle('dialog:pickPdf', async () => {
  const opts: Electron.OpenDialogOptions = { properties: ['openFile'], filters: [{ name: 'PDF', extensions: ['pdf'] }] };
  const r = mainWindow ? await dialog.showOpenDialog(mainWindow, opts) : await dialog.showOpenDialog(opts);
  return r.canceled || r.filePaths.length === 0 ? null : r.filePaths[0];
});

ipcMain.handle('app:info', () => ({
  testPdfs,
  fakeAi: fakeAi !== null,
  selfTest: SELFTEST,
  backendReady: backend !== null,
  backendPid: backend?.pid ?? null, // não é segredo
  windowVisible: mainWindow?.isVisible() ?? false,
  electron: process.versions.electron,
  chromium: process.versions.chrome,
}));

ipcMain.handle('selftest:capture', async (_e, name: string) => {
  if (!SHOTS_DIR || !mainWindow) return null;
  fs.mkdirSync(SHOTS_DIR, { recursive: true });
  const file = path.join(SHOTS_DIR, `${String(name).replace(/[^\w-]/g, '_')}.png`);
  const img = await mainWindow.webContents.capturePage();
  fs.writeFileSync(file, img.toPNG());
  return file;
});

ipcMain.on('selftest:result', async (_e, rendererResult: { ok: boolean }) => {
  const mainSide = await mainSideChecks();
  const ok = rendererResult.ok && mainSide.backendWithoutTokenStatus === 401 && mainSide.backendWithTokenStatus === 200;
  console.log('SELFTEST_RESULT ' + JSON.stringify({ ok, renderer: rendererResult, main: mainSide }));
  await shutdown(ok ? 0 : 1);
});

app.on('before-quit', (e) => {
  if (!quitting && backend) {
    e.preventDefault();
    void shutdown();
  }
});
app.on('window-all-closed', () => app.quit());

app.whenReady().then(async () => {
  if (SELFTEST) {
    process.env.ESTUDOTRON_DATA_DIR ??= fs.mkdtempSync(path.join(os.tmpdir(), 'estudotron-selftest-'));
    await startSseServer();
    try { testPdfs = makeTestPdfs(); } catch (err) { console.error('Falha ao gerar PDFs de teste:', String(err)); }
    try { await startFakeAi(); } catch (err) { console.error('Falha ao subir a IA falsa:', String(err)); }
  }
  try {
    backend = await startBackend();
  } catch (err) {
    console.error(String(err));
    if (SELFTEST) { console.log('SELFTEST_RESULT ' + JSON.stringify({ ok: false, error: String(err) })); app.exit(1); return; }
    dialog.showErrorBox('estudoTron 3000', `Não foi possível iniciar o backend.\n\n${String(err)}`);
    app.exit(1);
    return;
  }
  installProtocol({
    rendererDir: RENDERER_DIR,
    backend: () => (backend ? { baseUrl: backend.baseUrl(), token: backend.token } : null),
    selfTestSse: () => (SELFTEST && ssePort ? `http://127.0.0.1:${ssePort}` : null),
  });
  mainWindow = createWindow();
  mainWindow.on('closed', () => { mainWindow = null; });
});
