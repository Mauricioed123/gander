#!/usr/bin/env python3
"""
Generates every fixture document the test suite reads.

Three tiers share these files: the JVM and Robolectric tests take them as test
resources, the instrumented tests as assets inside the test APK, and the Python
viewer tests straight off disk. One directory, one generator, so a format only
ever needs describing once.

Everything here is invented. Nothing imitates a real organisation, and no
identifier is registry-shaped: see tests/fixtures/README.md for why that rule
exists.

Output is byte-stable, so regenerating an unchanged fixture produces no diff.
PDFs get ReportLab's invariant flag; the OOXML formats are zips, so their
entry timestamps and core properties are normalised by hand afterwards.

Usage:  python3 tests/fixtures/make_fixtures.py
Needs:  reportlab python-docx openpyxl python-pptx pillow
"""

import io
import os
import re
import shutil
import struct
import sys
import wave
import zipfile
from datetime import datetime
from math import pi, sin
from pathlib import Path

OUT = Path(__file__).parent / "files"

# Every timestamp written into a fixture. Arbitrary, fixed, and in the past.
EPOCH = datetime(2026, 1, 1, 0, 0, 0)
ZIP_DATE = (2026, 1, 1, 0, 0, 0)

# The word test_pdf_search.py counts. It appears exactly three times in
# six-pages.pdf and nowhere else in it, in three different cases.
NEEDLE_LINES = [
    "This tenancy begins on the first of March.",
    "The Tenancy may be ended by either party.",
    "Nothing in this TENANCY limits the above.",
]


def written(path: Path) -> Path:
    print(f"  {path.relative_to(OUT.parent.parent)}  {path.stat().st_size:,} B")
    return path


# ---------------------------------------------------------------------------
# Zip normalisation, for the three OOXML formats
# ---------------------------------------------------------------------------

ISO_EPOCH = EPOCH.strftime("%Y-%m-%dT%H:%M:%SZ")


