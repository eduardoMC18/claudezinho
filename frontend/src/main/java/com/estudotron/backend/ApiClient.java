package com.estudotron.backend;

import com.estudotron.backend.ChatDtos.AiSettingsDto;
import com.estudotron.backend.ChatDtos.ChatMessageDto;
import com.estudotron.backend.ChatDtos.ConnectionTestDto;
import com.estudotron.backend.ChatDtos.ConversationDto;
import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.backend.ExerciseDtos.ResultDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Cliente REST do backend local. Todas as chamadas levam o token de sessão. */
public final class ApiClient implements BackendServices {

    public static final String TOKEN_HEADER = "X-Estudotron-Token";
    /** Depois de pedir para parar, espera a confirmação do backend por este tempo antes de fechar a conexão. */
    private static final long CANCEL_GRACE_MS = 3000;

    /** Erro devolvido pelo backend, com a categoria (kind) para a interface tratar. */
    public static final class ApiException extends IOException {
        private final String kind;

        public ApiException(String kind, String message) {
            super(message);
            this.kind = kind;
        }

        public String kind() {
            return kind;
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1) // evita o "Upgrade: h2c" que o uvicorn recusa
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ObjectMapper json = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final Executor background = runnable -> {
        Thread t = new Thread(runnable, "api-background");
        t.setDaemon(true);
        t.start();
    };
    private final String baseUrl;
    private final String token;

    public ApiClient(int port, String token) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.token = token;
    }

