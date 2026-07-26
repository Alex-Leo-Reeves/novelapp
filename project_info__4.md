# NovelApp Streaming Architecture — Comprehensive Analysis

## Summary

This report covers NovelApp's complete video/streaming pipeline — how every type of content (movies, TV, anime, football, WWE) gets resolved into a playable stream, what the "Server 1-5" system actually does, and why specific servers fail. The architecture is a multi-layered system with embed WebViews, headless WebView scrapers, backend aggregators (CinePro Core), and direct ExoPlayer playback — plus a stabilization safety net to handle the chaotic JavaScript behavior of embed providers.

---

## The Two Player Types

The entire streaming system uses exactly **two** player screens:

### 1. `MaServerPlayerScreen` — WebView Embed Player
- **File**: `composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/ui/MaServerPlayerScreen.android.kt`
- **What it does**: Loads an embed URL (vidsrc.to/embed/movie/123, vidlink.pro/movie/123, nontongo.win/embed/...) in a full-screen WebView
- **How it plays video**: The embed provider's own JavaScript creates a video player inside the WebView
- **Ad blocking**: `shouldInterceptRequest` blocks ~50 known ad/tracker domains by returning empty responses
- **Stabilization**: After page load, injects JavaScript that:
  - Forces all videos to muted + playsinline
  - **Suppresses rapid play/pause/mute toggles** (embed providers often play/pause rapidly during the first 10 seconds while resolving streams)
  - Holds a loading overlay for a minimum of 8 seconds (STABILIZING phase)
  - After 8 seconds, un-mutes and plays cleanly
  - If no video appears after 15 seconds, auto-reloads (up to 2 attempts)
- **Used for**: Servers 1-4 (VidLink, VidSrc, Nontongo, CinePro), football embed streams, WWE embed streams

### 2. `AnimePlayerScreen` — ExoPlayer Native Player
- **File**: `composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/ui/AnimePlayerScreen.android.kt`
- **What it does**: Plays a direct .m3u8 / .mp4 URL using Android's Media3 ExoPlayer
- **How it gets the stream**: If the URL is already a direct playable media URL (.m3u8, .mp4), it plays it directly. If it's an embed URL (e.g. vidlink.pro/...), it launches a **headless WebView** (`EmbedSuStreamScraper`) to load the embed page and intercept network traffic for .m3u8 URLs
- **Headless scraping**: `extractStreamFromEmbed()` in `EmbedSuStreamScraper.android.kt` creates a hidden WebView, loads the embed URL, injects comprehensive JS to detect video sources from all major players (JWPlayer, VideoJS, Plyr, HLS.js, Shaka, Clappr, FlowPlayer), and captures .m3u8 URLs from console logs or `shouldInterceptRequest`
- **Subtitles**: Supports Sub 1 (normal/embedded), Sub 2 (OpenSubtitles), Sub 3 (SubDL) — fetched from the backend server
- **Used for**: Server 5 (VidLink ExoPlayer), direct CinePro streams, football direct .m3u8 streams, anime consumet streams

---

## The 5-Server System (for TMDB movies/TV/anime/donghua)

Defined in `MaServerSource.kt`:

| Server | Name | Type | What It Actually Does |
|--------|------|------|----------------------|
| **Server 1** | VidLink | WebView | Loads `vidlink.pro/movie/{id}` or `vidlink.pro/tv/{id}/{s}/{e}` in MaServerPlayerScreen |
| **Server 2** | VidSrc | WebView | Loads `vidsrc.to/embed/movie/{id}` or `vidsrc.to/embed/tv/{id}/{s}/{e}` in MaServerPlayerScreen |
| **Server 3** | Nontongo | WebView | Loads `nontongo.win/embed/movie/{id}` or `nontongo.win/embed/tv/{id}/{s}/{e}` in MaServerPlayerScreen — **known for excessive ads and popunders** that bypass the ad blocker |
| **Server 4** | CinePro | Mixed | `buildEmbedUrl` returns `""`. MovieDetailScreen calls `resolveAllCineProSources()` → `POST /api/content/cinepro/sources` → proxies to CinePro Core → If sources found, plays in ExoPlayer. If not, "Server unavailable" |
| **Server 5** | VidLink ExoPlayer | ExoPlayer | Same vidlink.pro URL as Server 1, but routes through AnimePlayerScreen's headless WebView scraper to extract a direct .m3u8 and play natively |

---

## Football Streaming Pipeline

1. User taps a match → `FootballMatchScreen` appears
2. User taps "Watch" → `resolveStream()` ladder approach:
   - **Step 1 (Server 2)**: `POST /api/football/direct-stream` — server scrapes streaming aggregators (streamed.su, sportshub.stream, crackstreams, scorebat, sportsurge, embed.su) for .m3u8 URLs
   - **Step 2 (Fallback)**: Builds embed URLs → MaServerPlayerScreen

---

## Your Specific Problems — Root Causes

### 1. ScoreBat takes you to their website instead of playing the video

**Root cause**: The URL is:
```
https://www.scorebat.com/embed/livescore/?search=TeamA+vs+TeamB
```
This is ScoreBat's **search results page** (live scores list), not a video player. The app doesn't find the specific video ID for the match before building the embed URL.

**What ScoreBat's actual embed looks like**: Their iframe-based embed uses URLs like `https://www.scorebat.com/embed/video/{videoId}` — this would render a proper video player inside your WebView. Your MaServerPlayerScreen can absolutely load this — it's designed for exactly this kind of embed.