def normalize_zip(path: Path) -> None:
    """Rewrites a zip with fixed entry timestamps, order and compression.

    python-docx and friends stamp every entry with the time the file was
    written, so an unchanged fixture would still produce a diff on every run.

    docProps/core.xml is rewritten as well. openpyxl in particular sets
    dcterms:modified to the moment of the save, overwriting whatever the
    workbook properties said, so pinning it before the save does nothing.
    """
    with zipfile.ZipFile(path) as z:
        items = sorted((i.filename, z.read(i.filename)) for i in z.infolist())
    items = [
        (name, re.sub(
            rb"(<dcterms:(?:created|modified)[^>]*>)[^<]*(</dcterms:)",
            rb"\g<1>" + ISO_EPOCH.encode() + rb"\g<2>",
            data,
        ) if name == "docProps/core.xml" else data)
        for name, data in items
    ]
    tmp = path.with_suffix(path.suffix + ".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in items:
            info = zipfile.ZipInfo(name, date_time=ZIP_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o600 << 16
            z.writestr(info, data)
    tmp.replace(path)


def fix_core_properties(doc) -> None:
    """Pins the created and modified times an OOXML package records."""
    cp = doc.core_properties
    cp.created = EPOCH
    cp.modified = EPOCH
    cp.last_modified_by = "Gander tests"
    cp.author = "Gander tests"
    cp.revision = 1


# ---------------------------------------------------------------------------
# PDFs
# ---------------------------------------------------------------------------

def pdfs() -> None:
    from reportlab.lib.pagesizes import A3, A4, landscape
    from reportlab.lib.pdfencrypt import StandardEncryption
    from reportlab.pdfbase import pdfmetrics
    from reportlab.pdfbase.cidfonts import UnicodeCIDFont
    from reportlab.pdfbase.ttfonts import TTFont
    from reportlab.pdfgen import canvas

    def new(path: Path, pagesize=A4, **kw):
        # invariant drops the creation date and the document id, which are the
        # only two things that would otherwise differ between two identical runs
        return canvas.Canvas(str(path), pagesize=pagesize, invariant=1, **kw)

    # six-pages.pdf: the workhorse. Base-14 fonts only, so no embedded face,
    # and exactly three occurrences of the search needle.
    c = new(OUT / "six-pages.pdf")
    for n in range(1, 7):
        c.setFont("Helvetica-Bold", 18)
        c.drawString(72, 760, f"Alder Court, page {n}")
        c.setFont("Helvetica", 11)
        y = 720
        for line in [
            "A short agreement written only so that a test has something to read.",
            "Every name, address and figure in it is invented.",
        ]:
            c.drawString(72, y, line)
            y -= 18
        if n <= len(NEEDLE_LINES):
            c.drawString(72, y - 12, NEEDLE_LINES[n - 1])
        c.showPage()
    c.save()
    written(OUT / "six-pages.pdf")

    # forty-pages.pdf: long enough that the page band holds a fraction of it,
    # which is what the virtualisation and go-to-page tests need.
    c = new(OUT / "forty-pages.pdf")
    for n in range(1, 41):
        c.setFont("Helvetica-Bold", 16)
        c.drawString(72, 760, f"Section {n}")
        c.setFont("Helvetica", 11)
        c.drawString(72, 730, f"This is page {n} of forty.")
        c.showPage()
    c.save()
    written(OUT / "forty-pages.pdf")

    # embedded-font.pdf: carries its own face, so the text layer must name
    # that face rather than a generic. ReportLab ships Vera under a licence
    # that allows redistribution.
    vera = Path(pdfmetrics.__file__).parent.parent / "fonts" / "Vera.ttf"
    pdfmetrics.registerFont(TTFont("Vera", str(vera)))
    c = new(OUT / "embedded-font.pdf")
    c.setFont("Vera", 20)
    c.drawString(72, 700, "Embedded Vera, not a system face.")
    c.showPage()
    c.save()
    written(OUT / "embedded-font.pdf")

    # cjk.pdf: a CID font named but deliberately NOT embedded, so pdf.js can
    # only draw it by loading Adobe's UniGB CMap out of lib/cmaps. Without
    # those tables the text vanishes from the canvas and the text layer both,
    # silently, which is issue #21.
    pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))
    c = new(OUT / "cjk.pdf")
    c.setFont("STSong-Light", 22)
    c.drawString(72, 700, "你好世界")  # ni hao shi jie
    c.setFont("Helvetica", 12)
    c.drawString(72, 660, "The line above must render and be selectable.")
    c.showPage()
    c.save()
    written(OUT / "cjk.pdf")

    # mixed-width.pdf: one A4 page then one A3, to pin that both lay out to the
    # same CSS width. That normalisation was once thought to be issue #20; it is
    # not, because a page shown at one width is equally sharp whatever its paper
    # size. The blur was the single rasterisation, and dense-map.pdf tests it.
    c = new(OUT / "mixed-width.pdf", pagesize=A4)
    c.setFont("Helvetica", 24)
    c.drawString(72, 700, "A4 page")
    c.showPage()
    c.setPageSize(A3)
    c.setFont("Helvetica", 24)
    c.drawString(72, 1000, "A3 page")
    c.showPage()
    c.save()
    written(OUT / "mixed-width.pdf")

    # dense-map.pdf: A3 landscape carrying detail into every corner, which is
    # what a tube map or a site plan is and what issue #20 was reported against.
    # The tile tests need a page whose middle is not blank: a fixture with a
    # line of text at the top correlates to nothing once you zoom past it, and
    # a test comparing two blank regions agrees with itself perfectly.
    c = new(OUT / "dense-map.pdf", pagesize=landscape(A3))
    w, h = landscape(A3)
    c.setLineWidth(0.25)
    c.setStrokeColorRGB(0.78, 0.78, 0.84)
    for x in range(0, int(w), 20):
        c.line(x, 0, x, h)
    for y in range(0, int(h), 20):
        c.line(0, y, w, y)
    c.setStrokeColorRGB(0.1, 0.2, 0.7)
    c.setLineWidth(2)
    for i in range(9):
        c.line(60 + i * 130, 60, 60 + i * 130 + 300, h - 60)
    c.setFillColorRGB(0, 0, 0)
    c.setFont("Helvetica", 3.5)
    for x in range(0, int(w), 100):
        for y in range(0, int(h), 60):
            c.drawString(x + 2, y + 2, f"St {x}/{y}")
    c.showPage()
    c.save()
    written(OUT / "dense-map.pdf")

    # colours.pdf: everything night mode has to get right, in known values so a
    # test can assert exact pixels rather than "darker".
    #
    # Page 1 is a document: white paper, near-black text, a saturated heading and
    # a vector block, all of which turn over, plus a small image that must not.
    # Page 2 is a scan: one image covering the whole page, which must turn over
    # despite being an image, because that is what a scanned book is.
    #
    # Flat colours on purpose. A photograph would make the assertions sample
    # noise, and what is being pinned here is which pixels the filter reached.
    from PIL import Image
    from reportlab.lib.utils import ImageReader

    def block(rgb, size=(64, 64)):
        return ImageReader(Image.new("RGB", size, rgb))

    W, H = 400, 600
    c = new(OUT / "colours.pdf", pagesize=(W, H))
    c.setFillColorRGB(1, 1, 1)
    c.rect(0, 0, W, H, stroke=0, fill=1)
    # Up in the top-left corner, so the first zoom tile lands on it and the tile
    # path's own coordinate mapping is exercised rather than assumed.
    c.drawImage(block((255, 0, 255)), 20, 420, width=100, height=100)
    c.setFillColorRGB(20 / 255, 20 / 255, 20 / 255)
    c.setFont("Helvetica", 14)
    c.drawString(160, H - 60, "Body text")
    c.setFillColorRGB(0, 119 / 255, 199 / 255)
    c.setFont("Helvetica-Bold", 20)
    c.drawString(160, H - 100, "Coloured heading")
    c.setFillColorRGB(30 / 255, 150 / 255, 60 / 255)
    c.rect(160, H - 200, 80, 60, stroke=0, fill=1)
    c.drawImage(block((200, 30, 30)), 40, 120, width=180, height=180)
    c.showPage()

    scan = Image.new("RGB", (200, 300), (255, 255, 255))
    for x in range(20, 180):
        for y in range(40, 60):
            scan.putpixel((x, y), (20, 20, 20))
    c.drawImage(ImageReader(scan), 0, 0, width=W, height=H)
    c.showPage()
    c.save()
    written(OUT / "colours.pdf")

    # encrypted.pdf: the standard security handler, which is what nearly every
    # protected PDF in circulation uses and the only kind pdf.js can unlock.
    enc = StandardEncryption("gander", canPrint=1)
    c = new(OUT / "encrypted.pdf", encrypt=enc)
    c.setFont("Helvetica", 18)
    c.drawString(72, 700, "Unlocked with the password gander.")
    c.showPage()
    c.save()
    written(OUT / "encrypted.pdf")

    # Named .pdf, is not one. pdf.js must say so rather than showing nothing.
    (OUT / "not-a-pdf.pdf").write_bytes(
        b"This file is named .pdf and is plain text. It is not a PDF at all.\n"
    )
    written(OUT / "not-a-pdf.pdf")


