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
 * Fundo do Light Liquid Glass: um ambiente claro e iluminado, com poças de luz (verde, ciano,
 * magenta, lavanda). Alguns anéis coloridos abstratos aparecem só através do vidro, desfocados.
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
    private static final double FROST_BLUR = 13; // em pixels da captura reduzida (~52 px reais)

    private record Glow(double fx, double fy, double radius, Color color, double alpha) {}

    private record Ring(double fx, double fy, double radius, double width, Color from, Color to, double alpha) {}

    private static final Glow[] GLOWS = {
            new Glow(0.08, 0.06, 520, Color.web("#4df0ae"), 0.36),   // verde luminoso, topo esquerdo
            new Glow(0.62, -0.05, 480, Color.web("#4fd6ff"), 0.32),  // ciano, topo
            new Glow(0.97, 0.92, 560, Color.web("#ff74d4"), 0.28),   // magenta, base direita
            new Glow(0.03, 1.02, 460, Color.web("#b7a6ff"), 0.26),   // lavanda, base esquerda
            new Glow(0.55, 0.62, 380, Color.web("#7df5e0"), 0.14),   // água, centro
    };

    private static final Ring[] RINGS = {
            new Ring(0.345, 0.50, 210, 30, Color.web("#3fe6a8"), Color.web("#46cdf7"), 0.38),
            new Ring(0.70, 0.36, 130, 22, Color.web("#ff7fd6"), Color.web("#a99bff"), 0.32),
            new Ring(0.86, 0.78, 90, 16, Color.web("#46cdf7"), Color.web("#5df0b6"), 0.32),
    };

    private final Region base = new Region();
    private final Circle[] glows = new Circle[GLOWS.length];
    private final Circle[] rings = new Circle[RINGS.length];
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
        for (Circle ring : rings) {
            ring.setVisible(true);
        }
        WritableImage reduced = snapshot(small, null);
        for (Circle ring : rings) {
            ring.setVisible(false);
        }

        ImageView blurView = new ImageView(reduced);
        blurView.setEffect(new GaussianBlur(FROST_BLUR));
        SnapshotParameters crop = new SnapshotParameters();
        crop.setFill(Color.WHITE);
        crop.setViewport(new Rectangle2D(0, 0, reduced.getWidth(), reduced.getHeight()));
        frost.set(blurView.snapshot(crop, null));
    }
}
