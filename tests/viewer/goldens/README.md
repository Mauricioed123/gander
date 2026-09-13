# Reference screenshots

Three images: `pdf.html` once, and `text.html` light and dark. pdf.html keeps
the same ground in either scheme, so a second image of it would be the same
file; test_theme.py asserts that equivalence instead.

They are made on Linux, because text rasterises differently on macOS and a
golden made on one machine and compared on another differs on every pixel.
CI compares them; locally, run them the same way CI does:

```sh
scripts/test-viewer-visual.sh
```

After a deliberate change to how either page looks, regenerate and look at the
result before committing it:

```sh
scripts/test-viewer-visual.sh --update-goldens
```

A failing comparison writes `<name>.failed.png` and `<name>.diff.png` beside
the golden. Neither is committed.