# ---------------------------------------------------------------------------
# OOXML
# ---------------------------------------------------------------------------

def docx() -> None:
    from docx import Document
    from docx.shared import Pt

    doc = Document()
    doc.add_heading("Field Survey, Willowmere", level=1)
    doc.add_paragraph(
        "A short report written only so that a test has something to render."
    )
    # A Wingdings bullet sitting in the private use area. docx.html rewrites
    # U+F000 to U+F0FF into real Unicode, because the font is not on the phone
    # and the glyph would otherwise come out as a blank box.
    p = doc.add_paragraph()
    run = p.add_run("")
    run.font.name = "Wingdings"
    run.font.size = Pt(12)
    p.add_run(" A bullet that arrives as a private use codepoint.")
    doc.add_paragraph("The paragraph after it, so ordering is checkable.")
    fix_core_properties(doc)
    doc.save(str(OUT / "report.docx"))
    normalize_zip(OUT / "report.docx")
    written(OUT / "report.docx")


SHEET_ROWS = [
    ("Item", "Quarter", "Amount"),
    ("Surveying", "Q3", 4200),
    ("Drainage", "Q3", 1850),
    ("Fencing", "Q3", 990),
]


def xlsx() -> None:
    import csv

    from openpyxl import Workbook

    wb = Workbook()
    first = wb.active
    first.title = "Summary"
    for row in SHEET_ROWS:
        first.append(row)
    second = wb.create_sheet("Detail")
    second.append(("Note", "Value"))
    second.append(("Second sheet marker", "detail-sheet"))
    third = wb.create_sheet("Notes")
    third.append(("Third sheet marker",))
    wb.properties.created = EPOCH
    wb.properties.modified = EPOCH
    wb.properties.creator = "Gander tests"
    wb.save(str(OUT / "budget.xlsx"))
    normalize_zip(OUT / "budget.xlsx")
    written(OUT / "budget.xlsx")

    with open(OUT / "budget.csv", "w", newline="", encoding="utf-8") as fh:
        csv.writer(fh).writerows(SHEET_ROWS)
    written(OUT / "budget.csv")


def pptx() -> None:
    from pptx import Presentation
    from pptx.util import Inches

    prs = Presentation()
    titles = ["Willowmere Kickoff", "What we found", "What happens next"]
    for n, title in enumerate(titles, start=1):
        slide = prs.slides.add_slide(prs.slide_layouts[1])
        slide.shapes.title.text = title
        body = slide.placeholders[1].text_frame
        body.text = f"Slide {n} of three."
        body.add_paragraph().text = "Invented content, for rendering only."
    fix_core_properties(prs)
    prs.save(str(OUT / "deck.pptx"))
    normalize_zip(OUT / "deck.pptx")
    written(OUT / "deck.pptx")


# ---------------------------------------------------------------------------
# Text
# ---------------------------------------------------------------------------

