# PLAN — ASIAN TAB: dedicated servers for Chinese Movies, Indian, Filipino (V154)

## 1. Why each region got its own server

Measured 2026-09-27 by driving a real Chromium at each embed and counting the
media requests it actually made. A title only counts as covered if the embed
pulled a real stream for it.

| Server (app chip) | host | INDIAN | CHINESE | FILIPINO |
|---|---|---|---|---|
| Nebula | vidlink.pro | 6/10 | 6/11 | 4/9 |
| Quasar | vidsrc.to | 0/4 | 0/3 | 0/4 |
| Pulsar | autoembed.co | 0/4 | 0/3 | 0/4 |
| Comet | 2embed.online | 0/4 | 0/3 | 0/4 |
| Lyra | multiembed.mov | 0/4 | 0/3 | 0/4 |
| Orion | nontongo.win | 0/4 | 0/3 | 0/4 |
| Astra | vidsrc.sbs | **4/4** | 0/3 | 0/4 |
| _(not in app)_ | vidsrc.su | 3/4 | 0/3 | 0/4 |

Titles that Nebula failed and the matrix re-tested: RRR, Baahubali 2, Jawan,
Dangal (Indian); The Wandering Earth, The Battle at Lake Changjin, Hero 2002
(Chinese); Hello Love Goodbye, The Hows of Us, Barcelona, Rewind (Filipino).

**Conclusion: no single existing server covers a region.** Filipino has no
TMDB-embed coverage at all, so each region needed its own dedicated source.

## 2. The servers chosen

### Chinese Movies + Indian → `ikanbot.com` (direct HLS)

A server-rendered aggregator with an open JSON API. The player token is derived
from two DOM values on the play page; the algorithm was recovered from the
obfuscated `play_new.js` and reproduces the live token **byte-for-byte**:

```
tail = videoId.slice(-4)
for (ch of tail) { i = parseInt(ch) % 3 + 1
                   part = eToken.slice(i, i+8); eToken = eToken.slice(i+8) }
```

Verified pair (videoId `398682`):

```
e_token "ttz9d03786d76a733763sozd9138498jftf2eb4781"
     -> "9d03786d6a733763d9138498f2eb4781"   (matches the live token exactly)
```

Flow: `GET /search?q=` → `/play/<id>` (id + e_token) → `GET /api/getResN` →
`data.list[].resData` → `[{flag,url}]` → probe each URL → **direct HLS**.

Measured live as routes found / routes that really loaded:

```
Chinese : 流浪地球 32/6 · 哪吒之魔童闹海 30/7 · 满江红 27/7 · 英雄 14/8
Indian  : RRR 26/7 · 三傻大闹宝莱坞 30/7 · 巴霍巴利王 41/5 · 帕坦 26/7
          小萝莉的猴神大叔 26/7 · 误杀瞒天记 37/7 · Jawan 1/1 · Dangal 1/1
Filipino: 菲律宾 12/9 · Hello Love Goodbye 5/1 · The Hows of Us 7/1
```

The streams are **public HLS with AES-128** and need **zero request headers**
(verified: master 200, key 200 / 16 bytes, segment 200 / 1.2 MB MPEG-TS), so
ExoPlayer, LibVLC, AVPlayer and hls.js all play them with no WebView.

**Title matching for non-English catalogs.** The library is indexed under
Chinese release names, so a TMDB title alone misses a lot (Dangal is only
findable as 摔跤吧！爸爸). The backend pulls TMDB `alternative_titles` and tries
the region-preferred names first — that is what makes the lookup work for a
whole catalog instead of a hand-written alias list.

### Filipino mainstream → official YouTube channels

ABS-CBN Star Cinema / Viva / Regal publish **full feature-length** movies on
YouTube (verified: Hello, Love, Goodbye 1:51:37 · Barcelona 2:05:31 · Can't Help
Falling in Love 1:53:11). The catalog is read from YouTube's own `ytInitialData`
over plain HTTP and played by the app's YouTube player, so mainstream Filipino
titles never depend on a third-party CDN. **Piped is not used** — its public
instances are dead (only 1 of 6 answered, and it returned an HTML error page).

> Deliberately NOT used: `cinema.com.ph`, `pinoymoviepedia.ru`, `fullpinoymovies.com`.
> They are Bold/Vivamax catalogues and their players are voe.sx / doodstream /
> playmogo ad-gates — unsafe content for an app with a Kids mode, and unstable
> technically. `einthusan.tv` blocks bots ("Website Crashed"), `hdhub4u.club` is
> a review blog with no player, and the Chinese resource APIs (ffzy/dytt/uku/
> bdzy/wujin) returned 0 results for Indian and Filipino English titles.


## 3. Implementation

