import { getDocument, GlobalWorkerOptions, TextLayer, type PDFDocumentLoadingTask, type PDFDocumentProxy, type RenderTask } from 'pdfjs-dist';
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import 'pdfjs-dist/web/pdf_viewer.css';

GlobalWorkerOptions.workerSrc = workerUrl;

// Dados do PDF.js copiados para renderer/pdfjs/ pelo scripts/copy-pdfjs-assets.mjs (build).
const ASSETS = '/pdfjs/';

export interface ViewerState {
  page: number;
  total: number;
  scale: number;
  /** true = ajusta à largura do painel (a página mais larga nunca é cortada). */
  fit: boolean;
  loading: boolean;
  error: string | null;
  /** Contador de navegações explícitas (goToPage): força o campo de página a ressincronizar mesmo se a página não mudou. */
  nav: number;
}

interface Slot {
  el: HTMLDivElement;
  w: number; // largura a 100% (px CSS)
  h: number;
  canvas: HTMLCanvasElement | null;
  textDiv: HTMLDivElement;
  task: RenderTask | null;
  textLayer: TextLayer | null;
  token: number; // invalida renderizações antigas
  rendered: boolean;
}

const PAD = 14; // espaço em volta das páginas
const GAP = 12; // entre páginas
const MARGIN = 1400; // pré-renderiza páginas até esta distância (px) da área visível
const RELEASE = 4200; // libera memória de páginas além desta distância
const MIN_SCALE = 0.25;
const MAX_SCALE = 5;

/**
 * Visualizador de um PDF (um por painel; estado nunca é compartilhado).
 * Rolagem virtualizada: só as páginas perto da área visível têm canvas e camada de texto.
 */
export class PdfController {
  state: ViewerState = { page: 1, total: 0, scale: 1, fit: true, loading: false, error: null, nav: 0 };

  private pdf: PDFDocumentProxy | null = null;
  private task: PDFDocumentLoadingTask | null = null;
  private slots: Slot[] = [];
  private tops: number[] = [];
  private maxW = 1;
  private loadId = 0;
  private raf = 0;
  private resizeTimer = 0;
  private readonly ro: ResizeObserver;
  private readonly onScroll = () => this.schedule();

  constructor(
    private readonly scroller: HTMLElement,
    private readonly host: HTMLElement,
    private readonly onChange: (s: ViewerState) => void,
  ) {
    this.scroller.addEventListener('scroll', this.onScroll, { passive: true });
    this.ro = new ResizeObserver(() => {
      window.clearTimeout(this.resizeTimer);
      this.resizeTimer = window.setTimeout(() => {
        if (this.state.fit) this.applyScale(this.fitScale());
        else this.schedule();
      }, 80);
    });
    this.ro.observe(this.scroller);
  }

  // ---------- ciclo de vida ----------

  async load(url: string): Promise<void> {
    const id = ++this.loadId;
    this.clear();
    this.set({ page: 1, total: 0, loading: true, error: null, fit: true });
    try {
      const task = getDocument({
        url,
        cMapUrl: ASSETS + 'cmaps/',
        cMapPacked: true,
        standardFontDataUrl: ASSETS + 'standard_fonts/',
        wasmUrl: ASSETS + 'wasm/',
        iccUrl: ASSETS + 'iccs/',
        useWorkerFetch: true,
      });
      this.task = task;
      const pdf = await task.promise;
      if (id !== this.loadId) { void task.destroy(); return; }
      this.pdf = pdf;
      const first = await pdf.getPage(1);
      const v = first.getViewport({ scale: 1 });
      this.maxW = v.width;
      for (let i = 0; i < pdf.numPages; i++) this.slots.push(this.makeSlot(i, v.width, v.height));
      this.set({ total: pdf.numPages, loading: false });
      this.applyScale(this.fitScale());
      void this.measureAll(id);
    } catch (e) {
      if (id !== this.loadId) return;
      this.set({ loading: false, error: (e as Error).message || 'Falha ao abrir o PDF' });
    }
  }

