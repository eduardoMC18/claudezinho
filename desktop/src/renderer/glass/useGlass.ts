import { useEffect, useRef, type RefObject } from 'react';
import type { GlassHandle, GlassOpts } from './engine';
import { useGlassContext } from './GlassProvider';

/**
 * Registra o elemento no motor de vidro. O elemento fica transparente; a GPU desenha o material atrás dele
 * usando o retângulo do próprio elemento (lido a cada quadro, então segue transformações e rolagem).
 */
export function useGlass(ref: RefObject<HTMLElement | null>, opts: GlassOpts): RefObject<GlassHandle | null> {
  const { engine } = useGlassContext();
  const handle = useRef<GlassHandle | null>(null);
  const latest = useRef(opts);
  latest.current = opts;

  useEffect(() => {
    const el = ref.current;
    if (!el || !engine) return;
    const h = engine.register(el, latest.current);
    handle.current = h;
    return () => {
      h.dispose();
      handle.current = null;
    };
  }, [engine, ref]);

  useEffect(() => {
    handle.current?.update(opts);
  }, [opts.kind, opts.radius, opts.bevel, opts.pressable]);

  return handle;
}
