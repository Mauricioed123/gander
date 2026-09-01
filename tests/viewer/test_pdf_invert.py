"""
Issue #19: night mode, the toggle that turns a PDF's pages over.

The page bitmap gets one filtered pass once pdf.js has finished drawing it, so
what is asserted here is pixels: which colour each thing on the page came out,
not merely that it got darker. colours.pdf is painted in flat known values for
exactly that, and the values below are the arithmetic the filter is meant to be
doing, worked out from the matrix rather than read off a screenshot.

Two of these tests are the ones worth keeping if the rest ever go. Grey has to
stay grey, because the near-miss implementation of this feature is
`invert(1) hue-rotate(180deg)` written as CSS, and a hue-rotate matrix that is
even slightly off leaves body text tinted rather than white -- on a page of
prose that is most of the pixels. And a hue has to survive, because plain
inversion flips it: the cheap version of this feature would bring the heading
below back orange.
"""

import pytest

from helpers import (
    body_ground, page_colours, pan, paper_ground, set_page_scale,
    text_layer_geometry, tile_colours, tiles, wait_for_pdf, wait_for_redraw,
    wait_for_text_layer, wait_for_tile,
)

# What colours.pdf is painted in, and what each one must become.
PAPER = "255,255,255"
PAPER_OVER = "0,0,0"

INK = "20,20,20"                 # body text
INK_OVER = "235,235,235"         # lighter by as much as it was dark, and still grey

HEADING = "0,119,199"            # a saturated blue
HEADING_OVER = "56,175,255"      # same hue, 204 degrees; lightness 0.39 -> 0.61
HEADING_PLAIN_INVERT = "255,136,56"   # what a plain invert would give: orange

VECTOR = "30,150,60"             # a drawn block, not an image, so it turns over
VECTOR_OVER = "49,169,79"

PHOTO = "200,30,30"              # an image on page 1, small enough to be an illustration
CORNER_PHOTO = "255,0,255"       # a second one, up where the first zoom tile lands


def night(viewer, page, on=True, fixture="colours.pdf"):
    """Opens colours.pdf the way ViewerActivity would, with the mode already set."""
    viewer("pdf.html", fixture, night="1" if on else "0")
    wait_for_pdf(page)
    page.wait_for_timeout(500)
    return page


# ---------------------------------------------------------------------------
# The transform itself
# ---------------------------------------------------------------------------

def test_the_paper_turns_black_and_the_ink_turns_white(viewer, page):
    night(viewer, page)
    colours = page_colours(page)
    assert PAPER_OVER in colours, f"paper did not turn over; saw {list(colours)[:6]}"
    assert PAPER not in colours, "white paper survived night mode"
    assert INK_OVER in colours, f"body text did not turn over; saw {list(colours)[:6]}"


def test_grey_stays_grey(viewer, page):
    """
    The one that catches a wrong matrix.

    `invert(1) hue-rotate(180deg)` is the usual one-liner for this, and if the
    hue-rotate matrix's rows do not each sum to one it scales a channel on
    neutral colours: body text comes back faintly tinted instead of white. It is
    subtle enough to ship and obvious enough to be reported, and on a page of
    prose it is most of the pixels. So the assertion is not "light" but "equal".
    """
    night(viewer, page)
    over = [c for c in page_colours(page) if c not in (CORNER_PHOTO, PHOTO)]
    for colour in over:
        r, g, b = (int(v) for v in colour.split(","))
        if r == g == b:
            continue
        # Anything not neutral has to be one of the page's own coloured things
        assert colour in (HEADING_OVER, VECTOR_OVER), \
            f"{colour} is neither neutral nor one of the page's colours"
    assert INK_OVER in over
    r, g, b = (int(v) for v in INK_OVER.split(","))
    assert r == g == b


def test_a_colour_keeps_its_hue(viewer, page):
    """Plain inversion would bring this heading back orange. It has to stay blue."""
    night(viewer, page)
    colours = page_colours(page)
    assert HEADING_OVER in colours, f"heading came out wrong; saw {list(colours)[:6]}"
    assert HEADING_PLAIN_INVERT not in colours, "the heading was plainly inverted"
    assert VECTOR_OVER in colours


