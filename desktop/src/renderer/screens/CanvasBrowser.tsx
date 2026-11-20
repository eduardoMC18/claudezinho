import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { ApiError, canvas, type Cached, type CanvasCourse, type CanvasItem, type CanvasModule, type CanvasOpen, type CanvasStatus, type SlotId } from '../api';
import { GlassButton } from '../ui/GlassButton';
import { FileIcon, FolderIcon, LinkIcon, PageIcon, RefreshIcon } from '../ui/icons';

/** Onde o usuário está: Disciplinas → Módulos → Materiais. Guardado por painel (nunca compartilhado entre PDF 1 e PDF 2). */
interface Nav {
  course?: { id: number; name: string };
  module?: { id: string; name: string };
}

const navKey = (slot: SlotId) => `estudotron.canvasnav.${slot}`;
function loadNav(slot: SlotId): Nav {
  try {
    const raw = JSON.parse(localStorage.getItem(navKey(slot)) ?? '{}') as Nav;
    if (raw.course && typeof raw.course.id === 'number') return { course: raw.course, module: raw.module?.id ? raw.module : undefined };
  } catch { /* sem preferência salva */ }
  return {};
}
function saveNav(slot: SlotId, nav: Nav): void {
  try { localStorage.setItem(navKey(slot), JSON.stringify(nav)); } catch { /* armazenamento indisponível */ }
}

interface Load<T> { data: Cached<T> | null; error: ApiError | Error | null; loading: boolean }

