import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { workspace, type DocumentInfo, type SlotId } from '../api';
import { GlassButton } from '../ui/GlassButton';
import { ViewerPane } from './ViewerPane';

type Slots = Record<SlotId, DocumentInfo | null>;
const EMPTY: Slots = { A: null, B: null };

/** Leitor de PDFs: dois visualizadores independentes lado a lado, divisor ajustável, sessão restaurada do backend. */
export function Reader() {
  const [slots, setSlots] = useState<Slots>(EMPTY);
  const [errors, setErrors] = useState<Record<SlotId, string | null>>({ A: null, B: null });
  const [ratio, setRatio] = useState(0.5);
  const [loaded, setLoaded] = useState(false);
  const wrap = useRef<HTMLDivElement>(null);

  const setErr = (slot: SlotId, msg: string | null) => setErrors((e) => ({ ...e, [slot]: msg }));

  // restaura a sessão (os PDFs que estavam carregados)
  useEffect(() => {
    let alive = true;
    workspace.get()
      .then((w) => { if (alive) { setSlots({ A: w.slots.A ?? null, B: w.slots.B ?? null }); setLoaded(true); } })
      .catch((e: Error) => { if (alive) { setErr('A', `Não foi possível carregar a sessão: ${e.message}`); setLoaded(true); } });
    return () => { alive = false; };
  }, []);

  // enquanto há documento sendo indexado, atualiza o progresso
  const processing = (['A', 'B'] as SlotId[]).filter((s) => slots[s]?.status === 'processing').map((s) => slots[s]!.id).join(',');
  useEffect(() => {
    if (!processing) return;
    const t = window.setInterval(async () => {
      for (const id of processing.split(',')) {
        try {
          const d = await workspace.document(id);
          setSlots((s) => (s[d.slot]?.id === d.id ? { ...s, [d.slot]: d } : s));
        } catch { /* o documento pode ter sido substituído */ }
      }
    }, 700);
    return () => window.clearInterval(t);
  }, [processing]);

  const setPath = useCallback(async (slot: SlotId, path: string) => {
    setErr(slot, null);
    try {
      const doc = await workspace.setSlot(slot, path);
      setSlots((s) => ({ ...s, [slot]: doc }));
    } catch (e) {
      setErr(slot, (e as Error).message);
    }
  }, []);

  const pick = useCallback(async (slot: SlotId) => {
    const path = await window.estudo.pickPdf();
    if (path) await setPath(slot, path);
  }, [setPath]);

  const clear = useCallback(async (slot: SlotId) => {
    setErr(slot, null);
    try {
      await workspace.clearSlot(slot);
      setSlots((s) => ({ ...s, [slot]: null }));
    } catch (e) {
      setErr(slot, (e as Error).message);
    }
  }, []);

  // gancho de teste: o autoteste carrega arquivos pelo mesmo caminho do seletor/arrastar
  useEffect(() => {
    (window as unknown as { __reader?: unknown }).__reader = { setPath, clear };
    return () => { delete (window as unknown as { __reader?: unknown }).__reader; };
  }, [setPath, clear]);

  // ---- divisor ----
  const dragFrom = (e: PointerEvent<HTMLDivElement>) => {
    const box = wrap.current!.getBoundingClientRect();
    const move = (ev: globalThis.PointerEvent) => setRatio(Math.min(0.8, Math.max(0.2, (ev.clientX - box.left) / box.width)));
    const up = () => { window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up); };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    e.preventDefault();
  };
  const keyDivider = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'ArrowLeft') setRatio((r) => Math.max(0.2, r - 0.02));
    if (e.key === 'ArrowRight') setRatio((r) => Math.min(0.8, r + 0.02));
  };

  return (
    <>
      <header className="head">
        <h1>Leitor de PDFs</h1>
        <GlassButton className="btn" variant="lens" radius={21} bevel={18} data-load="A" onClick={() => void pick('A')}>Carregar PDF 1</GlassButton>
        <GlassButton className="btn" variant="lens" radius={21} bevel={18} data-load="B" onClick={() => void pick('B')}>Carregar PDF 2</GlassButton>
      </header>
      <div
        ref={wrap}
        className="reader"
        data-loaded={loaded ? '1' : '0'}
        style={{ gridTemplateColumns: `minmax(0, ${ratio}fr) 12px minmax(0, ${1 - ratio}fr)` }}
      >
        <ViewerPane slot="A" doc={slots.A} error={errors.A} onPick={pick} onPath={setPath} onClear={clear} />
        <div
          className="divider"
          role="separator"
          aria-orientation="vertical"
          aria-label="Ajustar a largura dos PDFs"
          tabIndex={0}
          onPointerDown={dragFrom}
          onKeyDown={keyDivider}
        />
        <ViewerPane slot="B" doc={slots.B} error={errors.B} onPick={pick} onPath={setPath} onClear={clear} />
      </div>
    </>
  );
}
