# Design Map

Fonte: https://glassyui.com/ (Glassy UI, 1 página; viewport 1440x900, página de 14452 px; introdução pulada e banner de cookies recusado antes da extração). A URL pedida inicialmente (glassui.dev) não existe; o usuário indicou glassyui.com.

## Spacing Scale
- Unidade-base: 2 px com degraus de 4. Valores observados: 1, 2, 3, 4, 6, 7, 8, 10, 12, 14, 16, 20, 24, 28, 32 px.
- Mais frequentes: 2 px (131 usos), 8 px (38), 4 px (35), 6 px (34), 12 px (26), 28 px (25).
- Seção hero: padding `108px 24px 45px`; rodapé com margem superior de 160 px e padding `32px 24px 40px`.
- Grade de componentes: 3 colunas de ~387 px, gap 20 px (12 grades na página).

## Font Hierarchy
- Famílias: `-apple-system` (875 nós) e `ui-monospace` (24).
- h1 80 px / 600 / line-height 84 px / tracking -1,2 px; h2 56 px / 600 / 60 px / tracking -0,28 px.
- h3 e h4 19 px / 600 / 28 px / tracking -0,38 px.
- Corpo 21 px / 400 / 29 px, `max-width` 600 px.
- Tamanhos de interface: 12 px (71 nós), 14 px (63), 13 px (48), 17 px (28), 11 px (16).
- Pesos: 400 (146), 600 (138), 500 (33).

## Color Palette
- Fundo da página #FAF9F7 (branco quente); superfície #FFFFFF em 72,9% da área.
- Vidro: branco a 86% (`rgba(255,255,255,0.86)`), cinza-azul neutro `rgba(118,118,128,0.2)` (trilhos de controle), preto a 8% (`rgba(0,0,0,0.08)`).
- Texto #222224 (136 nós), secundário #66666A (109 nós), terciário #505055 (26).
- Acento azul #0067D9 (botão primário, interruptores ligados; 0,2% da área) e variante a 10% (`rgba(0,103,217,0.1)`); sucesso #14A874 sobre #E0F7EE a 88%; erro #B02D14.
- A maior massa de cor vem de uma faixa laranja-coral atrás dos vidros (imagem), não de CSS.

## Image Ratios
- Palco de demonstração "tela de computador": ~1200x(750) px com cantos de 28 px.
- Maquete de telefone: ~300x540 px (0,56:1), com legendas ligadas por linhas finas.
- Imagens medidas pelo extrator: nenhuma `img` acima de 80 px; os fundos são canvas/SVG/CSS.

## Component Tokens
- Raio: 999 px (77 nós) e 9999 px (58) em controles; 28 px (17) em placas; 16 px (16) e 18 px (13); 50% (11) e 22,5% (10) em ícones de app.
- Sombras: `0 0 0 1px rgba(0,0,0,0.1), 0 30px 80px -36px rgba(0,0,0,0.35)` em placas (18 nós; borda de 1 px mais sombra longa com spread negativo); `0 2px 6px rgba(28,28,30,0.16), 0 1px 2px rgba(28,28,30,0.1), 0 0 0 0.5px rgba(28,28,30,0.05)` em controles (14); `inset 0 0 0 0.5px rgba(255,255,255,0.7), 0 1px 3px rgba(0,0,0,0.15)` (13); filete `inset 0 1px 0 rgba(255,255,255,0.9)` em estados de destaque.
- Backdrop: `blur(12px) saturate(1.6)` (13 nós), `blur(12px) brightness(1.06) contrast(1.02) saturate(1.8)` (4), `blur(8px) ...` (3), `blur(2px) saturate(1.5) brightness(1.08)` (2). Dois nós usam `backdrop-filter: url(#lg-lens-...)`: um filtro SVG com `feGaussianBlur` (desvio 6), `feImage` com mapa de deslocamento em PNG embutido (lente) e variante `lg-capsule` (desvio 1,05). Ou seja, a refração real é feita por mapa de deslocamento, e o resto usa blur.
- Movimento: `scale 0.32s cubic-bezier(0.34,1.56,0.64,1)` (mola com overshoot) e `--lg-button-rise 0.32s cubic-bezier(0.32,0.72,0,1)`; cor em 0,18 a 0,2 s.
- Acessibilidade: `:focus-visible` e `prefers-reduced-motion` presentes; atalho de teclado Ctrl+K.
- Parâmetros ao vivo na demonstração: Merge / Flow / Scene (modos do canvas WebGL2 que refrata uma imagem de fundo), Lens (lupa arrastável com borda de vidro).

