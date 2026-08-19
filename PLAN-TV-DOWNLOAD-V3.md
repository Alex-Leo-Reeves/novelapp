# PLAN-TV-DOWNLOAD-V3 — TV App Download System Overhaul

## Status: IN PROGRESS

### Requirements Summary
1. ~~Remove x86_x64 ABI~~ ✅ Already done
2. Smart server selector — ping all servers, pick fastest with download available
3. Quality selection popup when downloading
4. Hierarchical download UI (media type → title → season → episodes)
5. Premium payment screen for unlimited downloads
6. Offline-first boot — access downloads without login cache
7. Graceful re-auth when internet returns
8. Cancel = stop + delete; completed = delete button (bin icon)
9. Free tier: 5 downloads/day, 20% content preview
10. Concurrent downloads support

### Data Pipeline Status
- `MediaDownloadRequest` — ✅ already has mediaType, seasonNumber, coverUrl
- `DownloadManifest` — ✅ already has these fields
- `TvMediaCacheController.enqueueInternal` — ✅ already accepts these fields
- `DownloadEngine.requestFor` — ❌ needs to propagate mediaType/seasonNumber/coverUrl
- `MediaTaskRunner.buildManifest` — ❌ needs to propagate mediaType/seasonNumber/coverUrl
- `TvDetailScreen.startDownloadToInternal` — ❌ passes mediaType + coverUrl but NOT seasonNumber

### Implementation Steps

- [x] Step 0: Create plan markdown
- [ ] Step 1: Fix data pipeline (DownloadEngine.requestFor + MediaTaskRunner.buildManifest)
- [ ] Step 2: Smart server selector in TvDetailScreen (parallel ping, pick fastest)
- [ ] Step 3: Quality selection popup before download
- [ ] Step 4: Hierarchical TvDownloadsScreen rewrite
- [ ] Step 5: Premium payment screen
- [ ] Step 6: Offline-first boot in TvApp
- [ ] Step 7: Re-auth on internet return
- [ ] Step 8: Free tier enforcement (5/day + 20% content limit)
- [ ] Step 9: Verify concurrent downloads work
- [ ] Step 10: Build verification