/** Carrega com o cache do backend; `reload(true)` força a atualização no Canvas. */
function useLoad<T>(fetcher: ((refresh: boolean) => Promise<Cached<T>>) | null, deps: unknown[]): Load<T> & { reload(refresh?: boolean): void } {
  const [state, setState] = useState<Load<T>>({ data: null, error: null, loading: !!fetcher });
  const seq = useRef(0);
  const fetchRef = useRef(fetcher);
  fetchRef.current = fetcher; // o fetcher é recriado a cada render; quem decide quando recarregar é `deps`
  const run = useCallback((refresh: boolean) => {
    const f = fetchRef.current;
    if (!f) return;
    const mine = ++seq.current;
    setState((s) => ({ data: s.data, error: null, loading: true }));
    f(refresh)
      .then((data) => { if (mine === seq.current) setState({ data, error: null, loading: false }); })
      .catch((error: Error) => { if (mine === seq.current) setState({ data: null, error, loading: false }); });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  useEffect(() => { setState({ data: null, error: null, loading: !!fetchRef.current }); run(false); return () => { seq.current++; }; }, [run]);
  return { ...state, reload: (refresh = true) => run(refresh) };
}

function errorText(e: Error): { text: string; settings: boolean } {
  const kind = e instanceof ApiError ? e.kind : undefined;
  return { text: e.message, settings: kind === 'auth' || kind === 'not_connected' };
}

function fmtSize(n: number | null): string {
  if (!n) return '';
  return n >= 1048576 ? `${(n / 1048576).toFixed(1).replace('.', ',')} MB` : `${Math.max(1, Math.round(n / 1024))} KB`;
}

function initials(name: string): string {
  const words = name.replace(/[^\p{L}\p{N} ]/gu, ' ').split(/\s+/).filter((w) => w.length > 1 || /\d/.test(w));
  return (words.length > 1 ? words[0][0] + words[1][0] : (words[0] ?? name).slice(0, 2)).toUpperCase();
}

interface Props {
  slot: SlotId;
  status: CanvasStatus | null;
  onOpenSettings(): void;
  /** Abre o PDF no painel; rejeita com o erro para a lista mostrar a mensagem. */
  onOpen(slot: SlotId, req: CanvasOpen): Promise<void>;
  /** Presente só quando o painel já tem um PDF: fecha a biblioteca e volta a ele. */
  onClose?: () => void;
  /** Arquivo atualmente aberto neste painel (destaca na lista). */
  currentFileId?: number | null;
}

/** Biblioteca do Canvas dentro de um painel do leitor. */
export function CanvasBrowser({ slot, status, onOpenSettings, onOpen, onClose, currentFileId }: Props) {
  const [nav, setNavState] = useState<Nav>(() => loadNav(slot));
  const setNav = (n: Nav) => { setNavState(n); saveNav(slot, n); };
  const connected = status?.connected === true;

  const courses = useLoad<CanvasCourse[]>(connected && !nav.course ? (r) => canvas.courses(r) : null, [connected, nav.course?.id]);
  const modules = useLoad<CanvasModule[]>(connected && nav.course && !nav.module ? (r) => canvas.modules(nav.course!.id, r) : null, [connected, nav.course?.id, nav.module?.id]);
  const items = useLoad<CanvasItem[]>(connected && nav.course && nav.module ? (r) => canvas.items(nav.course!.id, nav.module!.id, r) : null, [connected, nav.course?.id, nav.module?.id]);
  const level = !nav.course ? 'courses' : !nav.module ? 'modules' : 'items';
  const cur = level === 'courses' ? courses : level === 'modules' ? modules : items;

  const [opening, setOpening] = useState<number | null>(null);
  const [openError, setOpenError] = useState<string | null>(null);

  const openItem = async (it: CanvasItem) => {
    if (!nav.course || !nav.module || it.file_id == null || opening != null) return;
    setOpening(it.file_id);
    setOpenError(null);
    try {
      await onOpen(slot, { course_id: nav.course.id, module_id: nav.module.id, file_id: it.file_id, course_name: nav.course.name, module_name: nav.module.name });
    } catch (e) {
      setOpenError((e as Error).message);
    } finally {
      setOpening(null);
    }
  };

  // trilha: Canvas › Disciplina › Módulo
  const crumbs: ReactNode = (
    <nav className="cv-crumbs" aria-label="Onde você está no Canvas">
      <button className={'cv-crumb' + (level === 'courses' ? ' here' : '')} data-nav="courses" onClick={() => setNav({})}>Disciplinas</button>
      {nav.course && (
        <>
          <span aria-hidden="true">›</span>
          <button className={'cv-crumb' + (level === 'modules' ? ' here' : '')} data-nav="modules" title={nav.course.name} onClick={() => setNav({ course: nav.course })}>{nav.course.name}</button>
        </>
      )}
      {nav.module && (
        <>
          <span aria-hidden="true">›</span>
          <span className="cv-crumb here" title={nav.module.name}>{nav.module.name}</span>
        </>
      )}
    </nav>
  );

  const head = (
    <div className="cv-head">
      {crumbs}
      <div className="cv-head-actions">
        {connected && (
          <button className="cv-icon" data-action="refresh" aria-label="Atualizar do Canvas" title="Atualizar do Canvas" disabled={cur.loading} onClick={() => cur.reload(true)}>
            <RefreshIcon />
          </button>
        )}
        {onClose && <GlassButton className="sm" variant="film" radius={17} bevel={12} data-action="close-library" onClick={onClose}>Voltar ao PDF</GlassButton>}
      </div>
    </div>
  );

  if (status && !connected) {
    return (
      <div className="cv" data-level="disconnected">
        <div className="cv-card cv-connect">
          <b>Conecte o Canvas da sua faculdade</b>
          <span>Depois de conectar, suas disciplinas e PDFs aparecem aqui, sem baixar nada manualmente.</span>
          <div className="cv-row-actions">
            <GlassButton className="sm" variant="primary" radius={17} bevel={14} data-action="connect-canvas" onClick={onOpenSettings}>Conectar em Configurações</GlassButton>
            {onClose && <GlassButton className="sm" variant="film" radius={17} bevel={12} onClick={onClose}>Voltar ao PDF</GlassButton>}
          </div>
        </div>
      </div>
    );
  }

  const err = cur.error ? errorText(cur.error) : null;

  return (
    <div className="cv" data-level={level}>
      {head}
      {cur.data?.stale && cur.data.warning && <div className="msg info" role="status">{cur.data.warning} Mostrando a última lista salva.</div>}
      {openError && <div className="msg error" role="alert" data-error="open">{openError}</div>}

      <div className="cv-body">
        {!status && <div className="cv-note">Carregando…</div>}
        {err && (
          <div className="cv-card cv-error" role="alert">
            <b>Não foi possível carregar</b>
            <span>{err.text}</span>
            <div className="cv-row-actions">
              <GlassButton className="sm" variant="lens" radius={17} bevel={14} data-action="retry" onClick={() => cur.reload(false)}>Tentar de novo</GlassButton>
              {err.settings && <GlassButton className="sm" variant="primary" radius={17} bevel={14} onClick={onOpenSettings}>Abrir Configurações</GlassButton>}
            </div>
          </div>
        )}
        {cur.loading && !cur.data && !err && <div className="cv-note" data-state="loading">Carregando do Canvas…</div>}

        {level === 'courses' && courses.data && (courses.data.data.length === 0
          ? <div className="cv-note">Nenhuma disciplina ativa encontrada nesta conta.</div>
          : (
            <div className="cv-grid" role="list">
              {courses.data.data.map((c) => (
                <button key={c.id} role="listitem" className="cv-course" data-course={c.id} title={c.name}
                  onClick={() => setNav({ course: { id: c.id, name: c.name } })}>
                  <span className="cv-badge" aria-hidden="true">{initials(c.short_name || c.name)}</span>
                  <span className="cv-name">{c.name}</span>
                  <small>{[c.code && c.code !== c.name ? c.code : '', c.term ?? '', c.published ? '' : 'não publicada'].filter(Boolean).join(' · ')}</small>
                </button>
              ))}
            </div>
          ))}

        {level === 'modules' && modules.data && (
          <div className="cv-list" role="list">
            {modules.data.data.length <= 1 && modules.data.data[0]?.kind === 'files' && <div className="cv-note">Este curso ainda não tem módulos.</div>}
            {modules.data.data.map((m) => (
              <button key={m.id} role="listitem" className="cv-row" data-module={m.id} disabled={m.locked}
                title={m.locked ? 'Módulo bloqueado no Canvas' : m.name}
                onClick={() => setNav({ course: nav.course, module: { id: m.id, name: m.name } })}>
                <FolderIcon />
                <span className="cv-row-main"><b>{m.name}</b>{m.locked && <small>Bloqueado no Canvas</small>}</span>
                {m.items_count != null && !m.locked && <small>{m.items_count} {m.items_count === 1 ? 'item' : 'itens'}</small>}
              </button>
            ))}
          </div>
        )}

        {level === 'items' && items.data && (() => {
          const list = items.data.data;
          const pdfs = list.filter((i) => i.kind === 'pdf' && i.available).length;
          return (
            <div className="cv-list" role="list">
              {pdfs === 0 && <div className="cv-note" data-state="no-pdf">{list.length === 0 ? 'Este módulo está vazio.' : 'Este módulo não tem PDFs disponíveis.'}</div>}
              {list.map((it) => {
                const open = it.kind === 'pdf' && it.available;
                const icon = it.kind === 'pdf' || it.kind === 'file' ? <FileIcon /> : it.kind === 'page' ? <PageIcon /> : it.kind === 'link' ? <LinkIcon /> : null;
                if (it.kind === 'header') return <div key={it.key} className="cv-sub" style={{ paddingLeft: 8 + it.indent * 14 }}>{it.title}</div>;
                return (
                  <button key={it.key} role="listitem" className={'cv-row item' + (open ? ' pdf' : ' dim') + (it.file_id != null && it.file_id === currentFileId ? ' current' : '')}
                    data-file={it.file_id ?? undefined} data-kind={it.kind} disabled={!open || opening != null}
                    style={{ paddingLeft: 12 + it.indent * 14 }}
                    title={open ? it.title : it.reason ?? it.title}
                    onClick={() => void openItem(it)}>
                    {icon}
                    <span className="cv-row-main">
                      <b>{it.title}</b>
                      <small>{(opening != null && opening === it.file_id) ? 'Abrindo…' : [it.via, open ? fmtSize(it.size) : it.reason].filter(Boolean).join(' · ')}</small>
                    </span>
                    {open && <span className="cv-open" aria-hidden="true">{(opening != null && opening === it.file_id) ? '…' : 'Abrir'}</span>}
                  </button>
                );
              })}
            </div>
          );
        })()}
      </div>
    </div>
  );
}
