package com.estudotron.controller;

import com.estudotron.backend.ExerciseDtos;
import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.component.ExercisePane;
import com.estudotron.component.GlassMotion;
import com.estudotron.component.Icons;
import com.estudotron.component.Icons.Icon;
import com.estudotron.component.ObjectiveExercisePane;
import com.estudotron.component.StatusPanel;
import com.estudotron.component.SubjectiveExercisePane;
import com.estudotron.service.ExerciseService;
import com.estudotron.service.ExerciseService.Failure;
import com.estudotron.viewmodel.ChatMessage.Reference;
import com.estudotron.viewmodel.DocumentSlot;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;

/**
 * Fluxo de Criar Exercícios: início → gerando → questão → corrigindo → resultado (ou erro). Este controller só
 * troca de estado e conversa com o {@link ExerciseService}; a tela inicial é do {@link ExerciseHomeController} e
 * cada tipo de questão tem o seu componente ({@link ObjectiveExercisePane}, {@link SubjectiveExercisePane}).
 */
public final class ExercisesController {

    /** O que esta tela precisa do resto do app. */
    public interface Host {
        void openReader();

        void goToReference(Reference reference);

        void openSettings();
    }

    private final ExerciseService service;
    private final DocumentSlot first;
    private final DocumentSlot second;
    private final Host host;
    /** Muda a cada novo fluxo; respostas atrasadas de um fluxo abandonado (voltou ao início) são descartadas. */
    private int flow;
    private Parent homeView;

    @FXML private HBox toolbar;
    @FXML private Label modeTitle;
    @FXML private Button backButton;
    @FXML private StackPane stage;

    public ExercisesController(ExerciseService service, DocumentSlot first, DocumentSlot second, Host host) {
        this.service = service;
        this.first = first;
        this.second = second;
        this.host = host;
    }

    @FXML
    private void initialize() {
        Icons.set(backButton, Icon.ARROW_LEFT, 15);
        ExerciseHomeController home = new ExerciseHomeController(first, second, this::start, host::openReader);
        homeView = ViewLoader.load("exercise-home.fxml", home);
        showHome();
    }

    @FXML
    private void backHome() {
        showHome();
    }

    // ----------------------------------------------------------------- estados

    private void showHome() {
        flow++;
        toolbar.setVisible(false);
        toolbar.setManaged(false);
        swap(homeView);
    }

    /** Pede uma questão do tipo escolhido e mostra o carregamento até ela chegar. */
    private void start(String kind) {
        int mine = ++flow;
        boolean objective = ExerciseDtos.OBJECTIVE.equals(kind);
        modeTitle.setText(objective ? "Exercícios objetivos" : "Exercícios subjetivos");
        toolbar.setVisible(true);
        toolbar.setManaged(true);
        String names = ExerciseHomeController.namesOf(first, second);
        swap(centered(StatusPanel.loading("Gerando sua questão…",
                names.isEmpty() ? "A IA está lendo os trechos dos PDFs."
                        : "A IA está lendo trechos de " + names + ".")));
        service.generate(kind, exercise -> {
            if (mine == flow) {
                showQuestion(exercise);
            }
        }, failure -> {
            if (mine == flow) {
                showError(failure, () -> start(kind));
            }
        });
    }

    private void showQuestion(ExerciseDto exercise) {
        int mine = flow;
        ExercisePane[] holder = new ExercisePane[1];
        Runnable next = () -> start(exercise.kind());
        if (ExerciseDtos.OBJECTIVE.equals(exercise.kind())) {
            holder[0] = new ObjectiveExercisePane(exercise, option -> submit(mine, exercise, holder[0], option, null),
                    next, this::showHome, this::onReference);
        } else {
            holder[0] = new SubjectiveExercisePane(exercise, text -> submit(mine, exercise, holder[0], null, text),
                    next, this::showHome, this::onReference);
        }
        swap(scrolled(holder[0].node()));
    }

    private void submit(int mine, ExerciseDto exercise, ExercisePane pane, String option, String text) {
        pane.showSubmitting();
        service.answer(exercise.id(), option, text, result -> {
            if (mine == flow) {
                pane.showResult(result);
            }
        }, failure -> {
            if (mine == flow) {
                pane.showFailure(failure);
            }
        });
    }

    private void showError(Failure failure, Runnable retry) {
        java.util.List<Node> actions = new java.util.ArrayList<>();
        if (failure.retryable()) {
            Button again = new Button("Tentar de novo");
            again.getStyleClass().add("primary");
            Icons.set(again, Icon.REFRESH, 15);
            again.setOnAction(e -> retry.run());
            actions.add(again);
        }
        if (failure.needsSettings()) {
            Button settings = new Button("Abrir Configurações");
            settings.getStyleClass().add("primary");
            settings.setOnAction(e -> host.openSettings());
            actions.add(settings);
        }
        if (failure.needsDocuments()) {
            Button reader = new Button("Ir para o Leitor de PDFs");
            reader.getStyleClass().add("primary");
            reader.setOnAction(e -> host.openReader());
            actions.add(reader);
        }
        Button home = new Button("Voltar ao início");
        home.getStyleClass().add("secondary");
        home.setId("exErrHome");
        home.setOnAction(e -> showHome());
        actions.add(home);
        swap(centered(StatusPanel.error(titleFor(failure), failure.message(), actions.toArray(Node[]::new))));
    }

    private static String titleFor(Failure failure) {
        return switch (failure.kind()) {
            case "no_key" -> "Chave do Gemini não configurada";
            case "auth" -> "Chave do Gemini recusada";
            case "rate_limit" -> "Limite do Gemini atingido";
            case "overloaded" -> "Gemini sobrecarregado";
            case "network" -> "Sem conexão com o Gemini";
            case "invalid_response" -> "A IA respondeu fora do formato";
            case "refused" -> "A resposta foi bloqueada";
            case "no_documents" -> "Nenhum PDF carregado";
            case "no_text" -> "PDFs sem texto";
            case "indexing" -> "PDFs ainda sendo indexados";
            default -> "Não foi possível concluir";
        };
    }

    private void onReference(Reference reference) {
        host.goToReference(reference);
    }

    // ----------------------------------------------------------------- troca de conteúdo

    private void swap(Node content) {
        stage.getChildren().setAll(content);
        GlassMotion.fadeSlideIn(content, 0, 10, 240);
    }

    private static Node centered(Node content) {
        StackPane box = new StackPane(content);
        box.getStyleClass().add("ex-centered");
        return box;
    }

    /** Questão e correção rolam juntas; o conteúdo fica numa coluna centralizada e legível. */
    private static Node scrolled(Node content) {
        StackPane wrap = new StackPane(content);
        wrap.getStyleClass().add("ex-wrap");
        wrap.setAlignment(javafx.geometry.Pos.TOP_CENTER);
        if (content instanceof javafx.scene.layout.Region region) {
            region.setMaxWidth(880);
        }
        ScrollPane scroll = new ScrollPane(wrap);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("ex-scroll");
        scroll.setId("exScroll");
        return scroll;
    }
}
