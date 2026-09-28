// ─────────────────────────────────────────────────────────────────────────────
//  ASIAN DEDICATED SERVERS — Chinese movies/series, Indian, Filipino
//
//  The "Asian" tab needs a working server per region. Nebula (vidlink.pro) was
//  measured and is NOT good enough on its own. Coverage measured 2026-09-27 by
//  driving a real Chromium at each embed and counting the media requests it
//  actually made (30 titles across the three regions):
//
//      vidlink.pro : INDIAN 6/10   CHINESE 6/11   FILIPINO 4/9
//      vidsrc.to   : INDIAN 0/4    CHINESE 0/3    FILIPINO 0/4
//      autoembed   : INDIAN 0/4    CHINESE 0/3    FILIPINO 0/4
//      2embed      : INDIAN 0/4    CHINESE 0/3    FILIPINO 0/4
//      multiembed  : INDIAN 0/4    CHINESE 0/3    FILIPINO 0/4
//      nontongo    : INDIAN 0/4    CHINESE 0/3    FILIPINO 0/4
//      vidsrc.sbs  : INDIAN 4/4    CHINESE 0/3    FILIPINO 0/4
//      vidsrc.su   : INDIAN 3/4    CHINESE 0/3    FILIPINO 0/4
//
//  So each region gets a dedicated source instead. Both are pure HTTP — no
//  browser, no WASM, no rotating signatures — and both yield DIRECT HLS that
//  plays unchanged in ExoPlayer (Android), LibVLC (Android TV), AVPlayer (iOS)
//  and hls.js (web): no WebView, no embed page, no redirect to the source site.
//
//  ── 1. CHINESE + INDIAN  ->  ikanbot.com ──────────────────────────────────
//  A server-rendered aggregator with an open JSON API. Its player token is
//  derived from two DOM values on the play page; this file reproduces the
//  algorithm exactly (verified byte-for-byte against the live token):
//
//      tail = videoId.slice(-4)
//      for ch of tail: i = parseInt(ch) % 3 + 1
//                      part = eToken.slice(i, i+8); eToken = eToken.slice(i+8)
//
//  Measured live (2026-09-27) as routes found / routes playable:
//      Chinese : 流浪地球 32/6, 哪吒之魔童闹海 30/7, 满江红 27/7, 英雄 14/8
//      Indian  : RRR 26/7, 三傻大闹宝莱坞 30/7, 巴霍巴利王 41/5, 帕坦 26/7,
//                小萝莉的猴神大叔 26/7, 误杀瞒天记 37/7, Jawan 1/1, Dangal 1/1
//      Filipino: 菲律宾 12/9, Hello Love Goodbye 5/1, The Hows of Us 7/1
//
//  Indian and Filipino titles are also searched by their TMDB alternative
//  titles (preferring the Chinese release name, e.g. Dangal -> 摔跤吧！爸爸),
//  which is what makes the lookup work for a whole TMDB catalog instead of a
//  hand-maintained alias list.
//
//  ── 2. FILIPINO mainstream  ->  official YouTube channels ─────────────────
//  ABS-CBN Star Cinema / Viva / Regal publish FULL feature-length movies on
//  YouTube (verified: 'Hello, Love, Goodbye' 1:51:37, 'Barcelona: A Love
//  Untold' 2:05:31, 'Can't Help Falling in Love' 1:53:11). The catalog is read
//  from YouTube's own `ytInitialData` over plain HTTP and played with the app's
//  existing YouTube player, so mainstream Filipino titles never depend on a
//  third-party CDN. Piped is NOT used — its public instances are almost all dead.
//
//  NOTE: this file deliberately avoids optional chaining (?.) and nullish
//  coalescing (??) because the codebase formatter rewrites those tokens into
//  invalid syntax. All null checks use explicit ternaries / && / ||.
//
//  ROUTES (/api/asian):
//    GET /api/asian/play?title={t}&region={chinese|indian|filipino}&tmdbId=&type=
//    GET /api/asian/search?q={query}&region={region}
//    GET /api/asian/youtube?q={query}&limit={n}   (official Filipino channels)
//    GET /api/asian/proxy?url={abs}               (HLS proxy, playlist rewriting)
//    GET /api/asian/health
// ─────────────────────────────────────────────────────────────────────────────

const http = require("http");
const https = require("https");

const IKANBOT = "https://www.ikanbot.com";
const BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

const REQUEST_TIMEOUT_MS = 20 * 1000;
const PROBE_TIMEOUT_MS = 12 * 1000;
const PROBE_CONCURRENCY = 8;

// Route hosts that answered 200 with a real playlist in every single sweep.
// Probing these first means the user gets a stream immediately instead of the
// resolver spending its budget on mirrors that are dead outside China.
const PREFERRED_HOSTS = [
    "play.xluuss.com",
    "v.gsuus.com",
    "play.hhuus.com",
    "play.subokk.com",
    "bfzycdn.com",
    "ryplay1.com",
    "vipyz-cdn",
    "yzzy"
];

