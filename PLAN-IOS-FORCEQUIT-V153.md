# PLAN — iOS "launches then force-quits" (V153)

**Status:** in progress
**Symptom (user):** The app opens, shows the anime/novel/manga launch art, then force-quits
immediately after the first frame. Every prior iOS fix (V151 plist bundling, V152 workflow) landed;
the app still dies at startup.

---

## 1. Root cause — CONFIRMED (not a hypothesis)

Compose Multiplatform 1.7.3 (`gradle/libs.versions.toml: compose-multiplatform = "1.7.3"`) ships
`androidx.compose.ui.uikit.PlistSanityCheck`. It runs when `ComposeUIViewController` is created and,
if `Info.plist` has no valid `CADisableMinimumFrameDurationOnPhone`, prints an error and **aborts the
process** (`enforceStrictPlistSanityCheck` defaults to `true`).

### Evidence
1. The exact abort strings are present in the shipped binary inside
   `novelapp-ios-unsigned-ipa.zip`:

   ```
   PlistSanityCheck$performIfNeeded$2
   PlistSanityCheck
   CADisableMinimumFrameDurationOnPhone
   Error: `Info.plist` doesn't have a valid `CADisableMinimumFrameDurationOnPhone` entry.
   Add `<key>CADisableMinimumFrameDurationOnPhone</key><true/>` entry to `Info.plist` to fix this error.
   ```

2. The authored `iosApp/Info.plist` (the file the V151 fix made the app actually bundle) contains
   **no** `CADisableMinimumFrameDurationOnPhone` key.

3. Timing matches the user's report exactly:
   iOS shows `LaunchScreen.storyboard` (the anime/novel/manga art) →
   Compose creates its view controller → `PlistSanityCheck.performIfNeeded()` → `abort()` → force-quit
   *after the first frame*. Nothing in the Compose UI ever gets a chance to render.

This is a **third, independent** defect, unrelated to V151 (XcodeGen overwriting the plist) and
V152 (the CI version-extraction placeholder).

## 2. Fix

* **F1** `iosApp/Info.plist` — add `<key>CADisableMinimumFrameDurationOnPhone</key><true/>`
  (Apple/Compose-standard: unlock ProMotion 120 Hz; also satisfies the sanity check).
* **F2** `scripts/verify-ios-info-plist.py` — add the key to `REQUIRED_KEYS` **and** a dedicated
  assertion that it is strictly `true`, so a future regression (including the XcodeGen stub, which
  lacks it) fails the build instead of shipping a crash-on-launch IPA.
* **F3** Confirm the iOS entry point does **not** merely disable the check
  (`enforceStrictPlistSanityCheck = false`) — that would mask the problem instead of fixing it. If it
  does, keep the plist key anyway (the key is required for correct frame pacing regardless).

## 3. Completeness audit (the "is it a real, full app?" question)

The crash is why every other model concluded "it works, it's your device". It is not: the process
aborts. After the plist fix the app must actually render the shared UI. Audit:

* **A1** `composeApp/src/iosMain/.../MainViewController.kt` returns the real root composable
  (`NovaRead / App()`), not a placeholder.
* **A2** `iosApp/iosApp/*.swift` hosts that controller in a `UIViewControllerRepresentable` /
  `UIApplicationDelegate`, sized to the window.
* **A3** `BuildKonfig.ios.kt` reads the API keys (V151 added them to the bundle) and does **not**
  silently fall back to `mock_*` in a way that blanks the UI.
* **A4** No `TODO`, `NotImplementedError`, or `TODO()` in `iosMain` / shared UI entry paths.
* **A5** The tab structure (Anime / Novel / Manga) and its data calls are wired to real repositories.

## 4. Execution checklist

- [ ] P1 Write this plan
- [ ] P2 Inspect the bundled plist inside the built IPA (confirm missing key, confirm V151 keys present)
- [ ] P3 Grep for `ComposeUIViewController` / `enforceStrictPlistSanityCheck`
- [ ] F1 Add `CADisableMinimumFrameDurationOnPhone` to `iosApp/Info.plist`
- [ ] F2 Harden `scripts/verify-ios-info-plist.py`
- [ ] A1–A5 Completeness audit of the iOS entry + shared root
- [ ] V1 `plistlib` parse the edited plist (well-formed, key present, strictly true)
- [ ] V2 Run `verify-ios-info-plist.py` against a synthetic "as-built" plist to prove it passes now
- [ ] V3 Commit + push so CI rebuilds the IPA
- [ ] V4 Confirm CI verification step passes and IPA is produced

## 5. Verification limits

`xcodegen`/Xcode are macOS-only and absent here, so the authoritative build runs on the GitHub macOS
runner. Local verification = plist parse + the hardened checker + static review of the iOS entry
path. The CI run is the end-to-end proof.