  destroy(): void {
    this.loadId++;
    this.scroller.removeEventListener('scroll', this.onScroll);
    this.ro.disconnect();
    window.clearTimeout(this.resizeTimer);
    cancelAnimationFrame(this.raf);
    this.clear();
  }

  private clear(): void {
    for (const s of this.slots) this.release(s);
    this.slots = [];
    this.tops = [];
    this.host.replaceChildren();
    const t = this.task;
    this.pdf = null;
    this.task = null;
    if (t) void t.destroy(); // libera o documento e o worker
  }

  // ---------- comandos ----------

  goToPage(n: number): void {
    if (!this.slots.length) return;
    const i = Math.min(Math.max(Math.round(n), 1), this.slots.length) - 1;
    this.scroller.scrollTop = this.tops[i] - 6;
    this.set({ nav: this.state.nav + 1 }); // reemite: o campo de página volta a mostrar a página real
    this.schedule();
  }

  zoomBy(factor: number): void {
    this.set({ fit: false });
    this.applyScale(this.state.scale * factor);
  }

  setFit(): void {
    this.set({ fit: true });
    this.applyScale(this.fitScale());
  }

  // ---------- internos ----------

  private set(p: Partial<ViewerState>): void {
    const next = { ...this.state, ...p };
    if (JSON.stringify(next) === JSON.stringify(this.state)) return;
    this.state = next;
    this.onChange(next);
  }

  private makeSlot(i: number, w: number, h: number): Slot {
    const el = document.createElement('div');
    el.className = 'pdf-page';
    el.dataset.page = String(i + 1);
    const textDiv = document.createElement('div');
    textDiv.className = 'textLayer';
    el.appendChild(textDiv);
    this.host.appendChild(el);
    return { el, w, h, canvas: null, textDiv, task: null, textLayer: null, token: 0, rendered: false };
  }

  /** Mede o tamanho de todas as páginas em segundo plano (para o ajuste à largura e para a rolagem exata). */
  private async measureAll(id: number): Promise<void> {
    const pdf = this.pdf;
    if (!pdf) return;
    const B = 16;
    for (let start = 1; start <= pdf.numPages; start += B) {
      const batch = await Promise.all(
        Array.from({ length: Math.min(B, pdf.numPages - start + 1) }, async (_, k) => {
          const p = await pdf.getPage(start + k);
          const v = p.getViewport({ scale: 1 });
          return { i: start + k - 1, w: v.width, h: v.height };
        }),
      );
      if (id !== this.loadId || this.pdf !== pdf) return;
      let changed = false;
      for (const { i, w, h } of batch) {
        const s = this.slots[i];
        if (s && (s.w !== w || s.h !== h)) { s.w = w; s.h = h; changed = true; this.release(s); }
        if (w > this.maxW) { this.maxW = w; changed = true; }
      }
      if (changed) this.applyScale(this.state.fit ? this.fitScale() : this.state.scale, true);
    }
  }

  private fitScale(): number {
    const avail = Math.max(this.scroller.clientWidth - 2 * PAD, 80);
    return Math.min(Math.max(avail / this.maxW, MIN_SCALE), 4);
  }

  private applyScale(raw: number, keepRendered = false): void {
    const scale = Math.min(Math.max(raw, MIN_SCALE), MAX_SCALE);
    // âncora: mantém a mesma região do documento na tela
    const a = this.anchor();
    this.layout(scale);
    if (!keepRendered || Math.abs(scale - this.state.scale) > 1e-6) for (const s of this.slots) this.release(s);
    this.set({ scale });
    if (a) this.scroller.scrollTop = this.tops[a.i] + a.frac * this.slots[a.i].h * scale - 0;
    this.schedule();
  }

