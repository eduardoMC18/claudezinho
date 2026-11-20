import type { SVGProps } from 'react';

const base: SVGProps<SVGSVGElement> = {
  className: 'icon',
  viewBox: '0 0 24 24',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.8,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
  'aria-hidden': true,
};

export const BookIcon = () => (
  <svg {...base}><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21.5z" /><path d="M4 18.5A2.5 2.5 0 0 1 6.5 16H20" /></svg>
);
export const PencilIcon = () => (
  <svg {...base}><path d="M4 20l4-1 11-11-3-3L5 16z" /><path d="M14 6l3 3" /></svg>
);
/** Barra lateral com seta: aponta para a esquerda (recolher); a sidebar gira 180° quando recolhida (expandir). */
export const SidebarToggleIcon = () => (
  <svg {...base}><rect x="3.5" y="4.5" width="17" height="15" rx="3" /><path d="M9 4.5v15" /><path d="M15.5 9.5L13 12l2.5 2.5" /></svg>
);
export const GearIcon = () => (
  <svg {...base}><circle cx="12" cy="12" r="3" /><path d="M19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a7 7 0 0 0-2-1.2L14.2 3h-4.4l-.4 2.7a7 7 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 2 1.2l.4 2.7h4.4l.4-2.7a7 7 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z" /></svg>
);
