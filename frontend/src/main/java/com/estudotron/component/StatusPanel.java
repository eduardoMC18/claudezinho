package com.estudotron.component;

import com.estudotron.component.Icons.Icon;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Estados de tela cheia da área de exercícios: carregando (gerando a questão, corrigindo)
 * e erro (falha de comunicação com o Gemini, sem chave, sem documentos).
 */
public final class StatusPanel extends VBox {

    private StatusPanel(Icon icon, String tone, String title, String message) {
        getStyleClass().add("status-panel");
        setAlignment(Pos.CENTER);
        setSpacing(12);
        setMaxWidth(520);

        StackPane orb = new StackPane(Icons.create(icon, 28));
        orb.getStyleClass().addAll("status-orb", "tone-" + tone);
        orb.setMinSize(76, 76);
        orb.setPrefSize(76, 76);
        orb.setMaxSize(76, 76);

        Label titleLabel = ExerciseParts.wrapLabel(title, "status-title");
        titleLabel.setAlignment(Pos.CENTER);
        titleLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        getChildren().addAll(orb, titleLabel);
        if (message != null && !message.isBlank()) {
            Label messageLabel = ExerciseParts.wrapLabel(message, "status-message");
            messageLabel.setAlignment(Pos.CENTER);
            messageLabel.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
            getChildren().add(messageLabel);
        }
    }

    /** Indicador elegante de carregamento (esfera com brilho + pontos pulsando). */
    public static StatusPanel loading(String title, String message) {
        StatusPanel panel = new StatusPanel(Icon.SPARKLE, "info", title, message);
        PulseDots dots = new PulseDots();
        dots.setScaleX(1.5);
        dots.setScaleY(1.5);
        panel.getChildren().add(2, dots);
        return panel;
    }

    /** Erro com ações (botões) escolhidas por quem chama. */
    public static StatusPanel error(String title, String message, Node... actions) {
        StatusPanel panel = new StatusPanel(Icon.ALERT, "bad", title, message);
        if (actions.length > 0) {
            FlowPane row = new FlowPane(10, 10, actions);
            row.setAlignment(Pos.CENTER);
            row.setPadding(new javafx.geometry.Insets(6, 0, 0, 0));
            panel.getChildren().add(row);
        }
        return panel;
    }

    /** Aviso informativo (ex.: nenhum PDF carregado). */
    public static StatusPanel info(Icon icon, String title, String message, Node... actions) {
        StatusPanel panel = new StatusPanel(icon, "info", title, message);
        if (actions.length > 0) {
            FlowPane row = new FlowPane(10, 10, actions);
            row.setAlignment(Pos.CENTER);
            panel.getChildren().add(row);
        }
        return panel;
    }
}
