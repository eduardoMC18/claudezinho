package com.estudotron.backend;

import com.estudotron.backend.ExerciseDtos.ExerciseDto;
import com.estudotron.backend.ExerciseDtos.ResultDto;

import java.io.IOException;

/** Geração e correção de exercícios no backend local (chamadas demoradas: a IA responde em segundos). */
public interface ExerciseApi {

    /** Pede uma questão ({@link ExerciseDtos#OBJECTIVE} ou {@link ExerciseDtos#SUBJECTIVE}). */
    ExerciseDto generateExercise(String kind) throws IOException, InterruptedException;

    /** Envia a resposta: {@code option} (objetiva) ou {@code text} (discursiva). */
    ResultDto answerExercise(String exerciseId, String option, String text) throws IOException, InterruptedException;
}
