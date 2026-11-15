package com.estudotron.component;

/**
 * Física da película de vidro líquido de um botão: calcula, para cada pixel da imagem do que está ATRÁS do
 * botão, de onde essa imagem deve ser amostrada (deslocamento = refração). É matemática pura (sem JavaFX),
 * por isso é testável.
 *
 * <p>Coordenadas em pixels lógicos. A imagem tem uma margem ao redor do botão: o rim do vidro puxa conteúdo
 * de fora do botão para dentro, como uma lente de borda grossa.
 *
 * <p>O deslocamento é a soma de quatro termos:
 * <ol>
 *   <li><b>rim</b>: perto da borda, o vidro curva a imagem para dentro (amostra além da borda);</li>
 *   <li><b>lente</b>: ampliação leve e uniforme (a imagem parece mais próxima no centro);</li>
 *   <li><b>bolha do cursor</b>: ampliação gaussiana localizada em volta do cursor, que se acentua ao pressionar;</li>
 *   <li><b>anel do cursor</b>: uma onda radial em volta da bolha (a "borda" da deformação) e um arrasto na
 *       direção do movimento (a película é maleável).</li>
 * </ol>
 * Tudo é multiplicado por {@code strength} (0 = vidro em repouso, 1 = totalmente reativo).
 */
public final class LiquidSurface {

    static final double EDGE_BAND = 11;      // largura da faixa do rim
    static final double EDGE_PULL = 11;      // quanto o rim puxa (px)
    static final double MAGNIFY = 0.045;     // ampliação geral da lente
    static final double BUBBLE_GAIN = 0.65;  // ampliação no centro da bolha
    static final double RING_GAIN = 9;       // amplitude do anel (px)
    static final double DRAG_GAIN = 0.012;   // arrasto por px/s de velocidade do cursor
    static final double DRAG_MAX = 7;        // arrasto máximo (px)

    private final int w;
    private final int h;
    private final int marginLeft;
    private final int marginTop;
    private final int imageWidth;
    private final int imageHeight;
    private final double radius;
    private final double bubbleRadius;
    private final float[] offsetX;
    private final float[] offsetY;