---

# Taste DNA

### Refração de verdade só onde há forma; blur no resto
- **Trigger**: When the designers faced a material that needs to bend the content behind it on every browser, while real refraction costs far more than a blur.
- **Decision**: They chose to spend an SVG displacement-map lens (`feImage` + `feGaussianBlur`, `backdrop-filter: url(#lg-lens)`) on two hero surfaces and a WebGL2 canvas for the showcase, and a plain `blur(12px) saturate(1.6)` on the other 13 surfaces, over refracting everything or blurring everything.
- **Reason**: Because the eye reads depth from a few edges that visibly bend the scene; the rest only needs to separate from the background, and a uniform effect would cost frames without adding information.
- **Evidence**: 13 nós com `blur(12px) saturate(1.6)` contra 2 com `url(#lg-lens-...)`; legenda: "Android and the web blur what sits behind" e "A WebGL2 canvas that refracts a backdrop image"; `blur(2px) saturate(1.5) brightness(1.08)` nos controles pequenos.

### Saturação e brilho levantados sob o vidro
- **Trigger**: When the designers faced a pale surface that turns grey and dull once the backdrop is blurred.
- **Decision**: They chose to raise saturation (1,5 a 1,8), brightness (1,06 a 1,08) and contrast (1,02) in the backdrop filter over blur alone.
- **Reason**: Because blurring averages colours toward grey; boosting saturation puts the colour of the backdrop back so the coral band behind the glass still reads as coral.
- **Evidence**: Quatro valores de `backdrop-filter` com `saturate(1.5...1.8)`; fundo #FAF9F7 e superfície #FFFFFF em 72,9% da área, com a única massa de cor intensa sendo a faixa laranja atrás dos vidros.

### Sombra longa com spread negativo e filete de 0,5 px
- **Trigger**: When the designers faced panels that must float over a pale page without a heavy grey halo.
- **Decision**: They chose a 1 px ring plus one long, offset shadow with a negative spread (`0 30px 80px -36px` at 35%) for panels, and 0,5 px inner rim lights for controls, over a symmetric blurred shadow.
- **Reason**: Because a negative spread keeps the shadow under the panel instead of around it, which reads as distance from the surface and not as a grey glow.
- **Evidence**: 18 nós com `0 0 0 1px rgba(0,0,0,0.1), 0 30px 80px -36px rgba(0,0,0,0.35)`; 13 nós com `inset 0 0 0 0.5px rgba(255,255,255,0.7)`; sombras de controle de 0,16/0,1/0,05.

### Restraint: um azul só e mola só onde há toque
- **Trigger**: When the designers faced a showcase of 38 glass components that could each carry a colour and a motion.
- **Decision**: They chose one accent blue (#0067D9) on 0,2% of the area and one spring curve (`cubic-bezier(0.34,1.56,0.64,1)`, 0,32 s) for the pressed state over per-component colours and ambient animation.
- **Reason**: Because glass already carries the backdrop's colour; a second source of colour fights it, and motion that only answers a touch keeps the surface still when the user is not acting.
- **Evidence**: Acento #0067D9 em 0,2% da área; verde #14A874 e vermelho #B02D14 apenas em alertas; transições `scale 0.32s` com overshoot só nos botões; `prefers-reduced-motion` presente.
