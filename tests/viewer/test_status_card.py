"""
The card a viewer shows while a document opens.

It waits before it appears, so a document that opens quickly never shows it; how long, and
why that long, is beside .vw-wait in app.css. Anything a script puts in the card afterwards
is never held back: an error, the password prompt, and the line shown once a password has
been entered.
"""

import pytest

from helpers import wait_for_pdf

# Every viewer whose markup opens with the card.
OPENING = [
    ("pdf.html", "six-pages.pdf"),
    ("docx.html", "report.docx"),
    ("pptx.html", "deck.pptx"),
    ("xlsx.html", "budget.xlsx"),
    ("md.html", "notes.md"),
    ("text.html", "plain.txt"),
]

CARD = (
    "() => { const e = document.getElementById('vw-status');"
    "if (!e) return null; const s = getComputedStyle(e);"
    "return { display: s.display, visibility: s.visibility }; }"
)
SHOWING = (
    "() => { const e = document.getElementById('vw-status');"
    "if (!e) return false; const s = getComputedStyle(e);"
    "return s.display !== 'none' && s.visibility === 'visible'; }"
)


def showing(page):
    return page.evaluate(SHOWING)


def stretch_the_wait(page):
    """
    Holds the card back for longer than any test runs, so "not shown yet" is a fact about
    the page rather than a race against the machine. Appended to app.css as it is served,
    where it overrides the duration by coming later.
    """
    def serve(route):
        response = route.fetch()
        route.fulfill(
            response=response,
            body=response.text() + "\n.vw-wait { animation-duration: 600s; }\n",
        )
    page.route("**/assets/viewer/app.css", serve)


def hold_the_document(page):
    """Leaves every request for the document unanswered until release() lets it go."""
    held = []
    page.route("**/doc/**", lambda route: held.append(route))
    return held


def release(page, held):
    for _ in range(200):
        if held:
            break
        page.wait_for_timeout(50)
    assert held, "the page never asked for its document"
    for route in held:
        route.continue_()


@pytest.mark.parametrize("html, fixture", OPENING)
def test_the_card_a_viewer_opens_with_waits_before_it_appears(viewer, page, html, fixture):
    stretch_the_wait(page)
    hold_the_document(page)
    viewer(html, fixture)
    card = page.evaluate(CARD)
    assert card is not None, "the page no longer opens with a card"
    assert card["display"] != "none"
    assert card["visibility"] == "hidden"


def test_a_slow_document_shows_the_card_until_it_arrives(viewer, page):
    held = hold_the_document(page)
    viewer("pdf.html", "six-pages.pdf")
    page.wait_for_function(SHOWING, timeout=10000)
    release(page, held)
    wait_for_pdf(page, pages=6)


def test_an_error_does_not_wait(viewer, page):
    stretch_the_wait(page)
    viewer("pdf.html", "not-a-pdf.pdf")
    page.wait_for_selector(".vw-error", state="attached", timeout=20000)
    assert showing(page)


def test_the_password_prompt_does_not_wait(viewer, page):
    stretch_the_wait(page)
    viewer("pdf.html", "encrypted.pdf")
    page.wait_for_selector("#vw-pw", state="attached", timeout=20000)
    assert showing(page)


def test_a_line_put_up_after_a_tap_does_not_wait(viewer, page):
    """vwStatus is what puts "Opening document…" up once a password has been entered."""
    stretch_the_wait(page)
    hold_the_document(page)
    viewer("text.html", "plain.txt")
    page.wait_for_function("() => typeof vwStatus === 'function'", timeout=15000)
    assert not showing(page)
    page.evaluate("() => vwStatus('Opening document…')")
    assert showing(page)
