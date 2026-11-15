package com.estudotron.component;

import javafx.animation.AnimationTimer;
import javafx.event.EventTarget;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.ButtonBase;
import javafx.scene.effect.DisplacementMap;
import javafx.scene.effect.FloatMap;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.transform.Transform;

/**
 * Vidro líquido nos botões: refração dinâmica do que está ATRÁS do botão, que acompanha o cursor.
 *
 * <p><b>Como funciona</b> (o JavaFX CSS não consegue isto sozinho):
 * <ol>
 *   <li>Ao entrar no botão, captura-se UMA vez a região da cena atrás dele, mais uma margem de 16 px (o botão
 *       fica transparente só durante a captura, no mesmo pulso: nada pisca). Nunca se captura a janela inteira,
 *       e nada é capturado a cada movimento do mouse.</li>
 *   <li>Essa imagem entra numa camada irmã posicionada logo ABAIXO do botão (o vidro translúcido do botão a
 *       deixa aparecer; o texto e o ícone ficam por cima, nítidos). Um {@link DisplacementMap} deforma a imagem
 *       conforme o campo calculado por {@link LiquidSurface}: rim que curva a imagem, lente, bolha e anel
 *       localizados no cursor.</li>
 *   <li>A cada quadro, uma mola suaviza a posição do cursor (inércia), a reatividade sobe/desce e a pressão
 *       do clique aprofunda a deformação. Os reflexos são calculados do próprio campo de deformação (a
 *       superfície deformada reflete a luz) e desenhados em uma segunda camada fina, acima do botão.</li>
 *   <li>Ao sair, a reatividade volta a zero gradualmente; no fim as camadas são removidas (custo zero em repouso).</li>
 * </ol>
 *
 * <p>Instalado uma vez por cena ({@link #install(Scene)}); vale para todo {@link ButtonBase} (botões e botões
 * de alternância) cujo pai seja um {@code Pane}. Botões com a classe {@code no-liquid} ficam de fora.
 */
public final class LiquidGlass {

    private static final String PROP = "liquid.glass.controller";
    private static final String ACTIVE_CLASS = "liquid-active";
    private static final String OPT_OUT = "no-liquid";
    private static final int MARGIN = 16;
    private static final double DEFAULT_RADIUS = 12;
    /** -Destudotron.liquid.debug=true: imprime o custo medio por quadro ao fim de cada interacao. */
    private static final boolean DEBUG = Boolean.getBoolean("estudotron.liquid.debug");

    private LiquidGlass() {}

