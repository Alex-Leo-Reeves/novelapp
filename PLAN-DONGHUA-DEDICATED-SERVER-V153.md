# PLAN — Dedicated Donghua Server (donghuaworld.com) — V153

Status: ✅ DONE

## Decision (evidence-based, 2026-09-26)

**Chosen source: `donghuaworld.com`**

All six/seven candidates were probed live from this machine with plain HTTP (no browser
automation needed after the first pass) and cross-checked with a full HLS
manifest → variant → segment → subtitle fetch chain.

| Candidate | Player stack found | Server-side resolvable? | Verdict |
|---|---|---|---|
| **donghuaworld.com** | `playing.donghuaworld.in` own player → **public Rumble HLS** + Dailymotion fallback | ✅ **Zero headers**, `ACAO: *`, unencrypted TS, 18 VTT subs | ✅ **WINNER** |
| luciferdonghua.in | Dailymotion, Rumble, VidHide, OK.RU, misterdonghua | ⚠️ Dailymotion needs an exact CDN header fingerprint + 10-min signed token | Runner-up |
| animekhor.org | `dailymotion.com/embed/video/{id}` only | ⚠️ same Dailymotion fingerprint problem | Runner-up |
| myanime.live | Dailymotion + OK.RU | ⚠️ same; also one WP *post* per episode → no reliable series episode list | Weak |
| donghuastream.org | Dailymotion only | ⚠️ same | Weak |

### Why donghuaworld.com wins (verified, not assumed)

1. **Every** episode page carries TWO independent servers (`data-hash` base64 iframes):
   `geo.dailymotion.com` **and** `playing.donghuaworld.in/{id}`.
2. The own player's inline `jwplayer(...).setup({...})` config contains a **plain public
   Rumble HLS master** — `https://rumble.com/hls-vod/{id}/playlist.m3u8`.
3. That URL returns **HTTP 200 with ZERO request headers** — no `Referer`, no `Origin`,
   no `User-Agent`. Verified 6/6 across `no headers`, VLC UA, ExoPlayer UA, AVPlayer UA,
   empty UA, Chrome UA. (Dailymotion by contrast returns 403 unless the request carries
   `Referer`+`Origin`+`Accept`+`Accept-Language` **all four** — 4/4 deterministic.)
4. `access-control-allow-origin: *` → the web player (Android TV / Vidaa / iOS web) can
   play it without a proxy or CORS gymnastics.
5. Master advertises **1440p/1080p/720p/480p/360p/240p**; variants are **unencrypted**
   (`#EXT-X-KEY` count = 0) with valid MPEG-TS segments (0x47 sync byte verified, 6.5 MB
   first segment).
6. **18 subtitle tracks** (incl. English) served as public VTT from `hugh.cdn.rumble.cloud`,
   also zero-header.
7. Clean scrape surface: WP REST search (`/wp-json/wp/v2/search`) + server-rendered
   episode list markup `div.eplister li` → `.epl-num` / `.epl-title` / `.epl-date` / `.epl-sub`.

Live proof (5/5 episodes across 4 different shows — BTTH S5 ep211/ep212, Renegade
Immortal ep159, Perfect World ep227, Soul Land 2 ep119):

```
servers: ['dailymotion', 'own']
master HLS : https://rumble.com/hls-vod/Nu_btcn8avc/playlist.m3u8
qualities  : ['1440p','1080p','720p','480p','360p','240p']
subtitles  : 18 -> ['Vietnamese','Polish','Portuguese', ... 'English','Khmer']
master fetch (zero headers): HTTP 200  variants=6
variant fetch: HTTP 200  segments=106  keys=0
segment fetch: HTTP 200  bytes=6552177  ts_magic=True
```

## Architecture

Playback target per platform — all four get the **same plain HLS URL**, so no player
needs a website, a WebView, or an embed page:

| Platform | Player | Why it works |
|---|---|---|
| Android | ExoPlayer (media3) | `.m3u8` → `isDirectPlayableMediaUrl()` true; HLS module already wired |
| Android TV | LibVLC (`TvPlayerScreen`) | public HLS; no headers needed |
| iOS | AVPlayer (`IosOnlinePlayer`) | AVFoundation plays public HLS natively |
| Web (TV browser) | `hls.js` / native | `ACAO: *` |

### Backend — `server/donghua-handlers.js` (NEW, CommonJS, zero new deps)

