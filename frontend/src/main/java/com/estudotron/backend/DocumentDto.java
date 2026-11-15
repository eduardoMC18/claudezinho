package com.estudotron.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Documento como devolvido pela API (JSON em snake_case). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DocumentDto(
        String id,
        String slot,
        String name,
        int pages,
        String title,
        String status,
        int pagesDone,
        String error,
        String fileState) {

    public static final String PROCESSING = "processing";
    public static final String READY = "ready";
    public static final String NO_TEXT = "no_text";
    public static final String ERROR = "error";

    public static final String FILE_OK = "ok";
    public static final String FILE_MODIFIED = "modified";
    public static final String FILE_MISSING = "missing";

    public boolean isProcessing() {
        return PROCESSING.equals(status);
    }
}
