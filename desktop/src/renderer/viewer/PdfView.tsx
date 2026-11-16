import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import { PdfController, type ViewerState } from './pdfController';

export interface PdfViewHandle {
  goToPage(n: number): void;
  zoomBy(factor: number): void;
  setFit(): void;
}

interface Props {
  /** URL do arquivo (via proxy: /api/documents/<id>/file). */
  url: string;
  onState(s: ViewerState): void;
}

/** Área rolável com as páginas do PDF. É Solid: fundo opaco, sem refração. */
export const PdfView = forwardRef<PdfViewHandle, Props>(function PdfView({ url, onState }, ref) {
  const scrollerRef = useRef<HTMLDivElement>(null);
  const hostRef = useRef<HTMLDivElement>(null);
  const ctl = useRef<PdfController | null>(null);
  const cb = useRef(onState);
  cb.current = onState;

  useEffect(() => {
    const c = new PdfController(scrollerRef.current!, hostRef.current!, (s) => cb.current(s));
    ctl.current = c;
    return () => { c.destroy(); ctl.current = null; };
  }, []);

  useEffect(() => { void ctl.current?.load(url); }, [url]);

  useImperativeHandle(ref, () => ({
    goToPage: (n) => ctl.current?.goToPage(n),
    zoomBy: (f) => ctl.current?.zoomBy(f),
    setFit: () => ctl.current?.setFit(),
  }), []);

  return (
    <div ref={scrollerRef} className="pdf-scroller" tabIndex={0} aria-label="Páginas do PDF">
      <div ref={hostRef} className="pdf-host" />
    </div>
  );
});
