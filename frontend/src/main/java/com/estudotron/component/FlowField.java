package com.estudotron.component;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Geometria do "Liquid Flow Background": fios de vidro fluidos, faixas de luz e as linhas de construção fixas.
 * É matemática pura (sem JavaFX): a mesma composição sempre sai da mesma semente, e cada curva é uma função
 * contínua do tempo, o que garante movimento orgânico e sem saltos.
 *
 * <p>Cada fio é {@code y(x, t) = H·baseY + H·tilt·(x/W − ½) + A1·sin(2π·c1·x/W + φ1 + v1·t) + A2·sin(...)}:
 * duas ondas lentas somadas (a segunda, menor e mais curta, dá o aspecto líquido). Os fios nascem em feixes de
 * três, quase paralelos, que se afastam e se aproximam devagar, como fibras de vidro flutuando.
 */
public final class FlowField {

    public enum Tint { SILVER, WHITE, IRIDESCENT, TUBE }

    /**
     * Um fio. Alturas e amplitudes em fração da altura ({@code baseY}, {@code tilt}) ou em pixels (amplitudes,
     * {@code offset}). Velocidades em radianos por segundo (movimento muito lento: períodos de 1 a 2 minutos).
     */
    public record Strand(int bundle, double baseY, double offset, double tilt,
                         double amp1, double cycles1, double speed1, double phase1,
                         double amp2, double cycles2, double speed2, double phase2,
                         double width, double alpha, Tint tint) {}

    /** Faixa de luz translúcida que acompanha um fio. */
    public record Band(int strandIndex, double width, double alpha) {}

    private static final double TWO_PI = Math.PI * 2;
    private static final int BUNDLES = 14;

    private final List<Strand> strands = new ArrayList<>();
    private final List<Band> bands = new ArrayList<>();
    private final double[] hairlinesH = {0.09, 0.19, 0.30, 0.41, 0.52, 0.63, 0.74, 0.85, 0.94};
    private final double[] hairlinesV = {0.11, 0.27, 0.44, 0.61, 0.78, 0.92};

    public FlowField(long seed) {
        Random random = new Random(seed);
        for (int b = 0; b < BUNDLES; b++) {
            double baseY = (b + 0.5) / BUNDLES + (random.nextDouble() - 0.5) * 0.03;
            double tilt = (random.nextDouble() - 0.5) * 0.18;
            double amp1 = 16 + random.nextDouble() * 22;
            double cycles1 = 0.55 + random.nextDouble() * 0.8;
            double speed1 = (0.045 + random.nextDouble() * 0.06) * (random.nextBoolean() ? 1 : -1);
            double phase1 = random.nextDouble() * TWO_PI;
            double amp2 = 5 + random.nextDouble() * 8;
            double cycles2 = 1.9 + random.nextDouble() * 1.3;
            double speed2 = (0.07 + random.nextDouble() * 0.08) * (random.nextBoolean() ? 1 : -1);
            double phase2 = random.nextDouble() * TWO_PI;
            boolean iridescent = b % 3 == 1;
            // feixe de três fibras: prata fina, branca (reflexo) e prata clara; a do meio pode ser iridescente
            for (int i = -1; i <= 1; i++) {
                Tint tint = i == 0 ? (iridescent ? Tint.IRIDESCENT : Tint.SILVER) : (i < 0 ? Tint.WHITE : Tint.SILVER);
                double width = i == 0 ? 1.6 : (i < 0 ? 1.7 : 1.3);
                double alpha = i == 0 ? 0.62 : (i < 0 ? 0.95 : 0.38);
                strands.add(new Strand(b, baseY, i * 6.5, tilt,
                        amp1 * (1 + 0.05 * i), cycles1, speed1, phase1 + 0.07 * i,
                        amp2, cycles2, speed2, phase2 + 0.12 * i, width, alpha, tint));
            }
            if (b % 2 == 0) {   // faixas de luz em feixes alternados (a fibra do meio é a 2ª de cada feixe)
                bands.add(new Band(b * 3 + 1, 54 + random.nextDouble() * 40, 0.30));
            }
        }
        // fios longos e solitários, de grande amplitude, que cruzam os feixes (entre eles: nenhum botão fica sem linha)
        double[] wireY = {0.08, 0.22, 0.35, 0.50, 0.64, 0.78, 0.91};
        for (int w = 0; w < wireY.length; w++) {
            strands.add(new Strand(BUNDLES + w, wireY[w], 0, w % 2 == 0 ? 0.12 : -0.12,
                    36 + 7 * (w % 4), 0.45 + 0.08 * (w % 5), 0.038 * (w % 2 == 0 ? 1 : -1), random.nextDouble() * TWO_PI,
                    9, 1.7, 0.06, random.nextDouble() * TWO_PI, 1.2, 0.46, Tint.SILVER));
        }
        // tubos de vidro: faixas grossas (6 px) com núcleo branco; curvam de forma bem visível na refração
        double[] tubeY = {0.05, 0.19, 0.42, 0.60, 0.77, 0.93};
        for (int k = 0; k < tubeY.length; k++) {
            strands.add(new Strand(BUNDLES + wireY.length + k, tubeY[k], 0, k % 2 == 0 ? -0.10 : 0.10,
                    28 + 6 * (k % 3), 0.5 + 0.07 * k, 0.035 * (k % 2 == 0 ? 1 : -1), random.nextDouble() * TWO_PI,
                    7, 1.5, 0.05, random.nextDouble() * TWO_PI, 6, 1.0, Tint.TUBE));
        }
    }

    public List<Strand> strands() {
        return strands;
    }

    public List<Band> bands() {
        return bands;
    }

    /** Frações da altura das linhas de construção horizontais. */
    public double[] hairlinesH() {
        return hairlinesH;
    }

    /** Frações da largura das linhas de construção verticais. */
    public double[] hairlinesV() {
        return hairlinesV;
    }

    /** Altura (px) do fio na coluna {@code x} no instante {@code t} (segundos), numa área de w x h. */
    public static double y(Strand s, double x, double t, double w, double h) {
        double u = w <= 0 ? 0 : x / w;
        return h * s.baseY() + s.offset() + h * s.tilt() * (u - 0.5)
                + s.amp1() * Math.sin(TWO_PI * s.cycles1() * u + s.phase1() + s.speed1() * t)
                + s.amp2() * Math.sin(TWO_PI * s.cycles2() * u + s.phase2() + s.speed2() * t);
    }
}
