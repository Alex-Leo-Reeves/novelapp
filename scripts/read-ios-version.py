#!/usr/bin/env python3
"""Print the iOS version and build number declared in iosApp/project.yml.

The iOS IPA workflow needs MARKETING_VERSION and CURRENT_PROJECT_VERSION to prove
that the plist bundled into the built .app is the authored iosApp/Info.plist and
not an XcodeGen stub. Those two values used to be read by a multi-line Python
heredoc embedded directly inside the workflow's YAML `run:` block. That form is
indentation-sensitive and is exactly how the pipeline broke: a hand edit collapsed
it into the literal placeholder `python3 -c '...'`, which is valid Python (the
Ellipsis literal), exits 0 and prints nothing, so the step failed with a bogus
"Missing MARKETING_VERSION in iosApp/project.yml".

Keeping the parsing in this file means the workflow only has to invoke a script,
and the parsing can be exercised on its own.

Usage:
    read-ios-version.py [path/to/project.yml]

Default path: iosApp/project.yml

Output (stdout, exactly two lines):
    <MARKETING_VERSION>
    <CURRENT_PROJECT_VERSION>

Exit codes:
    0 = both values found and printed
    1 = the spec is missing/unreadable, or a value is absent or empty
    2 = wrong arguments
"""

from __future__ import annotations

import sys
from pathlib import Path

DEFAULT_PROJECT_SPEC = "iosApp/project.yml"

MARKETING_VERSION_KEY = "MARKETING_VERSION"
CURRENT_PROJECT_VERSION_KEY = "CURRENT_PROJECT_VERSION"

# XcodeGen applies `settings.base` to every build configuration, and also accepts
# a flat `settings:` mapping. Look in the more specific place first.
SETTING_PATHS: tuple[tuple[str, ...], ...] = (
    ("settings", "base"),
    ("settings",),
)


def _indentation(line: str) -> int:
    """Number of leading spaces, used to reconstruct the YAML block structure."""
    return len(line) - len(line.lstrip(" "))


def _clean_scalar(raw_value: str) -> str:
    """Unquote a scalar and drop any trailing `# comment`."""
    value = raw_value.strip()
    if not value:
        return ""

    quote = value[0]
    if quote in {'"', "'"}:
        closing = value.find(quote, 1)
        return value[1:closing] if closing != -1 else value[1:]

    # For an unquoted scalar a '#' only opens a comment when preceded by a space.
    for index, character in enumerate(value):
        if character == "#" and index > 0 and value[index - 1].isspace():
            return value[:index].strip()
    return value


def _parse_key_value(line: str) -> tuple[str, str] | None:
    """Split `key: value`. Returns None for comments, list items and blank lines."""
    stripped = line.strip()
    if not stripped or stripped.startswith("#") or stripped.startswith("-"):
        return None
    if ":" not in stripped:
        return None

    key, _, raw_value = stripped.partition(":")
    key = key.strip()
    if not key or " " in key or "\t" in key:
        return None
    return key, _clean_scalar(raw_value)


def _find_child(
    lines: list[str], start: int, end: int, parent_indent: int, key: str
) -> int | None:
    """Index of the first `key:` line nested inside the block, or None."""
    for index in range(start, end):
        line = lines[index]
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if _indentation(line) <= parent_indent:
            continue
        parsed = _parse_key_value(line)
        if parsed is not None and parsed[0] == key:
            return index
    return None


def _subtree_end(lines: list[str], block_index: int, indentation: int, limit: int) -> int:
    """First index after the block that is indented no deeper than its header."""
    index = block_index + 1
    while index < limit:
        line = lines[index]
        if line.strip() and not line.lstrip().startswith("#"):
            if _indentation(line) <= indentation:
                return index
        index += 1
    return limit


def read_setting(lines: list[str], mapping_path: tuple[str, ...], key: str) -> str | None:
    """Read `key` from the mapping at `mapping_path`, e.g. ("settings", "base")."""
    start, end, parent_indent = 0, len(lines), -1

    for segment in mapping_path:
        block_index = _find_child(lines, start, end, parent_indent, segment)
        if block_index is None:
            return None
        parent_indent = _indentation(lines[block_index])
        start = block_index + 1
        end = _subtree_end(lines, block_index, parent_indent, end)

    value_index = _find_child(lines, start, end, parent_indent, key)
    if value_index is None:
        return None

    parsed = _parse_key_value(lines[value_index])
    if parsed is None:
        return None
    return parsed[1] or None


def lookup(lines: list[str], key: str) -> str | None:
    """Read `key` from the first setting path that defines it."""
    for mapping_path in SETTING_PATHS:
        value = read_setting(lines, mapping_path, key)
        if value:
            return value
    return None


def main(argv: list[str]) -> int:
    if len(argv) > 2:
        print(__doc__, file=sys.stderr)
        return 2

    spec_path = Path(argv[1] if len(argv) == 2 else DEFAULT_PROJECT_SPEC)

    try:
        text = spec_path.read_text(encoding="utf-8")
    except OSError as error:
        print(f"::error::cannot read {spec_path}: {error}")
        return 1

    lines = text.splitlines()

    version = lookup(lines, MARKETING_VERSION_KEY)
    build = lookup(lines, CURRENT_PROJECT_VERSION_KEY)

    if not version:
        print(f"::error::Missing {MARKETING_VERSION_KEY} in {spec_path}")
        return 1
    if not build:
        print(f"::error::Missing {CURRENT_PROJECT_VERSION_KEY} in {spec_path}")
        return 1

    print(version)
    print(build)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