  private anchor(): { i: number; frac: number } | null {
    if (!this.slots.length || !this.tops.length) return null;
    const y = this.scroller.scrollTop;
    let i = this.slots.length - 1;
    for (let k = 0; k < this.tops.length; k++) if (this.tops[k] + this.slots[k].h * this.state.scale > y) { i = k; break; }
    const h = this.slots[i].h * this.state.scale;
    return { i, frac: h > 0 ? Math.min(Math.max((y - this.tops[i]) / h, 0), 1) : 0 };
  }

  private layout(scale: number): void {
    let y = PAD;
    this.tops = [];
    this.host.style.setProperty('--scale-factor', String(scale));
    for (const s of this.slots) {
      const w = s.w * scale, h = s.h * scale;
      s.el.style.width = `${w}px`;
      s.el.style.height = `${h}px`;
      s.el.style.setProperty('--scale-factor', String(scale));
      s.el.style.setProperty('--total-scale-factor', String(scale));
      s.textDiv.style.width = `${w}px`;
      s.textDiv.style.height = `${h}px`;
      this.tops.push(y);
      y += h + GAP;
    }
    this.host.style.height = `${y - GAP + PAD}px`;
  }

  private schedule(): void {
    if (this.raf) return;
    this.raf = requestAnimationFrame(() => { this.raf = 0; this.update(); });
  }

  private update(): void {
    if (!this.slots.length) return;
    const sc = this.state.scale;
    const top = this.scroller.scrollTop, bottom = top + this.scroller.clientHeight;
    let current = this.slots.length;
    for (let i = 0; i < this.slots.length; i++) {
      const s = this.slots[i];
      const t = this.tops[i], b = t + s.h * sc;
      if (current === this.slots.length && b > top + this.scroller.clientHeight * 0.35) current = i + 1;
      if (b >= top - MARGIN && t <= bottom + MARGIN) {
        if (!s.rendered && !s.task) void this.renderPage(i);
      } else if ((b < top - RELEASE || t > bottom + RELEASE) && (s.rendered || s.task)) {
        this.release(s);
      }
    }
    this.set({ page: current });
  }

  private async renderPage(i: number): Promise<void> {
    const pdf = this.pdf, s = this.slots[i];
    if (!pdf || !s) return;
    const token = ++s.token;
    const scale = this.state.scale;
    try {
      const page = await pdf.getPage(i + 1);
      if (token !== s.token || this.pdf !== pdf) return;
      const viewport = page.getViewport({ scale });
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const canvas = document.createElement('canvas');
      canvas.width = Math.floor(viewport.width * dpr);
      canvas.height = Math.floor(viewport.height * dpr);
      canvas.style.width = `${viewport.width}px`;
      canvas.style.height = `${viewport.height}px`;
      s.el.insertBefore(canvas, s.textDiv);
      s.canvas = canvas;
      const task = page.render({
        canvasContext: canvas.getContext('2d')!,
        canvas,
        viewport,
        transform: dpr !== 1 ? [dpr, 0, 0, dpr, 0, 0] : undefined,
      });
      s.task = task;
      await task.promise;
      if (token !== s.token) return;
      s.task = null;
      s.el.dataset.rendered = '1';
      s.rendered = true;
      const textLayer = new TextLayer({ textContentSource: page.streamTextContent(), container: s.textDiv, viewport });
      s.textLayer = textLayer;
      await textLayer.render();
    } catch (e) {
      const name = (e as { name?: string }).name;
      if (name !== 'RenderingCancelledException' && token === s.token) console.warn('Falha ao renderizar a página', i + 1, e);
      if (token === s.token) s.task = null;
    }
  }

  private release(s: Slot): void {
    s.token++;
    try { s.task?.cancel(); } catch { /* já finalizada */ }
    s.task = null;
    s.textLayer?.cancel();
    s.textLayer = null;
    if (s.canvas) { s.canvas.width = 0; s.canvas.height = 0; s.canvas.remove(); s.canvas = null; }
    s.textDiv.replaceChildren();
    s.rendered = false;
    delete s.el.dataset.rendered;
  }
}
