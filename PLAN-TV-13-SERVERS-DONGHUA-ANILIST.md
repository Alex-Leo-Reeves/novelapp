# TV App — 13 Anivexa Servers for Donghua + Proper AniList Keying

## Goal
1. Wire the **13 Anivexa servers (AniList-keyed)** into the TV **donghua** tab, matching the Android app's flow (donghua has the same 13-server provider selector + AniList-ID routing).
2. Ensure the 13 Anivexa servers in **anime** use the AniList ID **properly** — resolve it once, and from the item's own id/url when present, not a fresh title-search bridge on every server switch.

## Current behavior (verified)
- `TvDonghuaScreen` → builds `UnifiedSearchResult(mediaKind="donghua", isVideo=true, isAnime=false)` → `TvDetailScreen`.
- `TvDetailScreen`:
  - `isDonghua` → renders only `DonghuaServer.ALL_IN_ORDER` = **AnimeXin only**.
  - `LaunchedEffect` calls `mediaRepo.fetchVideoEpisodes(item)` — **no animeServer passed for donghua**.
  - `playMedia` computes `isAnimeItem = item.isAnime && !isDonghua` → donghua `animeServer=null`, session routes via `donghuaServer=ANIMEXIN`.
- `TvMediaRepository.fetchVideoEpisodes`:
  - `isDonghua` branch → `animeXinScraper.fetchEpisodes(titleQuery=item.title)` — never Anivexa.
  - anime + Anivexa → `resolveAnilistId(item)`: checks `detailPageUrl` (`anilist:`), `animeResult`, then **`anivexaApi.searchAnilistId(title)`** title bridge (slow, re-run per server switch; misses the `anilist_<digits>` id pattern).
- `TvMediaRepository.resolveStreamUrl`:
  - donghua branch → AnimeXin only (`resolveEpisodePlayerUrl`).
  - anime branch → Anivexa markers via `anivexaApi.resolveStream`, Anivault trio via device scrapers, VidLink via TMDB marker.

## Android parity (what Android has that TV is missing)
- Android `App.kt` routes `i.isAnime && i.animeResult != null` → `AnimeDetailScreen` (13 Anivexa providers, AniList-ID keyed).
- Android donghua feed titles that are also on AniList get the same provider set.
- TV donghua never shows the 13 Anivexa chips — **that is the missing piece this plan fixes**.

## Changes

### 1. `TvMediaRepository.kt` ✅
- `fetchVideoEpisodes(item, animeServer)`:
  - `useBackendForAnime` gate: also exclude backend chapters when `animeServer?.isAnivexa == true || animeServer?.usesClientScraper == true` for donghua (currently `isTmdb && !item.isAnime` still returns backend TMDB chapters for donghua even with an Anivexa server picked — fix).
  - `isDonghua` branch: if `animeServer?.isAnivexa == true` → `resolveAnilistId(item)` → `anivexaApi.fetchEpisodes(provider, anilistId)` → map to `Chapter`s; fall back to AnimeXin on null ID or empty list. If `animeServer?.usesClientScraper == true` (Anivault trio) → device-scraper episode lists. Else AnimeXin (unchanged default).
- `resolveAnilistId(item)`:
  - Add fast path: `item.id.startsWith("anilist_")` → extract digits (e.g. `anilist_151807`).
  - Add fast path: `item.url` with `anilist:` prefix.
  - **Memoize** resolved IDs in a private `mutableMapOf<String, String?>` keyed by `item.id.ifBlank { item.title.lowercase() }` so server switches / binge-next reuse the same AniList ID instead of re-hitting the title bridge.
- `resolveStreamUrl(item, chapter, server, donghuaServer, animeServer)`:
  - `isDonghua` branch: if `animeServer?.isAnivexa == true` and `AnivexaApi.isAnivexaEpisodeUrl(chapter.url)` → `anivexaApi.resolveStream(chapter.url)?.url`.
  - `isDonghua` + `animeServer?.usesClientScraper == true` → route to the Anivault device scraper.
  - `isDonghua` + `animeServer == AnimeServer.VIDLINK` → resolve via TMDB marker → `StreamServer.VIDLINK.buildEmbedUrl` (VidLink source).
  - `isDonghua` + AnimeXin → unchanged.

