# Fix Plan — v1.41 (TV + Android)

Goal: make the TV app actually work (real content, server picker, fullscreen player, login required, profile selector, free-preview enforcement, sane back navigation) and close the same preview-bypass hole on Android.

## Server (`server/index.js`)

1. **Real episodes for all video content**
   - Add `tmdbEpisodes(detailUrl)`:
     - `tmdb://tv/{id}` → fetch `/tv/{id}` seasons, then each `/tv/{id}/season/{s}` → episodes as `Chapter{title: "S{s}E{e} - {name}", url: "tmdb-episode://{id}/{s}/{e}", chapterNumber: e}`.
     - `tmdb://movie/{id}` → single `Chapter{title: "Full Movie", url: "tmdb-movie://{id}", chapterNumber: 1}`.
     - Uses same TMDB key logic as `tmdbItems` (env token/key).
   - In `contentChapters(kind, detailUrl, title, sourceName)`:
     - novels → swift scrapers (unchanged)
     - manga → mangadex (unchanged)
     - **all video kinds (movies/kdrama/cartoon/classic/nigerian/donghua/anime)** → `tmdbEpisodes(detailUrl)`, fall back to `[]` (never synthetic).
   - Keep `syntheticChapters` for novels only as last resort.

2. **Donghua servers in `watchRoutes`**
   - When `normalizedKind === "donghua"` and we have a tmdb match, prepend donghua-friendly embeds:
     - Nontongo: `https://nontongo.win/embed/{movie|tv}/{id}(/{s}/{e})`
     - AutoEmbed: `https://player.autoembed.cc/embed/{movie|tv}/{id}(/{s}/{e})`
     - EmbedSu: `https://embed.su/embed/{movie|tv}/{id}(/{s}/{e})`
   - These URLs are the same ones the Android app uses for donghua, so TV playback behaves identically.

3. **Free preview limits already server-side** (`MOVIE_FREE_PREVIEW_MS`, `EPISODIC_FREE_FRACTION`) — unchanged, but the **TV player must enforce them on WebView playback too**.

4. **Version/URL updates**
   - `ANDROID_TV_APK_URL` → v1.41.
   - Keep `ANDROID_APK_URL` aligned with the Android build (v1.41) if able; otherwise leave Android URL as-is and only bump TV (Android APK is distributed via `site/app-version.json`).

## TV App (`tvApp/`)

1. **Auth is mandatory**
   - Remove `onDismiss` guest bypass in `TvApp` + remove "Continue as Guest" button in `TvAuthScreen`.
   - Splash with no account → AUTH. Back on AUTH/PAIR exits (never skips to home).

2. **Profile selector (new screen `TvProfileScreen`)**
   - `TvScreen.PROFILE` added to enum.
   - After login/create/pair/splash-with-account → PROFILE first, then HOME with `selectedProfile` set.
   - Profiles: Main (from username) + Kids (kids mode), D-pad friendly tiles.
   - Sidebar You section + `TvYouScreen` get a "Switch Profile" action that returns to PROFILE.
   - `NavigationState` gains `selectedProfile`.

3. **Back-button logic (one press = one step back)**
   - `TvApp.goBack()`:
     - PROFILE → exit (nothing before it) — actually PROFILE → AUTH is safer: back = go to login again.
     - DETAIL → HOME (existing)
     - PLAYER → DETAIL or section (existing)
     - READER/MANGA → DETAIL (existing)
     - AUTH → exit
   - `TvHomeScreen`: add `onKeyEvent` for `Key.Back`:
     - search open → close search
     - section != HOME → `onBackHome()`
     - else → fall through (TvApp handles exit)
   - Server picker overlay in `TvDetailScreen`: intercept back → close picker first.

4. **Real episodes + server picker popup for everything**
   - `TvDetailScreen` `kind` uses `item.mediaKind.lowercase()` when present (so donghua stays "donghua", not "movie").
   - Chapter click: **video chapters → `playViaPicker(ch)`** (opens `TvServerPickerOverlay`), NOT the novel reader.
   - Non-video chapters unchanged (novel/manga).
   - `playViaPicker`: anime tries `fetchAnimeStream` direct first, else picker. All other video → picker.
   - Movies without chapters still show `ServerListPanel`.

5. **Fullscreen player, no sidebar**
   - Already routed fullscreen in current code; keep it. Player decides ExoPlayer vs WebView directly from the resolved URL (no waiting for ExoPlayer error).
   - WebView gets fullscreen layout, JS enabled, mediaPlaybackRequiresUserGesture=false.

6. **Free-preview enforcement on WebView playback**
   - Track a `WebView` ref; when free user and WebView path is active:
     - Poll JS for `video.currentTime/duration` every 1s.
     - Episodic: cap at `TV_EPISODIC_FREE_FRACTION` (20%) of detected duration.
     - Movie / undetected duration: cap at `TV_MOVIE_FREE_PREVIEW_MS` (20 min) wall-clock.
     - On expiry: pause via JS + show the existing "Free Preview Ended" gate with Go Premium.
   - ExoPlayer path already enforces (existing code).

7. **TV You screen**
   - Add "Switch Profile" button (calls new `onSwitchProfile`).
   - Keep subscription, stats, sign-out.

8. **Version bump**
   - `tvApp/build.gradle.kts` → versionCode 41, versionName "1.41".

## Android App (`composeApp/`)

1. **WebView preview enforcement (`MaServerPlayerScreen.android.kt`)**
   - Add optional `previewLimitMs: Long? = null` param (default null = no cap).
   - When non-null and free user: wall-clock timer + JS `currentTime` polling; on expiry pause video and show a "Free preview ended" gate overlay with "Go Premium" + "Back".

2. **Wire limits through `MediaDetailScreen`**
   - Add `onPlayMaEmbed(embedUrl, title, previewLimitMs)` (default overload keeps old callers working).
   - Movie embeds pass `freeMoviePreviewMs` when `!isPremium`.
   - Episodic embed playback passes a 20-min cap for free users (duration unavailable in webview; hard cap guarantees no full episode/movie for free).
   - `App.kt` passes the value through to `MaServerPlayerScreen`.

3. **Version bump**
   - `composeApp/build.gradle.kts` → versionCode/versionName 1.41 (or matching current scheme).

## Verification

- Start server locally? (Depends on env; otherwise validate via syntax check `node --check server/index.js`.)
- `grep`-verify no leftover synthetic chapter path for video in `contentChapters`.
- Build TV: `./gradlew :tvApp:assembleDebug`
- Build Android: `./gradlew :composeApp:assembleDebug`
- Fix all compile errors; verify APKs produced.
