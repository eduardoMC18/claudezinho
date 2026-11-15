package com.estudotron.component;

import com.estudotron.component.FlowField.Band;
import com.estudotron.component.FlowField.Strand;
import com.estudotron.component.FlowField.Tint;
import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.stage.Window;

import java.lang.management.ManagementFactory;

/**
 * "Liquid Flow Background": um ambiente branco com fios de vidro fluidos, faixas de luz, reflexos iridescentes
 * discretos e linhas de construção finas. Existe para dar ao vidro algo para refratar: os fios e as linhas
 * atravessam (inclusive) as regiões onde ficam os botões, e os painéis de vidro, translúcidos, deixam vê-los.
 *
 * <p><b>Desenho.</b> Duas camadas de {@link Canvas}: a fixa (linhas de construção, cruzes, pontos) é desenhada só
 * quando a janela muda de tamanho; a animada (fios e faixas) é redesenhada a 20 quadros por segundo, com o tempo
 * acumulado de forma contínua (movimento lento, 1 a 2 minutos por ciclo).
 *
 * <p><b>Economia.</b> Não anima com a janela sem foco, minimizada ou oculta. E, enquanto o cursor está sobre um
 * botão, o movimento desacelera até parar (e volta devagar ao sair): o vidro líquido captura o que há atrás do
 * botão ao entrar, e com o fundo parado essa imagem continua coincidindo com o que está em volta.
 *
 * <p>O fundo fica atrás de tudo e não intercepta o mouse.
 */
public final class AmbientBackground extends Pane {

    private static final long FRAME_NANOS = 50_000_000L;   // 20 quadros por segundo
    private static final double STEP = 10;                 // espaçamento horizontal das amostras das curvas
    private static final boolean DEBUG = Boolean.getBoolean("estudotron.bg.debug");
    /** Em testes automatizados a janela pode não ter foco: -Destudotron.bg.alwaysAnimate=true ignora isso. */
    private static final boolean ALWAYS = Boolean.getBoolean("estudotron.bg.alwaysAnimate");

    private static final Color SILVER = Color.rgb(100, 116, 148);

    private final FlowField field = new FlowField(42);
    private final Region base = new Region();
    private final Canvas fixedLayer = new Canvas();
    private final Canvas flowLayer = new Canvas();
    private final AnimationTimer timer;

    private double time;
    private double timeScale = 1;
    private double targetScale = 1;
    private long last;
    private boolean windowActive = true;
    private double[] xs = new double[0];
    private double[] ys = new double[0];

    // medição (só com -Destudotron.bg.debug=true)
    private long drawNanos;
    private long drawnFrames;
    private long reportedAt;

    private final EventHandler<MouseEvent> pointerHandler = this::onPointer;
    private final EventHandler<MouseEvent> leaveHandler = e -> targetScale = 1;
    private final ChangeListener<Boolean> activityListener = (obs, old, value) -> updateActivity();
    private Window observedWindow;

