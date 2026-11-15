package com.estudotron.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/** Estado do workspace: documento de cada slot ("A" e "B"), ou null se vazio. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkspaceDto(Map<String, DocumentDto> slots) {

    public DocumentDto slot(String slot) {
        return slots == null ? null : slots.get(slot);
    }
}
