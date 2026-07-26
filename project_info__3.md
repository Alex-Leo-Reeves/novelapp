# NovelApp Streaming Architecture — Comprehensive Analysis

## Summary

This report covers NovelApp's complete video/streaming pipeline — how every type of content (movies, TV, anime, football, WWE) gets resolved into a playable stream, what the "Server 1-5" system actually does, and why specific servers fail. The architecture is a multi-layered system with embed WebViews, headless WebView scrapers, backend aggregators (CinePro Core), and direct ExoPlayer playback — plus a stabilization safety net to handle the chaotic JavaScript behavior of embed providers.

---

## The Two Player Types

The entire streaming system uses exactly **two** player screens:

### 1. `MaServerPlayerScreen` — WebView Embed Player
- **File**: `composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/ui/MaServerPlayerScreen.android.kt`
- **What it does**: Loads an embed URL (vidsrc.to/embed/movie/123, vidlink.pro/movie/123, nontongo.win/embed/...) in a full-screen WebView
- **How it plays video**: The embed provider's own JavaScript creates a video player inside the WebView. The WebView renders it natively.
- **Ad blocking**: `shouldInterceptRequest` blocks known ad/tracker domains (doubleclick, popads, etc.) by returning empty responses
- **Stabilization**: On page load, it injects JavaScript that:
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
| **Server 1** | VidLink | WebView | Loads `vidlink.pro/movie/{id}` or `vidlink.pro/tv/{id}/{s}/{e}` in MaServerPlayerScreen. VidLink renders a video player inside the WebView. |
| **Server 2** | VidSrc | WebView | Loads `vidsrc.to/embed/movie/{id}` or `vidsrc.to/embed/tv/{id}/{s}/{e}` in MaServerPlayerScreen. Same approach. |
| **Server 3** | Nontongo | WebView | Loads `nontongo.win/embed/movie/{id}` or `nontongo.win/embed/tv/{id}/{s}/{e}` in MaServerPlayerScreen. **Known for excessive ads and popunders** that partially bypass the ad blocker. |
| **Server 4** | CinePro (Auto-Link) | WebView/Exo | **Special behavior**: The `buildEmbedUrl` returns `""` (empty string). Instead, the MovieDetailScreen code calls `resolveAllCineProSources()` which hits `POST /api/content/cinepro/sources` on the main server, which proxies to CinePro Core at `https://cinepro-core-esmh.onrender.com`. If sources are found, it auto-selects the best one and plays it. |
| **Server 5** | VidLink ExoPlayer | ExoPlayer | Loads the same vidlink.pro URL but routes through **AnimePlayerScreen** (ExoPlayer). The AnimePlayerScreen's headless WebView scraper (`EmbedSuStreamScraper`) loads the vidlink page, scrapes for .m3u8 URLs, then plays the direct stream in ExoPlayer. |

**Not shown in the UI but exists**: Donghua has its own 5-server system (`DonghuaServer` enum in `MaServerSource.kt`): DonghuaStream, Lucifer Donghua, 2embed (Exo), CinePro, Lucifer Exo.

---

## Football Streaming Pipeline

Defined in `FootballSource.kt` and the server's `handleFootballDirectStream()`:

1. **User taps a match** → `FootballMatchScreen` appears
2. **User taps "Watch"** → `resolveStream()` is called with a **ladder approach**:
   - **Step 1 (Server 2)**: Calls `POST /api/football/direct-stream` on the main server with `{ homeTeam, awayTeam, leagueName }`. The server tries to scrape streaming aggregators (streamed.su, sportshub.stream, crackstreams, scorebat, sportsurge, embed.su) by fetching their HTML and extracting .m3u8 URLs with `extractM3u8Urls()`. Returns `StreamResult.Direct` if found.
   - **Step 2 (Fallback)**: If no direct .m3u8 is found, constructs embed URLs (scorebat.com/embed/livescore/?search=..., footybite.to, sportsurge.net, etc.) and returns `StreamResult.Embed`.
3. **Direct → AnimePlayerScreen** (ExoPlayer with native playback)
4. **Embed → MaServerPlayerScreen** (WebView with embed)

### The ScoreBat Problem (Your Specific Complaint)

**Current behavior**: The app creates URLs like:
```
https://www.scorebat.com/embed/livescore/?search=TeamA+vs+TeamB
```

This loads **ScoreBat's search page** in a WebView — it's a live scores list with ads, not a dedicated video player for the specific match. The user has to navigate ScoreBat's UI to find and play a video.