    public AmbientBackground() {
        getStyleClass().add("ambient");
        base.getStyleClass().add("ambient-base");
        getChildren().addAll(base, fixedLayer, flowLayer);
        for (Node layer : new Node[] {base, fixedLayer, flowLayer}) {
            layer.setManaged(false);
            layer.setMouseTransparent(true);
        }
        setMouseTransparent(true);
        setMinSize(0, 0);

        timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                frame(now);
            }
        };
        sceneProperty().addListener((obs, old, scene) -> attach(old, scene));
    }

    // ------------------------------------------------------------------ ciclo de vida

    private void attach(Scene old, Scene scene) {
        if (old != null) {
            old.removeEventFilter(MouseEvent.MOUSE_MOVED, pointerHandler);
            old.removeEventFilter(MouseEvent.MOUSE_DRAGGED, pointerHandler);
            old.removeEventFilter(MouseEvent.MOUSE_EXITED, leaveHandler);
        }
        if (observedWindow != null) {
            observedWindow.focusedProperty().removeListener(activityListener);
            observedWindow.showingProperty().removeListener(activityListener);
            observedWindow = null;
        }
        if (scene == null) {
            timer.stop();
            return;
        }
        // enquanto o cursor está sobre um botão, o fundo desacelera (ver a documentação da classe)
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, pointerHandler);
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, pointerHandler);
        scene.addEventFilter(MouseEvent.MOUSE_EXITED, leaveHandler);
        scene.windowProperty().addListener((obs, was, window) -> observe(window));
        observe(scene.getWindow());
    }

    private void observe(Window window) {
        if (observedWindow != null) {
            observedWindow.focusedProperty().removeListener(activityListener);
            observedWindow.showingProperty().removeListener(activityListener);
        }
        observedWindow = window;
        if (window != null) {
            window.focusedProperty().addListener(activityListener);
            window.showingProperty().addListener(activityListener);
        }
        updateActivity();
    }

    private void updateActivity() {
        Window window = observedWindow;
        boolean showing = window != null && window.isShowing();
        boolean iconified = window instanceof javafx.stage.Stage stage && stage.isIconified();
        windowActive = showing && !iconified && (ALWAYS || window.isFocused());
        if (windowActive) {
            last = 0;
            timer.start();
        } else {
            timer.stop();
        }
    }

    private void onPointer(MouseEvent event) {
        targetScale = overButton(event.getTarget()) ? 0 : 1;
    }

    private static boolean overButton(EventTarget target) {
        return LiquidGlass.isOverLiquid(target);   // botões e cartões com vidro líquido
    }

    // ------------------------------------------------------------------ layout e quadros

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        base.resizeRelocate(0, 0, w, h);
        if (w < 2 || h < 2) {
            return;
        }
        boolean resized = fixedLayer.getWidth() != w || fixedLayer.getHeight() != h;
        if (resized) {
            fixedLayer.setWidth(w);
            fixedLayer.setHeight(h);
            flowLayer.setWidth(w);
            flowLayer.setHeight(h);
            xs = new double[(int) Math.ceil(w / STEP) + 3];
            ys = new double[xs.length];
            drawFixed();
            drawFlow();
        }
    }

    private void frame(long now) {
        if (last == 0) {
            last = now;
            return;
        }
        long elapsed = now - last;
        if (elapsed < FRAME_NANOS) {
            return;
        }
        last = now;
        double dt = Math.min(elapsed / 1e9, 0.25);
        // desacelera/retoma suavemente (constante de ~0,45 s), sem arranques bruscos
        timeScale += (targetScale - timeScale) * (1 - Math.exp(-dt * 2.2));
        if (timeScale < 0.003 && targetScale == 0) {
            return;                       // parado: nenhum redesenho, custo zero
        }
        time += dt * timeScale;
        long started = System.nanoTime();
        drawFlow();
        if (DEBUG) {
            report(System.nanoTime() - started, now);
        }
    }

    private void report(long nanos, long now) {
        drawNanos += nanos;
        drawnFrames++;
        if (reportedAt == 0) {
            reportedAt = now;
        }
        if (now - reportedAt > 5_000_000_000L) {
            double cpu = -1;
            try {
                cpu = ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
                        .getProcessCpuLoad() * 100;
            } catch (Throwable ignored) {
                // sem a extensão da JVM: só o tempo de desenho
            }
            System.out.printf("[fundo] %d quadros, desenho medio %.2f ms/quadro, CPU do processo %.1f%%%n",
                    drawnFrames, drawNanos / 1e6 / drawnFrames, cpu);
            drawNanos = 0;
            drawnFrames = 0;
            reportedAt = now;
        }
    }

    // ------------------------------------------------------------------ desenho

    /** Linhas de construção, cruzes e pontos: finos, nítidos, quase invisíveis; só redesenhados ao redimensionar. */
    private void drawFixed() {
        double w = fixedLayer.getWidth();
        double h = fixedLayer.getHeight();
        GraphicsContext gc = fixedLayer.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        gc.setLineWidth(1.4);
        gc.setStroke(Color.rgb(84, 100, 136, 0.30));
        for (double f : field.hairlinesH()) {
            double y = Math.round(f * h) + 0.5;
            gc.strokeLine(0, y, w, y);
        }
        for (double f : field.hairlinesV()) {
            double x = Math.round(f * w) + 0.5;
            gc.strokeLine(x, 0, x, h);
        }
        // nas interseções: cruzes pequenas e pontos, alternados
        gc.setStroke(Color.rgb(72, 88, 122, 0.55));
        gc.setFill(Color.rgb(72, 88, 122, 0.55));
        int k = 0;
        for (double fv : field.hairlinesV()) {
            for (double fh : field.hairlinesH()) {
                double x = Math.round(fv * w) + 0.5;
                double y = Math.round(fh * h) + 0.5;
                if ((k++ & 1) == 0) {
                    gc.strokeLine(x - 7, y, x + 7, y);
                    gc.strokeLine(x, y - 7, x, y + 7);
                } else {
                    gc.fillOval(x - 2, y - 2, 4, 4);
                }
            }
        }
        // dois contornos finos (linhas de construção, não manchas) numa interseção
        double cx = Math.round(field.hairlinesV()[3] * w) + 0.5;
        double cy = Math.round(field.hairlinesH()[4] * h) + 0.5;
        gc.setStroke(Color.rgb(90, 106, 140, 0.24));
        gc.strokeOval(cx - 58, cy - 58, 116, 116);
        gc.strokeOval(cx - 112, cy - 112, 224, 224);
    }

    /** Faixas de luz e fios de vidro, no instante {@code time}. */
    private void drawFlow() {
        double w = flowLayer.getWidth();
        double h = flowLayer.getHeight();
        if (w < 2 || h < 2) {
            return;
        }
        GraphicsContext gc = flowLayer.getGraphicsContext2D();
        gc.clearRect(0, 0, w, h);
        gc.setLineCap(StrokeLineCap.BUTT);

        // faixas de luz: traços largos empilhados (prata muito suave por baixo, branco luminoso por cima)
        for (Band band : field.bands()) {
            Strand s = field.strands().get(band.strandIndex());
            int n = sample(s, w, h);
            gc.setStroke(Color.rgb(150, 164, 192, 0.045));
            gc.setLineWidth(band.width() * 1.25);
            gc.strokePolyline(xs, ys, n);
            double[] widths = {band.width(), band.width() * 0.62, band.width() * 0.28};
            double[] alphas = {band.alpha() * 0.45, band.alpha() * 0.60, band.alpha()};
            for (int i = 0; i < widths.length; i++) {
                gc.setStroke(Color.rgb(255, 255, 255, alphas[i]));
                gc.setLineWidth(widths[i]);
                gc.strokePolyline(xs, ys, n);
            }
        }

        // reflexo iridescente: o gradiente (prata → ciano → prata → magenta → prata) desliza devagar ao longo do fio
        double shift = -(time * 6) % (w * 1.8);
        LinearGradient iridescent = new LinearGradient(shift, 0, shift + w * 0.9, 0, false, CycleMethod.REFLECT,
                new Stop(0.00, Color.rgb(140, 156, 184, 0.34)),
                new Stop(0.24, Color.rgb(70, 190, 232, 0.55)),
                new Stop(0.50, Color.rgb(160, 172, 196, 0.40)),
                new Stop(0.76, Color.rgb(228, 96, 198, 0.50)),
                new Stop(1.00, Color.rgb(140, 156, 184, 0.34)));

        for (Strand s : field.strands()) {
            int n = sample(s, w, h);
            if (s.tint() == Tint.TUBE) {
                drawTube(gc, s, n);
                continue;
            }
            switch (s.tint()) {
                case WHITE -> gc.setStroke(Color.rgb(255, 255, 255, s.alpha()));
                case IRIDESCENT -> gc.setStroke(iridescent);
                default -> gc.setStroke(Color.color(SILVER.getRed(), SILVER.getGreen(), SILVER.getBlue(), s.alpha()));
            }
            gc.setLineWidth(s.width());
            gc.strokePolyline(xs, ys, n);
        }
    }

    /** Tubo de vidro: corpo prateado translúcido, aresta fina mais escura embaixo e núcleo branco de reflexo em cima. */
    private void drawTube(GraphicsContext gc, Strand s, int n) {
        gc.setStroke(Color.rgb(120, 136, 168, 0.16));
        gc.setLineWidth(s.width());
        gc.strokePolyline(xs, ys, n);
        gc.save();
        gc.translate(0, s.width() * 0.5);
        gc.setStroke(Color.rgb(90, 106, 140, 0.34));
        gc.setLineWidth(1);
        gc.strokePolyline(xs, ys, n);                 // aresta inferior
        gc.translate(0, -s.width());
        gc.setStroke(Color.rgb(255, 255, 255, 0.92));
        gc.setLineWidth(1.6);
        gc.strokePolyline(xs, ys, n);                 // reflexo superior
        gc.restore();
    }

    /** Amostra a curva do fio em {@link #xs}/{@link #ys}; devolve o número de pontos. */
    private int sample(Strand s, double w, double h) {
        int n = 0;
        for (double x = -STEP; x <= w + STEP && n < xs.length; x += STEP) {
            xs[n] = x;
            ys[n] = FlowField.y(s, x, time, w, h);
            n++;
        }
        return n;
    }
}
