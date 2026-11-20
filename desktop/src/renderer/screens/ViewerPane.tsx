import { forwardRef, useCallback, useEffect, useImperativeHandle, useRef, useState, type DragEvent } from 'react';
import type { CanvasOpen, CanvasPrepare, CanvasStatus, DocumentInfo, SlotId } from '../api';
import { canvas, workspace } from '../api';
import { CanvasBrowser } from './CanvasBrowser';
import { GlassButton } from '../ui/GlassButton';
import { GlassPanel } from '../ui/GlassPanel';
import { PdfView, type PdfViewHandle } from '../viewer/PdfView';
import type { ViewerState } from '../viewer/pdfController';

interface Props {
  slot: SlotId;
  doc: DocumentInfo | null;
  error: string | null;
  onPick(slot: SlotId): void;
  onPath(slot: SlotId, path: string): void;
  onClear(slot: SlotId): void;
  canvasStatus: CanvasStatus | null;
  onOpenSettings(): void;
  /** Abre um PDF do Canvas neste painel (rejeita com o erro para a biblioteca mostrar). */
  onOpenCanvas(slot: SlotId, req: CanvasOpen): Promise<void>;
  /** O conteúdo do documento mudou no servidor (ex.: atualizado do Canvas): recarrega os dados. */
  onReload(slot: SlotId): void;
}

/** Texto do indicador de estado do documento (mesmos estados do app JavaFX). */
export function statusText(d: DocumentInfo): { text: string; level: 'ok' | 'warn' | 'error' | 'busy' } {
  const pages = `${d.pages} ${d.pages === 1 ? 'página' : 'páginas'}`;
  if (d.file_state === 'missing') return { text: `${pages} · arquivo não encontrado no disco`, level: 'error' };
  if (d.status === 'error') return { text: `erro: ${d.error ?? 'falha ao processar'}`, level: 'error' };
  if (d.status === 'processing') return { text: `${pages} · indexando ${d.pages_done}/${d.pages}`, level: 'busy' };
  if (d.status === 'no_text') return { text: `${pages} · sem texto pesquisável (PDF escaneado?)`, level: 'warn' };
  if (d.file_state === 'modified') return { text: `${pages} · o arquivo mudou desde a indexação`, level: 'warn' };
  return { text: `${pages} · pronto`, level: 'ok' };
}

function isPdf(f: File): boolean {
  return f.type === 'application/pdf' || /\.pdf$/i.test(f.name);
}

export interface ViewerPaneHandle {
  goToPage(n: number): void;
}

