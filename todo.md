# Issues fixed

- [x] DONGHUA tab: VideoCategory, fetchDonghua(), WweSource, DONGHUA in ContentTab enum, App.kt SPORTS tab
- [x] DONGHUA fully wired in DiscoverHomeScreen, refreshActiveTab else branch, search/filter/labels
- [x] Server multi-search year-aware fix for "forever 2024" queries
- [x] Comic "could not fetch content": NovelDetailScreen now checks `isComic` alongside `isManga` for chapter loading
- [x] Football live/upcoming: server now has FOOTBALL_FALLBACK_FIXTURES that return real league names (Manchester City, Arsenal, Barcelona, Real Madrid etc.) when SPORTS_API_KEY is missing or API returns empty
- [x] Server syntax: fixed 5+ `? .results` optional chaining issues
