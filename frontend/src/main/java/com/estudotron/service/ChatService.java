package com.estudotron.service;

import com.estudotron.backend.ChatApi;
import com.estudotron.backend.ChatApi.ChatCall;
import com.estudotron.backend.ChatApi.ChatListener;
import com.estudotron.backend.ChatDtos.ChatMessageDto;
import com.estudotron.backend.ChatDtos.ConversationDto;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Controla a conversa atual: envia perguntas, recebe a resposta em fluxo e permite interrompê-la.
 * Todos os retornos para a interface chegam pelo executor {@code ui} (a thread do JavaFX no app),
 * então a tela nunca fica bloqueada esperando a IA.
 */
public final class ChatService {

    /** Eventos de uma resposta, já na thread da interface. */
    public interface Handler {
        void onStarted(String assistantMessageId, ChatMessageDto userMessage);

        void onDelta(String text);

        void onDone(ChatMessageDto assistantMessage);

        /** {@code kind == "cancelled"}: interrupção pedida pelo usuário (não é uma falha). */
        void onError(String kind, String message, ChatMessageDto partialAssistantMessage);
    }

    private final ChatApi api;
    private final Executor io;
    private final Executor ui;

    private String conversationId;
    private ChatCall current;
    private boolean busy;

    public ChatService(ChatApi api, Executor io, Executor ui) {
        this.api = api;
        this.io = io;
        this.ui = ui;
    }

    /** Só deve ser chamado na thread da interface. */
    public boolean isBusy() {
        return busy;
    }

    public String conversationId() {
        return conversationId;
    }

    /** Carrega a conversa mais recente (histórico salvo) ao abrir o programa. */
    public void loadCurrent(Consumer<ConversationDto> onLoaded, Consumer<String> onError) {
        io.execute(() -> {
            try {
                ConversationDto conversation = api.currentConversation();
                ui.execute(() -> {
                    conversationId = conversation.id();
                    onLoaded.accept(conversation);
                });
            } catch (IOException e) {
                ui.execute(() -> onError.accept("Não foi possível carregar o histórico: " + e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Começa uma conversa nova (a anterior continua salva no histórico). */
    public void startNewConversation(Consumer<ConversationDto> onCreated, Consumer<String> onError) {
        cancel();
        io.execute(() -> {
            try {
                ConversationDto conversation = api.newConversation();
                ui.execute(() -> {
                    conversationId = conversation.id();
                    onCreated.accept(conversation);
                });
            } catch (IOException e) {
                ui.execute(() -> onError.accept("Não foi possível iniciar uma nova conversa: " + e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Envia a pergunta. Ignorado se já houver uma resposta em andamento. */
    public void send(String text, String provider, String model, Handler handler) {
        if (busy) {
            return;
        }
        busy = true;
        ChatCall call = api.stream(text, conversationId, provider, model, new ChatListener() {
            @Override
            public void onMeta(String convId, ChatMessageDto userMessage, String assistantMessageId) {
                ui.execute(() -> {
                    conversationId = convId;
                    handler.onStarted(assistantMessageId, userMessage);
                });
            }

            @Override
            public void onDelta(String delta) {
                ui.execute(() -> handler.onDelta(delta));
            }

            @Override
            public void onDone(ChatMessageDto assistantMessage) {
                ui.execute(() -> {
                    finish();
                    handler.onDone(assistantMessage);
                });
            }

            @Override
            public void onError(String kind, String message, ChatMessageDto partial) {
                ui.execute(() -> {
                    finish();
                    handler.onError(kind, message, partial);
                });
            }
        });
        if (busy) { // com um executor síncrono a resposta pode já ter terminado
            current = call;
        }
    }

    /** Interrompe a resposta em andamento (o texto já recebido é mantido). */
    public void cancel() {
        ChatCall call = current;
        if (busy && call != null) {
            call.cancel();
        }
    }

    private void finish() {
        busy = false;
        current = null;
    }
}
