package com.estudotron;

import com.estudotron.backend.ApiClient;
import com.estudotron.backend.BackendManager;
import com.estudotron.component.GlassMotion;
import com.estudotron.controller.MainController;
import com.estudotron.controller.ViewLoader;
import com.estudotron.dev.ScreenshotScript;
import javafx.application.Application;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;

/** Sobe o backend local e abre a janela principal. */
public final class App extends Application {

    private static final double MIN_WIDTH = 1140;
    private static final double MIN_HEIGHT = 560;

    private final BackendManager backend = new BackendManager();
    private MainController main;

    @Override
    public void start(Stage stage) {
        Label message = new Label("Iniciando o " + AppInfo.NAME + "…");
        message.getStyleClass().add("splash-title");
        VBox splash = new VBox(14, new ProgressIndicator(), message);
        splash.setAlignment(Pos.CENTER);
        splash.getStyleClass().add("app-root");

        var bounds = Screen.getPrimary().getVisualBounds();
        Scene scene = new Scene(splash, Math.min(1320, bounds.getWidth() * 0.92), Math.min(820, bounds.getHeight() * 0.92));
        scene.setFill(Color.web("#f3f5f9"));
        scene.getStylesheets().add(ViewLoader.stylesheet());
        GlassMotion.install(scene);
        stage.setTitle(AppInfo.NAME);
        stage.setMinWidth(MIN_WIDTH);
        stage.setMinHeight(MIN_HEIGHT);
        stage.setScene(scene);
        stage.show();

        Task<ApiClient> task = new Task<>() {
            @Override
            protected ApiClient call() throws Exception {
                return backend.start();
            }
        };
        task.setOnSucceeded(e -> {
            // Apoio a testes: -Destudotron.autoload=<pdf1> e -Destudotron.autoload.b=<pdf2>
            main = new MainController(stage, task.getValue(),
                    System.getProperty("estudotron.autoload"),
                    System.getProperty("estudotron.autoload.b"));
            Parent root = ViewLoader.load("main-view.fxml", main);
            scene.setRoot(root);
            ScreenshotScript.runIfRequested(stage, scene);
        });
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            message.setText("Não foi possível iniciar o backend:\n"
                    + (error == null ? "erro desconhecido" : error.getMessage()));
            splash.getChildren().remove(0);
        });
        Thread thread = new Thread(task, "backend-start");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        if (main != null) {
            main.shutdown();
        }
        backend.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
