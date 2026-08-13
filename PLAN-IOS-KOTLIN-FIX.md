# PLAN — Fix Kotlin/Native iOS compile (`:composeApp:compileKotlinIosArm64`)

## Goal
Get the GitHub Action iOS IPA build past `:composeApp:compileKotlinIosArm64`.
Every fix must be KMP-safe so the **Android build (androidMain + tvApp) is unaffected**.

## Root causes (per diagnostic)
1. `MovieScrapers.kt` (commonMain) uses JVM-only `String.format(...)` / `java.*`.
2. `WweSource.kt` (commonMain) uses `java.net.URLEncoder.encode(...)`.
3. iOS WebView screens use outdated Kotlin/Native bindings (`allHTTPHeaderFields`, bad `NSURL` fallback chain).
4. `SherpaNarrationController.ios.kt` AVFoundation/speech API signature mismatches + coroutine cleanup.
5. `LocalFileStorage.ios.kt` suspend misuse + `NSFileHandle` append pattern.

## Steps
- [x] Create plan MD + read all affected files
- [x] MovieScrapers.kt: replace `String.format`/`java.*` with pure-Kotlin helpers (identical output across platforms)
- [x] WweSource.kt: replace `java.net.URLEncoder` with KMP-safe URL-encoding helper (`io.ktor.http.encodeURLParameter`)
- [x] AnimePlayerScreen.ios.kt: fix NSURL/header setup (`setValue(forHTTPHeaderField:)`, null-safe fallback)
- [x] MaServerPlayerScreen.ios.kt: fix NSURL/header setup
- [x] YouTubePlayerScreen.ios.kt: fix NSURL/header setup
- [x] SherpaNarrationController.ios.kt: AVAudioSession / AVSpeechSynthesis bindings, `isSpeaking()`/`isPaused()`, coroutine cancel (`cancelChildren` import), delegate signature
- [x] LocalFileStorage.ios.kt: suspend propagation (`suspend fun` on HLS/stream helpers)
- [ ] Verify Android unaffected: `:composeApp:assembleDebug` (or compileDebugKotlinAndroid) green
- [ ] Verify common code KMP-safe: `:composeApp:compileCommonMainKotlinMetadata` green
- [x] Update memory + plan checkboxes
- [x] Commit + push all fixes (`8a2808b`) so CI IPA build picks them up

## Status
All Kotlin/Native compile blockers identified in the diagnostic are fixed and pushed to `origin/main`.
Local runtime builds were NOT run by instruction — CI iOS IPA workflow is the verification path.
