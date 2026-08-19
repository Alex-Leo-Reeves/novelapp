# PLAN: TV Downloads UI, Smart Server, Premium, Offline-First

## Status: ✅ COMPLETE — All changes wired end-to-end

---

## 1. Remove x86_x64 ABI from TV build ✅
- tvApp/build.gradle.kts: `abiFilters` now only `arm64-v8a`, `armeabi-v7a`, `x86`

## 2. Smart Server Selector ✅
- MediaServerProbe infrastructure: probe all servers, rank by TTFB/latency
- MediaServerCandidate + MediaServerProbeResult models
- TvMediaCacheController.probeServers() exposed
- Download engine falls back to next-fastest if primary drops below threshold

## 3. Hierarchical Download UI ✅
- TvDownloadsScreen: breadcrumb drill-down (Type → Title → Season → Episodes)
- Categories: Anime, Movies, Manga, Comics, Donghua, Light Novels, Other
- Tap episode → launch local playback from bundled cache

## 4. Data Pipeline (mediaType/seasonNumber/coverUrl) ✅
- MediaDownloadRequest carries: mediaType, seasonNumber, coverUrl, maxBytes, maxFraction
- DownloadManifest carries same fields
- DownloadEngine.requestFor propagates all new fields
- MediaTaskRunner.buildManifest: applies maxBytes (absolute) then maxFraction (dynamic based on probe)
- TvMediaCacheController.enqueueInternal accepts all new fields
- Chapter.seasonNumber added to Models.kt
- TvDetailScreen passes mediaType/seasonNumber/coverUrl when downloading

## 5. Quality Selection Popup ✅
- TvDetailScreen shows quality dialog (1080p/720p/480p) before storage dialog
- Free-tier notice in quality popup

## 6. Premium Payment Link ✅
- "Go Premium" button in downloads quota banner (TvDownloadsScreen)
- Links to TvYouScreen payment flow (FlutterwaveQrPayment)

## 7. Offline-First Boot ✅
- TvApp: when offline + has saved account, skips auth → goes straight to HOME/DOWNLOADS
- Users can access downloaded content without internet

## 8. Auth Re-check When Internet Returns ✅
- ConnectivityManager.NetworkCallback registered on app start
- When network becomes available, re-verifies auth token
- Invalidates session if token expired

## 9. Cancel = Stop + Delete, Bin Icon ✅
- Active downloads: cancel button stops engine + deletes partial bundle
- Completed downloads: bin icon (trash) to delete
- Quality-aware progress shown

## 10. Free Tier Limits ✅
- 5 downloads per day enforced via MediaAccessPolicy + daily counter
- 20% episode cap: `enqueueDownload` blocks free users at 20% of total episodes
- 20% movie byte cap: `maxFraction=0.2f` for single-content downloads by free users
- MediaTaskRunner applies fraction dynamically after probe (20% of probed file size)

## 11. Concurrency ✅
- 6 parallel chunks per download (MEDIA_MAX_CONCURRENT_CHUNKS = 6)
- Download engine manages queue with parallel chunk scheduling

## 12. Offline Playback From Downloads ✅
- Downloaded bundles decrypt+play via TvLoopbackMediaServer
- No internet needed for playback
- App remembers downloads across restarts

---

## Files Modified

| File | Change |
|------|--------|
| `tvApp/build.gradle.kts` | Remove x86_64 from abiFilters |
| `composeApp/.../MediaCacheModels.kt` | Add maxFraction, mediaType, seasonNumber, coverUrl to Request + Manifest |
| `composeApp/.../DownloadEngine.kt` | requestFor propagates maxFraction |
| `composeApp/.../MediaTaskRunner.kt` | buildManifest applies maxFraction dynamically |
| `composeApp/.../Models.kt` | Chapter.seasonNumber added |
| `composeApp/.../MediaAccessPolicy.kt` | Daily quota + byte cap policies |
| `tvApp/.../TvMediaCacheController.kt` | enqueueInternal accepts maxFraction |
| `tvApp/.../TvDetailScreen.kt` | Quality popup, 20% cap, maxFraction, episode cap |
| `tvApp/.../TvDownloadsScreen.kt` | Hierarchical breadcrumb UI, bin icon, cancel=delete, Go Premium |
| `tvApp/.../TvApp.kt` | Offline-first boot, auth re-check, onGoPremium wiring |
</task_progress>
