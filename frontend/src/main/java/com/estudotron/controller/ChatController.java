package com.estudotron.controller;

import com.estudotron.backend.AiSettingsApi;
import com.estudotron.backend.ChatDtos.AiSettingsDto;
import com.estudotron.backend.ChatDtos.ChatMessageDto;
import com.estudotron.backend.ChatDtos.ConversationDto;
import com.estudotron.backend.ChatDtos.ModelDto;
import com.estudotron.backend.ChatDtos.ProviderDto;
import com.estudotron.component.Icons;
import com.estudotron.component.Icons.Icon;
import com.estudotron.component.MessageBubble;
import com.estudotron.component.ReferenceChip;
import com.estudotron.service.ChatService;
import com.estudotron.service.NotificationService;
import com.estudotron.viewmodel.ChatMessage;
import com.estudotron.viewmodel.ChatMessage.Reference;
import com.estudotron.viewmodel.ChatMessage.Role;
import com.estudotron.viewmodel.ChatMessage.Status;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Painel do chat: histórico, resposta em fluxo, Markdown, referências clicáveis, escolha de provedor,
 * parar resposta e nova conversa. A tela nunca bloqueia: as chamadas à IA rodam em segundo plano.
 */
public final class ChatController {

    /** O que o chat precisa da janela principal. */
    public interface Host {
        void openSettings();

        void goToReference(Reference reference);
    }

    /** Opção do seletor de provedor. */
    public record ProviderOption(String id, String name, boolean hasKey) {
        @Override
        public String toString() {
            return hasKey ? name : name + " · sem chave";
        }
    }

    private static final int MAX_INPUT_ROWS = 5;

    private final ChatService chat;
    private final AiSettingsApi settingsApi;
    private final Executor io;
    private final NotificationService notifications;
    private final Host host;

    @FXML private StackPane chatIcon;
    @FXML private StackPane emptyIcon;
    @FXML private StackPane errorIcon;
    @FXML private VBox emptyState;
    @FXML private VBox messageList;
    @FXML private ScrollPane historyScroll;
    @FXML private ComboBox<ProviderOption> providerBox;
    @FXML private Button newChatButton;
    @FXML private HBox errorBanner;
    @FXML private Label errorText;
    @FXML private Button errorAction;
    @FXML private Button errorClose;
    @FXML private VBox referencesBar;
    @FXML private FlowPane referenceChips;
    @FXML private TextArea input;
    @FXML private Button sendButton;

    private AiSettingsDto settings;
    private MessageBubble userBubble;
    private MessageBubble streamingBubble;
    private String pendingText;
    private boolean started;

    private final MessageBubble.Actions bubbleActions = new MessageBubble.Actions() {
        @Override
        public void onReference(Reference reference) {
            host.goToReference(reference);
        }

        @Override
        public void onOpenSettings() {
            host.openSettings();
        }
    };

    public ChatController(ChatService chat, AiSettingsApi settingsApi, Executor io,
                          NotificationService notifications, Host host) {
        this.chat = chat;
        this.settingsApi = settingsApi;
        this.io = io;
        this.notifications = notifications;
        this.host = host;
    }

