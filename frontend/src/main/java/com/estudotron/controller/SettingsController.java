package com.estudotron.controller;

import com.estudotron.backend.AiSettingsApi;
import com.estudotron.backend.ChatDtos.AiSettingsDto;
import com.estudotron.backend.ChatDtos.ConnectionTestDto;
import com.estudotron.backend.ChatDtos.ModelDto;
import com.estudotron.backend.ChatDtos.ProviderDto;
import com.estudotron.component.GlassMotion;
import com.estudotron.component.LiquidGlass;
import com.estudotron.component.Icons;
import com.estudotron.component.Icons.Icon;
import com.estudotron.service.NotificationService;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.IOException;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Tela de configurações de IA: provedor padrão, chave da API (vai direto para o backend, que a guarda
 * no cofre do sistema e nunca a devolve), modelo e teste de conexão.
 */
public final class SettingsController {

    private final AiSettingsApi api;
    private final Executor io;
    private final NotificationService notifications;
    private final Runnable onClosed;

    @FXML private VBox root;
    @FXML private ComboBox<ProviderDto> defaultProviderBox;
    @FXML private ComboBox<ProviderDto> providerBox;
    @FXML private PasswordField keyField;
    @FXML private Button saveKeyButton;
    @FXML private Button testButton;
    @FXML private Button removeKeyButton;
    @FXML private StackPane keyStatusIcon;
    @FXML private Label keyStatus;
    @FXML private ComboBox<ModelDto> modelBox;
    @FXML private Label modelInfo;
    @FXML private Label resultLabel;
    @FXML private StackPane shieldIcon;

    private AiSettingsDto settings;
    private boolean updating;
    private Stage stage;

    private SettingsController(AiSettingsApi api, Executor io, NotificationService notifications, Runnable onClosed) {
        this.api = api;
        this.io = io;
        this.notifications = notifications;
        this.onClosed = onClosed;
    }

    /** Abre a janela de configurações. {@code onClosed} roda ao fechar (para o chat recarregar os provedores). */
    public static void open(Window owner, AiSettingsApi api, Executor io, NotificationService notifications,
                            Runnable onClosed) {
        SettingsController controller = new SettingsController(api, io, notifications, onClosed);
        Parent view = ViewLoader.load("settings-dialog.fxml", controller);
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Configurações");
        double height = Math.min(760, javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.92);
        Scene scene = new Scene(view, 600, height);
        scene.setFill(Color.web("#f3f5f9"));
        scene.getStylesheets().add(ViewLoader.stylesheet());
        GlassMotion.install(scene);
        LiquidGlass.install(scene);
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(520);
        controller.stage = stage;
        stage.setOnHidden(e -> onClosed.run());
        stage.show();
        GlassMotion.popIn(view, 240);
    }

