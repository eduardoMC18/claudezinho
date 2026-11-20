export const MAX_SHAPES = 40;

export const VS = `#version 300 es
in vec2 aPos; void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }`;

/**
 * Passe 1: o ambiente que o vidro refrata e reflete. Off-white frio com variações de luminosidade MUITO suaves
 * (uma poça de luz no canto superior esquerdo, uma sombra fria ampla embaixo à direita, um destaque pequeno e
 * difuso e um leve gradiente vertical). Sem linhas, grade, ondas, bordas luminosas nem cor saturada: o fundo existe
 * para dar profundidade, não para competir com o conteúdo. A deriva é lenta e some com "reduzir movimento".
 * Amplitude total ≈ 13 níveis de 255; dithering de ±0,5 nível evita faixas (banding) nos degradês.
 */
export const FS_BG = `#version 300 es
precision highp float;
uniform vec2 uRes; uniform float uTime; out vec4 o;
float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }
void main(){
  vec2 p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
  vec2 uv = p / uRes;
  float asp = uRes.x / uRes.y;
  float t = uTime;
  vec3 col = vec3(0.944, 0.945, 0.953);                      // off-white frio (≈ #F1F1F3)
  vec2 d1 = (uv - vec2(0.20 + 0.025*sin(t*0.045), 0.08 + 0.020*cos(t*0.037))) * vec2(asp, 1.0);
  col += vec3(0.034, 0.034, 0.031) * exp(-dot(d1, d1) * 2.4);   // poça de luz ampla (topo-esquerda)
  vec2 d2 = (uv - vec2(0.88 + 0.020*cos(t*0.040), 0.95 + 0.015*sin(t*0.033))) * vec2(asp, 1.0);
  col -= vec3(0.020, 0.017, 0.010) * exp(-dot(d2, d2) * 1.6);   // sombra fria (base-direita)
  vec2 d3 = (uv - vec2(0.62 + 0.030*sin(t*0.050 + 1.0), 0.14)) * vec2(asp, 1.0);
  col += vec3(0.018) * exp(-dot(d3, d3) * 38.0);                // destaque pequeno e difuso
  col += (0.5 - uv.y) * 0.010;                                  // profundidade: topo um pouco mais claro
  col += (hash(p) - 0.5) / 255.0;                               // dithering
  o = vec4(clamp(col, 0.0, 1.0), 1.0);
}`;

/**
 * Passe 2: fundo + vidros. Tipos: 0 film (blur por LOD, sem refração), 1 lens (refração com bisel,
 * dispersão, especular, rim), 2 lens primária (graphite translúcido).
 */
