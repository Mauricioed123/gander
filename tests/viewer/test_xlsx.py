"""xlsx.html: SheetJS into a table, one sheet at a time."""


def wait_for_sheet(page, timeout=25000):
    page.wait_for_function(
        "() => document.querySelector('#sheet') && "
        "document.querySelector('#sheet').textContent.trim().length > 0",
        timeout=timeout,
    )


def test_a_workbook_shows_its_first_sheet(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    assert "Surveying" in page.text_content("#sheet")


def test_the_cells_arrive_as_a_table(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    assert page.query_selector("#sheet table") is not None
    assert len(page.query_selector_all("#sheet tr")) >= 4


def test_every_sheet_gets_a_tab(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    page.wait_for_selector("#tabs button", timeout=10000)
    assert len(page.query_selector_all("#tabs button")) == 3


def test_choosing_a_tab_swaps_the_sheet(viewer, page):
    viewer("xlsx.html", "budget.xlsx")
    wait_for_sheet(page)
    page.wait_for_selector("#tabs button", timeout=10000)

    page.query_selector_all("#tabs button")[1].click()
    page.wait_for_function(
        "() => document.querySelector('#sheet').textContent.indexOf('detail-sheet') >= 0",
        timeout=10000,
    )
    assert "Surveying" not in page.text_content("#sheet")


def test_a_csv_opens_as_a_single_sheet(viewer, page):
    """A CSV goes to the spreadsheet viewer rather than the text one."""
    viewer("xlsx.html", "budget.csv")
    wait_for_sheet(page)
    assert "Surveying" in page.text_content("#sheet")
    assert page.query_selector("#tabs").is_hidden() or \
        len(page.query_selector_all("#tabs button")) <= 1
