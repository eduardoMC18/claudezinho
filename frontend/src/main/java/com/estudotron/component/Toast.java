package com.estudotron.component;

import com.estudotron.service.NotificationService.Notice;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/** Aviso temporário no rodapé da janela. Não captura cliques. */
public final class Toast extends HBox {

    private final Label text = new Label();
    private final PauseTransition hideDelay = new PauseTransition(Duration.seconds(4.5));
    private final FadeTransition fade = new FadeTransition(Duration.millis(180), this);

    public Toast() {
        getStyleClass().add("toast");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);
        setVisible(false);
        setOpacity(0);
        setMouseTransparent(true);
        setPickOnBounds(false);
        setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        text.setWrapText(true);
        text.setMaxWidth(560);
        hideDelay.setOnFinished(e -> {
            fade.stop();
            fade.setToValue(0);
            fade.setOnFinished(done -> setVisible(false));
            fade.play();
        });
    }

    public void show(Notice notice) {
        getStyleClass().removeAll("info", "success", "warning", "error");
        getStyleClass().add(notice.kind().name().toLowerCase());
        Icons.Icon icon = switch (notice.kind()) {
            case ERROR, WARNING -> Icons.Icon.ALERT;
            case SUCCESS -> Icons.Icon.CHECK;
            case INFO -> Icons.Icon.INFO;
        };
        text.setText(notice.message());
        getChildren().setAll(Icons.create(icon, 18), text);

        hideDelay.stop();
        fade.stop();
        setVisible(true);
        fade.setOnFinished(null);
        fade.setToValue(1);
        fade.play();
        hideDelay.playFromStart();
    }
}