    /** true se o backend respondeu 200 em /health. Nunca lança por falha de conexão. */
    public boolean health() {
        try {
            HttpResponse<String> resp = http.send(request("/health").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---------------------------------------------------------------- workspace

    @Override
    public WorkspaceDto getWorkspace() throws IOException, InterruptedException {
        return send(request("/workspace").GET().build(), 200, WorkspaceDto.class);
    }

    @Override
    public DocumentDto setSlot(String slot, Path pdf) throws IOException, InterruptedException {
        String body = json.writeValueAsString(Map.of("path", pdf.toAbsolutePath().toString()));
        HttpRequest req = request("/workspace/slots/" + enc(slot))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return send(req, 200, DocumentDto.class);
    }

    @Override
    public void clearSlot(String slot) throws IOException, InterruptedException {
        send(request("/workspace/slots/" + enc(slot)).DELETE().build(), 204, null);
    }

    @Override
    public DocumentDto getDocument(String docId) throws IOException, InterruptedException {
        return send(request("/documents/" + enc(docId)).GET().build(), 200, DocumentDto.class);
    }

    @Override
    public String viewerUrl(String docId) {
        return baseUrl + "/viewer/index.html?doc=" + enc(docId) + "&token=" + enc(token);
    }

    // ---------------------------------------------------------------- configurações de IA

    @Override
    public AiSettingsDto getSettings() throws IOException, InterruptedException {
        return send(request("/settings/ai").GET().build(), 200, AiSettingsDto.class);
    }

    @Override
    public AiSettingsDto setPreferences(String defaultProvider, Map<String, String> modelByProvider)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (defaultProvider != null) {
            body.put("default_provider", defaultProvider);
        }
        if (modelByProvider != null) {
            body.put("models", modelByProvider);
        }
        return send(jsonRequest("/settings/ai", body).build("PUT"), 200, AiSettingsDto.class);
    }

    @Override
    public void saveKey(String providerId, char[] apiKey) throws IOException, InterruptedException {
        HttpRequest req = jsonRequest("/settings/ai/providers/" + enc(providerId) + "/key",
                Map.of("api_key", new String(apiKey))).build("PUT");
        send(req, 204, null);
    }

    @Override
    public void removeKey(String providerId) throws IOException, InterruptedException {
        send(request("/settings/ai/providers/" + enc(providerId) + "/key").DELETE().build(), 204, null);
    }

    @Override
    public ConnectionTestDto testConnection(String providerId, String model, char[] typedKey)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (model != null) {
            body.put("model", model);
        }
        if (typedKey != null && typedKey.length > 0) {
            body.put("api_key", new String(typedKey));
        }
        // o teste faz uma chamada real à IA: pode demorar mais que as demais
        HttpRequest req = jsonRequest("/settings/ai/providers/" + enc(providerId) + "/test", body)
                .timeout(Duration.ofSeconds(90)).build("POST");
        return send(req, 200, ConnectionTestDto.class);
    }

    // ---------------------------------------------------------------- chat

    @Override
    public ConversationDto currentConversation() throws IOException, InterruptedException {
        return send(request("/conversations/current").GET().build(), 200, ConversationDto.class);
    }

    @Override
    public ConversationDto newConversation() throws IOException, InterruptedException {
        HttpRequest req = request("/conversations").POST(HttpRequest.BodyPublishers.noBody()).build();
        return send(req, 201, ConversationDto.class);
    }

    @Override
    public ChatCall stream(String content, String conversationId, String provider, String model,
                           ChatListener listener) {
        StreamCall call = new StreamCall();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        if (conversationId != null) {
            body.put("conversation_id", conversationId);
        }
        if (provider != null) {
            body.put("provider", provider);
        }
        if (model != null) {
            body.put("model", model);
        }
        background.execute(() -> runStream(call, body, listener));
        return call;
    }

    private final class StreamCall implements ChatCall {
        volatile boolean cancelled;
        volatile boolean finished;
        volatile String assistantId;
        volatile InputStream body;

        @Override
        public void cancel() {
            cancelled = true;
            requestServerCancel();
            // Se o backend não confirmar a tempo, fecha a conexão (ele então marca como cancelada sozinho).
            CompletableFuture.runAsync(() -> {
                if (!finished && body != null) {
                    try {
                        body.close();
                    } catch (IOException ignored) {
                        // já estava fechado
                    }
                }
            }, CompletableFuture.delayedExecutor(CANCEL_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
        }

        void requestServerCancel() {
            String id = assistantId;
            if (id == null) {
                return; // ainda sem id: será pedido assim que o evento "meta" chegar
            }
            background.execute(() -> {
                try {
                    http.send(request("/chat/messages/" + enc(id) + "/cancel")
                            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
                } catch (IOException e) {
                    // sem resposta do backend: o fechamento da conexão cuida do resto
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }

    private void runStream(StreamCall call, Map<String, Object> payload, ChatListener listener) {
        try {
            HttpRequest req = jsonRequest("/chat/stream", payload).header("Accept", "text/event-stream").build("POST");
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String body = new String(resp.body().readAllBytes(), StandardCharsets.UTF_8);
                ApiException error = toException(resp.statusCode(), body);
                call.finished = true;
                listener.onError(error.kind(), error.getMessage(), null);
                return;
            }
            call.body = resp.body();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(call.body, StandardCharsets.UTF_8))) {
                SseParser.parse(reader, (event, data) -> dispatch(call, event, data, listener));
            }
            if (!call.finished) { // o fluxo acabou sem "done"/"error"
                call.finished = true;
                if (call.cancelled) {
                    listener.onError("cancelled", "Resposta interrompida.", null);
                } else {
                    listener.onError("network", "A conexão com o backend foi interrompida.", null);
                }
            }
        } catch (IOException e) {
            if (!call.finished) {
                call.finished = true;
                if (call.cancelled) {
                    listener.onError("cancelled", "Resposta interrompida.", null);
                } else {
                    listener.onError("network", "Não foi possível falar com o backend: " + e.getMessage(), null);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void dispatch(StreamCall call, String event, String data, ChatListener listener) {
        try {
            JsonNode node = json.readTree(data);
            switch (event) {
                case "meta" -> {
                    call.assistantId = node.get("assistant_message_id").asText();
                    ChatMessageDto user = json.treeToValue(node.get("user_message"), ChatMessageDto.class);
                    listener.onMeta(node.get("conversation_id").asText(), user, call.assistantId);
                    if (call.cancelled) {
                        call.requestServerCancel();
                    }
                }
                case "delta" -> listener.onDelta(node.get("text").asText());
                case "done" -> {
                    call.finished = true;
                    listener.onDone(json.treeToValue(node.get("message"), ChatMessageDto.class));
                }
                case "error" -> {
                    call.finished = true;
                    JsonNode partial = node.get("assistant_message");
                    ChatMessageDto message = partial == null || partial.isNull()
                            ? null : json.treeToValue(partial, ChatMessageDto.class);
                    listener.onError(node.path("kind").asText("unknown"), node.path("message").asText(""), message);
                }
                default -> { }
            }
        } catch (IOException e) {
            // evento malformado: ignora (o fluxo seguinte continua válido)
        }
    }

    // ---------------------------------------------------------------- exercícios

    /** A IA pode levar dezenas de segundos (o backend ainda repete chamadas quando o Gemini está sobrecarregado). */
    private static final Duration EXERCISE_TIMEOUT = Duration.ofSeconds(170);

    @Override
    public ExerciseDto generateExercise(String kind) throws IOException, InterruptedException {
        HttpRequest req = jsonRequest("/exercises/generate", Map.of("kind", kind)).timeout(EXERCISE_TIMEOUT).build();
        return send(req, 200, ExerciseDto.class);
    }

    @Override
    public ResultDto answerExercise(String exerciseId, String option, String text)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        if (option != null) {
            body.put("option", option);
        }
        if (text != null) {
            body.put("text", text);
        }
        HttpRequest req = jsonRequest("/exercises/" + enc(exerciseId) + "/answer", body)
                .timeout(EXERCISE_TIMEOUT).build();
        return send(req, 200, ResultDto.class);
    }

    // ---------------------------------------------------------------- infraestrutura HTTP

    /** Monta requisições com corpo JSON (token já incluído). */
    private final class JsonRequest {
        private final HttpRequest.Builder builder;
        private final String body;

        JsonRequest(HttpRequest.Builder builder, String body) {
            this.builder = builder;
            this.body = body;
        }

        JsonRequest header(String name, String value) {
            builder.header(name, value);
            return this;
        }

        JsonRequest timeout(Duration timeout) {
            builder.timeout(timeout);
            return this;
        }

        HttpRequest build(String method) {
            return builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        }

        HttpRequest build() {
            return build("POST");
        }
    }

    private JsonRequest jsonRequest(String path, Object body) throws IOException {
        return new JsonRequest(request(path), json.writeValueAsString(body));
    }

    private <T> T send(HttpRequest req, int expectedStatus, Class<T> type) throws IOException, InterruptedException {
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() != expectedStatus) {
            throw toException(resp.statusCode(), resp.body());
        }
        return type == null ? null : json.readValue(resp.body(), type);
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30))
                .header(TOKEN_HEADER, token);
    }

    /** Lê o erro do backend: {"detail": "texto"} ou {"detail": {"kind": ..., "message": ...}}. */
    private ApiException toException(int status, String body) {
        try {
            JsonNode detail = json.readTree(body).get("detail");
            if (detail != null && detail.isTextual()) {
                return new ApiException("http_" + status, detail.asText());
            }
            if (detail != null && detail.isObject()) {
                return new ApiException(detail.path("kind").asText("http_" + status),
                        detail.path("message").asText("Erro " + status));
            }
        } catch (IOException ignored) {
            // corpo não era JSON: cai na mensagem genérica
        }
        return new ApiException("http_" + status, "Backend respondeu HTTP " + status);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
