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
export const FolderIcon = () => (
  <svg {...base}><path d="M3.5 7.5A2 2 0 0 1 5.5 5.5h4l2 2.2h7a2 2 0 0 1 2 2V17a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2z" /></svg>
);
export const FileIcon = () => (
  <svg {...base}><path d="M6.5 3.5h7L18.5 8.5V19a1.5 1.5 0 0 1-1.5 1.5H6.5A1.5 1.5 0 0 1 5 19V5a1.5 1.5 0 0 1 1.5-1.5z" /><path d="M13.5 3.5V8.5H18.5" /></svg>
);
export const PageIcon = () => (
  <svg {...base}><rect x="5" y="3.5" width="14" height="17" rx="2" /><path d="M8.5 9h7M8.5 12.5h7M8.5 16h4" /></svg>
);
export const LinkIcon = () => (
  <svg {...base}><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1" /><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1" /></svg>
);
export const RefreshIcon = () => (
  <svg {...base}><path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3" /><path d="M19.5 4.5v4h-4" /></svg>
);
