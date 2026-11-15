package com.estudotron.component;

import com.estudotron.component.Icons.Icon;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Card grande e interativo para escolher uma modalidade de exercício. O brilho e a elevação do
 * hover vêm do {@link GlassMotion} (classe {@code mode-card}); desabilitado, não reage.
 */
public final class ModeCard extends VBox {

    public ModeCard(Icon icon, String title, String description, List<String> points, Runnable onSelect) {
        getStyleClass().add("mode-card");
        setSpacing(12);
        setFocusTraversable(true);
        setMinWidth(0);

        StackPane iconBox = new StackPane(Icons.create(icon, 24));
        iconBox.getStyleClass().add("mode-icon");
        iconBox.setMinSize(54, 54);
        iconBox.setPrefSize(54, 54);
        iconBox.setMaxSize(54, 54);

        Label titleLabel = ExerciseParts.wrapLabel(title, "mode-title");
        Label descLabel = ExerciseParts.wrapLabel(description, "mode-desc");

        VBox pointsBox = new VBox(6);
        for (String point : points) {
            HBox row = new HBox(8, Icons.create(Icon.CHECK, 13), ExerciseParts.wrapLabel(point, "mode-point"));
            row.setAlignment(Pos.TOP_LEFT);
            row.getStyleClass().add("mode-point-row");
            pointsBox.getChildren().add(row);
        }

        Label cta = new Label("Começar");
        cta.getStyleClass().add("mode-cta");
        HBox ctaRow = new HBox(6, cta, Icons.create(Icon.ARROW_RIGHT, 15));
        ctaRow.setAlignment(Pos.CENTER_LEFT);
        ctaRow.getStyleClass().add("mode-cta-row");

        Region grow = new Region();
        VBox.setVgrow(grow, javafx.scene.layout.Priority.ALWAYS);
        getChildren().addAll(iconBox, titleLabel, descLabel, pointsBox, grow, ctaRow);

        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && !isDisable()) {
                onSelect.run();
            }
        });
        setOnKeyPressed(e -> {
            if ((e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) && !isDisable()) {
                onSelect.run();
                e.consume();
            }
        });
    }
}
