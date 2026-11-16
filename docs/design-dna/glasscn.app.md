# Design Map

Fonte: https://glasscn.app/showcase (1 página, viewport 1440x900, página de 2387 px). Cores convertidas de lab/oklch para hex aproximado.

## Spacing Scale
- Unidade-base: 4 px. Valores observados: 2, 4, 6, 8, 12, 16, 20, 24, 28, 32, 40, 48, 64, 112 px.
- Mais frequentes: 8 px (21 usos), 12 px (20), 4 px (19), 6 px (15), 2 px (14).
- Entre seções: 138 px, depois 64 px, 64 px. Rodapé: margem superior 96 px, padding inferior 112 px.
- Método: `gap` (24 px na grade, 20 px entre blocos, 4 px no `nav`).

## Font Hierarchy
- Famílias: `system-ui` (253 nós) e `ui-rounded` (só o h1).
- 72 px / 700: texto dentro da imagem ("GLASS").
- h1 48 px / 700 / `ui-rounded`, tracking -1,2 px, line-height 48 px.
- 28 px / 600 (títulos de seção) e h2 22 px / 600 / line-height 28 px; h3 17 px / 600 / line-height 22 px.
- Corpo 16 px / 400 / line-height 26 px, `max-width` 672 px.
- Controles 14 px (37 nós; 600 nos botões, 400 nas abas); legendas 12 px (15 nós).

## Color Palette
- Fundo da página #F4F4F8 (62,2% da área), neutro levemente azulado.
- Superfície sólida #FFFFFF (23%); vidro branco a 74% (3,1%) e a 58% (2,9%).
- Vidro neutro `oklch(0.575 0.012)` a 12% (7,2%) e a 20% nos estados de destaque.
- Texto principal #000000; secundário #4B4B53 (tingido de azul).
- Acento único #0A5FD6 em 0,3% da área (botão "Get started", interruptores ligados). O resto da cor vem das fotos.

## Image Ratios
- 1,50:1: foto de paisagem do palco Photos (1600x1067, 808 px renderizado).
- 0,67:1: foto retrato `coast.jpg` (1200x1800).
- Palcos renderizados: Photos 808x576 (~1,40:1), Inspect ~804x320 (~2,51:1), Separate ~804x240 (~3,35:1).

## Component Tokens
- Raio: 9999 px em controles (cápsula, 51 nós); 24 px no palco de imagem e no painel de ajustes.
- Sombra: `0 10px 30px 0 rgba(0,0,0,0.12)` (camada única, preto puro) + filete interno `inset 0 1px 0 0 rgba(255,255,255,0.92)`.
- Anéis de controle: 1 px, preto a 0,072 / 0,12 / 0,2.
- Grade: contêiner 1152 px, padding `40px 24px 64px`, 2 colunas `808px 272px`, gap 24 px.
- Movimento: `color/background-color/border-color` 0,15 a 0,2 s `cubic-bezier(0.4,0,0.2,1)`; `scale` 0,3 s e `height` 0,2 s com curva `linear()` de mola.
- Acessibilidade: `:focus-visible` e `prefers-reduced-motion` presentes.
- Parâmetros de material expostos ao usuário: Clarity (Clear ↔ Tinted, 50%), Reduce transparency, Reduce bright effects, Show control borders, Optical refraction, Edge displacement (14 px), Regular/Clear.

---

# Taste DNA

### Imagem viva atrás do vidro, escurecida só sob o controle
- **Trigger**: When the designers faced controls that must stay legible on top of a bright photo without turning the whole photo gray.
- **Decision**: They chose a full-colour photograph with a local dimming band only under each control over a uniform tint or a dark overlay on the whole stage.
- **Reason**: Because people read the glass as glass only when they can still recognise what sits behind it; a global tint makes the photo look like wallpaper and the control look like a sticker.
- **Evidence**: Palco Photos de 808x576 px com 3 fotos (1,50:1 e 0,67:1); fundo da página #F4F4F8 em 62,2% da área; slider Clarity de Clear a Tinted (50% padrão); texto da página: "Only the bands under the controls are dimmed, so the image stays bright".

### Refração e opacidade como parâmetros do usuário
- **Trigger**: When the designers faced a material that looks right for one person and blinding or illegible for another.
- **Decision**: They chose to expose the material as settings (Clarity, Reduce transparency, Reduce bright effects, Show control borders, Optical refraction, Edge displacement 14 px) over locking a single intensity as the brand look.
- **Reason**: Because transparency has a comfort limit that differs by eyes, screen and content, and a tool that cannot be dialled down gets switched off entirely.
- **Evidence**: 6 ajustes em 2 painéis na coluna de 272 px (raio 24 px); valores ao vivo ("14 px", "50%"); legenda declara o fallback: "Chromium bends the backdrop at the edge; Safari and Firefox use the diffuse material".

### Cápsula única e luz no filete, sem relevo
- **Trigger**: When the designers faced the temptation to make controls feel raised with layered shadows, gradients and coloured glows.
- **Decision**: They chose one single-layer shadow (`0 10px 30px` at 12% black) plus a 1 px inner highlight (`0 1px 0` white at 92%) and one pill radius over multi-layer elevation, tinted shadows or mixed corner radii.
- **Reason**: Because a raised, coloured button reads as an object on the page, while a thin rim of light on a transparent pill reads as the edge of a pane of glass.
- **Evidence**: 51 nós a 9999 px, 17 a 3,36e7 px, só 4 a 24 px; sombras de preto puro a 0,12/0,2/0,072 (sem tinta); 0 gradientes decorativos; acento #0A5FD6 em 0,3% da área.

### Vidros que se fundem num só material
- **Trigger**: When the designers faced several glass controls sitting next to each other.
- **Decision**: They chose a shared backdrop that merges nearby shapes into one contour over drawing every pill as an isolated sticker.
- **Reason**: Because glass is one continuous sheet in the physical world, and merging tells the eye that these controls belong to the same action group.
- **Evidence**: Palco "Separate, then come together" (~804x240 px) com três controles circulares e botão "Bring together"; texto da página: "Labels stay sharp while a shared backdrop follows the merging shapes"; `scale` 0,3 s e `height` 0,2 s com mola de 20 pontos. Evidência parcialmente textual: a fusão em si não foi medida no DOM.
