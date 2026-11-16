# Design Map

Fonte: https://developer.apple.com/documentation/TechnologyOverviews/liquid-glass (1 página, viewport 1440x900). Ressalva: o CSS medido descreve o site de documentação, não o material Liquid Glass em si (a página não tem sombras nem blur). Os princípios do material vêm do texto da página e foram tratados como evidência textual.

## Spacing Scale
- Unidade-base: ~5 px com derivados de 1,7 (9,6 / 13,6 / 27,2 px = múltiplos de 0,8 px sobre 12/17/34). Valores observados: 1, 3, 4, 5, 8, 9,6, 10, 11,2, 13,6, 15, 17, 20, 27,2, 40, 51,2 px.
- Mais frequentes: 9,6 px (67 usos), 5 px (39), 13,6 px (23), 17 px (14).
- Grade: 3 colunas de 260 px com gap de 20 px; contêiner de conteúdo de 993 px; imagem principal de 820 px.
- Método: margens negativas de -8 px no `ul` (compensam o padding dos itens).

## Font Hierarchy
- Famílias: SF Pro Text (682 nós), SF Pro Display (18), Arial (3, fallback).
- h1 40 px / 700 / line-height 44 px / tracking -0,374 px (SF Pro Display).
- h2 19 px / 600 / 23 px / tracking +0,228 px (SF Pro Display); h3 17 px / 600 / 25 px.
- Corpo 17 px / 400 / 25 px. Legendas e navegação 12 px (89 nós, o tamanho mais comum). Botões 14 px / 600 e 17 px / 400.
- Pesos: 400 (131 nós), 600 (30), 700 (1).

## Color Palette
- Fundo #FFFFFF em 84,6% da área; bloco de rodapé/seção #F5F5F7 em 13,3%; #FAFAFC em 0,9%; #E8E8ED em 0,2%.
- Texto principal #1D1D1F (72 nós); secundário #515154 (68 nós); terciário #86868B (4 nós).
- Acento único azul de link #0066CC (7 nós de texto) e #0071E3 (botões, 0% da área). Nenhum outro matiz.

## Image Ratios
- 2,12:1: imagem de abertura (920x433, 820 px renderizado), comparação "Before/After".
- 2,02:1: duas imagens de recurso (332x164).
- 1,33:1: imagem de código de exemplo (675x506).

## Component Tokens
- Raio: 6 px (19 nós), 18 px (13), 12 px (3), 10 px (3), 13 px (1). Escala de dois degraus dominantes: 6 e 18 px.
- Sombras: nenhuma medida (0 em `effects.shadows`). Profundidade vem de planos de cor (#FFFFFF sobre #F5F5F7), não de sombra.
- Movimento: `color 0.32s cubic-bezier(0.4,0,0.6,1)`; `background-color` e `color 0.5s cubic-bezier(0.28,0.11,0.32,1)`; `width 0.3s ease-in`.
- Acessibilidade: `:focus-visible` e `prefers-reduced-motion` presentes.

---

# Taste DNA

### Cor contida para o conteúdo atravessar o controle
- **Trigger**: When the designers faced controls and navigation that sit over changing content and must stay legible without dyeing it.
- **Decision**: They chose a neutral control layer with the colour left to the content ("be judicious with your use of color in controls and navigation") over brand-coloured toolbars and buttons.
- **Reason**: Because a tinted control blocks what is behind it, while a neutral one lets the content infuse the material and shine through, which is what makes the surface read as glass.
- **Evidence**: Texto da página: "allow your content to infuse them and shine through"; acento azul #0066CC/#0071E3 sobre 0% da área; fundo #FFFFFF em 84,6% e #F5F5F7 em 13,3%; texto em dois tons de cinza (#1D1D1F, #515154).

### Hierarquia por foco de conteúdo, não por ornamento
- **Trigger**: When the designers faced a new material that could be applied to every surface at once.
- **Decision**: They chose to define layout and navigation so the most important content is in focus ("puts the most important content in focus") over spreading the effect evenly across all elements.
- **Reason**: Because material on everything removes the contrast that tells the eye what is primary; hierarchy needs a few elements that float and the rest that stay quiet.
- **Evidence**: Texto: "establish hierarchy, create harmony, and maintain consistency"; apenas 3 níveis tipográficos fortes (40/19/17 px) e 12 px para o resto (89 nós); zero sombras medidas.

### Controles padrão herdam o material; só o personalizado o reimplementa
- **Trigger**: When the designers faced thousands of existing apps that would have to be redrawn.
- **Decision**: They chose to make standard controls pick up the appearance and behaviour automatically, with custom effects as the exception, over asking every team to restyle each element.
- **Reason**: Because consistency across devices depends on one source of the material, and a bespoke glass per app breaks the shared language.
- **Evidence**: Texto: "Standard components ... pick up the appearance and behavior of this material automatically. You can also implement these effects in custom interface elements"; "adopting Liquid Glass doesn't mean reinventing your app from the ground up"; seção "Before / After".

### Restraint: dois raios e nenhuma sombra no documento que explica o vidro
- **Trigger**: When the designers faced the page that sells a luminous, fluid material and the pull to demonstrate it with glows and blurs.
- **Decision**: They chose a flat page with two radii (6 px and 18 px), no shadows and one link blue over a demonstration made of effects.
- **Reason**: Because the material is shown in the before/after images; the surrounding page must not compete with it, and effects around the explanation would blur which part is the material.
- **Evidence**: `effects.shadows` vazio; raios 6 px (19 nós) e 18 px (13 nós); 84,6% da área em branco puro; `cards` vazio; transições só de cor (0,32 s e 0,5 s) e largura.
