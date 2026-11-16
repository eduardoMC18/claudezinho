/** Mola amortecida com passo fixo (Euler semi-implícito). ζ = amortecimento, ω = frequência natural (rad/s). */
export class Spring {
  x: number;
  v = 0;
  target: number;
  private readonly k: number;
  private readonly c: number;
  private acc = 0;
  private static readonly H = 1 / 240;

  constructor(value: number, zeta: number, omega: number) {
    this.x = value;
    this.target = value;
    this.k = omega * omega;
    this.c = 2 * zeta * omega;
  }

  get atRest(): boolean {
    return Math.abs(this.x - this.target) < 1e-3 && Math.abs(this.v) < 1e-3;
  }

  /** Avança dt segundos (limitado a 50 ms). Retorna true enquanto ainda se move. */
  step(dt: number): boolean {
    if (this.atRest) {
      this.x = this.target;
      this.v = 0;
      return false;
    }
    this.acc += Math.min(dt, 0.05);
    while (this.acc >= Spring.H) {
      this.v += (-this.k * (this.x - this.target) - this.c * this.v) * Spring.H;
      this.x += this.v * Spring.H;
      this.acc -= Spring.H;
    }
    return !this.atRest;
  }
}
