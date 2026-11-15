package com.estudotron.component;

import com.estudotron.backend.ExerciseDtos.ResultDto;
import com.estudotron.service.ExerciseService.Failure;
import javafx.scene.Node;

/**
 * Tela de uma questão (enunciado + resposta + correção). O controlador do fluxo só chama estes quatro
 * métodos conforme o estado: enviando, corrigido ou falhou (e então o usuário pode tentar enviar de novo).
 */
public interface ExercisePane {

    Node node();

    /** A resposta foi enviada e a correção está em andamento: trava a entrada e mostra o carregamento. */
    void showSubmitting();

    /** A correção chegou. */
    void showResult(ResultDto result);

    /** O envio/correção falhou: mostra o motivo e libera o envio de novo. */
    void showFailure(Failure failure);
}
