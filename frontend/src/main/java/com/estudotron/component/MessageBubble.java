package com.estudotron.component;

import com.estudotron.viewmodel.ChatMessage;
import com.estudotron.viewmodel.ChatMessage.Reference;
import com.estudotron.viewmodel.ChatMessage.Role;
import com.estudotron.viewmodel.ChatMessage.Status;
import com.estudotron.viewmodel.Formats;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Mensagem do histórico. Usuário: à direita, tom de destaque. IA: à esquerda, com avatar, Markdown,
 * referências clicáveis, rodapé (hora, tokens, custo, copiar) e estados: gerando, interrompida e erro.
 */
public final class MessageBubble extends HBox {

    /** Ações que a bolha dispara na tela. */
    public interface Actions {
        void onReference(Reference reference);

        void onOpenSettings();
    }

    private static final double USER_WIDTH_RATIO = 0.80;
    private static final double ASSISTANT_WIDTH_RATIO = 0.94;

    private final Actions actions;
    private final boolean user;
    private final VBox bubble = new VBox(6);
    private final Label sender = new Label();
    private final MarkdownView markdown = new MarkdownView();
    private final Label plainText = new Label();
    private final VBox errorBox = new VBox(6);
    private final FlowPane references = new FlowPane(6, 6);
    private final HBox footer = new HBox(8);
    private Label streamingLabel;

    private ChatMessage message;
    private final StringBuilder streamed = new StringBuilder();
    private boolean renderScheduled;

    public MessageBubble(ChatMessage message, ObservableValue<Number> availableWidth, Actions actions) {
        this(message, availableWidth, actions, false);
    }

