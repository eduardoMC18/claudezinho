package com.estudotron.viewmodel;

import com.estudotron.backend.BackendApi;
import com.estudotron.backend.DocumentDto;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Estado de um dos dois slots (A ou B). Cada slot tem seu próprio viewmodel: nada é
 * compartilhado entre os visualizadores.
 *
 * <p>As chamadas ao backend rodam em {@code io}; as mudanças de estado são aplicadas em
 * {@code ui} (a thread do JavaFX no app; execução direta nos testes).
 */
public final class DocumentSlot {

    private final String slot;
    private final BackendApi api;
    private final Executor io;
    private final Executor ui;

    private final ObjectProperty<DocumentDto> document = new SimpleObjectProperty<>();
    private final BooleanProperty busy = new SimpleBooleanProperty();
    private Consumer<String> errorListener = message -> { };

    public DocumentSlot(String slot, BackendApi api, Executor io, Executor ui) {
        this.slot = slot;
        this.api = api;
        this.io = io;
        this.ui = ui;
    }

    public String slot() {
        return slot;
    }

    public ReadOnlyObjectProperty<DocumentDto> documentProperty() {
        return document;
    }

    public DocumentDto document() {
        return document.get();
    }

    public BooleanProperty busyProperty() {
        return busy;
    }

    public void setErrorListener(Consumer<String> listener) {
        this.errorListener = listener;
    }

    public String viewerUrl(DocumentDto doc) {
        return api.viewerUrl(doc.id());
    }

    /** Carrega um PDF neste slot (substituindo o atual, se houver). */
    public void load(Path pdf) {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        io.execute(() -> {
            try {
                DocumentDto loaded = api.setSlot(slot, pdf);
                ui.execute(() -> {
                    document.set(loaded);
                    busy.set(false);
                });
            } catch (IOException e) {
                fail("Não foi possível abrir o PDF: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Operação interrompida");
            }
        });
    }

    /** Remove o documento do slot. */
    public void remove() {
        if (busy.get() || document.get() == null) {
            return;
        }
        busy.set(true);
        io.execute(() -> {
            try {
                api.clearSlot(slot);
                ui.execute(() -> {
                    document.set(null);
                    busy.set(false);
                });
            } catch (IOException e) {
                fail("Não foi possível remover o PDF: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Operação interrompida");
            }
        });
    }

    /** Restaura o estado vindo do backend (ao abrir o programa). */
    public void restore(DocumentDto doc) {
        document.set(doc);
    }

    /** Atualiza status/progresso e o estado do arquivo no disco. Falhas são ignoradas. */
    public void refresh() {
        DocumentDto current = document.get();
        if (current == null || busy.get()) {
            return;
        }
        io.execute(() -> {
            try {
                DocumentDto fresh = api.getDocument(current.id());
                ui.execute(() -> {
                    // descarta resposta atrasada se o documento foi trocado/removido nesse meio tempo
                    DocumentDto now = document.get();
                    if (now != null && now.id().equals(fresh.id())) {
                        document.set(fresh);
                    }
                });
            } catch (IOException ignored) {
                // documento sumiu ou backend ocupado: a próxima atualização tenta de novo
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Atualização periódica: só enquanto o documento está sendo indexado. */
    public void refreshIfProcessing() {
        DocumentDto current = document.get();
        if (current != null && current.isProcessing()) {
            refresh();
        }
    }

    private void fail(String message) {
        ui.execute(() -> {
            busy.set(false);
            errorListener.accept(message);
        });
    }
}
