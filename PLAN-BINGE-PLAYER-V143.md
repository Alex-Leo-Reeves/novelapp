# PLAN: Binge Playback, Remote NEXT, and Movie-End Recommendations (V1.43)

Create: 2026-08-12 · Status: In Progress
Scope: Android TV app (tvApp) + shared Compose app (Android/iOS/Windows)

## 1. What the user wants

1. **Auto-play next episode on the SAME server** after a TV episode ends,
   across Android TV, iOS, and Windows.
2. **Remote NEXT button** → jumps to the next episode (same server).
3. **Movie finished** → shows recommendations on the right side of the screen;
   the remote can choose one, then it opens the detail screen (with server
   selector) — i.e. the standard detail flow.
4. **Server is remembered per session**: the player doesn't know which server
   the stream came from today. We must thread a "binge session" through
   navigation so episodes re-resolve on the same server.

## 2. Architecture findings (verified 2026-08-12)

- **TV flows**: `TvDetailScreen` resolves a stream URL via
  `TvMediaRepository.resolveStreamUrl(item, chapter, selectedServer, selectedDonghuaServer)`
  then hands a bare URL to `TvPlayerScreen` (LibVLC) or `TvEmbedPlayerScreen`
  (WebView) via `TvApp` nav state (`playUrl`/`playTitle`).
- **Player end-detection**:
  - LibVLC `TvPlayerScreen`: has `currentPosition`/`duration` (ms) already.
  - WebView `TvEmbedPlayerScreen`: polls `EMBED_VIDEO_STATE_JS` every 1s giving
    `currentTime`/`duration`/`paused`/`ready` — can detect
    `duration - position < 10s && ready`.
- **Shared app (Android/iOS/Windows)**:
  - `App.kt` is the single orchestrator: `MediaDetailScreen` → `onPlayStream` /
    `onPlayMaEmbedWithLimit` → `animeStreamUrl` (ExoPlayer/WebView) or
    `maServerEmbedUrl` (WebView). The **movie server selector lives in
    MediaDetailScreen**.
  - `AnimePlayerScreen.android` (ExoPlayer) reports position via `onProgress`;
    iOS/desktop `AnimePlayerScreen` are WKWebView/JavaFX WebView with NO
    position callback today.
  - `MaServerPlayerScreen.android/ios/desktop` are WebView players with
    preview-only timers; Android one polls video state but doesn't surface it.
- **Recommendations**: TMDB exposes `/movie/{id}/recommendations` and
  `/tv/{id}/recommendations`. `TMDBMovieScraper` already exists and has a
  working TMDB API key. We'll add a `fetchRecommendations` method reusing the
  existing TMDB auth and returning `UnifiedSearchResult`s (detail flow already
  builds tmdb:// URLs).

## 3. TV implementation (tvApp)

### 3.1 New data layer: `TvBingeSession.kt`  (tvApp/src/main/kotlin/com/alexleoreeves/novelapp/tv/data/)
```kotlin
data class TvBingeEpisode(
    val chapter: Chapter,
    val kind: BingeContentKind,          // MOVIE, TV, ANIME, DONGHUA
    val isDirect: Boolean                // true → TvPlayerScreen, false → TvEmbedPlayerScreen
)
enum class BingeContentKind { MOVIE, TV, ANIME, DONGHUA }

data class TvBingeSession(
    val item: UnifiedSearchResult,
    val episodes: List<TvBingeEpisode>,
    val serverName: String,              // StreamServer.displayName or DonghuaServer.displayName
    val currentIndex: Int,
    val isDonghua: Boolean,
    val isPremium: Boolean,
    val isTVSection: Boolean             // came from SPORTS section or not
)
```
- Immutable copy with `withNext`/`withPrev` helpers; `hasNext`/`hasPrev`.

### 3.2 Rebuild `TvDetailScreen.kt` playback plumbing
- `playMedia(chapter)` builds the full **binge session**:
  - `TvMediaRepository.fetchVideoEpisodes(item)` → sorted chapters.
  - For each chapter compute `resolveStreamUrl(...)` **here** (same server the
    user just picked) → store URL + player kind (direct vs embed) per episode.
  - For a movie → session of 1 episode, kind MOVIE.
- New callbacks from `TvDetailScreen`:
  ```kotlin
  onPlaySession: (TvBingeSession) -> Unit   // replaces/supplements onPlayDirectStream+onPlayEmbed
  onOpenRecommendations: (item: UnifiedSearchResult, fromItem: UnifiedSearchResult?) -> Unit
  ```
  `onOpenRecommendations` navigates to ANOTHER item's detail (for movie-end
  recommendations panel).

### 3.3 Navigation state (`TvApp.kt`)
- Add `bingeSession: TvBingeSession?` to `NavigationState`.
- `PLAYER`/`EMBED_PLAYER` keep `playUrl`/`playTitle` but the player screens
  gain a `bingeSession` param instead of bare URLs. goBack() clears the session.
- On player `onEnded`:
  - if session.hasNext → update nav with `session = session.withNext()` and the
    next episode's URL/title — this is **auto-next on the same server**.
  - else (movie / last episode) → `onMovieEnded` → fetch recommendations
    (TV only) → show recommendations panel.

### 3.4 Player end detection
- `TvPlayerScreen` (LibVLC): add to the TimeChanged listener:
  `if (duration > 0 && duration - position <= 10_000) → onEnded()` (fire once,
  guard `endedFired`).
- `TvEmbedPlayerScreen`: in the 1s poll, `ended = ready && duration > 0 &&
  duration - currentTime <= 10_000` → `onEnded()` fired once.

### 3.5 Remote NEXT / PREV handling
- In both player key handlers, add:
  ```
  Key.MediaNext -> if (bingeSession.hasNext) onNext() else true
  Key.MediaPrevious -> if (bingeSession.hasPrev) onPrev() else true
  ```
