import { useRef, type ButtonHTMLAttributes, type KeyboardEvent, type ReactNode } from 'react';
import { useGlass } from '../glass/useGlass';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** lens = lente refrativa · primary = lente graphite (único destaque forte) · film = película (ação secundária). */
  variant?: 'lens' | 'primary' | 'film';
  radius?: number;
  bevel?: number;
  children?: ReactNode;
}

/** Botão de vidro com mola de afundar (a mesma mola alimenta o DOM e o shader). */
export function GlassButton({ variant = 'lens', radius, bevel = 18, className, children, onKeyDown, onKeyUp, ...rest }: Props) {
  const ref = useRef<HTMLButtonElement>(null);
  const handle = useGlass(ref, { kind: variant === 'film' ? 'film' : variant, radius, bevel, pressable: true });
  const press = (on: boolean) => () => handle.current?.press(on);
  const key = (on: boolean) => (e: KeyboardEvent<HTMLButtonElement>) => {
    if (e.key === ' ' || e.key === 'Enter') handle.current?.press(on);
  };
  return (
    <button
      ref={ref}
      type="button"
      className={['glass', 'btn', variant === 'primary' ? 'primary' : '', className].filter(Boolean).join(' ')}
      data-glass={variant}
      onPointerDown={press(true)}
      onPointerUp={press(false)}
      onPointerLeave={press(false)}
      onPointerCancel={press(false)}
      onKeyDown={(e) => { key(true)(e); onKeyDown?.(e); }}
      onKeyUp={(e) => { key(false)(e); onKeyUp?.(e); }}
      {...rest}
    >
      {children}
    </button>
  );
}