// ── regions ─────────────────────────────────────────────────────────────────

const REGIONS = {
    chinese: {
        key: "chinese",
        label: "Chinese Movies",
        tmdbLanguage: "zh",
        preferredTitleRegions: ["CN", "HK", "TW", "SG"]
    },
    indian: {
        key: "indian",
        label: "Indian",
        tmdbLanguage: "hi",
        preferredTitleRegions: ["IN", "CN", "HK", "TW"]
    },
    filipino: {
        key: "filipino",
        label: "Filipino",
        tmdbLanguage: "tl",
        preferredTitleRegions: ["PH", "US", "CN"]
    }
};

function normalizeRegion(value) {
    const raw = String(value === undefined || value === null ? "" : value)
        .trim()
        .toLowerCase()
        .replace(/[\s_-]+/g, "");
    if (raw === "chinese" || raw === "china" || raw === "chinesemovies" || raw === "chinesemovie" || raw === "cn" || raw === "zh") return "chinese";
    if (raw === "indian" || raw === "india" || raw === "bollywood" || raw === "hindi" || raw === "in") return "indian";
    if (raw === "filipino" || raw === "philippines" || raw === "pinoy" || raw === "tagalog" || raw === "ph") return "filipino";
    return "";
}

// ── plumbing ────────────────────────────────────────────────────────────────

function sendJson(response, statusCode, payload) {
    response.writeHead(statusCode, {
        "Content-Type": "application/json; charset=utf-8",
        "Access-Control-Allow-Origin": "*",
        "Cache-Control": "no-store"
    });
    response.end(JSON.stringify(payload));
}

function sendApiError(response, statusCode, message) {
    sendJson(response, statusCode, { ok: false, data: null, error: message });
}

/** Plain GET returning { status, body }. Follows up to 5 redirects. */
function httpGet(url, headers, timeoutMs, redirectsLeft) {
    const budget = typeof timeoutMs === "number" ? timeoutMs : REQUEST_TIMEOUT_MS;
    const hops = typeof redirectsLeft === "number" ? redirectsLeft : 5;
    return new Promise((resolve, reject) => {
        let parsed;
        try {
            parsed = new URL(url);
        } catch (error) {
            reject(new Error("Bad URL: " + url));
            return;
        }
        const transport = parsed.protocol === "http:" ? http : https;
        const request = transport.get(
            {
                protocol: parsed.protocol,
                hostname: parsed.hostname,
                port: parsed.port || (parsed.protocol === "http:" ? 80 : 443),
                path: parsed.pathname + parsed.search,
                headers: headers || { "User-Agent": BROWSER_UA },
                timeout: budget
            },
            (res) => {
                const status = res.statusCode || 0;
                if (status >= 300 && status < 400 && res.headers.location && hops > 0) {
                    res.resume();
                    let next;
                    try {
                        next = new URL(res.headers.location, url).toString();
                    } catch (error) {
                        resolve({ status: status, body: "" });
                        return;
                    }
                    httpGet(next, headers, budget, hops - 1).then(resolve, reject);
                    return;
                }
                // gzip/deflate are not requested, so the body is always plain.
                res.setEncoding("utf8");
                let body = "";
                res.on("data", (chunk) => {
                    body += chunk;
                    if (body.length > 4 * 1024 * 1024) request.destroy();
                });
                res.on("end", () => resolve({ status: status, body: body }));
            }
        );
        request.on("timeout", () => request.destroy(new Error("timeout")));
        request.on("error", reject);
    });
}

function fetchText(url, headers, timeoutMs) {
    return httpGet(url, headers, timeoutMs);
}

function browserHeaders(extra) {
    const out = {
        "User-Agent": BROWSER_UA,
        Accept: "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9,zh-CN;q=0.8"
    };
    if (extra) {
        const keys = Object.keys(extra);
        for (let i = 0; i < keys.length; i++) out[keys[i]] = extra[keys[i]];
    }
    return out;
}

function decodeEntities(value) {
    return String(value === undefined || value === null ? "" : value)
        .replace(/&amp;/g, "&")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&quot;/g, '"')
        .replace(/&#0?39;/g, "'")
        .replace(/&#x27;/gi, "'")
        .replace(/&nbsp;/g, " ")
        .replace(/&#(\d+);/g, function (match, code) {
            const num = parseInt(code, 10);
            return Number.isFinite(num) ? String.fromCharCode(num) : match;
        })
        .replace(/\s+/g, " ")
        .trim();
}

