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
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Transform;
import javafx.util.Duration;

/**
 * Fundo do Liquid Glass: um ambiente quase branco, limpo, com uma variação de luz muito suave (mais claro
 * no canto superior esquerdo, cinza-claríssimo no oposto). Sem formas decorativas: a profundidade vem das
 * placas de vidro, não do fundo.
 *
 * <p><b>Desfoque do que está atrás do vidro.</b> O JavaFX não tem backdrop blur. Como este fundo é estático,
 * ele é capturado em baixa resolução, desfocado uma única vez e publicado em {@link #frostProperty()}.
 * Cada {@link GlassCard} mostra o pedaço dessa imagem que fica exatamente atrás dele (levemente ampliado,
 * como uma lente), sem custo por quadro: só é refeito quando a janela muda de tamanho.
 */
public final class AmbientBackground extends Pane {

    /** Escala da captura (1/4 da resolução: o desfoque esconde a diferença e fica 16x mais barato). */
    private static final double FROST_SCALE = 0.25;
    private static final double FROST_BLUR = 8; // em pixels da captura reduzida (~32 px reais)

    private record Glow(double fx, double fy, double radius, Color color, double alpha) {}

    /** Luz ambiente discreta: uma fonte branca no topo e um toque frio e quase imperceptível no canto oposto. */
    private static final Glow[] GLOWS = {
            new Glow(0.25, 0.05, 700, Color.web("#ffffff"), 0.90),
            new Glow(1.00, 1.00, 700, Color.web("#c9d2e2"), 0.22),
    };

    private final Region base = new Region();
    private final Circle[] glows = new Circle[GLOWS.length];
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
                    new Stop(0.55, alpha(g.color(), g.alpha() * 0.45)),
                    new Stop(1.0, alpha(g.color(), 0))));
            circle.setManaged(false);
            glows[i] = circle;
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
        WritableImage reduced = snapshot(small, null);

        ImageView blurView = new ImageView(reduced);
        blurView.setEffect(new GaussianBlur(FROST_BLUR));
        SnapshotParameters crop = new SnapshotParameters();
        crop.setFill(Color.WHITE);
        crop.setViewport(new Rectangle2D(0, 0, reduced.getWidth(), reduced.getHeight()));
        frost.set(blurView.snapshot(crop, null));
    }
}
