package com.estudotron.component;

import com.estudotron.component.Icons.Icon;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Alternativa de múltipla escolha como botão de vidro: letra, texto e, depois da correção, o selo de
 * certo/errado e a explicação daquela alternativa. Só uma por questão fica selecionada (quem controla é a tela).
 */
public final class OptionButton extends HBox {

    private static final PseudoClass PICKED = PseudoClass.getPseudoClass("picked");
    private static final PseudoClass CORRECT = PseudoClass.getPseudoClass("correct");
    private static final PseudoClass WRONG = PseudoClass.getPseudoClass("wrong");
    private static final PseudoClass DIM = PseudoClass.getPseudoClass("dim");

    private final String id;
    private final VBox body = new VBox(4);
    private final Label explanation = ExerciseParts.wrapLabel("", "option-explanation");
    private final StackPane seal = new StackPane();
    private boolean locked;

    public OptionButton(String id, String text, Consumer<OptionButton> onPick) {
        this.id = id;
        getStyleClass().addAll("option-button", "glass-hover");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(14);
        setFocusTraversable(true);
        setMinWidth(0);

        Label letter = new Label(id.toUpperCase());
        letter.getStyleClass().add("option-letter");
        letter.setMinSize(30, 30);
        letter.setPrefSize(30, 30);
        letter.setAlignment(Pos.CENTER);

        Label label = ExerciseParts.wrapLabel(text, "option-text");
        explanation.setManaged(false);
        explanation.setVisible(false);
        body.getChildren().addAll(label, explanation);
        HBox.setHgrow(body, Priority.ALWAYS);

        seal.setMinSize(22, 22);
        seal.setPrefSize(22, 22);
        seal.setMaxSize(22, 22);
        seal.setVisible(false);
        seal.setManaged(false);

        getChildren().addAll(letter, body, seal);

        setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && !locked) {
                onPick.accept(this);
            }
        });
        setOnKeyPressed(e -> {
            if ((e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) && !locked) {
                onPick.accept(this);
                e.consume();
            }
        });
    }

    public String id() {
        return id;
    }

    public void setPicked(boolean picked) {
        pseudoClassStateChanged(PICKED, picked);
    }

    /** Depois do envio a resposta não pode mais mudar. */
    public void lock() {
        locked = true;
        getStyleClass().remove("glass-hover");
        setCursor(javafx.scene.Cursor.DEFAULT);
    }

    /** A correção falhou: a alternativa volta a poder ser trocada. */
    public void unlock() {
        locked = false;
        if (!getStyleClass().contains("glass-hover")) {
            getStyleClass().add("glass-hover");
        }
        setCursor(javafx.scene.Cursor.HAND);
    }

    /**
     * Mostra o resultado: a correta fica verde, a escolhida errada fica vermelha, as outras esmaecem;
     * cada uma exibe a sua explicação.
     */
    public void showResult(boolean isCorrect, boolean chosen, String why) {
        lock();
        pseudoClassStateChanged(PICKED, false);
        pseudoClassStateChanged(CORRECT, isCorrect);
        pseudoClassStateChanged(WRONG, chosen && !isCorrect);
        pseudoClassStateChanged(DIM, !isCorrect && !chosen);
        if (isCorrect || chosen) {
            seal.getChildren().setAll(Icons.create(isCorrect ? Icon.CHECK_CIRCLE : Icon.X_CIRCLE, 20));
            seal.setVisible(true);
            seal.setManaged(true);
        }
        if (why != null && !why.isBlank()) {
            explanation.setText(why);
            explanation.setManaged(true);
            explanation.setVisible(true);
        }
    }
}