export const ViewerPane = forwardRef<ViewerPaneHandle, Props>(function ViewerPane({ slot, doc, error, onPick, onPath, onClear, canvasStatus, onOpenSettings, onOpenCanvas, onReload }, ref) {
  const view = useRef<PdfViewHandle>(null);
  useImperativeHandle(ref, () => ({ goToPage: (n) => view.current?.goToPage(n) }), []);
  const [vs, setVs] = useState<ViewerState | null>(null);
  const [pageText, setPageText] = useState('1');
  const [dragging, setDragging] = useState(false);
  const [dropError, setDropError] = useState<string | null>(null);
  const label = slot === 'A' ? 'PDF 1' : 'PDF 2';
  const [browsing, setBrowsing] = useState(false); // biblioteca do Canvas aberta por cima do PDF (sem fechá-lo)
  type Prep = { id: string; state: 'checking' | 'ok' | 'error'; message?: string; update?: boolean; warning?: string | null };
  const [prepRaw, setPrep] = useState<Prep>({ id: '', state: 'ok' });
  const [updating, setUpdating] = useState(false);
  const [version, setVersion] = useState(0); // muda quando o conteúdo é atualizado: remonta o visualizador
  const restored = useRef<string | null>(null);
  const isCanvas = doc?.source === 'canvas';
  // enquanto o preparo deste documento não termina, o visualizador não monta (evita dois downloads simultâneos)
  const prep: Prep = !isCanvas || prepRaw.id === doc?.id ? prepRaw : { id: doc?.id ?? '', state: 'checking' };

  // o campo de página acompanha a rolagem
  useEffect(() => { if (vs) setPageText(String(vs.page)); }, [vs?.page, vs?.nav]);
  // trocar/remover o documento zera o estado do visualizador (nunca é compartilhado)
  useEffect(() => { setVs(null); setPageText('1'); setBrowsing(false); setVersion(0); restored.current = null; }, [doc?.id]);

  // PDF do Canvas: garante o arquivo (baixa de novo se o cache temporário foi limpo) e vê se há versão nova
  const prepare = useCallback(() => {
    if (!doc || doc.source !== 'canvas') return;
    const id = doc.id;
    setPrep({ id, state: 'checking' });
    canvas.prepare(id)
      .then((r: CanvasPrepare) => setPrep({ id, state: 'ok', update: r.update_available, warning: r.warning }))
      .catch((e: Error) => setPrep({ id, state: 'error', message: e.message || 'Falha ao abrir o PDF do Canvas.' }));
  }, [doc?.id, doc?.source]);
  useEffect(() => { prepare(); }, [prepare]);

  const updateFromCanvas = async () => {
    if (!doc) return;
    setUpdating(true);
    try {
      await canvas.refreshDoc(doc.id);
      setVersion((v) => v + 1);
      setVs(null);
      restored.current = doc.id;
      setPrep({ id: doc.id, state: 'ok' });
      onReload(slot);
    } catch (e) {
      setPrep({ id: doc.id, state: 'error', message: (e as Error).message });
    } finally {
      setUpdating(false);
    }
  };

  // restaura a última página lida (uma vez por documento) e grava a posição enquanto o usuário lê
  useEffect(() => {
    if (!doc || !vs || vs.total < 1 || restored.current === doc.id) return;
    restored.current = doc.id;
    if (doc.last_page > 1 && vs.page <= 1) view.current?.goToPage(doc.last_page);
  }, [doc, vs?.total]);
  useEffect(() => {
    if (!doc || !vs || vs.total < 1 || restored.current !== doc.id) return;
    const t = window.setTimeout(() => { void canvas.progress(doc.id, vs.page).catch(() => undefined); }, 700);
    return () => window.clearTimeout(t);
  }, [doc?.id, vs?.page, vs?.total]);

  const onDrop = (e: DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const f = e.dataTransfer.files[0];
    if (!f) return;
    if (!isPdf(f)) { setDropError('Solte um arquivo PDF.'); return; }
    const path = window.estudo.pathForFile(f);
    if (!path) { setDropError('Não consegui obter o caminho do arquivo.'); return; }
    setDropError(null);
    onPath(slot, path);
  };

  const canView = doc && doc.file_state !== 'missing';
  const st = doc ? statusText(doc) : null;
  const total = vs?.total ?? 0;
  const go = () => {
    const n = parseInt(pageText, 10);
    if (Number.isFinite(n)) view.current?.goToPage(n);
  };

  return (
    <GlassPanel
      className={'pane' + (dragging ? ' dragging' : '')}
      radius={30}
      bevel={20}
      data-pane={slot}
      onDragEnter={(e) => { e.preventDefault(); setDragging(true); }}
      onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = 'copy'; setDragging(true); }}
      onDragLeave={(e) => { if (e.currentTarget === e.target) setDragging(false); }}
      onDrop={onDrop}
    >
      <div className="pane-bar">
        <div className="pane-title">
          <span className="name" title={doc?.name}>{doc ? doc.name : label}</span>
          {st
            ? <span className={'status ' + st.level} data-status={doc!.status}>{st.text}</span>
            : <span className="status">vazio</span>}
          {doc?.canvas && (
            <span className="origin" data-origin="canvas" title={`${doc.canvas.course_name} › ${doc.canvas.module_name}`}>
              Canvas · {doc.canvas.course_name}{doc.canvas.module_name ? ` › ${doc.canvas.module_name}` : ''}
            </span>
          )}
          {doc && (
            <div className="doc-actions">
              <GlassButton className="sm" variant={browsing ? 'primary' : 'lens'} radius={17} bevel={14} data-action="library" aria-pressed={browsing}
                onClick={() => setBrowsing((b) => !b)}>Canvas</GlassButton>
              <GlassButton className="sm" variant="lens" radius={17} bevel={14} data-action="replace" onClick={() => onPick(slot)}>Substituir</GlassButton>
              <GlassButton className="sm" variant="film" radius={17} bevel={12} data-action="remove" onClick={() => onClear(slot)}>Remover</GlassButton>
            </div>
          )}
        </div>
        {doc && (
          <div className="tools">
            <GlassButton className="sm sq" variant="film" radius={17} bevel={12} aria-label="Página anterior" disabled={!canView || !vs || vs.page <= 1} onClick={() => view.current?.goToPage((vs?.page ?? 1) - 1)}>‹</GlassButton>
            <span className="pager">
              <input
                aria-label="Número da página"
                value={pageText}
                inputMode="numeric"
                onChange={(e) => setPageText(e.target.value.replace(/[^\d]/g, ''))}
                onKeyDown={(e) => { if (e.key === 'Enter') go(); }}
                onBlur={() => vs && setPageText(String(vs.page))}
              />
              <span>/ {total || doc.pages}</span>
            </span>
            <GlassButton className="sm sq" variant="film" radius={17} bevel={12} aria-label="Próxima página" disabled={!canView || !vs || vs.page >= total} onClick={() => view.current?.goToPage((vs?.page ?? 1) + 1)}>›</GlassButton>
            <GlassButton className="sm sq" variant="film" radius={17} bevel={12} aria-label="Diminuir zoom" disabled={!canView} onClick={() => view.current?.zoomBy(1 / 1.2)}>−</GlassButton>
            <span className="zoom" aria-label="Zoom" data-fit={vs?.fit ? '1' : '0'}>{vs ? Math.round(vs.scale * 100) : 100}%</span>
            <GlassButton className="sm sq" variant="film" radius={17} bevel={12} aria-label="Aumentar zoom" disabled={!canView} onClick={() => view.current?.zoomBy(1.2)}>+</GlassButton>
            <GlassButton className="sm" variant="lens" radius={17} bevel={14} disabled={!canView} onClick={() => view.current?.setFit()}>Ajustar</GlassButton>
          </div>
        )}
      </div>

      {error && <div className="msg error" role="alert">{error}</div>}
      {dropError && <div className="msg error" role="alert">{dropError}</div>}

      <div className="pane-body">
        {canView ? (
          <>
            {vs?.error && <div className="msg error" role="alert">Não foi possível abrir o PDF: {vs.error}</div>}
            {isCanvas && prep.state === 'ok' && prep.update && (
              <div className="msg banner" role="status" data-banner="update">
                Há uma versão mais nova deste PDF no Canvas.
                <GlassButton className="sm" variant="lens" radius={17} bevel={14} data-action="update-canvas" disabled={updating} onClick={() => void updateFromCanvas()}>
                  {updating ? 'Atualizando…' : 'Atualizar'}
                </GlassButton>
              </div>
            )}
            {isCanvas && prep.state === 'ok' && prep.warning && <div className="msg info" role="status">{prep.warning} Usando a cópia já aberta.</div>}
            {isCanvas && prep.state === 'error' ? (
              <div className="cv-card cv-error pane-fail" role="alert" data-state="canvas-error">
                <b>Não foi possível abrir este PDF do Canvas</b>
                <span>{prep.message}</span>
                <div className="cv-row-actions">
                  <GlassButton className="sm" variant="lens" radius={17} bevel={14} data-action="retry" onClick={prepare}>Tentar de novo</GlassButton>
                  <GlassButton className="sm" variant="film" radius={17} bevel={12} onClick={() => setBrowsing(true)}>Escolher outro</GlassButton>
                </div>
              </div>
            ) : isCanvas && prep.state === 'checking' ? (
              <div className="cv-note" data-state="preparing">Abrindo do Canvas…</div>
            ) : (
              <PdfView key={`${doc.id}:${version}`} ref={view} url={workspace.fileUrl(doc.id) + (version ? `?v=${version}` : '')} onState={setVs} />
            )}
            {browsing && (
              <div className="pane-browser" data-overlay="library">
                <CanvasBrowser slot={slot} status={canvasStatus} onOpenSettings={onOpenSettings} currentFileId={doc.canvas?.file_id ?? null}
                  onClose={() => setBrowsing(false)} onOpen={async (s, req) => { await onOpenCanvas(s, req); setBrowsing(false); }} />
              </div>
            )}
          </>
        ) : (
          <div className="pane-empty" data-empty={slot}>
            <button className="drop" onClick={() => onPick(slot)} aria-label={`Carregar ${label}`}>
              <span>{doc?.file_state === 'missing' ? `Arquivo não encontrado:\n${doc.name}` : 'Abrir PDF local'}</span>
              <small>{doc?.file_state === 'missing' ? 'Clique para escolher outro' : 'arraste um arquivo ou clique para escolher'}</small>
            </button>
            <CanvasBrowser slot={slot} status={canvasStatus} onOpenSettings={onOpenSettings} onOpen={onOpenCanvas} />
          </div>
        )}
      </div>
    </GlassPanel>
  );
});
