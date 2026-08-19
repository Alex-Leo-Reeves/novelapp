# PLAN-TV-DOWNLOAD-V4 — Complete TV Download System

## Requirements
1. Remove x86_x64 ABI from tvApp build
2. Smart server selector: query all servers in parallel, pick fastest with download available
3. Hierarchical download UI: Anime > Title > Season > Episodes, Movies > Title, Manga > Title > Chapters, Novels > Title
4. Premium payment UI for unlimited downloads/watching
5. Offline-first: open app without internet → go straight to downloads
6. When internet returns → check auth seamlessly
7. Cancel download = stop + delete file
8. Bin icon to delete completed downloads
9. Quality selection popup before download
10. Concurrency downloads
11. Free limit: 5 downloads/day, 20% of series (e.g., 2 of 10 episodes, 20 min of 100 min movie)

## Steps

- [ ] Remove x86_x64 ABI from tvApp/build.gradle.kts
- [ ] Smart server selector in TvMediaCacheController (parallel ping, TTFB, pick fastest)
- [ ] Update data pipeline: MediaDownloadRequest, DownloadManifest carry mediaType/season/coverUrl
- [ ] Update TvMediaCacheController.enqueueInternal to accept new fields
- [ ] Update TvDetailScreen to pass mediaType/season/coverUrl when downloading
- [ ] Add quality selection popup before download starts
- [ ] Rewrite TvDownloadsScreen with hierarchical breadcrumb UI
- [ ] Add premium payment screen (TvPremiumScreen)
- [ ] Wire premium screen into TvApp navigation
- [ ] Offline-first boot: TvAuthSplash goes to downloads when offline
- [ ] Auth re-check when internet returns
- [ ] Cancel = stop + delete, bin = delete completed
- [ ] Free tier limits (5/day, 20% content cap)
- [ ] Concurrency downloads support
- [ ] Build verification
</write_to_file>
</execute_command>