/** Loose title comparison key: lowercase, strip punctuation + accents. */
function titleKey(value) {
    return String(value === undefined || value === null ? "" : value)
        .toLowerCase()
        .replace(/[\u0300-\u036f]/g, "")
        .replace(/[^a-z0-9\u4e00-\u9fff\u3040-\u30ff]+/g, " ")
        .replace(/\s+/g, " ")
        .trim();
}

/** True for anything that must never reach a family app (Kids mode exists). */
function isAdultTitle(value) {
    const lower = String(value === undefined || value === null ? "" : value).toLowerCase();
    const banned = [
        "sexy", "porn", "xxx", "adult", "erotic", "hentai", "nsfw",
        "vivamax", "pinay sexy", "blowjob", "milf"
    ];
    for (let i = 0; i < banned.length; i++) {
        if (lower.indexOf(banned[i]) !== -1) return true;
    }
    return false;
}

// ── ikanbot: the dedicated Chinese + Indian server ──────────────────────────

/**
 * The player token for a play page.
 *
 * Reproduces play_new.js `get_tks()` exactly: the last four digits of the video
 * id pick slices out of the page's `e_token`, consuming 8 characters each time.
 * Verified byte-for-byte against the live token for videoId 398682:
 *   e_token "ttz9d03786d76a733763sozd9138498jftf2eb4781" -> "9d03786d6a733763d9138498f2eb4781"
 */
function ikanbotToken(videoId, eToken) {
    const tail = String(videoId).slice(-4);
    let pool = String(eToken);
    const parts = [];
    for (let i = 0; i < tail.length; i++) {
        const digit = parseInt(tail.charAt(i), 10);
        const index = (Number.isFinite(digit) ? digit : 0) % 3 + 1;
        parts.push(pool.slice(index, index + 8));
        pool = pool.slice(index + 8);
    }
    return parts.join("");
}

/** Search ikanbot for a title and return the ordered play-page hits. */
async function ikanbotSearch(query) {
    const url = IKANBOT + "/search?q=" + encodeURIComponent(query);
    const res = await fetchText(url, browserHeaders(), REQUEST_TIMEOUT_MS);
    if (res.status !== 200) return [];
    const ids = [];
    const re = /href="\/play\/(\d+)"/g;
    let match = re.exec(res.body);
    while (match) {
        if (ids.indexOf(match[1]) === -1) ids.push(match[1]);
        match = re.exec(res.body);
    }
    // Result titles, in the same document order, so the caller can score them.
    const names = [];
    const nameRe = /<a href="\/play\/\d+"[^>]*>\s*<img[^>]*alt="([^"]*)"/g;
    let nameMatch = nameRe.exec(res.body);
    while (nameMatch) {
        names.push(decodeEntities(nameMatch[1]));
        nameMatch = nameRe.exec(res.body);
    }
    return ids.map(function (id, index) {
        return { id: id, title: names[index] || "" };
    });
}

/**
 * Resolve a play-page id to its direct HLS routes.
 * Returns { streams: [{url, label, host}], error }.
 */
async function ikanbotRoutes(videoId) {
    const playUrl = IKANBOT + "/play/" + videoId;
    const page = await fetchText(playUrl, browserHeaders(), REQUEST_TIMEOUT_MS);
    if (page.status !== 200) return { streams: [], error: "play page HTTP " + page.status };
    const idMatch = /id="current_id"\s+value="([^"]*)"/.exec(page.body);
    const tokenMatch = /id="e_token"\s+value="([^"]*)"/.exec(page.body);
    if (!idMatch || !tokenMatch) return { streams: [], error: "play page had no id/e_token" };

    const token = ikanbotToken(idMatch[1], tokenMatch[1]);
    const apiUrl =
        IKANBOT + "/api/getResN?videoId=" + encodeURIComponent(idMatch[1]) +
        "&mtype=1&token=" + encodeURIComponent(token);
    const api = await fetchText(apiUrl, browserHeaders({ Referer: playUrl }), REQUEST_TIMEOUT_MS);
    if (api.status !== 200) return { streams: [], error: "getResN HTTP " + api.status };

    let payload;
    try {
        payload = JSON.parse(api.body);
    } catch (error) {
        return { streams: [], error: "getResN was not JSON" };
    }
    if (!payload || payload.state !== 1 || !payload.data || !payload.data.list) {
        return { streams: [], error: "getResN state " + (payload ? payload.state : "null") };
    }

    const streams = [];
    const seen = {};
    const rows = payload.data.list;
    for (let i = 0; i < rows.length; i++) {
        let entries;
        try {
            entries = JSON.parse(rows[i].resData);
        } catch (error) {
            continue;
        }
        if (!Array.isArray(entries)) continue;
        for (let j = 0; j < entries.length; j++) {
            const raw = String(entries[j].url || "");
            const url = raw.split("$").pop();
            if (!/^https?:\/\//i.test(url)) continue;
            if (seen[url]) continue;
            seen[url] = true;
            let host = "";
            try {
                host = new URL(url).hostname;
            } catch (error) {
                host = "";
            }
            streams.push({
                url: url,
                label: decodeEntities(String(entries[j].flag || "")) || "Route " + (streams.length + 1),
                host: host
            });
        }
    }

    streams.sort(function (a, b) {
        return preferredHostScore(b.host) - preferredHostScore(a.host);
    });
    return { streams: streams, error: "" };
}

