import { MAX_SHAPES, FS_BG, FS_GLASS, VS } from './shaders';
import { Spring } from './spring';

/** Níveis de material. Solid não passa pelo motor (é DOM opaco). */
export type GlassKind = 'film' | 'lens' | 'primary';

export interface GlassOpts {
  kind: GlassKind;
  /** Raio em px CSS; omitido = cápsula (metade da menor dimensão). */
  radius?: number;
  /** Largura do bisel em px CSS. */
  bevel?: number;
  /** Se true, o elemento afunda (mola) ao ser pressionado. */
  pressable?: boolean;
}

export interface GlassHandle {
  press(on: boolean): void;
  update(opts: GlassOpts): void;
  dispose(): void;
}

interface Rec {
  el: HTMLElement;
  opts: GlassOpts;
  depth: number;
  press: Spring;
}

const DPR_CAP = 2;

/** Níveis de qualidade: resolução do fundo refratado, dispersão cromática e taxa do fundo em repouso. */
const TIERS = {
  low: { bgScale: 0.5, dispersion: false, idleMs: 1000 / 15 },
  medium: { bgScale: 0.75, dispersion: true, idleMs: 1000 / 24 },
  high: { bgScale: 1, dispersion: true, idleMs: 1000 / 30 },
} as const;

/** Parâmetros de material e economia aplicados a partir das preferências do usuário. */
export interface GlassConfig {
  thickness: number;
  tinted: boolean;
  quality: keyof typeof TIERS;
  reduceMotion: boolean;
  /** true = não desenha (material de reserva em CSS no lugar). */
  paused: boolean;
}

export interface EngineEvents {
  /** WebGL perdido/restaurado: a interface troca para o material de reserva (CSS). */
  onFallbackChange(fallback: boolean): void;
}

export class GlassEngine {
  private gl!: WebGL2RenderingContext;
  private progBg!: WebGLProgram;
  private progGlass!: WebGLProgram;
  private bgTex!: WebGLTexture;
  private bgFbo!: WebGLFramebuffer;
  private readonly loc: Record<string, WebGLUniformLocation | null> = {};
  private W = 0;
  private H = 0;
  private ok = false;
  private raf = 0;
  private bgClock = 0;
  private lastFrame = 0;
  private lastDraw = 0;
  private kickUntil = 0;
  private readonly recs: Rec[] = [];
  private readonly light = { x: 0, y: 0, tx: 0, ty: 0, vx: 0, vy: 0 };
  private readonly rects = new Float32Array(MAX_SHAPES * 4);
  private readonly prm = new Float32Array(MAX_SHAPES * 4);
  private readonly prs = new Float32Array(MAX_SHAPES);
  private bgW = 0;
  private bgH = 0;
  private probes: Array<{ x: number; y: number; done: (rgb: number[]) => void }> = [];
  private readonly cleanup: Array<() => void> = [];

  /** Configuração em uso (ver `configure`). */
  readonly cfg: GlassConfig = { thickness: 1, tinted: false, quality: 'high', reduceMotion: false, paused: false };

  /** Telemetria leve para testes e medição (últimos quadros). */
  readonly stats = { frames: 0, dts: [] as number[], shapes: 0, bgClock: 0, bgScale: 1 };

  /** Aplica as preferências de material. Chamável a qualquer momento (efeito no próximo quadro). */
  configure(next: Partial<GlassConfig>): void {
    const prevQuality = this.cfg.quality;
    Object.assign(this.cfg, next);
    if (next.reduceMotion !== undefined) for (const r of this.recs) r.press.retune(this.cfg.reduceMotion ? 1 : 0.62);
    if (this.ok && this.cfg.quality !== prevQuality) this.resizeBg();
    this.stats.bgScale = TIERS[this.cfg.quality].bgScale;
    if (!this.cfg.paused) { this.lastFrame = 0; this.request(); }
  }

  /** Lê um pixel da tela depois do próximo quadro desenhado (para testar o efeito real do material). */
  probe(x: number, y: number): Promise<number[]> {
    return new Promise((resolve) => {
      this.probes.push({ x, y, done: resolve });
      this.request();
    });
  }

  constructor(
    private readonly canvas: HTMLCanvasElement,
    private readonly events: EngineEvents,
    private readonly opts: { alwaysAnimate?: boolean; still?: boolean } = {},
  ) {}

  get ready(): boolean {
    return this.ok;
  }

