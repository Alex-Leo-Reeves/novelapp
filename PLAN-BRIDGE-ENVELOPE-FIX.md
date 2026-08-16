# PLAN — Bridge Envelope Fix (NodeBridge ↔ AnivexaApi contract)

## Goal
Make the app's **embedded nodebridge** path return the exact same JSON
envelope (`{ ok, data, error }`) that the backend `/api/anivexa/*` handlers
produce, so `AnivexaApi.kt` parses search / episodes / watch-streams / map /
embed identically for both paths. This lets the app play through the 13
Anivexa servers even when the embedded worker (nodebridge) is active.

## Background (verified earlier)
- Backend path works: search -> anilistId 178788 (Infinity Castle) -> episodes -> 11 streams -> HLS.
- Nodebridge path resolves the SAME worker payloads but the raw worker
  envelope (`{ page, type, mappings, anineko }` for episodes,
  `{ anilistId, episode, providerEpisode, audio, streams }` for watch) does
  NOT match `AnivexaApi` (which requires `root["ok"] == true` + `root["data"]`),
  so the embedded path silently returns empty / null.
- The raw Anivexa worker has NO `/search` or `/embed` route at all.

## Steps
- [x] Read contract files (main.js, server/anivexa-handlers.js, AnivexaApi.kt, NodeBridgeRuntime.kt, nodebridge dir listing)
- [x] Rewrite nodebridge/main.js: normalize to `{ ok, data, error }` + implement `/search` and `/embed`
- [x] Bump NodeBridgeRuntime STAMP_VERSION so devices re-stage the new main.js
- [x] Restart nodebridge, verify all 5 routes against loopback (search, episodes, watch, map, embed)
- [x] Confirm backend path still works; write summary
