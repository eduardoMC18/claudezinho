/** Ponte exposta pelo preload (src/preload/preload.ts). Sem token, sem porta, sem Node. */
interface EstudoBridge {
  info(): Promise<{
    selfTest: boolean;
    backendReady: boolean;
    backendPid: number | null;
    windowVisible: boolean;
    testPdfs: Record<string, string> | null;
    electron: string;
    chromium: string;
  }>;
  reportSelfTest(result: unknown): void;
  /** Só no autoteste com --shots=<pasta>: salva uma captura da janela. Retorna o caminho ou null. */
  capture(name: string): Promise<string | null>;
  pickPdf(): Promise<string | null>;
  pathForFile(file: File): string;
}

interface Window {
  estudo: EstudoBridge;
}
