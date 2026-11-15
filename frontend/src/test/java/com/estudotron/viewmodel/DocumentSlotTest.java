package com.estudotron.viewmodel;

import com.estudotron.backend.BackendApi;
import com.estudotron.backend.DocumentDto;
import com.estudotron.backend.WorkspaceDto;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class DocumentSlotTest {

    private static final Executor DIRECT = Runnable::run;

    /** Backend falso em memória. */
    static final class FakeApi implements BackendApi {
        final List<String> calls = new ArrayList<>();
        DocumentDto next;
        IOException failure;
        DocumentDto refreshed;

        @Override public WorkspaceDto getWorkspace() { return new WorkspaceDto(Map.of()); }

        @Override public DocumentDto setSlot(String slot, Path pdf) throws IOException {
            calls.add("set " + slot + " " + pdf.getFileName());
            if (failure != null) throw failure;
            return next;
        }

        @Override public void clearSlot(String slot) throws IOException {
            calls.add("clear " + slot);
            if (failure != null) throw failure;
        }

        @Override public DocumentDto getDocument(String docId) {
            calls.add("get " + docId);
            return refreshed;
        }

        @Override public String viewerUrl(String docId) { return "http://x/" + docId; }
    }

    static DocumentDto doc(String id, String status, int done, String fileState) {
        return new DocumentDto(id, "A", id + ".pdf", 10, null, status, done, null, fileState);
    }

    private final FakeApi api = new FakeApi();
    private final DocumentSlot slot = new DocumentSlot("A", api, DIRECT, DIRECT);

    @Test
    void loadSetsDocumentAndReleasesBusy() {
        api.next = doc("d1", "processing", 0, "ok");
        slot.load(Path.of("x.pdf"));
        assertEquals("d1", slot.document().id());
        assertFalse(slot.busyProperty().get());
        assertEquals(List.of("set A x.pdf"), api.calls);
    }

    @Test
    void loadFailureKeepsPreviousDocumentAndReportsError() {
        api.next = doc("d1", "ready", 10, "ok");
        slot.load(Path.of("bom.pdf"));

        List<String> errors = new ArrayList<>();
        slot.setErrorListener(errors::add);
        api.failure = new IOException("O arquivo não é um PDF");
        slot.load(Path.of("ruim.pdf"));

        assertEquals("d1", slot.document().id());
        assertFalse(slot.busyProperty().get());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).contains("O arquivo não é um PDF"));
    }

    @Test
    void removeClearsDocument() {
        api.next = doc("d1", "ready", 10, "ok");
        slot.load(Path.of("a.pdf"));
        slot.remove();
        assertNull(slot.document());
        assertTrue(api.calls.contains("clear A"));
    }

    @Test
    void removeOnEmptySlotDoesNothing() {
        slot.remove();
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void refreshOnlyWhileProcessing() {
        slot.restore(doc("d1", "ready", 10, "ok"));
        slot.refreshIfProcessing();
        assertTrue(api.calls.isEmpty());

        slot.restore(doc("d2", "processing", 3, "ok"));
        api.refreshed = doc("d2", "ready", 10, "ok");
        slot.refreshIfProcessing();
        assertEquals("ready", slot.document().status());
    }

    @Test
    void staleRefreshIsDiscardedWhenDocumentWasReplaced() {
        slot.restore(doc("velho", "processing", 1, "ok"));
        List<Runnable> pendingUi = new ArrayList<>();
        DocumentSlot delayed = new DocumentSlot("A", api, DIRECT, pendingUi::add);
        delayed.restore(doc("velho", "processing", 1, "ok"));
        api.refreshed = doc("velho", "ready", 10, "ok");

        delayed.refresh();               // resposta fica pendente na fila de UI
        delayed.restore(doc("novo", "processing", 0, "ok")); // usuário troca o PDF
        pendingUi.forEach(Runnable::run);

        assertEquals("novo", delayed.document().id());
        assertEquals("processing", delayed.document().status());
    }

    @Test
    void shortStateLabels() {
        assertEquals("vazio", DocumentText.stateLabel(null));
        assertEquals("indexando 3/10", DocumentText.stateLabel(doc("d1", "processing", 3, "ok")));
        assertEquals("pronto", DocumentText.stateLabel(doc("d1", "ready", 10, "ok")));
        assertEquals("sem texto pesquisável", DocumentText.stateLabel(doc("d1", "no_text", 10, "ok")));
        assertEquals("alterado no disco", DocumentText.stateLabel(doc("d1", "ready", 10, "modified")));
        assertEquals("arquivo ausente", DocumentText.stateLabel(doc("d1", "ready", 10, "missing")));
        assertEquals("erro", DocumentText.stateLabel(doc("d1", "error", 3, "ok")));
    }

    @Test
    void indicatorTexts() {
        assertEquals(DocumentText.Level.EMPTY, DocumentText.describe(null).level());
        assertEquals("d1.pdf · 10 páginas · indexando 3/10",
                DocumentText.describe(doc("d1", "processing", 3, "ok")).text());
        assertEquals(DocumentText.Level.OK, DocumentText.describe(doc("d1", "ready", 10, "ok")).level());
        assertEquals(DocumentText.Level.WARNING, DocumentText.describe(doc("d1", "no_text", 10, "ok")).level());
        assertEquals(DocumentText.Level.WARNING, DocumentText.describe(doc("d1", "ready", 10, "modified")).level());
        assertEquals(DocumentText.Level.ERROR, DocumentText.describe(doc("d1", "ready", 10, "missing")).level());
        assertEquals(DocumentText.Level.ERROR, DocumentText.describe(doc("d1", "error", 3, "ok")).level());
    }
}
