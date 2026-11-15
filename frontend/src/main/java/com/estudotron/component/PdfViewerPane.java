package com.estudotron.component;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSException;
import netscape.javascript.JSObject;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Visualizador PDF.js dentro de uma WebView, sem a barra de ferramentas da página
 * (os controles são nativos do JavaFX). Cada instância tem página, zoom e rolagem próprios.
 */
public final class PdfViewerPane extends StackPane {

    /** Ponte chamada pelo JavaScript. Precisa ser pública para a WebView enxergar. */
    public final class Bridge {
        public void onStatus(String message) {
            System.out.println("[viewer] " + message);
            Platform.runLater(() -> statusListener.accept(message));
        }

        public void onPageChanged(int page, int total) {
            Platform.runLater(() -> pageListener.accept(page, total));
        }

        public void onZoomChanged(int percent, boolean fit) {
            Platform.runLater(() -> zoomListener.accept(percent, fit));
        }
    }

    private final WebView webView = new WebView();
    private final Bridge bridge = new Bridge(); // referência forte: evita coleta pelo GC
    private Consumer<String> statusListener = s -> { };
    private BiConsumer<Integer, Integer> pageListener = (page, total) -> { };
    private BiConsumer<Integer, Boolean> zoomListener = (percent, fit) -> { };

    public PdfViewerPane() {
        webView.setContextMenuEnabled(false);
        webView.setPageFill(Color.web("#e9edf3"));
        webView.setVisible(false);
        getChildren().add(webView);
        setMinSize(0, 0);

        WebEngine engine = webView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == Worker.State.SUCCEEDED && webView.isVisible()) {
                Object window = engine.executeScript("window");
                if (window instanceof JSObject js) {
                    js.setMember("javaBridge", bridge);
                }
                call("window.viewer && window.viewer.republish()"); // reenvia página/zoom já calculados
            } else if (state == Worker.State.FAILED) {
                statusListener.accept("Falha ao carregar o visualizador");
            }
        });
    }

    public void setStatusListener(Consumer<String> listener) {
        this.statusListener = listener;
    }

    /** (página atual, total de páginas), na thread do JavaFX. */
    public void setPageListener(BiConsumer<Integer, Integer> listener) {
        this.pageListener = listener;
    }

    /** (zoom em %, ajuste automático ligado?), na thread do JavaFX. */
    public void setZoomListener(BiConsumer<Integer, Boolean> listener) {
        this.zoomListener = listener;
    }

    /** Carrega o visualizador servido pelo backend (sem a barra interna da página). */
    public void show(String viewerUrl) {
        webView.setVisible(true);
        webView.getEngine().load(viewerUrl + "&chrome=0");
    }

    public void clear() {
        webView.setVisible(false);
        webView.getEngine().load("about:blank");
    }

    public void goToPage(int page) {
        call("window.viewer && window.viewer.goToPage(" + page + ")");
    }

    public void previousPage() {
        call("window.viewer && window.viewer.prevPage()");
    }

    public void nextPage() {
        call("window.viewer && window.viewer.nextPage()");
    }

    public void zoomIn() {
        call("window.viewer && window.viewer.zoomIn()");
    }

    public void zoomOut() {
        call("window.viewer && window.viewer.zoomOut()");
    }

    public void setFit(boolean fit) {
        call("window.viewer && window.viewer.setFit(" + fit + ")");
    }

    private void call(String script) {
        if (!webView.isVisible()) {
            return;
        }
        try {
            webView.getEngine().executeScript(script);
        } catch (JSException e) {
            System.out.println("[viewer] erro de script: " + e.getMessage());
        }
    }
}
