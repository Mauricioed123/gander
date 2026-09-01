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


# ---------------------------------------------------------------------------
# Tiles: the sharp patch drawn over the part of a page the reader is looking at
# ---------------------------------------------------------------------------

TILES = "() => document.querySelectorAll('#pages .pg canvas.tile').length"


def set_page_scale(page, factor):
    """
    Pinch, the way the WebView's compositor does it.

    Chromium clamps this to the page's own maximum, so the scale that actually
    took effect is read back rather than assumed: asking for 4 gives 3 here.
    """
    page.context.new_cdp_session(page).send(
        "Emulation.setPageScaleFactor", {"pageScaleFactor": factor}
    )
    return page.evaluate("() => visualViewport.scale")


def pan(page, dx, dy):
    """
    Drag the visual viewport, which is what moves the view while pinched in.

    window.scrollTo moves the layout viewport and cannot go sideways here,
    because the document is exactly as wide as that viewport.
    """
    page.context.new_cdp_session(page).send("Input.synthesizeScrollGesture", {
        "x": 120, "y": 200, "xDistance": -dx, "yDistance": -dy,
        "gestureSourceType": "touch",
    })
    page.wait_for_timeout(500)


def tile_count(page):
    return page.evaluate(TILES)


def tiles(page):
    """
    Every tile, with its rect measured from the top left of its own page box.

    The same space visible_rect() reports in, so the two can be compared without
    anything in between to get wrong.
    """
    return page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg')].flatMap(pg => {"
        "  const box = pg.getBoundingClientRect();"
        "  return [...pg.querySelectorAll('canvas.tile')].map(t => {"
        "    const r = t.getBoundingClientRect();"
        "    return { px: t.width, py: t.height,"
        "             x: r.left - box.left, y: r.top - box.top,"
        "             w: r.width, h: r.height,"
        "             boxW: box.width, boxH: box.height }; }); })"
    )


def visible_rect(page, index=0):
    """
    The part of one page the reader can see, in that page's own CSS px.

    getBoundingClientRect is in the layout viewport, which pinching deliberately
    leaves alone, and visualViewport says which part of that is on screen. So the
    two intersect directly.
    """
    return page.evaluate(
        "(i) => { const pg = document.querySelectorAll('#pages .pg')[i];"
        "  const b = pg.getBoundingClientRect(), vv = visualViewport;"
        "  const x = Math.max(b.left, vv.offsetLeft) - b.left;"
        "  const y = Math.max(b.top, vv.offsetTop) - b.top;"
        "  return { x, y,"
        "    w: Math.min(b.right, vv.offsetLeft + vv.width) - b.left - x,"
        "    h: Math.min(b.bottom, vv.offsetTop + vv.height) - b.top - y }; }",
        index,
    )


def device_pixels_per_css_pixel(page):
    """What the page derives its scale from: the screen width over what is visible."""
    return page.evaluate(
        "() => (visualViewport.width * devicePixelRatio * visualViewport.scale)"
        " / visualViewport.width"
    )


# ---------------------------------------------------------------------------
# Night mode: what colour the pixels actually came out
# ---------------------------------------------------------------------------

# Every distinct colour on a canvas, with how many samples had it. The fixture
# it is used on is painted in flat colours, so this reads as a list of "what is
# on this page" rather than a histogram of a photograph, and a test can assert
# exact values without depending on where anything sits.
_COLOURS = """(canvas) => {
  if (!canvas || !canvas.width) return null;
  const d = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
  const seen = new Map();
  for (let y = 0; y < canvas.height; y += 3)
    for (let x = 0; x < canvas.width; x += 3) {
      const o = (y * canvas.width + x) * 4;
      const k = d[o] + ',' + d[o + 1] + ',' + d[o + 2];
      seen.set(k, (seen.get(k) || 0) + 1);
    }
  return [...seen.entries()].sort((a, b) => b[1] - a[1]);
}"""


def page_colours(page, index=0, floor=20):
    """
    The colours on one page's bitmap, commonest first.

    [floor] drops the handful of samples that land on an antialiased edge, which
    are a blend of two real colours and say nothing about what the filter did.
    """
    found = page.evaluate(
        "(i) => (" + _COLOURS + ")"
        "(document.querySelectorAll('#pages .pg')[i].querySelector('canvas'))",
        index,
    )
    return {k: n for k, n in (found or []) if n >= floor}


def tile_colours(page, floor=20):
    """The same, for the sharp patch drawn over a page while zoomed in."""
    found = page.evaluate(
        "() => (" + _COLOURS + ")(document.querySelector('#pages .pg canvas.tile'))"
    )
    return {k: n for k, n in (found or []) if n >= floor}


def body_ground(page):
    return page.evaluate("() => getComputedStyle(document.body).backgroundColor")


def paper_ground(page):
    return page.evaluate(
        "() => getComputedStyle(document.querySelector('#pages .pg')).backgroundColor"
    )


def text_layer_geometry(page):
    """Every span's box, to the tenth of a pixel. Nothing night mode does may move one."""
    return page.evaluate(
        "() => [...document.querySelectorAll('#pages .pg .textLayer span')].map(s => {"
        "  const r = s.getBoundingClientRect();"
        "  return [s.textContent, Math.round(r.left * 10) / 10,"
        "          Math.round(r.top * 10) / 10, Math.round(r.width * 10) / 10,"
        "          Math.round(r.height * 10) / 10]; })"
    )


def wait_for_redraw(page, timeout=20000):
    """
    Toggling night mode gives every drawn page up and asks for it again, so the
    bitmaps go and come back. Waits for the far side of that.
    """
    page.wait_for_function(f"{DRAWN} >= 1", timeout=timeout)
    page.wait_for_timeout(700)
    return page


def wait_for_tile(page, timeout=15000):
    """A tile is debounced by 150 ms and then has to render, so it is waited for."""
    page.wait_for_function(f"{TILES} >= 1", timeout=timeout)
    return page