  /** Lança erro se não houver WebGL2. */
  init(): void {
    const gl = this.canvas.getContext('webgl2', { alpha: false, antialias: false, powerPreference: 'high-performance' });
    if (!gl) throw new Error('WebGL2 indisponível');
    this.gl = gl;
    this.progBg = this.program(VS, FS_BG);
    this.progGlass = this.program(VS, FS_GLASS);
    for (const n of ['uRes', 'uTime']) this.loc['bg_' + n] = gl.getUniformLocation(this.progBg, n);
    for (const n of ['uBg', 'uRes', 'uCount', 'uRect', 'uP', 'uPress', 'uLight', 'uThickness', 'uTint', 'uDisp', 'uDpr']) this.loc['g_' + n] = gl.getUniformLocation(this.progGlass, n);
    gl.bindVertexArray(gl.createVertexArray());
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);
    this.bgTex = gl.createTexture()!;
    this.bgFbo = gl.createFramebuffer()!;
    this.W = this.H = 0;
    this.resize();
    this.ok = true;
  }

  /** Liga os ouvintes globais e a perda de contexto. Chamar uma vez após init(). */
  attach(): void {
    const on = <K extends keyof WindowEventMap>(t: K, f: (e: WindowEventMap[K]) => void, o?: AddEventListenerOptions) => {
      window.addEventListener(t, f, o);
      this.cleanup.push(() => window.removeEventListener(t, f));
    };
    this.light.x = this.light.tx = innerWidth * 0.25;
    this.light.y = this.light.ty = innerHeight * 0.05;
    on('pointermove', (e) => { this.light.tx = e.clientX; this.light.ty = e.clientY; this.request(); }, { passive: true });
    on('resize', () => { if (this.ok) { this.resize(); this.request(); } });
    on('focus', () => { this.lastFrame = 0; this.request(); });
    on('blur', () => this.request());
    const vis = () => { this.lastFrame = 0; if (!document.hidden) this.request(); };
    document.addEventListener('visibilitychange', vis);
    this.cleanup.push(() => document.removeEventListener('visibilitychange', vis));

    const lost = (e: Event) => {
      e.preventDefault();
      this.ok = false;
      if (this.raf) { cancelAnimationFrame(this.raf); this.raf = 0; }
      this.events.onFallbackChange(true);
    };
    const restored = () => {
      try { this.init(); this.events.onFallbackChange(false); this.request(); } catch (err) { console.error(err); }
    };
    this.canvas.addEventListener('webglcontextlost', lost);
    this.canvas.addEventListener('webglcontextrestored', restored);
    this.cleanup.push(() => {
      this.canvas.removeEventListener('webglcontextlost', lost);
      this.canvas.removeEventListener('webglcontextrestored', restored);
    });
  }

  destroy(): void {
    this.ok = false;
    if (this.raf) cancelAnimationFrame(this.raf);
    this.cleanup.forEach((f) => f());
    this.cleanup.length = 0;
  }

  register(el: HTMLElement, opts: GlassOpts): GlassHandle {
    let depth = 0;
    for (let n: HTMLElement | null = el.parentElement; n; n = n.parentElement) depth++;
    const rec: Rec = { el, opts, depth, press: new Spring(0, this.cfg.reduceMotion ? 1 : 0.62, 30) };
    this.recs.push(rec);
    this.recs.sort((a, b) => a.depth - b.depth); // pai antes do filho (o filho desenha por cima)
    this.request();
    return {
      press: (on) => { rec.press.target = on ? 1 : 0; this.request(); },
      update: (o) => { rec.opts = o; this.request(); },
      dispose: () => {
        const i = this.recs.indexOf(rec);
        if (i >= 0) this.recs.splice(i, 1);
        el.style.transform = '';
        this.request();
      },
    };
  }

  request(): void {
    if (!this.raf && this.ok) this.raf = requestAnimationFrame((t) => this.frame(t));
  }

  /** Desenha a cada quadro por `ms` (ex.: enquanto o layout anima em CSS e os retângulos mudam). */
  kick(ms: number): void {
    this.kickUntil = Math.max(this.kickUntil, performance.now() + ms);
    this.request();
  }

  // ---------- internos ----------

  private compile(type: number, src: string): WebGLShader {
    const gl = this.gl;
    const s = gl.createShader(type)!;
    gl.shaderSource(s, src);
    gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('Shader: ' + gl.getShaderInfoLog(s));
    return s;
  }

  private program(vs: string, fs: string): WebGLProgram {
    const gl = this.gl;
    const p = gl.createProgram()!;
    gl.attachShader(p, this.compile(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, this.compile(gl.FRAGMENT_SHADER, fs));
    gl.bindAttribLocation(p, 0, 'aPos');
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('Link: ' + gl.getProgramInfoLog(p));
    return p;
  }

  private resize(): void {
    const gl = this.gl;
    const dpr = Math.min(devicePixelRatio || 1, DPR_CAP);
    const w = Math.max(1, Math.round(innerWidth * dpr));
    const h = Math.max(1, Math.round(innerHeight * dpr));
    if (w === this.W && h === this.H) return;
    this.W = this.canvas.width = w;
    this.H = this.canvas.height = h;
    void gl;
    this.resizeBg();
  }

  /** (Re)cria a textura do fundo refratado na resolução do nível de qualidade (menor = mais barato). */
  private resizeBg(): void {
    const gl = this.gl;
    const s = TIERS[this.cfg.quality].bgScale;
    const w = Math.max(2, Math.round(this.W * s));
    const h = Math.max(2, Math.round(this.H * s));
    if (this.bgTex) gl.deleteTexture(this.bgTex);
    this.bgW = w;
    this.bgH = h;
    this.stats.bgScale = s;
    this.bgTex = gl.createTexture()!;
    gl.bindTexture(gl.TEXTURE_2D, this.bgTex);
    gl.texStorage2D(gl.TEXTURE_2D, Math.floor(Math.log2(Math.max(w, h))) + 1, gl.RGBA8, w, h);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.bgFbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, this.bgTex, 0);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
  }

  private frame(now: number): void {
    this.raf = 0;
    if (!this.ok || document.hidden || this.cfg.paused) return;
    const dt = Math.min((now - (this.lastFrame || now)) / 1000, 0.05);
    this.lastFrame = now;
    const focused = document.hasFocus() || !!this.opts.alwaysAnimate;

    // 1) molas
    let moving = false;
    for (const r of this.recs) {
      if (r.press.step(dt)) moving = true;
      if (r.opts.pressable) r.el.style.transform = `scale(${(1 - 0.045 * r.press.x).toFixed(4)})`;
    }
    const L = this.light;
    const k = 160, c = 24;
    L.vx += (-k * (L.x - L.tx) - c * L.vx) * dt;
    L.vy += (-k * (L.y - L.ty) - c * L.vy) * dt;
    L.x += L.vx * dt;
    L.y += L.vy * dt;
    if (Math.abs(L.x - L.tx) + Math.abs(L.y - L.ty) + Math.abs(L.vx) + Math.abs(L.vy) > 0.05) moving = true;
    if (now < this.kickUntil) moving = true; // layout animando: quadro a quadro, sem o limite de repouso

    // fundo animado só com a janela em foco e sem "reduzir movimento"; em repouso, a taxa do nível de qualidade
    const animateBg = focused && !this.opts.still && !this.cfg.reduceMotion;
    if (animateBg) this.bgClock += dt;
    this.stats.bgClock = this.bgClock;
    if (!moving && animateBg && this.probes.length === 0 && now - this.lastDraw < TIERS[this.cfg.quality].idleMs - 1) { this.request(); return; }
    this.lastDraw = now;

    // 2) ler retângulos do DOM (depois de escrever transforms) e enviar ao shader
    const dpr = this.W / innerWidth;
    let n = 0;
    for (const r of this.recs) {
      if (n >= MAX_SHAPES) break;
      const b = r.el.getBoundingClientRect();
      if (b.width < 2 || b.height < 2 || b.bottom < 0 || b.top > innerHeight || b.right < 0 || b.left > innerWidth) continue;
      const type = r.opts.kind === 'primary' ? 2 : r.opts.kind === 'lens' ? 1 : 0;
      const radius = (r.opts.radius ?? Math.min(b.width, b.height) / 2) * dpr;
      const bevel = (r.opts.bevel ?? 18) * dpr;
      this.rects.set([b.left * dpr, b.top * dpr, b.width * dpr, b.height * dpr], n * 4);
      this.prm.set([radius, bevel, (type ? 14 : 8) * dpr, type], n * 4);
      this.prs[n] = r.press.x;
      n++;
    }
    this.stats.shapes = n;

    // 3) desenhar
    const gl = this.gl, W = this.W, H = this.H, loc = this.loc;
    gl.bindFramebuffer(gl.FRAMEBUFFER, this.bgFbo);
    gl.viewport(0, 0, this.bgW, this.bgH);
    gl.useProgram(this.progBg);
    gl.uniform2f(loc.bg_uRes, this.bgW, this.bgH);
    gl.uniform1f(loc.bg_uTime, this.bgClock);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.bindTexture(gl.TEXTURE_2D, this.bgTex);
    gl.generateMipmap(gl.TEXTURE_2D);

    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, W, H);
    gl.useProgram(this.progGlass);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, this.bgTex);
    gl.uniform1i(loc.g_uBg, 0);
    gl.uniform2f(loc.g_uRes, W, H);
    gl.uniform1i(loc.g_uCount, n);
    gl.uniform4fv(loc.g_uRect, this.rects);
    gl.uniform4fv(loc.g_uP, this.prm);
    gl.uniform1fv(loc.g_uPress, this.prs);
    gl.uniform2f(loc.g_uLight, L.x * dpr, L.y * dpr);
    gl.uniform1f(loc.g_uThickness, this.cfg.thickness);
    gl.uniform1f(loc.g_uTint, this.cfg.tinted ? 1 : 0);
    gl.uniform1f(loc.g_uDisp, TIERS[this.cfg.quality].dispersion ? 1 : 0);
    gl.uniform1f(loc.g_uDpr, dpr);
    gl.drawArrays(gl.TRIANGLES, 0, 3);

    // sondas de pixel (testes): lidas logo após o desenho, antes de o quadro ser composto
    if (this.probes.length) {
      const px = new Uint8Array(4);
      for (const p of this.probes.splice(0)) {
        gl.readPixels(Math.round(p.x * dpr), Math.round(H - p.y * dpr), 1, 1, gl.RGBA, gl.UNSIGNED_BYTE, px);
        p.done([px[0], px[1], px[2]]);
      }
    }

    this.stats.frames++;
    const d = this.stats.dts;
    d.push(dt * 1000);
    if (d.length > 1200) d.splice(0, d.length - 1200);

    if (moving || animateBg) this.request();
  }
}
