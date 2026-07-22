# Download Fix Summary

## Changes Made

### 1. `MediaDetailScreen.kt` — CinePro-first download resolution
- Rewrote `resolveDownloadableStreamUrl()` to take an optional TMDB context parameter `(tmdbId, mediaType, "season:episode")`
- **Phase 1**: Downloads first try CinePro Core (`resolveAllCineProSources`) which returns direct `.m3u8` URLs via the server proxy. This is the main fix — instead of scraping embed pages (which fail due to WASM), it gets real stream URLs.
- **Phase 2**: Fallback to direct playable URL check
- **Phase 3**: Hidden WebView scraping with 3 attempts and status messages
- Updated `downloadEpisode()` to build TMDB context from the episode URL structure
- Updated movie download to pass TMDB context to `resolveDownloadableStreamUrl`

### 2. `EmbedSuStreamScraper.android.kt` — Comprehensive embed scraper rewrite
- **4 rotating user agents** for WASM/bypass
- **Extended timeout from 15s to 45s**
- **Comprehensive JS injection** (`SCRAPE_JS_TEMPLATE`) detecting:
  - Native HTML5 video
  - **JW Player** (via `jwplayer().getPlaylist()[0].sources[0].file`)
  - **VideoJS** players
  - **Plyr** players
  - **HLS.js** instances (global and per-video)
  - **Shaka Player**
  - **Clappr** player
  - **FlowPlayer**
  - Data attributes (`data-file`, `data-video`, `data-hls`, etc.)
  - **SSR data** (Next.js `__NEXT_DATA__`, Nuxt `__NUXT__`)
  - Script tag content regex matching
- **Stabilization**: latestDetectedUrl with 3s grace period before delivery
- **Delayed polling**: 2s/5s/10s after load to catch late-loading players
- **Source extraction JS**: periodic `GET_SOURCES_JS` polling at 1s/3s/6s/10s/15s

### 3. `LocalFileStorage.android.kt` — Download improvements
- **User agent rotation** in embed scrape bridge (auto-rotates across calls)
- **Connection timeouts increased**: connect=20s (was 15s), read=60s (was 30s)
- Added `Accept-Encoding: identity` for chunked streaming

## Files Modified
| File | Lines changed |
|------|--------------|
| `MediaDetailScreen.kt` | ~50 lines added (resolveDownloadableStreamUrl rewrite + TMDB context) |
| `EmbedSuStreamScraper.android.kt` | ~300 lines replaced (comprehensive rewrite) |
| `LocalFileStorage.android.kt` | ~20 lines changed (bridge + timeouts) |
