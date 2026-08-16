# PLAN — Anime Core Fixes v1.44 (Anivault trio + player fixes)

Status: ✅ DONE / 🔄 IN PROGRESS / ⬜ PENDING

## Root cause (confirmed by probes)
The 13 Anivexa providers are scraped from Render's datacenter IPs and their
stream sites return **0 streams** for popular anime (DBS, Demon Slayer, Super
Hero). The repo owner's site plays them because the scraping runs **in the
user's browser on a residential IP**. Our fix: add the 3 Anivault servers
(AnimeHeaven / AnimePahe / AniDao) and resolve them through **device-side
scrapers** that run on the user's IP inside the app — plus a server-side
stream proxy (m3u8 key rewrite + Referer/Origin) for the direct MP4/HLS CDNs.

## Done
- [x] Probed live Anivexa instance: HTML/evicted from datacenter egress → confirms datacenter blocking
- [x] Wrote `server/anivault-handlers.js` — dependency-free (fetch + regex), 3 sources + stream/m3u8 proxy with HLS EXT-X-KEY rewrite, mounted later in `server/index.js`
- [x] `MaServerSource.kt`: added `ANIMEHEAVEN`/`ANIMEPAHE`/`ANIDAO` as Servers 14–16 with `clientScraperKey` + `usesClientScraper`; VIDLINK now Server 17
- [x] `AnimeScrapers.kt`: added `AniDaoScraper` (anidao.to search → /watch-online/ episode grid → WebView player)
- [x] `NovelSearchRepository.kt`: instantiated `animeHeavenScraper` + `aniDaoScraper`; added `"animeheaven"`/`"animepahe"`/`"anidao"` dispatch in `fetchEpisodesFromAnimeProvider`

## In progress
- [ ] `NovelSearchRepository.resolveAnimeServerStream(server, episodeUrl)` — new: routes animeheaven/anidao → WebView player URL, animepahe → direct stream w/ fallback
- [ ] Wire `AnimeDetailScreen.kt` (Android/common): episodes + play + download branches for `usesClientScraper` servers
- [ ] Wire `TvMediaRepository.kt` (TV): fetchVideoEpisodes + resolveStreamUrl branches for the 3 new servers
- [ ] Player fixes in `TvEmbedPlayerScreen.kt` / `TvEmbedPlayer.kt` (TV):

---

## Full issue list (from user)
1. 🔄 Anime subtitle out of sync — player fix
2. 🔄 Episode click → ask "continue from where I stopped / start from beginning" — resume dialog
3. 🔄 13 anime servers can't play popular anime → Anivault trio (device-side scrapers) ✅ server-side prepped
4. 🔄 Dragon Ball Super / Super Hero — plays via AnimeHeaven/AniDao/Pahe (MIRRORS repo owner) — Super Hero "LIVE/LIVE" stuck loading → player fix
5. 🔄 OK double-press → pause/play toggle — player fix
6. 🔄 Integrate Anivault servers except miruro into anime, both TV + Android, play through web player — in progress
7. 🔄 TV download UI — TvDownloadsScreen
8. 🔄 In-app update crash — TvUpdateInstaller
9. 🔄 Movies Server 3 (Nontongo) crash — rewrite/isolate route
10. 🔄 Nontongo redirects to website instead of in-app player
11. 🔄 Lucifer donghua no stream — device-side scrape in webview
12. 🔄 Profile creator + editor UI missing — TvProfileScreen
13. 🔄 Infinite scroll for novel/manga/anime/kdrama/movie tabs — pagination
14. 🔄 WeebCentral not showing in manga home/search — inspect site + fix scraper
15. 🔄 Football/WWE show 0 events — backend connectivity
16. 🔄 Donghua: remove 7 servers except AnimeXin, implement the 13 Anivexa providers; none resolve streams