    @FXML
    private void initialize() {
        shieldIcon.getChildren().add(Icons.create(Icon.SHIELD, 18));
        shieldIcon.getStyleClass().add("icon-accent");
        Icons.set(saveKeyButton, Icon.KEY, 15);
        Icons.set(removeKeyButton, Icon.TRASH, 14);

        javafx.util.StringConverter<ProviderDto> providerNames = new javafx.util.StringConverter<>() {
            @Override
            public String toString(ProviderDto provider) {
                return provider == null ? "" : provider.displayName();
            }

            @Override
            public ProviderDto fromString(String text) {
                return null;
            }
        };
        defaultProviderBox.setConverter(providerNames);
        providerBox.setConverter(providerNames);
        modelBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(ModelDto model) {
                return model == null ? "" : model.label();
            }

            @Override
            public ModelDto fromString(String text) {
                return null;
            }
        });

        defaultProviderBox.valueProperty().addListener((obs, old, provider) -> {
            if (!updating && provider != null) {
                runUpdate(() -> api.setPreferences(provider.id(), null), "Provedor padrão atualizado.");
            }
        });
        providerBox.valueProperty().addListener((obs, old, provider) -> {
            if (!updating && provider != null) {
                showProvider(provider);
            }
        });
        modelBox.valueProperty().addListener((obs, old, model) -> {
            ProviderDto provider = providerBox.getValue();
            if (!updating && model != null && provider != null) {
                modelInfo.setText(describe(model));
                runUpdate(() -> api.setPreferences(null, Map.of(provider.id(), model.id())), null);
            }
        });
        keyField.textProperty().addListener((obs, old, text) -> saveKeyButton.setDisable(text == null || text.isBlank()));
        saveKeyButton.setDisable(true);
        load();
    }

    // ----------------------------------------------------------------- carregar e mostrar

    private void load() {
        io.execute(() -> {
            try {
                AiSettingsDto loaded = api.getSettings();
                Platform.runLater(() -> apply(loaded, null));
            } catch (IOException e) {
                Platform.runLater(() -> showResult(false, "Não foi possível carregar as configurações: " + e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** @param keepProviderId provedor que deve continuar selecionado no painel (null = o padrão) */
    private void apply(AiSettingsDto loaded, String keepProviderId) {
        updating = true;
        try {
            this.settings = loaded;
            String editing = keepProviderId != null ? keepProviderId
                    : providerBox.getValue() != null ? providerBox.getValue().id() : loaded.defaultProvider();
            defaultProviderBox.getItems().setAll(loaded.providers());
            providerBox.getItems().setAll(loaded.providers());
            defaultProviderBox.setValue(loaded.provider(loaded.defaultProvider()));
            ProviderDto current = loaded.provider(editing) != null ? loaded.provider(editing)
                    : loaded.providers().stream().findFirst().orElse(null);
            providerBox.setValue(current);
            if (current != null) {
                showProvider(current);
            }
        } finally {
            updating = false;
        }
    }

    private void showProvider(ProviderDto provider) {
        boolean previous = updating;
        updating = true;
        try {
            keyField.setPromptText(provider.keyHint() == null || provider.keyHint().isBlank()
                    ? "Cole a chave aqui" : "Cole a chave aqui (" + provider.keyHint() + ")");
            keyStatusIcon.getChildren().setAll(Icons.create(provider.hasKey() ? Icon.CHECK : Icon.INFO, 16));
            keyStatusIcon.getStyleClass().removeAll("icon-ok", "icon-faint");
            keyStatusIcon.getStyleClass().add(provider.hasKey() ? "icon-ok" : "icon-faint");
            keyStatus.setText(provider.hasKey()
                    ? "Há uma chave salva com segurança. Por proteção, ela não é exibida; cole uma nova para substituí-la."
                    : "Nenhuma chave salva para este provedor.");
            removeKeyButton.setDisable(!provider.hasKey());
            testButton.setDisable(false);
            modelBox.getItems().setAll(provider.models());
            ModelDto selected = provider.selectedModelInfo();
            modelBox.setValue(selected);
            modelInfo.setText(selected == null ? "" : describe(selected));
        } finally {
            updating = previous;
        }
    }

    private static String describe(ModelDto model) {
        NumberFormat money = NumberFormat.getNumberInstance(Locale.of("pt", "BR"));
        money.setMinimumFractionDigits(2);
        money.setMaximumFractionDigits(2);
        StringBuilder text = new StringBuilder(model.description() == null ? "" : model.description());
        if (model.priceInputPerMtok() != null && model.priceOutputPerMtok() != null) {
            text.append("  Custo por 1 milhão de tokens: US$ ").append(money.format(model.priceInputPerMtok()))
                    .append(" de entrada · US$ ").append(money.format(model.priceOutputPerMtok())).append(" de saída.");
        }
        return text.toString().trim();
    }

    // ----------------------------------------------------------------- ações

    @FXML
    private void saveKey() {
        ProviderDto provider = providerBox.getValue();
        String typed = keyField.getText();
        if (provider == null || typed == null || typed.isBlank()) {
            showResult(false, "Cole a chave no campo antes de salvar.");
            return;
        }
        char[] key = typed.toCharArray();
        keyField.clear(); // a chave sai da tela imediatamente
        setBusy(true);
        showInfo("Salvando…");
        io.execute(() -> {
            try {
                api.saveKey(provider.id(), key);
                AiSettingsDto loaded = api.getSettings();
                Platform.runLater(() -> {
                    setBusy(false);
                    apply(loaded, provider.id());
                    showResult(true, "Chave salva com segurança no Windows. Use “Testar conexão” para confirmar.");
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    setBusy(false);
                    showResult(false, e.getMessage());
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                Arrays.fill(key, '\0');
            }
        });
    }

    @FXML
    private void removeKey() {
        ProviderDto provider = providerBox.getValue();
        if (provider == null) {
            return;
        }
        setBusy(true);
        io.execute(() -> {
            try {
                api.removeKey(provider.id());
                AiSettingsDto loaded = api.getSettings();
                Platform.runLater(() -> {
                    setBusy(false);
                    apply(loaded, provider.id());
                    showResult(true, "Chave removida deste computador.");
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    setBusy(false);
                    showResult(false, e.getMessage());
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @FXML
    private void testConnection() {
        ProviderDto provider = providerBox.getValue();
        ModelDto model = modelBox.getValue();
        if (provider == null) {
            return;
        }
        String typed = keyField.getText();
        char[] key = typed == null || typed.isBlank() ? null : typed.toCharArray();
        setBusy(true);
        showInfo(key == null ? "Testando com a chave salva…" : "Testando com a chave digitada…");
        io.execute(() -> {
            try {
                ConnectionTestDto result = api.testConnection(provider.id(), model == null ? null : model.id(), key);
                Platform.runLater(() -> {
                    setBusy(false);
                    if (result.ok()) {
                        showResult(true, result.message() + (result.latencyMs() == null ? ""
                                : " Resposta em " + result.latencyMs() + " ms."));
                    } else {
                        showResult(false, result.message());
                    }
                });
            } catch (IOException e) {
                Platform.runLater(() -> {
                    setBusy(false);
                    showResult(false, e.getMessage());
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (key != null) {
                    Arrays.fill(key, '\0');
                }
            }
        });
    }

    @FXML
    private void close() {
        // sai com um fade curto antes de fechar a janela
        javafx.animation.FadeTransition fade = new javafx.animation.FadeTransition(
                javafx.util.Duration.millis(120), stage.getScene().getRoot());
        fade.setToValue(0);
        fade.setOnFinished(e -> stage.close());
        fade.play();
    }

    /** Aplica uma preferência no backend; em caso de erro, mostra e recarrega o estado real. */
    private void runUpdate(ThrowingCall call, String successMessage) {
        io.execute(() -> {
            try {
                call.run();
                if (successMessage != null) {
                    Platform.runLater(() -> notifications.info(successMessage));
                }
            } catch (IOException e) {
                Platform.runLater(() -> {
                    showResult(false, e.getMessage());
                    load();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void run() throws IOException, InterruptedException;
    }

    // ----------------------------------------------------------------- resultado

    private void setBusy(boolean busy) {
        saveKeyButton.setDisable(busy || keyField.getText() == null || keyField.getText().isBlank());
        testButton.setDisable(busy);
        removeKeyButton.setDisable(busy || providerBox.getValue() == null || !providerBox.getValue().hasKey());
    }

    private void showInfo(String message) {
        showMessage("result-info", message);
    }

    private void showResult(boolean ok, String message) {
        showMessage(ok ? "result-ok" : "result-error", message);
    }

    private void showMessage(String styleClass, String message) {
        resultLabel.getStyleClass().removeAll("result-ok", "result-error", "result-info");
        resultLabel.getStyleClass().addAll("result-box", styleClass);
        resultLabel.setText(message);
        resultLabel.setVisible(true);
        resultLabel.setManaged(true);
        resultLabel.setPadding(new Insets(8, 12, 8, 12));
    }
}
