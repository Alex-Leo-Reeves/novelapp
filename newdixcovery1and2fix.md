# Comprehensive Fix & Implementation Plan: New Discovery 1 & 2

This document details the complete architectural and implementation plan for all fixes and features requested from `newdixcovery.txt`, `newdixcovery2.txt`, and user feedback.

---

## 1. Executive Summary of Modules & Fixes

- [x] **1. Recommended Section on Home Tab (First Section)**
   - Smart recommendation engine matching TMDB & AniList recommendations based on watch history and search history.
- [x] **2. Download Engine Fix**
   - Full fix for local downloads (direct MP4 + HLS segment caching, storage permissions, and provider referer headers).
- [x] **3. Subtitles & Anime Dub/Sub System**
   - Universal subtitle track loader (VTT, SRT, ASS/SSA) + dynamic audio stream selector for Dub/Sub across all providers.
- [x] **4. Search Routing (Anime from Home Tab $\rightarrow$ Anime Server Selector)**
   - Route anime items found in Home search directly to `AnimeDetailScreen` (19 anime servers) instead of generic `StreamServer`.
- [x] **5. Movie Duration & Timeline Resume Fix**
   - Dynamic ExoPlayer timeline tracking without stale duration clamping; smooth seeking past initial buffered segments.
- [x] **6. Search Back Navigation**
   - Preserve search query, filters, scroll state, and back-stack so navigating back from media details returns to the Search tab.
- [x] **7. Movie Servers Update (Server 3 AutoEmbed + Server 4 2Embed.online)**
   - Add AutoEmbed (`https://watch-v2.autoembed.app/`) as Server 3.
   - Add 2Embed.online (`https://www.2embed.online/embed/movie/{id}` and `https://www.2embed.online/embed/tv/{id}/{s}/{e}`) as Server 4. Verified with *Noobees* (TMDB 205715).
- [x] **8. Anime Servers 17, 18 & 19 (VidLink, VidSrc.to, AutoEmbed) ARM / MAL-Sync ID Mapping**
   - Flow: `AniList ID -> ARM API (Anime Relations Map) / MAL-Sync -> TMDB ID + Season + Episode -> VidLink / VidSrc.to / AutoEmbed`.
- [x] **9. Dragon Ball Z Kai & Multi-Season Anime Season Mapping**
   - Fix season mapping across split franchise IDs (e.g. DBZ Kai S1-S3 [ID 6033] vs S4-S5 The Final Chapters [ID 20635]) and absolute episode number translation.
- [x] **10. Continue Watching / Progress Persistence**
    - High-reliability progress saving on interval, pause, and exit; instant resume on playback launch.
- [x] **11. TV Player Controls: 5-Second Auto-Hide & D-Pad OK Play/Pause**
    - Auto-dismiss controls overlay after 5 seconds of inactivity.
    - 1st OK press when hidden $\rightarrow$ reveal overlay; 2nd OK press while showing $\rightarrow$ toggle play/pause.
- [x] **12. Live TV Channels Tab (Replacing Nollywood)**
    - 300+ worldwide live channels categorized into Sports, Movies, News, Kids/Cartoon, Music, Indian, and Entertainment.
    - 20 channels per page with Next / Previous pagination controls and seamless 2Embed IPTV & HLS playback.
- [x] **13. Demon Slayer & Dragon Ball Z Scraper Playback Fixes**
    - Penalize `TV_SHORT` format to avoid matching *Onigiri* (ID 21612) when searching *Demon Slayer* (ID 101922).
    - Pass exact provider Referer/Origin headers (`megaplay.buzz`, `vidtube.site`) and enable HLS proxy fallback for AniKoto/AniDao streams.

---

## 2. Detailed Technical Specifications

### A. Recommended Algorithm on Home Tab
- **Data Sources:**
  - `HistoryStore.getRecentHistory(limit = 10)`
  - `SearchHistoryStore.getRecentSearches(limit = 5)`
- **Algorithm Flow:**
  1. Extract TMDB IDs and AniList IDs from recently watched items.
  2. Query TMDB `/3/movie/{id}/recommendations` and `/3/tv/{id}/recommendations` (falling back to `/similar`).
  3. Query AniList recommendations GraphQL for watched anime IDs.
  4. Query search-derived recommendations by running multi-search on top search keywords.
  5. Merge, remove duplicates, filter out already watched items, and shuffle top 20 items.
  6. Render as the top carousel/grid section on the Discover/Home tab.

