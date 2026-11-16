import { net, protocol } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import { TOKEN_HEADER } from './backend';

/**
 * Esquema próprio `app://local/...` (mesma origem para a interface e para a API):
 *   app://local/index.html  -> arquivos da interface (renderer/)
 *   app://local/api/<rota>  -> proxy para o backend em 127.0.0.1, COM o token injetado aqui.
 *
 * O token nunca chega ao JavaScript da página; um script injetado na interface não consegue
 * lê-lo nem falar com o backend sem passar por este proxy. O corpo é repassado em streaming
 * (necessário para o SSE do chat).
 */

export const SCHEME = 'app';
export const HOST = 'local';

/** Chamar ANTES de app.whenReady(). */
export function registerScheme(): void {
  protocol.registerSchemesAsPrivileged([
    { scheme: SCHEME, privileges: { standard: true, secure: true, supportFetchAPI: true, stream: true } },
  ]);
}

export interface ProxyTargets {
  rendererDir: string;
  /** Backend real; null enquanto não subiu. */
  backend: () => { baseUrl: string; token: string } | null;
  /** Apenas autoteste: rota extra encaminhada para um servidor SSE local, sem token. */
  selfTestSse?: () => string | null;
}

async function forward(req: Request, target: string, token?: string): Promise<Response> {
  const headers = new Headers(req.headers);
  for (const h of ['host', 'origin', 'referer', TOKEN_HEADER.toLowerCase()]) headers.delete(h);
  if (token) headers.set(TOKEN_HEADER, token); // sempre sobrescreve: a página não escolhe o token
  const hasBody = req.method !== 'GET' && req.method !== 'HEAD';
  const init: RequestInit & { duplex?: 'half'; bypassCustomProtocolHandlers?: boolean } = {
    method: req.method,
    headers,
    body: hasBody ? req.body : undefined,
    duplex: 'half',
    bypassCustomProtocolHandlers: true,
  };
  try {
    return await net.fetch(target, init);
  } catch (err) {
    return new Response(JSON.stringify({ detail: `Backend indisponível: ${(err as Error).message}` }), {
      status: 502,
      headers: { 'content-type': 'application/json' },
    });
  }
}

/** Tipos de conteúdo dos arquivos da interface. Scripts de módulo exigem JavaScript; wasm exige application/wasm. */
const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.ico': 'image/x-icon',
  '.wasm': 'application/wasm',
  '.woff2': 'font/woff2',
};

/**
 * Serve um arquivo da interface lendo-o com `fs` (funciona dentro do app.asar do app empacotado, ao contrário de
 * `net.fetch(file://...)`, que não depende do asar patch do fs).
 */
async function serveStatic(rendererDir: string, pathname: string): Promise<Response> {
  const rel = decodeURIComponent(pathname === '/' ? '/index.html' : pathname);
  const file = path.resolve(rendererDir, '.' + rel);
  // impede sair da pasta da interface (../)
  if (file !== rendererDir && !file.startsWith(rendererDir + path.sep)) return new Response('Forbidden', { status: 403 });
  if (!fs.statSync(file, { throwIfNoEntry: false })?.isFile()) return new Response('Not found', { status: 404 });
  const body = await fs.promises.readFile(file);
  return new Response(new Uint8Array(body), {
    headers: { 'content-type': MIME[path.extname(file).toLowerCase()] ?? 'application/octet-stream', 'cache-control': 'no-store' },
  });
}

export function installProtocol(t: ProxyTargets): void {
  protocol.handle(SCHEME, (req) => {
    const url = new URL(req.url);
    if (url.host !== HOST) return new Response('Not found', { status: 404 });

    if (url.pathname === '/api' || url.pathname.startsWith('/api/')) {
      const b = t.backend();
      if (!b) return new Response(JSON.stringify({ detail: 'Backend ainda não iniciou' }), { status: 503 });
      const rest = url.pathname.slice('/api'.length) || '/';
      return forward(req, b.baseUrl + rest + url.search, b.token);
    }
    if (url.pathname === '/__selftest/sse') {
      const base = t.selfTestSse?.();
      return base ? forward(req, base + '/sse') : new Response('Not found', { status: 404 });
    }
    return serveStatic(t.rendererDir, url.pathname);
  });
}
