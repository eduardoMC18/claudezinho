import { Fragment, type ReactNode } from 'react';

/**
 * Markdown seguro para as respostas da IA. Produz elementos React (nunca HTML bruto): nada que a IA escreva
 * vira marcação executável. Suporta títulos, parágrafos, listas, citações em bloco, tabelas (GFM), blocos de
 * código, **negrito**, *itálico*, `código` e links. Links `ref:A:12` viram botões de citação; só http(s) abre
 * fora do app; qualquer outro esquema (javascript:, file:, data:) é exibido como texto.
 */

export interface MarkdownProps {
  text: string;
  onCite?: (slot: 'A' | 'B', page: number) => void;
}

const FENCE = /^```\s*([\w+-]*)\s*$/;
const HEADING = /^(#{1,4})\s+(.*)$/;
const UL = /^\s*[-*+]\s+(.*)$/;
const OL = /^\s*\d+[.)]\s+(.*)$/;
const QUOTE = /^>\s?(.*)$/;
const HR = /^\s*([-*_])(\s*\1){2,}\s*$/;
const TABLE_SEP = /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/;

function splitRow(line: string): string[] {
  let s = line.trim();
  if (s.startsWith('|')) s = s.slice(1);
  if (s.endsWith('|')) s = s.slice(0, -1);
  return s.split('|').map((c) => c.trim());
}

const INLINE = /(`[^`\n]+`)|(\*\*[^*\n]+?\*\*)|(\[[^\]\n]*\]\([^)\s]+\))|(\*[^*\s][^*\n]*?\*)/g;

function inline(text: string, onCite: MarkdownProps['onCite'], keyBase: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let n = 0;
  for (const m of text.matchAll(INLINE)) {
    const i = m.index ?? 0;
    if (i > last) out.push(text.slice(last, i));
    const tok = m[0];
    const key = `${keyBase}-${n++}`;
    if (m[1]) out.push(<code key={key}>{tok.slice(1, -1)}</code>);
    else if (m[2]) out.push(<strong key={key}>{inline(tok.slice(2, -2), onCite, key)}</strong>);
    else if (m[4]) out.push(<em key={key}>{inline(tok.slice(1, -1), onCite, key)}</em>);
    else if (m[3]) {
      const lm = /^\[([^\]]*)\]\(([^)\s]+)\)$/.exec(tok)!;
      const label = lm[1], url = lm[2];
      const ref = /^ref:([AB]):(\d+)$/.exec(url);
      if (ref) {
        const slot = ref[1] as 'A' | 'B', page = parseInt(ref[2], 10);
        out.push(
          <button key={key} type="button" className="cite" data-slot={slot} data-page={page} onClick={() => onCite?.(slot, page)}>
            {label}
          </button>,
        );
      } else if (/^https?:\/\//i.test(url)) {
        out.push(<a key={key} href={url} target="_blank" rel="noreferrer noopener">{label}</a>);
      } else {
        out.push(label); // esquema não permitido: só o texto
      }
    }
    last = i + tok.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

export function Markdown({ text, onCite }: MarkdownProps) {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  const blocks: ReactNode[] = [];
  let i = 0;
  let k = 0;
  const key = () => `b${k++}`;

  while (i < lines.length) {
    const line = lines[i];

    if (!line.trim()) { i++; continue; }

    const fence = FENCE.exec(line);
    if (fence) {
      const code: string[] = [];
      i++;
      while (i < lines.length && !FENCE.test(lines[i])) code.push(lines[i++]);
      i++; // fecha (ou fim do texto, se a resposta ainda está chegando)
      blocks.push(<pre key={key()} data-lang={fence[1] || undefined}><code>{code.join('\n')}</code></pre>);
      continue;
    }

    const h = HEADING.exec(line);
    if (h) {
      const level = h[1].length;
      const Tag = (`h${level + 2}` as 'h3' | 'h4' | 'h5' | 'h6'); // h3.. dentro de uma bolha
      blocks.push(<Tag key={key()}>{inline(h[2], onCite, `h${k}`)}</Tag>);
      i++;
      continue;
    }

    if (HR.test(line)) { blocks.push(<hr key={key()} />); i++; continue; }

    if (line.includes('|') && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1]) && lines[i + 1].includes('-')) {
      const head = splitRow(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && lines[i].includes('|') && lines[i].trim()) rows.push(splitRow(lines[i++]));
      const id = key();
      blocks.push(
        <div key={id} className="table-wrap">
          <table>
            <thead><tr>{head.map((c, ci) => <th key={ci}>{inline(c, onCite, `${id}h${ci}`)}</th>)}</tr></thead>
            <tbody>
              {rows.map((r, ri) => (
                <tr key={ri}>{head.map((_, ci) => <td key={ci}>{inline(r[ci] ?? '', onCite, `${id}r${ri}c${ci}`)}</td>)}</tr>
              ))}
            </tbody>
          </table>
        </div>,
      );
      continue;
    }

    if (QUOTE.test(line)) {
      const q: string[] = [];
      while (i < lines.length && QUOTE.test(lines[i])) q.push(QUOTE.exec(lines[i++])![1]);
      blocks.push(<blockquote key={key()}>{inline(q.join(' '), onCite, `q${k}`)}</blockquote>);
      continue;
    }

    if (UL.test(line) || OL.test(line)) {
      const ordered = OL.test(line) && !UL.test(line);
      const re = ordered ? OL : UL;
      const items: string[] = [];
      while (i < lines.length && re.test(lines[i])) items.push(re.exec(lines[i++])![1]);
      const id = key();
      const list = items.map((it, ii) => <li key={ii}>{inline(it, onCite, `${id}i${ii}`)}</li>);
      blocks.push(ordered ? <ol key={id}>{list}</ol> : <ul key={id}>{list}</ul>);
      continue;
    }

    // parágrafo: junta linhas seguidas
    const para: string[] = [];
    while (
      i < lines.length && lines[i].trim() && !FENCE.test(lines[i]) && !HEADING.test(lines[i]) &&
      !UL.test(lines[i]) && !OL.test(lines[i]) && !QUOTE.test(lines[i]) && !HR.test(lines[i]) &&
      !(lines[i].includes('|') && i + 1 < lines.length && TABLE_SEP.test(lines[i + 1]))
    ) para.push(lines[i++]);
    const id = key();
    blocks.push(
      <p key={id}>
        {para.map((l, li) => (
          <Fragment key={li}>{li > 0 && <br />}{inline(l, onCite, `${id}l${li}`)}</Fragment>
        ))}
      </p>,
    );
  }
  return <div className="md">{blocks}</div>;
}
