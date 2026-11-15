from app.services.citations import CitationRewriter, allowed_citations, to_history_text
from app.services.retrieval import RetrievedChunk


def _allowed():
    chunks = [
        RetrievedChunk(1, "docA", "A", "bio [1].pdf", 8, "t", 0.0),
        RetrievedChunk(2, "docA", "A", "bio [1].pdf", 9, "t", 0.0),
        RetrievedChunk(3, "docB", "B", "plantas.pdf", 14, "t", 0.0),
    ]
    return allowed_citations(chunks)


def rewrite(text: str, piece: int = 100) -> tuple[str, CitationRewriter]:
    rewriter = CitationRewriter(_allowed())
    out = "".join(rewriter.feed(text[i:i + piece]) for i in range(0, len(text), piece)) + rewriter.finish()
    return out, rewriter


def test_valid_tag_becomes_clickable_link_with_file_name() -> None:
    out, rw = rewrite("O conceito está na página 8 [A p.8].")
    assert out == "O conceito está na página 8 [(bio (1).pdf, p. 8)](ref:A:8)."
    assert [(c.slot, c.page) for c in rw.citations] == [("A", 8)]


def test_invented_pages_are_removed_and_counted() -> None:
    out, rw = rewrite("Veja [A p.8] e também [A p.999] e [B p.1].")
    assert "999" not in out and "p. 1)" not in out
    assert [(c.slot, c.page) for c in rw.citations] == [("A", 8)]
    assert rw.invalid_count == 2


def test_removing_an_invalid_citation_leaves_clean_punctuation() -> None:
    out, rw = rewrite("Isso cita uma página inexistente [A p.999]. Fim [B p.14] e mais [A p.77].")
    assert out.startswith("Isso cita uma página inexistente. Fim ")
    assert "inexistente ." not in out
    assert rw.invalid_count == 2


def test_tags_split_across_stream_chunks_are_handled() -> None:
    text = "Primeiro [A p.8] depois [B p.14] fim."
    whole, _ = rewrite(text, piece=1000)
    for piece in (1, 2, 3, 5, 7):
        streamed, rw = rewrite(text, piece=piece)
        assert streamed == whole
        assert [(c.slot, c.page) for c in rw.citations] == [("A", 8), ("B", 14)]


def test_multiple_pages_and_ranges() -> None:
    out, rw = rewrite("[A p.8, 9] e [A p.8-9]")
    assert out.count("ref:A:8") == 2 and out.count("ref:A:9") == 2
    assert [(c.slot, c.page) for c in rw.citations] == [("A", 8), ("A", 9)]  # sem repetição


def test_normal_markdown_brackets_are_untouched() -> None:
    text = "Veja [este link](http://x.y) e [uma nota] e a lista [1, 2, 3]."
    out, rw = rewrite(text, piece=3)
    assert out == text and not rw.citations and rw.invalid_count == 0


def test_unterminated_bracket_is_flushed_at_the_end() -> None:
    out, _ = rewrite("texto [A p.8")
    assert out == "texto [A p.8"


def test_history_text_restores_short_labels() -> None:
    out, _ = rewrite("Isso está em [A p.8] e [B p.14].")
    assert to_history_text(out) == "Isso está em [A p.8] e [B p.14]."
