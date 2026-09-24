# PLAN — iOS Info.plist bundled as XcodeGen stub (V151)

**Status:** in progress
**Symptom:** NovaRead builds and installs on iOS but launches to a black/blank screen; version
reports as `1.0`; every API-backed feature is dead.

---

## 1. Root cause (CONFIRMED — not a hypothesis)

`iosApp/project.yml` declares an `info:` block:

```yaml
    info:
      path: Info.plist
```

XcodeGen's `info:` option does **not** mean "use this file". It means "**generate** a plist at this
path". `xcodegen generate` therefore **overwrites `iosApp/Info.plist`** with XcodeGen's built-in
default template, which hardcodes `CFBundleShortVersionString = 1.0` and `CFBundleVersion = 1` and
knows nothing about this app's keys.

The repository copy of `iosApp/Info.plist` stays pristine on disk only because CI never commits its
overwrite — so the bug is invisible locally and always present in the produced IPA.

### Proof
Extracted `Payload/NovaRead.app/Info.plist` from `novelapp-ios-unsigned-ipa.zip` (942 bytes vs the
2.4 KB authored file) and parsed it. The bundled plist is exactly the generator template:

| Key | Bundled value | Authored value | Verdict |
|---|---|---|---|
| `CFBundleShortVersionString` | `1.0` | `$(MARKETING_VERSION)` → 1.44 | **literal, not expanded** |
| `CFBundleVersion` | `1` | `$(CURRENT_PROJECT_VERSION)` → 44 | **literal, not expanded** |
| `NSAppTransportSecurity` | absent | `NSAllowsArbitraryLoads = true` | lost |
| `UIApplicationSceneManifest` | absent | present | lost |
| `UILaunchStoryboardName` | absent | `LaunchScreen` | lost |
| `UIBackgroundModes` | absent | `audio` | lost |
| `UIRequiresFullScreen` | absent | `true` | lost |
| `CFBundleDisplayName` | absent | `NovaRead TV` | lost |
| `RAPID_API_KEY`, `TMDB_API_KEY`, `GROQ_API_KEY`, `MANGADEX_*`, … | absent | present | lost |

Proof that the file *was* processed by Xcode (so the failure is the wrong source file, not a skipped
processing step): the surviving `$(...)` placeholders were expanded — `CFBundleExecutable` =
`NovaRead` (`$(EXECUTABLE_NAME)`), `CFBundleIdentifier` = `com.alexleoreeves.novelapp.ios`,
`CFBundleDevelopmentRegion` = `en`. Only the two un-expandable literals stayed at `1.0` / `1`.

### Downstream impact (why the user sees a black screen)
`composeApp/src/iosMain/.../BuildKonfig.ios.kt` reads each key via
`NSBundle.mainBundle.objectForInfoDictionaryKey(name)` and **silently falls back to `mock_*`** when
the key is missing. So instead of crashing, the app boots with:

* all API credentials replaced by mocks → TMDB / MangaDex / Groq / RapidAPI calls all fail →
  no content loads → blank/black UI;
* ATS unrestricted-loads rule gone → any plain-HTTP media or API host is refused outright;
* no `UIBackgroundModes: audio` → audio dies when backgrounded;
* no `UILaunchStoryboardName` → black launch frame;
* `PlatformAppVersion.ios.kt` falls back to `1.0`/`1` → any update-check against
  `site/app-version.json` is comparing a constant.

---

## 2. Secondary defects found while verifying

### 2a. `iosApp/Config.xcconfig` does not exist
`project.yml` sets `configFiles: {Debug, Release}: Config.xcconfig`, but the file is absent from the
repo and is not gitignored. CI creates it in the "Write API key xcconfig" step; a local
`xcodegen generate` has nothing to read, so local project generation is broken, and the plist's
`$(RAPID_API_KEY)`-style values have no source. Fix: commit a placeholder file (mock values, no
secrets) that CI overwrites.

### 2b. Orphaned gitlink `server/ma-server`
`git ls-files -s` reports mode `160000` (gitlink) for `server/ma-server`, but **no `.gitmodules`
exists**, so CI emits `fatal: No url found for submodule path 'server/ma-server' in .gitmodules`
and any fresh clone gets an empty directory. The embedded repo's remote is
`https://github.com/JustANormalChurro/SimplStream.git` and the pinned commit
`1488916ebecd490eb608396951f9e3ce5a4d6c22` **is present on that remote** (verified with
`git ls-remote`). Nothing else in the repo references the path. Fix: declare the existing gitlink
in `.gitmodules` — this documents what the index already records and changes no tracked content.

### 2c. Nothing guards against this class of regression
A silent, invisible-locally, remote-only plist substitution went unnoticed. Fix: assert in CI that
(a) `xcodegen generate` did not modify the authored plist and (b) the **built** app's plist carries
the required keys and the correct version.

---

## 3. Non-issues verified and dismissed (checked, deliberately not changed)

* `CFBundleDisplayName = "NovaRead TV"` looks like copy-paste from the TV app but
  `composeApp/src/androidMain/res/values/strings.xml` also sets `app_name = NovaRead TV`, so the
  two platforms agree — leaving as-is.
* `:composeApp:syncComposeResourcesForIos` is not defined in any `.kts`; it is provided by the
  Compose Multiplatform Gradle plugin. The shipped bundle contains `compose-resources/`, proving
  the pre-build script succeeds — not a bug.
* The `System.*` / `Dispatchers.IO` common-source iOS compile errors in `iosbuildfailed.zip` are
  already fixed: `grep` finds zero occurrences of either in `composeApp/src/commonMain`.
* `iosbuildfailed.zip` is a stale log from a superseded workflow revision (it references a step
  that no longer exists); no live defect remains from it other than 2b.

---

## 4. Execution checklist

- [ ] **F1** `iosApp/project.yml` — delete the `info:` block; keep `INFOPLIST_FILE: Info.plist`;
      add `GENERATE_INFOPLIST_FILE: NO` so Xcode can never auto-generate over it.
- [ ] **F2** `iosApp/Config.xcconfig` — add placeholder with mock values (no secrets).
- [ ] **F3** `.gitmodules` — declare `server/ma-server` at its real remote.
- [ ] **F4** `.github/workflows/ios-ipa-build.yml` — add a guard step: fail if
      `xcodegen generate` mutated `iosApp/Info.plist`, and fail if the built `.app` plist lacks the
      required keys or has the wrong `CFBundleShortVersionString`/`CFBundleVersion`.
- [ ] **V1** Parse the authored `Info.plist` with `plistlib` to prove well-formedness.
- [ ] **V2** Assert every key `BuildKonfig.ios.kt` / `PlatformAppVersion.ios.kt` reads exists in the
      authored plist.
- [ ] **V3** Static review of the final `project.yml` against the XcodeGen schema.

## 5. Verification limits (stated honestly)
`xcodegen` is macOS-only and is not installed on this machine, so the generator cannot be executed
here. Verification is therefore: plist parse + key-completeness assertions + static schema review +
the new CI guard, which performs the authoritative end-to-end check on the next runner build.