**Why it doesn't work now**: The code never scrapes ScoreBat to find the video ID. It just builds a generic search URL.

**Could we fix this?** Yes. You'd need to:
1. Scrape ScoreBat's search results (or use their API if available) to find the highlight/replay video ID for that specific match
2. Build `https://www.scorebat.com/embed/video/{videoId}`
3. Load it in MaServerPlayerScreen — ScoreBat's own player would render inside the WebView

**BUT**: ScoreBat content is **highlights/replays**, not live full-match streams. For live matches, the Server 2 (direct .m3u8 scraping) approach is the correct one — it just doesn't always find a stream.

### 2. Server 4 (CinePro) — "Server unavailable"

**Root cause**: CinePro Core is on Render's free tier. Five independent failure points:

1. **Cold start timeout**: After 15+ minutes idle, Render spins it down. First request takes 30-60 seconds to wake up. The main server's timeout is **20 seconds** — so it fails before CinePro finishes waking.

2. **Timeout mismatch**: The main server does `fetchWithTimeout(url, {}, 20000)` on CinePro. But CinePro needs up to 60 seconds cold-start. **The timeout is 3x shorter than the cold start.**

3. **Keepalive failures**: The main server pings CinePro every 5 minutes (`scripts/cinepro-keepalive.js`). If the pings fail (Render network blip, restart), CinePro goes to sleep and the next user hits the cold start.

4. **Empty sources**: Even when warm, CinePro might return `{ sources: [] }` if none of its 17 providers have the content. The UI shows "Server unavailable" because no embed URL is generated.

5. **Stale URL correction**: The code auto-corrects `cinepro-core.onrender.com` → `cinepro-core-esmh.onrender.com`. If CinePro was redeployed to a different URL, the correction won't work.

**The error message in the app is misleading** — it says "Server unavailable" but the actual failures could be timeout, empty response, or CinePro Core being down entirely.

### 3. Server 3 (Nontongo) — Too many ads, won't play

**Root cause**: Nontongo loads as a raw embed in MaServerPlayerScreen. The ad blocker blocks ~50 known ad domains, but Nontongo specifically:
- **Serves ads from its own domain** (same-origin — the ad blocker can't block by domain without breaking the video)
- **Uses popunder scripts** that partially bypass URL-level blocking
- **Creates dynamic overlays via JavaScript** after the page loads
- **Forces ad-interaction timers** (click to close, wait X seconds) before the video starts
- **Multiple redirect chains** before reaching the actual video source

The ad blocker has no defense against same-origin JavaScript-generated overlays. The only real fix is to **not use Nontongo as a WebView embed** — instead, scrape it for a direct .m3u8 stream (like Server 5 does for VidLink) and play in ExoPlayer.

### 4. Server 5 (VidLink ExoPlayer) — You want it removed

Server 5 uses the exact same URL as Server 1 (`vidlink.pro/movie/{id}`) but routes through the headless WebView scraper instead of the visible WebView. It fails because:
- VidLink's WASM/bot detection challenges (Cloudflare, anti-bot) block the headless WebView
- The scraper has a 45-second timeout but bot challenges can take longer
- If Server 1 works (visible WebView) but Server 5 doesn't, it's purely because VidLink can distinguish a visible WebView from a headless one

---

## What Would Need To Change (If You Want to Fix These)

### For ScoreBat direct embed:
- Scrape ScoreBat search results server-side (or parse the scorebat_feed.json in the project root) to find match video IDs
- Build direct video embed URLs instead of search URLs

### For CinePro (Server 4):
- Increase the timeout from 20 seconds to 60 seconds in `server/index.js` `cineproAllSources()`
- Check if CinePro Core is actually running at `cinepro-core-esmh.onrender.com` — deploy a fresh instance if not
- Improve the error message to distinguish "cold starting" vs "empty results" vs "service down"

### For Nontongo (Server 3):
- Replace it with a direct .m3u8 scraping approach (remove the WebView embed, scrape for direct stream, play in ExoPlayer)
- Or replace it with a different embed provider (e.g. `vidsrc.me` fallback which has fewer ads)

### For Server 5:
- Remove it entirely if Server 1 already covers VidLink (the same content through a different player)

---

## Complete Reference: All Stream URLs Used

### TMDB Movie
```
Server 1:  https://vidlink.pro/movie/{tmdbId}
Server 2:  https://vidsrc.to/embed/movie/{tmdbId}
Server 3:  https://nontongo.win/embed/movie/{tmdbId}
Server 4:  POST /api/content/cinepro/sources → CinePro Core
Server 5:  https://vidlink.pro/movie/{tmdbId} (headless scraped)
```

### Football
```
Direct:    POST /api/football/direct-stream (scraped .m3u8)
Embed:     https://www.scorebat.com/embed/livescore/?search={teams}
           https://footybite.to/?s={search}
           https://v2.sportsurge.net/search?query={search}
```

### WWE
```
Embed:  Scraped from watchwrestling.ae post pages (doodstream, vidmoly, streamtape, voe.sx)
        Loaded in MaServerPlayerScreen
Direct: Scraped .m3u8 from same pages + streamed.su
        Loaded in AnimePlayerScreen
```

The full report with all technical details and file references has been saved to `project_info__3.md` in the project root.

To actually implement any of these fixes, please switch to **Act Mode** using the mode selector at the bottom of the chat — I can't write code in Explore Mode, but this analysis provides the exact blueprint for every change needed.