export const FS_GLASS = `#version 300 es
precision highp float;
#define MAXS ${MAX_SHAPES}
uniform sampler2D uBg; uniform vec2 uRes; uniform int uCount;
uniform vec4 uRect[MAXS];
uniform vec4 uP[MAXS];       // raio, bisel, espessura, tipo
uniform float uPress[MAXS];
uniform vec2 uLight;
uniform float uThickness;   // espessura como múltiplo do normal (0,25–2)
uniform float uTint;        // 0 = clear, 1 = tinted (mais véu branco)
uniform float uDisp;        // 1 = qualidade média/alta (dispersão cromática e sombra), 0 = baixa
uniform float uDpr;         // pixels do dispositivo por pixel CSS
out vec4 o;
vec3 bgAt(vec2 px, float lod){ vec2 q = px / uRes; return textureLod(uBg, vec2(q.x, 1.0 - q.y), lod).rgb; }
float sdRR(vec2 p, vec2 b, float r){ vec2 q = abs(p) - b + r; return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r; }
void main(){
  vec2 p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
  vec3 col = bgAt(p, 0.0);
  for (int i = 0; i < MAXS; i++) {
    if (i >= uCount) break;
    vec2 c = uRect[i].xy + uRect[i].zw*0.5, b = uRect[i].zw*0.5;
    float r = min(uP[i].x, min(b.x, b.y));
    float d = sdRR(p - c, b, r);
    if (d > 1.0) {
      // sombra de camada única: longa, suave, deslocada para baixo e com espalhamento negativo (sem halo cinza)
      if (uDisp > 0.5 && d < 30.0 * uDpr) {
        float ds = sdRR(p - c - vec2(0.0, 10.0 * uDpr), b - 6.0 * uDpr, max(r - 6.0 * uDpr, 1.0));
        col *= 1.0 - (1.0 - smoothstep(-2.0 * uDpr, 24.0 * uDpr, ds)) * 0.06;
      }
      continue;
    }
    float type = uP[i].w, B = max(uP[i].y, 2.0), thick = uP[i].z * uThickness;
    float t = clamp(-d / B, 0.0, 1.0);
    vec2 e = vec2(1.0, 0.0);
    vec2 g = normalize(vec2(sdRR(p-c+e.xy,b,r) - sdRR(p-c-e.xy,b,r), sdRR(p-c+e.yx,b,r) - sdRR(p-c-e.yx,b,r)) + vec2(1e-5));
    float f = sqrt(max(1.0 - (1.0-t)*(1.0-t), 0.0));
    float fp = min((1.0 - t) / max(f, 0.05), 6.0);
    vec3 N = normalize(vec3(thick * fp / B * g, 1.0));
    vec3 L = normalize(vec3(normalize(uLight - c) * 0.65, 0.75));
    vec3 H = normalize(L + vec3(0.0, 0.0, 1.0));
    vec3 rgb;
    float press = uPress[i];
    if (type > 0.5) {
      float ior = 1.42 + 0.10*press, disp = 0.035 + 0.03*press;
      vec2 mag = (p - c) * 0.10 * smoothstep(0.0, 1.0, t);
      if (uDisp > 0.5) {
        vec3 iors = vec3(ior - disp, ior, ior + disp), acc = vec3(0.0);
        for (int k = 0; k < 3; k++) {
          vec3 R = refract(vec3(0.0,0.0,-1.0), N, 1.0 / iors[k]);
          vec2 off = R.xy / max(abs(R.z), 0.2) * thick * 3.4;
          acc[k] = bgAt(p - off - mag, 0.0)[k];
        }
        rgb = acc;
      } else {
        vec3 R = refract(vec3(0.0,0.0,-1.0), N, 1.0 / ior);
        vec2 off = R.xy / max(abs(R.z), 0.2) * thick * 3.4;
        rgb = bgAt(p - off - mag, 0.0);
      }
      float lum = dot(rgb, vec3(0.299,0.587,0.114));
      rgb = mix(vec3(lum), rgb, 1.5);
      rgb = type > 1.5 ? mix(rgb, vec3(0.17,0.18,0.20), 0.66) : mix(rgb, vec3(1.0), 0.04 + 0.14*uTint);
      rgb *= 1.0 - 0.10 * pow(1.0 - t, 2.5) * smoothstep(-2.0, 2.0, -d);
    } else {
      rgb = bgAt(p, 2.8);
      float lum = dot(rgb, vec3(0.299,0.587,0.114));
      rgb = mix(vec3(lum), rgb, 1.5);
      rgb = mix(rgb, vec3(1.0), 0.09 + 0.24*uTint);
      rgb *= 1.0 - 0.05 * pow(1.0 - t, 2.2);               // borda interna levemente mais densa: dá espessura à placa
    }
    rgb *= 1.012;
    float spec = pow(max(dot(N, H), 0.0), 60.0) * (type > 0.5 ? (type > 1.5 ? 0.16 : 1.0) : 0.25);
    float rimBand = smoothstep(2.2, 0.0, -d);
    float lit = abs(dot(g, normalize(L.xy + 1e-4)));
    float rim = rimBand * (0.25 + 0.75*lit) * (type > 1.5 ? 0.45 : (type > 0.5 ? 0.85 : 0.42));
    float away = 0.5 - 0.5 * dot(g, normalize(L.xy + 1e-4));   // 1 no lado oposto à luz
    rgb -= smoothstep(1.8, 0.0, -d) * away * 0.05;              // filete escuro no lado sem luz (aresta definida)
    rgb += spec*0.9 + rim;
    col = mix(col, rgb, clamp(0.5 - d, 0.0, 1.0));
  }
  o = vec4(col, 1.0);
}`;
