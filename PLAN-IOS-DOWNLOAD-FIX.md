# PLAN — iOS Download System + Kotlin/Native Interop Fixes

## Scope
- **Part 1:** Download system updates (server interrogation/selection modal, subscription quota gate, pause/resume/delete lifecycle, grouped episode UI, embedded subtitle routing) for the **iOS target**, using proper Kotlin/Native Foundation/NSFileManager bindings. Android (ExoPlayer/WorkManager) and Windows (Desktop I/O) sources MUST remain untouched.
- **Part 2:** Resolve pending iOS Kotlin/Native build errors & interop mismatches:
  1. `SherpaNarrationController.ios.kt` — AVAudioSession/AVSpeechSynthesizer interop
  2. `LocalFileStorage.ios.kt` — coroutine dispatchers + Foundation API names
  3. UI state type mismatches in `AnimePlayerScreen.ios.kt`, `MaServerPlayerScreen.ios.kt`, `YouTubePlayerScreen.ios.kt`
  4. `.gitmodules` cleanup for `githubanime/Anivexa-API`

## Rules of engagement
- NEVER touch `androidMain` / `desktopMain` source sets — verify with git diff after each change.
- iOS fixes must match the *actual* Kotlin/Native bindings (compiler-verified), not guesses.
- Save progress to memory MCP; update this plan as steps complete.

## Steps
- [ ] Read current iOS source files + download engine (common + iosMain)
- [ ] Read Android/Desktop counterparts to establish the contract to preserve (read-only, no edits)
- [ ] **Part 2.4** — Clean `.gitmodules` (Anivexa-API)  to fix CI checkout warnings
- [ ] **Part 2.2** — Fix `LocalFileStorage.ios.kt` (coroutine wrapping, NSURL/NSFileHandle interop)
- [ ] **Part 2.1** — Fix `SherpaNarrationController.ios.kt` (AVAudioSession setCategory, voiceWithLanguage, delegate signatures)
- [ ] **Part 2.3** — Fix `MutableState.setValue` + numeric type mismatches in the 3 `*.ios.kt` player screens
- [ ] **Part 1** — Server interrogation/selection modal (common download engine + iOS wiring)
- [ ] **Part 1** — Subscription quota gate (5 free / 1k Naira token, 20% watch limit enforcement)
- [ ] **Part 1** — Pause/resume/delete lifecycle + full disk wipe on delete (chunks, temp, .srt)
- [ ] **Part 1** — Grouped episode UI + local playback routing with embedded subtitles (iOS)
- [ ] Verify: `./gradlew :composeApp:compileKotlinIosArm64` (or the task used by CI) green
- [ ] Verify Android & Windows builds untouched (git diff scoped, no androidMain/desktopMain changes)
- [ ] Update memory MCP with final state

## Verification commands
- iOS compile task (from CI workflow): check `.github/workflows/ios-ipa-build.yml`
- Android: `./gradlew :composeApp:assembleDebug` (must stay green, no changes)
- Desktop: `./gradlew :composeApp:compileKotlinDesktop` (must stay green, no changes)
