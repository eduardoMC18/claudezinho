package com.estudotron.component;

import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

/**
 * Cartão de vidro flutuante. Desenha, por baixo do conteúdo, uma sombra difusa em um nó irmão (um efeito no pai de
 * uma WebView obrigaria o PDF a ser renderizado fora da tela a cada quadro).
 *
 * <p>O vidro em si (véu translúcido, reflexos, aresta e luz na borda) vem do CSS ({@code .glass}). O cartão não
 * pinta nada do fundo: o ambiente animado ({@link AmbientBackground}) aparece, nítido, através do vidro — é isso
 * que dá aos botões Liquid Glass algo para refratar.
 */
public class GlassCard extends StackPane {

    private static final double SHADOW_INSET = 6;

    private final Region shadow = new Region();

    public GlassCard() {
        getStyleClass().add("glass-card");
        shadow.getStyleClass().add("glass-shadow");
        shadow.setManaged(false);
        shadow.setMouseTransparent(true);
        getChildren().add(shadow);
        setMinSize(0, 0);
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        double w = getWidth();
        double h = getHeight();
        shadow.resizeRelocate(SHADOW_INSET, SHADOW_INSET + 5, Math.max(0, w - 2 * SHADOW_INSET),
                Math.max(0, h - 2 * SHADOW_INSET));
        shadow.toBack();
    }
}
