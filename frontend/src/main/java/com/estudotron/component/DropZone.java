package com.estudotron.component;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Quadrado tracejado exibido quando o slot está vazio. Clicar abre o seletor de arquivos;
 * o arrasto de PDFs é tratado pelo painel que o contém (que chama {@link #setActive}).
 */
public final class DropZone extends StackPane {

    private static final double MAX_SIDE = 260;
    private static final double MIN_SIDE = 96;

    private final VBox box;
    private Runnable onChoose = () -> { };

    public DropZone() {
        Label title = new Label("Arraste um PDF aqui");
        title.getStyleClass().add("drop-zone-title");
        Label hint = new Label("ou clique para escolher o arquivo");
        hint.getStyleClass().add("drop-zone-hint");
        hint.setWrapText(true);
        title.setWrapText(true);

        box = new VBox(8, Icons.create(Icons.Icon.UPLOAD, 30), title, hint);
        box.getStyleClass().add("drop-zone-box");
        box.setAlignment(Pos.CENTER);
        box.setOnMouseClicked(e -> onChoose.run());

        // O quadrado se adapta ao painel, mantendo lados iguais.
        DoubleBinding side = Bindings.createDoubleBinding(
                () -> Math.max(MIN_SIDE, Math.min(MAX_SIDE, Math.min(getWidth(), getHeight()) - 40)),
                widthProperty(), heightProperty());
        box.minWidthProperty().bind(side);
        box.maxWidthProperty().bind(side);
        box.minHeightProperty().bind(side);
        box.maxHeightProperty().bind(side);

        setAlignment(Pos.CENTER);
        setPickOnBounds(false); // só o quadrado recebe cliques
        setMinSize(0, 0);
        getChildren().add(box);
    }

    public void setOnChoose(Runnable onChoose) {
        this.onChoose = onChoose;
    }

    public void setActive(boolean active) {
        if (active && !box.getStyleClass().contains("active")) {
            box.getStyleClass().add("active");
        } else if (!active) {
            box.getStyleClass().remove("active");
        }
    }
}
