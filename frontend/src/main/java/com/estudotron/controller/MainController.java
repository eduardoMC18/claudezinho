package com.estudotron.controller;

import com.estudotron.backend.BackendApi;
import com.estudotron.backend.BackendServices;
import com.estudotron.backend.DocumentDto;
import com.estudotron.backend.WorkspaceDto;
import com.estudotron.component.GlassMotion;
import com.estudotron.component.Toast;
import com.estudotron.controller.SidebarController.Section;
import com.estudotron.service.ChatService;
import com.estudotron.service.ExerciseService;
import com.estudotron.service.FileDialogService;
import com.estudotron.service.NotificationService;
import com.estudotron.viewmodel.ChatMessage.Reference;
import com.estudotron.viewmodel.DocumentSlot;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Parent;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Monta a janela: cabeçalho, dois painéis de documento e o chat à direita.
 * Os PDFs ficam sempre lado a lado: a largura mínima da janela (ver App) garante espaço para
 * os controles de cada painel, que quebram de linha se o painel ficar estreito.
 */
public final class MainController {

    /** Posição inicial do divisor entre os PDFs e o chat (o chat fica com ~30% da largura). */
    private static final double CHAT_DIVIDER = 0.70;
    /** Em janelas mais estreitas a barra lateral se recolhe sozinha para os PDFs e o chat terem espaço. */
    private static final double SIDEBAR_AUTO_COLLAPSE_BELOW = 1320;