**Backend — `server/asian-handlers.js`** (new, ~1030 lines), registered in
`server/index.js` before the auth routes:

```
GET /api/asian/play?title=&region=chinese|indian|filipino&tmdbId=&type=&youtube=
GET /api/asian/search?q=&region=
GET /api/asian/youtube?q=&limit=        (official Filipino channels)
GET /api/asian/proxy?url=               (HLS proxy, playlist rewriting)
GET /api/asian/health
```

`/play` answers with probed-playable streams plus a same-origin `proxyUrl` for
every one, so a blocked CDN can never black-screen playback. The proxy rewrites
`#EXT-X-KEY` / `#EXT-X-MAP` / `#EXT-X-MEDIA` URIs and resolves relative URIs
against the playlist URL first (otherwise a relative `enc.key` would resolve
against our own host) — verified live: key 200 / 16 bytes, segment 200 / 260 KB.

Guards: only a URL that returned a real playlist is advertised, adult titles are
filtered out of every catalog and result path (the app has a Kids mode), and the
proxy is restricted to http(s) targets.

**Content catalogs** — three new types in `normalizeContentType` + `tmdbItems` +
`contentHome` (`chinesemovies`, `indian`, `filipino`). The Asian regions are
labelled `movie` (they are movie catalogs; a `tv` label would make the detail
page treat films as series). TMDB `discover` queries: `zh` language + HK/TW
origin for Chinese, `with_origin_country=IN` for Indian, `with_origin_country=PH`
+ `tl` language for Filipino — measured 20 001 Chinese movies, 20 001 Indian
movies / 4 147 series, 9 933 Filipino movies / 1 889 series.

**Client** — `data/AsianApi.kt` (new), `VideoCategory.CHINESE_MOVIES / INDIAN /
FILIPINO` (+ `isAsian`, `asianRegionKey`), three TMDB discover fetchers in
`TmdbSource`, and `HomeFeedRepository.asianRow`.

**UI** — `ui/AsianHomeScreen.kt` (new): the header says **Asian** and the body is
three rows (Chinese Movies · Indian · Filipino), each labelled with the server it
plays on. `BottomTab.ASIAN` + a `Public` nav item, routed in `App.kt`.
`MediaDetailScreen` resolves these kinds through `AsianApi` and sends the HLS URL
straight to `tryPlayStream` (ExoPlayer / AVPlayer) — no WebView, no redirect; the
Filipino YouTube fallback goes to the app's embed player so it never leaves the app.

**TV** — `TvSection.ASIAN` (`fetchAsianHome()` merges the three regions), the
sidebar entry, the `Public` icon, and remote-config aliases
(`asian`, `chinese`, `indian`, `filipino`, `pinoy`).

**Home-tab bleed** — exactly two rows, as asked ("not too much"):
**Chinese Movies** and **Indian Cinema**, both playing through their region's own
server. Filipino stays in the Asian tab.

## 4. Playback test (real browser, `scripts/asian-webplayer-e2e.py`)

```
python3 scripts/asian-webplayer-e2e.py http://127.0.0.1:8792 chinese indian filipino

chinese   PASS  流浪地球   1920x752   t=3.07  bestNonBlackRatio 1.000
indian    PASS  RRR        1920x1008  t=3.07  bestNonBlackRatio 0.194  (dim scene)
filipino  PASS  你好，再见  1280x720   t=6.09  bestNonBlackRatio 0.756
RESULT: 3/3 regions play
```

The harness samples the canvas repeatedly rather than once, because a single
instant can land on a genuinely dark scene: a first run sampled the Filipino
stream during a dark shot and reported `avg 0`. A 12-sample trace of that same
stream proves it is real video, not a black screen —

```
t=0.7s  avg=16.7  nonBlack=0.17   t=20.8s avg=1.1  nonBlack=0.01  (dark scene)
t=8.7s  avg=81.6  nonBlack=0.76   t=28.8s avg=55.9 nonBlack=0.76
t=16.7s avg=68.7  nonBlack=0.76   t=44.9s avg=40.5 nonBlack=0.73
buffer reached 272s ahead, zero fatal hls.js errors
```

## 5. Backend route evidence (live, this machine)

