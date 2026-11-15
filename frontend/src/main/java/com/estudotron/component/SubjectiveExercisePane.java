package com.estudotron.component;

import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.backend.ExerciseDtos.ResultDto;
import com.estudotron.component.Icons.Icon;
import com.estudotron.service.ExerciseService.Failure;
import com.estudotron.viewmodel.ChatMessage.Reference;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Questão discursiva: enunciado, caixa de texto ampla e "Enviar resposta". Ao enviar, mostra o carregamento
 * da correção pela IA e, quando ela chega, a correção estruturada ({@link GradeResultPane}) logo abaixo.
 */
public final class SubjectiveExercisePane extends VBox implements ExercisePane {

    static final int MAX_CHARS = 6000;
    private static final int MIN_CHARS = 3;

    private final ExerciseDto exercise;
    private final Consumer<String> onSubmit;
    private final Runnable onNext;
    private final Runnable onHome;
    private final Consumer<Reference> onReference;

    private final TextArea answer = new TextArea();
    private final Label counter = new Label();
    private final Button submit = new Button("Enviar resposta");
    private final HBox submitRow = new HBox(12);
    private final Label errorLabel = ExerciseParts.wrapLabel("", "inline-error");
    private HBox grading;   // carregamento da correção (painel centralizado)

    public SubjectiveExercisePane(ExerciseDto exercise, Consumer<String> onSubmit, Runnable onNext, Runnable onHome,
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
        Label kind = new Label("Questão discursiva");
        kind.getStyleClass().add("ex-kind");
        HBox head = new HBox(10, kind);
        head.setAlignment(Pos.CENTER_LEFT);
        Label difficulty = ExerciseParts.difficultyChip(exercise.difficulty());
        if (difficulty != null) {
            head.getChildren().add(difficulty);
        }

        VBox statement = new VBox(ExerciseParts.markdown(exercise.statement()));
        statement.getStyleClass().add("statement-card");

        answer.setWrapText(true);
        answer.setPrefRowCount(10);
        answer.setMinHeight(190);
        answer.setPromptText("Escreva sua resposta com as suas próprias palavras…");
        answer.getStyleClass().add("answer-area");
        answer.setId("exAnswer");
        answer.textProperty().addListener((obs, old, text) -> {
            if (text.length() > MAX_CHARS) {
                answer.setText(old);   // limite do backend; não deixa passar
                return;
            }
            update();
        });

        counter.getStyleClass().add("muted-info");
        Icons.set(submit, Icon.SEND, 15);
        submit.getStyleClass().add("primary");
        submit.setId("exSubmit");
        submit.setOnAction(e -> onSubmit.accept(answer.getText().trim()));
        submitRow.setAlignment(Pos.CENTER_LEFT);
        submitRow.getChildren().addAll(submit, ExerciseParts.grow(), counter);
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
        update();

        getChildren().addAll(head, statement, answer, errorLabel, submitRow);
    }

    private void update() {
        int length = answer.getText().trim().length();
        submit.setDisable(length < MIN_CHARS);
        counter.setText(answer.getText().length() + " / " + MAX_CHARS);
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
    }

    @Override
    public void showSubmitting() {
        compactAnswer();
        answer.setEditable(false);
        getChildren().remove(submitRow);
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
        StatusPanel loading = StatusPanel.loading("Corrigindo sua resposta…",
                "A IA compara o que você escreveu com os trechos dos PDFs.");
        loading.getStyleClass().add("inline-status");
        grading = new HBox(loading);
        grading.setAlignment(Pos.CENTER);
        getChildren().add(grading);
    }

    @Override
    public void showFailure(Failure failure) {
        if (grading != null) {
            getChildren().remove(grading);
            grading = null;
        }
        answer.setEditable(true);
        errorLabel.setText(failure.message());
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
        if (!getChildren().contains(submitRow)) {
            getChildren().add(submitRow);
        }
    }

    @Override
    public void showResult(ResultDto result) {
        if (grading != null) {
            getChildren().remove(grading);
            grading = null;
        }
        compactAnswer();
        answer.setEditable(false);
        GradeResultPane grade = new GradeResultPane(result, exercise, onReference, onNext, onHome);
        getChildren().add(grade);
        GlassMotion.fadeSlideIn(grade, 0, 14, 300);
    }

    /** Depois do envio a caixa encolhe para a correção aparecer sem rolagem desnecessária. */
    private void compactAnswer() {
        answer.setMinHeight(96);
        answer.setPrefHeight(112);
        answer.setMaxHeight(112);
    }
}
