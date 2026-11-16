import { useState } from 'react';
import type { ExRef, Exercise, ExResult } from '../api';
import { Markdown } from '../chat/Markdown';
import { GlassButton } from '../ui/GlassButton';
import { GlassPanel } from '../ui/GlassPanel';
import { Card, EndActions, References, ScoreRing, StatusPanel, UsageLine, type Failure } from './parts';

interface Props {
  exercise: Exercise;
  result: ExResult | null;
  submitting: boolean;
  failure: Failure | null;
  onSubmit(text: string): void;
  onNext(): void;
  onHome(): void;
  onOpenRef(r: ExRef): void;
}

const MIN = 3; // mesmo limite do backend (MIN_ANSWER_CHARS)
const MAX = 6000; // idem (MAX_ANSWER_CHARS)

const VERDICT: Record<string, string> = {
  correct: 'Resposta correta',
  partial: 'Resposta parcialmente correta',
  incorrect: 'A resposta precisa melhorar',
};

function Points({ items, empty, ok }: { items: string[]; empty: string; ok: boolean }) {
  if (!items.length) return <p className="muted-info">{empty}</p>;
  return (
    <ul className="points">
      {items.map((p, i) => <li key={i}><span aria-hidden="true">{ok ? '✓' : '→'}</span><span>{p}</span></li>)}
    </ul>
  );
}

/** Correção estruturada (não é texto de chat): nota em anel, acertos, faltas, conceitos, resposta ideal, critérios, referências. */
function GradeResult({ result, exercise, onNext, onHome, onOpenRef }: { result: ExResult; exercise: Exercise; onNext(): void; onHome(): void; onOpenRef(r: ExRef): void }) {
  const level = result.verdict ?? 'partial';
  return (
    <div className="grade" data-verdict={level}>
      <div className={'verdict-banner ' + level}>
        <ScoreRing score={result.score ?? 0} level={level} />
        <div>
          <div className="verdict-title">{VERDICT[level] ?? VERDICT.partial}</div>
          <p className="verdict-detail">{result.summary}</p>
        </div>
      </div>
      <div className="two-cols">
        <Card title="O que você acertou" tone="ok"><Points ok items={result.strengths ?? []} empty="Nenhum ponto da resposta se destacou desta vez." /></Card>
        <Card title="O que faltou" tone="warn"><Points ok={false} items={result.missing ?? []} empty="Nada importante ficou de fora." /></Card>
      </div>
      {!!result.corrections?.length && (
        <Card title="Conceitos para corrigir" tone="bad">
          {result.corrections.map((c, i) => (
            <div key={i} className="fix-item"><b>{c.concept}</b><Markdown text={c.explanation} /></div>
          ))}
        </Card>
      )}
      <Card title="Como seria uma resposta mais completa" tone="info"><Markdown text={result.ideal_answer ?? ''} /></Card>
      {!!result.criteria?.length && (
        <Card title="Critérios considerados">
          <div className="chips">{result.criteria.map((c, i) => <span key={i} className="criterion-chip">{c}</span>)}</div>
        </Card>
      )}
      <References refs={result.references} onOpen={onOpenRef} />
      <UsageLine model={exercise.model} usage={result.usage ?? exercise.usage} />
      <EndActions onNext={onNext} onHome={onHome} />
    </div>
  );
}

/** Questão discursiva: enunciado, caixa de texto ampla e envio. A correção é feita pela IA. */
export function SubjectiveQuestion({ exercise, result, submitting, failure, onSubmit, onNext, onHome, onOpenRef }: Props) {
  const [text, setText] = useState('');
  const short = text.trim().length < MIN;

  return (
    <div className="ex-wrap" data-exercise-id={exercise.id} data-kind="subjective">
      <Card>
        <div className="ex-kind">Questão discursiva{exercise.difficulty ? ` · ${exercise.difficulty}` : ''}</div>
        <div className="ex-statement"><Markdown text={exercise.statement} /></div>
      </Card>

      {result ? (
        <GradeResult result={result} exercise={exercise} onNext={onNext} onHome={onHome} onOpenRef={onOpenRef} />
      ) : submitting ? (
        <StatusPanel kind="loading" title="Corrigindo sua resposta…" detail="A IA compara o que você escreveu com os trechos dos PDFs." />
      ) : (
        <>
          <GlassPanel className="answer-field" radius={24} bevel={16}>
            <textarea
              aria-label="Sua resposta"
              value={text}
              rows={8}
              maxLength={MAX}
              placeholder="Escreva sua resposta com as suas próprias palavras…"
              onChange={(e) => setText(e.target.value)}
            />
          </GlassPanel>
          <div className="submit-row">
            <GlassButton variant="primary" radius={21} bevel={18} data-action="submit" disabled={short} onClick={() => onSubmit(text)}>Enviar resposta</GlassButton>
            <span className="hint">{short ? 'Escreva sua resposta para enviar.' : `${text.trim().length} caracteres`}</span>
            {failure && <div className="msg error" role="alert">{failure.message}</div>}
          </div>
        </>
      )}
    </div>
  );
}