    @FXML
    private void initialize() {
        chatIcon.getChildren().add(Icons.create(Icon.SPARKLE, 16));
        chatIcon.getStyleClass().add("icon-accent");
        emptyIcon.getChildren().add(Icons.create(Icon.SPARKLE, 26));
        emptyIcon.getStyleClass().add("icon-faint");
        errorIcon.getChildren().add(Icons.create(Icon.ALERT, 17));
        Icons.set(errorClose, Icon.CLOSE, 13);
        Icons.set(newChatButton, Icon.NEW_CHAT, 16);
        newChatButton.setTooltip(new Tooltip("Nova conversa (a atual continua salva no histórico)"));

        providerBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ProviderOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.toString()); // na lista: "· sem chave"
            }
        });
        providerBox.setButtonCell(new ListCell<>() { // no botão: só o nome (cabe na coluna estreita)
            @Override
            protected void updateItem(ProviderOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.name());
            }
        });
        providerBox.setTooltip(new Tooltip("Provedor de IA usado neste chat"));

        setupInput();
        setSendMode(false);
        messageList.getChildren().addListener((ListChangeListener<javafx.scene.Node>) change ->
                emptyState.setVisible(messageList.getChildren().isEmpty()));
        messageList.heightProperty().addListener((obs, old, height) -> historyScroll.setVvalue(1.0));
        showReferences(List.of());

        refreshSettings();
        chat.loadCurrent(this::showConversation, message -> notifications.error(message));
    }

    // ----------------------------------------------------------------- configurações / provedores

    /** Recarrega provedores e chaves (chamado ao abrir o programa e ao fechar as configurações). */
    public void refreshSettings() {
        io.execute(() -> {
            try {
                AiSettingsDto loaded = settingsApi.getSettings();
                Platform.runLater(() -> applySettings(loaded));
            } catch (IOException e) {
                Platform.runLater(() -> notifications.error("Não foi possível carregar as configurações de IA: "
                        + e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void applySettings(AiSettingsDto loaded) {
        this.settings = loaded;
        ProviderOption previous = providerBox.getValue();
        List<ProviderOption> options = loaded.providers().stream()
                .map(p -> new ProviderOption(p.id(), p.displayName(), p.hasKey())).toList();
        providerBox.getItems().setAll(options);
        String wanted = previous != null ? previous.id() : loaded.defaultProvider();
        options.stream().filter(o -> o.id().equals(wanted)).findFirst()
                .or(() -> options.stream().findFirst())
                .ifPresent(providerBox::setValue);
    }

    private String modelLabel(String modelId) {
        if (settings != null) {
            for (ProviderDto provider : settings.providers()) {
                for (ModelDto model : provider.models()) {
                    if (model.id().equals(modelId)) {
                        return model.label();
                    }
                }
            }
        }
        return modelId;
    }

    private String selectedModelLabel(String providerId) {
        if (settings == null || providerId == null) {
            return null;
        }
        ProviderDto provider = settings.provider(providerId);
        ModelDto model = provider == null ? null : provider.selectedModelInfo();
        return model == null ? null : model.label();
    }

    // ----------------------------------------------------------------- campo de texto

    /** Enter envia; Shift+Enter quebra linha. O campo cresce até {@value #MAX_INPUT_ROWS} linhas. */
    private void setupInput() {
        input.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.ENTER) {
                return;
            }
            if (event.isShiftDown()) {
                input.insertText(input.getCaretPosition(), "\n");
            } else if (!event.isControlDown() && !event.isAltDown()) {
                send();
            }
            event.consume();
        });
        input.textProperty().addListener((obs, old, text) -> {
            if (!chat.isBusy()) {
                sendButton.setDisable(text == null || text.isBlank());
            }
            long lines = text == null ? 1 : text.chars().filter(c -> c == '\n').count() + 1;
            input.setPrefRowCount((int) Math.max(3, Math.min(MAX_INPUT_ROWS, lines)));
        });
    }

    /** Durante a resposta o botão vira "parar"; depois volta a ser "enviar". */
    private void setSendMode(boolean streaming) {
        sendButton.getStyleClass().remove("stop");
        if (streaming) {
            sendButton.getStyleClass().add("stop");
            Icons.set(sendButton, Icon.STOP, 16);
            sendButton.setTooltip(new Tooltip("Parar a resposta"));
            sendButton.setDisable(false);
        } else {
            Icons.set(sendButton, Icon.SEND, 17);
            sendButton.setTooltip(new Tooltip("Enviar (Enter)"));
            sendButton.setDisable(input.getText() == null || input.getText().isBlank());
        }
    }

    // ----------------------------------------------------------------- ações

    @FXML
    private void sendOrStop() {
        if (chat.isBusy()) {
            chat.cancel();
        } else {
            send();
        }
    }

    private void send() {
        String text = input.getText() == null ? "" : input.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        if (chat.isBusy()) {
            notifications.info("Aguarde a resposta terminar ou toque em parar.");
            return;
        }
        dismissBanner();
        ProviderOption provider = providerBox.getValue();
        String providerId = provider == null ? null : provider.id();

        pendingText = text;
        started = false;
        input.clear();
        userBubble = addBubble(ChatMessage.local(Role.USER, text, Status.COMPLETE, null, null));
        setSendMode(true);

        chat.send(text, providerId, null, new ChatService.Handler() {
            @Override
            public void onStarted(String assistantMessageId, ChatMessageDto userMessage) {
                started = true;
                userBubble.setMessage(ChatMessage.from(userMessage, ChatController.this::modelLabel));
                streamingBubble = addBubble(ChatMessage.local(Role.ASSISTANT, "", Status.STREAMING, providerId,
                        selectedModelLabel(providerId)));
            }

            @Override
            public void onDelta(String delta) {
                if (streamingBubble != null) {
                    streamingBubble.appendDelta(delta);
                }
            }

            @Override
            public void onDone(ChatMessageDto assistantMessage) {
                ChatMessage finished = ChatMessage.from(assistantMessage, ChatController.this::modelLabel);
                if (streamingBubble != null) {
                    streamingBubble.setMessage(finished);
                }
                showReferences(finished.references());
                endResponse();
            }

            @Override
            public void onError(String kind, String message, ChatMessageDto partial) {
                handleError(kind, message, partial);
            }
        });
    }

    private void handleError(String kind, String message, ChatMessageDto partial) {
        if (!started) {
            // Falha antes de a conversa começar: nada foi gravado. Devolve o texto ao campo e avisa.
            if (userBubble != null) {
                messageList.getChildren().remove(userBubble);
            }
            input.setText(pendingText);
            input.positionCaret(pendingText == null ? 0 : pendingText.length());
            if (!"cancelled".equals(kind)) {
                showBanner(message, "no_key".equals(kind) || "auth".equals(kind));
            }
        } else if (streamingBubble != null) {
            ChatMessage current = streamingBubble.message();
            if (partial != null) {
                streamingBubble.setMessage(ChatMessage.from(partial, this::modelLabel));
            } else if ("cancelled".equals(kind)) {
                streamingBubble.setMessage(current.withStatus(Status.CANCELLED));
            } else {
                streamingBubble.setMessage(new ChatMessage(current.id(), Role.ASSISTANT, current.text(),
                        current.provider(), current.modelLabel(), current.createdAt(), Status.ERROR, message, kind,
                        List.of(), null, false));
            }
        }
        endResponse();
    }

    private void endResponse() {
        streamingBubble = null;
        userBubble = null;
        pendingText = null;
        setSendMode(false);
        input.requestFocus();
    }

    @FXML
    private void newConversation() {
        chat.startNewConversation(conversation -> {
            streamingBubble = null;
            userBubble = null;
            messageList.getChildren().clear();
            showReferences(List.of());
            dismissBanner();
            setSendMode(false);
            input.requestFocus();
        }, message -> notifications.error(message));
    }

    // ----------------------------------------------------------------- histórico e referências

    private void showConversation(ConversationDto conversation) {
        messageList.getChildren().clear();
        List<ChatMessage> history = conversation.messagesOrEmpty().stream()
                .map(dto -> ChatMessage.from(dto, this::modelLabel)).toList();
        history.forEach(m -> addBubble(m, false)); // histórico aparece direto, sem animar
        history.stream().filter(m -> m.role() == Role.ASSISTANT).reduce((first, second) -> second)
                .ifPresent(last -> showReferences(last.references()));
    }

    private MessageBubble addBubble(ChatMessage message) {
        return addBubble(message, true);
    }

    private MessageBubble addBubble(ChatMessage message, boolean animate) {
        MessageBubble bubble = new MessageBubble(message, historyScroll.widthProperty().subtract(34), bubbleActions,
                animate);
        messageList.getChildren().add(bubble);
        Platform.runLater(() -> historyScroll.setVvalue(1.0));
        return bubble;
    }

    private void showReferences(List<Reference> references) {
        referenceChips.getChildren().clear();
        if (references.isEmpty()) {
            Label none = new Label("As citações aos PDFs aparecerão aqui.");
            none.getStyleClass().add("faint");
            referenceChips.getChildren().add(none);
            return;
        }
        for (Reference reference : references) {
            referenceChips.getChildren().add(new ReferenceChip(reference, bubbleActions::onReference));
        }
    }

    // ----------------------------------------------------------------- aviso de erro

    private void showBanner(String message, boolean offerSettings) {
        errorText.setText(message);
        errorAction.setVisible(offerSettings);
        errorAction.setManaged(offerSettings);
        errorBanner.setVisible(true);
        errorBanner.setManaged(true);
    }

    @FXML
    private void dismissBanner() {
        errorBanner.setVisible(false);
        errorBanner.setManaged(false);
    }

    @FXML
    private void openSettings() {
        host.openSettings();
    }
}