Routes under `/api/donghua/`:

| Route | Purpose |
|---|---|
| `GET /api/donghua/search?q=` | WP REST search (`subtype=anime`), HTML `?s=` fallback |
| `GET /api/donghua/series?url=` / `?title=` | series meta + full episode list (number/title/date/sub) |
| `GET /api/donghua/watch?url=` | resolve an episode page → streams + subtitle tracks |
| `GET /api/donghua/play?title=&ep=` | one-call: title+episode → ready-to-play stream |
| `GET /api/donghua/proxy?url=&h=` | HLS playlist + segment proxy (relative-URI rewrite, Range) |
| `GET /api/donghua/subtitle?url=` | CORS-safe VTT relay for the web player (native clients use the CDN URL) |

`?title=` resolution is **ranked**: candidates are probed in match order and the
first page that really has an episode grid wins, so hub/batch pages can never
leave the app with an empty episode list (see V153.1 below).

Resolution order inside `watch`:
1. `playing.donghuaworld.*` own player → Rumble HLS (direct, no headers) — **primary**
2. Dailymotion `player/metadata` with the exact 4-header fingerprint → m3u8 — **fallback**
3. Either way the response also carries a `proxyUrl` so any client can fall back to a
   same-origin stream if its network blocks the CDN.

### Client — `composeApp/.../data/DonghuaApi.kt` (NEW)

Ktor client mirroring `AnivexaApi`: `searchSeries`, `fetchEpisodes`, `resolveStream`.

### Wiring

| File | Change |
|---|---|
| `data/MaServerSource.kt` | add `DonghuaServer.DONGHUAWORLD` as chip #1 + default selector head |
| `data/ParallelStreamResolver.kt` | top-scored donghua candidate (score 1200 when direct) |
| `ui/MediaDetailScreen.kt` | episode list + resolve branch + default server |
| `tvApp/.../data/TvMediaRepository.kt` | episode list + resolve branch + default server |
| `tvApp/.../ui/screens/TvDetailScreen.kt` | default donghua server |

## Verification checklist

- [x] Live probe of all candidates; winner picked on measured evidence
- [x] Zero-header playback proven for 6 client UA profiles
- [x] Variant + TS segment + VTT subtitle fetch proven
- [x] `node --check server/donghua-handlers.js` + `node --check server/index.js`
- [x] Live end-to-end run of every new route against the real site
- [x] Kotlin compile of `composeApp` + `tvApp`
- [x] (V153.1) Hub/batch series pages fall back to a season with a real episode grid
- [x] (V153.1) `/api/donghua/subtitle` relay + `proxyUrl` on every track
- [x] (V153.1) Proxy base scheme derived from the socket, not assumed `https`
- [x] (V153.1) Real-browser E2E: playback (1920x816, non-black frame) + 18 tracks, EN showing 291 cues
- [x] (V153.1) Zero-header raw master + raw EN VTT fetch (ExoPlayer / LibVLC / AVPlayer path)

---

## V153.1 — robustness pass (2026-09-26, after live re-testing of the app path)

Re-testing the exact endpoints the app calls surfaced three defects, each of
which would have shown up to the user as "donghua doesn't work". All three are
fixed and re-verified against the live site.

### 1. Empty episode list when search ranks a hub/batch page (FIXED)

`?title=Battle Through the Heavens` ranked the **batch/hub** page first
(`/anime/battle-through-the-heavens/`, og:title "…Season 2"), whose markup has no
`div.eplister li` grid at all → `parseEpisodes()` returned 0 episodes → the
detail screen showed an empty episode list and nothing to play.

* New `resolveBestSeriesForTitle(title)`: walks the ranked search candidates and
  returns the first page that actually yields episodes. For this title it now
  resolves `/anime/battle-through-the-heavens-season-5/` → **100 episodes
  (113–212)**, and `/play?…&ep=212` resolves episode 212 with 7 streams + 18 subs.
* `GET /series?url=<hub page>` now falls back to the same ranked search using the
  page's own `og:title`, so callers holding a hub URL also get a playable season.
* `GET /play?title=&ep=` uses the same resolver (no more "No episodes found").

### 2. No CORS-safe subtitle route for browsers (FIXED)

Native players read the CDN VTT directly (zero-header fetch verified → HTTP 200,
`WEBVTT`, 291 cues), but a browser silently drops a cross-origin `<track>` without
`Access-Control-Allow-Origin`.

