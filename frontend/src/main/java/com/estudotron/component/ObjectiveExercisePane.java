package com.estudotron.component;

import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.backend.ExerciseDtos.OptionDto;
import com.estudotron.backend.ExerciseDtos.ResultDto;
import com.estudotron.component.Icons.Icon;
import com.estudotron.service.ExerciseService.Failure;
import com.estudotron.viewmodel.ChatMessage.Reference;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Questão de múltipla escolha: enunciado, alternativas como botões (uma só selecionada), "Enviar resposta"
 * e, depois da correção, o selo de acerto/erro, a explicação de cada alternativa e as referências.
 */
public final class ObjectiveExercisePane extends VBox implements ExercisePane {

    private final ExerciseDto exercise;
    private final Consumer<String> onSubmit;
    private final Runnable onNext;
    private final Runnable onHome;
    private final Consumer<Reference> onReference;

    private final List<OptionButton> buttons = new ArrayList<>();
    private final Button submit = new Button("Enviar resposta");
    private final PulseDots dots = new PulseDots();
    private final Label submitHint = new Label();
    private final Label errorLabel = ExerciseParts.wrapLabel("", "inline-error");
    private final HBox submitRow = new HBox(12);
    private String selected;

    public ObjectiveExercisePane(ExerciseDto exercise, Consumer<String> onSubmit, Runnable onNext, Runnable onHome,
                                 Consumer<Reference> onReference) {
        this.exercise = exercise;
        this.onSubmit = onSubmit;
        this.onNext = onNext;
        this.onHome = onHome;
        this.onReference = onReference;
        getStyleClass().add("exercise-pane");
        setSpacing(16);
        build();
    }

    @Override
    public Node node() {
        return this;
    }

    private void build() {
        Label kind = new Label("Questão objetiva");
        kind.getStyleClass().add("ex-kind");
        HBox head = new HBox(10, kind);
        head.setAlignment(Pos.CENTER_LEFT);
        Label difficulty = ExerciseParts.difficultyChip(exercise.difficulty());
        if (difficulty != null) {
            head.getChildren().add(difficulty);
        }

        VBox statement = new VBox(ExerciseParts.markdown(exercise.statement()));
        statement.getStyleClass().add("statement-card");

        VBox options = new VBox(10);
        for (OptionDto option : exercise.options()) {
            OptionButton button = new OptionButton(option.id(), option.text(), this::pick);
            buttons.add(button);
            options.getChildren().add(button);
        }

        Icons.set(submit, Icon.SEND, 15);
        submit.getStyleClass().add("primary");
        submit.setId("exSubmit");
        submit.setDisable(true);
        submit.setOnAction(e -> {
            if (selected != null) {
                onSubmit.accept(selected);
            }
        });
        submitHint.setText("Escolha uma alternativa para enviar.");
        submitHint.getStyleClass().add("muted-info");
        dots.setVisible(false);
        dots.setManaged(false);
        submitRow.setAlignment(Pos.CENTER_LEFT);
        submitRow.getChildren().addAll(submit, dots, submitHint);

        errorLabel.setManaged(false);
        errorLabel.setVisible(false);

        getChildren().addAll(head, statement, options, errorLabel, submitRow);
    }

    private void pick(OptionButton chosen) {
        selected = chosen.id();
        buttons.forEach(b -> b.setPicked(b == chosen));
        submit.setDisable(false);
        submitHint.setText("Alternativa " + chosen.id().toUpperCase() + " selecionada.");
        hideError();
    }

    @Override
    public void showSubmitting() {
        hideError();
        buttons.forEach(OptionButton::lock);   // a resposta não muda mais depois do envio
        submit.setDisable(true);
        submit.setText("Corrigindo…");
        dots.setVisible(true);
        dots.setManaged(true);
        submitHint.setText("");
    }

    @Override
    public void showFailure(Failure failure) {
        errorLabel.setText(failure.message());
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
        submit.setText("Enviar resposta");
        submit.setDisable(false);
        dots.setVisible(false);
        dots.setManaged(false);
        submitHint.setText("");
        buttons.forEach(b -> b.unlock());      // a correção não aconteceu: pode mudar e reenviar
    }

    @Override
    public void showResult(ResultDto result) {
        hideError();
        for (OptionButton button : buttons) {
            OptionDto data = result.options().stream().filter(o -> o.id().equals(button.id())).findFirst().orElse(null);
            boolean correct = data != null && Boolean.TRUE.equals(data.isCorrect());
            button.showResult(correct, button.id().equals(result.selected()), data == null ? null : data.explanation());
        }
        getChildren().remove(submitRow);

        boolean right = Boolean.TRUE.equals(result.correct());
        Label title = new Label(right ? "Resposta correta!" : "Resposta incorreta");
        title.getStyleClass().add("verdict-title");
        Label detail = ExerciseParts.wrapLabel(right
                ? "Você marcou a alternativa " + result.correctOption().toUpperCase() + "."
                : "Você marcou " + result.selected().toUpperCase() + "; a correta é "
                        + result.correctOption().toUpperCase() + ".", "verdict-detail");
        VBox texts = new VBox(2, title, detail);
        StackPane seal = new StackPane(Icons.create(right ? Icon.CHECK_CIRCLE : Icon.X_CIRCLE, 26));
        seal.getStyleClass().add("verdict-seal");
        HBox banner = new HBox(14, seal, texts);
        banner.setAlignment(Pos.CENTER_LEFT);
        banner.getStyleClass().addAll("verdict-banner", right ? "correct" : "incorrect");

        VBox feedback = new VBox(14, banner);
        feedback.getChildren().add(ExerciseParts.card("Por que a alternativa " + result.correctOption().toUpperCase()
                + " está correta", Icon.LIGHTBULB, "ok", ExerciseParts.markdown(result.explanation())));
        feedback.getChildren().add(ExerciseParts.references(result.references(), onReference));
        Label usage = ExerciseParts.usageLabel(result.usage() != null ? result.usage() : exercise.usage());
        if (usage != null) {
            feedback.getChildren().add(usage);
        }
        feedback.getChildren().add(ExerciseParts.endActions(onNext, onHome));
        getChildren().add(feedback);
        GlassMotion.fadeSlideIn(feedback, 0, 14, 280);
    }

    private void hideError() {
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
    }
}
