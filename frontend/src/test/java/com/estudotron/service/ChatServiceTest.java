package com.estudotron.service;

import com.estudotron.backend.ChatApi;
import com.estudotron.backend.ChatDtos.ChatMessageDto;
import com.estudotron.backend.ChatDtos.ConversationDto;
import com.estudotron.viewmodel.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.*;

class ChatServiceTest {

    private static final Executor DIRECT = Runnable::run;

    /** Backend de chat falso: guarda o receptor e deixa o teste disparar os eventos. */
    static final class FakeChatApi implements ChatApi {
        ChatListener listener;
        boolean cancelled;
        final List<String> sent = new ArrayList<>();

        @Override public ConversationDto currentConversation() {
            return new ConversationDto("conv-1", "", null, List.of());
        }

        @Override public ConversationDto newConversation() {
            return new ConversationDto("conv-2", "", null, List.of());
        }

        @Override public ChatCall stream(String content, String conversationId, String provider, String model,
                                         ChatListener listener) {
            sent.add(content + "|" + conversationId + "|" + provider);
            this.listener = listener;
            return () -> cancelled = true;
        }
    }

    static ChatMessageDto dto(String id, String role, String content, String status) {
        return new ChatMessageDto(id, "conv-1", role, content, "anthropic", "claude-opus-5-5", status, null, null,
                List.of(), null, "2026-11-13T10:00:00.000+00:00");
    }

    static final class Events implements ChatService.Handler {
        final List<String> log = new ArrayList<>();
        @Override public void onStarted(String id, ChatMessageDto user) { log.add("started:" + id); }
        @Override public void onDelta(String text) { log.add("delta:" + text); }
        @Override public void onDone(ChatMessageDto message) { log.add("done:" + message.content()); }
        @Override public void onError(String kind, String message, ChatMessageDto partial) {
            log.add("error:" + kind + (partial == null ? "" : ":partial"));
        }
    }

    private final FakeChatApi api = new FakeChatApi();
    private final ChatService service = new ChatService(api, DIRECT, DIRECT);

    @Test
    void sendStreamsEventsAndBecomesIdleAgain() {
        Events events = new Events();
        service.send("pergunta", "anthropic", null, events);
        assertTrue(service.isBusy());

        api.listener.onMeta("conv-1", dto("u1", "user", "pergunta", "complete"), "a1");
        api.listener.onDelta("Oi ");
        api.listener.onDelta("mundo");
        api.listener.onDone(dto("a1", "assistant", "Oi mundo", "complete"));

        assertEquals(List.of("started:a1", "delta:Oi ", "delta:mundo", "done:Oi mundo"), events.log);
        assertFalse(service.isBusy());
        assertEquals("conv-1", service.conversationId());
    }

    @Test
    void secondSendWhileBusyIsIgnoredAndConversationIdIsReused() {
        service.send("um", null, null, new Events());
        service.send("dois", null, null, new Events());
        assertEquals(1, api.sent.size());

        api.listener.onMeta("conv-9", dto("u", "user", "um", "complete"), "a");
        api.listener.onDone(dto("a", "assistant", "ok", "complete"));
        service.send("três", null, null, new Events());
        assertEquals("três|conv-9|null", api.sent.get(1));
    }

    @Test
    void cancelOnlyWorksWhileBusy() {
        service.cancel();
        assertFalse(api.cancelled);

        service.send("q", null, null, new Events());
        service.cancel();
        assertTrue(api.cancelled);
    }

    @Test
    void errorsReleaseTheServiceAndPassPartialMessages() {
        Events events = new Events();
        service.send("q", null, null, events);
        api.listener.onError("no_key", "sem chave", null);
        assertEquals(List.of("error:no_key"), events.log);
        assertFalse(service.isBusy());

        Events second = new Events();
        service.send("q2", null, null, second);
        api.listener.onMeta("c", dto("u", "user", "q2", "complete"), "a");
        api.listener.onError("rate_limit", "limite", dto("a", "assistant", "parcial", "error"));
        assertEquals(List.of("started:a", "error:rate_limit:partial"), second.log);
    }

    @Test
    void newConversationSwitchesIdAndLoadCurrentRestoresIt() {
        List<String> ids = new ArrayList<>();
        service.loadCurrent(c -> ids.add(c.id()), ids::add);
        service.startNewConversation(c -> ids.add(c.id()), ids::add);
        assertEquals(List.of("conv-1", "conv-2"), ids);
        assertEquals("conv-2", service.conversationId());
    }

    @Test
    void messageMappingBuildsUiModel() {
        ChatMessageDto dto = new ChatMessageDto("m1", "c", "assistant", "Texto", "anthropic", "claude-opus-5-5",
                "cancelled", null, null,
                List.of(new com.estudotron.backend.ChatDtos.ReferenceDto("B", 14, "plantas.pdf", "d2")),
                new com.estudotron.backend.ChatDtos.UsageDto(1000, 234, 0.0123, null, null),
                "2026-11-13T10:00:00.000+00:00");
        ChatMessage message = ChatMessage.from(dto, id -> "Claude Opus 5.5");
        assertEquals(ChatMessage.Role.ASSISTANT, message.role());
        assertEquals(ChatMessage.Status.CANCELLED, message.status());
        assertEquals("Claude Opus 5.5", message.modelLabel());
        assertEquals("plantas.pdf · p. 14", message.references().get(0).label());
        assertEquals("1,2 mil tokens · ≈ US$ 0,0123", message.usageSummary());
    }
}