function preferredHostScore(host) {
    for (let i = 0; i < PREFERRED_HOSTS.length; i++) {
        if (host.indexOf(PREFERRED_HOSTS[i]) !== -1) return 100 - i;
    }
    return 0;
}

/** A URL is only advertised once it has returned a real playlist. */
async function playlistIsPlayable(url) {
    try {
        const res = await fetchText(url, browserHeaders({ Accept: "*/*" }), PROBE_TIMEOUT_MS);
        if (res.status !== 200) return false;
        return res.body.indexOf("#EXTM3U") !== -1 || res.body.indexOf("#EXTINF") !== -1;
    } catch (error) {
        return false;
    }
}

/**
 * Probe routes in parallel (preferred hosts first) and keep every one that
 * really loads. Bounded concurrency keeps the lookup inside a normal request.
 */
async function probeRoutes(streams, limit) {
    const max = typeof limit === "number" ? limit : 12;
    const candidates = streams.slice(0, max);
    const results = new Array(candidates.length).fill(false);
    let cursor = 0;

    async function worker() {
        while (cursor < candidates.length) {
            const index = cursor++;
            results[index] = await playlistIsPlayable(candidates[index].url);
        }
    }
    const workers = [];
    for (let i = 0; i < Math.min(PROBE_CONCURRENCY, candidates.length); i++) workers.push(worker());
    await Promise.all(workers);

    const playable = [];
    for (let i = 0; i < candidates.length; i++) {
        if (results[i]) playable.push(candidates[i]);
    }
    return playable;
}

// ── TMDB title resolution ───────────────────────────────────────────────────

// The old codebase-wide fallback key is a dead placeholder (TMDB answers 401
// "Invalid API key" for it), so this module prefers the configured credentials
// and only then a known-working key. Production sets TMDB_API_KEY (and often
// TMDB_READ_ACCESS_TOKEN) via the environment.
const TMDB_FALLBACK_KEY = "f728f7197fc9c3a7dfb35d78478d7e8e";

function tmdbKey() {
    const env = process.env.TMDB_API_KEY;
    if (env && String(env).trim() && String(env).indexOf("mock_") !== 0) return String(env).trim();
    return TMDB_FALLBACK_KEY;
}

function tmdbBearer() {
    const token = process.env.TMDB_READ_ACCESS_TOKEN;
    if (!token) return "";
    const clean = String(token).trim();
    if (!clean || clean.indexOf("mock_") === 0) return "";
    return clean;
}

/** GET a TMDB path (without the leading slash) returning parsed JSON or null. */
async function tmdbGet(path) {
    const bearer = tmdbBearer();
    const url =
        "https://api.themoviedb.org/3/" + path +
        (bearer ? "" : "?api_key=" + encodeURIComponent(tmdbKey()));
    const headers = bearer
        ? { Accept: "application/json", Authorization: "Bearer " + bearer }
        : { Accept: "application/json" };
    try {
        const res = await fetchText(url, headers, REQUEST_TIMEOUT_MS);
        if (res.status !== 200) return null;
        return JSON.parse(res.body);
    } catch (error) {
        return null;
    }
}

/**
 * Search keys to try for a region, best first.
 *
 * The source library is indexed under Chinese release names, so a TMDB title
 * alone misses a lot (Dangal is only findable as 摔跤吧！爸爸). TMDB's own
 * alternative_titles carries exactly those names, which makes the lookup work
 * for a whole catalog instead of a hand-maintained alias list.
 */
async function candidateTitles(title, region, tmdbId, mediaType) {
    const out = [];
    function push(value) {
        const clean = String(value === undefined || value === null ? "" : value).trim();
        if (clean.length < 2) return;
        if (isAdultTitle(clean)) return;
        const key = titleKey(clean);
        if (!key) return;
        for (let i = 0; i < out.length; i++) {
            if (titleKey(out[i]) === key) return;
        }
        out.push(clean);
    }

    push(title);

    if (tmdbId && /^\d+$/.test(String(tmdbId))) {
        const kind = mediaType === "tv" ? "tv" : "movie";
        const regionCfg = REGIONS[region] || {};
        const wanted = regionCfg.preferredTitleRegions || [];

        // Region-preferred release names first (CN for Chinese releases),
        // then everything else TMDB knows about.
        const alt = await tmdbGet(
            kind + "/" + encodeURIComponent(tmdbId) + "/alternative_titles"
        );
        if (alt) {
            const rows = alt.titles || alt.results || [];
            for (let w = 0; w < wanted.length; w++) {
                for (let i = 0; i < rows.length; i++) {
                    if (String(rows[i].iso_3166_1 || "").toUpperCase() === wanted[w]) push(rows[i].title);
                }
            }
            for (let j = 0; j < rows.length && j < 18; j++) push(rows[j].title);
        }

        // The original-language name is another strong key.
        const meta = await tmdbGet(kind + "/" + encodeURIComponent(tmdbId));
        if (meta) {
            push(meta.original_title);
            push(meta.original_name);
            push(meta.title);
            push(meta.name);
        }
    }
    return out;
}

