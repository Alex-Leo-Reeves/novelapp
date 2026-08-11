# Plan — Full Android parity for iOS IPA + Windows EXE (v1.42)

## Audit result (verified file-by-file)
Both IPA and EXE boot the same common `App()` composable — all 5 tabs
(Discover/NMC/Sports/Read/You), auth/OTP/forgot-password, profile selector,
cloud sync, favorites/history, novel+manga readers, sports, WWE, detail screens.
iOS players are real WKWebView implementations.

## Real gaps found (the "half app" parts)
1. `SherpaNarrationController.{ios,desktop}.kt` = **no-op stubs** — Universal Read &
   Reader voice UI dead on IPA + EXE ("TTS only supported on Android").
2. Desktop players (`AnimePlayerScreen.desktop`, `MaServerPlayerScreen.desktop`,
   `YouTubePlayerScreen.desktop`) = **open-in-browser stubs**, not in-app playback.
3. `saveDownloadedVideo` (desktop+iOS) returns `error="only available on Android"`;
   `extractStreamFromEmbed` = null on both → no offline video, no stream resolution.
4. `App()` fetches update manifest with **Android defaults on every platform** —
   iOS update dialog would open the Android APK.

## Fixes
- **Desktop TTS (Windows)**: SAPI via PowerShell → WAV → javax.sound playback,
  per-paragraph streaming, pause/resume/stop/seek, volume/rate, voice setup Ready.
  `downloadChapterAudio` → real WAV file. Non-Windows stays graceful fallback.
- **iOS TTS**: `AVSpeechSynthesizer` + delegate → real word/paragraph highlighting,
  pause/resume/stop, volume/rate, audio session playback mode.
- **Desktop players**: in-app JavaFX WebView surface (JFXPanel in SwingPanel) for
  embed providers + YouTube + direct mp4/webm; HLS → extract first, honest
  "open in browser" fallback. Preview-limit timer enforced like Android/iOS.
- **Stream extraction**: common parser (`EmbedStreamExtractor.kt`) + per-platform
  fetcher (desktop HttpURLConnection, iOS Ktor-darwin) → returns direct mp4/m3u8.
- **Offline video downloads**: port Android downloader to desktop (JVM,
  progressive + HLS segments/key rewrite → `~/Downloads/NovelApp/videos`) and iOS
  (Ktor darwin → Documents) with graceful failure messages.
- **Update URLs**: `AppReleaseConfig.IOS_DOWNLOAD_URL` +
  `DESKTOP_DOWNLOAD_URL` → Render `site/downloads`; `App()` takes an
  `AppUpdateTarget` passed from each platform entry point; `fetchAppUpdateManifest`
  uses it for the default URL so the dialog opens the right artifact.
- **Version bump** → 1.41 (android/desktop/iOS), `site/app-version.json` synced.

## Verification
- Desktop: `./gradlew :composeApp:compileKotlinDesktop` on Linux; GH Actions
  builds Windows EXE.
- iOS: static verification against Kotlin/Native conventions (no local macOS).
- Push → GitHub Actions builds IPA + EXE; publish to `site/downloads`.

## Completion log
- [x] Audit parity
- [x] commonMain EmbedStreamExtractor
- [x] Desktop JavaFX player surface + 3 player actuals
- [x] Desktop SAPI narration
- [x] Desktop video download + stream extract
- [x] composeApp/build.gradle.kts JavaFX deps (org.openjfx 21.0.4:win, all 6 modules)
- [x] Local desktop compile check (BUILD SUCCESSFUL 5m30s)
- [x] Android compile check — Android app verified unaffected (BUILD SUCCESSFUL 10m21s)
- [x] iOS AVSpeechSynthesizer narration
- [x] iOS video download + stream extract
- [x] AppUpdateTarget + AppReleaseConfig + entry-point wiring
- [x] Version bumps 1.41 + site/app-version.json (release notes synced)
- [x] Fixes: AuthScreen XML leak, SwingPanel→ui.awt import, AppReleaseConfig enum scope
- [x] tvApp confirmed untouched (0 modified files)
- [ ] Push → GH Actions (IPA + EXE)