```
GET /api/asian/health                       -> regions chinese|indian|filipino
GET /api/asian/search?q=RRR&region=indian   -> 2 hits (RRR, rrr：幕后与超越)
GET /api/asian/play?title=RRR&region=indian&tmdbId=579974
                                            -> matched "RRR", 26 routes, 5-7 playable
GET /api/asian/play?title=Dangal&region=indian&tmdbId=360814
                                            -> matched "摔跤吧！爸爸" via TMDB alt titles, 10 playable
GET /api/asian/play?title=Ne Zha 2&region=chinese&tmdbId=980477
                                            -> matched "哪吒之魔童闹海", 4 playable
GET /api/asian/play?title=Hello Love Goodbye&region=filipino&tmdbId=593961
                                            -> matched "你好，再见", 1 playable
GET /api/asian/proxy?url=<master>           -> 200, 442 KB playlist, URIs rewritten
GET /api/asian/proxy?url=<enc.key>          -> 200, 16 bytes (AES-128 key)
GET /api/asian/proxy?url=<segment>          -> 200, video/mp2t
GET /api/asian/youtube?q=Hello Love Goodbye -> BvMJ0fNzIok (ABS-CBN Star Cinema, 1:51:37)
GET /api/content/home?type=chinesemovies    -> 20 items (The Scavenger, The Shadow's Edge…)
GET /api/content/home?type=indian           -> 20 items (Modha Rathri, Vishwanath & Sons…)
GET /api/content/home?type=filipino         -> 20 items (Hibla 2, Kesong Puti, Desperada)
```

## 6. Known behaviour

* **A dark scene is not a black screen.** Some streams have near-black shots;
  the E2E samples over time so this is never misreported. The app's players show
  the same frames either way.
* **Coverage is per title.** If a title is genuinely absent from the region
  server, `/play` says so and the UI asks the user to try another title — it
  never leaves a spinner or a black frame.
* **TMDB credentials matter.** The old codebase-wide fallback key
  `15d2ea…507cc` is a dead placeholder (TMDB answers 401 "Invalid API key"), so
  `asian-handlers.js` prefers `TMDB_API_KEY` / `TMDB_READ_ACCESS_TOKEN` and only

## 7. English search fix (V154.1)

Reported: *"i am seeing a lot of chinese hope if i search with english it will work"*.

Root cause found and fixed — three separate defects, all of which made an English
query behave wrongly:

1. **`contentSearch` ignored the region.** Its TMDB branch was a hardcoded list
   (`anime, donghua, kdrama, cartoon, classic, movies, nigerian`). The three new
   region types were not in it, so a region search fell through to the global
   "everything" sweep and returned unrelated titles — including Chinese ones.
   Fixed by adding `chinesemovies`, `indian`, `filipino` to that list.

2. **The phone app only searched Movies + Nollywood**, so English Asian titles
   were surfaced as plain `Movies` and then played through VidLink (which meas-
   ured 0/4 on Indian and 0/4 on Filipino). The Discover search now fans out to
   all three regions in parallel and shows an **Asian** section whose items carry
   the region `mediaKind`, so they route to the region's own server.

3. **The region kind was invisible to the TV app.** `toUnifiedResult` computed
   `isVideo` from a hardcoded kind list that did not include the region kinds, so
   TV treated them as non-video. Fixed, and `TvMediaRepository` now resolves the
   region kinds through `AsianApi` (direct HLS, or the YouTube embed fallback).

Also added: adult-title filtering on the client search path (the app ships a
Kids mode, so this must not depend on a user setting), and region labels on the
server (`Chinese Movies` / `Indian` / `Filipino` instead of a generic `Movie`).

Verified — English queries now return the right region's English-titled results:

```
GET /api/content/search?type=indian&q=Jawan        -> 33 hits: Jawan / Jawani Zindabad / Jawan Muhabat
GET /api/content/search?type=chinesemovies&q=Ne Zha -> 10 hits: Ne Zha / Ne Zha 2 / Ne Zha 3
GET /api/content/search?type=filipino&q=Hello Love  -> 21 hits: Hello Love / Hello, Love, Goodbye / Hello, Love, Again
```

(All before the fix returned the same unrelated mixed list for every region.)

### Why the *stream* side still shows Chinese names

The source for Chinese/Indian streams indexes its library under Chinese release
names, so the *matched title* the backend reports is Chinese (流浪地球, 摔跤吧！爸爸,
你好，再见). That is only diagnostics — the app always displays the **TMDB English
title** from the catalog (`item.title`), never the matched source name. The
Chinese name is used internally as a search key, which is exactly what makes an
English query succeed (Dangal is only findable upstream as 摔跤吧！爸爸).

### `when` exhaustiveness (compile safety)

Adding three `VideoCategory` values breaks every exhaustive `when` on that enum.
Scanned the whole tree and fixed all of them:
`TmdbSource` (fetchVideo, searchVideo), `NovelSearchRepository`
(search-multi filter, `backendContentType`), plus a stale duplicate
`VideoCategory.DONGHUA -> "donghua"` branch in `backendContentType` that is now
replaced. `App.kt`'s `when(currentTab.value)` is a statement with every tab listed,
so the new `ASIAN` tab is covered there too.

  then a known-working key. Production already sets these.
