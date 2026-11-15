package com.estudotron.service;

import com.estudotron.backend.ApiClient.ApiException;
import com.estudotron.backend.ExerciseApi;
import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.backend.ExerciseDtos.ResultDto;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Chamadas de exercícios fora da thread da interface: o trabalho roda em {@code io} e o resultado
 * volta pelo executor da interface ({@code ui}), então a tela nunca trava esperando a IA.
 */
public final class ExerciseService {

    private final ExerciseApi api;
    private final Executor io;
    private final Executor ui;

    public ExerciseService(ExerciseApi api, Executor io, Executor ui) {
        this.api = api;
        this.io = io;
        this.ui = ui;
    }

    /** Falha traduzida para a interface: categoria (kind) e mensagem já em português. */
    public record Failure(String kind, String message) {
        /** Faltam chave ou provedor: a interface pode oferecer abrir as Configurações. */
        public boolean needsSettings() {
            return "no_key".equals(kind) || "auth".equals(kind);
        }

        public boolean needsDocuments() {
            return "no_documents".equals(kind) || "no_text".equals(kind);
        }

        /** Erros em que tentar de novo não adianta sem o usuário mudar algo (não há botão "tentar de novo"). */
        public boolean retryable() {
            return !needsSettings() && !needsDocuments() && !"already_answered".equals(kind)
                    && !"not_found".equals(kind);
        }
    }

    public void generate(String kind, Consumer<ExerciseDto> onSuccess, Consumer<Failure> onFailure) {
        run(() -> api.generateExercise(kind), onSuccess, onFailure);
    }

    public void answer(String exerciseId, String option, String text, Consumer<ResultDto> onSuccess,
                       Consumer<Failure> onFailure) {
        run(() -> api.answerExercise(exerciseId, option, text), onSuccess, onFailure);
    }

    private interface Call<T> {
        T call() throws IOException, InterruptedException;
    }

    private <T> void run(Call<T> call, Consumer<T> onSuccess, Consumer<Failure> onFailure) {
        io.execute(() -> {
            try {
                T result = call.call();
                ui.execute(() -> onSuccess.accept(result));
            } catch (ApiException e) {
                ui.execute(() -> onFailure.accept(new Failure(e.kind(), e.getMessage())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                ui.execute(() -> onFailure.accept(new Failure("network",
                        "Não foi possível falar com o backend: " + e.getMessage())));
            } catch (RuntimeException e) {
                ui.execute(() -> onFailure.accept(new Failure("unknown", "Erro inesperado: " + e.getMessage())));
            }
        });
    }
}
