import { useCallback, useEffect, useRef, useState } from 'react';
import { aiSettings, api, ApiError, type AiOverview } from '../api';
import { readSse } from './sse';

export interface Reference {
  slot: 'A' | 'B';
  page: number;
  file_name: string;
  document_id: string;
}

export interface Usage {
  input_tokens?: number;
  output_tokens?: number;
  cost_usd?: number | null;
  truncated?: boolean;
  invalid_citations_removed?: number;
}

export interface ChatMsg {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  /** streaming | complete | cancelled | error */
  status: string;
  error: string | null;
  provider: string | null;
  model: string | null;
  references: Reference[];
  usage: Usage | null;
}

export interface ConversationSummary {
  id: string;
  title: string;
  created_at: string;
  updated_at: string;
  message_count: number;
}

interface RawMsg {
  id: string;
  role: string;
  content: string;
  status?: string;
  error?: string | null;
  provider?: string | null;
  model?: string | null;
  references?: Reference[] | null;
  usage?: Usage | null;
}

const toMsg = (m: RawMsg): ChatMsg => ({
  id: m.id,
  role: m.role === 'user' ? 'user' : 'assistant',
  content: m.content ?? '',
  status: m.status ?? 'complete',
  error: m.error ?? null,
  provider: m.provider ?? null,
  model: m.model ?? null,
  references: m.references ?? [],
  usage: m.usage ?? null,
});

export interface Selection {
  provider: string;
  model: string;
}

/** Estado do chat. Vive no App (não na tela), então uma resposta em andamento continua ao trocar de tela. */
export function useChat() {
  const [messages, setMessages] = useState<ChatMsg[]>([]);
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [streaming, setStreaming] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [settings, setSettings] = useState<AiOverview | null>(null);
  const [selection, setSelection] = useState<Selection | null>(null);
  const [history, setHistory] = useState<ConversationSummary[]>([]);
  const activeId = useRef<string | null>(null);
  const convRef = useRef<string | null>(null);
  convRef.current = conversationId;
  const selRef = useRef<Selection | null>(null);
  selRef.current = selection;

  const refreshHistory = useCallback(async () => {
    try { setHistory(await api<ConversationSummary[]>('/conversations')); } catch { /* histórico é secundário */ }
  }, []);

  /** (Re)lê provedores e modelos; mantém a escolha atual se ainda existir. */
  const refreshSettings = useCallback(async () => {
    try {
      const s = await aiSettings.get();
      setSettings(s);
      setSelection((cur) => {
        const p = (cur && s.providers.find((x) => x.id === cur.provider)) ?? s.providers.find((x) => x.id === s.default_provider) ?? s.providers[0];
        if (!p) return cur;
        const keepModel = cur && cur.provider === p.id && p.models.some((m) => m.id === cur.model);
        return { provider: p.id, model: keepModel ? cur!.model : p.selected_model };
      });
    } catch { /* o painel mostra o aviso ao enviar */ }
  }, []);

  useEffect(() => {
    void refreshSettings();
    void refreshHistory();
    api<{ id: string; messages: RawMsg[] }>('/conversations/current')
      .then((c) => { setConversationId(c.id); setMessages(c.messages.map(toMsg)); })
      .catch(() => undefined);
  }, [refreshSettings, refreshHistory]);

  const setProvider = useCallback((id: string) => {
    const p = settings?.providers.find((x) => x.id === id);
    if (p) setSelection({ provider: id, model: p.selected_model });
  }, [settings]);

  const setModel = useCallback((model: string) => setSelection((s) => (s ? { ...s, model } : s)), []);

  /** Envia uma pergunta. Retorna false se NADA foi gravado (o texto deve voltar ao campo). */
  const send = useCallback(async (text: string): Promise<boolean> => {
    const content = text.trim();
    if (!content || activeId.current) return false;
    setNotice(null);
    setStreaming(true);
    let started = false;
    try {
      const sel = selRef.current;
      const res = await fetch('/api/chat/stream', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ content, conversation_id: convRef.current, provider: sel?.provider, model: sel?.model }),
      });
      if (!res.ok) {
        let msg = `Erro HTTP ${res.status}`;
        try {
          const j = await res.json();
          const d = j?.detail;
          msg = typeof d === 'string' ? d : (d?.message ?? msg);
        } catch { /* sem corpo JSON */ }
        throw new ApiError(res.status, msg);
      }
      for await (const ev of readSse(res)) {
        if (ev.event === 'meta') {
          started = true;
          setConversationId(ev.data.conversation_id);
          activeId.current = ev.data.assistant_message_id;
          const sel2 = selRef.current;
          setMessages((m) => [
            ...m,
            toMsg(ev.data.user_message),
            toMsg({ id: ev.data.assistant_message_id, role: 'assistant', content: '', status: 'streaming', provider: sel2?.provider, model: sel2?.model }),
          ]);
        } else if (ev.event === 'delta') {
          setMessages((m) => m.map((x) => (x.id === activeId.current ? { ...x, content: x.content + ev.data.text } : x)));
        } else if (ev.event === 'done') {
          setMessages((m) => m.map((x) => (x.id === ev.data.message.id ? toMsg(ev.data.message) : x)));
        } else if (ev.event === 'error') {
          const am = ev.data.assistant_message;
          setMessages((m) => m.map((x) => (x.id === am.id ? toMsg(am) : x)));
        }
      }
    } catch (e) {
      if (!started) { setNotice((e as Error).message); return false; }
      setMessages((m) => m.map((x) => (x.id === activeId.current ? { ...x, status: 'error', error: 'A conexão com o backend caiu durante a resposta.' } : x)));
    } finally {
      activeId.current = null;
      setStreaming(false);
      void refreshHistory();
    }
    return started;
  }, [refreshHistory]);

  /** Para a resposta: o texto parcial é mantido (status "interrompida"). */
  const stop = useCallback(async () => {
    const id = activeId.current;
    if (id) await api(`/chat/messages/${encodeURIComponent(id)}/cancel`, { method: 'POST' }).catch(() => undefined);
  }, []);

  const newConversation = useCallback(async () => {
    if (activeId.current) return;
    try {
      const c = await api<{ id: string }>('/conversations', { method: 'POST' });
      setConversationId(c.id);
      setMessages([]);
      setNotice(null);
      void refreshHistory();
    } catch (e) { setNotice((e as Error).message); }
  }, [refreshHistory]);

  const openConversation = useCallback(async (id: string) => {
    if (activeId.current) return;
    try {
      const list = await api<RawMsg[]>(`/conversations/${encodeURIComponent(id)}/messages`);
      setConversationId(id);
      setMessages(list.map(toMsg));
      setNotice(null);
    } catch (e) { setNotice((e as Error).message); }
  }, []);

  return {
    messages, conversationId, streaming, notice, setNotice,
    settings, selection, setProvider, setModel, refreshSettings,
    history, refreshHistory,
    send, stop, newConversation, openConversation,
  };
}

export type ChatApi = ReturnType<typeof useChat>;