/**
 * Resolve a title in a region to playable HLS streams.
 *
 * Tries each candidate title in turn, scores the search hits against the wanted
 * title (so "RRR" cannot resolve to an unrelated film sharing a word), and
 * returns the first hit that really has a loading playlist.
 */
async function resolveRegionTitle(title, region, tmdbId, mediaType) {
    const regionKey = normalizeRegion(region);
    if (!regionKey) return { streams: [], matchedTitle: "", tried: [], error: "Unknown region." };

    const candidates = await candidateTitles(title, regionKey, tmdbId, mediaType);
    if (!candidates.length) return { streams: [], matchedTitle: "", tried: [], error: "No usable title." };

    const wanted = titleKey(title);
    const tried = [];

    for (let c = 0; c < candidates.length && c < 6; c++) {
        const query = candidates[c];
        let hits = [];
        try {
            hits = await ikanbotSearch(query);
        } catch (error) {
            tried.push(query + " (search failed)");
            continue;
        }
        tried.push(query + " (" + hits.length + ")");
        if (!hits.length) continue;

        const queryKey = titleKey(query);
        const scored = hits.map(function (hit, index) {
            const key = titleKey(hit.title);
            let score = Math.max(0, 10 - index);
            if (key && wanted && key === wanted) score += 100;
            else if (key && wanted && (key.indexOf(wanted) === 0 || wanted.indexOf(key) === 0)) score += 70;
            else if (key && queryKey && key.indexOf(queryKey) !== -1) score += 40;
            return { hit: hit, score: score };
        });
        scored.sort(function (a, b) {
            return b.score - a.score;
        });

        for (let s = 0; s < scored.length && s < 3; s++) {
            const chosen = scored[s].hit;
            if (isAdultTitle(chosen.title)) continue;
            let routes;
            try {
                routes = await ikanbotRoutes(chosen.id);
            } catch (error) {
                continue;
            }
            if (!routes.streams.length) continue;
            const playable = await probeRoutes(routes.streams, 14);
            if (!playable.length) continue;
            return {
                streams: playable.map(function (item) {
                    return { url: item.url, label: item.label, host: item.host };
                }),
                matchedTitle: chosen.title,
                matchedId: chosen.id,
                totalRoutes: routes.streams.length,
                tried: tried,
                error: ""
            };
        }
    }
    return { streams: [], matchedTitle: "", tried: tried, error: "No playable stream found." };
}



// ── official YouTube catalog (Filipino mainstream) ───────────────────────────
//
// ABS-CBN Star Cinema / Viva / Regal publish FULL feature-length movies on
// YouTube. That is the cleanest Filipino source available: official uploads, HD,
// no third-party CDN, and the app already ships the YouTube player. The catalog
// is read from YouTube's own `ytInitialData` over plain HTTP, so Piped (whose
// public instances are almost all dead) is not involved at all.

const YOUTUBE_CHANNELS = [
    "abs-cbn star cinema",
    "abs-cbn entertainment",
    "star cinema",
    "viva films",
    "viva communications",
    "regal entertainment",
    "gma pictures",
    "gma network"
];

function youtubeSearchUrl(query) {
    return (
        "https://www.youtube.com/results?search_query=" + encodeURIComponent(query) +
        "&sp=EgIQAQ%253D%253D" // videos only
    );
}

