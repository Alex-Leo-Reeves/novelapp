#!/usr/bin/env python3
"""Fail the iOS build if the shipped binary does not carry the current shared UI.

The check used to be written inline in the workflow as:

    strings "$BIN" > "$TMP"
    strings -e l "$BIN" >> "$TMP"
    grep -qF "$marker" "$TMP"

That produced a false negative on the macOS runner. Kotlin/Native stores its
string literals as UTF-16 little-endian *only* -- no UTF-8 copy is emitted -- so
every marker taken from the shared Compose UI depends entirely on
`strings -e l`. On the runner that invocation wrote nothing, and because it was
wrapped in `2>/dev/null || true` the failure was silent. The file therefore held
only the ASCII-extracted markers (e.g. `MainViewController`, which comes from the
Swift host side), the first Kotlin marker then failed, and the build reported
"The IPA does not contain the current shared app" for a binary that does contain
it: the real 68 MB binary passes a byte-level search for all five markers.

Searching the raw bytes has no encoding flag, no alignment rule, and no
per-platform `strings` implementation to disagree with.

Usage:
    verify-ios-markers.py <path/to/Built.app/NovaRead>

Exit codes:
    0 = every marker found
    1 = at least one marker missing (the binary is not the current shared app)
    2 = wrong arguments
"""

from __future__ import annotations

import sys

# Marker strings from the CURRENT shared UI and the iOS host. Keep in sync with
# the UI: removing or rewording one of these strings silently drops its coverage.
MARKERS: tuple[str, ...] = (
    "MainViewController",
    "Search movies, anime, shows...",
    "KIDS MODE ACTIVE",
    "Loading secure player...",
    "Voice Settings",
)

# Encodings a marker may legitimately be stored in. Kotlin/Native emits UTF-16LE
# for shared-UI literals; the Swift host side is plain UTF-8.
ENCODINGS: tuple[tuple[str, str], ...] = (
    ("utf-8", "utf-8"),
    ("utf-16le", "utf-16-le"),
    ("utf-16be", "utf-16-be"),
)


def find_missing(data: bytes) -> list[str]:
    """Return the markers that are absent from `data` in every known encoding."""
    missing: list[str] = []
    for marker in MARKERS:
        if not any(marker.encode(encoding) in data for _label, encoding in ENCODINGS):
            missing.append(marker)
    return missing


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__, file=sys.stderr)
        return 2

    binary_path = argv[1]

    try:
        with open(binary_path, "rb") as handle:
            data = handle.read()
    except OSError as error:
        print(f"::error::cannot read binary {binary_path}: {error}")
        return 1

    missing = find_missing(data)

    if missing:
        for marker in missing:
            print(f"::error::Marker string not found in bundled binary: {marker}")
        print(
            "::error::The IPA does not contain the current shared app — do not ship it."
        )
        return 1

    print(
        "OK: bundled binary is the full shared Compose app "
        f"(host + current UI markers present: {len(MARKERS)} markers, {len(data)} bytes)."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
