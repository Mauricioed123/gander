"""imgweb.html: the formats the tiling view cannot decode."""


def wait_for_image(page, timeout=20000):
    page.wait_for_function(
        "() => { const i = document.getElementById('img');"
        "return i && i.complete && i.naturalWidth > 0; }",
        timeout=timeout,
    )


def test_a_gif_is_shown(viewer, page):
    viewer("imgweb.html", "anim.gif")
    wait_for_image(page)
    assert page.evaluate("() => document.getElementById('img').naturalWidth") == 48


def test_an_svg_is_shown(viewer, page):
    viewer("imgweb.html", "icon.svg")
    wait_for_image(page)
    assert page.evaluate("() => document.getElementById('img').naturalWidth") > 0


def test_the_image_is_labelled_with_the_file_name(viewer, page):
    """The only thing a screen reader can truthfully say about a photo."""
    viewer("imgweb.html", "anim.gif")
    wait_for_image(page)
    assert page.get_attribute("#img", "alt") == "anim.gif"


def test_something_that_will_not_decode_says_so(viewer, page):
    viewer("imgweb.html", "not-a-pdf.pdf", ext="gif")
    page.wait_for_selector(".vw-error", timeout=20000)
    assert page.text_content("#vw-status").strip()