### 2. `TvDetailScreen.kt` ✅
- Server chips: for `isDonghua`, render **`AnimeServer.ALL_IN_ORDER`** (13 Anivexa + 3 Anivault + VidLink) instead of only AnimeXin. Keep `selectedDonghuaServer=ANIMEXIN` as the "device scraper" default; add `selectedAnimeServer` drives the anime/donghua 13-server row. To preserve existing AnimeXin access, show both rows when donghua: DonghuaServer row (AnimeXin) + AnimeServer row (13 Anivexa etc.). Selecting an AnimeServer chip sets `selectedAnimeServer`; selecting AnimeXin sets `selectedDonghuaServer`.
- `LaunchedEffect(item, selectedAnimeServer, selectedDonghuaServer)`: for donghua, call `mediaRepo.fetchVideoEpisodes(item, selectedAnimeServer)` so switching to an Anivexa chip loads that provider's episode list.
- `playMedia` session threading:
  - `isAnimeItem = item.isAnime || isDonghua` (donghua can carry an animeServer now).
  - When donghua and the active server is an AnimeServer (Anivexa/Anivault/VidLink), pass `animeServer = selectedAnimeServer`, `server = null`, `donghuaServer = null` (so `resolveStreamUrl`/`resolveBingeEpisode` routes through the Anivexa/anime path). When the active server is AnimeXin, pass `donghuaServer = ANIMEXIN` as before.
  - `serverName` label uses the active anime server's `displayName` when an Anivexa chip is chosen.

### 3. `TvDonghuaScreen.kt` ✅
- Pass `isAnime = true` (or keep a dedicated donghua flag) so `TvDetailScreen` treats the donghua item as anime-capable for server-selection/episode routing? NO — keep `isAnime=false`, `mediaKind="donghua"`; TvDetailScreen already keys off `isDonghua`. Only change: nothing needed here beyond what TvDetailScreen does. (Optional: pass `animeResult` if the feed item ever carries one — backend donghua items are TMDB, not AniList.)
- No change required — `TvDetailScreen` handles donghua + animeServer selection.

### 4. `AnivexaApi.kt` (optional hardening) ✅
- `searchAnilistId(title)`: strip parenthetical suffix like `(TV)` / `(Dub)` before searching to improve match rate for TMDB titles.

## Verification ✅ (confirmed by code tracing — 2026-08-14)
1. `TvDetailScreen.kt` — donghua renders TWO rows: DonghuaServer (AnimeXin) + AnimeServer.ALL_IN_ORDER (13 Anivexa + 3 Anivault + VidLink LAST). ✅
2. `TvMediaRepository.fetchVideoEpisodes(item, animeServer)` — ✅
   - donghua + Anivexa → `resolveAnilistId(item)` → `anivexaApi.fetchEpisodes(provider, anilistId)` (AniList-keyed).
   - anime + Anivexa → `resolveAnilistId(item)` → `anivexaApi.fetchEpisodes(provider, anilistId)` (AniList-keyed).
   - donghua/anime + Anivault trio → device-side scrapers (title-based).
   - donghua + VIDLINK → `fetchTmdbChaptersForAnime(item)` (TMDB episode list).
   - anime + VIDLINK → `fetchTmdbChaptersForAnime(item)` (TMDB episode list).
3. `resolveAnilistId(item)` — fast paths: `anilist_<digits>` id → digits; `anilist:<digits>` detailPageUrl/url → digits; then `animeResult`; final fallback `anivexaApi.searchAnilistId(title)` (title bridge, now strips `(TV)/(Dub)` suffixes). Memoized per item id/title. ✅
4. `TvMediaRepository.resolveStreamUrl` — ✅
   - donghua/anime + Anivexa → `anivexaApi.resolveStream(chapter.url)`.
   - donghua/anime + Anivault → device scrapers.
   - donghua + VIDLINK → `parseTmdbPlaybackMarker` → `StreamServer.VIDLINK.buildEmbedUrl` (TMDB).
   - anime + VIDLINK (fall-through) → TMDB marker → `StreamServer.VIDLINK.buildEmbedUrl` (TMDB).
5. Backend feed keying (`server/index.js`): anime items from `anilistItems()` carry `id: "anilist_{id}"` + `detailUrl: "anilist:{id}"` (e.g. seed rows `anilist:151807`, `anilist:21`, `anilist:813`, `anilist:21459`) → TV `toUnifiedResult()` preserves both → the AniList fast-path fires with NO title bridge. Donghua feed items are TMDB (`tmdb://...`) → AniList reached via title-bridge. ✅
6. `AnivexaApi.searchAnilistId` — strips `(TV)/(Dub)/(Sub)/(Movie)/(Film)` + year suffix to improve TMDB→AniList bridge match rate. ✅

