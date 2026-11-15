package com.estudotron.controller;

import com.estudotron.backend.DocumentDto;
import com.estudotron.component.DropZone;
import com.estudotron.component.Icons;
import com.estudotron.component.Icons.Icon;
import com.estudotron.component.PdfViewerPane;
import com.estudotron.service.NotificationService;
import com.estudotron.viewmodel.DocumentSlot;
import com.estudotron.viewmodel.DocumentText;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.io.File;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Um dos dois painéis de documento. Cada instância tem o próprio {@link DocumentSlot} e o
 * próprio visualizador: página, zoom e rolagem nunca são compartilhados.
 */
public final class DocumentPanelController {

    private final DocumentSlot slot;
    private final String title; // "PDF 1" / "PDF 2"
    private final Consumer<DocumentSlot> onChoose;
    private final NotificationService notifications;

    @FXML private VBox root;
    @FXML private Label slotBadge;
    @FXML private Label fileName;
    @FXML private Label fileState;
    @FXML private Button removeButton;
    @FXML private PdfViewerPane viewer;
    @FXML private DropZone dropZone;
    @FXML private Label message;
    @FXML private FlowPane toolbar;
    @FXML private Button prevButton;
    @FXML private Button nextButton;
    @FXML private TextField pageField;
    @FXML private Label pageTotal;
    @FXML private Button zoomOutButton;
    @FXML private Button zoomInButton;
    @FXML private Label zoomLabel;
    @FXML private ToggleButton fitToggle;

    private final Tooltip nameTip = new Tooltip();
    private String shownDocId;
    private int currentPage = 1;
    private int totalPages;

    public DocumentPanelController(DocumentSlot slot, String title, Consumer<DocumentSlot> onChoose,
                                   NotificationService notifications) {
        this.slot = slot;
        this.title = title;
        this.onChoose = onChoose;
        this.notifications = notifications;
    }

