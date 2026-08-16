# PLAN-TV-WEBPLAYER-UNIFY-V146

Goal: fix TV playback reliability for binge/back-to-back watching and make
embed/webplayer behavior consistent across every server.

User-reported symptoms (2026-08-14):
1. Player says "playing" but video is frozen.
2. Sometimes starts paused; remote OK doesn't play it (must use mouse).
3. After playing with mouse, playhead can break ("says playing, isn't").
4. "Cannot find content" for a title that worked; later loads on its own.
5. Skip works (shows frame at target) but still doesn't play.
6. Embed's own black-and-white play button appears (JS can't reach it).
7. Continue-Watching UI sometimes doesn't appear; when it does the remote
   can't control it and the video keeps loading in the background.
8. Legend of the Seeker routed to an anime server; resume UI appeared before
   the video started and the seek couldn't target the player.
9. Back-to-back episodes unreliable (ep1 → ep2 fails/black).
10. Some tabs play via webplayer, others don't — make everything consistent.

## Root causes (confirmed in code)

- **TvEmbedPlayer.kt**: `AndroidView` factory runs once. When `embedUrl`
  changes (auto-next / NEXT), state resets via `LaunchedEffect(embedUrl)` but
  the WebView keeps showing the OLD episode. Back-to-back is broken.
- **TvPlayerScreen.kt**: `remember(resolvedUrl)` makes a new MediaPlayer but the
  `AndroidView` surface never re-attaches (`attachViews` only in factory) →
  black screen on episode change for direct streams.
- **TvApp.kt playBingeEpisode**: computes `resumeMs` then throws it away — it is
  never stored in `NavigationState`. The player re-reads it and shows the
  resume card DURING stabilization, before the video is seekable → "seek could
  not target the player". Video also keeps loading behind the card.
- **TvEmbedPlayerScreen.kt key handling**: the root Box consumes
  DirectionCenter/Enter even when the resume-card Surface is focused → remote
  cannot press Resume ("remote cannot control it").
- **EMBED_VIDEO_STATE_JS**: picks the FIRST reachable video — often a short ad
  video (`paused=false`) → "says playing but frozen". No stall detection.
- **EMBED_TOGGLE_PLAY_JS / playerSeekToChecked**: target the first `<video>`,
  not the real (longest) one.
- **TvBingeSession.kt resolveBingeEpisode**: VIDLINK_EXO (Server 5) is eagerly
  scraped into a direct stream → routed to LibVLC while every other server uses
  the WebView → the "some play with webplayer, some don't" inconsistency.
- **TvMediaRepository.resolveStreamUrl**: Anivexa/Anivault provider branches
  have zero retry → transient rate-limits / CDN blips surface as
  "cannot find content"; later retry magically works.
- **TvApp.kt advanceBinge**: when a lazy episode fails to resolve it silently
  `return@launch` — nav never changes, no message, no retry.
- **Progress key mismatch**: TvDetailScreen uses
  `${item.id}::${item.title} - ${chapter.title}`; TvApp builds the same string
  from `nav.playTitle`. When chapter titles are blank the keys diverge
  (`"Title - "` vs `"Title"`) → Continue-Watching button/resume "doesn't always
  appear".

## Fixes

### 1. Webplayer consistency (user's explicit ask)
- `resolveBingeEpisode` (TvBingeSession.kt): REMOVE the VIDLINK_EXO
  `extractTvStreamFromEmbed` scrape-to-direct branch. Server 5 (VidLink Exo)
  stays in the WebView embed player like every other server.
- True direct `.m3u8/.mp4/.mpd` streams (AnimePahe, Anivexa direct HLS) still
  classify `isDirect=true` → LibVLC. That's unavoidable (WebView can't play raw
  HLS), but now every EMBED-style server is uniformly routed to TvEmbedPlayerScreen.

### 2. Reload WebView on episode change (back-to-back)
- TvEmbedPlayer.kt: add `LaunchedEffect(embedUrl)` that resets phase and calls
  `webViewRef?.loadUrl(newUrl, headers)` (extract `embedHeadersFor(url)` from
  the factory inline logic). This fixes auto-next / NEXT for every server.

### 3. Pre-player Resume choice (user's suggestion)
- `NavigationState` gains `resumePositionMs: Long? = null` and
  `hasResumeChoiceMade: Boolean = false`.
- `playBingeEpisode`: when `resumeMs > 0`, do NOT navigate. Store
  `pendingResumeNav/pendingResumeMs` and show an AlertDialog:
  **"Continue from mm:ss" / "Watch from Beginning"**.
  - Continue → navigate with `resumePositionMs = resumeMs`.
  - Beginning → `watchProgressStore.clear(progressKey)`, navigate with
    `resumePositionMs = null`.
  - Video does NOT start loading until the user picks (players not composed yet).
