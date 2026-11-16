import type { ReactNode } from 'react';
import { GlassButton } from './GlassButton';
import { GlassPanel } from './GlassPanel';
import { BookIcon, GearIcon, PencilIcon } from './icons';

export type ScreenId = 'reader' | 'exercises' | 'settings';

const ITEMS: Array<{ id: ScreenId; label: string; icon: ReactNode }> = [
  { id: 'reader', label: 'Leitor de PDFs', icon: <BookIcon /> },
  { id: 'exercises', label: 'Criar Exercícios', icon: <PencilIcon /> },
];

function NavItem({ id, label, icon, active, onSelect }: { id: ScreenId; label: string; icon: ReactNode; active: boolean; onSelect: (id: ScreenId) => void }) {
  // Só o item ativo é lente (refração); os demais são texto simples: poucas superfícies refrativas.
  return active ? (
    <GlassButton className="nav" variant="lens" radius={22} bevel={20} data-nav={id} aria-current="page" onClick={() => onSelect(id)}>
      {icon}{label}
    </GlassButton>
  ) : (
    <button className="nav-plain" data-nav={id} onClick={() => onSelect(id)}>
      {icon}{label}
    </button>
  );
}

export function Sidebar({ screen, onSelect }: { screen: ScreenId; onSelect: (id: ScreenId) => void }) {
  return (
    <GlassPanel as="aside" className="sidebar" radius={30} bevel={20} aria-label="Navegação">
      <div className="brand">estudoTron 3000<small>Assistente de estudos</small></div>
      {ITEMS.map((it) => <NavItem key={it.id} {...it} active={screen === it.id} onSelect={onSelect} />)}
      <div className="spacer" />
      <NavItem id="settings" label="Configurações" icon={<GearIcon />} active={screen === 'settings'} onSelect={onSelect} />
      <div className="foot">O conteúdo dos PDFs é enviado ao provedor de IA durante o chat.</div>
    </GlassPanel>
  );
}