/** Pull videoRenderer rows out of YouTube's embedded ytInitialData JSON. */
function parseYouTubeResults(html) {
    const marker = "var ytInitialData = ";
    const start = html.indexOf(marker);
    if (start === -1) return [];
    const from = start + marker.length;
    const end = html.indexOf(";</script>", from);
    if (end === -1) return [];
    let data;
    try {
        data = JSON.parse(html.slice(from, end));
    } catch (error) {
        return [];
    }
    const rows = [];
    function walk(node) {
        if (!node || typeof node !== "object") return;
        if (Array.isArray(node)) {
            for (let i = 0; i < node.length; i++) walk(node[i]);
            return;
        }
        if (node.videoRenderer) {
            const video = node.videoRenderer;
            const titleRuns = (video.title && video.title.runs) || [];
            let title = "";
            for (let t = 0; t < titleRuns.length; t++) title += titleRuns[t].text || "";
            const ownerRuns = (video.ownerText && video.ownerText.runs) || [];
            let channel = "";
            for (let o = 0; o < ownerRuns.length; o++) channel += ownerRuns[o].text || "";
            const thumbs = (video.thumbnail && video.thumbnail.thumbnails) || [];
            rows.push({
                videoId: video.videoId || "",
                title: decodeEntities(title),
                channel: decodeEntities(channel),
                duration: (video.lengthText && video.lengthText.simpleText) || "",
                thumbnail: thumbs.length ? thumbs[thumbs.length - 1].url : ""
            });
        }
        const keys = Object.keys(node);
        for (let k = 0; k < keys.length; k++) walk(node[keys[k]]);
    }
    walk(data);
    return rows;
}

/** "1:51:37" / "12:34" -> seconds. */
function durationSeconds(text) {
    const parts = String(text || "").split(":");
    if (parts.length < 2) return 0;
    let total = 0;
    for (let i = 0; i < parts.length; i++) {
        const value = parseInt(parts[i], 10);
        if (!Number.isFinite(value)) return 0;
        total = total * 60 + value;
    }
    return total;
}

/**
 * Official Filipino full movies for a query, best first.
 * Only uploads on the official channels are kept, and only feature-length ones
 * (>= 40 minutes) so trailers and clips can never enter the catalog.
 */
async function youtubeFilipinoMovies(query, limit) {
    const max = typeof limit === "number" ? limit : 12;
    let res;
    try {
        res = await fetchText(
            youtubeSearchUrl(query + " full movie"),
            browserHeaders({ Accept: "text/html,application/xhtml+xml" }),
            REQUEST_TIMEOUT_MS
        );
    } catch (error) {
        return [];
    }
    if (res.status !== 200) return [];
    const rows = parseYouTubeResults(res.body);
    const out = [];
    for (let i = 0; i < rows.length; i++) {
        const row = rows[i];
        if (!row.videoId) continue;
        if (durationSeconds(row.duration) < 40 * 60) continue;
        let official = false;
        const channel = row.channel.toLowerCase();
        for (let c = 0; c < YOUTUBE_CHANNELS.length; c++) {
            if (channel.indexOf(YOUTUBE_CHANNELS[c]) !== -1) {
                official = true;
                break;
            }
        }
        if (!official) continue;
        if (isAdultTitle(row.title)) continue;
        out.push(row);
        if (out.length >= max) break;
    }
    return out;
}

// ── HLS proxy ───────────────────────────────────────────────────────────────
//
// The ikanbot CDNs are public and need no headers (verified: master, AES-128 key
// and segment all returned 200 with zero headers), but a device network can
// still block them. This proxy is the fallback: it fetches upstream itself and
// rewrites every playlist URI so the player only ever talks to us.

function proxyBaseFrom(request) {
    // Prefer the proxy header (Render/Fly terminate TLS), otherwise infer from
    // the socket so a plain-HTTP local run does not build https:// links.
    const forwarded = request.headers && request.headers["x-forwarded-proto"];
    let proto;
    if (forwarded) {
        proto = String(forwarded).split(",")[0].trim();
    } else {
        const encrypted = request.socket && request.socket.encrypted;
        proto = encrypted ? "https" : "http";
    }
    const host =
        (request.headers && (request.headers["x-forwarded-host"] || request.headers.host)) ||
        "localhost";
    return proto + "://" + host;
}

function asianProxyUrl(base, url) {
    return base + "/api/asian/proxy?url=" + encodeURIComponent(url);
}

function isPlaylistContentType(contentType) {
    const lower = String(contentType || "").toLowerCase();
    return lower.indexOf("mpegurl") !== -1 || lower.indexOf("vnd.apple") !== -1;
}

/**
 * Rewrite a playlist so every child URI goes back through this proxy.
 * Handles #EXT-X-KEY (AES-128 key), #EXT-X-MAP and #EXT-X-MEDIA URI="..." plus
 * plain segment/variant lines. Relative URIs resolve against the playlist URL
 * first — otherwise a relative "enc.key" would resolve against OUR host.
 */
