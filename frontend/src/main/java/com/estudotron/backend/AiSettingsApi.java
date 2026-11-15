package com.estudotron.backend;

import com.estudotron.backend.ChatDtos.AiSettingsDto;
import com.estudotron.backend.ChatDtos.ConnectionTestDto;

import java.io.IOException;
import java.util.Map;

/**
 * Configurações de IA. A chave de API só trafega da tela para o backend (que a guarda no cofre
 * do sistema); o backend nunca a devolve.
 */
public interface AiSettingsApi {

    AiSettingsDto getSettings() throws IOException, InterruptedException;

    /** Qualquer argumento null é ignorado. */
    AiSettingsDto setPreferences(String defaultProvider, Map<String, String> modelByProvider)
            throws IOException, InterruptedException;

    void saveKey(String providerId, char[] apiKey) throws IOException, InterruptedException;

    void removeKey(String providerId) throws IOException, InterruptedException;

    /** Testa com a chave informada ({@code typedKey}) ou, se null/vazia, com a chave salva. */
    ConnectionTestDto testConnection(String providerId, String model, char[] typedKey)
            throws IOException, InterruptedException;
}
