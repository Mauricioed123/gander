"""docx.html: docx-preview, plus the private-use bullet fix."""


def wait_for_document(page, timeout=25000):
    page.wait_for_function(
        "() => document.querySelector('#container') && "
        "document.querySelector('#container').textContent.trim().length > 0",
        timeout=timeout,
    )


def test_a_document_renders_its_text(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    assert "Field Survey, Willowmere" in page.text_content("#container")


def test_paragraphs_keep_their_order(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    text = page.text_content("#container")
    assert text.index("A short report") < text.index("The paragraph after it")


def test_a_wingdings_bullet_becomes_a_real_character(viewer, page):
    """
    Word writes its bullets as private use codepoints in a font that is not on
    the phone, so they arrive as blank boxes. fixSymbolChars swaps the range
    U+F000 to U+F0FF for the Unicode characters they stand for.
    """
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    page.wait_for_timeout(500)

    leftover = page.evaluate(
        "() => { const t = document.querySelector('#container').textContent;"
        "return [...t].filter(c => c >= '\\uF000' && c <= '\\uF0FF').length; }"
    )
    assert leftover == 0, f"{leftover} private use characters left in the document"


def test_the_text_around_the_bullet_is_untouched(viewer, page):
    viewer("docx.html", "report.docx")
    wait_for_document(page)
    assert "A bullet that arrives as a private use codepoint." \
        in page.text_content("#container")
