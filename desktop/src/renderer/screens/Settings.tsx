import { useCallback, useEffect, useState } from 'react';
import { aiSettings, type AiOverview, type ModelInfo, type ProviderInfo } from '../api';
import { GlassButton } from '../ui/GlassButton';
import { GlassPanel } from '../ui/GlassPanel';
import { CanvasSettings } from './CanvasSettings';
import { GlassAppearance } from './GlassAppearance';

type Msg = { kind: 'ok' | 'error' | 'info'; text: string };

function price(m: ModelInfo): string {
  if (m.price_input_per_mtok == null || m.price_output_per_mtok == null) return '';
  return `US$ ${m.price_input_per_mtok} / ${m.price_output_per_mtok} por milhão de tokens (entrada / saída)`;
}

function ProviderCard({ p, isDefault, onChanged }: { p: ProviderInfo; isDefault: boolean; onChanged: (o?: AiOverview) => void }) {
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg | null>(null);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setMsg(null);
    try { await fn(); } catch (e) { setMsg({ kind: 'error', text: (e as Error).message }); } finally { setBusy(false); }
  };

  return (
    <GlassPanel className="prov" radius={28} bevel={18} data-provider={p.id}>
      <div className="prov-head">
        <h2>{p.display_name}</h2>
        {isDefault
          ? <span className="badge">Provedor padrão</span>
          : <GlassButton className="sm" variant="lens" radius={17} bevel={14} disabled={busy} onClick={() => run(async () => onChanged(await aiSettings.setDefault(p.id)))}>Usar como padrão</GlassButton>}
      </div>

      <div className="keyrow">
        <div className={'keystate' + (p.has_key ? ' ok' : '')}>
          {p.has_key ? '✓ Chave salva no Gerenciador de Credenciais do Windows' : 'Nenhuma chave salva'}
        </div>
        <GlassPanel className="field" radius={23} bevel={14}>
          <input
            type="password"
            autoComplete="off"
            spellCheck={false}
            placeholder={p.key_hint || 'Cole a chave da API'}
            aria-label={`Chave da API de ${p.display_name}`}
            value={key}
            onChange={(e) => setKey(e.target.value)}
          />
        </GlassPanel>
        <div className="actions">
          <GlassButton className="sm" variant="primary" radius={17} bevel={14} disabled={busy || !key.trim()}
            onClick={() => run(async () => { await aiSettings.saveKey(p.id, key.trim()); setKey(''); setMsg({ kind: 'ok', text: 'Chave salva.' }); onChanged(); })}>
            Salvar chave
          </GlassButton>
          <GlassButton className="sm" variant="lens" radius={17} bevel={14} disabled={busy || (!key.trim() && !p.has_key)}
            onClick={() => run(async () => {
              const r = await aiSettings.test(p.id, p.selected_model, key.trim() || undefined);
              setMsg({ kind: r.ok ? 'ok' : 'error', text: r.ok ? `Conexão OK${r.latency_ms != null ? ` (${r.latency_ms} ms)` : ''}.` : r.message });
            })}>
            Testar conexão
          </GlassButton>
          {p.has_key && (
            <GlassButton className="sm" variant="lens" radius={17} bevel={14} disabled={busy}
              onClick={() => run(async () => { await aiSettings.removeKey(p.id); setMsg({ kind: 'info', text: 'Chave removida.' }); onChanged(); })}>
              Remover chave
            </GlassButton>
          )}
        </div>
        {msg && <div className={'msg ' + msg.kind} role="status">{msg.text}</div>}
      </div>

      <div className="models" role="radiogroup" aria-label={`Modelo de ${p.display_name}`}>
        <h3>Modelo</h3>
        {p.models.map((m) => {
          const selected = m.id === p.selected_model;
          return (
            <GlassButton
              key={m.id}
              className="model"
              variant={selected ? 'lens' : 'film'}
              radius={20}
              bevel={14}
              role="radio"
              aria-checked={selected}
              data-model={m.id}
              disabled={busy}
              onClick={() => run(async () => onChanged(await aiSettings.setModel(p.id, m.id)))}
            >
              <b>{m.label}</b>
              <span>{m.description}</span>
              <span className="price">{price(m)}</span>
            </GlassButton>
          );
        })}
      </div>
    </GlassPanel>
  );
}

export function Settings() {
  const [data, setData] = useState<AiOverview | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (o?: AiOverview) => {
    try { setData(o ?? await aiSettings.get()); setError(null); } catch (e) { setError((e as Error).message); }
  }, []);
  useEffect(() => { void load(); }, [load]);

  return (
    <>
      <header className="head"><h1>Configurações</h1></header>
      <div className="screen settings">
        <CanvasSettings />
        <GlassAppearance />
        {error && <div className="msg error" role="alert">Não foi possível carregar as configurações: {error}</div>}
        {!data && !error && <div className="loading">Carregando…</div>}
        {data?.providers.map((p) => (
          <ProviderCard key={p.id} p={p} isDefault={data.default_provider === p.id} onChanged={load} />
        ))}
      </div>
    </>
  );
}
