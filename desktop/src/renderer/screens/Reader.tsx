import { useCallback, useEffect, useRef, useState, type KeyboardEvent, type PointerEvent } from 'react';
import { workspace, type DocumentInfo, type SlotId } from '../api';
import type { ChatApi } from '../chat/useChat';
import { GlassButton } from '../ui/GlassButton';
import { ChatPanel, type CiteTarget } from './ChatPanel';
import { ViewerPane, type ViewerPaneHandle } from './ViewerPane';

type Slots = Record<SlotId, DocumentInfo | null>;
const EMPTY: Slots = { A: null, B: null };

/** Leitor de PDFs: dois visualizadores independentes lado a lado, divisor ajustável, chat à direita. */
export function Reader({ chat }: { chat: ChatApi }) {
  const [slots, setSlots] = useState<Slots>(EMPTY);
  const [errors, setErrors] = useState<Record<SlotId, string | null>>({ A: null, B: null });
  const [ratio, setRatio] = useState(0.5);
  const [showChat, setShowChat] = useState(true);
  const [warning, setWarning] = useState<string | null>(null);
  const [loaded, setLoaded] = useState(false);
  const pdfs = useRef<HTMLDivElement>(null);
  const paneA = useRef<ViewerPaneHandle>(null);
  const paneB = useRef<ViewerPaneHandle>(null);

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
      setWarning(null);
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

  /** Clique numa citação: leva o PDF à página; avisa se o documento foi substituído/removido desde a resposta. */
  const onCite = useCallback((t: CiteTarget) => {
    const doc = slots[t.slot];
    const name = t.slot === 'A' ? 'PDF 1' : 'PDF 2';
    if (!doc) { setWarning(`O ${name} foi removido desde esta resposta; a citação não pode ser aberta.`); return; }
    if (t.documentId && t.documentId !== doc.id) {
      setWarning(`O ${name} foi substituído desde esta resposta; a página ${t.page} pode não corresponder ao texto citado.`);
      return;
    }
    setWarning(null);
    (t.slot === 'A' ? paneA : paneB).current?.goToPage(t.page);
  }, [slots]);

  // ---- divisor ----
  const dragFrom = (e: PointerEvent<HTMLDivElement>) => {
    const box = pdfs.current!.getBoundingClientRect();
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

  const hasDocs = !!(slots.A || slots.B);

  return (
    <>
      <header className="head">
        <h1>Leitor de PDFs</h1>
        <GlassButton className="btn" variant="lens" radius={21} bevel={18} data-load="A" onClick={() => void pick('A')}>Carregar PDF 1</GlassButton>
        <GlassButton className="btn" variant="lens" radius={21} bevel={18} data-load="B" onClick={() => void pick('B')}>Carregar PDF 2</GlassButton>
        <GlassButton className="btn" variant={showChat ? 'primary' : 'lens'} radius={21} bevel={18} data-action="toggle-chat" aria-pressed={showChat}
          onClick={() => setShowChat((v) => !v)}>Chat</GlassButton>
      </header>
      <div className="reader" data-loaded={loaded ? '1' : '0'} data-chat={showChat ? '1' : '0'}>
        <div
          ref={pdfs}
          className="pdfs"
          style={{ gridTemplateColumns: `minmax(0, ${ratio}fr) 12px minmax(0, ${1 - ratio}fr)` }}
        >
          <ViewerPane ref={paneA} slot="A" doc={slots.A} error={errors.A} onPick={pick} onPath={setPath} onClear={clear} />
          <div
            className="divider"
            role="separator"
            aria-orientation="vertical"
            aria-label="Ajustar a largura dos PDFs"
            tabIndex={0}
            onPointerDown={dragFrom}
            onKeyDown={keyDivider}
          />
          <ViewerPane ref={paneB} slot="B" doc={slots.B} error={errors.B} onPick={pick} onPath={setPath} onClear={clear} />
        </div>
        {showChat && <ChatPanel chat={chat} hasDocs={hasDocs} warning={warning} onCite={onCite} />}
      </div>
    </>
  );
}
