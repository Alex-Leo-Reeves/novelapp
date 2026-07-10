# Issues fixed

## Donghua tab showing wrong content (Wuxiaworld/RoyalRoad novels)

### Root Cause — Server normalizeContentType
The server function `normalizeContentType("donghua")` had no case for "donghua" — it fell through to the default `"novels"`, causing the server to return **novel titles** (Wuxiaworld, RoyalRoad, ReadNovelFull books) when the Donghua tab requested content.

### Fixes applied to `server/index.js`:
- [x] `normalizeContentType()`: Added `"donghua"` (and aliases `"chineseanime"`, `"chineseanimation"`, `"dongman"`, `"donghua-anime"`, `"chinesedrama"`) to return `"donghua"` instead of defaulting to `"novels"`
- [x] `contentHome()`: Added `"donghua"` to the TMDB pipeline check
- [x] `contentSearch()`: Added `"donghua"` to the TMDB pipeline check
- [x] `tmdbItems()`: Added dedicated `normalizedType === "donghua"` endpoint for Chinese animation discovers
- [x] `tmdbItems()`: Added `"Donghua"` subtitle in the subtitle mapping

## Other fixes
- [x] DONGHUA tab: VideoCategory, fetchDonghua(), WweSource, DONGHUA in ContentTab enum, App.kt SPORTS tab
- [x] DONGHUA fully wired in DiscoverHomeScreen, refreshActiveTab else branch, search/filter/labels
- [x] Server multi-search year-aware fix for "forever 2024" queries
- [x] Comic "could not fetch content": NovelDetailScreen now checks `isComic` alongside `isManga` for chapter loading
- [x] Football live/upcoming: server now has FOOTBALL_FALLBACK_FIXTURES that return real league names
- [x] Server syntax: fixed 5+ optional chaining issues
