#!/usr/bin/env python3
"""Fail the iOS build if the bundled Info.plist is not the authored one.

This exists because `iosApp/project.yml` used to declare:

    info:
      path: Info.plist

XcodeGen reads `info:` as "generate a plist at this path", not "use this file",
so `xcodegen generate` overwrote the authored plist with its own stub. The stub
hardcodes CFBundleShortVersionString=1.0 and CFBundleVersion=1, and carries none
of this app's keys (no NSAppTransportSecurity, no UIApplicationSceneManifest, no
UILaunchStoryboardName, no UIBackgroundModes, no UIRequiresFullScreen, and no API
keys). The app still installed and launched, then showed a blank screen because
BuildKonfig.ios.kt silently falls back to mock credentials for missing keys.

This checker asserts that the plist inside the *built* .app actually matches
`iosApp/Info.plist` after Xcode's build-setting expansion.

Usage:
    verify-ios-info-plist.py <path/to/Built.app/Info.plist> <expected-version> <expected-build>

`expected-version` / `expected-build` are MARKETING_VERSION and
CURRENT_PROJECT_VERSION as declared in iosApp/project.yml.

Exit code 0 = OK. Exit code 1 = the bundle is wrong (details on stdout/stderr).
"""

from __future__ import annotations

import plistlib
import sys
from typing import Any, Iterator


# Keys that must be present in the shipped bundle. Every one of these is declared
# in iosApp/Info.plist, so a missing key means the wrong plist was bundled.
REQUIRED_KEYS: tuple[str, ...] = (
    "CFBundleDisplayName",
    "CFBundleExecutable",
    "CFBundleIdentifier",
    "CFBundleName",
    "CFBundlePackageType",
    "CFBundleShortVersionString",
    "CFBundleVersion",
    # Compose Multiplatform 1.7+ aborts at startup (PlistSanityCheck) unless this
    # key is present and true -- the "shows launch screen then force-quits" bug.
    "CADisableMinimumFrameDurationOnPhone",
    "LSRequiresIPhoneOS",
    "NSAppTransportSecurity",
    "UIApplicationSceneManifest",
    "UIApplicationSupportsIndirectInputEvents",
    "UIBackgroundModes",
    "UILaunchStoryboardName",
    "UIRequiredDeviceCapabilities",
    "UIRequiresFullScreen",
    "UISupportedInterfaceOrientations",
    "RAPID_API_KEY",
    "RAPID_API_HOST",
    "MANGADEX_CLIENT_ID",
    "MANGADEX_CLIENT_SECRET",
    "MANGADEX_USERNAME",
    "MANGADEX_PASSWORD",
    "TMDB_API_KEY",
    "TMDB_READ_ACCESS_TOKEN",
    "GROQ_API_KEY",
    "GROQ_CLOUD_API_KEY",
)

# An unexpanded build setting in the final bundle means either the authored plist
# was replaced, or the $(VAR) had no source (missing Config.xcconfig entry).
PLACEHOLDER_MARKER = "$("


def load_plist(plist_path: str) -> dict[str, Any]:
    """Read a binary or XML plist into a dict."""
    with open(plist_path, "rb") as handle:
        loaded = plistlib.load(handle)
    if not isinstance(loaded, dict):
        raise ValueError(f"{plist_path} does not contain a dictionary plist")
    return loaded


def iter_leaf_values(node: Any, path: str) -> Iterator[tuple[str, Any]]:
    """Yield (dotted path, value) for every leaf in a nested plist structure."""
    if isinstance(node, dict):
        for key, child in node.items():
            yield from iter_leaf_values(child, f"{path}/{key}")
    elif isinstance(node, list):
        for index, child in enumerate(node):
            yield from iter_leaf_values(child, f"{path}/{index}")
    else:
        yield path, node


def collect_failures(info: dict[str, Any], expected_version: str, expected_build: str) -> list[str]:
    """Return a list of human-readable problems, empty when the bundle is correct."""
    failures: list[str] = []

    missing = sorted(key for key in REQUIRED_KEYS if key not in info)
    if missing:
        failures.append(
            "missing keys (" + str(len(missing)) + "): " + ", ".join(missing)
            + " -- the bundled plist is not iosApp/Info.plist"
        )

    for path, value in iter_leaf_values(info, ""):
        if isinstance(value, str) and PLACEHOLDER_MARKER in value:
            failures.append(f"unexpanded build setting at {path}: {value!r}")

    actual_version = str(info.get("CFBundleShortVersionString", ""))
    if actual_version != expected_version:
        failures.append(
            f"CFBundleShortVersionString is {actual_version!r}, expected {expected_version!r}"
        )

    actual_build = str(info.get("CFBundleVersion", ""))
    if actual_build != expected_build:
        failures.append(f"CFBundleVersion is {actual_build!r}, expected {expected_build!r}")

    if info.get("CADisableMinimumFrameDurationOnPhone") is not True:
        failures.append(
            "CADisableMinimumFrameDurationOnPhone is not set to true -- Compose "
            "Multiplatform's PlistSanityCheck aborts the app at startup without it"
        )

    ats = info.get("NSAppTransportSecurity")
    if not isinstance(ats, dict) or ats.get("NSAllowsArbitraryLoads") is not True:
        failures.append("NSAppTransportSecurity.NSAllowsArbitraryLoads is not true")

    background_modes = info.get("UIBackgroundModes")
    if not isinstance(background_modes, list) or "audio" not in background_modes:
        failures.append("UIBackgroundModes does not contain 'audio'")

    scene_manifest = info.get("UIApplicationSceneManifest")
    if not isinstance(scene_manifest, dict):
        failures.append("UIApplicationSceneManifest is missing or not a dictionary")

    return failures


def main(argv: list[str]) -> int:
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2

    plist_path, expected_version, expected_build = argv[1], argv[2], argv[3]

    try:
        info = load_plist(plist_path)
    except (OSError, ValueError, plistlib.InvalidFileException) as error:
        print(f"::error::cannot read plist {plist_path}: {error}")
        return 1

    failures = collect_failures(info, expected_version, expected_build)

    if failures:
        print(f"::error::Info.plist verification FAILED for {plist_path}")
        for failure in failures:
            print(f"::error::  - {failure}")
        print(
            "::error::Check that iosApp/project.yml has no 'info:' block and that "
            "INFOPLIST_FILE points at the authored iosApp/Info.plist."
        )
        return 1

    print(
        f"Info.plist verification OK: {plist_path} "
        f"version={expected_version} build={expected_build} "
        f"keys={len(REQUIRED_KEYS)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
