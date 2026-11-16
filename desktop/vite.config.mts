import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';

// A interface (React) é compilada para desktop/renderer/, a pasta que o protocolo app://local/ serve.
export default defineConfig({
  root: path.resolve(import.meta.dirname, 'src/renderer'),
  base: './',
  plugins: [react()],
  build: {
    outDir: path.resolve(import.meta.dirname, 'renderer'),
    emptyOutDir: true,
    target: 'chrome140', // o Chromium do Electron é fixo; sem polyfills
    sourcemap: true,
  },
});
