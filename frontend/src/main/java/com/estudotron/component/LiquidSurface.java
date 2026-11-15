package com.estudotron.component;

/**
 * Física da película de vidro líquido de um botão/cartão: calcula, para cada ponto da imagem do que está ATRÁS
 * dele, de onde essa imagem deve ser amostrada (deslocamento = refração). É matemática pura (sem JavaFX), por isso é
 * testável.
 *
 * <p>Coordenadas em pixels lógicos. A imagem tem uma margem ao redor do nó: o rim do vidro puxa conteúdo de fora
 * para dentro, como uma lente de borda grossa.
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
 *
 * <p><b>Resolução.</b> Para nós grandes (cartões) o campo é calculado numa grade mais grossa ({@code step} px por
 * célula): a deformação é suave, então a diferença é imperceptível e o custo cai com o quadrado do passo.
 */
public final class LiquidSurface {

    static final double EDGE_BAND = 11;      // largura da faixa do rim
    static final double EDGE_PULL = 11;      // quanto o rim puxa (px)
    static final double MAGNIFY = 0.045;     // ampliação geral da lente
    static final double MAGNIFY_MAX = 3.5;   // limite da ampliação: em cartões grandes uma lente linear criaria uma inclinação enorme (mancha)
    static final double BUBBLE_GAIN = 0.65;  // ampliação no centro da bolha
    static final double RING_GAIN = 9;       // amplitude do anel (px)
    static final double DRAG_GAIN = 0.012;   // arrasto por px/s de velocidade do cursor
    static final double DRAG_MAX = 7;        // arrasto máximo (px)

    private final int w;
    private final int h;
    private final int step;
    private final int marginLeft;
    private final int marginTop;
    private final int imageWidth;
    private final int imageHeight;
    private final int cellsX;
    private final int cellsY;
    private final double radius;
    private final double bubbleRadius;
    private final float[] offsetX;
    private final float[] offsetY;

    /**
     * Passo de resolução adequado ao tamanho do nó: 1 para botões, mais grosso para cartões grandes.
     * Mantém o campo em no máximo ~30 mil células.
     */
    public static int stepFor(int w, int h) {
        double area = (double) w * h;
        int step = 1;
        while (area / ((double) step * step) > 30_000) {
            step++;
        }
        return step;
    }

    public LiquidSurface(int w, int h, double radius, int marginLeft, int marginTop, int marginRight, int marginBottom) {
        this(w, h, radius, marginLeft, marginTop, marginRight, marginBottom, 1);
    }

