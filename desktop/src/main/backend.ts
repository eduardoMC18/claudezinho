import { spawn, ChildProcess } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import fs from 'node:fs';
import net from 'node:net';
import path from 'node:path';

/**
 * Sobe e derruba o backend Python (mesmo protocolo do BackendManager em Java):
 * porta livre aleatória, token aleatório por variável de ambiente (nunca por argumento),
 * espera por /health, e `--exit-on-stdin-eof` para o backend morrer junto se a casca morrer.
 */

export interface Backend {
  readonly port: number;
  /** Token de sessão. Fica SOMENTE no processo principal; a interface nunca o recebe. */
  readonly token: string;
  readonly pid: number | undefined;
  /** Chamada direta ao backend (com ou sem token) — usada pelo proxy e pelos autotestes. */
  baseUrl(): string;
  stop(): Promise<void>;
}

const START_TIMEOUT_MS = 30_000;
export const TOKEN_HEADER = 'X-Estudotron-Token';

export function freePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.once('error', reject);
    srv.listen(0, '127.0.0.1', () => {
      const { port } = srv.address() as net.AddressInfo;
      srv.close(() => resolve(port));
    });
  });
}

function resolveBackendDir(): string {
  // dist/main/backend.js -> desktop/dist/main -> repositório
  const dir = process.env.ESTUDOTRON_BACKEND_DIR ?? path.resolve(__dirname, '..', '..', '..', 'backend');
  if (!fs.statSync(dir, { throwIfNoEntry: false })?.isDirectory()) {
    throw new Error(`Pasta do backend não encontrada: ${dir} (defina ESTUDOTRON_BACKEND_DIR)`);
  }
  return dir;
}

function resolvePython(backendDir: string): string {
  if (process.env.ESTUDOTRON_PYTHON) return process.env.ESTUDOTRON_PYTHON;
  const venv = path.join(backendDir, '.venv', 'Scripts', 'python.exe');
  return fs.existsSync(venv) ? venv : 'python';
}

async function healthy(port: number, token: string): Promise<boolean> {
  try {
    const r = await fetch(`http://127.0.0.1:${port}/health`, { headers: { [TOKEN_HEADER]: token } });
    return r.ok;
  } catch {
    return false;
  }
}

export async function startBackend(): Promise<Backend> {
  const backendDir = resolveBackendDir();
  const port = await freePort();
  const token = randomBytes(32).toString('hex');

  const child: ChildProcess = spawn(
    resolvePython(backendDir),
    ['-m', 'app', '--port', String(port), '--exit-on-stdin-eof'],
    {
      cwd: backendDir,
      env: { ...process.env, ESTUDOTRON_TOKEN: token },
      stdio: ['pipe', 'inherit', 'inherit'],
      windowsHide: true,
    },
  );

  let exited: number | null = null;
  child.once('exit', (code) => { exited = code ?? -1; });
  child.once('error', (err) => { console.error('Falha ao iniciar o backend:', err.message); exited = -1; });

  const deadline = Date.now() + START_TIMEOUT_MS;
  while (!(await healthy(port, token))) {
    if (exited !== null) throw new Error(`O backend encerrou durante a inicialização (código ${exited}).`);
    if (Date.now() > deadline) {
      child.kill();
      throw new Error(`O backend não respondeu em ${START_TIMEOUT_MS / 1000} s.`);
    }
    await new Promise((r) => setTimeout(r, 200));
  }

  return {
    port,
    token,
    pid: child.pid,
    baseUrl: () => `http://127.0.0.1:${port}`,
    stop: () =>
      new Promise<void>((resolve) => {
        if (exited !== null) return resolve();
        const timer = setTimeout(() => { child.kill(); }, 2000); // plano B se o watchdog não bastar
        child.once('exit', () => { clearTimeout(timer); resolve(); });
        child.stdin?.end(); // dispara o watchdog de stdin do backend
      }),
  };
}