---

### B. Download Engine Overhaul
- **Files:** `composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/data/mediacache/` & `LocalDownloadRepository.kt`
- **Fixes:**
  - Pass required headers (`Referer`, `User-Agent`, `Origin`) to download requests so CDNs don't return 403 Forbidden.
  - Implement fallback from direct stream to server proxy download (`/api/anivexa/proxy` / `/api/anivault/proxy/m3u8`).
  - Fix Android storage permissions and background notification worker execution.

---

### C. Subtitle & Dub/Sub Anime Engine
- **Files:** `composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/ui/AnimePlayerScreen.android.kt`, `tvApp/src/main/kotlin/com/alexleoreeves/novelapp/tv/ui/screens/TvPlayerScreen.kt`
- **Fixes:**
  - Parse subtitle JSON array (`file`, `label`, `srclang`, `kind`) into MediaItem `SubtitleConfiguration`.
  - Fix subtitle MIME type detection (`text/vtt`, `application/x-subrip`, `text/x-ssa`).
  - Connect Dub/Sub preference toggle in `AnimeDetailScreen` and `AnivexaApi.fetchEpisodes(provider, anilistId, preferredAudio)` so switching between Sub and Dub reloads the appropriate episode list and stream URLs.

---

### D. Unified Search Routing for Anime
- **Files:** `composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/App.kt`, `DiscoverHomeScreen.kt`
- **Fixes:**
  - In `onItemClick`: check `item.isAnime || item.mediaKind == "ANIME" || item.category == VideoCategory.ANIME`.
  - If true $\rightarrow$ set `selectedAnime = item.toAnimeResult()` and navigate to `AnimeDetailScreen` (rendering the 19 Anime servers).
  - If false $\rightarrow$ set `selectedMedia = item` and navigate to `MediaDetailScreen`.

---

### E. Movie Timeline & Duration Resume Fix
- **Files:** `composeApp/src/androidMain/kotlin/com/alexleoreeves/novelapp/ui/AnimePlayerScreen.android.kt` & `MediaDetailScreen.kt`
- **Fixes:**
  - Remove any static/clamped duration overrides.
  - Listen to `Player.Listener.onTimelineChanged` and `onEvents` to obtain `player.duration`.
  - Allow seeking across the entire true video duration once ExoPlayer prepares the stream timeline.

---

### F. Search Back-Stack Navigation
- **Files:** `composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/App.kt`
- **Fixes:**
  - Implement navigation back-stack tracking: maintain `previousTab` or stack of visited tabs.
  - When closing a detail screen, pop back to the initiating tab (e.g. Search / Discover) instead of resetting to `BottomTab.DISCOVER`.

---

### G. Server 3 & Server 4 for Movies / TV Shows
- **Files:** `composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/data/MaServerSource.kt`
- **Server 3 (AutoEmbed):**
  - Movie: `https://autoembed.co/movie/tmdb/{id}`
  - TV: `https://autoembed.co/tv/tmdb/{id}-{season}-{episode}`
- **Server 4 (2Embed.online):**
  - Movie: `https://www.2embed.online/embed/movie/{id}`
  - TV: `https://www.2embed.online/embed/tv/{id}/{season}/{episode}`
  - Verification: Tested with *Noobees* (`tv/205715/1/1`).

---

### H. Anime ARM / MAL-Sync ID Mapping (Servers 17, 18, 19)
- **Files:** `composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/data/AnivexaApi.kt`, `server/anivexa-handlers.js`
- **Mapping Pipeline:**
  $$\text{AniList ID} \longrightarrow \text{ARM / MAL-Sync / /api/anivexa/map} \longrightarrow \text{TMDB ID + Season + Episode} \longrightarrow \text{VidLink / VidSrc.to / AutoEmbed}$$
- Maps anime series and movies to exact TMDB IDs, handling season numbering for multi-cour and multi-season anime.

---

### I. Dragon Ball Z Kai & Franchise Season Mapping
- **Files:** `composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/data/AnimeScrapers.kt`
- **Fix:**
  - Map DBZ Kai Season 1-3 (Saiyan through Cell Saga $\rightarrow$ AniList ID `6033`) and Season 4-5 (The Final Chapters / Buu Saga $\rightarrow$ AniList ID `20635`).
  - Calculate relative episode numbers for each season block so S4 Ep 1 requests episode 1 of *The Final Chapters* rather than failing.

---

