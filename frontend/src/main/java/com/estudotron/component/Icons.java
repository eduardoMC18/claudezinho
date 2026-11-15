package com.estudotron.component;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/**
 * Ícones discretos de traço (grade 24x24, estilo Lucide), desenhados como SVGPath.
 * A cor vem do CSS (classe {@code icon-path}); nenhuma imagem ou dependência externa.
 */
public final class Icons {

    public enum Icon {
        PLUS("M5 12h14 M12 5v14"),
        MINUS("M5 12h14"),
        CHEVRON_LEFT("m15 18-6-6 6-6"),
        CHEVRON_RIGHT("m9 18 6-6-6-6"),
        CLOSE("M18 6 6 18 M6 6l12 12"),
        FILE("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M16 13H8 M16 17H8 M10 9H8"),
        SETTINGS("M21 4h-7 M10 4H3 M21 12h-9 M8 12H3 M21 20h-5 M12 20H3 M14 2v4 M8 10v4 M16 18v4"),
        SEND("m22 2-7 20-4-9-9-4Z M22 2 11 13"),
        UPLOAD("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4 M17 8l-5-5-5 5 M12 3v12"),
        SPARKLE("M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z M19 16v4 M17 18h4"),
        INFO("M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M12 16v-4 M12 8h.01"),
        ALERT("M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z M12 9v4 M12 17h.01"),
        CHECK("M20 6 9 17l-5-5"),
        BOOK("M2 4h6a4 4 0 0 1 4 4v13a3 3 0 0 0-3-3H2z M22 4h-6a4 4 0 0 0-4 4v13a3 3 0 0 1 3-3h7z"),
        COPY("M9 9h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V11a2 2 0 0 1 2-2z M5 15H4a2 2 0 0 1-2-2V3a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v1"),
        STOP("M7 7h10v10H7z"),
        NEW_CHAT("M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z M12 7v6 M9 10h6"),
        KEY("M21 2l-2 2 M11.4 11.6a5.5 5.5 0 1 1-7.8 7.8 5.5 5.5 0 0 1 7.8-7.8z M11.4 11.6 15.5 7.5 M15.5 7.5l3 3L22 7l-3-3"),
        SHIELD("M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z M9 12l2 2 4-4"),
        PANEL_LEFT("M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z M9 3v18"),
        LIST_CHECK("m3 17 2 2 4-4 M3 7l2 2 4-4 M13 6h8 M13 12h8 M13 18h8"),
        PEN_LINE("M12 20h9 M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4z"),
        ARROW_RIGHT("M5 12h14 M12 5l7 7-7 7"),
        ARROW_LEFT("M19 12H5 M12 19l-7-7 7-7"),
        CHECK_CIRCLE("M22 11.08V12a10 10 0 1 1-5.93-9.14 M22 4 12 14.01l-3-3"),
        X_CIRCLE("M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z M15 9l-6 6 M9 9l6 6"),
        REFRESH("M21 12a9 9 0 1 1-3-6.7 M21 3v6h-6"),
        LIGHTBULB("M9 18h6 M10 22h4 M12 2a7 7 0 0 0-4 12.7c.7.6 1 1.5 1 2.3h6c0-.8.3-1.7 1-2.3A7 7 0 0 0 12 2z"),
        TRASH("M3 6h18 M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6 M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2");

        private final String path;

        Icon(String path) {
            this.path = path;
        }
    }

    private Icons() {}

    public static Node create(Icon icon, double size) {
        SVGPath path = new SVGPath();
        path.setContent(icon.path);
        path.getStyleClass().add("icon-path");

        Group scaled = new Group(path);
        double factor = size / 24.0;
        scaled.setScaleX(factor);
        scaled.setScaleY(factor);

        StackPane box = new StackPane(scaled);
        box.getStyleClass().add("icon");
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        box.setMouseTransparent(true);
        return box;
    }

    public static void set(Labeled target, Icon icon, double size) {
        target.setGraphic(create(icon, size));
    }
}
