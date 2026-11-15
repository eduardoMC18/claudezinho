package com.estudotron.markdown;

import com.estudotron.markdown.Block.Span;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownParserTest {

    private static String plain(List<Span> spans) {
        StringBuilder sb = new StringBuilder();
        spans.forEach(s -> sb.append(s.text()));
        return sb.toString();
    }

    @Test
    void headingsAndParagraphs() {
        List<Block> blocks = MarkdownParser.parse("## Título\n\nPrimeira linha\nsegunda linha\n\nOutro parágrafo");
        assertEquals(3, blocks.size());
        Block.Heading heading = assertInstanceOf(Block.Heading.class, blocks.get(0));
        assertEquals(2, heading.level());
        assertEquals("Título", plain(heading.spans()));
        assertEquals("Primeira linha segunda linha", plain(((Block.Paragraph) blocks.get(1)).spans()));
    }

    @Test
    void inlineStyles() {
        List<Span> spans = MarkdownParser.parseInline("texto **negrito** e *itálico* e `código` fim");
        assertEquals(7, spans.size());
        assertTrue(spans.get(1).bold() && spans.get(1).text().equals("negrito"));
        assertTrue(spans.get(3).italic() && spans.get(3).text().equals("itálico"));
        assertTrue(spans.get(5).code() && spans.get(5).text().equals("código"));
        assertEquals("texto negrito e itálico e código fim", plain(spans));
    }

    @Test
    void underscoresInsideWordsAreNotItalic() {
        List<Span> spans = MarkdownParser.parseInline("veja meu_arquivo_final.pdf agora");
        assertEquals(1, spans.size());
        assertEquals("veja meu_arquivo_final.pdf agora", spans.get(0).text());
    }

    @Test
    void citationLinksBecomeReferenceSpans() {
        List<Span> spans = MarkdownParser.parseInline("Está na página 8 [(bio_1.pdf, p. 8)](ref:A:8).");
        Span link = spans.stream().filter(Span::isLink).findFirst().orElseThrow();
        assertEquals("(bio_1.pdf, p. 8)", link.text());
        assertTrue(link.isReference());
        assertEquals("ref:A:8", link.href());
        assertEquals("Está na página 8 (bio_1.pdf, p. 8).", plain(spans));
    }

    @Test
    void escapedCharactersAreLiteral() {
        assertEquals("2 * 3 * 4", plain(MarkdownParser.parseInline("2 \\* 3 \\* 4")));
    }

    @Test
    void listsKeepOrderLevelsAndMarkers() {
        List<Block> blocks = MarkdownParser.parse("- um\n- dois\n  - sub\n\n1. primeiro\n2. segundo");
        Block.ListBlock bullets = assertInstanceOf(Block.ListBlock.class, blocks.get(0));
        assertEquals(3, bullets.items().size());
        assertEquals("•", bullets.items().get(0).marker());
        assertEquals(1, bullets.items().get(2).level());
        Block.ListBlock numbered = assertInstanceOf(Block.ListBlock.class, blocks.get(1));
        assertEquals("1.", numbered.items().get(0).marker());
        assertEquals("segundo", plain(numbered.items().get(1).spans()));
    }

    @Test
    void fencedCodeKeepsContentVerbatim() {
        List<Block> blocks = MarkdownParser.parse("Antes\n```python\nx = [1, 2]\n# não é título\n```\nDepois");
        assertEquals(3, blocks.size());
        Block.Code code = assertInstanceOf(Block.Code.class, blocks.get(1));
        assertEquals("python", code.language());
        assertEquals("x = [1, 2]\n# não é título", code.text());
    }

    @Test
    void unclosedCodeBlockWhileStreamingStaysCode() {
        List<Block> blocks = MarkdownParser.parse("```js\nlet a = 1;");
        Block.Code code = assertInstanceOf(Block.Code.class, blocks.get(0));
        assertEquals("let a = 1;", code.text());
    }

    @Test
    void tablesAreParsedIntoHeaderAndRows() {
        String md = "| Documento | Página |\n|---|---|\n| A | 8 |\n| B | 14 |\n\nDepois.";
        List<Block> blocks = MarkdownParser.parse(md);
        Block.Table table = assertInstanceOf(Block.Table.class, blocks.get(0));
        assertEquals(2, table.header().size());
        assertEquals("Documento", plain(table.header().get(0)));
        assertEquals(2, table.rows().size());
        assertEquals("14", plain(table.rows().get(1).get(1)));
        assertInstanceOf(Block.Paragraph.class, blocks.get(1));
    }

    @Test
    void quotesAndRules() {
        List<Block> blocks = MarkdownParser.parse("> citação\n> continua\n\n---\n\ntexto");
        assertEquals("citação continua", plain(assertInstanceOf(Block.Quote.class, blocks.get(0)).spans()));
        assertInstanceOf(Block.Rule.class, blocks.get(1));
        assertInstanceOf(Block.Paragraph.class, blocks.get(2));
    }

    @Test
    void emptyAndBlankInput() {
        assertTrue(MarkdownParser.parse(null).isEmpty());
        assertTrue(MarkdownParser.parse("  \n \n").isEmpty());
    }

    @Test
    void partialMarkdownNeverThrows() {
        for (String fragment : new String[]{"**negri", "[texto](ref:A", "`código", "| a | b |\n|--", "- ", "#", "> ", "[[[", "]]]((("}) {
            assertDoesNotThrow(() -> MarkdownParser.parse(fragment), fragment);
        }
    }
}
