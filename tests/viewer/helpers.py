"""Small readers over the pdf.html DOM, so the tests read as prose."""

# A canvas that has never been drawn is 300x150, because that is the HTML
# default for the element and pdf.html leaves it alone until draw() sizes it.
# blank() zeroes it on release. So the three states are told apart by size:
# 300 wide means untouched, 0 means released, and anything else is a real
# bitmap at PAGE_WIDTH * DPR across.
DEFAULT_CANVAS_WIDTH = 300

DRAWN = (
    "() => [...document.querySelectorAll('#pages .pg canvas')]"
    f".filter(c => c.width > {DEFAULT_CANVAS_WIDTH}).length"
)
RELEASED = (
    "() => [...document.querySelectorAll('#pages .pg canvas')]"
    ".filter(c => c.width === 0).length"
)
SLOTS = "() => document.querySelectorAll('#pages .pg').length"


def wait_for_pdf(page, pages=None, timeout=30000):
    """Waits until the first page carries a real bitmap and the spinner has gone."""
    page.wait_for_function(f"{DRAWN} >= 1", timeout=timeout)
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return !e || getComputedStyle(e).display === 'none'"
        " || e.className.indexOf('vw-error') >= 0; }",
        timeout=timeout,
    )
    if pages is not None:
        assert page.evaluate(SLOTS) == pages, (
            f"expected {pages} page slots, saw {page.evaluate(SLOTS)}"
        )
    return page


def wait_for_text_layer(page, timeout=20000):
    """The text layer is built after the bitmap, so it is waited for separately."""
    page.wait_for_function(
        "() => document.querySelector('#pages .pg .textLayer span')", timeout=timeout
    )
    return page


def drawn_count(page):
    return page.evaluate(DRAWN)


def released_count(page):
    return page.evaluate(RELEASED)


def slot_count(page):
    return page.evaluate(SLOTS)


def canvas_widths(page):
    return page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg canvas')].map(c => c.width)"
    )


def status_text(page):
    el = page.query_selector("#vw-status")
    return el.text_content().strip() if el else ""


def status_visible(page):
    return page.evaluate(
        "() => { const e = document.getElementById('vw-status');"
        "return !!e && getComputedStyle(e).display !== 'none'; }"
    )


def text_layer(page):
    return page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg .textLayer')]"
        ".map(l => l.textContent).join(' ')"
    )


def highlight_count(page, name="vw-find"):
    return page.evaluate(
        "(n) => (window.CSS && CSS.highlights && CSS.highlights.get(n))"
        " ? CSS.highlights.get(n).size : 0",
        name,
    )
