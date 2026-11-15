package com.estudotron.component;

import com.estudotron.backend.DocumentDto;
import com.estudotron.component.Icons.Icon;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Cartão compacto de um PDF carregado (miniatura de página + nome + páginas), mostrado na tela inicial. */
public final class SourceDocCard extends HBox {

    public SourceDocCard(String slotLabel, DocumentDto doc) {
        getStyleClass().add("source-card");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(10);

        StackPane thumb = new StackPane(Icons.create(Icon.FILE, 18));
        thumb.getStyleClass().add("source-thumb");
        thumb.setMinSize(34, 44);
        thumb.setPrefSize(34, 44);
        thumb.setMaxSize(34, 44);

        Label tag = new Label(slotLabel);
        tag.getStyleClass().add("source-tag");
        Label name = new Label(doc.name());
        name.getStyleClass().add("source-name");
        name.setMaxWidth(190);
        name.setMinWidth(0);
        Label info = new Label(info(doc));
        info.getStyleClass().add("source-info");
        if (DocumentDto.READY.equals(doc.status())) {
            info.getStyleClass().add("ready");
        }

        VBox text = new VBox(1, tag, name, info);
        text.setMinWidth(0);
        getChildren().addAll(thumb, text);
        Tooltip.install(this, new Tooltip(doc.name()));
    }

    private static String info(DocumentDto doc) {
        String pages = doc.pages() + (doc.pages() == 1 ? " página" : " páginas");
        return switch (doc.status() == null ? "" : doc.status()) {
            case DocumentDto.READY -> pages;
            case DocumentDto.PROCESSING -> "indexando " + doc.pagesDone() + "/" + doc.pages();
            case DocumentDto.NO_TEXT -> "sem texto (escaneado)";
            case DocumentDto.ERROR -> "erro ao ler";
            default -> pages;
        };
    }
}
