package com.estudotron.backend;

import com.estudotron.backend.ChatDtos.ChatMessageDto;
import com.estudotron.backend.ChatDtos.ConversationDto;

import java.io.IOException;

/** Chat e histórico no backend local. */
public interface ChatApi {

    /** Receptor dos eventos da resposta. Chamado em uma thread de fundo, nunca na do JavaFX. */
    interface ChatListener {
        /** A conversa começou: a pergunta foi gravada e a resposta (vazia) já tem id. */
        void onMeta(String conversationId, ChatMessageDto userMessage, String assistantMessageId);

        void onDelta(String text);

        void onDone(ChatMessageDto assistantMessage);

        /**
         * Falha. {@code kind} é a categoria (no_key, auth, rate_limit, network...). Se a resposta já tinha
         * começado, {@code assistantMessage} traz o texto parcial salvo; antes disso é null.
         */
        void onError(String kind, String message, ChatMessageDto assistantMessage);
    }

    /** Resposta em andamento; {@link #cancel()} interrompe. */
    interface ChatCall {
        void cancel();
    }

    ConversationDto currentConversation() throws IOException, InterruptedException;

    ConversationDto newConversation() throws IOException, InterruptedException;

    /** Inicia a resposta em fluxo (SSE) em uma thread própria e devolve na hora. */
    ChatCall stream(String content, String conversationId, String provider, String model, ChatListener listener);
}
