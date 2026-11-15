package com.estudotron.component;

import com.estudotron.viewmodel.ChatMessage.Reference;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/** Referência clicável a uma página de um PDF ("arquivo.pdf · p. 12"); leva o visualizador até lá. */
public final class ReferenceChip extends HBox {

    public ReferenceChip(Reference reference, Consumer<Reference> onClick) {
        getStyleClass().add("ref-chip");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(6);
        Label label = new Label(reference.label());
        label.setMinWidth(0);
        label.setMaxWidth(240);
        getChildren().addAll(Icons.create(Icons.Icon.FILE, 13), label);
        Tooltip.install(this, new Tooltip("Ir para " + reference.label()));
        setOnMouseClicked(e -> onClick.accept(reference));
    }
}