    @FXML
    private void initialize() {
        slotBadge.setText(title.toUpperCase());
        root.setFocusTraversable(true); // permite tirar o foco do campo de página

        Icons.set(removeButton, Icon.CLOSE, 15);
        removeButton.setTooltip(new Tooltip("Remover documento"));
        Icons.set(prevButton, Icon.CHEVRON_LEFT, 16);
        prevButton.setTooltip(new Tooltip("Página anterior"));
        Icons.set(nextButton, Icon.CHEVRON_RIGHT, 16);
        nextButton.setTooltip(new Tooltip("Próxima página"));
        Icons.set(zoomOutButton, Icon.MINUS, 15);
        zoomOutButton.setTooltip(new Tooltip("Diminuir zoom"));
        Icons.set(zoomInButton, Icon.PLUS, 15);
        zoomInButton.setTooltip(new Tooltip("Aumentar zoom"));
        fitToggle.setTooltip(new Tooltip("Ajustar o zoom à largura do painel automaticamente"));
        fitToggle.setSelected(true);

        // Campo de página: só dígitos; Enter ou perda de foco navega.
        pageField.setTextFormatter(new TextFormatter<String>(change ->
                change.getControlNewText().matches("\\d{0,5}") ? change : null));
        pageField.setOnAction(e -> goToTypedPage());
        pageField.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                pageField.setText(totalPages > 0 ? String.valueOf(currentPage) : "");
            }
        });

        dropZone.setOnChoose(() -> onChoose.accept(slot));
        viewer.setPageListener(this::onPageChanged);
        viewer.setZoomListener(this::onZoomChanged);
        viewer.setStatusListener(status -> { });

        installDropHandlers();
        slot.busyProperty().addListener((obs, old, busy) -> updateControls());
        slot.documentProperty().addListener((obs, old, doc) -> render(doc));
        render(slot.document());
    }

    /** Leva o visualizador até a página (usado pelas referências do chat). */
    public void goToPage(int page) {
        viewer.goToPage(page);
    }

    // ----- ações dos controles -----

    @FXML
    private void remove() {
        slot.remove();
    }

    @FXML
    private void previousPage() {
        viewer.previousPage();
    }

    @FXML
    private void nextPage() {
        viewer.nextPage();
    }

    @FXML
    private void zoomIn() {
        viewer.zoomIn();
    }

    @FXML
    private void zoomOut() {
        viewer.zoomOut();
    }

    @FXML
    private void toggleFit() {
        viewer.setFit(fitToggle.isSelected());
    }

    private void goToTypedPage() {
        String text = pageField.getText();
        if (!text.isBlank()) {
            viewer.goToPage(Integer.parseInt(text));
        }
        root.requestFocus();
    }

    // ----- eventos do visualizador -----

    private void onPageChanged(int page, int total) {
        currentPage = page;
        totalPages = total;
        pageTotal.setText("/ " + total);
        if (!pageField.isFocused()) {
            pageField.setText(String.valueOf(page));
        }
        updateControls();
    }

    private void onZoomChanged(int percent, boolean fit) {
        zoomLabel.setText(percent + "%");
        fitToggle.setSelected(fit);
    }

    // ----- estado do documento -----

    private void render(DocumentDto doc) {
        if (doc == null) {
            fileName.setText("Nenhum documento");
            fileState.setText("Adicione ou arraste um PDF");
        } else {
            fileName.setText(doc.name());
            fileState.setText(doc.pages() + (doc.pages() == 1 ? " página" : " páginas")
                    + " · " + DocumentText.stateLabel(doc));
        }
        Tooltip.uninstall(fileName, nameTip);
        if (doc != null) {
            nameTip.setText(doc.name());
            Tooltip.install(fileName, nameTip);
        }

        String newId = doc == null ? null : doc.id();
        if (!Objects.equals(newId, shownDocId)) { // só o estado mudou? então não recarrega o PDF
            shownDocId = newId;
            currentPage = 1;
            totalPages = doc == null ? 0 : doc.pages();
            pageTotal.setText(doc == null ? "/ –" : "/ " + doc.pages());
            pageField.setText(doc == null ? "" : "1");
            zoomLabel.setText("–");
            fitToggle.setSelected(true);

            boolean missing = doc != null && DocumentDto.FILE_MISSING.equals(doc.fileState());
            if (doc == null || missing) {
                viewer.clear();
            } else {
                viewer.show(slot.viewerUrl(doc));
            }
            message.setText(missing ? "Arquivo não encontrado no disco:\n" + doc.name()
                    + "\nArraste o PDF de volta ou clique em “Adicionar PDF”." : "");
            message.setVisible(missing);
        }
        dropZone.setVisible(doc == null);
        updateControls();
    }

    private void updateControls() {
        DocumentDto doc = slot.document();
        boolean viewable = doc != null && !DocumentDto.FILE_MISSING.equals(doc.fileState());
        boolean busy = slot.busyProperty().get();
        removeButton.setDisable(doc == null || busy);
        toolbar.setDisable(!viewable);
        prevButton.setDisable(!viewable || currentPage <= 1);
        nextButton.setDisable(!viewable || (totalPages > 0 && currentPage >= totalPages));
    }

    // ----- arrastar e soltar -----

    /**
     * Aceita PDFs soltos em qualquer ponto do painel. São filtros (fase de captura) para que
     * a WebView nunca receba o arquivo (ela tentaria navegar até ele). Soltar sobre um painel
     * ocupado substitui o documento.
     */
    private void installDropHandlers() {
        root.addEventFilter(DragEvent.DRAG_OVER, e -> {
            Dragboard board = e.getDragboard();
            if (!board.hasFiles()) {
                return;
            }
            boolean acceptable = firstPdf(board) != null && !slot.busyProperty().get();
            if (acceptable) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            setDragHighlight(acceptable);
            e.consume();
        });
        root.addEventFilter(DragEvent.DRAG_DROPPED, e -> {
            Dragboard board = e.getDragboard();
            if (!board.hasFiles()) {
                return;
            }
            File pdf = firstPdf(board);
            boolean accepted = pdf != null && !slot.busyProperty().get();
            if (accepted) {
                slot.load(pdf.toPath());
            } else if (pdf == null) {
                notifications.warning("Apenas arquivos PDF podem ser abertos.");
            }
            setDragHighlight(false);
            e.setDropCompleted(accepted);
            e.consume();
        });
        root.setOnDragExited(e -> setDragHighlight(false));
    }

    private void setDragHighlight(boolean on) {
        dropZone.setActive(on);
        if (on && !root.getStyleClass().contains("drag-over")) {
            root.getStyleClass().add("drag-over");
        } else if (!on) {
            root.getStyleClass().remove("drag-over");
        }
    }

    private static File firstPdf(Dragboard board) {
        return board.getFiles().stream()
                .filter(f -> f.isFile() && f.getName().toLowerCase().endsWith(".pdf"))
                .findFirst()
                .orElse(null);
    }
}
