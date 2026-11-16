import type { ReactNode } from 'react';
import { ApiError, type ExRef, type ExUsage } from '../api';
import { GlassButton } from '../ui/GlassButton';
import { GlassPanel } from '../ui/GlassPanel';

export interface Failure {
  kind: string;
  message: string;
}

export const toFailure = (e: unknown): Failure => ({
  kind: e instanceof ApiError ? (e.kind ?? 'unknown') : 'unknown',
  message: (e as Error).message || 'Erro desconhecido',
});

/** Falhas que valem uma nova tentativa (as demais pedem uma ação do usuário). */
export const isRetryable = (kind: string) =>
  ['rate_limit', 'overloaded', 'network', 'invalid_response', 'refused', 'unknown', 'server', 'timeout'].includes(kind);
export const needsSettings = (kind: string) => kind === 'no_key' || kind === 'auth';
export const needsDocuments = (kind: string) => ['no_documents', 'no_text', 'indexing'].includes(kind);

export function failureTitle(kind: string): string {
  switch (kind) {
    case 'no_key': return 'Chave do Gemini não configurada';
    case 'auth': return 'Chave do Gemini recusada';
    case 'rate_limit': return 'Limite do Gemini atingido';
    case 'overloaded': return 'Gemini sobrecarregado';
    case 'network': return 'Sem conexão com o Gemini';
    case 'invalid_response': return 'A IA respondeu fora do formato';
    case 'refused': return 'A resposta foi bloqueada';
    case 'no_documents': return 'Nenhum PDF carregado';
    case 'no_text': return 'PDFs sem texto';
    case 'indexing': return 'PDFs ainda sendo indexados';
    default: return 'Não foi possível concluir';
  }
}

/** Cartão sólido (texto longo fica opaco, fora do motor de vidro). */
export function Card({ title, tone = 'plain', children }: { title?: string; tone?: 'plain' | 'ok' | 'warn' | 'bad' | 'info'; children: ReactNode }) {
  return (
    <section className={'ex-card ' + tone}>
      {title && <h3>{title}</h3>}
      {children}
    </section>
  );
}

/** Painel de estado: carregando, erro ou informação. */
export function StatusPanel({ kind, title, detail, children }: { kind: 'loading' | 'error' | 'info'; title: string; detail?: string; children?: ReactNode }) {
  return (
    <GlassPanel className={'status-panel ' + kind} radius={28} bevel={18} role={kind === 'error' ? 'alert' : 'status'} data-status-panel={kind}>
      {kind === 'loading' && <div className="spinner" aria-hidden="true" />}
      <h2>{title}</h2>
      {detail && <p>{detail}</p>}
      {children && <div className="actions center">{children}</div>}
    </GlassPanel>
  );
}

export function References({ refs, onOpen }: { refs: ExRef[]; onOpen(r: ExRef): void }) {
  if (!refs.length) return null;
  const seen = new Set<string>();
  const unique = refs.filter((r) => { const k = `${r.slot}:${r.page}`; if (seen.has(k)) return false; seen.add(k); return true; });
  return (
    <div className="refs ex-refs" aria-label="Referências nos PDFs">
      <span>Onde está no material:</span>
      {unique.map((r) => (
        <GlassButton key={`${r.slot}${r.page}`} className="sm ref" variant="film" radius={15} bevel={10} data-ref={`${r.slot}:${r.page}`} onClick={() => onOpen(r)}>
          {r.file_name} · p. {r.page}
        </GlassButton>
      ))}
    </div>
  );
}

export function UsageLine({ model, usage }: { model?: string; usage?: ExUsage | null }) {
  const parts: string[] = [];
  if (model) parts.push(model);
  if (usage?.cost_usd != null) parts.push('US$ ' + (usage.cost_usd < 0.01 ? usage.cost_usd.toFixed(4) : usage.cost_usd.toFixed(2)).replace('.', ','));
  if (usage?.input_tokens != null) parts.push(`${usage.input_tokens} em / ${usage.output_tokens ?? 0} saída`);
  return parts.length ? <div className="bubble-foot ex-usage">{parts.join(' · ')}</div> : null;
}

export function EndActions({ onNext, onHome }: { onNext(): void; onHome(): void }) {
  return (
    <div className="actions ex-end">
      <GlassButton variant="primary" radius={21} bevel={18} data-action="next" onClick={onNext}>Nova questão</GlassButton>
      <GlassButton variant="lens" radius={21} bevel={18} data-action="home" onClick={onHome}>Voltar ao início</GlassButton>
    </div>
  );
}

/** Nota em anel (0–10). */
export function ScoreRing({ score, level }: { score: number; level: string }) {
  const r = 40, c = 2 * Math.PI * r;
  const frac = Math.min(Math.max(score / 10, 0), 1);
  return (
    <div className={'score-ring ' + level} role="img" aria-label={`Nota ${score.toFixed(1).replace('.', ',')} de 10`}>
      <svg viewBox="0 0 100 100" width="96" height="96" aria-hidden="true">
        <circle cx="50" cy="50" r={r} className="track" />
        <circle cx="50" cy="50" r={r} className="arc" strokeDasharray={`${c * frac} ${c}`} transform="rotate(-90 50 50)" />
      </svg>
      <div className="num"><b>{score.toFixed(1).replace('.', ',')}</b><span>/10</span></div>
    </div>
  );
}
