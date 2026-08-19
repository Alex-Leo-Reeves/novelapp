# Fix DBZ Episode Ordering + Servers 14-16 + Anivexa Fallback

## Issues
1. Anineko/AnimePahe/Consumet sort episodes `sortedByDescending` → UI shows 291..1. `distinctBy` runs before final sort, keeping wrong first-seen episodes.
2. Server 14 (AnimeHeaven): search selector misses newer layouts.
3. Server 15 (AnimePahe): kwik extraction never works; fallback needs correct Referer for WebView.
4. Server 16 (AniDao): `anidao.to` down; need `anidao.app` fallback.
5. Anivexa servers returning 0 episodes silently fall back to TMDB-marker chapters (`tv:{id}:{s}:{e}`) that fail on Anivexa play → detect and route to VidLink.

## Changes
- [ ] AnimeScrapers.kt: 3x `sortedByDescending` → `sortedBy`
- [ ] AnimeScrapers.kt: AnimeHeaven wider search selector + alternate queries
- [ ] AnimeScrapers.kt: AnimePahe ensure embed fallback + Referer support
- [ ] AnimeScrapers.kt: AniDao domain fallback `anidao.app`
- [ ] TvDetailScreen.kt: detect TMDB-marker episodes on Anivexa → auto-switch to VidLink
- [ ] Verify: build, typecheck