def test_nothing_turns_over_unless_night_mode_is_asked_for(viewer, page):
    """Default off. A document is paper and is white at midnight."""
    night(viewer, page, on=False)
    colours = page_colours(page)
    assert PAPER in colours
    assert PAPER_OVER not in colours
    assert HEADING in colours


# ---------------------------------------------------------------------------
# Photographs
# ---------------------------------------------------------------------------

def test_a_photograph_is_left_as_it_was_printed(viewer, page):
    """The wart in every reader that does this by inverting the whole page."""
    night(viewer, page)
    colours = page_colours(page)
    assert PHOTO in colours, "the photograph was turned over with the page"
    assert CORNER_PHOTO in colours


def test_a_scan_turns_over_even_though_it_is_an_image(viewer, page):
    """
    Page 2 of the fixture is one image covering the whole page, which is what a
    scanned book is. Excluding it the way a photograph is excluded would make
    night mode do nothing at all on the documents most people turn it on for, so
    an image covering more than IMAGE_KEEP_MAX of a page counts as the page.
    """
    night(viewer, page)
    page.evaluate("() => document.querySelectorAll('#pages .pg')[1].scrollIntoView()")
    page.wait_for_timeout(1200)
    colours = page_colours(page, index=1)
    assert PAPER_OVER in colours, f"the scan did not turn over; saw {list(colours)[:6]}"
    assert INK_OVER in colours
    assert PAPER not in colours


# ---------------------------------------------------------------------------
# What night mode must not touch
# ---------------------------------------------------------------------------

def test_the_text_layer_is_not_moved(viewer, page):
    """
    The filter goes on the bitmap, not on an ancestor, so the words over it are
    untouched. If that ever changes, selection and search go wrong silently:
    see the text layer contract in docs/VENDORED.md.
    """
    viewer("pdf.html", "colours.pdf", night="0")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    page.wait_for_timeout(400)
    before = text_layer_geometry(page)
    assert before, "no text layer to compare"

    viewer("pdf.html", "colours.pdf", night="1")
    wait_for_pdf(page)
    wait_for_text_layer(page)
    page.wait_for_timeout(400)
    assert text_layer_geometry(page) == before


def test_the_grounds_move_with_the_toggle_and_not_with_the_scheme(viewer, page):
    """
    Night mode is asked for; it is not the phone's dark mode arriving. The
    surround and the unpainted paper both turn over with it, the second so that
    a page not yet drawn is not a white rectangle.
    """
    night(viewer, page, on=False)
    assert body_ground(page) == "rgb(72, 68, 61)"
    assert paper_ground(page) == "rgb(255, 255, 255)"

    night(viewer, page, on=True)
    assert body_ground(page) == "rgb(23, 19, 10)"
    assert paper_ground(page) == "rgb(0, 0, 0)"


# ---------------------------------------------------------------------------
# The toggle, over the real channel
# ---------------------------------------------------------------------------

def test_the_port_turns_it_on_and_off(viewer, page, port):
    """
    Driven the way ViewerActivity drives it, so the verb is checked here and in
    PortMessageTest.kt against the same two strings.
    """
    night(viewer, page, on=False)
    p = port()
    before = page_colours(page)

    p.night_mode(True)
    wait_for_redraw(page)
    assert PAPER_OVER in page_colours(page)

    p.night_mode(False)
    wait_for_redraw(page)
    assert page_colours(page) == before, \
        "turning it off did not put the page back exactly as it was"


def test_the_last_of_several_quick_toggles_wins(viewer, page, port):
    """
    A render already in flight cannot be told to change its mind, so pages are
    reconciled as they land rather than cancelled. Three taps inside a redraw is
    the case that arrangement exists for.
    """
    night(viewer, page, on=False)
    p = port()
    p.night_mode(True)
    p.night_mode(False)
    p.night_mode(True)
    wait_for_redraw(page)
    page.wait_for_timeout(800)
    colours = page_colours(page)
    assert PAPER_OVER in colours and PAPER not in colours
    assert PHOTO in colours, "the photograph was lost somewhere in the toggling"


