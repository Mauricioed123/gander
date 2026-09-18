"""unsupported.html: the last resort, and the way out of it."""


def test_the_file_is_named(viewer, page):
    viewer("unsupported.html", "unknown.xyz")
    page.wait_for_selector("#fname", timeout=10000)
    assert page.text_content("#fname").strip() == "unknown.xyz"


def test_legacy_word_gets_an_explanation_of_its_own(viewer, page):
    """
    .doc and .ppt are the two formats Gander deliberately does not open, so
    they are told why rather than just refused.
    """
    viewer("unsupported.html", "legacy.doc")
    page.wait_for_selector("#hint", timeout=10000)
    assert page.text_content("#hint").strip()


def test_reading_it_as_text_is_offered_with_a_caveat(viewer, page):
    viewer("unsupported.html", "unknown.xyz")
    page.wait_for_selector("#asText", timeout=10000)
    assert page.get_attribute("#asText", "aria-describedby") == "caveat"
    assert "caracteres sin sentido" in page.text_content("#caveat")


def test_reading_it_as_text_opens_the_text_viewer(viewer, page):
    viewer("unsupported.html", "unknown.xyz")
    page.wait_for_selector("#asText", timeout=10000)

    page.click("#asText")
    page.wait_for_url("**/text.html*", timeout=15000)

    assert "name=unknown.xyz" in page.url
    assert "ext=xyz" in page.url
    page.wait_for_function(
        "() => document.querySelector('#content').textContent.length > 0", timeout=20000
    )