## Final confirmation (the question asked)
- **Anime under the 13 Anivexa servers:** episode lists use **AniList** (native `anilist_<id>` / `anilist:<id>` markers from the feed; title-bridge only for TMDB-sourced anime items). ✅
- **Donghua under the 13 Anivexa servers:** episode lists also use **AniList** — resolved via the same `resolveAnilistId` (fast-path for `anilist_`/`anilist:` items, title-bridge for TMDB donghua feed items), then `anivexaApi.fetchEpisodes(provider, anilistId)`. ✅
- **VidLink (Server 17):** BOTH anime and donghua use **TMDB** — `fetchTmdbChaptersForAnime()` builds the `tv:{id}:{s}:{e}` markers and playback resolves through `StreamServer.VIDLINK.buildEmbedUrl` (TMDB). ✅
- `./gradlew :tvApp:compileDebugKotlin` compile: NOT re-run locally (builds via GitHub Actions on push per user preference); prior working tree contains the full change set (711 insertions / 171 deletions across TvMediaRepository + TvDetailScreen + AnivexaApi + MaServerSource + backend).

---

## 2026-08-14 FIX — Contents under the 13 servers showing WRONG episodes ✅

## Root cause
Items sourced from TMDB feeds (donghua feed + TMDB-sourced anime) carry no AniList ID.
`TvMediaRepository.resolveAnilistIdUncached` fell back to `anivexaApi.searchAnilistId(title)`,
and the backend `/api/anivexa/search` returned AniList's **first SEARCH_MATCH hit with ZERO validation**.
For sequels ("My Hero Academia Season 6"), romanized titles, or broad names, that top hit is a
DIFFERENT show → wrong AniList ID → all 13 Anivexa providers keyed the wrong show's episode list.

## Fix (3 parts) ✅
1. **`server/anivexa-handlers.js`** — `handleAnivexaSearch` now:
   - Strips `(TV)/(Dub)/(Sub)/(Movie)/(Film)` + trailing year qualifiers from the query.
   - Fetches `perPage: 12` candidates and ranks them with `pickBestAnilistCandidate`
     (normalized title: exact 10000 → prefix 8000−sequelPenalty → contains 5000−sequelPenalty).
   - Returns `{ anilistId: null }` when NO candidate scores > 0 → app falls back to
     AnimeXin/TMDB instead of keying the 13 providers to the wrong show.
   - Cache key now includes the `q:` prefix (stores negative results too).
2. **`composeApp/src/commonMain/kotlin/com/alexleoreeves/novelapp/data/AnivexaApi.kt`** —
   added `resolveTmdbIdForAnilist(anilistId): String?` hitting the worker `/map/{id}` route
   (works on both the embedded loopback worker and the backend `/api/anivexa/map` fallthrough).
3. **`tvApp/src/main/kotlin/com/alexleoreeves/novelapp/data/TvMediaRepository.kt`** —
   `resolveAnilistIdUncached` for a `tmdb://{movie|tv}/{id}` item now:
   - Bridges the title → candidate AniList ID.
   - Calls `anivexaApi.resolveTmdbIdForAnilist(bridged)` and REQUIRES the mapped TMDB id
     to equal the item's own TMDB id; mismatch → logs `Rejected mis-matched AniList id ...`
     and returns `null` (falls back to AnimeXin/TMDB), so the 13 servers never show
     another show's episodes.
   - Fast paths (`anilist_<digits>`, `anilist:<digits>`, `animeResult`) remain unchanged/untrusted.

## Verification
- `node --check server/anivexa-handlers.js` → **JS_SYNTAX_OK** ✅
- `node --check server/index.js` → **INDEX_OK** ✅
- `./gradlew :tvApp:compileDebugKotlin` → **SKIPPED per user request** ("leave the build for now");
  compiles via GitHub Actions on push. Kotlin changes are additive (new method in shared
  `AnivexaApi`, new guard block in `TvMediaRepository`) with no signature changes.

## End state (answer to the original question)
- Anime under the 13 Anivexa servers: **AniList** (native `anilist_<id>`/`anilist:<id>` fast paths; verified title-bridge for TMDB-sourced anime). ✅
- Donghua under the 13 Anivexa servers: **AniList** (same `resolveAnilistId`; TMDB donghua now TMDB-verified). ✅
- VidLink (Server 17): **TMDB** for both anime and donghua (`fetchTmdbChaptersForAnime` → `StreamServer.VIDLINK.buildEmbedUrl`). ✅
