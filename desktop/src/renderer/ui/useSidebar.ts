import { useCallback, useEffect, useState } from 'react';

/**
 * Estado da barra lateral (expandida/recolhida).
 *
 * Persistência: `localStorage` da própria interface (origem app://local), que o Electron guarda na pasta de dados
 * do usuário (userData) e sobrevive a reinícios e atualizações. Só é gravado quando o usuário escolhe.
 * Sem escolha salva, a barra segue a largura da janela: recolhe sozinha abaixo de AUTO_COLLAPSE_W.
 */
const KEY = 'estudotron.sidebar';
export const AUTO_COLLAPSE_W = 1280;

type Pref = 'collapsed' | 'expanded';

function readPref(): Pref | null {
  try {
    const v = localStorage.getItem(KEY);
    return v === 'collapsed' || v === 'expanded' ? v : null;
  } catch {
    return null; // armazenamento indisponível: segue a largura da janela
  }
}

function writePref(v: Pref): void {
  try { localStorage.setItem(KEY, v); } catch { /* sem persistência, mas a interface continua funcionando */ }
}

export function useSidebar() {
  const [pref, setPref] = useState<Pref | null>(readPref);
  const [narrow, setNarrow] = useState(() => window.innerWidth < AUTO_COLLAPSE_W);

  useEffect(() => {
    const onResize = () => setNarrow(window.innerWidth < AUTO_COLLAPSE_W);
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  const collapsed = pref ? pref === 'collapsed' : narrow;

  const toggle = useCallback(() => {
    const next: Pref = collapsed ? 'expanded' : 'collapsed';
    setPref(next);
    writePref(next);
  }, [collapsed]);

  return { collapsed, toggle, remembered: pref !== null };
}
