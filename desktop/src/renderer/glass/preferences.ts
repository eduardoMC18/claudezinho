import { createContext, useContext } from 'react';

/**
 * Preferências do material de vidro (inspiradas no GlassProvider do GlassyUI e nos ajustes do GlassCN).
 * Um único lugar decide o material; o motor WebGL, o CSS de reserva e as telas só obedecem.
 *
 *  - mode:               'clear' (transparente) ou 'tinted' (película e lentes com mais véu branco)
 *  - quality:            null = automática (detectada pelo aparelho); a escolha do usuário sempre vence
 *  - reduceMotion:       fundo parado e sem molas com overshoot
 *  - reduceTransparency: material opaco (sem WebGL, sem refração)
 *  - thickness:          espessura do vidro como múltiplo do normal, limitada a 0,25–2
 */
export type GlassMode = 'clear' | 'tinted';
export type GlassQuality = 'low' | 'medium' | 'high';

export interface GlassPrefs {
  mode: GlassMode;
  quality: GlassQuality | null;
  reduceMotion: boolean;
  reduceTransparency: boolean;
  thickness: number;
}

export const THICKNESS_MIN = 0.25;
export const THICKNESS_MAX = 2;
const KEY = 'estudotron.glass';

export const clampThickness = (v: number): number =>
  Number.isFinite(v) ? Math.min(THICKNESS_MAX, Math.max(THICKNESS_MIN, Math.round(v * 100) / 100)) : 1;

/** Padrões: seguem as configurações de acessibilidade do sistema até o usuário escolher. */
export function systemDefaults(): GlassPrefs {
  const mq = (q: string) => { try { return matchMedia(q).matches; } catch { return false; } };
  return {
    mode: 'clear',
    quality: null,
    reduceMotion: mq('(prefers-reduced-motion: reduce)'),
    reduceTransparency: mq('(prefers-reduced-transparency: reduce)') || mq('(forced-colors: active)'),
    thickness: 1,
  };
}

/**
 * Qualidade detectada: baixa com transparência reduzida ou memória de 4 GB ou menos; média em tela de toque ou
 * com movimento reduzido; alta nos demais casos.
 */
export function detectQuality(p: Pick<GlassPrefs, 'reduceMotion' | 'reduceTransparency'>): GlassQuality {
  const mem = (navigator as Navigator & { deviceMemory?: number }).deviceMemory;
  if (p.reduceTransparency || (typeof mem === 'number' && mem <= 4)) return 'low';
  let coarse = false;
  try { coarse = matchMedia('(pointer: coarse)').matches; } catch { /* sem matchMedia */ }
  if (coarse || p.reduceMotion) return 'medium';
  return 'high';
}

export const effectiveQuality = (p: GlassPrefs): GlassQuality => p.quality ?? detectQuality(p);

export function loadPrefs(): GlassPrefs {
  const base = systemDefaults();
  try {
    const raw = localStorage.getItem(KEY);
    if (!raw) return base;
    const s = JSON.parse(raw) as Partial<GlassPrefs>;
    return {
      mode: s.mode === 'tinted' ? 'tinted' : s.mode === 'clear' ? 'clear' : base.mode,
      quality: s.quality === 'low' || s.quality === 'medium' || s.quality === 'high' ? s.quality : s.quality === null ? null : base.quality,
      reduceMotion: typeof s.reduceMotion === 'boolean' ? s.reduceMotion : base.reduceMotion,
      reduceTransparency: typeof s.reduceTransparency === 'boolean' ? s.reduceTransparency : base.reduceTransparency,
      thickness: typeof s.thickness === 'number' ? clampThickness(s.thickness) : base.thickness,
    };
  } catch {
    return base; // armazenamento indisponível ou corrompido: usa os padrões
  }
}

export function savePrefs(p: GlassPrefs): void {
  try { localStorage.setItem(KEY, JSON.stringify(p)); } catch { /* sem persistência; a interface segue funcionando */ }
}

export function clearSavedPrefs(): void {
  try { localStorage.removeItem(KEY); } catch { /* idem */ }
}

/** Escreve os atributos `data-lg-*` no <html> (ganchos de CSS e de teste). */
export function applyAttributes(p: GlassPrefs): void {
  const r = document.documentElement;
  r.dataset.lgMode = p.mode;
  r.dataset.lgQuality = effectiveQuality(p);
  r.dataset.lgMotion = p.reduceMotion ? 'reduced' : 'full';
  r.dataset.lgTransparency = p.reduceTransparency ? 'reduced' : 'full';
  r.dataset.lgThickness = String(p.thickness);
}

export interface PreferencesApi {
  prefs: GlassPrefs;
  /** Qualidade em uso (a escolhida ou a detectada). */
  quality: GlassQuality;
  /** Qualidade detectada pelo aparelho, independente da escolha. */
  detected: GlassQuality;
  setPrefs(p: Partial<GlassPrefs>): void;
  reset(): void;
}

const fallback: PreferencesApi = {
  prefs: systemDefaults(),
  quality: 'high',
  detected: 'high',
  setPrefs: () => undefined,
  reset: () => undefined,
};

export const PreferencesContext = createContext<PreferencesApi>(fallback);
export const usePreferences = (): PreferencesApi => useContext(PreferencesContext);
