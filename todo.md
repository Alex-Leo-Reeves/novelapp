# CinePro "Stream Not Available" Fix

- [x] Diagnose root cause: CinePro Core returns proxy URLs (`/v1/proxy?data=...`), not direct `.m3u8`/`.mp4` URLs
- [x] Client's `isDirectPlayableStreamUrl()` rejects proxy URLs because they don't end in a known extension
- [x] Diagnose root cause: CinePro Core returns proxy URLs (`/v1/proxy?data=...`), not direct `.m3u8`/`.mp4` URLs
- [x] Client's `isDirectPlayableStreamUrl()` rejects proxy URLs because they don't end in a known extension
- [x] Fix 1: Add CinePro proxy URL detection to `isDirectPlayableStreamUrl()` in `StreamUrlHelper.kt`
- [x] Fix 2: Clean up `resolveCineProStream()` — remove broken JSON parsing, just rewrite proxy URL
