import { useState } from 'react';
import type { ExRef, Exercise, ExResult } from '../api';
import { Markdown } from '../chat/Markdown';
import { GlassButton } from '../ui/GlassButton';
import { Card, EndActions, References, UsageLine, type Failure } from './parts';

interface Props {
  exercise: Exercise;
  result: ExResult | null;
  submitting: boolean;
  failure: Failure | null;
  onSubmit(option: string): void;
  onNext(): void;
  onHome(): void;
  onOpenRef(r: ExRef): void;
}

/** Questão de múltipla escolha. O gabarito só chega do backend depois do envio. */
export function ObjectiveQuestion({ exercise, result, submitting, failure, onSubmit, onNext, onHome, onOpenRef }: Props) {
  const [chosen, setChosen] = useState<string | null>(null);
  const done = !!result;
  const letter = (id: string) => id.toUpperCase();

  return (
    <div className="ex-wrap" data-exercise-id={exercise.id} data-kind="objective">
      <Card>
        <div className="ex-kind">Questão objetiva{exercise.difficulty ? ` · ${exercise.difficulty}` : ''}</div>
        <div className="ex-statement"><Markdown text={exercise.statement} /></div>
      </Card>

      {!done ? (
        <div className="options" role="radiogroup" aria-label="Alternativas">
          {exercise.options.map((o) => {
            const sel = chosen === o.id;
            return (
              <GlassButton key={o.id} className="btn option" variant={sel ? 'lens' : 'film'} radius={22} bevel={16}
                role="radio" aria-checked={sel} data-option={o.id} disabled={submitting} onClick={() => setChosen(o.id)}>
                <b className="letter">{letter(o.id)}</b><span>{o.text}</span>
              </GlassButton>
            );
          })}
        </div>
      ) : (
        <>
          <Card tone={result.correct ? 'ok' : 'bad'}>
            <div className="verdict-title" data-verdict={result.correct ? 'correct' : 'incorrect'}>{result.correct ? 'Resposta correta!' : 'Resposta incorreta'}</div>
            <p className="verdict-detail">
              {result.correct
                ? `Você marcou a alternativa ${letter(result.correct_option ?? '')}.`
                : `Você marcou ${letter(result.selected ?? '')}; a correta é ${letter(result.correct_option ?? '')}.`}
            </p>
          </Card>
          <div className="options results">
            {(result.options ?? []).map((o) => {
              const picked = o.id === result.selected;
              const tone = o.is_correct ? 'ok' : picked ? 'bad' : 'plain';
              return (
                <div key={o.id} className={'opt-result ' + tone} data-option={o.id} data-correct={o.is_correct ? '1' : '0'} data-picked={picked ? '1' : '0'}>
                  <div className="opt-head"><b className="letter">{letter(o.id)}</b><span>{o.text}</span>
                    {o.is_correct && <em>correta</em>}{picked && !o.is_correct && <em>sua resposta</em>}</div>
                  {o.explanation && <p className="opt-expl">{o.explanation}</p>}
                </div>
              );
            })}
          </div>
          {result.explanation && (
            <Card title={`Por que a alternativa ${letter(result.correct_option ?? '')} é a correta`} tone="info">
              <Markdown text={result.explanation} />
            </Card>
          )}
          <References refs={result.references} onOpen={onOpenRef} />
          <UsageLine model={exercise.model} usage={exercise.usage} />
          <EndActions onNext={onNext} onHome={onHome} />
        </>
      )}

      {!done && (
        <div className="submit-row">
          <GlassButton variant="primary" radius={21} bevel={18} data-action="submit" disabled={!chosen || submitting} onClick={() => chosen && onSubmit(chosen)}>
            {submitting ? 'Corrigindo…' : 'Enviar resposta'}
          </GlassButton>
          <span className="hint">{chosen ? `Alternativa ${letter(chosen)} selecionada.` : 'Escolha uma alternativa para enviar.'}</span>
          {failure && <div className="msg error" role="alert">{failure.message}</div>}
        </div>
      )}
    </div>
  );
}