    /** @param animate true para mensagens novas (aparecem com fade e um deslize curto); false no histórico */
    public MessageBubble(ChatMessage message, ObservableValue<Number> availableWidth, Actions actions,
                         boolean animate) {
        this.actions = actions;
        this.message = message;
        this.user = message.role() == Role.USER;
        setSpacing(10);
        setAlignment(user ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        sender.getStyleClass().add("bubble-sender");
        bubble.getStyleClass().addAll("bubble", user ? "user" : "assistant");
        double ratio = user ? USER_WIDTH_RATIO : ASSISTANT_WIDTH_RATIO;
        bubble.maxWidthProperty().bind(Bindings.createDoubleBinding(
                () -> Math.max(240, availableWidth.getValue().doubleValue() * ratio), availableWidth));
        bubble.setMinWidth(0);

        plainText.getStyleClass().add("bubble-text");
        plainText.setWrapText(true);
        plainText.setMinHeight(Region.USE_PREF_SIZE);
        markdown.setOnReference((slot, page) -> actions.onReference(findReference(slot, page)));
        errorBox.getStyleClass().add("bubble-error");
        references.getStyleClass().add("bubble-refs");
        footer.getStyleClass().add("bubble-footer");
        footer.setAlignment(Pos.CENTER_LEFT);

        bubble.getChildren().add(sender);
        bubble.getChildren().add(user ? plainText : markdown);
        if (!user) {
            bubble.getChildren().addAll(errorBox, references, footer);
        }

        if (user) {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            getChildren().addAll(spacer, bubble);
        } else {
            HBox avatar = new HBox(Icons.create(Icons.Icon.SPARKLE, 16));
            avatar.getStyleClass().add("avatar");
            avatar.setAlignment(Pos.CENTER);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            getChildren().addAll(avatar, bubble, spacer);
        }
        streamed.append(message.text());
        refresh();
        if (animate) {
            GlassMotion.fadeSlideIn(this, user ? 18 : -18, 8, 240);
        }
    }

    public ChatMessage message() {
        return message;
    }

    /** Troca o conteúdo pelo estado final/atualizado (fim da resposta, erro, histórico). */
    public void setMessage(ChatMessage updated) {
        this.message = updated;
        streamed.setLength(0);
        streamed.append(updated.text());
        refresh();
    }

    /** Acrescenta texto de uma resposta em andamento. Reagrupa várias chegadas em uma única renderização. */
    public void appendDelta(String delta) {
        streamed.append(delta);
        if (!renderScheduled) {
            renderScheduled = true;
            Platform.runLater(() -> {
                renderScheduled = false;
                renderBody();
            });
        }
    }

    // ----------------------------------------------------------------- desenho

    private void refresh() {
        sender.setText(senderText());
        renderBody();
        if (!user) {
            renderReferences();
            renderErrorBox();
            renderFooter();
        }
    }

    private String senderText() {
        if (user) {
            return "Você";
        }
        return message.modelLabel() != null ? message.modelLabel() : "Assistente";
    }

    private void renderBody() {
        String text = streamed.toString();
        if (user) {
            plainText.setText(text);
        } else {
            markdown.setMarkdown(text);
            boolean waiting = message.status() == Status.STREAMING && text.isEmpty();
            markdown.setVisible(!text.isEmpty() || !waiting);
            markdown.setManaged(!text.isEmpty() || !waiting);
            if (streamingLabel != null) {
                streamingLabel.setText(text.isEmpty() ? "Pensando…" : "Gerando…");
            }
        }
    }

    private void renderReferences() {
        references.getChildren().clear();
        boolean show = !message.references().isEmpty();
        for (Reference reference : message.references()) {
            references.getChildren().add(new ReferenceChip(reference, actions::onReference));
        }
        references.setVisible(show);
        references.setManaged(show);
    }

    private void renderErrorBox() {
        errorBox.getChildren().clear();
        boolean failed = message.status() == Status.ERROR;
        getBubbleStyle().remove("error");
        if (failed) {
            getBubbleStyle().add("error");
            Label text = new Label(message.error() == null ? "Ocorreu um erro." : message.error());
            text.getStyleClass().add("bubble-error-text");
            text.setWrapText(true);
            text.setMinHeight(Region.USE_PREF_SIZE);
            errorBox.getChildren().add(new HBox(8, Icons.create(Icons.Icon.ALERT, 16), text));
            if ("no_key".equals(message.errorKind()) || "auth".equals(message.errorKind())) {
                Button open = new Button("Abrir configurações");
                open.getStyleClass().add("secondary");
                open.setOnAction(e -> actions.onOpenSettings());
                errorBox.getChildren().add(open);
            }
        }
        errorBox.setVisible(failed);
        errorBox.setManaged(failed);
    }

    private javafx.collections.ObservableList<String> getBubbleStyle() {
        return bubble.getStyleClass();
    }

    private void renderFooter() {
        footer.getChildren().clear();
        streamingLabel = null;
        if (message.status() == Status.STREAMING) {
            streamingLabel = new Label(streamed.length() == 0 ? "Pensando…" : "Gerando…");
            streamingLabel.getStyleClass().add("bubble-meta");
            footer.getChildren().addAll(new PulseDots(), streamingLabel);
            return;
        }
        StringBuilder info = new StringBuilder(Formats.time(message.createdAt()));
        if (message.usageSummary() != null) {
            info.append(" · ").append(message.usageSummary());
        }
        Label meta = new Label(info.toString());
        meta.getStyleClass().add("bubble-meta");
        footer.getChildren().add(meta);
        if (message.status() == Status.CANCELLED) {
            Label stopped = new Label("Interrompida");
            stopped.getStyleClass().add("bubble-stopped");
            footer.getChildren().add(stopped);
        }
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copy = new Button();
        copy.getStyleClass().addAll("icon-button", "bubble-copy");
        Icons.set(copy, Icons.Icon.COPY, 13);
        copy.setTooltip(new Tooltip("Copiar resposta"));
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(streamed.toString());
            Clipboard.getSystemClipboard().setContent(content);
        });
        footer.getChildren().addAll(spacer, copy);
    }

    private Reference findReference(String slot, int page) {
        return message.references().stream()
                .filter(r -> r.slot().equals(slot) && r.page() == page)
                .findFirst()
                .orElse(new Reference(slot, page, "PDF " + ("A".equals(slot) ? "1" : "2"), null));
    }
}
