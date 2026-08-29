"""
text.html: anything can be asked for as text, including a multi-gigabyte
binary, so it is read a page at a time.
"""

VW_TEXT_PAGE = 5 * 1024 * 1024


def content(page):
    return page.text_content("#content") or ""


def wait_for_text(page, timeout=20000):
    page.wait_for_function(
        "() => document.querySelector('#content').textContent.length > 0", timeout=timeout
    )


def test_a_text_file_is_shown(viewer, page):
    viewer("text.html", "plain.txt")
    wait_for_text(page)
    assert "Plain text, opened by the text viewer." in content(page)


def test_newlines_survive(viewer, page):
    viewer("text.html", "plain.txt")
    wait_for_text(page)
    assert content(page).count("\n") >= 2


def test_an_accented_character_decodes(viewer, page):
    viewer("text.html", "plain.txt")
    wait_for_text(page)
    assert "café" in content(page)


# ---------------------------------------------------------------------------
# Byte order marks
# ---------------------------------------------------------------------------

def test_utf16_little_endian_is_decoded_by_its_mark(viewer, page):
    viewer("text.html", "utf16le.txt")
    wait_for_text(page)
    assert "Byte order marked text" in content(page)


def test_utf16_big_endian_is_decoded_by_its_mark(viewer, page):
    viewer("text.html", "utf16be.txt")
    wait_for_text(page)
    assert "Byte order marked text" in content(page)


def test_the_mark_itself_is_not_shown(viewer, page):
    """The decoder strips it; a stray U+FEFF at the top would be visible."""
    viewer("text.html", "utf16le.txt")
    wait_for_text(page)
    assert "﻿" not in content(page)


def test_a_binary_read_as_text_does_not_break_the_page(viewer, page):
    """The unsupported page offers this, and warns that it may be gibberish."""
    viewer("text.html", "legacy.doc", ext="doc")
    page.wait_for_timeout(1500)
    assert page.query_selector("#content") is not None


# ---------------------------------------------------------------------------
# Paging
# ---------------------------------------------------------------------------

def test_a_small_file_offers_no_more_button(viewer, page):
    viewer("text.html", "plain.txt")
    wait_for_text(page)
    assert page.query_selector("#more").is_hidden()


def test_a_large_file_shows_the_first_page_and_offers_the_rest(viewer, page, made):
    big = made("big.txt", ("Line of text to pad the file out.\n" * 200_000))
    assert big.stat().st_size > VW_TEXT_PAGE

    viewer("text.html", big, ext="txt")
    wait_for_text(page)

    assert page.query_selector("#more").is_visible()
    note = page.text_content("#moreNote")
    assert "MB" in note or "KB" in note
    shown = len(content(page))
    assert shown < big.stat().st_size


def test_the_note_about_the_rest_is_announced(viewer, page, made):
    big = made("big2.txt", "x" * (VW_TEXT_PAGE + 1000))
    viewer("text.html", big, ext="txt")
    wait_for_text(page)
    assert page.get_attribute("#moreNote", "aria-live") == "polite"


def test_asking_for_more_appends_the_rest(viewer, page, made):
    big = made("big3.txt", "abcdefghij" * 600_000)
    viewer("text.html", big, ext="txt")
    wait_for_text(page)
    first = len(content(page))

    page.click("#moreBtn")
    page.wait_for_function(
        f"() => document.querySelector('#content').textContent.length > {first}",
        timeout=20000,
    )
    assert len(content(page)) > first


def test_a_file_ending_exactly_on_the_boundary_offers_no_empty_page(viewer, page, made):
    """
    The reader overshoots the page by a chunk rather than stopping level with
    it, so a file that ends on the boundary is known to be finished.
    """
    exact = made("exact.txt", b"a" * VW_TEXT_PAGE)
    viewer("text.html", exact, ext="txt")
    wait_for_text(page)
    page.wait_for_timeout(600)
    assert page.query_selector("#more").is_hidden()


def test_a_character_split_across_the_boundary_still_decodes(viewer, page, made):
    """
    One streaming decoder across every page, so a multi-byte character cut in
    half by a page boundary comes out whole rather than as two replacements.
    """
    # A three-byte character straddling the 5 MiB mark
    head = b"a" * (VW_TEXT_PAGE - 1)
    straddling = "€".encode()  # e2 82 ac
    made_file = made("split.txt", head + straddling + b"b" * 100)

    viewer("text.html", made_file, ext="txt")
    wait_for_text(page)
    page.click("#moreBtn")
    page.wait_for_function(
        "() => document.querySelector('#content').textContent.indexOf('b') >= 0",
        timeout=20000,
    )

    text = content(page)
    assert "€" in text, "the split character did not survive the page boundary"
    assert "�" not in text, "the split character decoded as a replacement"
