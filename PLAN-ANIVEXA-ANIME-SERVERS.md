# PLAN — Anivexa Anime Servers + Content-Aware Anime Selector (VidLink Last)

## Goal
Make the app content-aware: when a title is anime, show ONLY anime servers from the
Anivexa-API repo (13 providers) with **VidLink as the last server** — never the generic
movie/TV server list (Nontongo, 2Embed, CinePro, etc.).

- Anime selector (all platforms): MKissa, Reanime, AniKoto, AnimeGG, AniNeko, AniDB App,
  2DHive, AnimeNoSub, AniZone, AniBD, Senshi, KickAssAnime, AnimeDunya, **VidLink (last)**.
- Non-anime video (movies/K-drama/cartoon/classic/Nigerian): unchanged generic `StreamServer`.
- Donghua: restore `DonghuaServer` (Nontongo, AutoEmbed, DonghuaStream, EmbedSu,
  LuciferDonghua, VidSrc, AnimeXin) — the repo currently does NOT compile because
  `MediaDetailScreen` references entries the stripped enum no longer has.

## Anivexa-API contract (verified by reading the repo)
- Worker: `export default { fetch(request, env) }` — pure ESM, uses Node 18+ global fetch.
- `GET /episodes/{provider}/{anilistId}` → `{ page, type, { [provider]: { meta, episodes: { sub[], dub[] } } } }`
- `get /watch/{provider}/{anilistId}/{sub|dub}/{provider}-{ep}` →
  `{ anilistId, episode, providerEpisode, audio, streams: [{ url, type: "hls"|"embed", audio, server, priority, referer, isActive }] }`
- `GET /map/{anilistId}` → `{ mappings: { themoviedbId, tmdbSeason, defaultTvdbSeason, format, episodes, title, ... } }`
- Providers: mkissa, reanime, anikoto, animegg, anineko, anidbapp, 2dhive, animenosub,
  anizone, anibd, senshi, kaa, animedunya.

## Backend (app's own JS) — server/index.js (CJS)
1. Copy `githubanime/Anivexa-API` → `server/anivexa/` (no `.git`, no docs) so it ships to Render.
2. New `server/anivexa-handlers.js` (CJS): lazy `import()` of the ESM worker (Node ≥20),
   rewrites the URL path, forwards method/headers/body, wraps in a 60s timeout, normalizes
   responses with `sendApiData`/`sendApiError`.
3. Routes wired into `handleApi` (surgical Python edit):
   - `GET /api/anivexa/episodes/{provider}/{anilistId}`
   - `GET /api/anivexa/watch/{provider}/{anilistId}/{sub|dub}/{provider}-{ep}`
   - `GET /api/anivexa/embed/{anilistId}?ep={n}` → uses `/map` → `{ tmdbId, type, season }`,
     client builds `https://vidlink.pro/movie|tv/...`
   - `GET /api/anivexa/search?q={title}` → AniList GraphQL → first ANIME AniList ID
     (bridges TMDB-anime items in MediaDetailScreen into the Anivexa selectors).

## Client — commonMain Kotlin
4. New `data/AnivexaApi.kt`: HttpClient client with
   `fetchEpisodes(provider, anilistId): List<AnimeEpisode>` (episode url = `anivexa://{provider}/{id}/{audio}/{ep}`),
   `resolveStream(marker): AnivexaStream?(url,type)` (prefer hls/active),
   `resolveVidLinkEmbed(anilistId, ep): String?`, `searchAnilistId(title): String?`.
5. `MaServerSource.kt`:
   - Redefine `AnimeServer` → 14 entries (13 Anivexa providers + `VIDLINK` last).
     Keep `usesTmdbEpisodes` (true only for VIDLINK), add `anivexaProviderKey`, `isAnivexa`.
     `toStreamServer()` maps only VIDLINK → `StreamServer.VIDLINK`.
   - Restore `DonghuaServer` → 7 entries (NONTONGO, AUTOEMBED, DONGHUA_STREAM, EMBEDSU,
     LUCIFER_DONGHUA, VIDSRC, ANIMEXIN).
6. `NovelSearchRepository.kt`: expose `anivexaApi` + wrappers
   `fetchAnivexaEpisodes(provider, anilistId)`, `resolveAnivexaStream(marker)`.

## Mobile UI
7. `AnimeDetailScreen.kt`: replace hardcoded chips with `AnimeServer.ALL_IN_ORDER`.
   - Anivexa server → `repository.fetchAnivexaEpisodes(provider, selectedSeason.id)`.
   - VIDLINK server → episode list from Anineko fallback (list source only); play is the
     VidLink embed (WebView).
   - Play: anivexa hls → `onPlayEpisode`; anivexa embed → new `onPlayMaEmbed` param;
     VIDLINK → `onPlayMaEmbed(vidlinkEmbed)`. Downloads use the same per-server resolve.
