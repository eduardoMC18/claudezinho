package com.estudotron.backend;

/** Tudo o que a interface usa do backend local (permite um falso único nos testes). */
public interface BackendServices extends BackendApi, ChatApi, AiSettingsApi, ExerciseApi {}
