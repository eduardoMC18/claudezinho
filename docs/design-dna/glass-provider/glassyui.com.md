# Design Map

Fonte: https://glassyui.com/components/glass-provider (1 página de documentação do componente `GlassProvider`; viewport 1440x900, página de 5087 px; banner de cookies recusado). Ressalva: o CSS medido descreve a página de documentação; o material de vidro aparece em poucos nós (ver Component Tokens). Esta análise é distinta da página inicial do mesmo site (`../glassyui.com.md`).

## Spacing Scale
- Unidade-base: 2 px com degraus de 4. Valores observados: 1, 2, 4, 5, 6, 8, 10, 11, 12, 13, 14, 16, 32, 56 px.
- Mais frequentes: 6 px (129 usos), 2 px (123), 13 px (72), 5 px (49), 4 px (25), 8 px (18).
- Entre seções: 56 px em quatro intervalos seguidos (Preview, Installation, Usage, Examples, API reference); rodapé com margem superior de 160 px e padding `32px 24px 40px`.
- Blocos de código: padding `16px 18px 18px`.

## Font Hierarchy
- Famílias: `-apple-system` (456 nós) e `ui-monospace` (63 nós, usada nos nomes da API e no código).
- h1 48 px / 600 / line-height 51,84 px / tracking -0,144 px.
- h2 28 px / 600 / 31,92 px / tracking +0,196 px.
- h3 16 px / 600 / 23,5 px, em `ui-monospace` (nomes `GlassProvider`, `usePreferences`...).
- Texto de apoio do título ~19 px (2 nós); interface e tabelas em 12 px (86 nós), 14 px (68), 13 px (64), 12,5 px (15), 15 px (15).
- Pesos: 400 (188 nós), 600 (67), 500 (13).

## Color Palette
- Fundo da página #FAF9F7 (branco quente); superfícies de tabelas e blocos #FFFFFF (96,2% da área com fundo próprio).
- Texto: #222224 (60 nós), cinza #66666A (123 nós), #505055 (8), e `color(srgb 0.235 0.235 0.240)` (48 nós, texto de código/tabela).
- Cores de código: azul-petróleo #0B4F79 (16 nós) para tipos e props; vermelho #A5361F (5 nós) para tags e atributos.
- Link/acento: #005EC6 (2 nós) e o azul #0B4F79; nenhuma cor de marca em áreas grandes. A massa de cor vem da faixa laranja no palco de prévia.
- Superfícies translúcidas: branco a 72% (3 nós), branco a 45% (1), preto a 5% (9 nós, trilhos de controle), preto a 8% (2), `rgba(4,33,65,0.6)` (1 nó).

## Image Ratios
- Palco de prévia: 684x~363 px (~1,88:1), cantos de 28 px, com uma faixa laranja ondulada sobre fundo cinza claro (o conteúdo que o vidro refrata).
- Nenhum `<img>` medido: o palco é CSS/SVG.

## Component Tokens
- Raio: 9 px (49 nós: itens de menu, linhas), 999 px (32) e 9999 px (10) nas cápsulas, 16 px (7), 6 px (4), 28 px (palco de prévia).
- Sombras (poucos padrões): `0 1px 2px rgba(0,0,0,.06), 0 8px 24px -6px rgba(0,0,0,.14)` (palco/cartões); controle ativo `inset 0 1px 0 rgba(255,255,255,.9), inset 0 0 0 1px rgba(0,0,0,.14), 0 1px 0 rgba(0,0,0,.16)`; botão grande `0 1.8px 2px -1px rgba(52,62,84,.18), 0 6.7px 10px -5px rgba(52,62,84,.2)`.
- Backdrop (só 4 nós em 5087 px): `blur(2px) saturate(1.5) brightness(1.08)` (2 nós), `blur(16px) brightness(1.06) contrast(1.02) saturate(1.8)` (1 nó) e dois `url(#lg-lens-...)` (filtro SVG com `feGaussianBlur` desvio 6 e `feImage`; um deles encadeia `grayscale(1) contrast(.5) brightness(.35) sepia(1) hue-rotate(172deg) saturate(6) brightness(1.4)` para tingir a lente).
- Controle segmentado ("React" / "React Native"): trilho `rgba(0,0,0,.05)` com anel interno de 1 px a 10% e pílula ativa branca.
- Grade: 3 colunas `220px 684px 200px`, gap 56 px (menu, conteúdo, "On this page").
- Movimento: `scale 0.32s cubic-bezier(0.34,1.56,0.64,1)`, `--lg-button-rise 0.32s cubic-bezier(0.32,0.72,0,1)`, cor/fundo em 0,18 a 0,2 s. `:focus-visible` e `prefers-reduced-motion` presentes.
- API do componente: `GlassProvider` escreve atributos `data-lg-*` no `<html>`; `usePreferences` devolve `theme` (light/dark), `mode` (clear/tinted), `quality` (low/medium/high), `reduceMotion`, `reduceTransparency`, `thickness` (0,25 a 2, padrão 1); grava em `localStorage` (`glassy-ui-preferences`).

