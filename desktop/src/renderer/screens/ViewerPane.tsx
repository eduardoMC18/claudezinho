import { useEffect, useRef, useState, type DragEvent } from 'react';
import type { DocumentInfo, SlotId } from '../api';
import { workspace } from '../api';
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

export function ViewerPane({ slot, doc, error, onPick, onPath, onClear }: Props) {
  const view = useRef<PdfViewHandle>(null);
  const [vs, setVs] = useState<ViewerState | null>(null);
  const [pageText, setPageText] = useState('1');
  const [dragging, setDragging] = useState(false);
  const [dropError, setDropError] = useState<string | null>(null);
  const label = slot === 'A' ? 'PDF 1' : 'PDF 2';

  // o campo de página acompanha a rolagem
  useEffect(() => { if (vs) setPageText(String(vs.page)); }, [vs?.page]);
  // trocar/remover o documento zera o estado do visualizador (nunca é compartilhado)
  useEffect(() => { setVs(null); setPageText('1'); }, [doc?.id]);

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
          {doc && (
            <div className="doc-actions">
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

      {canView ? (
        <>
          {vs?.error && <div className="msg error" role="alert">Não foi possível abrir o PDF: {vs.error}</div>}
          <PdfView ref={view} url={workspace.fileUrl(doc.id)} onState={setVs} />
        </>
      ) : (
        <button className="drop" onClick={() => onPick(slot)} aria-label={`Carregar ${label}`}>
          <span>{doc?.file_state === 'missing' ? `Arquivo não encontrado:\n${doc.name}` : 'Arraste um PDF aqui'}</span>
          <small>{doc?.file_state === 'missing' ? 'Clique para escolher outro' : 'ou clique para escolher'}</small>
        </button>
      )}
    </GlassPanel>
  );
}
