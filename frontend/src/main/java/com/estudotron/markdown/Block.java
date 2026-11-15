package com.estudotron.markdown;

import java.util.List;

/** Blocos de um documento Markdown (o subconjunto usado nas respostas da IA). */
public sealed interface Block {

    /** Trecho de texto com estilo. {@code href} só existe em links. */
    record Span(String text, boolean bold, boolean italic, boolean code, String href) {
        public static Span plain(String text) {
            return new Span(text, false, false, false, null);
        }

        public boolean isLink() {
            return href != null;
        }

        /** Link de citação gerado pelo backend: ref:A:12. */
        public boolean isReference() {
            return href != null && href.startsWith("ref:");
        }
    }

    record Paragraph(List<Span> spans) implements Block {}

    record Heading(int level, List<Span> spans) implements Block {}

    record Code(String language, String text) implements Block {}

    record ListItem(int level, String marker, List<Span> spans) {}

    record ListBlock(List<ListItem> items) implements Block {}

    record Quote(List<Span> spans) implements Block {}

    record Table(List<List<Span>> header, List<List<List<Span>>> rows) implements Block {}

    record Rule() implements Block {}
}
