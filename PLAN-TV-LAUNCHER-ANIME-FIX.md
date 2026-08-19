# PLAN: TV App Launcher + Anime Fixes

## Issues
1. TV app has no launcher icon in emulator app drawer
2. Anime engine (NodeBridge) fails on emulator
3. App crashes when watching anime on ALL 13 servers

## Root Causes
1. `tvApp/src/main/AndroidManifest.xml` only has `LEANBACK_LAUNCHER`, missing `LAUNCHER`
2. `AnivexaApi.baseUrl()` doesn't validate `embeddedBaseUrl` is reachable
3. `buildTvBingeEpisode()` uses `isTvPlayableStreamUrl()` URL heuristics to decide player routing — Anivexa URLs containing `/stream/` etc get misclassified as direct → sent to LibVLC → crash on HTML content

## Fixes
- [ ] Fix 1: Add `LAUNCHER` category to TV manifest intent-filter
- [ ] Fix 2: Make `AnivexaApi.baseUrl()` validate embedded server before using it
- [ ] Fix 3: Thread `isDirect` flag through `resolveBingeEpisode` → use `AnivexaStream.isDirect` instead of URL heuristics
- [ ] Verify build