    /**
     * @param w      largura do nó
     * @param h      altura do nó
     * @param radius raio do canto do nó
     * @param step   pixels por célula do campo (1 = um valor por pixel)
     */
    public LiquidSurface(int w, int h, double radius, int marginLeft, int marginTop, int marginRight,
                         int marginBottom, int step) {
        this.w = w;
        this.h = h;
        this.step = Math.max(1, step);
        this.radius = Math.min(radius, Math.min(w, h) / 2.0);
        this.marginLeft = marginLeft;
        this.marginTop = marginTop;
        this.imageWidth = w + marginLeft + marginRight;
        this.imageHeight = h + marginTop + marginBottom;
        this.cellsX = (imageWidth + this.step - 1) / this.step;
        this.cellsY = (imageHeight + this.step - 1) / this.step;
        this.bubbleRadius = Math.max(20, Math.min(34, Math.min(w, h) * 0.9));
        this.offsetX = new float[cellsX * cellsY];
        this.offsetY = new float[cellsX * cellsY];
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public int step() {
        return step;
    }

    public int imageWidth() {
        return imageWidth;
    }

    public int imageHeight() {
        return imageHeight;
    }

    /** Células do campo (é este o tamanho do {@code FloatMap}). */
    public int cellsX() {
        return cellsX;
    }

    public int cellsY() {
        return cellsY;
    }

    public int marginLeft() {
        return marginLeft;
    }

    public int marginTop() {
        return marginTop;
    }

    /** Deslocamento horizontal (px) do pixel (x, y) da imagem. */
    public float offsetX(int x, int y) {
        return offsetX[cell(x, y)];
    }

    public float offsetY(int x, int y) {
        return offsetY[cell(x, y)];
    }

    /** Deslocamento (px) da célula (cx, cy) do campo. */
    public float cellOffsetX(int cx, int cy) {
        return offsetX[cy * cellsX + cx];
    }

    public float cellOffsetY(int cx, int cy) {
        return offsetY[cy * cellsX + cx];
    }

    private int cell(int x, int y) {
        int cx = Math.min(cellsX - 1, x / step);
        int cy = Math.min(cellsY - 1, y / step);
        return cy * cellsX + cx;
    }

    /**
     * Recalcula o campo de deslocamento.
     *
     * @param cx       posição do cursor no nó (x, coordenadas locais)
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
        double half = (step - 1) / 2.0;    // centro da célula, em pixels da imagem

        for (int cellY = 0; cellY < cellsY; cellY++) {
            double py = cellY * step + half - marginTop - hh;
            for (int cellX = 0; cellX < cellsX; cellX++) {
                double px = cellX * step + half - marginLeft - hw;
                // distância assinada ao retângulo arredondado (negativa = dentro) e normal para fora
                double ax = Math.abs(px) - (hw - radius);
                double ay = Math.abs(py) - (hh - radius);
                double ex = Math.max(ax, 0);
                double ey = Math.max(ay, 0);
                double len = Math.hypot(ex, ey);
                double sdf = len + Math.min(Math.max(ax, ay), 0) - radius;
                if (sdf > 1.5 + step) {
                    continue;              // fora do nó: não é visto
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
                dx -= clamp(px * MAGNIFY, -MAGNIFY_MAX, MAGNIFY_MAX) * strength;
                dy -= clamp(py * MAGNIFY, -MAGNIFY_MAX, MAGNIFY_MAX) * strength;

                // 3) e 4) bolha e anel do cursor
                double vxp = px - qx;
                double vyp = py - qy;
                double r = Math.hypot(vxp, vyp);
                double tt = r / bubbleR;
                if (tt < 3.2) {            // fora disso a gaussiana é ~0
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
                }

                offsetX[cellY * cellsX + cellX] = (float) dx;
                offsetY[cellY * cellsX + cellX] = (float) dy;
            }
        }
    }

    /**
     * Expande o campo (grade grossa) para um deslocamento por pixel da imagem, com interpolação bilinear. O
     * {@code FloatMap} do JavaFX NÃO é esticado sobre a imagem (é aplicado pixel a pixel a partir do canto), então o
     * mapa entregue ao efeito precisa ter a resolução da imagem. Com {@code step == 1} é uma cópia.
     *
     * @param dx saída, {@code imageWidth * imageHeight} valores (px)
     * @param dy idem
     */
    public void expand(float[] dx, float[] dy) {
        if (step == 1) {
            System.arraycopy(offsetX, 0, dx, 0, offsetX.length);
            System.arraycopy(offsetY, 0, dy, 0, offsetY.length);
            return;
        }
        double half = (step - 1) / 2.0;
        int[] x0 = new int[imageWidth];
        float[] xw = new float[imageWidth];
        for (int x = 0; x < imageWidth; x++) {
            double f = (x - half) / step;
            int i = (int) Math.floor(f);
            x0[x] = Math.max(0, Math.min(cellsX - 1, i));
            xw[x] = (i < 0 || i >= cellsX - 1) ? 0f : (float) (f - i);
        }
        for (int y = 0; y < imageHeight; y++) {
            double f = (y - half) / step;
            int j = (int) Math.floor(f);
            int row0 = Math.max(0, Math.min(cellsY - 1, j));
            int row1 = Math.min(cellsY - 1, row0 + 1);
            float wy = (j < 0 || j >= cellsY - 1) ? 0f : (float) (f - j);
            for (int x = 0; x < imageWidth; x++) {
                int c0 = x0[x];
                int c1 = Math.min(cellsX - 1, c0 + 1);
                float wx = xw[x];
                int a00 = row0 * cellsX + c0;
                int a01 = row0 * cellsX + c1;
                int a10 = row1 * cellsX + c0;
                int a11 = row1 * cellsX + c1;
                int out = y * imageWidth + x;
                dx[out] = lerp(lerp(offsetX[a00], offsetX[a01], wx), lerp(offsetX[a10], offsetX[a11], wx), wy);
                dy[out] = lerp(lerp(offsetY[a00], offsetY[a01], wx), lerp(offsetY[a10], offsetY[a11], wx), wy);
            }
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Largura da imagem de reflexos: uma célula por {@code step} pixels do nó. */
    public int lightWidth() {
        return (w + step - 1) / step;
    }

    public int lightHeight() {
        return (h + step - 1) / step;
    }

    /**
     * Iluminação da superfície deformada. O campo de deslocamento é a inclinação da película, então dele sai a
     * normal de cada ponto; com ela se calcula (a) o reflexo (branco) onde a superfície encara as luzes (topo-esquerda
     * forte, baixo-direita fraca) e (b) a sombra suave onde ela se afasta da luz. Assim a deformação aparece mesmo
     * sobre um fundo liso: é o relevo óptico da película, não uma mancha seguindo o cursor.
     * Escreve ARGB não pré-multiplicado, {@link #lightWidth()} x {@link #lightHeight()}.
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
        // reflexo de uma superfície plana (normal 0,0,1): é o nível de base, descontado para que o plano fique limpo
        double flatSpec1 = Math.pow(h1z, 22);
        double flatSpec2 = Math.pow(h2z, 22);
        int lw = lightWidth();
        int lh = lightHeight();
        double hw = w / 2.0;
        double hh = h / 2.0;
        double half = (step - 1) / 2.0;

        for (int y = 0; y < lh; y++) {
            for (int x = 0; x < lw; x++) {
                int cellX = Math.min(cellsX - 1, (marginLeft + x * step + step / 2) / step);
                int cellY = Math.min(cellsY - 1, (marginTop + y * step + step / 2) / step);
                int i = cellY * cellsX + cellX;
                // A lente (ampliação geral) é um deslocamento grande e uniforme em metade do nó: como inclinação
                // viraria uma sombra enorme e constante. A iluminação usa só o rim e a bolha do cursor.
                double px = cellX * step + half - marginLeft - hw;
                double py = cellY * step + half - marginTop - hh;
                double lensX = -clamp(px * MAGNIFY, -MAGNIFY_MAX, MAGNIFY_MAX) * strength;
                double lensY = -clamp(py * MAGNIFY, -MAGNIFY_MAX, MAGNIFY_MAX) * strength;
                // a película se inclina no sentido do deslocamento de amostragem
                double sx = (offsetX[i] - lensX) * 0.055;
                double sy = (offsetY[i] - lensY) * 0.055;
                double nn = Math.sqrt(sx * sx + sy * sy + 1);
                double nx = sx / nn;
                double ny = sy / nn;
                double nz = 1 / nn;
                double spec1 = Math.pow(Math.max(0, nx * h1x + ny * h1y + nz * h1z), 22);
                double spec2 = Math.pow(Math.max(0, nx * h2x + ny * h2y + nz * h2z), 22);
                double highlight = clamp(Math.max(0, (spec1 - flatSpec1) + 0.45 * (spec2 - flatSpec2)) * 0.80 * strength, 0, 0.60);
                double facing = nx * lx + ny * ly + nz * lz;
                // só as paredes inclinadas escurecem (crescente nítido), não a deformação inteira (mancha)
                double shade = clamp((flat - facing - 0.04) * 0.95 * strength, 0, 0.13);
                if (highlight >= shade) {
                    argb[y * lw + x] = ((int) Math.round(highlight * 255) << 24) | 0x00FFFFFF;
                } else {
                    argb[y * lw + x] = ((int) Math.round(shade * 255) << 24) | 0x001E2846;
                }
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
