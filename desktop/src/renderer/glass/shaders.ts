export const MAX_SHAPES = 40;

export const VS = `#version 300 es
in vec2 aPos; void main(){ gl_Position = vec4(aPos, 0.0, 1.0); }`;

/**
 * Passe 1: o que o vidro refrata. Fios de vidro (3 fibras, reflexo iridescente), faixas de luz suaves
 * e linhas finas de construção. Linhas finas são essenciais: blur não curva linhas, refração sim.
 */
export const FS_BG = `#version 300 es
precision highp float;
uniform vec2 uRes; uniform float uTime; out vec4 o;
void main(){
  vec2 p = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
  float W = uRes.x, H = uRes.y;
  vec3 col = vec3(0.958, 0.958, 0.968);
  float b1 = H*0.62 + sin(p.x*0.0021 + uTime*0.10)*H*0.16;
  col = mix(col, vec3(1.0, 0.78, 0.70), smoothstep(150.0, 0.0, abs(p.y-b1)) * 0.55);
  float b2 = H*0.28 + sin(p.x*0.0016 - uTime*0.08 + 1.7)*H*0.14;
  col = mix(col, vec3(0.78, 0.84, 0.98), smoothstep(130.0, 0.0, abs(p.y-b2)) * 0.5);
  for (int i = 0; i < 6; i++) {
    float fi = float(i);
    float base = H*(0.10 + 0.16*fi);
    float y = base + sin(p.x*(0.0030 + 0.0004*fi) + uTime*(0.07 + 0.012*fi) + fi*1.9)*H*0.09
                   + sin(p.x*0.011 - uTime*0.05 + fi)*5.0;
    for (int k = -1; k <= 1; k++) {
      float d = abs(p.y - (y + float(k)*5.0));
      float line = smoothstep(1.3, 0.0, d);
      float irid = 0.5 + 0.5*sin(p.x*0.004 + fi*2.0 + uTime*0.12);
      vec3 tint = mix(vec3(0.62,0.80,0.84), vec3(0.86,0.66,0.82), irid);
      col = mix(col, mix(vec3(0.60,0.62,0.67), tint, 0.5), line * (k==0 ? 0.75 : 0.45));
    }
  }
  for (int i = 0; i < 5; i++) {
    float fi = float(i);
    float y = 12.0 + 11.0*fi + sin(p.x*0.0055 + fi*1.3 + uTime*0.14)*9.0;
    for (int k = -1; k <= 1; k++) {
      float d = abs(p.y - (y + float(k)*3.0));
      float line = smoothstep(1.2, 0.0, d);
      float irid = 0.5 + 0.5*sin(p.x*0.005 + fi*1.7 + uTime*0.15);
      vec3 tint = mix(vec3(0.45,0.74,0.80), vec3(0.84,0.52,0.74), irid);
      float zone = smoothstep(W*0.50, W*0.62, p.x);   // só atrás dos botões do canto superior direito
      col = mix(col, mix(vec3(0.42,0.44,0.50), tint, 0.55), line * zone * (k==0 ? 0.85 : 0.5));
    }
  }
  vec2 g = abs(fract(p/96.0 + 0.5) - 0.5) * 96.0;
  col = mix(col, vec3(0.20,0.21,0.25), (1.0 - smoothstep(0.0, 1.1, min(g.x, g.y))) * 0.10);
  float tube = abs(p.y - (H*0.88 + sin(p.x*0.0025 + uTime*0.06)*H*0.05));
  col = mix(col, vec3(1.0), smoothstep(7.0, 2.0, tube) * 0.9);
  col = mix(col, vec3(0.70,0.72,0.77), smoothstep(8.5, 6.5, tube) * smoothstep(5.5, 7.5, tube) * 0.5);
  o = vec4(col, 1.0);
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
    if (d > 1.0) continue;
    float type = uP[i].w, B = max(uP[i].y, 2.0), thick = uP[i].z;
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
      vec3 iors = vec3(ior - disp, ior, ior + disp), acc = vec3(0.0);
      for (int k = 0; k < 3; k++) {
        vec3 R = refract(vec3(0.0,0.0,-1.0), N, 1.0 / iors[k]);
        vec2 off = R.xy / max(abs(R.z), 0.2) * thick * 3.4;
        vec2 mag = (p - c) * 0.10 * smoothstep(0.0, 1.0, t);
        acc[k] = bgAt(p - off - mag, 0.0)[k];
      }
      rgb = acc;
      float lum = dot(rgb, vec3(0.299,0.587,0.114));
      rgb = mix(vec3(lum), rgb, 1.5);
      rgb = type > 1.5 ? mix(rgb, vec3(0.17,0.18,0.20), 0.66) : mix(rgb, vec3(1.0), 0.04);
      rgb *= 1.0 - 0.10 * pow(1.0 - t, 2.5) * smoothstep(-2.0, 2.0, -d);
    } else {
      rgb = bgAt(p, 2.8);
      float lum = dot(rgb, vec3(0.299,0.587,0.114));
      rgb = mix(vec3(lum), rgb, 1.5);
      rgb = mix(rgb, vec3(1.0), 0.24);
    }
    rgb *= 1.03;
    float spec = pow(max(dot(N, H), 0.0), 60.0) * (type > 0.5 ? (type > 1.5 ? 0.6 : 1.0) : 0.25);
    float rimBand = smoothstep(2.2, 0.0, -d);
    float lit = abs(dot(g, normalize(L.xy + 1e-4)));
    float rim = rimBand * (0.25 + 0.75*lit) * (type > 0.5 ? 0.85 : 0.6);
    rgb += spec*0.9 + rim;
    col = mix(col, rgb, clamp(0.5 - d, 0.0, 1.0));
  }
  o = vec4(col, 1.0);
}`;