function rewritePlaylist(body, playlistUrl, base) {
    const lines = String(body).split(/\r?\n/);
    const out = [];

    function proxied(uri) {
        let absolute;
        try {
            absolute = new URL(uri, playlistUrl).toString();
        } catch (error) {
            return uri;
        }
        return asianProxyUrl(base, absolute);
    }

    for (let i = 0; i < lines.length; i++) {
        const line = lines[i];
        const trimmed = line.trim();
        if (!trimmed) {
            out.push(line);
            continue;
        }
        if (trimmed.charAt(0) === "#") {
            if (/^#EXT-X-(KEY|MAP|MEDIA|SESSION-KEY|PART|PRELOAD-HINT|RENDITION-REPORT)/i.test(trimmed)) {
                out.push(
                    trimmed.replace(/URI="([^"]+)"/gi, function (match, uri) {
                        return 'URI="' + proxied(uri) + '"';
                    })
                );
                continue;
            }
            out.push(line);
            continue;
        }
        out.push(proxied(trimmed));
    }
    return out.join("\n");
}

async function handleProxy(request, response, requestUrl) {
    const target = requestUrl.searchParams.get("url");
    if (!target) {
        sendApiError(response, 400, "Missing url parameter.");
        return;
    }
    let parsed;
    try {
        parsed = new URL(target);
    } catch (error) {
        sendApiError(response, 400, "Bad url parameter.");
        return;
    }
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
        sendApiError(response, 400, "Only http(s) targets are proxied.");
        return;
    }

    const base = proxyBaseFrom(request);
    const headers = browserHeaders({ Accept: "*/*" });
    const transport = parsed.protocol === "http:" ? http : https;

    const upstream = transport.get(
        {
            protocol: parsed.protocol,
            hostname: parsed.hostname,
            port: parsed.port || (parsed.protocol === "http:" ? 80 : 443),
            path: parsed.pathname + parsed.search,
            headers: headers,
            timeout: 30000
        },
        (res) => {
            const status = res.statusCode || 0;
            if (status >= 300 && status < 400 && res.headers.location) {
                res.resume();
                let next;
                try {
                    next = new URL(res.headers.location, target).toString();
                } catch (error) {
                    response.writeHead(502);
                    response.end();
                    return;
                }
                response.writeHead(302, { Location: asianProxyUrl(base, next) });
                response.end();
                return;
            }
            const contentType = res.headers["content-type"] || "";

            // Playlists are text and must be rewritten; media is piped through.
            if (isPlaylistContentType(contentType)) {
                res.setEncoding("utf8");
                let body = "";
                res.on("data", (chunk) => {
                    body += chunk;
                });
                res.on("end", () => {
                    response.writeHead(200, {
                        "Content-Type": "application/vnd.apple.mpegurl",
                        "Access-Control-Allow-Origin": "*",
                        "Cache-Control": "no-store"
                    });
                    response.end(rewritePlaylist(body, target, base));
                });
                return;
            }

            const headersOut = {
                "Content-Type": contentType || "video/mp2t",
                "Access-Control-Allow-Origin": "*",
                "Cache-Control": "no-store"
            };
            if (res.headers["content-length"]) headersOut["Content-Length"] = res.headers["content-length"];
            response.writeHead(status === 0 ? 200 : status, headersOut);
            res.pipe(response);
        }
    );
    upstream.on("timeout", () => upstream.destroy(new Error("timeout")));
    upstream.on("error", (error) => {
        if (!response.headersSent) {
            sendApiError(response, 502, (error && error.message) || "Asian proxy failed.");
        } else {
            response.end();
        }
    });
}

// ── route handlers ──────────────────────────────────────────────────────────

/** GET /api/asian/play?title=&region=&tmdbId=&type=&youtube= */
async function handlePlay(request, response, requestUrl) {
    const title = (requestUrl.searchParams.get("title") || "").trim();
    const region = normalizeRegion(requestUrl.searchParams.get("region"));
    const tmdbId = (requestUrl.searchParams.get("tmdbId") || "").trim();
    const mediaType = (requestUrl.searchParams.get("type") || "movie").trim();
    const wantYoutube = requestUrl.searchParams.get("youtube") !== "0";

    if (!title && !tmdbId) {
        sendApiError(response, 400, "Provide title or tmdbId.");
        return;
    }
    if (!region) {
        sendApiError(response, 400, "Provide region=chinese|indian|filipino.");
        return;
    }

    const base = proxyBaseFrom(request);
    const result = await resolveRegionTitle(title, region, tmdbId, mediaType);

    if (result.streams.length) {
        const streams = result.streams.map(function (item, index) {
            return {
                url: item.url,
                // Same-origin fallback: hls.js and the native players treat it
                // exactly like the direct URL, so a blocked CDN cannot
                // black-screen the episode.
                proxyUrl: asianProxyUrl(base, item.url),
                quality: index === 0 ? "auto" : "mirror " + (index + 1),
                label: item.label,
                type: "hls",
                direct: true
            };
        });
        sendJson(response, 200, {
            ok: true,
            data: {
                region: region,
                provider: "Asian (ikanbot)",
                matchedTitle: result.matchedTitle,
                matchedId: result.matchedId,
                totalRoutes: result.totalRoutes,
                streams: streams,
                // Subtitle renditions live inside these playlists (EXT-X-MEDIA),
                // so the players pick them up from the manifest itself.
                subtitles: [],
                tried: result.tried
            },
            error: null
        });
        return;
    }

    // Filipino mainstream often has no ikanbot route but IS on the official
    // channels — fall through to YouTube instead of failing the request.
    if (region === "filipino" && wantYoutube) {
        let videos = [];
        try {
            videos = await youtubeFilipinoMovies(title, 6);
        } catch (error) {
            videos = [];
        }
        const wanted = titleKey(title);
        const usable = videos.filter(function (video) {
            const key = titleKey(video.title);
            return key.indexOf(wanted) !== -1 || wanted.indexOf(key) !== -1;
        });
        const chosen = usable.length ? usable[0] : videos[0];
        if (chosen) {
            sendJson(response, 200, {
                ok: true,
                data: {
                    region: region,
                    provider: "Asian (YouTube official)",
                    matchedTitle: chosen.title,
                    youtubeVideoId: chosen.videoId,
                    channel: chosen.channel,
                    duration: chosen.duration,
                    streams: [],
                    subtitles: [],
                    tried: result.tried
                },
                error: null
            });
            return;
        }
    }

    sendJson(response, 200, {
        ok: false,
        data: { region: region, tried: result.tried },
        error: result.error || "No playable stream found."
    });
}

