package com.estudotron.component;

import javafx.animation.PauseTransition;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Transform;
import javafx.util.Duration;

/**
 * Fundo do Liquid Glass: um ambiente branco e neutro, com variações discretas de luminosidade. Atrás dele
 * (só na imagem desfocada) ficam esferas e anéis de vidro/prata: é o que o vidro dos painéis refrata e
 * desfoca, e o que faz a transparência ser perceptível. Nas frestas entre os painéis o fundo fica liso.
 *
 * <p><b>Desfoque real por trás dos painéis.</b> O JavaFX não tem backdrop blur. Como este fundo é
 * estático, ele é capturado em baixa resolução, desfocado uma única vez e publicado em
 * {@link #frostProperty()}. Cada {@link GlassCard} mostra o pedaço dessa imagem que fica exatamente
 * atrás dele — o resultado é vidro fosco de verdade (formas nítidas nas frestas, borradas atrás do
 * vidro), sem custo por quadro: só é refeito quando a janela muda de tamanho.
 */
public final class AmbientBackground extends Pane {

    /** Escala da captura (1/4 da resolução: o desfoque esconde a diferença e fica 16x mais barato). */
    private static final double FROST_SCALE = 0.25;
    private static final double FROST_BLUR = 8; // em pixels da captura reduzida (~32 px reais): ainda reconhecível

    private record Glow(double fx, double fy, double radius, Color color, double alpha) {}

    /** Anel de prata (traço em gradiente). */
    private record Ring(double fx, double fy, double radius, double width, Color from, Color to, double alpha) {}

    /** Esfera de vidro: corpo cinza-azulado com um ponto de luz. */
    private record Orb(double fx, double fy, double radius, Color body, double alpha) {}

    /** Luz ambiente: branco dominante, só um toque frio e sombras cinza muito suaves. */
    private static final Glow[] GLOWS = {
            new Glow(0.28, 0.06, 640, Color.web("#ffffff"), 0.95),   // fonte de luz, topo
            new Glow(0.72, 0.58, 460, Color.web("#ffffff"), 0.70),   // brilho central
            new Glow(1.00, 1.00, 640, Color.web("#8d99b2"), 0.24),   // sombra cinza, canto inferior
            new Glow(0.00, 0.98, 520, Color.web("#c3d0ea"), 0.26),   // toque frio, canto inferior esquerdo
            new Glow(0.98, 0.02, 420, Color.web("#d3dcee"), 0.30),   // toque frio, canto superior direito
    };

    // Só entram na imagem desfocada (atrás do vidro).
    private static final Ring[] RINGS = {
            new Ring(0.38, 0.52, 230, 36, Color.web("#46526e"), Color.web("#b7c3da"), 0.32),
            new Ring(0.74, 0.30, 140, 24, Color.web("#5d6b88"), Color.web("#c9d3e6"), 0.30),
            new Ring(0.93, 0.70, 100, 18, Color.web("#46526e"), Color.web("#aebbd3"), 0.28),
    };

    private static final Orb[] ORBS = {
            new Orb(0.07, 0.26, 130, Color.web("#4d5a78"), 0.34),    // atrás da barra lateral
            new Orb(0.60, 0.80, 170, Color.web("#56637f"), 0.30),
            new Orb(0.90, 0.16, 110, Color.web("#4d5a78"), 0.28),
    };

