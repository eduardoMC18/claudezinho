package com.estudotron.component;

import com.estudotron.backend.ChatDtos.ReferenceDto;
import com.estudotron.backend.ChatDtos.UsageDto;
import com.estudotron.component.Icons.Icon;
import com.estudotron.viewmodel.ChatMessage.Reference;
import com.estudotron.viewmodel.Formats;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Peças visuais compartilhadas pelas telas de exercícios (cartões com título, chips, ações, referências). */
public final class ExerciseParts {

    private static final Locale PT_BR = Locale.of("pt", "BR");

    private ExerciseParts() {}

    /** Texto que quebra linha sem ser cortado com "…" quando está dentro de um VBox/ScrollPane. */
    public static Label wrapLabel(String text, String... styleClasses) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        label.setMaxWidth(Double.MAX_VALUE);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    public static MarkdownView markdown(String text, String... styleClasses) {
        MarkdownView view = new MarkdownView();
        view.setMarkdown(text == null ? "" : text);
        view.getStyleClass().addAll(styleClasses);
        return view;
    }

    /** "Fácil" / "Média" / "Difícil", com a cor da dificuldade; null se o modelo não informou. */
    public static Label difficultyChip(String difficulty) {
        if (difficulty == null) {
            return null;
        }
        String text = switch (difficulty) {
            case "facil" -> "Fácil";
            case "media" -> "Média";
            case "dificil" -> "Difícil";
            default -> null;
        };
        if (text == null) {
            return null;
        }
        Label chip = new Label(text);
        chip.getStyleClass().addAll("difficulty-chip", "d-" + difficulty);
        return chip;
    }

    /** Cartão com título e ícone; {@code tone} = ok | bad | warn | info | plain define a cor de destaque. */
    public static VBox card(String title, Icon icon, String tone, Node... content) {
        VBox card = new VBox(8);
        card.getStyleClass().addAll("ex-card", "tone-" + tone);
        if (title != null) {
            HBox head = new HBox(8, Icons.create(icon, 15), wrapLabel(title, "ex-card-title"));
            head.setAlignment(Pos.CENTER_LEFT);
            card.getChildren().add(head);
        }
        card.getChildren().addAll(content);
        return card;
    }

    /** Referências clicáveis aos PDFs; vazio se não houver nenhuma. */
    public static Node references(List<ReferenceDto> references, Consumer<Reference> onClick) {
        VBox box = new VBox(6);
        if (references == null || references.isEmpty()) {
            box.setManaged(false);
            box.setVisible(false);
            return box;
        }
        FlowPane chips = new FlowPane(8, 8);
        for (ReferenceDto dto : references) {
            chips.getChildren().add(new ReferenceChip(Reference.from(dto), onClick));
        }
        Label caption = new Label("REFERÊNCIAS NOS PDFS");
        caption.getStyleClass().add("muted-small");
        box.getChildren().addAll(caption, chips);
        return box;
    }

    /** Botões do fim da questão: nova questão (principal) e voltar ao início. */
    public static HBox endActions(Runnable onNext, Runnable onHome) {
        Button next = new Button("Nova questão");
        next.getStyleClass().add("primary");
        next.setId("exNext");
        Icons.set(next, Icon.REFRESH, 15);
        next.setOnAction(e -> onNext.run());
        Button home = new Button("Voltar ao início");
        home.getStyleClass().add("secondary");
        home.setId("exHome");
        home.setOnAction(e -> onHome.run());
        HBox row = new HBox(10, next, home);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("ex-end-actions");
        return row;
    }

    /** "1,2 mil tokens · ≈ US$ 0,0009" ou null. */
    public static Label usageLabel(UsageDto usage) {
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) {
            return null;
        }
        String text = Formats.tokens(usage.inputTokens() + usage.outputTokens()) + " tokens"
                + (usage.costUsd() == null ? "" : " · " + Formats.usd(usage.costUsd()));
        Label label = new Label(text);
        label.getStyleClass().add("bubble-meta");
        return label;
    }

    /** Nota no formato brasileiro: "8,5" (e "8" quando é inteira). */
    public static String score(double score) {
        return score == Math.rint(score) ? String.valueOf((int) score) : String.format(PT_BR, "%.1f", score);
    }

    public static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
