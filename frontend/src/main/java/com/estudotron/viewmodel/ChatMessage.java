package com.estudotron.viewmodel;

import com.estudotron.backend.ChatDtos.ChatMessageDto;
import com.estudotron.backend.ChatDtos.ReferenceDto;
import com.estudotron.backend.ChatDtos.UsageDto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.UnaryOperator;

/** Mensagem do chat como a interface a mostra. */
public record ChatMessage(String id, Role role, String text, String provider, String modelLabel, Instant createdAt,
                          Status status, String error, String errorKind, List<Reference> references,
                          String usageSummary, boolean sample) {

    public enum Role { USER, ASSISTANT }

    public enum Status { COMPLETE, STREAMING, CANCELLED, ERROR }

    /** Citação a uma página de um documento (slot "A" ou "B"), validada pelo backend. */
    public record Reference(String slot, int page, String fileName, String documentId) {
        public String label() {
            return fileName + " · p. " + page;
        }

        public static Reference from(ReferenceDto dto) {
            return new Reference(dto.slot(), dto.page(), dto.fileName(), dto.documentId());
        }
    }

    public ChatMessage {
        references = references == null ? List.of() : List.copyOf(references);
    }

    public ChatMessage withText(String newText) {
        return new ChatMessage(id, role, newText, provider, modelLabel, createdAt, status, error, errorKind,
                references, usageSummary, sample);
    }

    public ChatMessage withStatus(Status newStatus) {
        return new ChatMessage(id, role, text, provider, modelLabel, createdAt, newStatus, error, errorKind,
                references, usageSummary, sample);
    }

    /** Mensagem do histórico/da resposta; {@code modelLabels} traduz o id do modelo para o nome amigável. */
    public static ChatMessage from(ChatMessageDto dto, UnaryOperator<String> modelLabels) {
        Status status = switch (dto.status() == null ? "complete" : dto.status()) {
            case "streaming" -> Status.STREAMING;
            case "cancelled" -> Status.CANCELLED;
            case "error" -> Status.ERROR;
            default -> Status.COMPLETE;
        };
        Instant created = dto.createdAt() == null ? Instant.now() : OffsetDateTime.parse(dto.createdAt()).toInstant();
        return new ChatMessage(
                dto.id(),
                "user".equals(dto.role()) ? Role.USER : Role.ASSISTANT,
                dto.content() == null ? "" : dto.content(),
                dto.provider(),
                dto.model() == null ? null : modelLabels.apply(dto.model()),
                created,
                status,
                dto.error(),
                dto.errorKind(),
                dto.referencesOrEmpty().stream().map(Reference::from).toList(),
                usageSummary(dto.usage()),
                false);
    }

    /** Mensagem local ainda sem id do backend (a pergunta recém-digitada, a resposta chegando). */
    public static ChatMessage local(Role role, String text, Status status, String provider, String modelLabel) {
        return new ChatMessage(null, role, text, provider, modelLabel, Instant.now(), status, null, null,
                List.of(), null, false);
    }

    static String usageSummary(UsageDto usage) {
        if (usage == null || usage.inputTokens() == null || usage.outputTokens() == null) {
            return null;
        }
        String tokens = Formats.tokens(usage.inputTokens() + usage.outputTokens()) + " tokens";
        return usage.costUsd() == null ? tokens : tokens + " · " + Formats.usd(usage.costUsd());
    }
}
