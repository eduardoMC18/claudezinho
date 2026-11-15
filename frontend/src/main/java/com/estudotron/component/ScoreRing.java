package com.estudotron.component;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

/** Anel de pontuação (0 a 10) que se desenha ao aparecer; a cor vem do nível (correct, partial, incorrect). */
public final class ScoreRing extends StackPane {

    private static final double SIZE = 96;
    private static final double STROKE = 9;

    public ScoreRing(double score, String level) {
        getStyleClass().addAll("score-ring", level);
        setMinSize(SIZE, SIZE);
        setPrefSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);

        double radius = (SIZE - STROKE) / 2;
        Circle track = new Circle(radius);
        track.getStyleClass().add("score-track");
        track.setFill(null);
        track.setStrokeWidth(STROKE);

        Arc arc = new Arc(0, 0, radius, radius, 90, 0);
        arc.setType(ArcType.OPEN);
        arc.setFill(null);
        arc.setStrokeWidth(STROKE);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        arc.getStyleClass().add("score-arc");

        Label value = new Label(ExerciseParts.score(score));
        value.getStyleClass().add("score-value");
        Label of = new Label("de 10");
        of.getStyleClass().add("score-of");
        VBox text = new VBox(-2, value, of);
        text.setAlignment(Pos.CENTER);

        getChildren().addAll(track, arc, text);

        double target = -360.0 * Math.max(0, Math.min(10, score)) / 10.0;  // sentido horário a partir do topo
        new Timeline(new KeyFrame(Duration.millis(900),
                new KeyValue(arc.lengthProperty(), target, Interpolator.SPLINE(0.22, 0.61, 0.36, 1.0)))).play();
    }
}
