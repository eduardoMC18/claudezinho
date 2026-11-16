// Copia os dados do PDF.js (CMaps, fontes padrão, wasm, perfis ICC) para renderer/pdfjs/,
// de onde o app os serve em app://local/pdfjs/... (o Vite só empacota o JavaScript).
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const src = path.join(root, 'node_modules', 'pdfjs-dist');
const dst = path.join(root, 'renderer', 'pdfjs');

if (!existsSync(src)) throw new Error('pdfjs-dist não instalado (rode npm install em desktop/)');
rmSync(dst, { recursive: true, force: true });
mkdirSync(dst, { recursive: true });
for (const dir of ['cmaps', 'standard_fonts', 'wasm', 'iccs']) {
  cpSync(path.join(src, dir), path.join(dst, dir), { recursive: true });
}
console.log('PDF.js: dados copiados para', path.relative(root, dst));
