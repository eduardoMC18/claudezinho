from app.services.chunking import MAX_CHARS, OVERLAP_CHARS, normalize, split_page_text


def test_empty_and_short_text() -> None:
    assert split_page_text("") == []
    assert split_page_text("  \n\n  ") == []
    assert split_page_text("Linha um\ncontinua\n\nSegundo paragrafo") == ["Linha um continua\nSegundo paragrafo"]


def test_long_text_is_split_with_overlap() -> None:
    sentences = [f"Esta é a frase número {i} sobre fotossíntese e clorofila." for i in range(60)]
    text = " ".join(sentences)
    chunks = split_page_text(text, max_chars=500, overlap=120)

    assert len(chunks) >= 6
    assert all(len(c) <= 500 for c in chunks)
    # Sobreposição: o começo de cada trecho repete o final do anterior.
    for before, after in zip(chunks, chunks[1:]):
        head = after[:40]
        assert head in before, f"sem sobreposição entre trechos: {head!r}"
    # Nada se perde: todas as frases aparecem inteiras em algum trecho.
    for sentence in sentences:
        assert any(sentence in c for c in chunks), sentence


def test_cuts_do_not_split_words_and_prefer_sentence_ends() -> None:
    text = ("Palavra " * 200).strip()
    chunks = split_page_text(text, max_chars=300, overlap=60)
    assert all(not c.startswith(("alavra", "alavr")) and not c.endswith("Palavr") for c in chunks)
    assert all(set(c.split()) == {"Palavra"} for c in chunks)

    sentences = "Primeira frase termina aqui. " * 30
    for chunk in split_page_text(sentences, max_chars=200, overlap=40)[:-1]:
        assert chunk.endswith("."), chunk


def test_giant_token_without_spaces_still_terminates() -> None:
    chunks = split_page_text("x" * 5000, max_chars=1000, overlap=200)
    assert chunks and all(len(c) <= 1000 for c in chunks)
    assert len(chunks) < 20


def test_defaults_are_sane() -> None:
    assert 0 < OVERLAP_CHARS < MAX_CHARS
    assert normalize("a  b\t c\n\n\n d") == "a b c\nd"
