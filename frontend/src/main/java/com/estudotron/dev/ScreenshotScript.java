package com.estudotron.dev;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Apoio à verificação visual (só desenvolvimento): executa um roteiro de passos e tira capturas.
 * Ativado por {@code -Dstudydesk.script="..."}; sem a propriedade, não faz nada.
 *
 * <p>Passos separados por vírgula: {@code wait:1500}, {@code size:1000x700},
 * {@code shot:caminho.png} (janela principal), {@code shot2:caminho.png} (janela secundária aberta),
 * {@code close2}, {@code hl:#id=0.13} (brilho de hover), {@code select:#id=1} (item de ComboBox), {@code click:#id} (ou {@code click:#id[1]} para a 2ª ocorrência),
 * {@code exit}.
 */
public final class ScreenshotScript {

    private ScreenshotScript() {}

    public static void runIfRequested(Stage stage, Scene scene) {
        String script = System.getProperty("studydesk.script");
        if (script == null || script.isBlank()) {
            return;
        }
        List<String> steps = new ArrayList<>();
        if (script.startsWith("@")) { // @arquivo.txt: um passo por linha (aceita espaços e acentos; # comenta)
            try {
                for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of(script.substring(1)),
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    if (!line.isBlank() && !line.startsWith("#")) {
                        steps.add(line.strip());
                    }
                }
            } catch (IOException e) {
                System.out.println("[script] não foi possível ler " + script + ": " + e);
                return;
            }
        } else {
            steps.addAll(List.of(script.split(",")));
        }
        next(stage, scene, steps);
    }

    private static void next(Stage stage, Scene scene, List<String> steps) {
        if (steps.isEmpty()) {
            return;
        }
        String step = steps.remove(0).trim();
        String name = step.contains(":") ? step.substring(0, step.indexOf(':')) : step;
        String arg = step.contains(":") ? step.substring(step.indexOf(':') + 1) : "";
        long delay = 0;
        try {
            switch (name) {
                case "wait" -> delay = Long.parseLong(arg);
                case "size" -> {
                    String[] wh = arg.split("x");
                    stage.setWidth(Double.parseDouble(wh[0]));
                    stage.setHeight(Double.parseDouble(wh[1]));
                    delay = 900;
                }
                case "shot" -> save(scene, new File(arg));
                case "shot2" -> save(secondaryScene(scene), new File(arg));
                case "close2" -> {
                    Scene other = secondaryScene(scene);
                    other.getWindow().hide();
                    delay = 600;
                }
                case "click" -> {
                    click(scene, arg);
                    delay = 500;
                }
                case "text" -> {
                    int eq = arg.indexOf('=');
                    if (find(scene, arg.substring(0, eq)) instanceof javafx.scene.control.TextInputControl field) {
                        field.setText(arg.substring(eq + 1));
                    }
                    delay = 200;
                }
                case "hl" -> {
                    // simula o brilho de hover (variável -g-hl) num nó: hl:#id=0.13
                    int eq = arg.indexOf('=');
                    Node target = find(scene, arg.substring(0, eq));
                    if (target != null) {
                        target.setStyle("-g-hl: rgba(255,255,255," + arg.substring(eq + 1) + ");");
                    }
                }
                case "select" -> {
                    // escolhe um item de um ComboBox pelo índice: select:#id=1
                    int eq = arg.indexOf('=');
                    if (find(scene, arg.substring(0, eq)) instanceof javafx.scene.control.ComboBox<?> combo) {
                        combo.getSelectionModel().select(Integer.parseInt(arg.substring(eq + 1)));
                    }
                    delay = 400;
                }
                case "dump" -> {
                    if (find(scene, arg) instanceof javafx.scene.control.TextInputControl field) {
                        System.out.println("[script] " + arg + " = '" + field.getText().replace("\n", "\\n")
                                + "' caret=" + field.getCaretPosition());
                    }
                }
                case "scroll" -> {
                    // rola um ScrollPane até uma fração (0 = topo, 1 = fim): scroll:#id=0.5
                    int eq = arg.indexOf('=');
                    if (find(scene, arg.substring(0, eq)) instanceof javafx.scene.control.ScrollPane pane) {
                        pane.setVvalue(Double.parseDouble(arg.substring(eq + 1)));
                    }
                    delay = 300;
                }
                case "press" -> {
                    int eq = arg.indexOf('=');
                    String[] keys = arg.substring(eq + 1).split("\\+");
                    boolean shift = java.util.Arrays.asList(keys).contains("SHIFT");
                    javafx.scene.input.KeyCode code = javafx.scene.input.KeyCode.valueOf(keys[0]);
                    Node target = find(scene, arg.substring(0, eq));
                    if (target != null) {
                        javafx.event.Event.fireEvent(target, new javafx.scene.input.KeyEvent(
                                javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", code, shift, false, false, false));
                    }
                    delay = 400;
                }
                case "exit" -> {
                    Platform.exit();
                    return;
                }
                default -> System.out.println("[script] passo desconhecido: " + step);
            }
        } catch (Exception e) {
            System.out.println("[script] falha em '" + step + "': " + e);
        }
        PauseTransition pause = new PauseTransition(Duration.millis(Math.max(delay, 50)));
        pause.setOnFinished(e -> next(stage, scene, steps));
        pause.play();
    }

    /** Localiza um nó por "#id" ou "#id[n]" (n-ésima ocorrência). */
    private static Node find(Scene scene, String selector) {
        int index = 0;
        String css = selector;
        int bracket = selector.indexOf('[');
        if (bracket > 0) {
            index = Integer.parseInt(selector.substring(bracket + 1, selector.indexOf(']')));
            css = selector.substring(0, bracket);
        }
        List<Node> matches = new ArrayList<>();
        // procura em todas as janelas abertas (a principal e, por exemplo, as configurações)
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null) {
                matches.addAll(window.getScene().getRoot().lookupAll(css));
            }
        }
        if (index >= matches.size()) {
            System.out.println("[script] nada encontrado para " + selector);
            return null;
        }
        return matches.get(index);
    }

    private static void click(Scene scene, String selector) {
        Node node = find(scene, selector);
        if (node == null) {
            return;
        }
        if (node instanceof ButtonBase button) {
            button.fire();
        } else {
            // componentes que reagem a clique do mouse (chips de referência, links de citação)
            javafx.event.Event.fireEvent(node, new javafx.scene.input.MouseEvent(
                    javafx.scene.input.MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, javafx.scene.input.MouseButton.PRIMARY,
                    1, false, false, false, false, true, false, false, true, false, false, null));
        }
    }

    /** Cena da janela aberta mais recentemente que não é a principal (ex.: configurações). */
    private static Scene secondaryScene(Scene main) {
        Scene found = null;
        for (javafx.stage.Window window : javafx.stage.Window.getWindows()) {
            if (window.isShowing() && window.getScene() != null && window.getScene() != main
                    && window instanceof Stage) {
                found = window.getScene();
            }
        }
        if (found == null) {
            throw new IllegalStateException("nenhuma janela secundária aberta");
        }
        return found;
    }

    private static void save(Scene scene, File file) throws IOException {
        WritableImage image = scene.snapshot(null);
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out.setRGB(x, y, reader.getArgb(x, y));
            }
        }
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ImageIO.write(out, "png", file);
        System.out.println("[script] captura salva: " + file + " (" + w + "x" + h + ")");
    }
}
