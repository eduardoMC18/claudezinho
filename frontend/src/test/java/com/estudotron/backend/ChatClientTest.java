package com.estudotron.backend;

import com.estudotron.backend.ChatApi.ChatCall;
import com.estudotron.backend.ChatApi.ChatListener;
import com.estudotron.backend.ChatDtos.AiSettingsDto;
import com.estudotron.backend.ChatDtos.ChatMessageDto;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Cliente REST/SSE contra um servidor HTTP local que imita o backend. */
class ChatClientTest {

    private HttpServer server;
    private ApiClient client;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch cancelSeen = new CountDownLatch(1);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool()); // o fluxo SSE não pode travar o pedido de cancelar
        client = new ApiClient(server.getAddress().getPort(), "tok");
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String sse(String event, String data) {
        return "event: " + event + "\ndata: " + data + "\n\n";
    }

    private void streamHandler(String... chunks) {
        server.createContext("/chat/stream", exchange -> {
            requests.add("stream token=" + exchange.getRequestHeaders().getFirst("X-Estudotron-Token") + " body="
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                for (String chunk : chunks) {
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(20);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
    }

    /** Receptor que guarda os eventos em ordem. */
    private static final class Recorder implements ChatListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<ChatMessageDto> done = new AtomicReference<>();
        final AtomicReference<String> errorKind = new AtomicReference<>();
        final AtomicReference<String> errorMessage = new AtomicReference<>();

        @Override
        public void onMeta(String conversationId, ChatMessageDto userMessage, String assistantMessageId) {
            events.add("meta:" + conversationId + ":" + userMessage.content() + ":" + assistantMessageId);
        }

        @Override
        public void onDelta(String text) {
            events.add("delta:" + text);
        }

        @Override
        public void onDone(ChatMessageDto message) {
            events.add("done");
            done.set(message);
            finished.countDown();
        }

        @Override
        public void onError(String kind, String message, ChatMessageDto assistant) {
            events.add("error:" + kind);
            errorKind.set(kind);
            errorMessage.set(message);
            finished.countDown();
        }
    }

    @Test
    void sseParserHandlesMultilineDataCommentsAndTrailingEvent() throws IOException {
        List<String> seen = new ArrayList<>();
        SseParser.parse(new BufferedReader(new StringReader(
                ": comentário\nevent: a\ndata: um\ndata: dois\n\nevent: b\ndata:{\"x\":1}\n\ndata: sem nome")),
                (event, data) -> seen.add(event + "=" + data));
        assertEquals(List.of("a=um\ndois", "b={\"x\":1}", "message=sem nome"), seen);
    }

    @Test
    void streamsEventsInOrderAndMapsSnakeCaseDtos() throws Exception {
        streamHandler(
                sse("meta", "{\"conversation_id\":\"c1\",\"user_message\":{\"id\":\"u1\",\"role\":\"user\","
                        + "\"content\":\"Olá?\",\"status\":\"complete\",\"references\":[]},\"assistant_message_id\":\"a1\"}"),
                sse("delta", "{\"text\":\"Oi \"}"),
                sse("delta", "{\"text\":\"mundo\"}"),
                sse("done", "{\"message\":{\"id\":\"a1\",\"role\":\"assistant\",\"content\":\"Oi mundo\","
                        + "\"provider\":\"anthropic\",\"model\":\"claude-opus-5-5\",\"status\":\"complete\","
                        + "\"created_at\":\"2026-11-13T10:00:00.000+00:00\","
                        + "\"references\":[{\"slot\":\"A\",\"page\":8,\"file_name\":\"bio.pdf\",\"document_id\":\"d1\"}],"
                        + "\"usage\":{\"input_tokens\":321,\"output_tokens\":77,\"cost_usd\":0.0028}}}"));

        Recorder recorder = new Recorder();
        client.stream("Olá?", "c1", "anthropic", "claude-opus-5-5", recorder);
        assertTrue(recorder.finished.await(5, TimeUnit.SECONDS));

        assertEquals(List.of("meta:c1:Olá?:a1", "delta:Oi ", "delta:mundo", "done"), recorder.events);
        ChatMessageDto done = recorder.done.get();
        assertEquals("claude-opus-5-5", done.model());
        assertEquals("bio.pdf", done.references().get(0).fileName());
        assertEquals(8, done.references().get(0).page());
        assertEquals(321, done.usage().inputTokens());
        assertEquals(0.0028, done.usage().costUsd(), 1e-9);

        String request = requests.get(0);
        assertTrue(request.startsWith("stream token=tok"));
        assertTrue(request.contains("\"content\":\"Olá?\"") && request.contains("\"conversation_id\":\"c1\"")
                && request.contains("\"provider\":\"anthropic\""));
    }

    @Test
    void preflightErrorsCarryKindAndMessage() throws Exception {
        server.createContext("/chat/stream", exchange -> {
            byte[] body = "{\"detail\":{\"kind\":\"no_key\",\"message\":\"Nenhuma chave configurada\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        Recorder recorder = new Recorder();
        client.stream("x", null, null, null, recorder);
        assertTrue(recorder.finished.await(5, TimeUnit.SECONDS));
        assertEquals("no_key", recorder.errorKind.get());
        assertEquals("Nenhuma chave configurada", recorder.errorMessage.get());
        assertEquals(List.of("error:no_key"), recorder.events);
    }

    @Test
    void errorEventDuringStreamBringsPartialMessage() throws Exception {
        streamHandler(
                sse("meta", "{\"conversation_id\":\"c\",\"user_message\":{\"id\":\"u\",\"role\":\"user\",\"content\":\"q\"},"
                        + "\"assistant_message_id\":\"a\"}"),
                sse("delta", "{\"text\":\"parcial\"}"),
                sse("error", "{\"kind\":\"rate_limit\",\"message\":\"Limite atingido\",\"assistant_message\":"
                        + "{\"id\":\"a\",\"role\":\"assistant\",\"content\":\"parcial\",\"status\":\"error\","
                        + "\"error\":\"Limite atingido\",\"error_kind\":\"rate_limit\"}}"));
        Recorder recorder = new Recorder();
        client.stream("q", null, null, null, recorder);
        assertTrue(recorder.finished.await(5, TimeUnit.SECONDS));
        assertEquals("rate_limit", recorder.errorKind.get());
        assertEquals(List.of("meta:c:q:a", "delta:parcial", "error:rate_limit"), recorder.events);
    }

    @Test
    void cancelAsksTheBackendToStopAndKeepsReadingUntilItConfirms() throws Exception {
        server.createContext("/chat/messages/a1/cancel", exchange -> {
            requests.add("cancel");
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            cancelSeen.countDown();
        });
        server.createContext("/chat/stream", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(sse("meta", "{\"conversation_id\":\"c\",\"user_message\":{\"id\":\"u\",\"role\":\"user\","
                        + "\"content\":\"q\"},\"assistant_message_id\":\"a1\"}").getBytes(StandardCharsets.UTF_8));
                out.flush();
                cancelSeen.await(5, TimeUnit.SECONDS);   // só termina depois de receber o pedido de parar
                out.write(sse("done", "{\"message\":{\"id\":\"a1\",\"role\":\"assistant\",\"content\":\"meio\","
                        + "\"status\":\"cancelled\"}}").getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();

        Recorder recorder = new Recorder();
        ChatCall call = client.stream("q", null, null, null, recorder);
        long deadline = System.currentTimeMillis() + 5000;
        while (recorder.events.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        call.cancel();

        assertTrue(recorder.finished.await(5, TimeUnit.SECONDS));
        assertTrue(requests.contains("cancel"));
        assertEquals("cancelled", recorder.done.get().status());
    }

    @Test
    void settingsRoundTripNeverExposesKeys() throws Exception {
        String overview = "{\"default_provider\":\"anthropic\",\"providers\":[{\"id\":\"anthropic\","
                + "\"display_name\":\"Claude (Anthropic)\",\"key_hint\":\"sk-ant-…\",\"has_key\":true,"
                + "\"selected_model\":\"claude-opus-5-5\",\"models\":[{\"id\":\"claude-opus-5-5\",\"label\":\"Claude Opus 5.5\","
                + "\"description\":\"d\",\"context_window\":1000000,\"price_input_per_mtok\":4.0,\"price_output_per_mtok\":20.0}]}]}";
        server.createContext("/settings/ai/providers/anthropic/key", exchange -> {
            requests.add(exchange.getRequestMethod() + " key body="
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/settings/ai", exchange -> {
            byte[] body = overview.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        AiSettingsDto settings = client.getSettings();
        assertTrue(settings.provider("anthropic").hasKey());
        assertEquals("Claude Opus 5.5", settings.provider("anthropic").selectedModelInfo().label());

        client.saveKey("anthropic", "sk-ant-teste".toCharArray());
        assertTrue(requests.get(0).contains("\"api_key\":\"sk-ant-teste\""));
        assertFalse(settings.toString().contains("sk-ant-teste"));
    }

    @Test
    void streamReportsNetworkErrorWhenServerIsGone() throws Exception {
        server.start();
        int port = server.getAddress().getPort();
        server.stop(0);
        Recorder recorder = new Recorder();
        new ApiClient(port, "tok").stream("q", null, null, null, recorder);
        assertTrue(recorder.finished.await(5, TimeUnit.SECONDS));
        assertEquals("network", recorder.errorKind.get());
        // consome o reader só para garantir que nada ficou preso
        new BufferedReader(new InputStreamReader(new java.io.ByteArrayInputStream(new byte[0]))).close();
    }
}