def test_a_toggle_during_a_redraw_is_not_left_on_screen(viewer, page, port):
    """
    The window the reconcile in draw() exists for.

    setNight() gives up every drawn page and asks for it again, and pump() starts
    those renders synchronously, each one remembering the mode it began in. A
    second tap before they land leaves bitmaps arriving in a mode the reader has
    already left. They cannot be told to change their mind and cancelling them
    would send them down the failure path a fling uses, so instead they are
    checked as they land and asked for again.

    Without that check this ends with turned-over pages sitting on a light
    surround, and nothing further happens to correct it.
    """
    night(viewer, page, on=False)
    p = port()
    p.night_mode(True)
    # No wait: draw() has already captured "on" for everything in flight by the
    # time the first message is handled, so the second lands inside the window.
    p.night_mode(False)
    wait_for_redraw(page)
    page.wait_for_timeout(900)

    colours = page_colours(page)
    assert PAPER in colours, \
        f"a page turned over in a mode that was left; saw {list(colours)[:6]}"
    assert PAPER_OVER not in colours
    assert body_ground(page) == "rgb(72, 68, 61)"


# ---------------------------------------------------------------------------
# The sharp patch drawn while zoomed in
# ---------------------------------------------------------------------------

def test_a_zoom_tile_is_turned_over_too(viewer, page):
    """A tile is a second render of the page, so it needs the same pass."""
    night(viewer, page)
    set_page_scale(page, 4)
    wait_for_tile(page)
    page.wait_for_timeout(600)
    colours = tile_colours(page)
    assert colours, "no tile to read"
    assert PAPER_OVER in colours, f"the tile was not turned over; saw {list(colours)[:6]}"
    assert PAPER not in colours


def test_a_zoom_tile_leaves_a_photograph_alone(viewer, page):
    """
    The image coordinates are fractions of the whole page and the tile is one
    rectangle of it blown up, so this is the mapping between the two.

    Counted rather than looked for. A hole in the wrong place still overlaps the
    photograph if it is anywhere near it, so "some magenta survived" passes with
    the mapping broken; "exactly as much magenta as in daylight" does not.
    """
    def magenta_in_tile(on):
        night(viewer, page, on=on)
        set_page_scale(page, 4)
        wait_for_tile(page)
        page.wait_for_timeout(600)
        colours = tile_colours(page)
        assert colours, "no tile to read"
        return colours.get(CORNER_PHOTO, 0)

    daylight = magenta_in_tile(False)
    assert daylight > 0, "the fixture's corner image is not in the first tile"
    assert magenta_in_tile(True) == daylight, \
        "the photograph in the tile was turned over, or the clip landed elsewhere"


def test_a_tile_away_from_the_page_corner_still_finds_the_photograph(viewer, page):
    """
    The tile's own origin, which the first tile of a page cannot test.

    A tile at the top left of a page begins at 0,0, so the term that shifts the
    image coordinates back by where the tile starts is multiplied by nothing and
    a mistake in it cannot show. This pans down to the illustration in the lower
    half of the page first, so there is an origin to get wrong.
    """
    def red_in_tile(on):
        night(viewer, page, on=on)
        set_page_scale(page, 4)
        wait_for_tile(page)
        pan(page, 0, 900)
        page.wait_for_timeout(1200)
        placed = [t for t in tiles(page) if t["px"]]
        assert placed, "no tile after panning"
        assert placed[0]["y"] > 1, f"the tile is still at the page origin: {placed[0]}"
        return tile_colours(page).get(PHOTO, 0)

    daylight = red_in_tile(False)
    assert daylight > 0, "the fixture's lower illustration is not in the panned tile"
    assert red_in_tile(True) == daylight, \
        "the clip landed in the wrong part of the page"


def test_a_zoom_tile_is_not_turned_over_in_daylight(viewer, page):
    night(viewer, page, on=False)
    set_page_scale(page, 4)
    wait_for_tile(page)
    page.wait_for_timeout(600)
    colours = tile_colours(page)
    assert PAPER in colours and PAPER_OVER not in colours
