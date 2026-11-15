package com.estudotron.backend;

import com.estudotron.backend.ApiClient.ApiException;
import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.backend.ExerciseDtos.ResultDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O cliente REST de exercícios contra um servidor HTTP local: caminhos, corpo, token e erros do backend. */
class ExerciseClientTest {

    private HttpServer server;
    private ApiClient client;
    private final List<String> seen = new ArrayList<>();
    private volatile int status = 200;
    private volatile String reply = "{}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " " + body + " token="
                    + exchange.getRequestHeaders().getFirst(ApiClient.TOKEN_HEADER));
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new ApiClient(server.getAddress().getPort(), "tok");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void generatePostsTheKindWithTheSessionToken() throws Exception {
        reply = "{\"id\":\"e1\",\"kind\":\"subjective\",\"statement\":\"Explique\",\"options\":[]}";
        ExerciseDto exercise = client.generateExercise(ExerciseDtos.SUBJECTIVE);
        assertEquals("e1", exercise.id());
        assertEquals("POST /exercises/generate {\"kind\":\"subjective\"} token=tok", seen.get(0));
    }

    @Test
    void answerSendsOnlyTheFieldsThatWereGiven() throws Exception {
        reply = "{\"exercise_id\":\"e1\",\"kind\":\"objective\",\"correct\":true,\"selected\":\"b\"}";
        ResultDto result = client.answerExercise("e1", "b", null);
        assertTrue(result.correct());
        assertEquals("POST /exercises/e1/answer {\"option\":\"b\"} token=tok", seen.get(0));

        reply = "{\"exercise_id\":\"e2\",\"kind\":\"subjective\",\"score\":7.0}";
        client.answerExercise("e2", null, "Minha resposta");
        assertEquals("POST /exercises/e2/answer {\"text\":\"Minha resposta\"} token=tok", seen.get(1));
    }

    @Test
    void backendErrorsBecomeApiExceptionsWithTheirKind() {
        status = 502;
        reply = "{\"detail\":{\"kind\":\"overloaded\",\"message\":\"O Gemini está sobrecarregado\"}}";
        ApiException error = assertThrows(ApiException.class, () -> client.generateExercise("objective"));
        assertEquals("overloaded", error.kind());
        assertEquals("O Gemini está sobrecarregado", error.getMessage());

        status = 409;
        reply = "{\"detail\":{\"kind\":\"already_answered\",\"message\":\"Esta questão já foi respondida.\"}}";
        assertEquals("already_answered",
                assertThrows(ApiException.class, () -> client.answerExercise("e1", "a", null)).kind());
    }
}
