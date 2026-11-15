package com.estudotron.markdown;

import com.estudotron.markdown.Block.Span;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Interpretador de Markdown para as respostas do chat: títulos, parágrafos, listas, citações,
 * tabelas, blocos de código, negrito, itálico, código em linha e links. Tolerante a texto
 * incompleto (a resposta chega aos poucos): bloco de código sem fechamento continua sendo código.
 */
public final class MarkdownParser {

    private static final Pattern FENCE = Pattern.compile("^\\s*(```|~~~)\\s*([\\w+#.-]*)\\s*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern LIST_ITEM = Pattern.compile("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern TABLE_SEPARATOR =
            Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");

    private MarkdownParser() {}

    public static List<Block> parse(String markdown) {
        List<Block> blocks = new ArrayList<>();
        if (markdown == null || markdown.isBlank()) {
            return blocks;
        }
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank()) {
                i++;
                continue;
            }
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                i = parseCode(lines, i, fence.group(1), fence.group(2), blocks);
                continue;
            }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                blocks.add(new Block.Heading(heading.group(1).length(), parseInline(heading.group(2))));
                i++;
                continue;
            }
            if (RULE.matcher(line).matches()) {
                blocks.add(new Block.Rule());
                i++;
                continue;
            }
            if (isTableStart(lines, i)) {
                i = parseTable(lines, i, blocks);
                continue;
            }
            if (QUOTE.matcher(line).matches()) {
                i = parseQuote(lines, i, blocks);
                continue;
            }
            if (LIST_ITEM.matcher(line).matches()) {
                i = parseList(lines, i, blocks);
                continue;
            }
            i = parseParagraph(lines, i, blocks);
        }
        return blocks;
    }

    // ----------------------------------------------------------------- blocos

    private static int parseCode(String[] lines, int start, String fence, String language, List<Block> out) {
        StringBuilder code = new StringBuilder();
        int i = start + 1;
        while (i < lines.length && !lines[i].trim().startsWith(fence)) {
            if (code.length() > 0) {
                code.append('\n');
            }
            code.append(lines[i]);
            i++;
        }
        out.add(new Block.Code(language, code.toString()));
        return i < lines.length ? i + 1 : i; // pula o fechamento, se existir
    }

    private static boolean isTableStart(String[] lines, int i) {
        return lines[i].contains("|") && i + 1 < lines.length && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()
                && lines[i + 1].contains("-");
    }

    private static int parseTable(String[] lines, int start, List<Block> out) {
        List<List<Span>> header = splitRow(lines[start]);
        List<List<List<Span>>> rows = new ArrayList<>();
        int i = start + 2;
        while (i < lines.length && !lines[i].isBlank() && lines[i].contains("|")) {
            rows.add(splitRow(lines[i]));
            i++;
        }
        out.add(new Block.Table(header, rows));
        return i;
    }

    private static List<List<Span>> splitRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<List<Span>> cells = new ArrayList<>();
        for (String cell : trimmed.split("(?<!\\\\)\\|", -1)) {
            cells.add(parseInline(cell.trim().replace("\\|", "|")));
        }
        return cells;
    }

    private static int parseQuote(String[] lines, int start, List<Block> out) {
        StringBuilder text = new StringBuilder();
        int i = start;
        while (i < lines.length) {
            Matcher quote = QUOTE.matcher(lines[i]);
            if (!quote.matches()) {
                break;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(quote.group(1).trim());
            i++;
        }
        out.add(new Block.Quote(parseInline(text.toString())));
        return i;
    }

    private static int parseList(String[] lines, int start, List<Block> out) {
        List<Block.ListItem> items = new ArrayList<>();
        StringBuilder current = null;
        int level = 0;
        String marker = "";
        int i = start;
        while (i < lines.length) {
            String line = lines[i];
            Matcher item = LIST_ITEM.matcher(line);
            if (item.matches()) {
                if (current != null) {
                    items.add(new Block.ListItem(level, marker, parseInline(current.toString())));
                }
                level = Math.min(3, item.group(1).replace("\t", "    ").length() / 2);
                String raw = item.group(2);
                marker = Character.isDigit(raw.charAt(0)) ? raw : "•";
                current = new StringBuilder(item.group(3).trim());
            } else if (!line.isBlank() && line.startsWith(" ") && current != null && !FENCE.matcher(line).matches()) {
                current.append(' ').append(line.trim()); // continuação do mesmo item
            } else {
                break;
            }
            i++;
        }
        if (current != null) {
            items.add(new Block.ListItem(level, marker, parseInline(current.toString())));
        }
        out.add(new Block.ListBlock(items));
        return i;
    }

    private static int parseParagraph(String[] lines, int start, List<Block> out) {
        StringBuilder text = new StringBuilder();
        int i = start;
        while (i < lines.length && !lines[i].isBlank()) {
            if (i > start && startsNewBlock(lines, i)) {
                break;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(lines[i].trim());
            i++;
        }
        out.add(new Block.Paragraph(parseInline(text.toString())));
        return i;
    }

    private static boolean startsNewBlock(String[] lines, int i) {
        String line = lines[i];
        return FENCE.matcher(line).matches() || HEADING.matcher(line).matches() || RULE.matcher(line).matches()
                || LIST_ITEM.matcher(line).matches() || QUOTE.matcher(line).matches() || isTableStart(lines, i);
    }

    // ----------------------------------------------------------------- inline

    public static List<Span> parseInline(String text) {
        List<Span> spans = new ArrayList<>();
        parseInline(text, false, false, spans);
        return merge(spans);
    }

    private static void parseInline(String text, boolean bold, boolean italic, List<Span> out) {
        StringBuilder plain = new StringBuilder();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!|~>".indexOf(text.charAt(i + 1)) >= 0) {
                plain.append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '`') {
                int end = text.indexOf('`', i + 1);
                if (end > i) {
                    flush(plain, bold, italic, out);
                    out.add(new Span(text.substring(i + 1, end), bold, italic, true, null));
                    i = end + 1;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && i + 1 < n && text.charAt(i + 1) == c) {
                int end = text.indexOf("" + c + c, i + 2);
                if (end > i + 2 && !Character.isWhitespace(text.charAt(i + 2))) {
                    flush(plain, bold, italic, out);
                    parseInline(text.substring(i + 2, end), true, italic, out);
                    i = end + 2;
                    continue;
                }
            }
            if ((c == '*' || c == '_') && canOpenItalic(text, i)) {
                int end = findItalicEnd(text, i + 1, c);
                if (end > i + 1) {
                    flush(plain, bold, italic, out);
                    parseInline(text.substring(i + 1, end), bold, true, out);
                    i = end + 1;
                    continue;
                }
            }
            if (c == '[') {
                int close = findClosingBracket(text, i);
                if (close > i && close + 1 < n && text.charAt(close + 1) == '(') {
                    int paren = text.indexOf(')', close + 2);
                    if (paren > close) {
                        flush(plain, bold, italic, out);
                        String label = text.substring(i + 1, close);
                        String href = text.substring(close + 2, paren).trim();
                        List<Span> inner = new ArrayList<>();
                        parseInline(label, bold, italic, inner);
                        for (Span s : inner) {
                            out.add(new Span(s.text(), s.bold(), s.italic(), s.code(), href));
                        }
                        i = paren + 1;
                        continue;
                    }
                }
            }
            plain.append(c);
            i++;
        }
        flush(plain, bold, italic, out);
    }

    private static boolean canOpenItalic(String text, int i) {
        if (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1))) {
            return false;
        }
        // "_" dentro de uma palavra (nome_de_arquivo) não é itálico
        return text.charAt(i) != '_' || i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1));
    }

    private static int findItalicEnd(String text, int from, char marker) {
        for (int j = from; j < text.length(); j++) {
            if (text.charAt(j) == marker && !Character.isWhitespace(text.charAt(j - 1))
                    && (marker == '*' || j + 1 >= text.length() || !Character.isLetterOrDigit(text.charAt(j + 1)))) {
                return j;
            }
        }
        return -1;
    }

    private static int findClosingBracket(String text, int open) {
        int depth = 0;
        for (int j = open; j < text.length(); j++) {
            char c = text.charAt(j);
            if (c == '[') {
                depth++;
            } else if (c == ']' && --depth == 0) {
                return j;
            }
        }
        return -1;
    }

    private static void flush(StringBuilder plain, boolean bold, boolean italic, List<Span> out) {
        if (plain.length() > 0) {
            out.add(new Span(plain.toString(), bold, italic, false, null));
            plain.setLength(0);
        }
    }

    private static List<Span> merge(List<Span> spans) {
        List<Span> merged = new ArrayList<>();
        for (Span span : spans) {
            if (!merged.isEmpty()) {
                Span last = merged.get(merged.size() - 1);
                if (last.bold() == span.bold() && last.italic() == span.italic() && last.code() == span.code()
                        && java.util.Objects.equals(last.href(), span.href())) {
                    merged.set(merged.size() - 1,
                            new Span(last.text() + span.text(), last.bold(), last.italic(), last.code(), last.href()));
                    continue;
                }
            }
            merged.add(span);
        }
        return merged;
    }
}