---

# Taste DNA

### Um provedor decide o material; os componentes obedecem
- **Trigger**: When the designers faced 38 glass components that must all respect the same theme and the same accessibility settings, on every user's device.
- **Decision**: They chose one `GlassProvider` that holds the preferences and writes them as `data-lg-*` attributes on the `<html>` element over per-component props or per-component CSS switches.
- **Reason**: Because a user who turns on Reduce Transparency expects every glass surface to change at once; a single source of truth makes that one write instead of 38 coordinated ones.
- **Evidence**: Texto da API: "Holds the preferences and writes them to data-lg-* attributes on the html element"; instalação adiciona 4 arquivos; `useOptionalPreferences` devolve os padrões fora do provedor e segue a transparência reduzida do sistema; 5 preferências (theme, mode, quality, reduceMotion, reduceTransparency, thickness).

### Qualidade detectada pelo aparelho, com a escolha do usuário acima
- **Trigger**: When the designers faced a refraction effect that costs real GPU time on phones and low-memory machines.
- **Decision**: They chose to derive `quality` automatically (low with reduced transparency or 4 GB of memory or less, medium on touch or reduced motion, high otherwise) and let a saved user choice always win over detection.
- **Reason**: Because a weak device that stutters makes the material look broken, while a lower tier that stays smooth keeps the look intact; the override keeps control with the person, not the heuristic.
- **Evidence**: Tabela da API: "low with reduced transparency or 4 GB of device memory or less, medium on touch or with reduced motion, otherwise high"; `defaultTheme`: "A saved choice always wins"; `reduceTransparency` "turns off the lens and refraction".

### Espessura como multiplicador limitado, não como estilo
- **Trigger**: When the designers faced the need to let products make the glass thicker or thinner without breaking legibility.
- **Decision**: They chose one number, `thickness`, defined as a multiple of regular glass (default 1) and clamped to 0,25 to 2, over exposing raw bevel, blur and refraction values.
- **Reason**: Because a bounded multiplier scales every material parameter together and cannot be pushed to the extremes where text turns unreadable.
- **Evidence**: Linha da tabela: "Glass thickness as a multiple of regular; applied between 0.25 and 2", tipo `number`, padrão `1`; `mode` limitado a `'clear' | 'tinted'`.

### Restraint: documentação plana em volta de um único palco de vidro
- **Trigger**: When the designers faced a documentation page for a glass library and the pull to make every surrounding panel glass too.
- **Decision**: They chose a flat page (fundo #FAF9F7, menus e tabelas em branco com anel de 1 px, texto de 12 a 14 px) and spent the backdrop effects on four nodes, with the lens only inside the preview stage, over glass sidebars, glass tables and glass code blocks.
- **Reason**: Because the material is only readable against a plain background; if the documentation chrome were glass as well, the preview would stop being the one place where the refraction is the subject.
- **Evidence**: `backdrop-filter` em 4 nós numa página de 5087 px; 96,2% da área com fundo próprio em #FFFFFF; duas sombras recorrentes; grade `220px 684px 200px` com gap 56 px e quatro intervalos de seção iguais a 56 px; o palco de prévia (684x~363 px, raio 28 px) é o maior elemento com faixa colorida.