- Player screens read `resumePositionMs` from nav (fallback: load from store).
  Re-enable the AUTO-resume in TvEmbedPlayerScreen's poll, gated strictly:
  `ready && duration>0 && currentPosition < 10s && !hasAppliedResume` with
  retries (max 5). Since the user explicitly chose continue, auto-seek is now
  safe and lands only once the video is actually seekable.

### 4. Remote control of in-player resume card (fallback path)
- TvEmbedPlayerScreen key handler: while
  `resumeMs > 0 && !hasAppliedResume && !previewExpired`, return `false` for
  DirectionCenter/Enter/Dpad so the focused Resume/Watch-from-Start Surface
  receives the click. Request focus on the Resume button when the card appears.

### 5. Best-video targeting + stall recovery (fixes "playing but frozen")
- New JS helper in TvEmbedPlayerScreen constants: `findBestVideo(root)` returns
  the video with the LONGEST duration (real content beats short ad videos),
  tie-break by largest area, then readyState. Walk top document + same-origin
  iframes.
- `EMBED_VIDEO_STATE_JS` uses best video; adds `stalled` =
  `!paused && currentTime not advancing across polls` (tracked via
  `window.__novelAppLastT`).
- `EMBED_TOGGLE_PLAY_JS`, `playerSeekToChecked`, `EMBED_UNMUTE_JS` (mute only)
  use findBestVideo.
- `TvEmbedPlayerScreen` poll: if `stalled` for > 8s → one synthetic
  center-touch auto-recover (max 2/episode, resets on embedUrl) + status hint
  "Playback stalled — press OK". Handles the embed's own black/white play
  button.
- `TvEmbedPlayer` READY synthetic touch moves from CENTER to top-left corner
  (20,20) so it still counts as a user gesture (AudioContext resume) but no
  longer toggles play/pause on players that auto-pause on first tap.

### 6. Retry transient resolution failures
- TvMediaRepository: add `private suspend fun <T> retryNullable(times, block)`.
  Wrap Anivexa `resolveStream`, Anivault `resolvePlayerUrl/extractStreamUrl`
  branches with 3 attempts, 800ms/1600ms backoff.
- TvApp `advanceBinge`: on resolve failure set
  `streamErrorDialog = targetTitle`; dialog offers **Retry** (re-runs resolve)
  and **Back**. No more silent no-op.

### 7. Progress-key consistency
- Add `fun tvBingeProgressKey(item: UnifiedSearchResult, chapterTitle: String)`
  in TvBingeSession.kt (blank-title safe). TvDetailScreen + TvApp both use it.

### 8. Anime-server misroute escape hatch
- TvDetailScreen: when `item.isAnime` and every provider attempt yields an
  empty list, render the normal StreamServer row as a fallback with a hint
  ("This title may not be anime — try a server below") so live-action shows
  that get misclassified can still be played on movie/TV servers.

### 9. TvPlayerScreen surface re-attach
- Store the `VLCVideoLayout`; `LaunchedEffect(vlcMediaPlayer)` calls
  `attachViews(layout)` when the player changes so direct-stream episode swaps
  don't black-screen.

## Files touched
- tvApp/.../tv/data/TvBingeSession.kt
- tvApp/.../data/TvMediaRepository.kt
- tvApp/.../tv/ui/TvApp.kt
- tvApp/.../tv/ui/screens/TvDetailScreen.kt
- tvApp/.../tv/ui/screens/TvEmbedPlayer.kt
- tvApp/.../tv/ui/screens/TvEmbedPlayerScreen.kt
- tvApp/.../tv/ui/screens/TvPlayerScreen.kt

## Verification
- No local gradle build (user preference — GitHub Actions builds on push).
- Static compile-gate: every referenced symbol exists; imports updated
  (kotlinx.coroutines.delay in TvMediaRepository).
- Manual repro checklist for the user on the TV:
  1. Watch ep1 → auto-next → ep2 must load in the SAME WebView.
  2. Kill app mid-episode → reopen → episode shows Continue dialog BEFORE
     loading; both choices behave.
  3. Server with ads (vidsrc/Nontongo): progress bar must track the real video,
     not the ad; no more "playing but frozen".
  4. Same title on Server 5 (VidLink Exo) now uses the webplayer like Server 1.
  5. Transient provider failure → auto-retry, or explicit Retry dialog.