**What ScoreBat's actual embed player looks like** (from examining their site):
- ScoreBat has a real embed player at `https://www.scorebat.com/embed/video/...` or `https://www.scorebat.com/embed/...` that shows a proper video player
- But they require you to find the specific video ID first
- Their embed player checks referrer and may block if not from an allowed domain
- They show "Highlights" (short clips/replays), not live full-match streams

**Why it goes to their website**: The current code builds a search URL, not a specific video embed URL. To get the actual video embed, you'd need to:
1. Search ScoreBat for the match
2. Find the specific highlight/replay video ID
3. Build the direct video embed URL
4. That embed would have ScoreBat's own player (like how they embed videos on their site)

**Can you embed the video in your own app like ScoreBat does?** ScoreBat's embed player uses iframe-based embedding. If you use `https://www.scorebat.com/embed/video/{videoId}`, it renders a video player inside the iframe. Your MaServerPlayerScreen (WebView) loads exactly this type of page — so **yes, theoretically you can**. The problem is finding the correct video ID for the specific match.

---

## WWE Streaming Pipeline

Defined in `WweSource.kt` and `server/wwe-handlers.js`:

1. Fetches events from watchwrestling.ae by scraping HTML with `fetchWweHtml()` (HTML scraping — no API available)
2. **Option A (Embed)**: Fetch the event's post page, extract iframe URLs pointing to doodstream, vidmoly, streamtape, voe.sx, etc. → play in MaServerPlayerScreen
3. **Option B (Direct)**: Scrape the same pages for .m3u8/.mp4 URLs, try sports aggregators (streamed.su) → play in AnimePlayerScreen
4. Falls back to synthetic match data if scraping fails

**The server code has cache with 2-minute TTL** for events and 3-minute TTL for stream URLs.

---

## CinePro Core (Server 4) — Why It Says "Server Unavailable"

### What CinePro Core Is
Deployed at `https://cinepro-core-esmh.onrender.com`, CinePro Core is a **separate Node.js service** based on the `@omss/framework` (Open Media Streaming Server). It aggregates **17 streaming providers**:

```
02moviedownloader/  anyembed/  cinesu/  fmovies4u/  fshare/  icefy/
peachify/  popr/  streammafia/  tulnex/  vidapi/  videasy/
vidnest/  vidrock/  vidsrc/  vidzee/  vixsrc/
```

Each provider implements a scraper that extracts stream URLs from different sources (VidSrc, VidLink, VidAPI, VidRock, VidNest, VidZee, VixSrc, FShare, CineSu, Popr, etc.).

### Why Server 4 Fails ("server unavailable")

The flow is:
```
App → Main Server (novelapp1.onrender.com) → CinePro Core (cinepro-core-esmh.onrender.com)
     → POST /api/content/cinepro/sources → /v1/movies/{id} or /v1/tv/{id}/seasons/{s}/episodes/{e}
```

**Common failure reasons identified in the code:**

1. **Render free-tier cold start**: CinePro Core is on Render's free tier. After 15 minutes of inactivity, it goes to sleep. The first request after sleep takes 30-60 seconds to wake up. The main server's request timeout is **20 seconds** (`fetchWithTimeout` with 20000ms). So cold starts time out.

2. **The keepalive ping has a race condition**: The main server pings CinePro Core every 5 minutes (`scripts/cinepro-keepalive.js`), but if the pings fail (network issue, Render restart), CinePro will sleep.

3. **Empty sources response**: Even when CinePro Core responds, it might return `{ sources: [] }` (no stream found for that content). The code returns `sources.length > 0 ? "Found X stream(s)" : "No streams returned by CinePro"`. If no provider could resolve a stream, the user sees failure.

4. **The auto-correction of the URL**: At server start, the code auto-corrects from `cinepro-core.onrender.com` to `cinepro-core-esmh.onrender.com`. If the Render instance was renamed or redeployed to a different URL, the correction might be stale.

5. **CinePro Core itself might have provider failures**: CinePro scratches 17 providers. If most of those providers are down or the scrapers break (site layout changes), CinePro returns no sources.