* New `GET /api/donghua/subtitle?url=` — host-allowlisted, 1 retry, 30 s timeout,
  serves `text/vtt; charset=utf-8` with `ACAO: *`.
* Every subtitle entry in `/watch` and `/play` now carries a same-origin
  `proxyUrl`; native clients keep using the raw CDN `url`.

### 3. Proxy URLs were hard-coded to `https://` (FIXED)

`proxyBaseFrom()` assumed `https` whenever `x-forwarded-proto` was absent, so a
direct plain-HTTP request received unplayable `https://host:port/...` proxy URLs.
It now falls back to the socket's real encryption state (production behind the
Render/Netlify TLS terminator is unchanged).

### Player-side wiring

| File | Change |
|---|---|
| `site/tvv/player.js` | `attachSubtitleTracks()` + `showDefaultSubtitle()`: attaches the source VTT tracks, marks **only** the English track `default` (18 languages never fire 18 parallel requests), switches EN to `showing`; a failing track cannot touch the video element |
| `site/tvv/api.js` | `donghuaSubtitleUrls()` prefers the CORS-safe `proxyUrl` (raw URL kept as `rawUrl`) |

### 4. Dailymotion was unreachable from Node (FIXED)

Some episode pages carry **no own player at all** — only a Dailymotion embed — and
those episodes returned "no playable stream" even though the video is alive.
Root cause: **dmcdn's edge blocks Node's TLS fingerprint**. The identical URL +
header set returns `403` from Node (HTTP/1.1 *and* HTTP/2, every header variant
tried) while `curl` returns `200` in the same second.

* Dailymotion content is now fetched through the **system curl binary**
  (`curlGetText`, `curlUpstream`); playlists are still rewritten to stay inside
  our proxy, and media is streamed without buffering.
* When curl is unavailable the DM path reports "not playable" and is dropped,
  exactly like before — a missing binary can never break playback.
* Result: BTTH S5 ep113 and Renegade Immortal ep53 (both previously unplayable)
  now resolve. Swallowed Star ep196 is a **404 page on the source itself** and
  Soul Land 2 3D ep65 is a **Dailymotion video removed for ToU breach** (DM005) —
  those two are source-side gaps, and the app falls back to its other chips.

### 5. In-manifest subtitles were not selected (FIXED)

Dailymotion masters carry `#EXT-X-MEDIA:TYPE=SUBTITLES` renditions (11 languages
incl. English, 238 cues verified). `site/tvv/player.js` now sets
`subtitleDisplay: true` and auto-selects English on `SUBTITLES_TRACKS_UPDATED`
(guarded so it never overrides an existing selection). Native players get those
renditions straight from the manifest — this is also how **iOS (AVPlayer) gets
subtitles**, since the Rumble master has no embedded renditions.

### Real-browser E2E for both servers (headless Chromium, hls.js@1.5.7)

```text
RUMBLE path (ep 212): played=True 1920x816 t=3.6s fatal=None
                      frame avg 106/255, non-black ratio 0.95
                      18 source VTT tracks, English showing, 291 cues        -> PASS
DM path (ep 113)    : played=True 1280x544 t=3.5s fatal=None
                      frame non-black ratio 1.00
                      11 manifest subtitle renditions, English showing, 238 cues -> PASS
```

Regression harness kept in `scripts/donghua-webplayer-e2e.py`
(`python3 scripts/donghua-webplayer-e2e.py /path/to/play.json`).

### Route evidence (live, this machine)

```
GET /api/donghua/series?title=Battle Through the Heavens -> matched "…Season 5", 100 eps (113..212)
GET /api/donghua/series?url=<hub page>                   -> matched "…Season 5", 100 eps
GET /api/donghua/play?title=…&ep=212                     -> 7 streams + 18 subs (proxyUrl each)
GET /api/donghua/play?title=Perfect World&ep=227         -> main HLS + 18 subs
GET /api/donghua/watch?url=<ep212 page>                  -> "Donghuaworld (Rumble)" + 18 subs (+EN)
GET /api/donghua/subtitle?url=<EN vtt>                   -> 200 text/vtt, ACAO:*, 291 cues
GET /api/donghua/proxy?url=<master>                      -> 200, variants rewritten to /api/donghua/proxy
GET /api/donghua/proxy?url=<1440p variant>               -> 200, segments rewritten to /api/donghua/proxy
GET /api/donghua/proxy?url=<segment>                     -> 200 video/mp2t, 10.3 MB, TS sync byte 0x47
GET /api/donghua/proxy?url=<dm master>&h=dm              -> 200, variants/segments/sub renditions rewritten (&h=dm)
GET /api/donghua/proxy?url=<dm segment>&h=dm             -> 200 video/mp2t, 2.5 MB, TS sync byte 0x47
GET /api/donghua/proxy?url=<dm EN sub playlist>&h=dm     -> 200, VTT with 238 cues
RAW master, zero headers                                 -> 200 (direct playback with no proxy)
RAW EN VTT, zero headers                                 -> 200 WEBVTT (ExoPlayer / LibVLC / AVPlayer)
```

