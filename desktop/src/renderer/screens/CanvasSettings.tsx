import { useCallback, useEffect, useState } from 'react';
import { canvas, type CanvasStatus } from '../api';
import { GlassButton } from '../ui/GlassButton';
import { GlassPanel } from '../ui/GlassPanel';

type Msg = { kind: 'ok' | 'error' | 'info'; text: string };

/** Conexão com o Canvas LMS. O token vai uma vez ao backend (cofre do sistema), o campo é limpo e ele nunca volta. */
export function CanvasSettings() {
  const [status, setStatus] = useState<CanvasStatus | null>(null);
  const [url, setUrl] = useState('');
  const [token, setToken] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<Msg | null>(null);
  const [help, setHelp] = useState(false);

  const load = useCallback(async () => {
    try {
      const s = await canvas.status();
      setStatus(s);
      if (s.base_url) setUrl(s.base_url);
    } catch (e) {
      setMsg({ kind: 'error', text: (e as Error).message });
    }
  }, []);
  useEffect(() => { void load(); }, [load]);

  const run = async (fn: () => Promise<void>) => {
    setBusy(true);
    setMsg(null);
    try { await fn(); } catch (e) { setMsg({ kind: 'error', text: (e as Error).message }); } finally { setBusy(false); }
  };

  const connect = () => run(async () => {
    const s = await canvas.connect(url.trim(), token.trim());
    setToken('');
    setStatus(s);
    setMsg({ kind: 'ok', text: `Conectado${s.user_name ? ` como ${s.user_name}` : ''}. Suas disciplinas já aparecem no Leitor de PDFs.` });
  });

  const disconnect = () => run(async () => {
    await canvas.disconnect();
    setToken('');
    await load();
    setMsg({ kind: 'info', text: 'Desconectado: o token foi removido deste computador, junto com o cache e os PDFs do Canvas abertos.' });
  });

  return (
    <GlassPanel className="prov" radius={28} bevel={18} data-section="canvas">
      <div className="prov-head">
        <h2>Canvas LMS</h2>
        {status?.connected && <span className="badge">Conectado</span>}
      </div>

      {status?.connected ? (
        <div className="keyrow">
          <div className="keystate ok" data-state="connected">
            ✓ {status.user_name ? `${status.user_name} · ` : ''}{status.base_url}
          </div>
          <label className="pref-check">
            <input type="checkbox" checked={status.auto_refresh} disabled={busy}
              onChange={(e) => void run(async () => setStatus(await canvas.setAutoRefresh(e.target.checked)))} />
            <span>Atualizar disciplinas e módulos automaticamente (com limite de requisições)</span>
          </label>
          <small className="hint">O token está guardado no Gerenciador de Credenciais do Windows. Para revogá-lo também no Canvas: Conta › Configurações › Integrações aprovadas.</small>
          <div className="actions">
            <GlassButton className="sm" variant="lens" radius={17} bevel={14} disabled={busy} data-action="canvas-disconnect" onClick={() => void disconnect()}>Desconectar</GlassButton>
          </div>
        </div>
      ) : (
        <div className="keyrow">
          <div className="keystate">{status ? 'Nenhuma conta conectada' : 'Carregando…'}</div>
          <GlassPanel className="field" radius={23} bevel={14}>
            <input type="url" autoComplete="off" spellCheck={false} placeholder="Endereço do Canvas (ex.: suafaculdade.instructure.com)"
              aria-label="Endereço do Canvas da instituição" data-field="canvas-url" value={url} onChange={(e) => setUrl(e.target.value)} />
          </GlassPanel>
          <GlassPanel className="field" radius={23} bevel={14}>
            <input type="password" autoComplete="off" spellCheck={false} placeholder="Token de acesso do Canvas"
              aria-label="Token de acesso do Canvas" data-field="canvas-token" value={token} onChange={(e) => setToken(e.target.value)} />
          </GlassPanel>
          <div className="actions">
            <GlassButton className="sm" variant="primary" radius={17} bevel={14} disabled={busy || !url.trim() || !token.trim()} data-action="canvas-connect" onClick={() => void connect()}>
              {busy ? 'Conectando…' : 'Conectar'}
            </GlassButton>
            <GlassButton className="sm" variant="film" radius={17} bevel={12} aria-expanded={help} onClick={() => setHelp((h) => !h)}>Como obter o token?</GlassButton>
          </div>
          {help && (
            <div className="cv-help" role="note">
              <b>Token pessoal (funciona em qualquer faculdade)</b>
              <ol>
                <li>Entre no Canvas pelo navegador.</li>
                <li>Conta › Configurações › role até <i>Tokens de acesso aprovados</i> › <i>+ Novo token de acesso</i>.</li>
                <li>Dê um nome (ex.: estudoTron), deixe a validade que preferir e copie o token gerado.</li>
              </ol>
              <small>Nunca informe a sua senha aqui: o programa só aceita o token. Se o Canvas da sua instituição bloquear tokens pessoais, o
                login por OAuth2 exige que a TI da faculdade cadastre uma “chave de desenvolvedor” (Developer Key) com o endereço de retorno do programa.</small>
            </div>
          )}
        </div>
      )}
      {msg && <div className={'msg ' + msg.kind} role={msg.kind === 'error' ? 'alert' : 'status'}>{msg.text}</div>}
    </GlassPanel>
  );
}
