package com.estudotron.viewmodel;

import com.estudotron.backend.DocumentDto;

/** Texto e nível do indicador de um documento (barra superior e cabeçalho do visualizador). */
public final class DocumentText {

    public enum Level { EMPTY, WORKING, OK, WARNING, ERROR }

    public record Indicator(String text, Level level) {}

    private DocumentText() {}

    /** Estado em poucas palavras (para chips e cabeçalhos compactos). */
    public static String stateLabel(DocumentDto doc) {
        if (doc == null) {
            return "vazio";
        }
        if (DocumentDto.FILE_MISSING.equals(doc.fileState())) {
            return "arquivo ausente";
        }
        if (DocumentDto.ERROR.equals(doc.status())) {
            return "erro";
        }
        if (DocumentDto.FILE_MODIFIED.equals(doc.fileState())) {
            return "alterado no disco";
        }
        return switch (doc.status() == null ? "" : doc.status()) {
            case DocumentDto.PROCESSING -> "indexando " + doc.pagesDone() + "/" + doc.pages();
            case DocumentDto.NO_TEXT -> "sem texto pesquisável";
            case DocumentDto.READY -> "pronto";
            default -> "carregando";
        };
    }

    public static Indicator describe(DocumentDto doc) {
        if (doc == null) {
            return new Indicator("nenhum PDF", Level.EMPTY);
        }
        String base = doc.name() + " · " + doc.pages() + (doc.pages() == 1 ? " página" : " páginas");

        if (DocumentDto.FILE_MISSING.equals(doc.fileState())) {
            return new Indicator(base + " · arquivo não encontrado no disco", Level.ERROR);
        }
        if (DocumentDto.ERROR.equals(doc.status())) {
            String why = doc.error() == null || doc.error().isBlank() ? "falha na indexação" : doc.error();
            return new Indicator(base + " · erro: " + why, Level.ERROR);
        }
        if (DocumentDto.FILE_MODIFIED.equals(doc.fileState())) {
            return new Indicator(base + " · arquivo alterado desde o carregamento", Level.WARNING);
        }
        return switch (doc.status() == null ? "" : doc.status()) {
            case DocumentDto.PROCESSING ->
                    new Indicator(base + " · indexando " + doc.pagesDone() + "/" + doc.pages(), Level.WORKING);
            case DocumentDto.NO_TEXT ->
                    new Indicator(base + " · sem texto pesquisável (PDF escaneado?)", Level.WARNING);
            case DocumentDto.READY -> new Indicator(base + " · pronto", Level.OK);
            default -> new Indicator(base, Level.WORKING);
        };
    }
}