    /** Liga o efeito a todos os botões da cena (inclusive os criados depois), no primeiro hover de cada um. */
    public static void install(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, event -> {
            ButtonBase button = findButton(event.getTarget());
            if (button == null || button.getProperties().containsKey(PROP)) {
                return;
            }
            if (!(button.getParent() instanceof Pane) || button.getStyleClass().contains(OPT_OUT)) {
                button.getProperties().put(PROP, Boolean.FALSE);   // sem suporte: não tenta de novo
                return;
            }
            Refraction refraction = new Refraction(button);
            button.getProperties().put(PROP, refraction);
            refraction.enter(event.getSceneX(), event.getSceneY());
        });
    }

    private static ButtonBase findButton(EventTarget target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node instanceof ButtonBase button) {
                return button;
            }
            node = node.getParent();
        }
        return null;
    }

    /** Estado e animação de UM botão. */
    private static final class Refraction {

        private final ButtonBase button;
        private final Pane refractLayer = new Pane();
        private final Pane lightLayer = new Pane();
        private final ImageView refractView = new ImageView();
        private final ImageView lightView = new ImageView();
        private final DisplacementMap displacement = new DisplacementMap();
        private final AnimationTimer timer;

        private LiquidSurface surface;
        private WritableImage lightImage;
        private int[] lightPixels;
        private boolean installed;

        // cursor-alvo e cursor suavizado (coordenadas locais do botão), velocidade, reatividade, pressão
        private double targetX;
        private double targetY;
        private double x;
        private double y;
        private double vx;
        private double vy;
        private double strength;
        private double targetStrength;
        private double press;
        private double targetPress;
        private long last;
        private long computeNanos;
        private long frames;

        Refraction(ButtonBase button) {
            this.button = button;
            for (Pane layer : new Pane[] {refractLayer, lightLayer}) {
                layer.setManaged(false);
                layer.setMouseTransparent(true);
                layer.setFocusTraversable(false);
            }
            refractView.setSmooth(true);
            refractView.setPreserveRatio(false);
            refractView.setEffect(displacement);
            lightView.setSmooth(true);
            lightView.setPreserveRatio(false);
            refractLayer.getChildren().add(refractView);
            lightLayer.getChildren().add(lightView);

            button.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> enter(e.getSceneX(), e.getSceneY()));
            button.addEventHandler(MouseEvent.MOUSE_MOVED, e -> move(e.getSceneX(), e.getSceneY()));
            button.addEventHandler(MouseEvent.MOUSE_DRAGGED, e -> move(e.getSceneX(), e.getSceneY()));
            button.addEventHandler(MouseEvent.MOUSE_EXITED, e -> exit());
            // um botão desabilitado sob o cursor não recebe mais eventos: a película precisa se recompor sozinha
            button.disabledProperty().addListener((obs, was, disabled) -> {
                if (disabled) {
                    exit();
                }
            });
            button.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> targetPress = 1);
            button.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> targetPress = 0);

            timer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    frame(now);
                }
            };
        }

        // ------------------------------------------------------------------ eventos

        void enter(double sceneX, double sceneY) {
            if (button.getScene() == null) {
                return;
            }
            if (!installed && !install()) {
                return;
            }
            toLocal(sceneX, sceneY);
            if (strength < 0.02) {          // entrada "fria": a película nasce sob o cursor
                x = targetX;
                y = targetY;
                vx = 0;
                vy = 0;
            }
            targetStrength = 1;
            if (!button.getStyleClass().contains(ACTIVE_CLASS)) {
                button.getStyleClass().add(ACTIVE_CLASS);   // o vidro do botão fica mais transparente (CSS)
            }
            last = 0;
            timer.start();
        }

        void move(double sceneX, double sceneY) {
            if (!installed) {
                if (targetStrength > 0 && button.getScene() != null) {   // recaptura depois de uma mudança de tamanho
                    enter(sceneX, sceneY);
                }
                return;
            }
            toLocal(sceneX, sceneY);
        }

        void exit() {
            targetStrength = 0;
            targetPress = 0;
        }

        private void toLocal(double sceneX, double sceneY) {
            var p = button.sceneToLocal(sceneX, sceneY);
            if (p != null) {
                targetX = p.getX();
                targetY = p.getY();
            }
        }

        // ------------------------------------------------------------------ montagem

        /** Captura o que está atrás do botão e monta as camadas. false se não for possível. */
        private boolean install() {
            Scene scene = button.getScene();
            Pane parent = button.getParent() instanceof Pane p ? p : null;
            double w = button.getLayoutBounds().getWidth();
            double h = button.getLayoutBounds().getHeight();
            if (scene == null || parent == null || w < 8 || h < 8) {
                return false;
            }
            Bounds onScene = button.localToScene(button.getLayoutBounds());
            double scale = scene.getWindow() != null ? Math.max(1, scene.getWindow().getOutputScaleX()) : 1;

            // margem de captura, limitada à cena
            int left = (int) Math.max(0, Math.min(MARGIN, Math.floor(onScene.getMinX())));
            int top = (int) Math.max(0, Math.min(MARGIN, Math.floor(onScene.getMinY())));
            int right = (int) Math.max(0, Math.min(MARGIN, Math.floor(scene.getWidth() - onScene.getMaxX())));
            int bottom = (int) Math.max(0, Math.min(MARGIN, Math.floor(scene.getHeight() - onScene.getMaxY())));
            int iw = (int) Math.round(w) + left + right;
            int ih = (int) Math.round(h) + top + bottom;

            SnapshotParameters params = new SnapshotParameters();
            params.setFill(Color.TRANSPARENT);
            params.setTransform(Transform.scale(scale, scale));
            params.setViewport(new Rectangle2D((onScene.getMinX() - left) * scale, (onScene.getMinY() - top) * scale,
                    iw * scale, ih * scale));

            // o botão (e as camadas, se já existiram) ficam transparentes só durante a captura, no mesmo pulso
            double previous = button.getOpacity();
            button.setOpacity(0);
            WritableImage backdrop;
            try {
                backdrop = scene.getRoot().snapshot(params, null);
            } finally {
                button.setOpacity(previous);
            }

            surface = new LiquidSurface((int) Math.round(w), (int) Math.round(h), cornerRadius(), left, top, right, bottom);
            refractView.setImage(backdrop);
            refractView.setFitWidth(surface.imageWidth());
            refractView.setFitHeight(surface.imageHeight());
            refractView.setLayoutX(-left);
            refractView.setLayoutY(-top);

            lightPixels = new int[(int) Math.round(w) * (int) Math.round(h)];
            lightImage = new WritableImage((int) Math.round(w), (int) Math.round(h));
            lightView.setImage(lightImage);
            lightView.setFitWidth(Math.round(w));
            lightView.setFitHeight(Math.round(h));

            for (Pane layer : new Pane[] {refractLayer, lightLayer}) {
                layer.setPrefSize(w, h);
                layer.resize(w, h);
                Rectangle clip = new Rectangle(w, h);
                clip.setArcWidth(cornerRadius() * 2);
                clip.setArcHeight(cornerRadius() * 2);
                layer.setClip(clip);
                layer.scaleXProperty().bind(button.scaleXProperty());
                layer.scaleYProperty().bind(button.scaleYProperty());
                layer.translateXProperty().bind(button.translateXProperty());
                layer.translateYProperty().bind(button.translateYProperty());
            }
            syncPosition();

            // camada refratada logo ABAIXO do botão (o texto fica por cima, nítido); reflexos logo ACIMA
            int index = parent.getChildren().indexOf(button);
            parent.getChildren().add(index, refractLayer);
            parent.getChildren().add(index + 2, lightLayer);
            installed = true;
            return true;
        }

        private double cornerRadius() {
            Background background = button.getBackground();
            if (background != null && !background.getFills().isEmpty()) {
                BackgroundFill fill = background.getFills().get(0);
                double r = fill.getRadii().getTopLeftHorizontalRadius();
                if (r > 0) {
                    return Math.min(r, Math.min(button.getLayoutBounds().getWidth(),
                            button.getLayoutBounds().getHeight()) / 2);
                }
            }
            return DEFAULT_RADIUS;
        }

        private void syncPosition() {
            for (Pane layer : new Pane[] {refractLayer, lightLayer}) {
                layer.setLayoutX(button.getLayoutX());
                layer.setLayoutY(button.getLayoutY());
            }
        }

        private void teardown() {
            timer.stop();
            if (DEBUG && frames > 0) {
                System.out.printf("[liquid] %d quadros, calculo medio %.2f ms/quadro (campo %dx%d)%n", frames,
                        computeNanos / 1e6 / frames, surface == null ? 0 : surface.imageWidth(),
                        surface == null ? 0 : surface.imageHeight());
                frames = 0;
                computeNanos = 0;
            }
            button.getStyleClass().remove(ACTIVE_CLASS);
            if (installed) {
                for (Pane layer : new Pane[] {refractLayer, lightLayer}) {
                    layer.scaleXProperty().unbind();
                    layer.scaleYProperty().unbind();
                    layer.translateXProperty().unbind();
                    layer.translateYProperty().unbind();
                    layer.setClip(null);
                    if (layer.getParent() instanceof Pane parent) {
                        parent.getChildren().remove(layer);
                    }
                }
            }
            refractView.setImage(null);
            lightView.setImage(null);
            installed = false;
            strength = 0;
            press = 0;
        }

        // ------------------------------------------------------------------ animação

        private void frame(long now) {
            if (button.getScene() == null || !installed) {
                teardown();
                return;
            }
            double dt = last == 0 ? 1 / 60.0 : Math.max(0.001, Math.min(0.05, (now - last) / 1e9));
            last = now;

            // mola do cursor: inércia (segue o mouse com atraso suave) e velocidade suavizada para o arrasto
            double follow = 1 - Math.exp(-dt * 15);
            double nx = x + (targetX - x) * follow;
            double ny = y + (targetY - y) * follow;
            double ivx = (nx - x) / dt;
            double ivy = (ny - y) / dt;
            double smooth = 1 - Math.exp(-dt * 12);
            vx += (ivx - vx) * smooth;
            vy += (ivy - vy) * smooth;
            x = nx;
            y = ny;

            // reatividade: entra com rapidez moderada e sai devagar (a película "se recompõe")
            double rate = targetStrength > strength ? 8 : 4.5;
            strength += (targetStrength - strength) * (1 - Math.exp(-dt * rate));
            press += (targetPress - press) * (1 - Math.exp(-dt * 16));

            if (targetStrength == 0 && strength < 0.004) {
                teardown();
                return;
            }
            // o botão mudou de tamanho (janela redimensionada, texto novo): a captura não vale mais.
            // As camadas saem; o próximo movimento do mouse captura de novo.
            if ((int) Math.round(button.getLayoutBounds().getWidth()) != surface.width()
                    || (int) Math.round(button.getLayoutBounds().getHeight()) != surface.height()) {
                teardown();
                return;
            }
            syncPosition();
            long started = System.nanoTime();
            render();
            computeNanos += System.nanoTime() - started;
            frames++;
        }

        private void render() {
            surface.compute(x, y, vx, vy, strength, press);

            int iw = surface.imageWidth();
            int ih = surface.imageHeight();
            FloatMap map = new FloatMap(iw, ih);
            for (int j = 0; j < ih; j++) {
                for (int i = 0; i < iw; i++) {
                    // o mapa usa deslocamentos normalizados pelo tamanho da imagem
                    map.setSamples(i, j, surface.offsetX(i, j) / iw, surface.offsetY(i, j) / ih);
                }
            }
            displacement.setMapData(map);

            int w = (int) lightImage.getWidth();
            int h = (int) lightImage.getHeight();
            surface.lighting(lightPixels, strength);
            lightImage.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), lightPixels, 0, w);
        }
    }
}