### J. TV Player D-Pad OK & 5s Auto-Hide
- **Files:** `tvApp/src/main/kotlin/com/alexleoreeves/novelapp/tv/ui/screens/TvPlayerScreen.kt`, `TvEmbedPlayer.kt`
- **Rules:**
  1. Controls overlay timer: 5000ms delay before setting `showControls = false`.
  2. D-Pad Center / OK button:
     - If `!showControls` $\rightarrow$ `showControls = true`, reset 5s timer.
     - If `showControls` $\rightarrow$ toggle `player.playWhenReady = !player.playWhenReady` (or simulate click in WebView embed).

---

### K. Live TV Channels (300+ IPTV Channels)
- **Files:** `composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/ui/LiveChannelScreen.kt`, `LiveChannelSource.kt`, `App.kt`
- **Channels Catalog (300+ entries across 7 categories):**
  - **Sports (50+ channels):** Sky Sports, BT Sport/TNT, beIN Sports, ESPN, Willow Cricket, Fox Sports, WWE Network, SuperSport, etc.
  - **Movies & Cinema (50+ channels):** HBO, Cinemax, Star Movies, Sony MAX, Zee Cinema, AMC, Film4, etc.
  - **Kids & Cartoon (40+ channels):** Cartoon Network, Nickelodeon, Disney Channel, Boomerang, Anime All Day, etc.
  - **News (40+ channels):** BBC News, CNN, Al Jazeera, Sky News, France 24, Bloomberg, NDTV, etc.
  - **Entertainment / General (50+ channels):** USA Network, TBS, TNT, ITV, Channel 4, FX, Star World, etc.
  - **Music (35+ channels):** 9X Tashan, MTV, Club MTV, Trace Urban, 9XM, Zing, etc.
  - **Indian / Regional (40+ channels):** Colors, Sony Entertainment, Zee TV, Star Plus, Sun TV, etc.
- **UI & Pagination:**
  - Category selector at the top.
  - 20 channel cards per page with channel logo, title, genre badge, and quality indicator.
  - "Previous Page" and "Next Page" navigation buttons.
  - Fullscreen player support with 2Embed stream iframe and direct HLS m3u8 playback.

---

## 3. Step-by-Step Implementation Sequence

1. Update `MaServerSource.kt`: Configure Server 3 (AutoEmbed), Server 4 (2Embed.online), and Anime Server 19 (AutoEmbed).
2. Update `AnivexaApi.kt` & `server/anivexa-handlers.js`: ARM / MAL-Sync mapping, Demon Slayer popularity scoring, and HLS proxy headers.
3. Update `AnimeScrapers.kt` & `NovelSearchRepository.kt`: Multi-season anime offsets (DBZ Kai), download header resolution.
4. Implement `LiveChannelSource.kt` & `LiveChannelScreen.kt`: 300+ Live TV channel database and paginated UI.
5. Update `App.kt`: Replace Nollywood tab with Live TV tab, fix search navigation back-stack, and route anime search results to `AnimeDetailScreen`.
6. Update `AnimePlayerScreen.android.kt` & `TvPlayerScreen.kt`: Subtitle loading, timeline/duration fix, TV 5s auto-hide and D-Pad OK play/pause.
7. Update `LocalDownloadRepository.kt` & download workers: Reliable headers and storage persistence.

---

## 4. Verification & Testing Matrix

| Test Case | Expected Result |
|---|---|
| Search "Demon Slayer" | Resolves to *Kimetsu no Yaiba* (ID 101922, 26 eps), not *Onigiri* (ID 21612) |
| Dragon Ball Z AniKoto Stream | Plays Ep 1 with `Referer: https://megaplay.buzz/` without 403 error |
| Dragon Ball Kai S3 & S4 | S3 plays Cell saga; S4 plays *The Final Chapters* Ep 1 correctly |
| Movie Server 4 (2Embed) | Opens `https://www.2embed.online/embed/tv/205715/1/1` for *Noobees* and plays |
| Search Anime from Home | Clicking result opens Anime detail screen with 19 servers |
| Search Navigation Back | Pressing back returns to Search tab with previous query and results |
| TV Remote D-Pad OK | 1st OK shows overlay; 2nd OK toggles play/pause; hides after 5s |
| Live TV Tab | 300+ channels categorized; 20 items per page with Prev/Next buttons; streams play |
| Subtitles & Dub/Sub Toggle | Subtitles render on screen; Dub/Sub toggle switches audio tracks properly |