    private final Region base = new Region();
    private final Circle[] glows = new Circle[GLOWS.length];
    private final Circle[] rings = new Circle[RINGS.length];
    private final Circle[] orbs = new Circle[ORBS.length];
    private final ReadOnlyObjectWrapper<Image> frost = new ReadOnlyObjectWrapper<>();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(140));

    public AmbientBackground() {
        getStyleClass().add("ambient");
        base.getStyleClass().add("ambient-base");
        getChildren().add(base);

        for (int i = 0; i < GLOWS.length; i++) {
            Glow g = GLOWS[i];
            Circle circle = new Circle(g.radius());
            circle.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                    new Stop(0.0, alpha(g.color(), g.alpha())),
                    new Stop(0.55, alpha(g.color(), g.alpha() * 0.50)),
                    new Stop(1.0, alpha(g.color(), 0))));
            circle.setManaged(false);
            glows[i] = circle;
            getChildren().add(circle);
        }
        for (int i = 0; i < RINGS.length; i++) {
            Ring r = RINGS[i];
            Circle circle = new Circle(r.radius());
            circle.setFill(Color.TRANSPARENT);
            circle.setStrokeWidth(r.width());
            circle.setStroke(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                    new Stop(0, alpha(r.from(), r.alpha())), new Stop(1, alpha(r.to(), r.alpha() * 0.75))));
            circle.setManaged(false);
            circle.setVisible(false); // só entram na imagem desfocada (ver renderFrost)
            rings[i] = circle;
            getChildren().add(circle);
        }

        for (int i = 0; i < ORBS.length; i++) {
            Orb o = ORBS[i];
            Circle circle = new Circle(o.radius());
            // corpo escuro na borda e luz no ponto superior esquerdo: lê como uma esfera de vidro
            circle.setFill(new RadialGradient(0, 0, 0.34, 0.30, 0.78, true, CycleMethod.NO_CYCLE,
                    new Stop(0.0, alpha(Color.WHITE, o.alpha() * 0.85)),
                    new Stop(0.45, alpha(o.body(), o.alpha() * 0.55)),
                    new Stop(1.0, alpha(o.body(), o.alpha()))));
            circle.setManaged(false);
            circle.setVisible(false);
            orbs[i] = circle;
            getChildren().add(circle);
        }

        setMouseTransparent(true);
        setMinSize(0, 0);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

        debounce.setOnFinished(e -> renderFrost());
        widthProperty().addListener((obs, old, w) -> debounce.playFromStart());
        heightProperty().addListener((obs, old, h) -> debounce.playFromStart());
        sceneProperty().addListener((obs, old, scene) -> debounce.playFromStart());
    }

    /** O fundo desfocado (mesmas proporções da janela); null até a primeira captura. */
    public ReadOnlyObjectProperty<Image> frostProperty() {
        return frost.getReadOnlyProperty();
    }

    /** Fundo ambiente da cena, se houver. */
    public static AmbientBackground of(Scene scene) {
        return scene != null && scene.getRoot().lookup(".ambient") instanceof AmbientBackground bg ? bg : null;
    }

    private static Color alpha(Color color, double alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(1, alpha)));
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        base.resizeRelocate(0, 0, w, h);
        for (int i = 0; i < glows.length; i++) {
            glows[i].setCenterX(w * GLOWS[i].fx());
            glows[i].setCenterY(h * GLOWS[i].fy());
        }
        for (int i = 0; i < rings.length; i++) {
            rings[i].setCenterX(w * RINGS[i].fx());
            rings[i].setCenterY(h * RINGS[i].fy());
        }
        for (int i = 0; i < orbs.length; i++) {
            orbs[i].setCenterX(w * ORBS[i].fx());
            orbs[i].setCenterY(h * ORBS[i].fy());
        }
    }

    private void setBehindGlassVisible(boolean visible) {
        for (Circle ring : rings) {
            ring.setVisible(visible);
        }
        for (Circle orb : orbs) {
            orb.setVisible(visible);
        }
    }

    /** Captura o fundo em 1/4 da resolução e desfoca. Roda só quando a janela muda de tamanho. */
    private void renderFrost() {
        double w = getWidth();
        double h = getHeight();
        if (getScene() == null || w < 2 || h < 2) {
            return;
        }
        SnapshotParameters small = new SnapshotParameters();
        small.setTransform(Transform.scale(FROST_SCALE, FROST_SCALE));
        small.setFill(Color.WHITE);
        small.setViewport(new Rectangle2D(0, 0, Math.ceil(w * FROST_SCALE), Math.ceil(h * FROST_SCALE)));
        // Os anéis coloridos existem apenas "atrás do vidro": aparecem borrados através dos painéis,
        // mas não nas frestas entre eles (onde uma fatia nítida de anel pareceria um defeito).
        setBehindGlassVisible(true);
        WritableImage reduced = snapshot(small, null);
        setBehindGlassVisible(false);

        ImageView blurView = new ImageView(reduced);
        blurView.setEffect(new GaussianBlur(FROST_BLUR));
        SnapshotParameters crop = new SnapshotParameters();
        crop.setFill(Color.WHITE);
        crop.setViewport(new Rectangle2D(0, 0, reduced.getWidth(), reduced.getHeight()));
        frost.set(blurView.snapshot(crop, null));
    }
}
