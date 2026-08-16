# Plan: TV Downloads UI, In-App Update, Movies Server 3, Profile Manager, Infinite Scrolling, WeebCentral, Sports/WWE & Donghua

## Status Legend
- [ ] Not started
- [x] Done
- [~] Partial / needs verification

## 1. TV App Download UI
- [x] Assess TvDetailScreen current download support (episode/movie buttons)
- [x] Connect to TvMediaCacheController / LocalDownloadRepository
- [x] Ensure TvDownloadsScreen lists downloaded items

## 2. In-app Update Crash
- [x] Check TvUpdateInstaller.kt current try/catch handling
- [x] Check AndroidExternalLinkOpener.kt intent safety
- [x] Fix ActivityNotFoundException fallbacks

## 3. Movies Server 3 (Nontongo) Crash & Web Redirect
- [x] Check MaServerPlayerScreen.android.kt shouldOverrideUrlLoading
- [x] Check TvEmbedPlayer.kt popup/redirect blocking
- [x] Ensure Server 3 strict ad/popup blocking

## 4. Profile Creator UI & Profile Editor UI
- [x] Check TvProfileScreen.kt current profile UI
- [x] Check if TvProfileStore.kt exists
- [x] Implement Creator + Editor if missing

## 5. Infinite Scrolling
- [x] Check TvHomeScreen.kt pagination state
- [x] Check DiscoverHomeScreen.kt pagination
- [x] Check NmcHomeScreen.kt pagination
      IMPLEMENTED: TvHomeScreen grid pages via sectionGridState + loadMoreContent; TvHomeFeed rows page per-row; Discover rows page per-section via DiscoverPosterRow; NmcHomeScreen browse feed pages all three sections and drops .take(6); all append distinctBy id.

## 6. WeebCentral Manga Provider
- [x] Check WeebCentralScraper current target URL
- [x] Verify search + home feed parsing

## 7. Football & WWE 0 Events Bug
- [x] Check server/wwe-handlers.js for sendApiData
- [x] Check FootballSource.kt / server/index.js ESPN endpoints

## 8. Donghua Server / Cloudflare Server 7
- [x] Check TvDetailScreen.kt donghua server routing
- [x] Check MediaDetailScreen.kt donghua default
- [x] Ensure AnimeXin default, no forced 2DHive

## 9. Verification
- [ ] Compile desktop/common code
- [ ] Compile TV app
- [ ] Node server handler tests (WWE)
