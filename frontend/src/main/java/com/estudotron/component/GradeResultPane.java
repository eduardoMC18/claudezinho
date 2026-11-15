package com.estudotron.component;

import com.estudotron.backend.ExerciseDtos.CorrectionDto;
import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.backend.ExerciseDtos.ResultDto;
import com.estudotron.component.Icons.Icon;
import com.estudotron.viewmodel.ChatMessage.Reference;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Correção da resposta discursiva em componentes (não em texto de chat): nota em anel, avaliação geral,
 * cartões de acertos e de pontos a melhorar, conceitos a corrigir, resposta mais completa, critérios e referências.
 */
public final class GradeResultPane extends VBox {

    public GradeResultPane(ResultDto result, ExerciseDto exercise, Consumer<Reference> onReference,
                           Runnable onNext, Runnable onHome) {
        getStyleClass().add("grade-pane");
        setSpacing(14);

        String level = result.verdict() == null ? "partial" : result.verdict();
        getChildren().add(summary(result, level));

        VBox good = pointsCard("O que você acertou", Icon.CHECK_CIRCLE, "ok", result.strengths(),
                "Nenhum ponto da resposta se destacou desta vez.");
        VBox missing = pointsCard("O que faltou", Icon.ALERT, "warn", result.missing(),
                "Nada importante ficou de fora.");
        HBox columns = new HBox(14, good, missing);
        HBox.setHgrow(good, Priority.ALWAYS);
        HBox.setHgrow(missing, Priority.ALWAYS);
        good.setMaxWidth(Double.MAX_VALUE);
        missing.setMaxWidth(Double.MAX_VALUE);
        good.setPrefWidth(10);       // largura igual: o crescimento divide o espaço em duas partes
        missing.setPrefWidth(10);
        columns.setFillHeight(true);
        getChildren().add(columns);

        if (!result.corrections().isEmpty()) {
            VBox fixes = new VBox(10);
            for (CorrectionDto fix : result.corrections()) {
                VBox item = new VBox(3, ExerciseParts.wrapLabel(fix.concept(), "fix-concept"),
                        ExerciseParts.markdown(fix.explanation()));
                item.getStyleClass().add("fix-item");
                fixes.getChildren().add(item);
            }
            getChildren().add(ExerciseParts.card("Conceitos para corrigir", Icon.X_CIRCLE, "bad", fixes));
        }

        getChildren().add(ExerciseParts.card("Como seria uma resposta mais completa", Icon.LIGHTBULB, "info",
                ExerciseParts.markdown(result.idealAnswer())));

        if (!result.criteria().isEmpty()) {
            FlowPane chips = new FlowPane(8, 8);
            for (String criterion : result.criteria()) {
                Label chip = new Label(criterion);
                chip.setWrapText(true);
                chip.setMaxWidth(420);
                chip.getStyleClass().add("criterion-chip");
                chips.getChildren().add(chip);
            }
            getChildren().add(ExerciseParts.card("Critérios considerados", Icon.LIST_CHECK, "plain", chips));
        }

        getChildren().add(ExerciseParts.references(result.references(), onReference));
        Label usage = ExerciseParts.usageLabel(result.usage());
        if (usage != null) {
            getChildren().add(usage);
        }
        getChildren().add(ExerciseParts.endActions(onNext, onHome));
    }

    private static Node summary(ResultDto result, String level) {
        double score = result.score() == null ? 0 : result.score();
        String title = switch (level) {
            case "correct" -> "Resposta correta";
            case "incorrect" -> "A resposta precisa melhorar";
            default -> "Resposta parcialmente correta";
        };
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("verdict-title");
        Label summary = ExerciseParts.wrapLabel(result.summary(), "verdict-detail");
        VBox texts = new VBox(4, titleLabel, summary);
        HBox.setHgrow(texts, Priority.ALWAYS);
        HBox banner = new HBox(18, new ScoreRing(score, level), texts);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().addAll("verdict-banner", level);
        return banner;
    }

    private static VBox pointsCard(String title, Icon icon, String tone, List<String> points, String emptyText) {
        VBox rows = new VBox(8);
        if (points.isEmpty()) {
            rows.getChildren().add(ExerciseParts.wrapLabel(emptyText, "muted-info"));
        }
        for (String point : points) {
            Node mark = Icons.create(tone.equals("ok") ? Icon.CHECK : Icon.ARROW_RIGHT, 13);
            HBox row = new HBox(8, mark, ExerciseParts.wrapLabel(point, "point-text"));
            row.setAlignment(Pos.TOP_LEFT);
            row.getStyleClass().add("point-row");
            HBox.setHgrow(row.getChildren().get(1), Priority.ALWAYS);
            rows.getChildren().add(row);
        }
        return ExerciseParts.card(title, icon, tone, rows);
    }
}
