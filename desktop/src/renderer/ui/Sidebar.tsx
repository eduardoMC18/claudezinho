import { useEffect, useState, type ReactNode } from 'react';
import { GlassButton } from './GlassButton';
import { GlassPanel } from './GlassPanel';
import { BookIcon, GearIcon, PencilIcon, SidebarToggleIcon } from './icons';

export type ScreenId = 'reader' | 'exercises' | 'settings';

const ITEMS: Array<{ id: ScreenId; label: string; icon: ReactNode }> = [
  { id: 'reader', label: 'Leitor de PDFs', icon: <BookIcon /> },
  { id: 'exercises', label: 'Criar Exercícios', icon: <PencilIcon /> },
];

/** Dica ao lado do ícone (só na barra recolhida). Aparece com o mouse ou com o foco do teclado. */
function Tip({ text, enabled, children }: { text: string; enabled: boolean; children: ReactNode }) {
  const [on, setOn] = useState(false);
  useEffect(() => { if (!enabled) setOn(false); }, [enabled]);
  return (
    <span className="tipwrap" onMouseEnter={() => setOn(true)} onMouseLeave={() => setOn(false)} onFocus={() => setOn(true)} onBlur={() => setOn(false)}>
      {children}
      {enabled && on && <span role="tooltip" className="tip">{text}</span>}
    </span>
  );
}

function NavItem({ id, label, icon, active, collapsed, onSelect }: { id: ScreenId; label: string; icon: ReactNode; active: boolean; collapsed: boolean; onSelect: (id: ScreenId) => void }) {
  const content = <>{icon}<span className="label">{label}</span></>;
  // Só o item ativo é lente (refração); os demais são texto simples: poucas superfícies refrativas.
  return (
    <Tip text={label} enabled={collapsed}>
      {active ? (
        <GlassButton className="nav" variant="lens" radius={22} bevel={20} data-nav={id} aria-current="page" aria-label={label} onClick={() => onSelect(id)}>
          {content}
        </GlassButton>
      ) : (
        <button className="nav-plain" data-nav={id} aria-label={label} onClick={() => onSelect(id)}>{content}</button>
      )}
    </Tip>
  );
}

interface Props {
  screen: ScreenId;
  onSelect: (id: ScreenId) => void;
  collapsed: boolean;
  onToggle: () => void;
}

export function Sidebar({ screen, onSelect, collapsed, onToggle }: Props) {
  const toggleLabel = collapsed ? 'Expandir barra lateral' : 'Recolher barra lateral';
  return (
    <GlassPanel as="aside" className="sidebar" radius={30} bevel={20} aria-label="Navegação" data-collapsed={collapsed ? '1' : '0'}>
      <div className="brand-row">
        <div className="brand" aria-hidden={collapsed}><span className="label">estudoTron 3000<small>Assistente de estudos</small></span></div>
        <Tip text={toggleLabel} enabled={collapsed}>
          <button className="collapse-btn" data-action="toggle-sidebar" aria-label={toggleLabel} aria-expanded={!collapsed}
            title={collapsed ? undefined : toggleLabel} onClick={onToggle}>
            <SidebarToggleIcon />
          </button>
        </Tip>
      </div>
      {ITEMS.map((it) => <NavItem key={it.id} {...it} active={screen === it.id} collapsed={collapsed} onSelect={onSelect} />)}
      <div className="spacer" />
      <NavItem id="settings" label="Configurações" icon={<GearIcon />} active={screen === 'settings'} collapsed={collapsed} onSelect={onSelect} />
      <div className="foot" aria-hidden={collapsed}><span className="foot-text">O conteúdo dos PDFs é enviado ao provedor de IA durante o chat.</span></div>
    </GlassPanel>
  );
}
