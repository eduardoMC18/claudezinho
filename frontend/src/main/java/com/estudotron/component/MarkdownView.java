package com.estudotron.component;

import com.estudotron.markdown.Block;
import com.estudotron.markdown.Block.Span;
import com.estudotron.markdown.MarkdownParser;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Mostra Markdown com controles nativos do JavaFX (sem WebView): títulos, parágrafos, listas,
 * tabelas, blocos de código com botão de copiar, e links de citação clicáveis.
 */
public final class MarkdownView extends VBox {

    private BiConsumer<String, Integer> onReference = (slot, page) -> { };

    public MarkdownView() {
        getStyleClass().add("md-root");
        setSpacing(8);
        setMinWidth(0);
    }

    /** Chamado ao clicar numa citação: (slot "A"/"B", página). */
    public void setOnReference(BiConsumer<String, Integer> handler) {
        this.onReference = handler;
    }

    public void setMarkdown(String markdown) {
        List<Node> nodes = new ArrayList<>();
        for (Block block : MarkdownParser.parse(markdown)) {
            nodes.add(render(block));
        }
        getChildren().setAll(nodes);
    }

    // ----------------------------------------------------------------- blocos

    private Node render(Block block) {
        return switch (block) {
            case Block.Paragraph p -> flow(p.spans(), "md-paragraph");
            case Block.Heading h -> flow(h.spans(), "md-heading", "md-h" + Math.min(h.level(), 4));
            case Block.Code c -> codeBlock(c);
            case Block.ListBlock l -> list(l);
            case Block.Quote q -> quote(q);
            case Block.Table t -> table(t);
            case Block.Rule r -> {
                Region rule = new Region();
                rule.getStyleClass().add("md-rule");
                yield rule;
            }
        };
    }

    private TextFlow flow(List<Span> spans, String... styleClasses) {
        TextFlow flow = new TextFlow();
        flow.getStyleClass().addAll(styleClasses);
        flow.setLineSpacing(2);
        flow.setMinWidth(0);
        for (Span span : spans) {
            flow.getChildren().add(text(span));
        }
        return flow;
    }

    private Text text(Span span) {
        Text node = new Text(span.text());
        node.getStyleClass().add("md-text");
        if (span.bold()) {
            node.getStyleClass().add("md-bold");
        }
        if (span.italic()) {
            node.getStyleClass().add("md-italic");
        }
        if (span.code()) {
            node.getStyleClass().add("md-inline-code");
        }
        if (span.isReference()) {
            node.getStyleClass().add("md-ref");
            node.setCursor(Cursor.HAND);
            String[] parts = span.href().split(":");
            if (parts.length == 3) {
                node.setOnMouseClicked(e -> onReference.accept(parts[1], Integer.parseInt(parts[2])));
            }
        } else if (span.isLink()) {
            node.getStyleClass().add("md-link"); // links externos: só aparência (não abrimos o navegador)
        }
        return node;
    }

    private Node codeBlock(Block.Code code) {
        Label language = new Label(code.language().isBlank() ? "código" : code.language());
        language.getStyleClass().add("md-code-lang");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button copy = new Button();
        copy.getStyleClass().addAll("icon-button", "md-copy");
        Icons.set(copy, Icons.Icon.COPY, 13);
        copy.setTooltip(new Tooltip("Copiar código"));
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(code.text());
            Clipboard.getSystemClipboard().setContent(content);
        });
        HBox header = new HBox(language, spacer, copy);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("md-code-header");

        Text body = new Text(code.text());
        body.getStyleClass().add("md-code-text");
        TextFlow bodyFlow = new TextFlow(body);
        bodyFlow.getStyleClass().add("md-code-body");
        bodyFlow.setMinWidth(0);

        VBox box = new VBox(header, bodyFlow);
        box.getStyleClass().add("md-code-block");
        return box;
    }

    private Node list(Block.ListBlock list) {
        VBox box = new VBox(3);
        for (Block.ListItem item : list.items()) {
            Label marker = new Label(item.marker());
            marker.getStyleClass().add("md-list-marker");
            marker.setMinWidth(Region.USE_PREF_SIZE);
            TextFlow content = flow(item.spans(), "md-paragraph");
            HBox.setHgrow(content, Priority.ALWAYS);
            HBox row = new HBox(6, marker, content);
            row.setAlignment(Pos.TOP_LEFT);
            row.setPadding(new Insets(0, 0, 0, item.level() * 18));
            box.getChildren().add(row);
        }
        return box;
    }

    private Node quote(Block.Quote quote) {
        Region bar = new Region();
        bar.getStyleClass().add("md-quote-bar");
        bar.setMinWidth(3);
        bar.setPrefWidth(3);
        TextFlow content = flow(quote.spans(), "md-paragraph", "md-quote-text");
        HBox.setHgrow(content, Priority.ALWAYS);
        HBox box = new HBox(10, bar, content);
        box.setFillHeight(true);
        return box;
    }

    private Node table(Block.Table table) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("md-table");
        int columns = table.header().size();
        for (int c = 0; c < columns; c++) {
            ColumnConstraints constraints = new ColumnConstraints();
            constraints.setHgrow(Priority.ALWAYS);
            constraints.setFillWidth(true);
            constraints.setPercentWidth(100.0 / columns);
            grid.getColumnConstraints().add(constraints);
        }
        for (int c = 0; c < columns; c++) {
            grid.add(cell(table.header().get(c), true), c, 0);
        }
        for (int r = 0; r < table.rows().size(); r++) {
            List<List<Span>> row = table.rows().get(r);
            for (int c = 0; c < columns; c++) {
                grid.add(cell(c < row.size() ? row.get(c) : List.of(), false), c, r + 1);
            }
        }
        return grid;
    }

    private Node cell(List<Span> spans, boolean header) {
        TextFlow flow = flow(spans, "md-paragraph");
        StackPane cell = new StackPane(flow);
        cell.setAlignment(Pos.CENTER_LEFT);
        cell.getStyleClass().addAll("md-cell", header ? "md-cell-header" : "md-cell-body");
        if (header) {
            flow.getChildren().forEach(n -> n.getStyleClass().add("md-bold"));
        }
        return cell;
    }
}
