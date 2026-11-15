package com.estudotron.component;

import javafx.beans.value.ChangeListener;
import javafx.geometry.Point2D;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;

/**
 * Cartão de vidro flutuante. Desenha, por baixo do conteúdo:
 * <ol>
 *   <li>uma sombra difusa (em um nó irmão — um efeito no pai de uma WebView obrigaria o PDF a ser
 *       renderizado fora da tela a cada quadro);</li>
 *   <li>o <b>fundo desfocado</b>: o pedaço da imagem borrada do {@link AmbientBackground} que está
 *       exatamente atrás do cartão. É isso que faz o vidro parecer fosco de verdade.</li>
 * </ol>
 * O material do vidro em si (véu branco, reflexos, borda de luz) vem do CSS ({@code .glass}).
 */
public class GlassCard extends StackPane {

    private static final double SHADOW_INSET = 6;
    private static final double ARC = 44; // diâmetro do canto (raio 22, igual ao CSS)

    private final Region shadow = new Region();
    private final ImageView backdrop = new ImageView();
    private final Pane backdropHolder = new Pane(backdrop);
    private final Rectangle backdropClip = new Rectangle();
    private AmbientBackground ambient;
    private final ChangeListener<Image> frostListener = (obs, old, image) -> {
        backdrop.setImage(image);
        positionBackdrop();
    };

    public GlassCard() {
        getStyleClass().add("glass-card");
        shadow.getStyleClass().add("glass-shadow");
        shadow.setManaged(false);
        shadow.setMouseTransparent(true);

        backdrop.setSmooth(true);
        backdrop.setPreserveRatio(false);
        backdropHolder.setManaged(false);
        backdropHolder.setMouseTransparent(true);
        backdropClip.setArcWidth(ARC);
        backdropClip.setArcHeight(ARC);
        backdropHolder.setClip(backdropClip);

        getChildren().addAll(shadow, backdropHolder);
        setMinSize(0, 0);

        sceneProperty().addListener((obs, old, scene) -> attach(AmbientBackground.of(scene)));
        // O cartão pode se mover sem mudar de tamanho (divisor, rolagem): realinha o fundo.
        localToSceneTransformProperty().addListener((obs, old, transform) -> positionBackdrop());
    }

    private void attach(AmbientBackground found) {
        if (ambient != null) {
            ambient.frostProperty().removeListener(frostListener);
        }
        ambient = found;
        if (ambient != null) {
            ambient.frostProperty().addListener(frostListener);
            backdrop.setImage(ambient.frostProperty().get());
        } else {
            backdrop.setImage(null);
        }
        positionBackdrop();
    }

    @Override
    protected void layoutChildren() {
        super.layoutChildren();
        double w = getWidth();
        double h = getHeight();
        shadow.resizeRelocate(SHADOW_INSET, SHADOW_INSET + 5, Math.max(0, w - 2 * SHADOW_INSET),
                Math.max(0, h - 2 * SHADOW_INSET));
        backdropHolder.resizeRelocate(0, 0, w, h);
        backdropClip.setWidth(w);
        backdropClip.setHeight(h);
        backdropHolder.toBack();
        shadow.toBack();
        positionBackdrop();
    }

    /** Estica a imagem borrada para o tamanho do fundo e a desloca para coincidir com ele. */
    private void positionBackdrop() {
        if (ambient == null || backdrop.getImage() == null || getScene() == null) {
            return;
        }
        Point2D origin = ambient.sceneToLocal(localToScene(0, 0));
        if (origin == null) {
            return;
        }
        backdrop.setFitWidth(ambient.getWidth());
        backdrop.setFitHeight(ambient.getHeight());
        backdrop.setLayoutX(-origin.getX());
        backdrop.setLayoutY(-origin.getY());
    }
}
