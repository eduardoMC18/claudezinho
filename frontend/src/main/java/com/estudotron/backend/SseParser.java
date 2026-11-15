package com.estudotron.backend;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.BiConsumer;

/** Leitor mínimo de Server-Sent Events: campos "event:" e "data:", eventos separados por linha em branco. */
public final class SseParser {

    private SseParser() {}

    /** Lê até o fim do fluxo, chamando {@code handler(nomeDoEvento, dados)} para cada evento completo. */
    public static void parse(BufferedReader reader, BiConsumer<String, String> handler) throws IOException {
        String event = "message";
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() > 0) {
                    handler.accept(event, data.toString());
                }
                event = "message";
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                String value = line.substring(5);
                data.append(value.startsWith(" ") ? value.substring(1) : value);
            }
            // outras linhas (comentários ":" e "id:") são ignoradas
        }
        if (data.length() > 0) {
            handler.accept(event, data.toString());
        }
    }
}