### Playback matrix (what each platform gets)

| Platform | Rumble stream (own player) | Dailymotion stream |
|---|---|---|
| Android ExoPlayer | direct HLS + 18 `SubtitleConfiguration` tracks | proxied HLS + 11 manifest renditions |
| Android TV LibVLC | direct HLS + English `IMedia.Slave` | proxied HLS + manifest renditions |
| iOS AVPlayer | direct HLS (no embedded subs) | proxied HLS **with English renditions** |
| Web (hls.js) | direct/proxied HLS + 18 `<track>`s (EN default) | proxied HLS + manifest renditions (EN auto-selected) |

### Known limitations (source-side, not player-side)

* The Rumble master ships its subtitles as **separate files**, so iOS (AVPlayer)
  has no legible track on that particular stream; the Dailymotion stream carries
  in-manifest renditions and therefore **does** give iOS English subtitles.
* A few old episode pages are dead **at the source**: some are 404s the site's own
  grid links to, others point at Dailymotion videos removed for a Terms-of-Use
  breach (`DM005`). For those the resolver returns a clear error and the app's
  other donghua chips (VidLink / VidSrc / AniNeko / AnimeXin) take over.


### CDN-block immunity — per-player fallbacks (V153 final)

A public Rumble HLS needs **no** headers, which is why it was chosen — but a
device network can still refuse to reach `rumble.com`. Every player therefore has
its own one-shot escalation to `/api/donghua/proxy`, so a CDN block can never end
in a black screen or an error card:

| Player | Fallback trigger | Result |
|---|---|---|
| Android ExoPlayer | `onPlayerError` + URL is a donghua CDN host (**no headers required**) | `resolvedUrl` rebuilt as `/api/donghua/proxy?url=…`, player recreated |
| Android TV LibVLC | `MediaPlayer.Event.EncounteredError` | `proxyRetryUrl` set → `remember(resolvedUrl)` rebuilds the player on the proxy URL |
| iOS AVPlayer | Retry button (AVPlayer reports no CDN error) | `useProxy` → `effectiveUrl` becomes `/api/donghua/proxy?url=…` |
| Web hls.js | first candidate in the resolver list is already the proxy | proxy first, raw as second candidate |

Note the Android gate is `headers.isNullOrBlank() **or** donghua CDN host`: the
donghua source legitimately passes no headers, so the old header-only gate would
have skipped its fallback entirely.

### TV subtitles for TMDB-sourced donghua

`TvMediaRepository.resolveDonghuaSubtitleUrl(episodeUrl, title, episodeNumber)`
now resolves the ENGLISH VTT by **title + episode number** when the chapter has no
donghuaworld URL (TMDB-sourced rows), not just when an episode page is attached.
`TvBingeSession` passes `item.title` / `chapter.chapterNumber`, and LibVLC attaches
the track as an `IMedia.Slave.Subtitle`.

### Dead-episode behaviour (verified in a real browser)

`battle-through-the-heavens-season-5-episode-120` was diagnosed with Playwright
against the live site: the site's own player iframe
(`playing.donghuaworld.in/v5l88xk`) ships `sources: []` and the page issues
**0 media requests** (vs 15 for ep212). The episode is dead at the source, not a
resolver bug. The backend correctly refuses to advertise a stream, and the app's
parallel sweep then falls through to the TMDB-embed servers, so the user still
gets playback instead of a dead end.

```
Coverage sweep, 10 episodes across 3 series: 9 resolved with a proxied HLS,
1 (ep120) dead at source -> embed fallback. Every resolved stream was reachable
raw with ZERO headers and served `access-control-allow-origin: *`.
```

