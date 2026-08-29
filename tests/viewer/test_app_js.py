"""
app.js, the runtime every viewer page shares.

Its functions are globals on every page, so they are called directly rather
than exercised through a document.
"""

import pytest


@pytest.fixture
def loaded(viewer, page):
    """Any page will do; text.html is the smallest that loads app.js."""
    viewer("text.html", "plain.txt")
    page.wait_for_function("() => typeof vwFormatSize === 'function'", timeout=15000)
    return page


# ---------------------------------------------------------------------------
# vwFormatSize: the "showing the first N of" notice
# ---------------------------------------------------------------------------

def test_sizes_under_a_megabyte_are_shown_in_kilobytes(loaded):
    assert loaded.evaluate("() => vwFormatSize(1023)") == "1 KB"
    assert loaded.evaluate("() => vwFormatSize(2048)") == "2 KB"
    assert loaded.evaluate("() => vwFormatSize(500 * 1024)") == "500 KB"


def test_nothing_is_ever_reported_as_zero_kilobytes(loaded):
    """A file with something in it should not be described as having nothing."""
    assert loaded.evaluate("() => vwFormatSize(1)") == "1 KB"
    assert loaded.evaluate("() => vwFormatSize(0)") == "1 KB"


def test_megabytes_keep_one_decimal_until_they_reach_ten(loaded):
    assert loaded.evaluate("() => vwFormatSize(2.56 * 1024 * 1024)") == "2.6 MB"
    assert loaded.evaluate("() => vwFormatSize(1.04 * 1024 * 1024)") == "1 MB"


def test_ten_megabytes_and_over_are_whole_numbers(loaded):
    """A tenth of a megabyte is not a distinction anybody reads."""
    assert loaded.evaluate("() => vwFormatSize(10.4 * 1024 * 1024)") == "10 MB"
    assert loaded.evaluate("() => vwFormatSize(53 * 1024 * 1024)") == "53 MB"


# ---------------------------------------------------------------------------
# vwEncodingOf: the only byte sniffing anywhere in the project
# ---------------------------------------------------------------------------

def encoding_of(page, *byte_values):
    return page.evaluate(
        "(b) => vwEncodingOf(new Uint8Array(b))", list(byte_values)
    )


def test_a_little_endian_mark_is_recognised(loaded):
    assert encoding_of(loaded, 0xFF, 0xFE, 0x41, 0x00) == "utf-16le"


def test_a_big_endian_mark_is_recognised(loaded):
    assert encoding_of(loaded, 0xFE, 0xFF, 0x00, 0x41) == "utf-16be"


def test_anything_unmarked_is_read_as_utf8(loaded):
    assert encoding_of(loaded, 0x48, 0x65, 0x6C, 0x6C, 0x6F) == "utf-8"
    assert encoding_of(loaded, 0xEF, 0xBB, 0xBF, 0x41) == "utf-8"


def test_a_file_too_short_to_carry_a_mark_is_read_as_utf8(loaded):
    assert encoding_of(loaded) == "utf-8"
    assert encoding_of(loaded, 0xFF) == "utf-8"


# ---------------------------------------------------------------------------
# vwDocUrl: where every page fetches its document from
# ---------------------------------------------------------------------------

def test_the_document_is_always_fetched_from_the_same_path(loaded):
    assert loaded.evaluate("() => vwDocUrl()") == "/doc/file.txt"


def test_a_file_with_no_extension_still_has_a_url(viewer, page):
    viewer("text.html", "plain.txt", ext="")
    page.wait_for_function("() => typeof vwDocUrl === 'function'", timeout=15000)
    assert page.evaluate("() => vwDocUrl()") == "/doc/file"


# ---------------------------------------------------------------------------
# The status card
# ---------------------------------------------------------------------------

def test_an_error_is_shown_as_a_title_and_a_detail(loaded):
    loaded.evaluate("() => vwError('Something went wrong', 'and here is why')")
    assert loaded.text_content(".vw-error-title") == "Something went wrong"
    assert loaded.text_content(".vw-error-detail") == "and here is why"


def test_error_text_is_set_as_text_rather_than_markup(loaded):
    """
    The detail is often an exception message carrying content from the
    document, which is untrusted. It is written with textContent.
    """
    loaded.evaluate("() => vwError('t', '<img src=x onerror=\"window.__xss=1\">')")
    loaded.wait_for_timeout(200)
    assert loaded.evaluate("() => window.__xss") is None
    assert loaded.query_selector(".vw-error-detail img") is None
