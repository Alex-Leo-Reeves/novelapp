# PLAN — Fix "Server 10 (CinePro)" showing text instead of video

## Goal
When playing a movie via Server 10 (CinePro) in the TV app, a raw text/string is rendered instead of the video stream. Fix so the actual video plays.

## Steps
- [ ] 1. Understand the flow: how the TV app resolves "server 10" + cinepro stream URLs, and how the player renders them.
- [ ] 2. Reproduce / inspect what response the cinepro endpoint returns for a movie stream (JSON/text vs video/mp4/m3u8).
- [ ] 3. Identify root cause — likely server returns JSON/text (e.g. an embed page, error, or structured payload) that the player treats as a playable URL.
- [ ] 4. Fix server side (cinepro core) and/or player side (TV embed/player) to extract the real media URL.
- [ ] 5. Verify with the actual movie request.
- [ ] 6. Update memory/knowledge graph with the fix.

## Notes
- File likely involved: `cinepro/core/src/server.ts`, `cinepro/core/src/providers/*`, `tvApp/.../TvStreamResolver.kt`, `tvApp/.../TvPlayerScreen.kt`, `tvApp/.../TvEmbedPlayer.kt`.
