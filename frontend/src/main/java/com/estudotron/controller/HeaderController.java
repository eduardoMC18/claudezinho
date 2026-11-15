package com.estudotron.controller;

import com.estudotron.component.DocumentChip;
import com.estudotron.component.Icons;
import com.estudotron.component.Icons.Icon;
import com.estudotron.viewmodel.DocumentSlot;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/** Cabeçalho: adicionar PDF 1/2, indicadores dos documentos e configurações. */
public final class HeaderController {

    /** A partir desta largura os indicadores aparecem completos (nome + estado). */
    private static final double FULL_CHIPS_FROM = 1180;
    /** Entre este valor e o anterior os indicadores ficam compactos; abaixo, somem. */
    private static final double COMPACT_CHIPS_FROM = 900;

    private final DocumentSlot first;
    private final DocumentSlot second;
    private final Consumer<DocumentSlot> onAdd;
    private final Runnable onSettings;
    private final Consumer<Boolean> onChatToggle;

    @FXML private ToggleButton chatToggle;
    @FXML private HBox root;
    @FXML private HBox chips;
    @FXML private Button addFirstButton;
    @FXML private Button addSecondButton;
    @FXML private Button settingsButton;

    private DocumentChip chipFirst;
    private DocumentChip chipSecond;

    public HeaderController(DocumentSlot first, DocumentSlot second,
                            Consumer<DocumentSlot> onAdd, Runnable onSettings, Consumer<Boolean> onChatToggle) {
        this.first = first;
        this.second = second;
        this.onAdd = onAdd;
        this.onSettings = onSettings;
        this.onChatToggle = onChatToggle;
    }

    @FXML
    private void initialize() {

        Icons.set(addFirstButton, Icon.PLUS, 15);
        Icons.set(addSecondButton, Icon.PLUS, 15);
        addFirstButton.setTooltip(new Tooltip("Adicionar o primeiro PDF (coluna esquerda)"));
        addSecondButton.setTooltip(new Tooltip("Adicionar o segundo PDF (coluna direita)"));
        Icons.set(chatToggle, Icon.SPARKLE, 14);
        chatToggle.setTooltip(new Tooltip("Mostrar ou ocultar o chat com a IA"));
        Icons.set(settingsButton, Icon.SETTINGS, 16);
        settingsButton.setTooltip(new Tooltip("Configurações"));

        chipFirst = new DocumentChip("PDF 1");
        chipSecond = new DocumentChip("PDF 2");
        chips.getChildren().addAll(chipFirst, chipSecond);
        first.documentProperty().addListener((obs, old, doc) -> chipFirst.update(doc));
        second.documentProperty().addListener((obs, old, doc) -> chipSecond.update(doc));
        chipFirst.update(first.document());
        chipSecond.update(second.document());

        root.widthProperty().addListener((obs, old, width) -> applyResponsiveLayout(width.doubleValue()));
    }

    /** Indicadores completos → compactos → ocultos, conforme a largura (cada painel já mostra seu estado). */
    private void applyResponsiveLayout(double width) {
        if (width <= 0) {
            return;
        }
        boolean show = width >= COMPACT_CHIPS_FROM;
        chips.setVisible(show);
        chips.setManaged(show);
        boolean compact = width < FULL_CHIPS_FROM;
        chipFirst.setCompact(compact);
        chipSecond.setCompact(compact);
    }

    @FXML
    private void addFirst() {
        onAdd.accept(first);
    }

    @FXML
    private void addSecond() {
        onAdd.accept(second);
    }

    @FXML
    private void toggleChat() {
        onChatToggle.accept(chatToggle.isSelected());
    }

    @FXML
    private void openSettings() {
        onSettings.run();
    }
}

