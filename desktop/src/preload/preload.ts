import { contextBridge, ipcRenderer, webUtils } from 'electron';

/**
 * Ponte mínima e explícita. NÃO expõe token, porta do backend, Node, nem acesso a arquivos.
 * A interface fala com o backend por `fetch('/api/...')` (proxy do processo principal).
 */
contextBridge.exposeInMainWorld('estudo', {
  info: (): Promise<{
    selfTest: boolean;
    smoke: boolean;
    packaged: boolean;
    backendReady: boolean;
    backendPid: number | null;
    windowVisible: boolean;
    /** Só no autoteste: PDFs gerados para os testes. */
    testPdfs: Record<string, string> | null;
    /** Só no autoteste: o backend aponta para o servidor de IA FALSO e usa cofre em memória. */
    fakeAi: boolean;
    /** Só no autoteste: Canvas FALSO (endereço e token de teste). */
    fakeCanvas: { baseUrl: string; token: string } | null;
    electron: string;
    chromium: string;
  }> => ipcRenderer.invoke('app:info'),
  reportSelfTest: (result: unknown): void => ipcRenderer.send('selftest:result', result),
  capture: (name: string): Promise<string | null> => ipcRenderer.invoke('selftest:capture', name),
  /** Só no autoteste: comando para o Canvas falso. */
  canvasControl: (query: string): Promise<boolean> => ipcRenderer.invoke('selftest:canvasControl', query),
  /** Só no autoteste: redimensiona a área útil da janela. */
  resize: (w: number, h: number): Promise<[number, number] | null> => ipcRenderer.invoke('selftest:resize', w, h),
  /** Abre o seletor de arquivos do sistema (somente PDF). Retorna o caminho ou null se cancelado. */
  pickPdf: (): Promise<string | null> => ipcRenderer.invoke('dialog:pickPdf'),
  /** Caminho no disco de um arquivo arrastado para a janela. */
  pathForFile: (file: File): string => webUtils.getPathForFile(file),
});
