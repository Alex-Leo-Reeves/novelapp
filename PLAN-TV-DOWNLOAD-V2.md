# TV Download System V2 — Implementation Plan

## Requirements
1. ✅ Remove x86_64 ABI from TV build
2. Smart server selector — auto-probe all servers, pick fastest with download available
3. Hierarchical download UI — Anime > Title > Season > Episodes
4. Premium payment UI screen accessible from the app
5. Offline-first — app opens to downloads even when offline + login cache forgotten
6. Quality selection popup when downloading
7. Cancel = stop + delete, completed = delete button (bin icon)
8. Concurrent downloads with free limits (5/day, 20% content cap)
9. Movies show flat list in downloads (e.g. "Odyssey")

## Files to Modify
- `tvApp/build.gradle.kts` — ABI filter ✅
- `tvApp/.../TvDetailScreen.kt` — Smart server probe + quality popup + free tier 20% cap
- `tvApp/.../TvDownloadsScreen.kt` — Hierarchical UI with breadcrumb nav
- `tvApp/.../TvApp.kt` — Offline-first boot, premium payment nav, quality dialog
- `tvApp/.../TvMediaCacheController.kt` — Auto-server selection integration

## Status
- [x] Remove x86_64 ABI
- [ ] Smart server selector
- [ ] Hierarchical download UI
- [ ] Premium payment UI
- [ ] Offline-first boot
- [ ] Quality selection popup
- [ ] Free tier 20% cap
- [ ] Cancel/delete semantics
