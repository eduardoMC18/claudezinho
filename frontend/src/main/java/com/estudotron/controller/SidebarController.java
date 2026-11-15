package com.estudotron.controller;

import com.estudotron.AppInfo;
import com.estudotron.component.GlassCard;
import com.estudotron.component.Icons;
import com.estudotron.component.Icons.Icon;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * Barra lateral: identidade do app e as seções (Leitor de PDFs, Criar Exercícios). Pode ser recolhida
 * (só ícones, com dica ao passar o mouse) e expandida, com uma animação curta de largura.
 */
public final class SidebarController {

    /** Telas que a barra lateral alterna na área de conteúdo. */
    public enum Section { READER, EXERCISES }

    private static final double EXPANDED_WIDTH = 232;
    private static final double COLLAPSED_WIDTH = 76;
    private static final PseudoClass COLLAPSED = PseudoClass.getPseudoClass("collapsed");
    private static final String PREF_COLLAPSED = "sidebarCollapsed";

    private final Consumer<Section> onNavigate;
    private final Preferences prefs = Preferences.userNodeForPackage(SidebarController.class);
    private final DoubleProperty width = new SimpleDoubleProperty(EXPANDED_WIDTH);
    private Timeline animation;
    private boolean collapsed;
    private boolean autoCollapsed;

    @FXML private GlassCard card;
    @FXML private VBox root;
    @FXML private HBox brand;
    @FXML private StackPane logoMark;
    @FXML private Label appName;
    @FXML private ToggleGroup navGroup;
    @FXML private ToggleButton readerItem;
    @FXML private ToggleButton exercisesItem;
    @FXML private Button collapseButton;

    public SidebarController(Consumer<Section> onNavigate) {
        this.onNavigate = onNavigate;
    }

    @FXML
    private void initialize() {
        appName.setText(AppInfo.NAME);
        logoMark.getChildren().add(Icons.create(Icon.BOOK, 16));
        Icons.set(readerItem, Icon.FILE, 18);
        Icons.set(exercisesItem, Icon.LIST_CHECK, 18);
        Icons.set(collapseButton, Icon.PANEL_LEFT, 17);

        width.addListener((obs, old, w) -> {
            card.setMinWidth(w.doubleValue());
            card.setPrefWidth(w.doubleValue());
            card.setMaxWidth(w.doubleValue());
        });
        // Lembra a última escolha do usuário (padrão: expandida).
        applyState(prefs.getBoolean(PREF_COLLAPSED, false), false);
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    /** Marca a seção ativa (quando a navegação vem de outro lugar, como o clique numa referência). */
    public void select(Section section) {
        (section == Section.READER ? readerItem : exercisesItem).setSelected(true);
    }

    /**
     * Recolhe/expande por uma regra automática (janela estreita), sem gravar a preferência do
     * usuário. A barra só volta a expandir sozinha se foi ela mesma que recolheu.
     */
    public void autoCollapse(boolean narrow) {
        if (narrow && !collapsed) {
            autoCollapsed = true;
            applyState(true, true);
        } else if (!narrow && collapsed && autoCollapsed) {
            autoCollapsed = false;
            applyState(false, true);
        }
    }

    @FXML
    private void toggleCollapsed() {
        autoCollapsed = false;
        applyState(!collapsed, true);
        prefs.putBoolean(PREF_COLLAPSED, collapsed);
    }

    /** Clicar na seção já ativa não a desmarca: sempre há uma seção selecionada. */
    @FXML
    private void openReader() {
        readerItem.setSelected(true);
        onNavigate.accept(Section.READER);
    }

    @FXML
    private void openExercises() {
        exercisesItem.setSelected(true);
        onNavigate.accept(Section.EXERCISES);
    }

    private void applyState(boolean collapse, boolean animate) {
        collapsed = collapse;
        double target = collapse ? COLLAPSED_WIDTH : EXPANDED_WIDTH;
        if (animation != null) {
            animation.stop();
        }
        if (collapse) {
            showLabels(false); // some o texto primeiro, para não espremer palavras durante a animação
        }
        if (!animate) {
            width.set(target);
            showLabels(!collapse);
            return;
        }
        animation = new Timeline(new KeyFrame(Duration.millis(220),
                new KeyValue(width, target, Interpolator.SPLINE(0.22, 0.61, 0.36, 1.0))));
        animation.setOnFinished(e -> showLabels(!collapse));
        animation.play();
    }

    private void showLabels(boolean visible) {
        root.pseudoClassStateChanged(COLLAPSED, !visible);
        appName.setVisible(visible);
        appName.setManaged(visible);
        brand.setAlignment(visible ? Pos.CENTER_LEFT : Pos.CENTER);
        List<ToggleButton> items = List.of(readerItem, exercisesItem);
        for (ToggleButton item : items) {
            item.setContentDisplay(visible ? ContentDisplay.LEFT : ContentDisplay.GRAPHIC_ONLY);
            if (visible) {
                item.setTooltip(null);
            } else {
                Tooltip tip = new Tooltip(item.getText()); // recolhida: a dica identifica o ícone
                tip.setShowDelay(Duration.millis(120));
                item.setTooltip(tip);
            }
        }
        collapseButton.setTooltip(new Tooltip(visible ? "Recolher a barra lateral" : "Expandir a barra lateral"));
    }
}
