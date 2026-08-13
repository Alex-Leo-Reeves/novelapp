# PLAN — AnimeHeaven as Anime Server 1 + Dynamic TMDB Server Shift

## Goal
Stable, WebView-friendly anime servers. Make the server picker ACTUALLY switch
streams for anime (previously it was cosmetic — TV always used Anineko/Consumet).
Insert AnimeHeaven as **Server 1**, HiAnime as **Server 2**, and shift the old
TMDB-based servers down: VidLink→**Server 3**, VidSrc→**Server 4**, etc.

## AnimeHeaven structure (verified 2026-08-13)
- Search: `https://animeheaven.me/search.php?s={query}` → links `anime.php?{code}` (obfuscated ids).
- Anime page: episode grid = `<a ... onclick='sel(...)' id="{md5}" href='gate.php'>` with
  `<div class='watch2 bc '>{n}</div>` for episode number n (descending 25..1).
- Player: `https://animeheaven.me/gate.php?id={md5}` — a JS redirect page that
  forwards to the video player. Loads fine in the visible WebView player
  (MaServerPlayerScreen / TvEmbedPlayerScreen), same pattern as AnimeXin embeds.

## Server layout (new)
| # | Server | Episode list source | Stream resolution |
|---|--------|--------------------|--------------------|
| 1 | AnimeHeaven | own (anime.php grid → gate.php) | WebView loads gate.php |
| 2 | HiAnime | own (Consumet `hianime`) | existing consumet:// path |
| 3 | VidLink (was 1) | TMDB | `StreamServer.VIDLINK` embed |
| 4 | VidSrc (was 2) | TMDB | `StreamServer.VIDSRC_CC` embed |
| 5 | Nontongo (was 3) | TMDB | `StreamServer.NONTONGO` embed |
| 6 | 2Embed (was 4) | TMDB | `StreamServer.TWO_EMBED` embed |
| 7 | VidLink Exo (was 5) | TMDB | `StreamServer.VIDLINK_EXO` scrape |
| 8 | MultiEmbed (was 6) | TMDB | `StreamServer.MULTI_EMBED` embed |
| 9 | AutoEmbed (was 7) | TMDB | `StreamServer.AUTOEMBED` embed |
| 10 | VidSrc Net (was 8) | TMDB | `StreamServer.VIDSRC_NET` embed |
| 11 | SmashyStream (was 9) | TMDB | `StreamServer.SMASHY` embed |
| 12 | CinePro (was 10) | TMDB | `StreamServer.CINEPRO` → direct stream |

TMDB flow already exists: `TMDBMovieScraper.fetchTVSeasonsAndEpisodes(tvId)` →
`MediaEpisode(url="tv:{id}:{s}:{e}")` → `parseTmdbPlaybackMarker` →
`StreamServer.buildEmbedUrl(...)`.

## Files
1. `composeApp/.../data/MaServerSource.kt` — add `enum class AnimeServer` (12 entries,
   `usesTmdbEpisodes: Boolean`), mapping helpers `toStreamServer()`/`fromStreamServer()`.
2. `composeApp/.../data/AnimeScrapers.kt` — add `AnimeHeavenScraper`:
   `fetchEpisodes(titleQuery, max)` (search → best `anime.php?{code}` via `animeTitleMatchScore`
   → parse gate grid → ascending episodes, `url = gate.php?id=...`) and
   `resolveGateUrl(chapter)` verifying/rewriting the animeheaven origin.
3. `tvApp/.../data/TvMediaRepository.kt` —
   - `fetchVideoEpisodes`: anime default → AnimeHeaven first, Consumet hianime fallback.
   - new `fetchTmdbChaptersForAnime(item, alternateTitles): List<Chapter>` — search TMDB
     (title.match via `TMDBMovieScraper.searchMultiPaged`), take best `tmdb://tv/{id}`,
     then `fetchTVSeasonsAndEpisodes(id)` → `Chapter(url="tv:{id}:{s}:{e}")`.
   - `resolveStreamUrl(..., animeServer: AnimeServer?)`: anime branch switches on server
     (AnimeHeaven → gate page; HiAnime → consumet/anineko; TMDB-based → embed via marker).
4. `tvApp/.../tv/data/TvBingeSession.kt` — add `animeServer` field; thread through
   `resolveBingeEpisode` + `buildTvBingeEpisode` + session copy.
5. `tvApp/.../tv/ui/screens/TvDetailScreen.kt` — for `item.isAnime && !isDonghua`:
   render AnimeServer row; on server switch, reload episode list (TMDB for `usesTmdbEpisodes`,
   scraper otherwise); pass `animeServer` into `resolveBingeEpisode`/session.
6. `tvApp/.../tv/ui/TvApp.kt` — update `resolveBingeEpisode` call sites with animeServer.
7. `composeApp/.../ui/AnimeDetailScreen.kt` (mobile) — prepend AnimeHeaven + HiAnime to the
   server chips; shift remaining providers to positions 3-10 (names only; mobile streaming
   still resolves via repository per-server provider).

## Order of work (one change at a time, verify after each)
1. Write PLAN + save memory (server-memory MCP).
2. AnimeScrapers.kt: AnimeHeavenScraper.
3. MaServerSource.kt: AnimeServer enum + helpers.
4. TvMediaRepository.kt: fetch paths + resolveStreamUrl animeServer.
5. TvBingeSession.kt + TvApp.kt + TvDetailScreen.kt wiring.
6. Mobile AnimeDetailScreen chip shift.
7. Compile: `:tvApp:compileDebugKotlin` and `:composeApp:compileDebugKotlin`.

## Out of scope (documented)
- Mobile per-server TMDB playback (mobile uses repository provider dispatch, not the
  StreamServer marker flow) — server chips updated, stream resolution unchanged.
- Backend `server/index.js` changes (TV resolves streams client-side; backend `anilist:`
  → `tmdbBestMatch` mapping already exists for stream routes).
