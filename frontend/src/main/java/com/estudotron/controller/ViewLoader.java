package com.estudotron.controller;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;

/**
 * Carrega um FXML de {@code /com/estudotron/fxml}. Os controllers são criados em código
 * (recebem serviços e viewmodels por construtor), por isso os FXML não declaram fx:controller.
 */
public final class ViewLoader {

    private static final String FXML_DIR = "/com/estudotron/fxml/";

    private ViewLoader() {}

    public static <T extends Parent> T load(String fxmlFile, Object controller) {
        URL url = ViewLoader.class.getResource(FXML_DIR + fxmlFile);
        if (url == null) {
            throw new IllegalStateException("FXML não encontrado: " + fxmlFile);
        }
        FXMLLoader loader = new FXMLLoader(url);
        loader.setController(controller);
        try {
            return loader.load();
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao carregar " + fxmlFile, e);
        }
    }

    public static String stylesheet() {
        URL url = ViewLoader.class.getResource("/com/estudotron/css/liquid-glass.css");
        if (url == null) {
            throw new IllegalStateException("liquid-glass.css não encontrado");
        }
        return url.toExternalForm();
    }
}