    /**
     * @param w      largura do botão
     * @param h      altura do botão
     * @param radius raio do canto do botão
     */
    public LiquidSurface(int w, int h, double radius, int marginLeft, int marginTop, int marginRight, int marginBottom) {
        this.w = w;
        this.h = h;
        this.radius = Math.min(radius, Math.min(w, h) / 2.0);
        this.marginLeft = marginLeft;
        this.marginTop = marginTop;
        this.imageWidth = w + marginLeft + marginRight;
        this.imageHeight = h + marginTop + marginBottom;
        this.bubbleRadius = Math.max(20, Math.min(34, h * 0.9));
        this.offsetX = new float[imageWidth * imageHeight];
        this.offsetY = new float[imageWidth * imageHeight];
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public int imageWidth() {
        return imageWidth;
    }

    public int imageHeight() {
        return imageHeight;
    }

    public int marginLeft() {
        return marginLeft;
    }

    public int marginTop() {
        return marginTop;
    }

    /** Deslocamento horizontal (px) do pixel (x, y) da imagem. */
    public float offsetX(int x, int y) {
        return offsetX[y * imageWidth + x];
    }

    public float offsetY(int x, int y) {
        return offsetY[y * imageWidth + x];
    }

    /**
     * Recalcula o campo de deslocamento.
     *
     * @param cx       posição do cursor no botão (x, coordenadas locais do botão)
     * @param cy       idem (y)
     * @param vx       velocidade (suavizada) do cursor em px/s
     * @param strength reatividade, de 0 a 1
     * @param press    quanto a película está sendo pressionada, de 0 a 1
     */
    public void compute(double cx, double cy, double vx, double vy, double strength, double press) {
        java.util.Arrays.fill(offsetX, 0f);
        java.util.Arrays.fill(offsetY, 0f);
        if (strength <= 1e-4) {
            return;
        }
        double hw = w / 2.0;
        double hh = h / 2.0;
        double qx = cx - hw;               // cursor relativo ao centro
        double qy = cy - hh;
        double bubbleR = bubbleRadius * (1 + 0.25 * press);
        double dragX = clamp(vx * DRAG_GAIN, -DRAG_MAX, DRAG_MAX);
        double dragY = clamp(vy * DRAG_GAIN, -DRAG_MAX, DRAG_MAX);

        for (int iy = 0; iy < imageHeight; iy++) {
            double py = iy - marginTop - hh;
            for (int ix = 0; ix < imageWidth; ix++) {
                double px = ix - marginLeft - hw;
                // distância assinada ao retângulo arredondado (negativa = dentro) e normal para fora
                double ax = Math.abs(px) - (hw - radius);
                double ay = Math.abs(py) - (hh - radius);
                double ex = Math.max(ax, 0);
                double ey = Math.max(ay, 0);
                double len = Math.hypot(ex, ey);
                double sdf = len + Math.min(Math.max(ax, ay), 0) - radius;
                if (sdf > 1.5) {
                    continue;              // fora do botão: não é visto
                }
                double depth = -sdf;
                double nx;
                double ny;
                if (len > 1e-9) {
                    nx = ex / len * Math.signum(px);
                    ny = ey / len * Math.signum(py);
                } else if (ax > ay) {
                    nx = Math.signum(px);
                    ny = 0;
                } else {
                    nx = 0;
                    ny = Math.signum(py);
                }

                double dx = 0;
                double dy = 0;

                // 1) rim: perto da borda, a imagem é puxada de fora
                if (depth < EDGE_BAND) {
                    double t = clamp(1 - Math.max(depth, 0) / EDGE_BAND, 0, 1);
                    double pull = EDGE_PULL * t * t * strength * (1 + 0.5 * press);
                    dx += nx * pull;
                    dy += ny * pull;
                }
                // 2) lente: leve ampliação geral
                dx -= px * MAGNIFY * strength;
                dy -= py * MAGNIFY * strength;

                // 3) e 4) bolha e anel do cursor
                double vxp = px - qx;
                double vyp = py - qy;
                double r = Math.hypot(vxp, vyp);
                double tt = r / bubbleR;
                double g = Math.exp(-tt * tt);
                double bubble = BUBBLE_GAIN * strength * g * (1 + 0.7 * press);
                dx -= vxp * bubble;
                dy -= vyp * bubble;
                if (r > 1e-6) {
                    double ring = RING_GAIN * strength * (1 + press) * tt * Math.exp((1 - tt * tt) / 2);
                    dx += vxp / r * ring * g;
                    dy += vyp / r * ring * g;
                }
                // arrasto: a película acompanha o movimento com atraso
                dx += dragX * g * strength;
                dy += dragY * g * strength;

                offsetX[iy * imageWidth + ix] = (float) dx;
                offsetY[iy * imageWidth + ix] = (float) dy;
            }
        }
    }

    /**
     * Iluminação da superfície deformada. O campo de deslocamento é a inclinação da película, então dele sai a
     * normal de cada ponto; com ela se calcula (a) o reflexo (branco) onde a superfície encara as luzes (topo-esquerda
     * forte, baixo-direita fraca) e (b) a sombra suave onde ela se afasta da luz. Assim a deformação aparece mesmo
     * sobre um fundo liso: é o relevo óptico da película, não uma mancha seguindo o cursor.
     * Escreve ARGB não pré-multiplicado, w x h.
     */
    public void lighting(int[] argb, double strength) {
        // luz principal (de cima à esquerda) e meia-direção com o observador (0, 0, 1)
        double lx = -0.50;
        double ly = -0.68;
        double lz = 0.52;
        double ln = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= ln;
        ly /= ln;
        lz /= ln;
        double h1x = lx;
        double h1y = ly;
        double h1z = lz + 1;
        double h1n = Math.sqrt(h1x * h1x + h1y * h1y + h1z * h1z);
        h1x /= h1n;
        h1y /= h1n;
        h1z /= h1n;
        // luz secundária (de baixo à direita), mais fraca
        double h2x = 0.34;
        double h2y = 0.40;
        double h2z = 0.85;
        double flat = lz;   // iluminação de uma superfície plana: a sombra só aparece abaixo disso

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y + marginTop) * imageWidth + (x + marginLeft);
                // a película se inclina no sentido do deslocamento de amostragem
                double sx = offsetX[i] * 0.055;
                double sy = offsetY[i] * 0.055;
                double nn = Math.sqrt(sx * sx + sy * sy + 1);
                double nx = sx / nn;
                double ny = sy / nn;
                double nz = 1 / nn;
                double spec1 = Math.pow(Math.max(0, nx * h1x + ny * h1y + nz * h1z), 22);
                double spec2 = Math.pow(Math.max(0, nx * h2x + ny * h2y + nz * h2z), 22);
                double highlight = clamp((spec1 + 0.45 * spec2) * 0.80 * strength, 0, 0.60);
                double facing = nx * lx + ny * ly + nz * lz;
                // só as paredes inclinadas escurecem (crescente nítido), não a deformação inteira (mancha)
                double shade = clamp((flat - facing - 0.04) * 0.95 * strength, 0, 0.13);
                if (highlight >= shade) {
                    argb[y * w + x] = ((int) Math.round(highlight * 255) << 24) | 0x00FFFFFF;
                } else {
                    argb[y * w + x] = ((int) Math.round(shade * 255) << 24) | 0x001E2846;
                }
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