    private final Stage stage;
    private final BackendServices api;
    private final String autoloadFirst;
    private final String autoloadSecond;
    private final NotificationService notifications = new NotificationService();
    private final FileDialogService files = new FileDialogService();
    private final Executor io = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "io");
        t.setDaemon(true);
        return t;
    });

    private DocumentSlot slotFirst;
    private DocumentSlot slotSecond;
    private Timeline poller;
    private DocumentPanelController panelFirst;
    private DocumentPanelController panelSecond;
    private ChatController chatController;
    private SidebarController sidebar;
    private Parent exercisesView;

    @FXML private StackPane sidebarHost;
    @FXML private StackPane contentHost;
    @FXML private BorderPane readerView;
    @FXML private StackPane headerHost;
    @FXML private StackPane chatHost;
    @FXML private SplitPane mainSplit;
    @FXML private SplitPane documentsSplit;
    @FXML private Toast toast;

    /**
     * @param autoloadFirst  PDF a carregar no slot 1 depois de restaurar a sessão (apoio a testes), ou null
     * @param autoloadSecond idem para o slot 2
     */
    public MainController(Stage stage, BackendServices api, String autoloadFirst, String autoloadSecond) {
        this.stage = stage;
        this.api = api;
        this.autoloadFirst = autoloadFirst;
        this.autoloadSecond = autoloadSecond;
    }

    @FXML
    private void initialize() {
        notifications.setSink(toast::show);

        slotFirst = new DocumentSlot(BackendApi.SLOT_A, api, io, Platform::runLater);
        slotSecond = new DocumentSlot(BackendApi.SLOT_B, api, io, Platform::runLater);
        slotFirst.setErrorListener(notifications::error);
        slotSecond.setErrorListener(notifications::error);

        panelFirst = new DocumentPanelController(slotFirst, "PDF 1", this::chooseAndLoad, notifications);
        panelSecond = new DocumentPanelController(slotSecond, "PDF 2", this::chooseAndLoad, notifications);
        Parent first = ViewLoader.load("document-panel.fxml", panelFirst);
        Parent second = ViewLoader.load("document-panel.fxml", panelSecond);
        documentsSplit.getItems().addAll(first, second);
        documentsSplit.setDividerPositions(0.5);

        sidebar = new SidebarController(this::showSection);
        sidebarHost.getChildren().add(ViewLoader.load("sidebar.fxml", sidebar));
        stage.widthProperty().addListener((obs, old, w) -> sidebar.autoCollapse(w.doubleValue() < SIDEBAR_AUTO_COLLAPSE_BELOW));
        Platform.runLater(() -> sidebar.autoCollapse(stage.getWidth() < SIDEBAR_AUTO_COLLAPSE_BELOW));

        HeaderController header = new HeaderController(slotFirst, slotSecond, this::chooseAndLoad,
                this::openSettings, this::setChatVisible);
        headerHost.getChildren().add(ViewLoader.load("header.fxml", header));

        ChatService chatService = new ChatService(api, io, Platform::runLater);
        chatController = new ChatController(chatService, api, io, notifications, new ChatController.Host() {
            @Override
            public void openSettings() {
                MainController.this.openSettings();
            }

            @Override
            public void goToReference(Reference reference) {
                onReferenceClicked(reference);
            }
        });
        chatHost.getChildren().add(ViewLoader.load("chat-panel.fxml", chatController));

        // Criar Exercícios: tela própria, no mesmo conteúdo do leitor, que continua vivo (PDFs carregados) por baixo.
        ExerciseService exerciseService = new ExerciseService(api, io, Platform::runLater);
        ExercisesController exercises = new ExercisesController(exerciseService, slotFirst, slotSecond,
                new ExercisesController.Host() {
                    @Override
                    public void openReader() {
                        sidebar.select(Section.READER);
                        showSection(Section.READER);
                    }

                    @Override
                    public void goToReference(Reference reference) {
                        sidebar.select(Section.READER);
                        showSection(Section.READER);
                        Platform.runLater(() -> onReferenceClicked(reference));
                    }

                    @Override
                    public void openSettings() {
                        MainController.this.openSettings();
                    }
                });
        exercisesView = ViewLoader.load("exercises-view.fxml", exercises);
        exercisesView.setVisible(false);
        exercisesView.setManaged(false);
        contentHost.getChildren().add(exercisesView);

        // Ao redimensionar a janela, o chat mantém a largura e os visualizadores absorvem a diferença.
        SplitPane.setResizableWithParent(chatHost, false);
        Platform.runLater(() -> mainSplit.setDividerPositions(CHAT_DIVIDER));
        startPolling();
        restoreSession(autoloadFirst, autoloadSecond);
    }

    /**
     * Mostra ou oculta o chat (coluna à direita), com um fade + deslizamento curtos.
     * Os PDFs ocupam o espaço e reajustam o zoom sozinhos.
     */
    private void setChatVisible(boolean visible) {
        if (visible && !mainSplit.getItems().contains(chatHost)) {
            mainSplit.getItems().add(chatHost);
            Platform.runLater(() -> {
                mainSplit.setDividerPositions(CHAT_DIVIDER);
                GlassMotion.fadeSlideIn(chatHost, 36, 0, 260);
            });
        } else if (!visible && mainSplit.getItems().contains(chatHost)) {
            GlassMotion.fadeSlideOut(chatHost, 36, 0, 190, () -> mainSplit.getItems().remove(chatHost));
        }
    }

    /** Atualiza o progresso da indexação e, ao voltar para a janela, o estado dos arquivos. */
    private void startPolling() {
        poller = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            slotFirst.refreshIfProcessing();
            slotSecond.refreshIfProcessing();
        }));
        poller.setCycleCount(Animation.INDEFINITE);
        poller.play();
        stage.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                slotFirst.refresh();
                slotSecond.refresh();
            }
        });
    }

    private void restoreSession(String autoloadA, String autoloadB) {
        io.execute(() -> {
            try {
                WorkspaceDto workspace = api.getWorkspace();
                Platform.runLater(() -> {
                    slotFirst.restore(workspace.slot(BackendApi.SLOT_A));
                    slotSecond.restore(workspace.slot(BackendApi.SLOT_B));
                    autoload(slotFirst, autoloadA);
                    autoload(slotSecond, autoloadB);
                });
            } catch (Exception e) {
                Platform.runLater(() -> notifications.error("Não foi possível restaurar a sessão: " + e.getMessage()));
            }
        });
    }

    private static void autoload(DocumentSlot slot, String path) {
        if (path != null && !path.isBlank()) {
            slot.load(new File(path).toPath());
        }
    }

    private void chooseAndLoad(DocumentSlot slot) {
        String title = slot == slotFirst ? "Selecionar o primeiro PDF" : "Selecionar o segundo PDF";
        files.choosePdf(stage, title).ifPresent(file -> slot.load(file.toPath()));
    }

    /**
     * Alterna a tela da área de conteúdo. As duas continuam na cena (a oculta sai do layout): os PDFs
     * carregados, o zoom e a página de cada visualizador não são tocados ao trocar de tela.
     */
    private void showSection(Section section) {
        Parent shown = section == Section.READER ? readerView : exercisesView;
        Parent hidden = section == Section.READER ? exercisesView : readerView;
        if (shown.isVisible()) {
            return;
        }
        hidden.setVisible(false);
        hidden.setManaged(false);
        shown.setManaged(true);
        shown.setVisible(true);
        GlassMotion.fadeSlideIn(shown, 0, 8, 220);
    }

    /** Abre as configurações de IA; ao fechar, o chat recarrega provedores e chaves. */
    private void openSettings() {
        SettingsController.open(stage, api, io, notifications, () -> chatController.refreshSettings());
    }

    /** Clicar numa referência leva o visualizador do PDF citado até a página. */
    private void onReferenceClicked(Reference reference) {
        boolean first = BackendApi.SLOT_A.equals(reference.slot());
        DocumentSlot slot = first ? slotFirst : slotSecond;
        DocumentDto loaded = slot.document();
        String where = first ? "PDF 1" : "PDF 2";
        if (loaded == null) {
            notifications.warning(where + " não está carregado.");
            return;
        }
        if (reference.documentId() != null && !reference.documentId().equals(loaded.id())) {
            notifications.warning("“" + reference.fileName() + "” não está mais carregado em " + where
                    + " (o documento foi substituído).");
            return;
        }
        (first ? panelFirst : panelSecond).goToPage(reference.page());
    }

    public void shutdown() {
        if (poller != null) {
            poller.stop();
        }
    }
}
