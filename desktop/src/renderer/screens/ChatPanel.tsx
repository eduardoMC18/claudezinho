import { useEffect, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import type { ChatApi, ChatMsg, Reference } from '../chat/useChat';
import { Markdown } from '../chat/Markdown';
import { GlassButton } from '../ui/GlassButton';
import { GlassPanel } from '../ui/GlassPanel';

export interface CiteTarget {
  slot: 'A' | 'B';
  page: number;
  /** Documento citado na época da resposta (para avisar se foi substituído). */
  documentId?: string;
}

interface Props {
  chat: ChatApi;
  hasDocs: boolean;
  /** Aviso vindo da tela (ex.: documento substituído desde a resposta). */
  warning: string | null;
  onCite(t: CiteTarget): void;
}

const MAX_LINES = 5;

function money(v: number | null | undefined): string {
  if (v == null) return '';
  return 'US$ ' + (v < 0.01 ? v.toFixed(4) : v.toFixed(2)).replace('.', ',');
}

function Footer({ m, providerName }: { m: ChatMsg; providerName: string }) {
  const parts: string[] = [];
  if (m.model) parts.push(`${providerName} · ${m.model}`);
  if (m.usage?.cost_usd != null) parts.push(money(m.usage.cost_usd));
  if (m.usage?.input_tokens != null) parts.push(`${m.usage.input_tokens} em / ${m.usage.output_tokens ?? 0} saída`);
  return (
    <div className="bubble-foot">
      {parts.length > 0 && <span>{parts.join(' · ')}</span>}
      {m.status === 'cancelled' && <span className="flag warn">interrompida</span>}
      {m.usage?.truncated && <span className="flag warn">resposta cortada pelo limite do modelo</span>}
      {!!m.usage?.invalid_citations_removed && (
        <span className="flag warn" title="A IA citou uma página que não estava nos trechos enviados; a citação foi removida.">
          {m.usage.invalid_citations_removed} citação(ões) inexistente(s) removida(s)
        </span>
      )}
    </div>
  );
}

export function ChatPanel({ chat, hasDocs, warning, onCite }: Props) {
  const { messages, streaming, notice, settings, selection } = chat;
  const [text, setText] = useState('');
  const [showHistory, setShowHistory] = useState(false);
  const listRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const stick = useRef(true);

  const providerName = (id: string | null) => settings?.providers.find((p) => p.id === id)?.display_name ?? id ?? '';

  // rola até o fim enquanto a resposta chega (a menos que o usuário tenha subido)
  useLayoutEffect(() => {
    const el = listRef.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [messages]);

  // o campo cresce até 5 linhas
  useLayoutEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    const line = parseFloat(getComputedStyle(ta).lineHeight) || 20;
    ta.style.height = `${Math.min(ta.scrollHeight, line * MAX_LINES)}px`;
  }, [text]);

  const submit = async () => {
    const t = text;
    if (!t.trim() || streaming) return;
    setText('');
    stick.current = true;
    const ok = await chat.send(t);
    if (!ok) setText(t); // nada foi gravado: o texto volta ao campo
  };

  const onKey = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); void submit(); }
  };

  const cite = (m: ChatMsg) => (slot: 'A' | 'B', page: number) => {
    const ref = m.references.find((r) => r.slot === slot && r.page === page);
    onCite({ slot, page, documentId: ref?.document_id });
  };

  // referências da última resposta (chips clicáveis)
  const lastRefs = useMemo<Reference[]>(() => {
    const last = [...messages].reverse().find((m) => m.role === 'assistant' && m.references.length > 0);
    if (!last) return [];
    const seen = new Set<string>();
    return last.references.filter((r) => { const k = `${r.slot}:${r.page}`; if (seen.has(k)) return false; seen.add(k); return true; });
  }, [messages]);
  const lastRefMsg = useMemo(() => [...messages].reverse().find((m) => m.role === 'assistant' && m.references.length > 0), [messages]);

  useEffect(() => { if (!streaming) taRef.current?.focus(); }, [streaming]);
  // ao abrir o painel (ex.: voltando de Configurações) relê provedores/chaves
  const refreshSettings = chat.refreshSettings;
  useEffect(() => { void refreshSettings(); }, [refreshSettings]);

  const cur = settings?.providers.find((p) => p.id === selection?.provider);

  return (
    <GlassPanel as="aside" className="chat" radius={30} bevel={20} aria-label="Chat com a IA">
      <div className="chat-head">
        <h2>Chat com a IA</h2>
        <GlassButton className="sm" variant="film" radius={17} bevel={12} data-action="history" aria-expanded={showHistory}
          onClick={() => { setShowHistory((v) => !v); void chat.refreshHistory(); }}>Histórico</GlassButton>
        <GlassButton className="sm" variant="film" radius={17} bevel={12} data-action="new-chat" disabled={streaming}
          title="Nova conversa (a atual continua salva no histórico)" onClick={() => { setShowHistory(false); void chat.newConversation(); }}>Nova</GlassButton>
      </div>

      {settings && selection && (
        <div className="chat-pick">
          <GlassPanel className="sel" radius={17} bevel={12}>
            <select aria-label="Provedor de IA" data-sel="provider" value={selection.provider} disabled={streaming}
              onChange={(e) => chat.setProvider(e.target.value)}>
              {settings.providers.map((p) => <option key={p.id} value={p.id}>{p.display_name}{p.has_key ? '' : ' (sem chave)'}</option>)}
            </select>
          </GlassPanel>
          <GlassPanel className="sel" radius={17} bevel={12}>
            <select aria-label="Modelo" data-sel="model" value={selection.model} disabled={streaming}
              onChange={(e) => chat.setModel(e.target.value)}>
              {cur?.models.map((m) => <option key={m.id} value={m.id}>{m.label}</option>)}
            </select>
          </GlassPanel>
        </div>
      )}

      {showHistory && (
        <div className="history" role="listbox" aria-label="Conversas anteriores">
          {chat.history.length === 0 && <div className="loading">Nenhuma conversa ainda.</div>}
          {chat.history.map((c) => (
            <button key={c.id} role="option" aria-selected={c.id === chat.conversationId} data-conv={c.id}
              className={'hist-item' + (c.id === chat.conversationId ? ' current' : '')}
              onClick={() => { setShowHistory(false); void chat.openConversation(c.id); }}>
              <b>{c.title || 'Nova conversa'}</b>
              <span>{c.message_count} {c.message_count === 1 ? 'mensagem' : 'mensagens'} · {new Date(c.updated_at || c.created_at).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })}</span>
            </button>
          ))}
        </div>
      )}

      <div
        ref={listRef}
        className="msgs"
        role="log"
        aria-live="polite"
        aria-label="Mensagens"
        onScroll={(e) => { const el = e.currentTarget; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60; }}
      >
        {messages.length === 0 && (
          <div className="empty">{hasDocs ? 'Pergunte algo sobre os PDFs carregados.' : 'Carregue pelo menos um PDF para conversar sobre ele.'}</div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={'bubble ' + m.role} data-role={m.role} data-status={m.status}>
            {m.role === 'user' ? (
              <div className="user-text">{m.content}</div>
            ) : (
              <>
                {m.content
                  ? <Markdown text={m.content} onCite={cite(m)} />
                  : m.status === 'streaming' && <div className="thinking" aria-label="A IA está respondendo"><i /><i /><i /></div>}
                {m.status === 'error' && <div className="msg error" role="alert">{m.error ?? 'Erro ao gerar a resposta.'}</div>}
                {m.status !== 'streaming' && <Footer m={m} providerName={providerName(m.provider)} />}
              </>
            )}
          </div>
        ))}
      </div>

      {(notice || warning) && (
        <div className="msg error chat-notice" role="alert">
          {notice ?? warning}
          {notice && <button className="link" onClick={() => chat.setNotice(null)}>fechar</button>}
        </div>
      )}

      {lastRefs.length > 0 && (
        <div className="refs" aria-label="Referências da última resposta">
          <span>Referências:</span>
          {lastRefs.slice(0, 6).map((r) => (
            <GlassButton key={`${r.slot}${r.page}`} className="sm ref" variant="film" radius={15} bevel={10} data-ref={`${r.slot}:${r.page}`}
              onClick={() => onCite({ slot: r.slot, page: r.page, documentId: lastRefMsg?.references.find((x) => x.slot === r.slot && x.page === r.page)?.document_id })}>
              {r.file_name} · p. {r.page}
            </GlassButton>
          ))}
          {lastRefs.length > 6 && <span>+{lastRefs.length - 6}</span>}
        </div>
      )}

      <div className="composer">
        <GlassPanel className="msgfield" radius={23} bevel={14}>
          <textarea
            ref={taRef}
            rows={1}
            value={text}
            placeholder={hasDocs ? 'Pergunte sobre os PDFs… (Enter envia, Shift+Enter quebra linha)' : 'Carregue um PDF para começar'}
            aria-label="Pergunta"
            onChange={(e) => setText(e.target.value)}
            onKeyDown={onKey}
            disabled={streaming}
          />
        </GlassPanel>
        {streaming
          ? <GlassButton className="send" variant="primary" radius={23} bevel={18} data-action="stop" aria-label="Parar resposta" onClick={() => void chat.stop()}>■</GlassButton>
          : <GlassButton className="send" variant="primary" radius={23} bevel={18} data-action="send" aria-label="Enviar" disabled={!text.trim()} onClick={() => void submit()}>➤</GlassButton>}
      </div>
    </GlassPanel>
  );
}
