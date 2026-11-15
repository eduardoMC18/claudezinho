package com.estudotron.backend;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** DTOs do chat e das configurações de IA (JSON em snake_case). Chaves de API nunca aparecem aqui. */
public final class ChatDtos {

    private ChatDtos() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReferenceDto(String slot, int page, String fileName, String documentId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageDto(Integer inputTokens, Integer outputTokens, Double costUsd, Boolean truncated,
                           Integer invalidCitationsRemoved) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatMessageDto(String id, String conversationId, String role, String content, String provider,
                                 String model, String status, String error, String errorKind,
                                 List<ReferenceDto> references, UsageDto usage, String createdAt) {
        public List<ReferenceDto> referencesOrEmpty() {
            return references == null ? List.of() : references;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConversationDto(String id, String title, String createdAt, List<ChatMessageDto> messages) {
        public List<ChatMessageDto> messagesOrEmpty() {
            return messages == null ? List.of() : messages;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelDto(String id, String label, String description, Long contextWindow,
                           Double priceInputPerMtok, Double priceOutputPerMtok) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProviderDto(String id, String displayName, String keyHint, boolean hasKey, String selectedModel,
                              List<ModelDto> models) {
        public ModelDto selectedModelInfo() {
            return models.stream().filter(m -> m.id().equals(selectedModel)).findFirst().orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AiSettingsDto(String defaultProvider, List<ProviderDto> providers) {
        public ProviderDto provider(String id) {
            return providers.stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConnectionTestDto(boolean ok, String message, Integer latencyMs) {}
}