def texts() -> None:
    md = """# Willowmere site notes

A heading, a [link](https://example.invalid/notes), and a list:

- first
- second

<script>window.__xss = 1;</script>

<img src="x" onerror="window.__xss = 2;">

Text after the injected markup, so the sanitiser can be seen to have kept it.
"""
    (OUT / "notes.md").write_text(md, encoding="utf-8")
    written(OUT / "notes.md")

    plain = (
        "Plain text, opened by the text viewer.\n"
        "A second line so the newline handling is visible.\n"
        "An accented character: café.\n"
    )
    (OUT / "plain.txt").write_text(plain, encoding="utf-8")
    written(OUT / "plain.txt")

    # Byte order marks. app.js sniffs these three bytes and picks the decoder;
    # the decoder strips the mark itself, so neither file should show one.
    marked = "Byte order marked text, decoded by the mark alone.\n"
    (OUT / "utf16le.txt").write_bytes(b"\xff\xfe" + marked.encode("utf-16-le"))
    written(OUT / "utf16le.txt")
    (OUT / "utf16be.txt").write_bytes(b"\xfe\xff" + marked.encode("utf-16-be"))
    written(OUT / "utf16be.txt")

    # An extension nothing claims, so the viewer offers to read it as text.
    (OUT / "unknown.xyz").write_bytes(bytes(range(32, 127)) * 4 + b"\n")
    written(OUT / "unknown.xyz")

    # Legacy binary Word: the OLE2 compound file signature and nothing useful
    # after it. Gander does not open these and says so; it must not try.
    (OUT / "legacy.doc").write_bytes(
        b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1" + b"\x00" * 504
    )
    written(OUT / "legacy.doc")


# ---------------------------------------------------------------------------
# Images and audio
# ---------------------------------------------------------------------------

# The six EXIF orientations Thumbs.exifRotation maps to a rotation, plus the
# two flips it folds into 90 and 270.
EXIF_ORIENTATIONS = {
    1: 0,    # normal
    3: 180,  # rotate 180
    6: 90,   # rotate 90
    8: 270,  # rotate 270
    5: 90,   # transpose
    7: 270,  # transverse
}


def images() -> None:
    from PIL import Image, ImageDraw

    def asymmetric(w=120, h=80):
        """A frame with one filled corner, so a rotation is visible."""
        img = Image.new("RGB", (w, h), (245, 245, 245))
        d = ImageDraw.Draw(img)
        d.rectangle([0, 0, w - 1, h - 1], outline=(30, 30, 30), width=2)
        d.rectangle([4, 4, 34, 24], fill=(178, 45, 24))
        return img

    for orientation in sorted(EXIF_ORIENTATIONS):
        path = OUT / f"exif-{orientation}.jpg"
        exif = Image.Exif()
        exif[0x0112] = orientation
        asymmetric().save(path, "JPEG", quality=88, exif=exif)
        written(path)

    asymmetric(64, 64).save(OUT / "tiny.png", "PNG", optimize=True)
    written(OUT / "tiny.png")

    frames = []
    for shift in range(4):
        img = Image.new("P", (48, 48), 0)
        d = ImageDraw.Draw(img)
        d.rectangle([shift * 8, 8, shift * 8 + 16, 24], fill=1)
        img.putpalette([245, 245, 245, 178, 45, 24] + [0] * 762)
        frames.append(img)
    frames[0].save(
        OUT / "anim.gif", save_all=True, append_images=frames[1:],
        duration=120, loop=0,
    )
    written(OUT / "anim.gif")

    (OUT / "icon.svg").write_text(
        '<svg xmlns="http://www.w3.org/2000/svg" width="120" height="80" '
        'viewBox="0 0 120 80" role="img" aria-label="A red square in a frame">\n'
        '  <rect width="120" height="80" fill="#f5f5f5" stroke="#1e1e1e" '
        'stroke-width="2"/>\n'
        '  <rect x="8" y="8" width="30" height="20" fill="#b22d18"/>\n'
        "</svg>\n",
        encoding="utf-8",
    )
    written(OUT / "icon.svg")


def audio() -> None:
    rate, seconds, freq = 8000, 1, 440.0
    frames = bytearray()
    for i in range(rate * seconds):
        # A quiet sine, so a device test that actually plays it is bearable
        frames += struct.pack("<h", int(6000 * sin(2 * pi * freq * i / rate)))
    with wave.open(str(OUT / "tone.wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(bytes(frames))
    written(OUT / "tone.wav")


# ---------------------------------------------------------------------------

def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    print(f"Writing fixtures into {OUT}")
    for step in (pdfs, docx, xlsx, pptx, texts, images, audio):
        step()
    total = sum(p.stat().st_size for p in OUT.iterdir() if p.is_file())
    count = sum(1 for p in OUT.iterdir() if p.is_file())
    print(f"\n{count} files, {total:,} bytes total")
    if total > 3 * 1024 * 1024:
        print("WARNING: fixtures exceed the 3 MB budget", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
