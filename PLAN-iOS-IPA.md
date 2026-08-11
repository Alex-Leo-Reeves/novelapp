# PLAN — Full Android app → signed/unsigned IPA via GitHub Action

## Goal
Make the GitHub Action produce an IPA that runs **the same KMP app (composeApp)** that Android builds —
not the reduced native SwiftUI app that currently sits in `iosApp/iosApp`.

## Why the current IPA is wrong
- `iosApp/iosApp/*.swift` is a *separate reduced SwiftUI app* (its own API client, 6 content kinds, no downloads, no sports, no TTS) and `project.yml` builds it.
- The workflow expects an `AnimeNovelManga` scheme which `project.yml` no longer generates → build would fail.
- The full KMP app already has `iosMain` actuals + `MainViewController()` + `NovelAppIosBridge`, but:
  1. `AnimePlayerScreen.ios.kt` signature is stale (has `isAnime`, missing `contentKind`/`subtitlesJson`).
  2. `SherpaNarrationController.ios.kt` is missing `downloadChapterAudio`.
  3. `MaServerPlayerScreen.ios.kt` and `YouTubePlayerScreen.ios.kt` actuals are missing entirely.
  4. `project.yml` references `../kokoro-assets/kokoro` (gitignored, does not exist) → XcodeGen fails.

## Steps
- [x] Investigate repo layout, iOS source set, workflow, and expect/actual inventory
- [ ] Fix `AnimePlayerScreen.ios.kt` signature to match current expect
- [ ] Add `downloadChapterAudio` to `SherpaNarrationController.ios.kt`
- [ ] Add `MaServerPlayerScreen.ios.kt` actual (WKWebView embed player)
- [ ] Add `YouTubePlayerScreen.ios.kt` actual (WKWebView)
- [ ] Replace `iosApp/iosApp/*.swift` with Compose Multiplatform host (hosts `MainViewController()`)
- [ ] Rewrite `iosApp/project.yml`: target `NovaRead TV`, embed+link `ComposeApp` framework, drop missing kokoro folder
- [ ] Add `iosApp/Config.xcconfig` for API keys consumed by `BuildKonfig.ios`
- [ ] Rewrite `.github/workflows/ios-ipa-build.yml`: Gradle framework build → xcodegen → xcodebuild archive/export → publish IPA (signed path + unsigned fallback path)
- [ ] Final static verification (no compile blockers left in iosMain)

## Integration contract
- Framework name: `ComposeApp` (static, `isStatic = true`)
- Build phase: `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`
- Swift entry: `MainViewControllerKt.MainViewController()`
- Info.plist API keys resolved via `Config.xcconfig` build settings
- Output: `site/downloads/novelapp-ios.ipa` + GitHub Actions artifact
