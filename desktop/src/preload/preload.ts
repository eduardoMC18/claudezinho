import { contextBridge, ipcRenderer } from 'electron';

/**
 * Ponte mínima e explícita. NÃO expõe token, porta do backend, Node, nem acesso a arquivos.
 * A interface fala com o backend por `fetch('/api/...')` (proxy do processo principal).
 */
contextBridge.exposeInMainWorld('estudo', {
  info: (): Promise<{
    selfTest: boolean;
    backendReady: boolean;
    backendPid: number | null;
    electron: string;
    chromium: string;
  }> => ipcRenderer.invoke('app:info'),
  reportSelfTest: (result: unknown): void => ipcRenderer.send('selftest:result', result),
});