8. `MediaDetailScreen.kt`: add `isAnimeItem` detection (mediaKind ANIME / isAnime / genre).
   When anime → render `AnimeServer.ALL_IN_ORDER` instead of `StreamServer`;
   resolve anilistId (detailUrl `anilist:` or backend search by title); load episodes per
   server (Anivexa or TMDB for VIDLINK); play/download per server. Donghua/other unchanged.
9. `App.kt`: pass `onPlayMaEmbed` lambda into `AnimeDetailScreen` (→ maServerEmbedUrl).

## TV app
10. `TvMediaRepository.kt`:
    - anime branch → Anivexa providers (anilistId from `anilist:` detailUrl or backend search)
      + VIDLINK (TMDB episodes/embed); remove ANIMEHEAVEN/HIANIME branches.
    - donghua branch → all 7 restored `DonghuaServer`s (restore mapping from backup
      `TvMaServerSource.kt`/`TvMediaRepository` + LUCIFER_DONGHUA + VIDSRC + ANIMEXIN).
11. `TvDetailScreen.kt`: default anime server `ANIMEHEAVEN` → `ANINEKO`; route anime playback
    through `TvMediaRepository.resolveStreamUrl(..., animeServer)` so Anivexa resolves.
    (TvBingeSession/TvApp use the `AnimeServer` type only — no change beyond compile.)

## Verification
12. `node --check server/index.js server/anivexa-handlers.js`; harness test of
    `/api/anivexa/episodes/anineko/21`, `/map/21`, a watch stream, `/search`.
    ✅ PASSED — `/api/anivexa/episodes/anineko/21` (episode lists),
    `/api/anivexa/search?q=One Piece` → AniList ID 21, watch stream resolved in-process.
13. `./gradlew :composeApp:compileDebugKotlinAndroid :tvApp:compileDebugKotlin` — fix all errors.
    ✅ PASSED — **BUILD SUCCESSFUL in 12m 15s** (composeApp + tvApp clean; only pre-existing
    deprecation warnings remain).
    Build-fix notes (all resolved):
    - `AnivexaApi.kt` "Unclosed comment": KDoc body contained `/api/anivexa/*)` — Kotlin block
      comments NEST, so the inner `/*` swallowed the closing `*/`. Rewrote to `/api/anivexa/)`.
    - `MediaDetailScreen.kt`/`TvMediaRepository.kt`: `AnimeResult.id` is a `String`, not `Int`
      (`it.id > 0` → digit check).
    - `TvApp.kt`: `animeServer?.toStreamServer()` — migrated `AnimeServer.toStreamServer()` from a
      companion fun to a top-level extension function.
    - `TvEmbedPlayerScreen.kt`: a `private const val EMBED_*` + `private fun formatEmbedTime` block
      sat INSIDE the `@Composable` body (missing closing brace) → "private not applicable to local
      variable" + unresolved refs. Restored the closing brace so the constants are file-scope again.
14. Update this plan's checkboxes; save memory to server-memory MCP. ✅ DONE

## Out of scope
- Removing AnimeHeaven/HiAnime from selectors (per request: anime = Anivexa providers + VidLink last).
  `AnimeHeavenScraper` code remains but is no longer listed.
- Backend reverse map TMDB→AniList: bridged via AniList GraphQL title search instead.

## Final polish (follow-up session)
- Default server = **ANINEKO** everywhere (most reliable Anivexa provider; avoids MKissa's
  flaky rate/403 rate). VidLink remains last in all lists.
  - Mobile `AnimeDetailScreen`: default chip index → `AnimeServer.ANINEKO` (was MKissa index 0).
  - Mobile `MediaDetailScreen`: `selectedAnimeServer` already = `AnimeServer.ANINEKO`.
  - TV `TvDetailScreen`: default `ANIMEHEAVEN` → `ANINEKO`.
  - TV `TvMediaRepository`: both `animeServer ?: AnimeServer.MKISSA` fallbacks → `ANINEKO`.
  - Grep-confirmed: no `AnimeServer.MKISSA` / `ANIMEHEAVEN` default remains in tvApp.
- Server-side auto-fallback cascade (try next provider on failure, VidLink as final net):
  **SKIPPED per user decision** — a fidgety user shouldn't sit through hidden retries; the
  manual 14-chip selector stays the UX.
- Final state: composeApp + tvApp compile green; TV grep-clean of stray default server refs.
