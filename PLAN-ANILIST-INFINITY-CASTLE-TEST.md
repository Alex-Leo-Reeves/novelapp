# PLAN-ANILIST-INFINITY-CASTLE-TEST

Goal: prove "Kimetsu no Yaiba: Infinity Castle Arc" resolves and plays through
the APP'S OWN wiring — NOT via external probes of the Anivexa repo.

## Test surface (the app's actual code paths)
- App backend `server/index.js` mounts `handleAnivexa` at `/api/anivexa/*`.
  `AnivexaApi.kt` baseUrl() = API_BASE_URL + "/anivexa" (fallback) or the
  nodebridge loopback URL (embedded worker). Both paths must be exercised.
- Nodebridge `nodebridge/main.js` runs the unmodified Anivexa worker on
  127.0.0.1:ephemeral and writes `bridge-port.json`.
- Wiring under test (exactly what TvMediaRepository/AnivexaApi do):
  1. `/search?q={title}` → AniList GraphQL search → validated anilistId
  2. `/episodes/{provider}/{anilistId}` → sub/dub episode list
  3. `/watch/{provider}/{anilistId}/{audio}/{provider}-{ep}` → streams[]

## Steps
- [x] Read AppReleaseConfig (API_BASE_URL), root package.json start script,
      nodebridge/index.js, server/anivexa/index.js, server/index.js mount/port
- [x] Start nodebridge → listening on 127.0.0.1:45027 (bridge-port.json)
- [x] Start app server → PORT=3150, /api/anivexa mounted (server/anivexa-handlers.js)
- [x] Test via APP SERVER (the app's own fallback wiring): search → episodes → watch → stream
- [x] Test via NODEBRIDGE (embedded path): watch route resolves streams
- [x] Confirm the stream URL is a real playable HLS source
- [x] Record results in this file; report to user

## RESULTS — "Kimetsu no Yaiba: Infinity Castle Arc" via the APP'S OWN wiring

Driven ONLY through our own servers (app server `localhost:3150` + nodebridge
`127.0.0.1:45027`). No external probes of the Anivexa repo.

### 1. App server path (WHAT THE DEPLOYED APP USES: API_BASE_URL + "/anivexa") ✅ FULLY WORKS
- `GET /api/anivexa/search?q=kimetsu no yaiba infinity castle`
  → `{ anilistId: "178788", title: "Demon Slayer: Kimetsu no Yaiba Infinity Castle" }`
  (AniList GraphQL via server/anivexa-handlers.js → pickBestAnilistCandidate → validated id)
- `GET /api/anivexa/episodes/anineko/178788?map=true`
  → `{ ok, data: { sub: [anineko-1], dub: [anineko-1] } }` (matches AnivexaApi.fetchEpisodes parsing)
- `GET /api/anivexa/watch/anineko/178788/sub/anineko-1`
  → `{ ok, data: { streams: [ 11 ] } }`
  → preferred: `https://morning-credit-3bcc.vibevibe.workers.dev/age218afb59372a2066715cdcb81e1ace84h/master.m3u8` (type hls, isActive, priority 11)
- Stream probe: HTTP 200, `application/vnd.apple.mpegurl`, `#EXTM3U` with
  `#EXT-X-STREAM-INF` 1080p + 720p variants → genuinely playable.

### 2. Nodebridge embedded path (loopback worker) ⚠️ STREAMS RESOLVE BUT ENVELOPE MISMATCH
- `GET /episodes/anineko/178788?map=true` on :45027 → raw worker shape
  `{ page, type, mappings, anineko }` — NO `ok` / `data` envelope.
  AnivexaApi.fetchEpisodes requires `root["ok"] == true` + `root["data"]`
  → on the embedded baseUrl, fetchEpisodes would return an EMPTY list.
- `GET /watch/anineko/178788/sub/anineko-1` on :45027 → `{ anilistId, episode,
  providerEpisode, audio, streams }` with the SAME 11 streams incl. the active
  HLS. But resolveStream also requires `ok` / `data` → would return null on
  the embedded baseUrl as currently parsed.

### Verdict
- The app's production/fallback wiring (app backend `/api/anivexa/*`) is
  CORRECTLY set up: AniList-search → episodes → playable HLS all work for
  Infinity Castle (anilist 178788) and match exactly what AnivexaApi parses.
- The nodebridge loopback worker resolves the same streams, but its raw
  response envelope does NOT match AnivexaApi's `{ ok, data }` expectation.
  If the embedded path is ever enabled (setEmbeddedBaseUrl), episodes/stream
  parsing will silently empty-out unless the bridge normalizes responses
  (wrap in `{ ok: true, data: ... }`) or AnivexaApi is taught both shapes.
  This is a REAL integration gap worth a follow-up fix — not a provider issue.
