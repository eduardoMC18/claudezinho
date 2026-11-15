package com.estudotron.component;

import com.estudotron.component.FlowField.Strand;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O fundo precisa ser determinístico, contínuo, lento e cobrir a tela (para a refração dos botões ter o que mostrar). */
class FlowFieldTest {

    private static final double W = 1320;
    private static final double H = 820;

    private final FlowField field = new FlowField(42);

    @Test
    void sameSeedGivesTheSameComposition() {
        FlowField other = new FlowField(42);
        assertEquals(field.strands().size(), other.strands().size());
        for (int i = 0; i < field.strands().size(); i++) {
            assertEquals(FlowField.y(field.strands().get(i), 300, 12, W, H),
                    FlowField.y(other.strands().get(i), 300, 12, W, H));
        }
    }

    @Test
    void motionIsSlowAndContinuous() {
        double maxSpeed = 0;        // px/s
        double maxSpatialStep = 0;  // salto entre colunas vizinhas (continuidade no espaço)
        for (Strand s : field.strands()) {
            for (double x = 0; x <= W; x += 20) {
                double a = FlowField.y(s, x, 10, W, H);
                double b = FlowField.y(s, x, 10.05, W, H);   // 50 ms depois (um quadro a 20 fps)
                maxSpeed = Math.max(maxSpeed, Math.abs(b - a) / 0.05);
                maxSpatialStep = Math.max(maxSpatialStep, Math.abs(FlowField.y(s, x + 2, 10, W, H) - a));
            }
        }
        assertTrue(maxSpeed < 8, "movimento discreto (px/s): " + maxSpeed);
        assertTrue(maxSpatialStep < 6, "curva suave, sem quebras: " + maxSpatialStep);
    }

    @Test
    void curvesStayInsideTheComposition() {
        for (Strand s : field.strands()) {
            for (double t = 0; t < 600; t += 37) {
                for (double x = 0; x <= W; x += 110) {
                    double y = FlowField.y(s, x, t, W, H);
                    assertTrue(y > -120 && y < H + 120, "fio fora da composição: " + y);
                }
            }
        }
    }

    @Test
    void theLinesCrossAnyButtonSizedRegion() {
        // Retângulos do tamanho de um botão (150 x 34) em posições aleatórias: a grande maioria deve ter um fio ou
        // uma linha de construção passando por dentro, senão não há o que refratar.
        Random random = new Random(7);
        int hits = 0;
        int trials = 400;
        for (int n = 0; n < trials; n++) {
            double x0 = random.nextDouble() * (W - 150);
            double y0 = random.nextDouble() * (H - 34);
            double t = random.nextDouble() * 300;
            boolean crossed = false;
            for (Strand s : field.strands()) {
                for (double x = x0; x <= x0 + 150 && !crossed; x += 10) {
                    double y = FlowField.y(s, x, t, W, H);
                    crossed = y >= y0 && y <= y0 + 34;
                }
                if (crossed) {
                    break;
                }
            }
            for (double f : field.hairlinesH()) {
                crossed |= f * H >= y0 && f * H <= y0 + 34;
            }
            for (double f : field.hairlinesV()) {
                crossed |= f * W >= x0 && f * W <= x0 + 150;
            }
            hits += crossed ? 1 : 0;
        }
        assertTrue(hits >= trials * 0.97, "cobertura: " + hits + "/" + trials);
    }

    @Test
    void thereAreBandsAndIridescentStrandsButMostlySilver() {
        long iridescent = field.strands().stream().filter(s -> s.tint() == FlowField.Tint.IRIDESCENT).count();
        assertTrue(iridescent >= 3 && iridescent <= 5, "iridescência rara: " + iridescent);
        assertTrue(field.bands().size() >= 4);
        for (var band : field.bands()) {
            assertTrue(band.alpha() <= 0.35, "faixas translúcidas");
        }
    }
}
