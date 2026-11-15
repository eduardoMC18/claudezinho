package com.estudotron.backend;

import com.estudotron.backend.ChatDtos.ReferenceDto;
import com.estudotron.backend.ChatDtos.UsageDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** Exercícios como devolvidos pela API (JSON em snake_case). */
public final class ExerciseDtos {

    private ExerciseDtos() {}

    public static final String OBJECTIVE = "objective";
    public static final String SUBJECTIVE = "subjective";

    /** Alternativa. Ao gerar só vêm id e texto; depois do envio vêm também a explicação e se é a correta. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OptionDto(String id, String text, String explanation, Boolean isCorrect) {}

    /** Questão gerada (sem gabarito). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExerciseDto(String id, String kind, String statement, String difficulty, List<OptionDto> options,
                              String model, UsageDto usage) {
        public ExerciseDto {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CorrectionDto(String concept, String explanation) {}

    /** Correção. Os campos de cada tipo ficam nulos/vazios no outro. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultDto(String exerciseId, String kind, Boolean correct, String selected, String correctOption,
                            String explanation, List<OptionDto> options, String difficulty,
                            List<ReferenceDto> references, String verdict, Double score, String summary,
                            List<String> strengths, List<String> missing, List<CorrectionDto> corrections,
                            String idealAnswer, List<String> criteria, UsageDto usage) {
        public ResultDto {
            options = options == null ? List.of() : List.copyOf(options);
            references = references == null ? List.of() : List.copyOf(references);
            strengths = strengths == null ? List.of() : List.copyOf(strengths);
            missing = missing == null ? List.of() : List.copyOf(missing);
            corrections = corrections == null ? List.of() : List.copyOf(corrections);
            criteria = criteria == null ? List.of() : List.copyOf(criteria);
        }
    }
}