- NEXT = `session.withNext()` → recompose player with new URL (same server).
  PREV = same backwards.
- When there is no valid binge session (sports), NEXT/PREV are ignored.

### 3.6 Movie-end recommendations panel (TV)
- New file `TvRecommendationsPanel.kt` — right-side overlay rendered INSIDE
  the player when `onEnded` fires on a MOVIE (or last episode):
  - Right-hand column listing 6-10 recommendations
    (`TmdbSource.fetchRecommendations` — reuse `TMDBMovieScraper` with a new
    method, or call the backend `/api/recommendations` route).
  - Remote: D-pad right focuses the panel, up/down selects, OK opens
    `onOpenRecommendations(item, fromItem)` → detail screen (server selector
    available). Back returns to controls.
- Recommendations get `UnifiedSearchResult` shape → detail screen works
  unchanged.

## 4. Shared app implementation (iOS / Windows / Android)

Shared `App.kt` is the orchestrator; server choice stays in
`MediaDetailScreen`. The binge session is simpler here:

### 4.1 New state in `App.kt`
```kotlin
data class SharedBingeItem(
    val item: UnifiedSearchResult,
    val episodes: List<SharedBingeEpisode>,   // episode + pre-resolved url + embed flag
    val currentIndex: Int,
    val serverName: String
)
```
- Set when `onPlayStream`/`onPlayMaEmbedWithLimit` fires from MediaDetailScreen
  (MediaDetailScreen already knows the server + episodes; it builds the list).
- Auto-next: when the native player reports end (see 4.3), advance
  `currentIndex` → set `animeStreamUrl`/`maServerEmbedUrl` to next episode URL.

### 4.2 `MediaDetailScreen` emits the binge list
- Add an optional callback `onBuildBinge: (SharedBingeItem) -> Unit` invoked
  alongside `onPlayStream`/`onPlayMaEmbedWithLimit` with the pre-computed
  episode list (server preserved, e.g. "Server 1 (VidLink)").
- For the movie case: binge list is just 1 movie.

### 4.3 End detection for shared players
- Android `AnimePlayerScreen` (ExoPlayer): already has `currentPosition`.
  Add `onEnded: () -> Unit` to the expect; call when
  `playbackState == STATE_ENDED`.
- iOS/desktop `AnimePlayerScreen`: WKWebView/JavaFX WebView. Add a polling
  JS-based end detector (iOS: evaluateJavaScript in WKWebView; desktop: JFX
  WebView executor) mirroring the TV embed poll. Where not feasible, use a
  wall-clock fallback: if no end event in `duration*1.05`, treat as ended.
- `MaServerPlayerScreen` (WebView) on Android/iOS/desktop: same JS poll for
  `ended` on the embedded <video>.

### 4.4 Movie recommendations (shared)
- New common `MovieEndScreen.kt` (Compose Multiplatform, works on iOS/Windows/
  Android): full-screen end card → "More like this" horizontal list on the
  right. Selecting one opens `MediaDetailScreen` for that `UnifiedSearchResult`
  (server selector flows naturally).
- Data: new `TMDBMovieScraper.fetchRecommendations(tmdbType, tmdbId)`.

## 5. Backend

- New endpoint `GET /api/tmdb/recommendations?type=movie|tv&id={id}` on
  `server/index.js` proxying TMDB recommendations (server already has TMDB
  keys / CinePro proxy patterns). Returns `UnifiedSearchResult`-shaped items.
- The TV + shared clients `fetchRecommendations(...)` via this route, with
  direct-TMDB fallback in `TMDBMovieScraper`.

## 6. Free-preview guardrails

- Auto-next must NOT bypass the free preview cap. For free users:
  - episodes all share the same `previewLimitMs` (20 min movie cap / 20%
    episodic fraction) applied to each new episode by the player as it loads.
  - Next replay would advance into the same cap — acceptable.
- End-detection for free users happens BEFORE the preview gate so no bypass.

## 7. Files touched

| File | Change |
|---|---|
| `tvApp/.../data/TvBingeSession.kt` (new) | binge session model |
| `tvApp/.../ui/screens/TvDetailScreen.kt` | build+emit session; recommendations callback |
| `tvApp/.../tv/ui/TvApp.kt` | nav state + auto-next + rec panel wiring |
| `tvApp/.../ui/screens/TvPlayerScreen.kt` | end detect + NEXT/PREV + onEnded |
| `tvApp/.../ui/screens/TvEmbedPlayerScreen.kt` | same |
| `tvApp/.../ui/screens/TvRecommendationsPanel.kt` (new) | right-side recs |
| `composeApp/.../data/MovieScrapers.kt` | `fetchRecommendations` |
| `composeApp/.../ui/MediaDetailScreen.kt` | emit binge list + open end screen |
| `composeApp/.../ui/MovieEndScreen.kt` (new) | shared movie-end recs |
| `composeApp/.../ui/AnimePlayerScreen.kt` + 3 actuals | `onEnded` param + end detect |
| `composeApp/.../ui/MaServerPlayerScreen.kt` + 3 actuals | end detect |
| `composeApp/.../App.kt` | binge session state, auto-next, end screen |
| `server/index.js` | `/api/tmdb/recommendations` route |

## 8. Acceptance criteria

- [ ] TV: episode ends → next episode auto-plays on the same server.
- [ ] TV: remote NEXT/PREV jumps episode on the same server.
- [ ] TV: movie ends → right-side recommendations; OK opens detail with server
      selector.
- [ ] Android/iOS/Windows: same auto-next behavior on same server.
- [ ] Movie-end recommendations on iOS/Windows.
- [ ] iOS IPA + Windows EXE builds still compile (GH Actions).