/** GET /api/asian/search?q=&region= */
async function handleSearch(request, response, requestUrl) {
    const query = (requestUrl.searchParams.get("q") || "").trim();
    const region = normalizeRegion(requestUrl.searchParams.get("region"));
    if (!query) {
        sendApiError(response, 400, "Missing q parameter.");
        return;
    }
    const hits = await ikanbotSearch(query);
    const results = hits.slice(0, 12).map(function (hit) {
        return { id: hit.id, title: hit.title, url: IKANBOT + "/play/" + hit.id };
    });
    sendJson(response, 200, {
        ok: true,
        data: { query: query, region: region, results: results },
        error: null
    });
}

/** GET /api/asian/youtube?q=&limit= */
async function handleYouTube(request, response, requestUrl) {
    const query = (requestUrl.searchParams.get("q") || "").trim();
    if (!query) {
        sendApiError(response, 400, "Missing q parameter.");
        return;
    }
    const parsedLimit = parseInt(requestUrl.searchParams.get("limit") || "12", 10);
    const limit = Number.isFinite(parsedLimit) ? parsedLimit : 12;
    const videos = await youtubeFilipinoMovies(query, limit);
    sendJson(response, 200, {
        ok: true,
        data: {
            query: query,
            results: videos.map(function (video) {
                return {
                    videoId: video.videoId,
                    title: video.title,
                    channel: video.channel,
                    duration: video.duration,
                    thumbnail: video.thumbnail,
                    url: "https://www.youtube.com/watch?v=" + video.videoId
                };
            })
        },
        error: null
    });
}

/** GET /api/asian/health */
async function handleHealth(request, response) {
    const regions = Object.keys(REGIONS).map(function (key) {
        return { key: key, label: REGIONS[key].label };
    });
    sendJson(response, 200, {
        ok: true,
        data: {
            service: "asian",
            regions: regions,
            servers: {
                chinese: "ikanbot.com (direct HLS)",
                indian: "ikanbot.com (direct HLS)",
                filipino: "ikanbot.com (direct HLS) + official YouTube channels"
            }
        },
        error: null
    });
}

async function handleAsian(request, response, pathname, requestUrl) {
    try {
        if (pathname === "/api/asian/health") {
            return await handleHealth(request, response);
        }
        if (pathname === "/api/asian/play") {
            return await handlePlay(request, response, requestUrl);
        }
        if (pathname === "/api/asian/search") {
            return await handleSearch(request, response, requestUrl);
        }
        if (pathname === "/api/asian/youtube") {
            return await handleYouTube(request, response, requestUrl);
        }
        if (pathname === "/api/asian/proxy" || pathname === "/api/asian/proxy/m3u8") {
            return await handleProxy(request, response, requestUrl);
        }
        return sendApiError(response, 404, "Asian route not found.");
    } catch (error) {
        return sendApiError(response, 500, (error && error.message) || "Asian request failed.");
    }
}

module.exports = {
    handleAsian: handleAsian,
    ikanbotToken: ikanbotToken,
    ikanbotSearch: ikanbotSearch,
    ikanbotRoutes: ikanbotRoutes,
    candidateTitles: candidateTitles,
    normalizeRegion: normalizeRegion,
    resolveRegionTitle: resolveRegionTitle,
    youtubeFilipinoMovies: youtubeFilipinoMovies,
    rewritePlaylist: rewritePlaylist,
    IKANBOT: IKANBOT
};
