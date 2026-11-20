import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { exercises, workspace, type DocumentInfo, type ExRef, type Exercise, type ExerciseKind, type ExResult, type SlotId } from '../api';
import { ObjectiveQuestion } from '../exercises/ObjectiveQuestion';
import { StatusPanel, failureTitle, isRetryable, needsDocuments, needsSettings, toFailure, type Failure } from '../exercises/parts';
import { SubjectiveQuestion } from '../exercises/SubjectiveQuestion';
import { GlassButton } from '../ui/GlassButton';
import { BookIcon, PencilIcon } from '../ui/icons';

type Stage = 'home' | 'loading' | 'question' | 'error';

interface Props {
  onOpenReader(): void;
  onOpenSettings(): void;
  /** Abre o PDF na página (troca para o Leitor). */
  onOpenReference(r: ExRef): void;
}

const MODES: Array<{ kind: ExerciseKind; title: string; text: string; bullets: string[]; icon: ReactNode }> = [
  {
    kind: 'objective',
    title: 'Exercícios objetivos',
    text: 'Questões de múltipla escolha para testar se você entendeu o conteúdo.',
    bullets: ['Escolha uma alternativa entre as opções', 'Veja na hora o que está certo e por quê', 'Explicação de cada alternativa'],
    icon: <BookIcon />,
  },
  {
    kind: 'subjective',
    title: 'Exercícios subjetivos',
    text: 'Questões discursivas: você escreve a resposta e a IA corrige com critérios claros.',
    bullets: ['Escreva com as suas próprias palavras', 'Nota, acertos e o que faltou', 'Sugestão de resposta mais completa'],
    icon: <PencilIcon />,
  },
];

const docStatus = (d: DocumentInfo): string => {
  const pages = `${d.pages} ${d.pages === 1 ? 'página' : 'páginas'}`;
  if (d.status === 'processing') return `${pages} · indexando ${d.pages_done}/${d.pages}`;
  if (d.status === 'no_text') return `${pages} · sem texto (escaneado)`;
  if (d.status === 'error') return `${pages} · erro`;
  return `${pages} · pronto`;
};

