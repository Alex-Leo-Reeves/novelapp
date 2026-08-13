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
- [ ] Create plan MD + read all affected files
- [ ] MovieScrapers.kt: replace `String.format`/`java.*` with pure-Kotlin helpers (identical output across platforms)
- [ ] WweSource.kt: replace `java.net.URLEncoder` with KMP-safe URL-encoding helper
- [ ] AnimePlayerScreen.ios.kt: fix NSURL/header setup
- [ ] MaServerPlayerScreen.ios.kt: fix NSURL/header setup
- [ ] YouTubePlayerScreen.ios.kt: fix NSURL/header setup
- [ ] SherpaNarrationController.ios.kt: AVAudioSession / AVSpeechSynthesis bindings, `isSpeaking()`/`isPaused()`, coroutine cancel, delegate signature
- [ ] LocalFileStorage.ios.kt: suspend propagation + NSFileHandle removal
- [ ] Verify Android unaffected: `:composeApp:assembleDebug` (or compileDebugKotlinAndroid) green
- [ ] Verify common code KMP-safe: `:composeApp:compileCommonMainKotlinMetadata` green
- [ ] Update memory + plan checkboxes
