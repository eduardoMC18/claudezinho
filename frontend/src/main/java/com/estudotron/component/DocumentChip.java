package com.estudotron.component;

import com.estudotron.backend.DocumentDto;
import com.estudotron.viewmodel.DocumentText;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

/**
 * Indicador de um documento no cabeçalho: ponto de estado, rótulo, nome e estado.
 * No modo compacto o estado some (continua na dica) e o nome encurta.
 */
public final class DocumentChip extends HBox {

    private static final double NAME_MAX = 190;
    private static final double NAME_MAX_COMPACT = 120;

    private final Label tag;
    private final Label name = new Label();
    private final Label state = new Label();
    private final Tooltip tooltip = new Tooltip();
    private boolean hasDocument;
    private boolean compact;

    public DocumentChip(String tagText) {
        getStyleClass().add("doc-chip");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(7);

        Circle dot = new Circle(4);
        dot.getStyleClass().add("chip-dot");
        tag = new Label(tagText);
        tag.getStyleClass().add("chip-tag");
        tag.setMinWidth(Region.USE_PREF_SIZE);
        name.getStyleClass().add("chip-name");
        name.setMaxWidth(NAME_MAX);
        state.getStyleClass().add("chip-state");
        state.setMinWidth(Region.USE_PREF_SIZE);

        getChildren().addAll(dot, tag, name, state);
        Tooltip.install(this, tooltip);
        update(null);
    }

    public void setCompact(boolean compact) {
        this.compact = compact;
        applyLayout();
    }

    public void update(DocumentDto doc) {
        DocumentText.Indicator indicator = DocumentText.describe(doc);
        getStyleClass().removeIf(c -> c.startsWith("level-"));
        getStyleClass().add("level-" + indicator.level().name().toLowerCase());
        hasDocument = doc != null;
        name.setText(doc == null ? "nenhum PDF" : doc.name());
        state.setText(doc == null ? "" : "· " + DocumentText.stateLabel(doc));
        tooltip.setText(indicator.text());
        applyLayout();
    }

    private void applyLayout() {
        boolean showState = hasDocument && !compact;
        state.setVisible(showState);
        state.setManaged(showState);
        name.setMaxWidth(compact ? NAME_MAX_COMPACT : NAME_MAX);
    }
}
