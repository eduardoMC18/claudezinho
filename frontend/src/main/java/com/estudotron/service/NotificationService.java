package com.estudotron.service;

import java.util.function.Consumer;

/** Canal de avisos para o usuário. A tela registra quem os exibe (toast). */
public final class NotificationService {

    public enum Kind { INFO, SUCCESS, WARNING, ERROR }

    public record Notice(String message, Kind kind) {}

    private Consumer<Notice> sink = notice -> { };

    public void setSink(Consumer<Notice> sink) {
        this.sink = sink;
    }

    public void info(String message) {
        sink.accept(new Notice(message, Kind.INFO));
    }

    public void warning(String message) {
        sink.accept(new Notice(message, Kind.WARNING));
    }

    public void error(String message) {
        sink.accept(new Notice(message, Kind.ERROR));
    }

    /** Aviso padrão para funcionalidades cujo comportamento ainda não foi conectado. */
    public void notConnected(String feature) {
        warning(feature + " ainda não está conectado — será ligado em uma próxima etapa.");
    }
}
