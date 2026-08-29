"""md.html: Markdown through marked, then DOMPurify, then into the DOM."""


def wait_for_render(page, timeout=20000):
    page.wait_for_function(
        "() => document.querySelector('#content').children.length > 0", timeout=timeout
    )


def test_markdown_becomes_html(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert page.query_selector("#content h1") is not None
    assert "Willowmere site notes" in page.text_content("#content h1")


def test_lists_and_links_render(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert len(page.query_selector_all("#content li")) >= 2
    assert page.query_selector("#content a") is not None


def test_a_script_in_the_document_is_removed(viewer, page):
    """
    A Markdown file is untrusted input and marked will happily pass raw HTML
    through. DOMPurify is what stands between the two.
    """
    viewer("md.html", "notes.md")
    wait_for_render(page)
    page.wait_for_timeout(500)

    assert page.query_selector("#content script") is None
    assert page.evaluate("() => window.__xss") is None


def test_an_event_handler_attribute_is_removed(viewer, page):
    viewer("md.html", "notes.md")
    wait_for_render(page)
    page.wait_for_timeout(500)

    handlers = page.evaluate(
        "() => [...document.querySelectorAll('#content *')]"
        ".filter(e => e.getAttribute('onerror') || e.getAttribute('onload')).length"
    )
    assert handlers == 0
    assert page.evaluate("() => window.__xss") is None


def test_the_prose_after_the_injected_markup_survives(viewer, page):
    """Sanitising is not the same as discarding the document."""
    viewer("md.html", "notes.md")
    wait_for_render(page)
    assert "Text after the injected markup" in page.text_content("#content")
