# PLAN — Series-vs-Anime Servers, Playback Reliability, Webplayer-First

## Context
TV app running on Android TV. User reported multiple related bugs after the DBZ episode-ordering work:
1. Non-anime series (Henry Danger, FROM) incorrectly show the anime server list
2. Anime "Server 5" (AniNeko) worked for Henry Danger — user wants it available for series/movies/kdrama/cartoon too, as the LAST server in the normal movie-server row
3. Episodes play once, then refuse to replay until waiting ("servers start stopping me")
4. Subtitles missing on direct streams (played via LibVLC); webplayer would show them
5. Remote OK button should pause/play
6. Henry Danger season and other contents episode lists are unordered — seasons bleed into each other; episodes must be ordered E1, E2, E3 vertically
7. Bottom of episode list → UI disappears / can't scroll up / app crashes
8. Spider-Man Brand New Day stuck on "preparing to play" / loading
9. Terrible sound on Henry Danger (volume)
10. Webplayer-first: ALL servers/content through webplayer EXCEPT when the webplayer cannot play a stream (direct .m3u8/.mp4 must still go to LibVLC)

## Root-Cause Analysis (from tracing)
- `TvDetailScreen` decides anime-server row using `item.isAnime` — this flag is set too broadly in search/feed paths.
- `AnimeServer.ANINEKO` ("Server 5") is an Anivexa provider keyed to `anineko` — plays Western series via AniNeko titles; belongs in the StreamServer row too.
- `resolveStreamUrl` / `resolveBingeEpisode` route: direct streams → TvPlayerScreen (LibVLC), embed pages → TvEmbedPlayerScreen (webplayer). User wants embed-first; direct only when webplayer cannot play.
- Watch-once bug: `routePlaybackSession` reuses a previously-resolved URL across separate sessions; stale/expired URL replays fail. Fix: ALWAYS re-resolve on replay.
- Episode ordering: UI `sortedBy { chapterNumber }` flat-mixes seasons. Fix: group by season, order E1,E2,E3 within season, render vertically.
- Bottom-of-list crash: focus loss in LazyVerticalGrid + no focus containment. Fix: wrap right panel in focus container + cap grid height with vertical scroll.
- Volume: TvEmbedPlayer already injects AUDIO_BOOST_JS; TvPlayerScreen (LibVLC) caps at volume=100. Fix: bump VLC boost + normalize.

## Implementation Notes
- `StreamServer` gains `ANINEKO` (last server, order 11). Its `buildEmbedUrl` maps to VidLink (TMDB-keyed episodes).
- TvDetailScreen StreamServer-row + resolution: when ANINEKO chip is selected for a NON-anime title, `resolveStreamUrl` routes through AnimeHeaven device-side scraper (title query) → fallback VidLink.
- Harden the anime-row gate: only `kind == "anime"` OR genre contains Japanese+Animation yields the anime row; anything else uses the StreamServer row (which now includes ANINEKO last).
- `routePlaybackSession`/`advanceBinge`: re-resolve every episode on entry (never trust cached URL across play sessions).
- Episode list: per-season groups, E1,E2,E3 ascending, one row per episode (vertical).
- Webplayer-first stays as-is (embeds → TvEmbedPlayerScreen, direct media → LibVLC) — matches requirement 10.

## Work Items
- [x] 1. DBZ episode ordering — convert 3 scrapers from `sortedByDescending` to `sortedBy`
- [x] 2. AnimeHeaven (S14) — widen search selector + fallback queries
- [x] 3. AnimePahe (S15) — ensure embed-page fallback goes to webplayer with correct Referer
- [x] 4. AniDao (S16) — domain fallback (anidao.app)
- [x] 5. Anivexa silent-TMDB-fallback — when Anivexa server returns TMDB-marker chapters, auto-route to VidLink/StreamServer
- [ ] 6. Fix `isAnime` classification — non-anime series must NOT show anime servers
- [ ] 7. Add ANINEKO to StreamServer row as last server for non-anime content
- [ ] 8. Webplayer-first routing in binge/resolve paths
- [ ] 9. OK button pause/play in webplayer (and LibVLC player)
- [ ] 10. Episode ordering per season — group by season, order E1,E2,E3 vertically; no cross-season bleed
- [ ] 11. Fix bottom-of-list UI crash / focus loss
- [ ] 12. Volume normalization (Henry Danger terrible sound)
- [ ] 13. Episodes play once, then refuse to replay until waiting ("servers start stopping me")