/** Criar Exercícios: início (PDFs + modalidades) → carregando → questão → correção. Só usa o Gemini. */
export function Exercises({ onOpenReader, onOpenSettings, onOpenReference }: Props) {
  const [stage, setStage] = useState<Stage>('home');
  const [kind, setKind] = useState<ExerciseKind>('objective');
  const [exercise, setExercise] = useState<Exercise | null>(null);
  const [result, setResult] = useState<ExResult | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [answerFailure, setAnswerFailure] = useState<Failure | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [docs, setDocs] = useState<DocumentInfo[]>([]);
  const [docsLoaded, setDocsLoaded] = useState(false);
  const flow = useRef(0); // respostas atrasadas de um fluxo antigo são ignoradas

  // PDFs carregados (e progresso da indexação)
  useEffect(() => {
    let alive = true;
    const load = () => workspace.get()
      .then((w) => { if (alive) { setDocs((['A', 'B'] as SlotId[]).map((s) => w.slots[s]).filter((d): d is DocumentInfo => !!d)); setDocsLoaded(true); } })
      .catch(() => { if (alive) setDocsLoaded(true); });
    void load();
    const t = window.setInterval(() => { if (stage === 'home') void load(); }, 1200);
    return () => { alive = false; window.clearInterval(t); };
  }, [stage]);

  const goHome = useCallback(() => { flow.current++; setStage('home'); }, []);

  const start = useCallback(async (k: ExerciseKind) => {
    const mine = ++flow.current;
    setKind(k);
    setExercise(null);
    setResult(null);
    setSubmitting(false);
    setAnswerFailure(null);
    setFailure(null);
    setStage('loading');
    try {
      const ex = await exercises.generate(k);
      if (mine !== flow.current) return;
      setExercise(ex);
      setStage('question');
    } catch (e) {
      if (mine !== flow.current) return;
      setFailure(toFailure(e));
      setStage('error');
    }
  }, []);

  const submit = useCallback(async (body: { option?: string; text?: string }) => {
    if (!exercise) return;
    const mine = flow.current;
    setSubmitting(true);
    setAnswerFailure(null);
    try {
      const r = await exercises.answer(exercise.id, body);
      if (mine === flow.current) setResult(r);
    } catch (e) {
      if (mine === flow.current) setAnswerFailure(toFailure(e));
    } finally {
      if (mine === flow.current) setSubmitting(false);
    }
  }, [exercise]);

  const names = docs.map((d) => d.name).join(' e ');
  const indexing = docs.some((d) => d.status === 'processing');
  const allNoText = docs.length > 0 && docs.every((d) => d.status === 'no_text');
  const ready = docs.length > 0 && !indexing && !allNoText;
  const modeTitle = kind === 'objective' ? 'Exercícios objetivos' : 'Exercícios subjetivos';
  // Estados de painel único (sem PDFs, gerando, erro): o cartão fica no centro da ÁREA ÚTIL (coluna principal,
  // abaixo do cabeçalho), por Grid; a barra lateral recolhida/expandida muda a coluna e o cartão acompanha.
  const centered = stage === 'loading' || stage === 'error' || (stage === 'home' && docsLoaded && docs.length === 0);

  return (
    <>
      <header className="head">
        {stage !== 'home' && (
          <GlassButton className="sm" variant="film" radius={17} bevel={12} data-action="back" aria-label="Voltar ao início" onClick={goHome}>‹ Início</GlassButton>
        )}
        <h1>{stage === 'home' ? 'Criar Exercícios' : modeTitle}</h1>
      </header>

      <div className="screen exercises" data-stage={stage} data-centered={centered ? '1' : '0'}>
        {stage === 'home' && (
          <div className="ex-home">
            {!docsLoaded && <div className="loading">Carregando…</div>}
            {docsLoaded && docs.length === 0 && (
              <StatusPanel kind="info" title="Carregue um PDF primeiro"
                detail="Os exercícios são criados a partir dos documentos carregados no Leitor de PDFs. Adicione pelo menos um PDF e volte aqui.">
                <GlassButton variant="primary" radius={21} bevel={18} data-action="to-reader" onClick={onOpenReader}>Ir para o Leitor de PDFs</GlassButton>
              </StatusPanel>
            )}
            {docs.length > 0 && (
              <>
                <h2 className="ex-h">Material de estudo</h2>
                <div className="source-cards">
                  {docs.map((d) => (
                    <div key={d.id} className="ex-card source" data-source={d.slot}>
                      <small>{d.slot === 'A' ? 'PDF 1' : 'PDF 2'}</small>
                      <b title={d.name}>{d.name}</b>
                      <span className={d.status === 'ready' ? 'ok' : d.status === 'processing' ? '' : 'warn'}>{docStatus(d)}</span>
                    </div>
                  ))}
                </div>
                <h2 className="ex-h">Como você quer praticar?</h2>
                <div className="mode-cards">
                  {MODES.map((m) => (
                    <GlassButton key={m.kind} className="btn mode" variant="film" radius={28} bevel={18} data-mode={m.kind}
                      disabled={!ready} onClick={() => void start(m.kind)}>
                      <span className="mode-icon">{m.icon}</span>
                      <b>{m.title}</b>
                      <span>{m.text}</span>
                      <ul>{m.bullets.map((b) => <li key={b}>{b}</li>)}</ul>
                    </GlassButton>
                  ))}
                </div>
                <p className={'ex-note' + (ready ? '' : ' warn')} role="status">
                  {indexing ? 'Aguarde o fim da indexação dos PDFs para criar exercícios.'
                    : allNoText ? 'Os PDFs carregados não têm texto pesquisável (parecem escaneados), então a IA não consegue lê-los.'
                    : 'Para criar as questões, trechos dos seus PDFs são enviados ao Gemini (Google). A chave configurada em Configurações é a mesma do assistente.'}
                </p>
              </>
            )}
          </div>
        )}

        {stage === 'loading' && (
          <StatusPanel kind="loading" title="Gerando sua questão…"
            detail={names ? `A IA está lendo trechos de ${names}.` : 'A IA está lendo os trechos dos PDFs.'} />
        )}

        {stage === 'error' && failure && (
          <StatusPanel kind="error" title={failureTitle(failure.kind)} detail={failure.message}>
            {isRetryable(failure.kind) && <GlassButton variant="primary" radius={21} bevel={18} data-action="retry" onClick={() => void start(kind)}>Tentar de novo</GlassButton>}
            {needsSettings(failure.kind) && <GlassButton variant="primary" radius={21} bevel={18} data-action="to-settings" onClick={onOpenSettings}>Abrir Configurações</GlassButton>}
            {needsDocuments(failure.kind) && <GlassButton variant="primary" radius={21} bevel={18} data-action="to-reader" onClick={onOpenReader}>Ir para o Leitor de PDFs</GlassButton>}
            <GlassButton variant="lens" radius={21} bevel={18} data-action="home" onClick={goHome}>Voltar ao início</GlassButton>
          </StatusPanel>
        )}

        {stage === 'question' && exercise && (exercise.kind === 'objective'
          ? <ObjectiveQuestion key={exercise.id} exercise={exercise} result={result} submitting={submitting} failure={answerFailure}
              onSubmit={(option) => void submit({ option })} onNext={() => void start(kind)} onHome={goHome} onOpenRef={onOpenReference} />
          : <SubjectiveQuestion key={exercise.id} exercise={exercise} result={result} submitting={submitting} failure={answerFailure}
              onSubmit={(text) => void submit({ text })} onNext={() => void start(kind)} onHome={goHome} onOpenRef={onOpenReference} />)}
      </div>
    </>
  );
}
