#!/usr/bin/env bash
#
# Runs the viewer's reference-screenshot tests in the same Linux image CI uses.
#
# The goldens are rasterised text, and macOS and Linux do not rasterise text
# alike, so a golden made on a Mac fails on Linux on every pixel and vice
# versa. Running them in the container is the only way to compare like with
# like from a Mac.
#
#   scripts/test-viewer-visual.sh                  compare against the goldens
#   scripts/test-viewer-visual.sh --update-goldens rewrite them
#
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"

# Pinned: a newer image is a newer Chromium, and a newer Chromium rasterises
# differently, which would fail every golden at once for no reason anybody
# reading the diff could see.
IMAGE="mcr.microsoft.com/playwright/python:v1.62.0-noble"

if ! command -v docker >/dev/null 2>&1; then
    echo "docker is needed to run the goldens on Linux from here." >&2
    echo "On Linux, run pytest tests/viewer -m visual directly instead." >&2
    exit 1
fi

exec docker run --rm -t \
    -v "$REPO:/repo" -w /repo \
    "$IMAGE" \
    bash -lc "pip install --quiet -r tests/viewer/requirements.txt \
        && pytest tests/viewer/test_visual.py -m visual $*"
