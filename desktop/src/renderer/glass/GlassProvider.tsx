import { createContext, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { GlassEngine } from './engine';

interface GlassContextValue {
  /** null enquanto não iniciou ou quando há material de reserva (sem WebGL2 / transparência reduzida). */
  engine: GlassEngine | null;
  fallback: boolean;
}

const GlassContext = createContext<GlassContextValue>({ engine: null, fallback: true });
export const useGlassContext = (): GlassContextValue => useContext(GlassContext);

declare global {
  interface Window {
    __glass?: { engine: GlassEngine | null; fallback: boolean };
  }
}

/**
 * Cria o canvas WebGL2 (atrás da interface) e o motor. Em caso de falha, de contexto perdido ou de
 * `prefers-reduced-transparency`, marca <html class="no-gl"> e a interface usa o material de reserva em CSS.
 */
export function GlassProvider({ children }: { children: ReactNode }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [state, setState] = useState<GlassContextValue>({ engine: null, fallback: false });

  useEffect(() => {
    const root = document.documentElement;
    const setFallback = (fb: boolean) => {
      root.classList.toggle('no-gl', fb);
      setState((s) => ({ engine: fb ? null : s.engine, fallback: fb }));
      window.__glass = { engine: window.__glass?.engine ?? null, fallback: fb };
    };

    const q = new URLSearchParams(location.search);
    if (matchMedia('(prefers-reduced-transparency: reduce)').matches || matchMedia('(forced-colors: active)').matches) {
      setFallback(true);
      return;
    }
    const canvas = canvasRef.current!;
    const engine = new GlassEngine(
      canvas,
      { onFallbackChange: (fb) => { root.classList.toggle('no-gl', fb); setState({ engine: fb ? null : engine, fallback: fb }); } },
      { alwaysAnimate: q.has('alwaysAnimate'), still: q.has('still') },
    );
    try {
      engine.init();
      engine.attach();
      root.classList.remove('no-gl');
      window.__glass = { engine, fallback: false };
      setState({ engine, fallback: false });
    } catch (err) {
      console.warn('Vidro: material de reserva ativado.', (err as Error).message);
      setFallback(true);
    }
    return () => engine.destroy();
  }, []);

  return (
    <GlassContext.Provider value={state}>
      <canvas ref={canvasRef} id="glass-canvas" aria-hidden="true" />
      {children}
    </GlassContext.Provider>
  );
}
