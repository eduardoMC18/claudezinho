package com.estudotron.component;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

/**
 * Indicador de carregamento: três pontos que pulsam em sequência (mais leve e elegante que um
 * spinner girando). Só anima enquanto está na tela, para não gastar CPU escondido.
 */
public final class PulseDots extends HBox {

    private static final int DOTS = 3;
    private static final double PERIOD_MS = 1100;

    private final Timeline timeline = new Timeline();

    public PulseDots() {
        getStyleClass().add("pulse-dots");
        setAlignment(Pos.CENTER);
        setSpacing(4);
        for (int i = 0; i < DOTS; i++) {
            Circle dot = new Circle(2.8);
            dot.getStyleClass().add("pulse-dot");
            dot.setOpacity(0.28);
            getChildren().add(dot);
            double start = PERIOD_MS * i / (DOTS * 2.6);
            timeline.getKeyFrames().addAll(
                    new KeyFrame(Duration.millis(start), new KeyValue(dot.opacityProperty(), 0.28, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(start + PERIOD_MS * 0.22),
                            new KeyValue(dot.opacityProperty(), 1.0, Interpolator.EASE_BOTH)),
                    new KeyFrame(Duration.millis(start + PERIOD_MS * 0.55),
                            new KeyValue(dot.opacityProperty(), 0.28, Interpolator.EASE_BOTH)));
        }
        timeline.getKeyFrames().add(new KeyFrame(Duration.millis(PERIOD_MS)));
        timeline.setCycleCount(Animation.INDEFINITE);

        sceneProperty().addListener((obs, old, scene) -> update());
        visibleProperty().addListener((obs, old, visible) -> update());
    }

    private void update() {
        if (getScene() != null && isVisible()) {
            timeline.play();
        } else {
            timeline.pause();
        }
    }
}
