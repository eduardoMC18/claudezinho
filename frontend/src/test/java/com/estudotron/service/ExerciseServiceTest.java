package com.estudotron.service;

import com.estudotron.backend.ApiClient.ApiException;
import com.estudotron.backend.ExerciseApi;
import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.backend.ExerciseDtos.ResultDto;
import com.estudotron.service.ExerciseService.Failure;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExerciseServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /** Executor que roda na hora, para os testes serem determinísticos. */
    private static final Executor NOW = Runnable::run;

    private static final class FakeApi implements ExerciseApi {
        IOException failure;
        final List<String> calls = new ArrayList<>();

        @Override
        public ExerciseDto generateExercise(String kind) throws IOException {
            calls.add("generate:" + kind);
            if (failure != null) {
                throw failure;
            }
            return new ExerciseDto("e1", kind, "Enunciado", "media", null, "gemini-x", null);
        }

        @Override
        public ResultDto answerExercise(String exerciseId, String option, String text) throws IOException {
            calls.add("answer:" + exerciseId + ":" + option + ":" + text);
            if (failure != null) {
                throw failure;
            }
            return JSON.readValue("{\"exercise_id\":\"e1\",\"kind\":\"objective\",\"correct\":true}", ResultDto.class);
        }
    }

    @Test
    void generateDeliversTheExerciseToTheUiExecutor() {
        FakeApi api = new FakeApi();
        List<String> got = new ArrayList<>();
        new ExerciseService(api, NOW, NOW).generate("objective", e -> got.add(e.id() + "/" + e.kind()),
                f -> got.add("falhou"));
        assertEquals(List.of("e1/objective"), got);
        assertEquals(List.of("generate:objective"), api.calls);
    }

    @Test
    void answerSendsOptionOrText() {
        FakeApi api = new FakeApi();
        ExerciseService service = new ExerciseService(api, NOW, NOW);
        List<ResultDto> results = new ArrayList<>();
        service.answer("e1", "b", null, results::add, f -> { });
        service.answer("e2", null, "minha resposta", results::add, f -> { });
        assertEquals(2, results.size());
        assertEquals(List.of("answer:e1:b:null", "answer:e2:null:minha resposta"), api.calls);
    }

    @Test
    void backendErrorsKeepTheirKindAndMessage() {
        FakeApi api = new FakeApi();
        api.failure = new ApiException("rate_limit", "Limite atingido");
        List<Failure> failures = new ArrayList<>();
        new ExerciseService(api, NOW, NOW).generate("subjective", e -> { }, failures::add);
        assertEquals(1, failures.size());
        assertEquals("rate_limit", failures.get(0).kind());
        assertEquals("Limite atingido", failures.get(0).message());
        assertTrue(failures.get(0).retryable());
    }

    @Test
    void connectionProblemsBecomeNetworkFailures() {
        FakeApi api = new FakeApi();
        api.failure = new IOException("Connection refused");
        List<Failure> failures = new ArrayList<>();
        new ExerciseService(api, NOW, NOW).answer("e1", "a", null, r -> { }, failures::add);
        assertEquals("network", failures.get(0).kind());
        assertTrue(failures.get(0).message().contains("Connection refused"));
    }

    @Test
    void failureKindsDecideWhichActionsTheScreenOffers() {
        assertTrue(new Failure("no_key", "x").needsSettings());
        assertTrue(new Failure("auth", "x").needsSettings());
        assertFalse(new Failure("no_key", "x").retryable());
        assertTrue(new Failure("no_documents", "x").needsDocuments());
        assertTrue(new Failure("no_text", "x").needsDocuments());
        assertFalse(new Failure("no_documents", "x").retryable());
        assertFalse(new Failure("already_answered", "x").retryable());
        assertTrue(new Failure("overloaded", "x").retryable());
        assertTrue(new Failure("invalid_response", "x").retryable());
    }

    @Test
    void dtosReadTheBackendJson() throws IOException {
        ExerciseDto question = JSON.readValue("""
                {"id":"e1","kind":"objective","statement":"Pergunta?","difficulty":"facil",
                 "options":[{"id":"a","text":"Um"},{"id":"b","text":"Dois"}],"model":"gemini-3.5-flash-lite",
                 "usage":{"input_tokens":410,"output_tokens":60,"cost_usd":0.0003},"campo_novo":1}""",
                ExerciseDto.class);
        assertEquals(2, question.options().size());
        assertEquals("b", question.options().get(1).id());
        assertNull(question.options().get(0).explanation());

        ResultDto objective = JSON.readValue("""
                {"exercise_id":"e1","kind":"objective","correct":false,"selected":"a","correct_option":"c",
                 "explanation":"Porque sim","options":[{"id":"a","text":"Um","explanation":"errada","is_correct":false},
                 {"id":"c","text":"Três","explanation":"certa","is_correct":true}],
                 "references":[{"slot":"A","page":3,"file_name":"bio.pdf","document_id":"d1"}]}""", ResultDto.class);
        assertEquals("c", objective.correctOption());
        assertTrue(objective.options().get(1).isCorrect());
        assertEquals(3, objective.references().get(0).page());
        assertTrue(objective.strengths().isEmpty());   // listas ausentes viram vazias, nunca nulas

        ResultDto grade = JSON.readValue("""
                {"exercise_id":"e2","kind":"subjective","verdict":"partial","score":6.5,"summary":"Bom",
                 "strengths":["a"],"missing":["b"],"corrections":[{"concept":"X","explanation":"Y"}],
                 "ideal_answer":"Resposta","criteria":["c1"],"references":[]}""", ResultDto.class);
        assertEquals(6.5, grade.score());
        assertEquals("X", grade.corrections().get(0).concept());
        assertEquals(List.of("c1"), grade.criteria());
    }
}
