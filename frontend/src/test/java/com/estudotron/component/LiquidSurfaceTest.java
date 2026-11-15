package com.estudotron.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A física da película líquida: localizada no cursor, contínua, proporcional à reatividade e reversível. */
class LiquidSurfaceTest {

    private static final int W = 150;
    private static final int H = 36;
    private static final int M = 16;

    private static LiquidSurface surface() {
        return new LiquidSurface(W, H, 13, M, M, M, M);
    }

    private static double magnitude(LiquidSurface s, int bx, int by) {
        int x = M + bx;
        int y = M + by;
        return Math.hypot(s.offsetX(x, y), s.offsetY(x, y));
    }

    /** Média da magnitude em uma janela do interior do botão. */
    private static double average(LiquidSurface s, int x0, int x1) {
        double sum = 0;
        int n = 0;
        for (int x = x0; x < x1; x++) {
            for (int y = 12; y < 24; y++) {   // interior, longe do rim
                sum += magnitude(s, x, y);
                n++;
            }
        }
        return sum / n;
    }

    @Test
    void atRestThereIsNoDistortion() {
        LiquidSurface s = surface();
        s.compute(40, 18, 0, 0, 0, 0);
        for (int y = 0; y < s.imageHeight(); y++) {
            for (int x = 0; x < s.imageWidth(); x++) {
                assertEquals(0f, s.offsetX(x, y));
                assertEquals(0f, s.offsetY(x, y));
            }
        }
    }

    /** Excesso de deformação causado pelo cursor: diferença para o mesmo botão com o cursor muito longe. */
    private static double excess(LiquidSurface withCursor, LiquidSurface baseline, int x0, int x1) {
        double sum = 0;
        int n = 0;
        for (int x = x0; x < x1; x++) {
            for (int y = 8; y < 28; y++) {
                int ix = M + x;
                int iy = M + y;
                sum += Math.hypot(withCursor.offsetX(ix, iy) - baseline.offsetX(ix, iy),
                        withCursor.offsetY(ix, iy) - baseline.offsetY(ix, iy));
                n++;
            }
        }
        return sum / n;
    }

    private static LiquidSurface baseline() {
        LiquidSurface s = surface();
        s.compute(5000, 18, 0, 0, 1, 0);    // cursor fora do alcance: só rim e lente
        return s;
    }

    @Test
    void distortionIsLocalizedAroundTheCursorAndFollowsIt() {
        LiquidSurface base = baseline();
        LiquidSurface s = surface();
        s.compute(30, 18, 0, 0, 1, 0);
        double near = excess(s, base, 12, 48);
        double far = excess(s, base, 100, 136);
        assertTrue(near > 1.5, "deve haver deformação perto do cursor: " + near);
        assertTrue(far < near * 0.05, "longe do cursor quase nada muda: " + far + " vs " + near);

        s.compute(120, 18, 0, 0, 1, 0);
        double nearNow = excess(s, base, 102, 138);
        double farNow = excess(s, base, 12, 48);
        assertTrue(nearNow > 1.5 && farNow < nearNow * 0.05, "a deformação acompanha o cursor");
    }

    @Test
    void intensityFallsWithDistanceFromTheCursor() {
        LiquidSurface base = baseline();
        LiquidSurface s = surface();
        s.compute(40, 18, 0, 0, 1, 0);
        double d1 = excess(s, base, 54, 62);     // ~18 px do cursor
        double d2 = excess(s, base, 80, 88);     // ~44 px
        double d3 = excess(s, base, 110, 118);   // ~74 px
        assertTrue(d1 > d2 && d2 > d3, d1 + " > " + d2 + " > " + d3);
    }

    @Test
    void motionIsContinuous() {
        LiquidSurface a = surface();
        LiquidSurface b = surface();
        a.compute(60, 18, 0, 0, 1, 0);
        b.compute(61, 18, 0, 0, 1, 0);   // 1 px de movimento do cursor
        double maxJump = 0;
        for (int y = 0; y < a.imageHeight(); y++) {
            for (int x = 0; x < a.imageWidth(); x++) {
                maxJump = Math.max(maxJump, Math.hypot(a.offsetX(x, y) - b.offsetX(x, y),
                        a.offsetY(x, y) - b.offsetY(x, y)));
            }
        }
        assertTrue(maxJump < 1.5, "um passo de 1 px não pode causar salto na deformação: " + maxJump);
    }

    @Test
    void strengthScalesTheEffectAndPressingDeepensIt() {
        LiquidSurface half = surface();
        LiquidSurface full = surface();
        LiquidSurface pressed = surface();
        half.compute(75, 18, 0, 0, 0.5, 0);
        full.compute(75, 18, 0, 0, 1, 0);
        pressed.compute(75, 18, 0, 0, 1, 1);
        LiquidSurface base = baseline();
        double aHalf = average(half, 60, 90);
        double aFull = average(full, 60, 90);
        double aPressed = average(pressed, 60, 90);
        assertTrue(excess(pressed, base, 60, 90) > excess(full, base, 60, 90));
        assertTrue(aHalf < aFull && aFull < aPressed, aHalf + " < " + aFull + " < " + aPressed);
    }

    @Test
    void rimPullsContentFromOutsideAndNothingLeaksOutsideTheButton() {
        LiquidSurface s = surface();
        s.compute(75, 18, 0, 0, 1, 0);
        // borda esquerda: o deslocamento aponta para fora (amostra além da borda), x negativo
        assertTrue(s.offsetX(M + 1, M + 18) < -3, "o rim puxa de fora: " + s.offsetX(M + 1, M + 18));
        // longe do botão (na margem de 16 px, fora do retângulo) não há deslocamento
        assertEquals(0f, s.offsetX(0, 0));
        assertEquals(0f, s.offsetY(s.imageWidth() - 1, s.imageHeight() - 1));
    }

    @Test
    void dragFollowsTheCursorVelocityAndLightingIsVisibleOnlyWhenReactive() {
        LiquidSurface still = surface();
        LiquidSurface moving = surface();
        still.compute(75, 18, 0, 0, 1, 0);
        moving.compute(75, 18, 600, 0, 1, 0);
        assertTrue(moving.offsetX(M + 80, M + 18) > still.offsetX(M + 80, M + 18),
                "o arrasto desloca a imagem no sentido do movimento");

        int[] argb = new int[W * H];
        moving.lighting(argb, 1);
        int bright = 0;
        for (int p : argb) {
            if ((p >>> 24) > 20) {
                bright++;
            }
        }
        assertTrue(bright > 20, "a superfície deformada tem reflexos");
        surface().lighting(argb, 0);
        for (int p : argb) {
            assertEquals(0, p >>> 24);
        }
    }
}
