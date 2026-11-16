import { useRef, type ElementType, type HTMLAttributes, type ReactNode } from 'react';
import { useGlass } from '../glass/useGlass';
import type { GlassKind } from '../glass/engine';

interface Props extends HTMLAttributes<HTMLElement> {
  as?: ElementType;
  /** film = película (blur, sem refração) · lens = refração real. Conteúdo longo/texto deve ficar em Solid (DOM opaco). */
  kind?: GlassKind;
  radius?: number;
  bevel?: number;
  children?: ReactNode;
}

/** Superfície de vidro. Por padrão é Film. */
export function GlassPanel({ as: Tag = 'div', kind = 'film', radius = 28, bevel = 18, className, children, ...rest }: Props) {
  const ref = useRef<HTMLElement>(null);
  useGlass(ref, { kind, radius, bevel });
  return (
    <Tag ref={ref} className={['glass', className].filter(Boolean).join(' ')} data-glass={kind} {...rest}>
      {children}
    </Tag>
  );
}
