package com.estudotron.backend;

import java.io.IOException;
import java.nio.file.Path;

/** Contrato do backend usado pela interface (permite um falso nos testes). */
public interface BackendApi {

    String SLOT_A = "A";
    String SLOT_B = "B";

    WorkspaceDto getWorkspace() throws IOException, InterruptedException;

    /** Carrega o PDF no slot, substituindo o anterior. Devolve o documento (ainda indexando). */
    DocumentDto setSlot(String slot, Path pdf) throws IOException, InterruptedException;

    void clearSlot(String slot) throws IOException, InterruptedException;

    DocumentDto getDocument(String docId) throws IOException, InterruptedException;

    /** URL do visualizador PDF.js servido pelo backend. */
    String viewerUrl(String docId);
}
