package com.estudotron.component;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.util.Set;

/**
 * Movimento do Liquid Glass, curto e discreto:
 * <ul>
 *   <li><b>hover</b>: o vidro clareia e o controle sobe 2,5% (profundidade), em 140–170 ms;</li>
 *   <li><b>clique</b>: o botão "afunda" 4% e volta (resposta ao toque);</li>
 *   <li><b>entrada/saída</b> de painéis e mensagens: fade + deslocamento curto.</li>
 * </ul>
 * O CSS do JavaFX não tem transições, então o brilho é animado aqui mudando a cor-variável
 * {@code -g-hl}, que o CSS usa como camada de fundo. Instalado uma vez por cena; os nós são
 * ligados sob demanda (no primeiro hover), então botões criados depois também funcionam.
 */
public final class GlassMotion {

    private static final Set<String> HOVER_CLASSES =
            Set.of("button", "toggle-button", "ref-chip", "drop-zone-box", "glass-hover", "mode-card");
    private static final Set<String> PRESS_CLASSES = Set.of("button", "toggle-button", "ref-chip", "mode-card");
    private static final String PROP_WIRED = "glass.motion.wired";
    private static final double HOVER_ALPHA = 0.50;
    private static final Interpolator EASE = Interpolator.SPLINE(0.22, 0.61, 0.36, 1.0); // ease-out suave

    private GlassMotion() {}

    /** Liga o movimento a todos os controles interativos da cena (inclusive os criados depois). */
    public static void install(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, event -> {
            Node node = find(event.getTarget(), HOVER_CLASSES);
            if (node != null && !Boolean.TRUE.equals(node.getProperties().get(PROP_WIRED))) {
                HoverDriver driver = wire(node);
                if (node.isHover() && !node.isDisabled()) {
                    driver.to(1); // o mouse já estava em cima quando ligamos o nó
                }
            }
        });
    }

    private static Node find(EventTarget target, Set<String> classes) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            for (String style : node.getStyleClass()) {
                if (classes.contains(style)) {
                    return node;
                }
            }
            node = node.getParent();
        }
        return null;
    }

    // ----------------------------------------------------------------- hover e clique

    private static HoverDriver wire(Node node) {
        node.getProperties().put(PROP_WIRED, Boolean.TRUE);
        HoverDriver driver = new HoverDriver(node);
        boolean scales = find(node, PRESS_CLASSES) == node;
        node.hoverProperty().addListener((obs, was, hovering) -> {
            driver.to(hovering && !node.isDisabled() ? 1 : 0);
            if (scales) {
                scale(node);
            }
        });
        node.disabledProperty().addListener((obs, was, disabled) -> {
            if (disabled) {
                driver.to(0);
            }
        });
        if (scales) {
            node.pressedProperty().addListener((obs, was, pressed) -> scale(node));
            scale(node);
        }
        return driver;
    }

    /** Anima o brilho de 0 a 1 mudando a variável CSS -g-hl do próprio nó. */
    private static final class HoverDriver {
        private final Node node;
        private final String baseStyle;
        private final DoubleProperty progress = new SimpleDoubleProperty(0);
        private Timeline timeline;

        HoverDriver(Node node) {
            this.node = node;
            this.baseStyle = node.getStyle() == null ? "" : node.getStyle();
            progress.addListener((obs, old, value) -> node.setStyle(
                    baseStyle + "-g-hl: rgba(255,255,255," + String.format(java.util.Locale.ROOT, "%.3f",
                            value.doubleValue() * HOVER_ALPHA) + ");"));
        }

        void to(double target) {
            if (timeline != null) {
                timeline.stop();
            }
            timeline = new Timeline(new KeyFrame(Duration.millis(target > 0 ? 140 : 220),
                    new KeyValue(progress, target, EASE)));
            timeline.setOnFinished(e -> {
                if (target == 0) {
                    node.setStyle(baseStyle); // volta ao estado limpo (sem estilo inline)
                }
            });
            timeline.play();
        }
    }

    /** Profundidade: sobe um pouco no hover (2,5%), afunda ao pressionar (4%). */
    private static void scale(Node node) {
        boolean pressed = node.isPressed();
        double target = pressed ? 0.96 : node.isHover() && !node.isDisabled() ? 1.015 : 1.0;
        ScaleTransition scale = new ScaleTransition(Duration.millis(pressed ? 70 : 170), node);
        scale.setInterpolator(EASE);
        scale.setToX(target);
        scale.setToY(target);
        scale.play();
    }

    // ----------------------------------------------------------------- entradas e saídas

    /** Aparece com fade e um deslocamento curto (de dx, dy até a posição final). */
    public static void fadeSlideIn(Node node, double dx, double dy, double millis) {
        node.setOpacity(0);
        node.setTranslateX(dx);
        node.setTranslateY(dy);
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setToValue(1);
        fade.setInterpolator(EASE);
        TranslateTransition move = new TranslateTransition(Duration.millis(millis), node);
        move.setToX(0);
        move.setToY(0);
        move.setInterpolator(EASE);
        new ParallelTransition(fade, move).play();
    }

    /** Some com fade e deslocamento; {@code after} roda ao terminar (ex.: remover o nó). */
    public static void fadeSlideOut(Node node, double dx, double dy, double millis, Runnable after) {
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setToValue(0);
        fade.setInterpolator(Interpolator.EASE_IN);
        TranslateTransition move = new TranslateTransition(Duration.millis(millis), node);
        move.setToX(dx);
        move.setToY(dy);
        move.setInterpolator(Interpolator.EASE_IN);
        ParallelTransition both = new ParallelTransition(fade, move);
        both.setOnFinished(e -> {
            node.setTranslateX(0);
            node.setTranslateY(0);
            node.setOpacity(1);
            if (after != null) {
                after.run();
            }
        });
        both.play();
    }

    /** Janela/painel que "emerge": fade + leve aumento de escala. */
    public static void popIn(Node node, double millis) {
        node.setOpacity(0);
        node.setScaleX(0.972);
        node.setScaleY(0.972);
        FadeTransition fade = new FadeTransition(Duration.millis(millis), node);
        fade.setToValue(1);
        fade.setInterpolator(EASE);
        ScaleTransition scale = new ScaleTransition(Duration.millis(millis), node);
        scale.setToX(1);
        scale.setToY(1);
        scale.setInterpolator(EASE);
        new ParallelTransition(fade, scale).play();
    }
}
