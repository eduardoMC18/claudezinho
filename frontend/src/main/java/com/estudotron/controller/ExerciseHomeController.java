package com.estudotron.controller;

import com.estudotron.backend.DocumentDto;
import com.estudotron.backend.ExerciseDtos;
import com.estudotron.component.GlassMotion;
import com.estudotron.component.Icons.Icon;
import com.estudotron.component.ModeCard;
import com.estudotron.component.SourceDocCard;
import com.estudotron.component.StatusPanel;
import com.estudotron.viewmodel.DocumentSlot;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Consumer;

/**
 * Tela inicial de Criar Exercícios. Os cartões mostram os PDFs REAIS carregados no leitor: o controller só
 * observa os mesmos {@link DocumentSlot} do leitor (nenhuma cópia dos arquivos nem do texto extraído).
 */
public final class ExerciseHomeController {

    private final DocumentSlot first;
    private final DocumentSlot second;
    private final Consumer<String> onStart;
    private final Runnable onOpenReader;
    private ModeCard objectiveCard;
    private ModeCard subjectiveCard;
    private List<DocumentDto> shown = List.of();

    @FXML private VBox sourcesBox;
    @FXML private FlowPane sourceCards;
    @FXML private StackPane emptyBox;
    @FXML private HBox modeRow;
    @FXML private Label hint;

    public ExerciseHomeController(DocumentSlot first, DocumentSlot second, Consumer<String> onStart,
                                  Runnable onOpenReader) {
        this.first = first;
        this.second = second;
        this.onStart = onStart;
        this.onOpenReader = onOpenReader;
    }

    @FXML
    private void initialize() {
        objectiveCard = new ModeCard(Icon.LIST_CHECK, "Exercícios objetivos",
                "Questões de múltipla escolha para testar se você entendeu o conteúdo.",
                List.of("Escolha uma alternativa entre as opções", "Veja na hora o que está certo e por quê",
                        "Explicação de cada alternativa"),
                () -> onStart.accept(ExerciseDtos.OBJECTIVE));
        subjectiveCard = new ModeCard(Icon.PEN_LINE, "Exercícios subjetivos",
                "Questões discursivas: você escreve a resposta e a IA corrige com critérios claros.",
                List.of("Escreva com as suas próprias palavras", "Nota, acertos e o que faltou",
                        "Sugestão de resposta mais completa"),
                () -> onStart.accept(ExerciseDtos.SUBJECTIVE));
        for (ModeCard card : List.of(objectiveCard, subjectiveCard)) {
            HBox.setHgrow(card, Priority.ALWAYS);
            card.setMaxWidth(Double.MAX_VALUE);
            card.setPrefWidth(10);   // as duas ocupam metade cada
        }
        modeRow.getChildren().addAll(objectiveCard, subjectiveCard);

        Button toReader = new Button("Ir para o Leitor de PDFs");
        toReader.getStyleClass().add("primary");
        toReader.setId("exToReader");
        toReader.setOnAction(e -> onOpenReader.run());
        emptyBox.getChildren().add(StatusPanel.info(Icon.FILE, "Carregue um PDF primeiro",
                "Os exercícios são criados a partir dos documentos carregados no Leitor de PDFs. "
                        + "Adicione pelo menos um PDF e volte aqui.", toReader));

        first.documentProperty().addListener((obs, old, doc) -> refresh());
        second.documentProperty().addListener((obs, old, doc) -> refresh());
        refresh();
    }

    /** Reconstrói os cartões e o estado dos botões a partir dos documentos atuais. */
    private void refresh() {
        List<DocumentDto> docs = new java.util.ArrayList<>();
        sourceCards.getChildren().clear();
        addSource("PDF 1", first.document(), docs);
        addSource("PDF 2", second.document(), docs);

        boolean any = !docs.isEmpty();
        boolean ready = docs.stream().anyMatch(d -> DocumentDto.READY.equals(d.status()));
        for (var node : List.of(sourcesBox, modeRow)) {
            node.setVisible(any);
            node.setManaged(any);
        }
        emptyBox.setVisible(!any);
        emptyBox.setManaged(!any);
        objectiveCard.setDisable(!ready);
        subjectiveCard.setDisable(!ready);

        String message = "";
        if (any && !ready) {
            boolean indexing = docs.stream().anyMatch(DocumentDto::isProcessing);
            message = indexing ? "Aguarde o fim da indexação dos PDFs para criar exercícios."
                    : "Os PDFs carregados não têm texto pesquisável (parecem escaneados), então a IA não consegue lê-los.";
        } else if (any) {
            message = "Para criar as questões, trechos dos seus PDFs são enviados ao Gemini (Google). "
                    + "A chave configurada em Configurações é a mesma do assistente.";
        }
        hint.setText(message);
        hint.setManaged(!message.isEmpty());
        hint.setVisible(!message.isEmpty());

        if (!docs.equals(shown) && !docs.isEmpty()) {
            int i = 0;
            for (var card : sourceCards.getChildren()) {   // pequena entrada escalonada dos cartões
                int delay = i++ * 70;
                card.setOpacity(0);
                javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(
                        javafx.util.Duration.millis(delay));
                pause.setOnFinished(e -> GlassMotion.fadeSlideIn(card, 0, 10, 260));
                pause.play();
            }
        }
        shown = List.copyOf(docs);
    }

    private void addSource(String label, DocumentDto doc, List<DocumentDto> docs) {
        if (doc != null) {
            docs.add(doc);
            sourceCards.getChildren().add(new SourceDocCard(label, doc));
        }
    }

    /** Nomes dos PDFs carregados, para mensagens como "Lendo trechos de a.pdf e b.pdf". */
    public static String namesOf(DocumentSlot first, DocumentSlot second) {
        List<String> names = new java.util.ArrayList<>();
        if (first.document() != null) {
            names.add(first.document().name());
        }
        if (second.document() != null) {
            names.add(second.document().name());
        }
        return String.join(" e ", names);
    }
}