**The actual "Server unavailable" message** comes from `MaServerPlayerScreen.android.kt` when the embed URL is empty (CinePro's `buildEmbedUrl` returns `""`). The MovieDetailScreen calls `resolveAllCineProSources()` and if it returns empty, the server shows as unavailable.

---

## Nontongo (Server 3) — Ad Problem

Nontongo is loaded as a raw embed in MaServerPlayerScreen (WebView). While the ad blocker blocks many known ad domains, Nontongo specifically is notorious for:

- **Ad overlays that appear on top of the video** (the ad blocker blocks the domain, but the embed JavaScript creates elements dynamically)
- **Popunders that open new tabs/windows** (the `shouldOverrideUrlLoading` blocks some but not all)
- **Multiple redirect chains** before the video starts
- **Forced redirect to ad pages** (the timer-based ad timer that forces you to wait)

The ad blocker in `MaServerPlayerScreen` blocks ~50 specific ad domains but cannot:
- Block ads served from the same domain as the video content
- Block JavaScript-injected overlays
- Block forced redirects that happen through the embed's own UI

**Why it "won't allow me watch anything"**: Nontongo typically requires ad interactions (closing popups, waiting for timers) before the video starts. The WebView renders these overlays, and without a human to close them, playback never starts.

---

## Key Architecture Diagrams

### TMDB Content Stream Resolution Flow
```
MovieDetailScreen/AnimeDetailScreen
  │
  ├─ User selects Server 1-4
  │   └─ MaServerPlayerScreen (WebView)
  │       ├─ Loads embed URL directly
  │       ├─ 8-second stabilization (no play/pause toggling)
  │       ├─ Ad blocking in shouldInterceptRequest
  │       └─ Player appears after stabilization
  │
  ├─ User selects Server 4 (CinePro)
  │   └─ POST /api/content/cinepro/sources
  │       └─ Proxy to CinePro Core → /v1/movies/{id}
  │           └─ If sources found → AnimePlayerScreen (ExoPlayer)
  │           └─ If no sources → "Server unavailable"
  │
  └─ User selects Server 5 (VidLink Exo)
      └─ AnimePlayerScreen (ExoPlayer)
          └─ Headless WebView loads vidlink.pro URL
              └─ extractStreamFromEmbed() scrapes .m3u8
                  └─ Plays in ExoPlayer
```

### Football Stream Resolution Flow
```
FootballMatchScreen
  │
  └─ resolveStream()
      │
      ├─ Step 1: POST /api/football/direct-stream
      │   └─ Server scrapes streamed.su, crackstreams, etc.
      │       ├─ .m3u8 found → StreamResult.Direct → AnimePlayerScreen
      │       └─ No .m3u8 → fall to Step 2
      │
      └─ Step 2: Build embed URLs
          ├─ scorebat.com/embed/livescore/?search=...
          ├─ footybite.to/?s=...
          └─ v2.sportsurge.net/search?query=...
              └─ StreamResult.Embed → MaServerPlayerScreen
```

---

## All Streaming URLs Used (Complete Reference)

### TMDB Movie
```
Server 1 (VidLink):   https://vidlink.pro/movie/{tmdbId}
Server 2 (VidSrc):    https://vidsrc.to/embed/movie/{tmdbId}
Server 3 (Nontongo):  https://nontongo.win/embed/movie/{tmdbId}
Server 4 (CinePro):   POST /api/content/cinepro/sources → CinePro Core
Server 5 (ExoPlayer): https://vidlink.pro/movie/{tmdbId} (scraped for direct .m3u8)
```

### TMDB TV
```
Server 1:   https://vidlink.pro/tv/{tmdbId}/{s}/{e}
Server 2:   https://vidsrc.to/embed/tv/{tmdbId}/{s}/{e}
Server 3:   https://nontongo.win/embed/tv/{tmdbId}/{s}/{e}
Server 4:   POST /api/content/cinepro/sources → CinePro Core
Server 5:   https://vidlink.pro/tv/{tmdbId}/{s}/{e} (scraped)
```

### Football
```
Direct (Server 2): POST /api/football/direct-stream → scraped .m3u8
Embed:             https://www.scorebat.com/embed/livescore/?search={teams}
                   https://footybite.to/?s={search}
                   https://v2.sportsurge.net/search?query={search}
```

### WWE
```
Embed:  Scraped from watchwrestling.ae post pages (doodstream, vidmoly, streamtape)
Direct: Scraped .m3u8 from same pages + streamed.su
```

---

## Specific Answers to Your Questions

### 1. "ScoreBat takes me to their website — can't we play the video in our app?"

**The problem**: The URL `https://www.scorebat.com/embed/livescore/?search=...` loads their **live scores page** (not a video). It shows a list of matches with links to videos on scorebat.com.

**What would need to change**: To play ScoreBat's actual video player inside your app, you'd need to:
1. Scrape ScoreBat's search results to find the specific video ID for the match
2. Build a direct embed URL: `https://www.scorebat.com/embed/video/{videoId}`
3. Load that URL in MaServerPlayerScreen — it would render ScoreBat's own video player inside the WebView

**However**: ScoreBat's content is **highlights/replays**, not live matches. For live matches, the current approach (scraping aggregators for .m3u8 via the backend Server 2, or using embed from footybite/sportsurge) is the right approach — it just doesn't always work.

### 2. "Why does Server 4 (CinePro) say server unavailable?"

The problem is multi-layered:
- CinePro Core at `cinepro-core-esmh.onrender.com` is on Render's free tier and goes to sleep
- The main server gives it 20 seconds to respond — cold starts exceed this
- Even when it responds, it might return no sources for that specific content
- The error message in the app is generic ("Server 4"), but the actual failure could be timeout, empty response, or CinePro Core being down

### 3. "Remove Server 5 (VidLink Exo)"

Server 5 uses the same VidLink URL as Server 1, but routes through ExoPlayer's headless WebView scraper. If Server 1 works (WebView), Server 5 may fail because:
- The headless scraper can't bypass WASM/bot detection that VidLink uses
- VidLink might block the headless WebView user-agent
- The scraper has a 45-second timeout, but WASM challenges can take longer

### 4. "Server 3 (Nontongo) won't let me watch because of ads"

Nontongo's aggressive ad model is a known issue. The ad blocker in MaServerPlayerScreen blocks known domains, but Nontongo:
- Serves ads from its own domain (same-origin, can't be blocked by domain filtering)
- Uses popunder scripts that partially bypass the URL blocking
- Requires human interaction (closing overlays, waiting) to start playback

The only real fix is to **not use Nontongo as an embed** — instead, scrape it for a direct .m3u8 stream (like Server 5 does for VidLink) and play in ExoPlayer.

---

## Module Reference

| File | Purpose |
|------|---------|
| `composeApp/src/commonMain/.../data/MaServerSource.kt` | Defines StreamServer enum (Server 1-5), DonghuaServer enum, and `buildEmbedUrlForServer()` |
| `composeApp/src/androidMain/.../ui/MaServerPlayerScreen.android.kt` | Full-screen WebView embed player with ad blocking and 8-second stabilization |
| `composeApp/src/commonMain/.../ui/MaServerPlayerScreen.kt` | Expect declaration for MaServerPlayerScreen |
| `composeApp/src/androidMain/.../ui/AnimePlayerScreen.android.kt` | Full ExoPlayer with subtitle support, audio language switching, speed control, and headless WebView scraping fallback |
| `composeApp/src/commonMain/.../ui/AnimePlayerScreen.kt` | Expect declaration for AnimePlayerScreen |
| `composeApp/src/androidMain/.../ui/EmbedSuStreamScraper.android.kt` | Headless WebView scraper that loads embed pages and intercepts .m3u8 URLs via JS injection |
| `composeApp/src/commonMain/.../data/FootballSource.kt` | FootballApiSource with EspnAPI parsing and `resolveStream()` ladder: Server 2 direct → embed fallback |
| `composeApp/src/commonMain/.../data/FootballStreamResolver.kt` | Simpler client-side football resolver (alternative to FootballApiSource) |
| `composeApp/src/commonMain/.../ui/FootballMatchScreen.kt` | Match detail screen with stream resolution and "Try Another Source" button |
| `composeApp/src/commonMain/.../data/WweSource.kt` | WweSource with watchwrestling.ae scraping, resolveEmbedUrls(), resolveDirectStreamUrls() |
| `composeApp/src/commonMain/.../ui/WweMatchScreen.kt` | WWE event detail with stream resolution: try Direct first, fall back to Embed |
| `server/index.js` | Main server: handles `/api/football/direct-stream` (scrape .m3u8), `/api/content/cinepro/sources` (proxy to CinePro Core), and `/api/content/stream` |
| `server/wwe-handlers.js` | WWE server-side scraping: fetches watchwrestling.ae, extracts embed iframes and direct .m3u8 URLs |
| `cinepro/core/src/server.ts` | CinePro Core OMSS server that aggregates 17 providers into one API |
| `composeApp/src/commonMain/.../ui/StreamUrlHelper.kt` | Utility functions: `resolveAllCineProSources()`, `resolveCineProStream()`, and URL builders for embed providers |
| `render-video-sources.env` | Documentation of all required/optional env vars for video source configuration |
