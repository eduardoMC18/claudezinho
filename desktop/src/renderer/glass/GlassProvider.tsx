import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { GlassEngine } from './engine';
import {
  PreferencesContext, applyAttributes, clampThickness, clearSavedPrefs, detectQuality, effectiveQuality, loadPrefs, savePrefs,
  systemDefaults, type GlassPrefs, type PreferencesApi,
} from './preferences';

interface GlassContextValue {
  /** null quando não há WebGL2 ou o contexto foi perdido (a interface usa o material de reserva em CSS). */
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
 * Provedor único do material (no espírito do GlassProvider do GlassyUI): guarda as preferências, escreve os
 * atributos `data-lg-*` no <html>, cria o canvas WebGL2 e o motor, e o configura. Todo o resto só obedece.
 *
 * Material de reserva (<html class="no-gl">, opaco em CSS): sem WebGL2, contexto perdido, ou "reduzir transparência".
 */
export function GlassProvider({ children }: { children: ReactNode }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [prefs, setPrefsState] = useState<GlassPrefs>(loadPrefs);
  const [state, setState] = useState<GlassContextValue>({ engine: null, fallback: false });
  const [lost, setLost] = useState(false); // WebGL indisponível ou contexto perdido
  const engineRef = useRef<GlassEngine | null>(null);

  const setPrefs = useCallback((p: Partial<GlassPrefs>) => {
    setPrefsState((cur) => {
      const next: GlassPrefs = { ...cur, ...p, thickness: clampThickness(p.thickness ?? cur.thickness) };
      savePrefs(next);
      return next;
    });
  }, []);

  const reset = useCallback(() => {
    clearSavedPrefs();
    setPrefsState(systemDefaults());
  }, []);

  // atributos data-lg-* e configuração do motor sempre que as preferências mudam
  const fallbackWanted = prefs.reduceTransparency || lost;
  useEffect(() => {
    applyAttributes(prefs);
    document.documentElement.classList.toggle('no-gl', fallbackWanted);
    engineRef.current?.configure({
      thickness: prefs.thickness,
      tinted: prefs.mode === 'tinted',
      quality: effectiveQuality(prefs),
      reduceMotion: prefs.reduceMotion,
      paused: fallbackWanted,
    });
    setState((s) => (s.fallback === fallbackWanted ? s : { ...s, fallback: fallbackWanted }));
    window.__glass = { engine: engineRef.current, fallback: fallbackWanted };
  }, [prefs, fallbackWanted]);

  // cria o motor uma única vez (o contexto WebGL vive enquanto o app vive)
  useEffect(() => {
    const q = new URLSearchParams(location.search);
    const engine = new GlassEngine(
      canvasRef.current!,
      { onFallbackChange: (fb) => setLost(fb) },
      { alwaysAnimate: q.has('alwaysAnimate'), still: q.has('still') },
    );
    try {
      engine.init();
      engine.attach();
      engineRef.current = engine;
      const p = loadPrefs();
      engine.configure({
        thickness: p.thickness, tinted: p.mode === 'tinted', quality: effectiveQuality(p),
        reduceMotion: p.reduceMotion, paused: p.reduceTransparency,
      });
      setState({ engine, fallback: p.reduceTransparency });
      window.__glass = { engine, fallback: p.reduceTransparency };
    } catch (err) {
      console.warn('Vidro: material de reserva ativado.', (err as Error).message);
      setLost(true);
    }
    return () => { engine.destroy(); engineRef.current = null; };
  }, []);

  const api = useMemo<PreferencesApi>(() => ({
    prefs,
    quality: effectiveQuality(prefs),
    detected: detectQuality(prefs),
    setPrefs,
    reset,
  }), [prefs, setPrefs, reset]);

  return (
    <PreferencesContext.Provider value={api}>
      <GlassContext.Provider value={state}>
        <canvas ref={canvasRef} id="glass-canvas" aria-hidden="true" />
        {children}
      </GlassContext.Provider>
    </PreferencesContext.Provider>
  );
}
