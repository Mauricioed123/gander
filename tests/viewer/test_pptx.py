"""pptx.html: PPTXjs, which reports nothing and is polled instead."""


def test_a_deck_renders_every_slide(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    assert len(page.query_selector_all("#result .slide")) >= 3


def test_the_spinner_goes_once_the_slides_are_up(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    page.wait_for_function(
        "() => { const e = document.getElementById('vw-status');"
        "return !e || getComputedStyle(e).display === 'none'; }",
        timeout=20000,
    )


def test_the_slide_titles_are_there(viewer, page):
    viewer("pptx.html", "deck.pptx")
    page.wait_for_function(
        "() => document.querySelectorAll('#result .slide').length >= 3", timeout=40000
    )
    # PPTXjs lays every run out with non-breaking spaces between the words
    said = page.text_content("#result").replace("\u00a0", " ")
    assert "Willowmere Kickoff" in said
    assert "What we found" in said
    assert "What happens next" in said
