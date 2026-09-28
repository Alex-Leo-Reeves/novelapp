// ─────────────────────────────────────────────────────────────────────────────
//  Donghua Bridge — dedicated donghua server (donghuaworld.com)
//
//  WHY THIS EXISTS:
//  The donghua tab had no source that could hand a player a URL it can actually
//  play. Every candidate site is Dailymotion-first, and Dailymotion's CDN 403s
//  any request that does not carry the exact four-header fingerprint
//  (Referer + Origin + Accept + Accept-Language) on EVERY sub-request, on top of
//  a 10-minute signed token. Native players (LibVLC on Android TV, AVPlayer on
//  iOS) cannot guarantee per-sub-request headers, which is exactly the "black
//  screen / opens their website instead" failure the donghua tab showed.
//
//  donghuaworld.com is different. Every episode page ships TWO servers; the
//  second one is their own player (playing.donghuaworld.in/<id>) whose inline
//  jwplayer setup embeds a PLAIN PUBLIC Rumble HLS master:
//      https://rumble.com/hls-vod/<id>/playlist.m3u8
//  That URL returns HTTP 200 with ZERO request headers, sends
//  `access-control-allow-origin: *`, serves unencrypted MPEG-TS variants
//  (1440p/1080p/720p/480p/360p/240p verified) and 18 public VTT subtitle
//  tracks including English. Verified live 2026-09-26 against 5 episodes
//  across 4 shows; hostile-client sweep (no-UA, VLC, ExoPlayer, AVPlayer,
//  empty-UA, Chrome) returned 200 on master, variant AND segment every time.
//
//  So this handler returns a directly-playable HLS URL that works unchanged in
//  ExoPlayer (Android), LibVLC (Android TV), AVPlayer (iOS) and hls.js (web).
//  Dailymotion stays as a second server and is routed through the proxy below,
//  which supplies the fingerprint server-side and rewrites playlist URIs so the
//  player never has to know.
//
//  NOTE: this file deliberately avoids optional chaining (?.) and nullish
//  coalescing (??) because the codebase formatter rewrites those tokens into
//  invalid syntax. All null checks use explicit ternaries / && / ||.
//
//  ROUTES (/api/donghua):
//    GET /api/donghua/search?q={title}
//    GET /api/donghua/series?url={seriesUrl}            (or ?title={title})
//    GET /api/donghua/watch?url={episodeUrl}
//    GET /api/donghua/play?title={title}&ep={n}
//    GET /api/donghua/proxy?url={abs}&h={none|dm}
// ─────────────────────────────────────────────────────────────────────────────

const http = require("http");
const https = require("https");

const SITE = "https://donghuaworld.com";
const WP_SEARCH = SITE + "/wp-json/wp/v2/search";
const BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

// Dailymotion rejects anything that is not this exact header set — verified
// 4/4 pass with Accept-Language and 4/4 fail without it.
const DM_HEADERS = {
    "User-Agent": BROWSER_UA,
    Referer: "https://geo.dailymotion.com/",
    Origin: "https://geo.dailymotion.com",
    Accept: "*/*",
    "Accept-Language": "en-US,en;q=0.9"
};

const REQUEST_TIMEOUT_MS = 20 * 1000;
const PROXY_TIMEOUT_MS = 45 * 1000;
const CACHE_TTL_MS = 10 * 60 * 1000;

const SEARCH_CACHE = new Map();
const SERIES_CACHE = new Map();
const WATCH_CACHE = new Map();

// ─────────────────────────────────────────────────────────────────────────────
//  HTTP + response helpers
// ─────────────────────────────────────────────────────────────────────────────

function corsHeaders() {
    return {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET, POST, OPTIONS",
        "access-control-allow-headers": "Content-Type, Range, Authorization",
        "access-control-expose-headers": "Accept-Ranges, Content-Range, Content-Length, Content-Type"
    };
}

function sendJson(response, statusCode, payload) {
    response.writeHead(statusCode, {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
        ...corsHeaders()
    });
    response.end(JSON.stringify(payload));
}

function sendApiData(response, statusCode, data) {
    sendJson(response, statusCode, { ok: statusCode >= 200 && statusCode < 300, data: data, error: null });
}

function sendApiError(response, statusCode, message) {
    sendJson(response, statusCode, { ok: false, data: null, error: message });
}

function cacheGet(cache, key) {
    const hit = cache.get(key);
    if (!hit) return null;
    if (Date.now() - hit.at > CACHE_TTL_MS) {
        cache.delete(key);
        return null;
    }
    return hit.value;
}

function cacheSet(cache, key, value) {
    if (cache.size > 400) cache.clear();
    cache.set(key, { at: Date.now(), value: value });
    return value;
}

async function fetchWithTimeout(url, headers, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(function() { controller.abort(); }, timeoutMs || REQUEST_TIMEOUT_MS);
    try {
        return await fetch(url, {
            headers: headers || { "User-Agent": BROWSER_UA },
            signal: controller.signal,
            redirect: "follow"
        });
    } finally {
        clearTimeout(timer);
    }
}

async function fetchText(url, headers, timeoutMs) {
    const res = await fetchWithTimeout(url, headers, timeoutMs);
    const body = await res.text();
    return { status: res.status, ok: res.ok, body: body };
}

// ─────────────────────────────────────────────────────────────────────────────
//  Core http/https transport for Dailymotion.
//
//  Dailymotion's CDN 403s (x-error-code E005) ANY request that carries an
//  Accept-Encoding header — verified: identity/gzip/br/deflate and browser-style
//  lists all return 403, while sending no Accept-Encoding at all returns 200.
//  undici's global fetch always adds one and it cannot be removed, so
//  Dailymotion is spoken to over the core transport where the header set is
//  exactly what we pass.
// ─────────────────────────────────────────────────────────────────────────────

function rawRequest(url, headers) {
    return new Promise(function(resolve, reject) {
        let parsed;
        try {
            parsed = new URL(url);
        } catch (error) {
            reject(new Error("invalid url: " + url));
            return;
        }
        if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
            reject(new Error("unsupported protocol: " + parsed.protocol));
            return;
        }
        const lib = parsed.protocol === "http:" ? http : https;
        const request = lib.request({
            protocol: parsed.protocol,
            hostname: parsed.hostname,
            port: parsed.port || (parsed.protocol === "http:" ? 80 : 443),
            path: parsed.pathname + parsed.search,
            method: "GET",
            headers: headers,
            // A fresh connection per request. With the default keep-alive agent
            // (the Node 19+ default) Dailymotion's edge never delivers a body and
            // the socket just sits idle until it times out; curl-style one-shot
            // connections return 200 immediately.
            agent: false
        }, function(res) {
            resolve(res);
        });
        request.on("error", reject);
        request.setTimeout(REQUEST_TIMEOUT_MS, function() {
            request.destroy(new Error("upstream timeout"));
        });
        request.end();
    });
}

async function rawGetText(url, headers) {
    const res = await rawRequest(url, headers);
    const chunks = [];
    for await (const chunk of res) chunks.push(chunk);
    const status = res.statusCode || 0;
    return {
        status: status,
        ok: status >= 200 && status < 300,
        body: Buffer.concat(chunks).toString("utf8")
    };
}

// ─────────────────────────────────────────────────────────────────────────────
//  HTML helpers (regex only — no cheerio so Render needs zero new deps)
// ─────────────────────────────────────────────────────────────────────────────

function decodeEntities(text) {
    if (!text) return "";
    return String(text)
        .replace(/&#0?39;/g, "'")
        .replace(/&apos;/g, "'")
        .replace(/&#8217;/g, "\u2019")
        .replace(/&#8216;/g, "\u2018")
        .replace(/&quot;/g, "\"")
        .replace(/&#8220;/g, "\u201c")
        .replace(/&#8221;/g, "\u201d")
        .replace(/&#8211;/g, "\u2013")
        .replace(/&amp;/g, "&")
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&nbsp;/g, " ")
        .replace(/&#(\d+);/g, function(_m, code) {
            return String.fromCharCode(parseInt(code, 10));
        })
        .replace(/\s+/g, " ")
        .trim();
}

function metaContent(html, property) {
    const escaped = property.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    const pattern = new RegExp('<meta[^>]+(?:property|name)=["\']' + escaped + '["\'][^>]*>', "i");
    const tag = pattern.exec(html);
    if (!tag) return "";
    const content = /content=["\']([^"\']*)["\']/i.exec(tag[0]);
    return content ? decodeEntities(content[1]) : "";
}

function stripTags(html) {
    return decodeEntities(String(html).replace(/<[^>]*>/g, " "));
}

/** Absolute-ise a possibly-relative URL against a base. */
function absoluteUrl(raw, base) {
    if (!raw) return "";
    const value = decodeEntities(String(raw)).trim();
    if (!value || value.indexOf("javascript:") === 0 || value.indexOf("about:") === 0) return "";
    if (value.indexOf("//") === 0) return "https:" + value;
    if (/^https?:\/\//i.test(value)) return value;
    try {
        return new URL(value, base).toString();
    } catch (_error) {
        return "";
    }
}

function isBlockedOrErrorPage(body) {
    const lower = String(body || "").slice(0, 900).toLowerCase();
    if (!lower) return true;
    return (
        lower.indexOf("just a moment") !== -1 ||
        lower.indexOf("attention required") !== -1 ||
        lower.indexOf("cf-browser-verification") !== -1 ||
        lower.indexOf("access denied") !== -1 ||
        lower.indexOf("<title>404") !== -1
    );
}

// ─────────────────────────────────────────────────────────────────────────────
//  Search (WP REST API first, themed HTML search page as fallback)
// ─────────────────────────────────────────────────────────────────────────────

function normalizeForMatch(text) {
    return String(text || "")
        .toLowerCase()
        .replace(/[^a-z0-9 ]+/g, " ")
        .replace(/\b(season|part|cour|the|of|and)\b/g, " ")
        .replace(/\s+/g, " ")
        .trim();
}

/** Score how well a candidate title matches the query (0 = reject). */
function titleMatchScore(query, candidate) {
    const q = normalizeForMatch(query);
    const c = normalizeForMatch(candidate);
    if (!q || !c) return 0;
    if (q === c) return 10000;
    if (c.indexOf(q) === 0) return 8000 - Math.min(500, c.length - q.length);
    if (c.indexOf(q) !== -1) return 5000 - Math.min(500, c.length - q.length);
    const qWords = q.split(" ").filter(Boolean);
    const cWords = c.split(" ").filter(Boolean);
    if (!qWords.length) return 0;
    let hits = 0;
    for (const word of qWords) {
        if (cWords.indexOf(word) !== -1) hits++;
    }
    const ratio = hits / qWords.length;
    if (ratio >= 0.6) return Math.floor(2000 * ratio);
    return 0;
}

/** Strip release qualifiers so "X Season 5 (4K) Multi-Sub" still matches "X Season 5". */
function searchQueryVariants(title) {
    const base = String(title || "").trim();
    const out = [];
    const push = function(value) {
        const clean = String(value || "").replace(/\s+/g, " ").trim();
        if (clean && out.indexOf(clean) === -1) out.push(clean);
    };
    push(base);
    push(base.replace(/\([^)]*\)/g, " "));
    push(base.replace(/\[[^\]]*\]/g, " "));
    push(base.replace(/\b(season|part|cour)\s*\d+\b/i, " "));
    push(base.split(":")[0]);
    push(base.split("-")[0]);
    push(base.split(" ").slice(0, 3).join(" "));
    return out.filter(function(value) { return value.length >= 2; });
}

async function wpSearch(query) {
    const url = WP_SEARCH + "?search=" + encodeURIComponent(query) +
        "&subtype=anime&per_page=20&_fields=id,title,url,subtype";
    const res = await fetchText(url, { "User-Agent": BROWSER_UA, Accept: "application/json" });
    if (!res.ok) return [];
    let parsed;
    try {
        parsed = JSON.parse(res.body);
    } catch (_error) {
        return [];
    }
    if (!Array.isArray(parsed)) return [];
    return parsed
        .filter(function(item) { return item && item.url; })
        .map(function(item) {
            return {
                id: String(item.id || ""),
                title: decodeEntities(item.title || ""),
                url: String(item.url)
            };
        })
        .filter(function(item) { return item.title && /\/anime\//.test(item.url); });
}

/** Fallback: scrape the themed `?s=` search page when the REST API is unavailable. */
async function htmlSearch(query) {
    const res = await fetchText(SITE + "/?s=" + encodeURIComponent(query), {
        "User-Agent": BROWSER_UA,
        Accept: "text/html,application/xhtml+xml",
        "Accept-Language": "en-US,en;q=0.9"
    });
    if (!res.ok || isBlockedOrErrorPage(res.body)) return [];
    const seen = {};
    const out = [];
    const anchorPattern = /<a[^>]+href=["\'](https:\/\/donghuaworld\.com\/anime\/[^"\']+)["\'][^>]*>/gi;
    let match = anchorPattern.exec(res.body);
    while (match) {
        const url = match[1];
        if (!seen[url]) {
            seen[url] = true;
            const tail = res.body.slice(match.index, match.index + 700);
            const titleAttr = /title=["\']([^"\']+)["\']/i.exec(tail);
            const heading = /<h[1-4][^>]*>([\s\S]{0,180}?)<\/h[1-4]>/i.exec(tail);
            const title = decodeEntities(
                (titleAttr && titleAttr[1]) ||
                (heading && stripTags(heading[1])) ||
                url.split("/anime/")[1].replace(/\/$/, "").replace(/-/g, " ")
            );
            out.push({ id: "", title: title, url: url });
        }
        match = anchorPattern.exec(res.body);
    }
    return out;
}

/** Run every query variant through REST+HTML search and rank the union. */
async function collectSearchCandidates(title) {
    const collected = [];
    const seenUrls = {};
    for (const variant of searchQueryVariants(title)) {
        let found = [];
        try {
            found = await wpSearch(variant);
        } catch (_error) {
            found = [];
        }
        if (!found.length) {
            try {
                found = await htmlSearch(variant);
            } catch (_error) {
                found = [];
            }
        }
        for (const item of found) {
            if (seenUrls[item.url]) continue;
            seenUrls[item.url] = true;
            item.score = titleMatchScore(title, item.title);
            collected.push(item);
        }
        // An exact/prefix match means we already have the right series; no need
        // to keep hammering the origin with fuzzier variants.
        const good = collected.filter(function(item) { return item.score >= 8000; });
        if (good.length) break;
    }
    collected.sort(function(a, b) { return b.score - a.score; });
    return collected.filter(function(item) { return item.score > 0; });
}

/**
 * Top-ranked title match (no episode-grid validation).
 * Prefer [resolveBestSeriesForTitle] anywhere a playable season is required —
 * the top match alone can be a batch/hub page with zero episodes.
 */
async function searchFirst(title) {
    const candidates = await collectSearchCandidates(title);
    return candidates.length ? candidates[0] : null;
}

async function handleSearch(request, response, requestUrl) {
    const query = String(requestUrl.searchParams.get("q") || "").trim();
    if (!query) return sendApiError(response, 400, "q is required.");

    const cacheKey = "q:" + query.toLowerCase();
    const cached = cacheGet(SEARCH_CACHE, cacheKey);
    if (cached) return sendApiData(response, 200, { query: query, results: cached });

    const ranked = (await collectSearchCandidates(query)).slice(0, 20);
    cacheSet(SEARCH_CACHE, cacheKey, ranked);
    return sendApiData(response, 200, { query: query, results: ranked });
}

// ─────────────────────────────────────────────────────────────────────────────
//  Series + episode list
// ─────────────────────────────────────────────────────────────────────────────

function parseEpisodeNumber(numText, title, fallbackIndex) {
    const sources = [numText, title];
    for (const source of sources) {
        if (!source) continue;
        const match = /(?:episode|ep\.?|e)\s*[-#]?\s*(\d{1,4})/i.exec(source);
        if (match) return parseInt(match[1], 10);
        const bare = /^\s*(\d{1,4})\b/.exec(source);
        if (bare) return parseInt(bare[1], 10);
    }
    return fallbackIndex;
}

function parseSubLabel(subHtml) {
    const text = stripTags(subHtml || "").toLowerCase();
    if (text.indexOf("dub") !== -1 && text.indexOf("sub") !== -1) return "Sub & Dub";
    if (text.indexOf("dub") !== -1) return "Dub";
    if (text.indexOf("sub") !== -1) return "Sub";
    return "";
}

/**
 * donghuaworld renders its episode grid as:
 *   <div class="eplister"><ul>
 *     <li data-index="0"><a href="{episodeUrl}">
 *       <div class="epl-num">212 (4K)</div>
 *       <div class="epl-title">…Episode 212 (4K) Multi-Subtitles</div>
 *       <div class="epl-sub"><span class="status Sub">Sub</span></div>
 *       <div class="epl-date">September 26, 2026</div>
 *     </a></li> …
 */
function parseEpisodes(html, seriesUrl) {
    const episodes = [];
    const seen = {};
    const listStart = html.indexOf("eplister");
    const scope = listStart === -1 ? html : html.slice(listStart);

    const itemPattern = /<li[^>]*data-index=["\']?\d+["\']?[^>]*>([\s\S]*?)<\/li>/gi;
    let item = itemPattern.exec(scope);
    let index = 0;
    while (item) {
        const chunk = item[1];
        const href = /<a[^>]+href=["\']([^"\']+)["\']/i.exec(chunk);
        if (href) {
            const url = absoluteUrl(href[1], seriesUrl);
            if (url && /episode|watch/i.test(url) && !seen[url]) {
                seen[url] = true;
                const numHtml = /<div[^>]*class=["\'][^"\']*epl-num[^"\']*["\'][^>]*>([\s\S]*?)<\/div>/i.exec(chunk);
                const titleHtml = /<div[^>]*class=["\'][^"\']*epl-title[^"\']*["\'][^>]*>([\s\S]*?)<\/div>/i.exec(chunk);
                const dateHtml = /<div[^>]*class=["\'][^"\']*epl-date[^"\']*["\'][^>]*>([\s\S]*?)<\/div>/i.exec(chunk);
                const subHtml = /<div[^>]*class=["\'][^"\']*epl-sub[^"\']*["\'][^>]*>([\s\S]*?)<\/div>/i.exec(chunk);
                const numText = numHtml ? stripTags(numHtml[1]) : "";
                const titleText = titleHtml ? stripTags(titleHtml[1]) : "";
                const number = parseEpisodeNumber(numText, titleText, index + 1);
                episodes.push({
                    number: number,
                    title: titleText || ("Episode " + number),
                    url: url,
                    date: dateHtml ? stripTags(dateHtml[1]) : "",
                    sub: parseSubLabel(subHtml ? subHtml[1] : ""),
                    index: index
                });
                index++;
            }
        }
        item = itemPattern.exec(scope);
    }

    // Ascending by episode number so the app shows 1 -> N.
    episodes.sort(function(a, b) { return a.number - b.number; });
    return episodes;
}

function parseSeriesMeta(html, seriesUrl) {
    const titleTag = /<h1[^>]*class=["\'][^"\']*(?:entry-title|ts-title)[^"\']*["\'][^>]*>([\s\S]*?)<\/h1>/i.exec(html);
    const fallback = (seriesUrl.split("/anime/")[1] || "").replace(/\/$/, "").replace(/-/g, " ");
    const title = metaContent(html, "og:title") || (titleTag ? stripTags(titleTag[1]) : "") || fallback;
    const lastEpisode = /epcur\s+epcurlast["\'][^>]*>([\s\S]*?)<\/span>/i.exec(html);
    return {
        title: decodeEntities(String(title).replace(/\s*[-\u2013|]\s*Donghua World.*$/i, "").trim()),
        url: seriesUrl,
        poster: metaContent(html, "og:image"),
        description: metaContent(html, "og:description"),
        latestEpisode: lastEpisode ? stripTags(lastEpisode[1]) : ""
    };
}

async function loadSeries(seriesUrl) {
    const resolvedUrl = absoluteUrl(seriesUrl, SITE);
    if (!resolvedUrl || !/^https?:\/\/(www\.)?donghuaworld\.com\//i.test(resolvedUrl)) {
        throw new Error("series url must be a donghuaworld.com URL.");
    }
    const cacheKey = "s:" + resolvedUrl;
    const cached = cacheGet(SERIES_CACHE, cacheKey);
    if (cached) return cached;

    const res = await fetchText(resolvedUrl, {
        "User-Agent": BROWSER_UA,
        Accept: "text/html,application/xhtml+xml",
        "Accept-Language": "en-US,en;q=0.9"
    });
    if (!res.ok || isBlockedOrErrorPage(res.body)) {
        throw new Error("series page fetch failed (HTTP " + res.status + ").");
    }
    const payload = {
        series: parseSeriesMeta(res.body, resolvedUrl),
        episodes: parseEpisodes(res.body, resolvedUrl)
    };
    return cacheSet(SERIES_CACHE, cacheKey, payload);
}

/**
 * Rank the title-search candidates and return the first one whose page really
 * carries an episode grid.
 *
 * Several donghuaworld pages are "batch"/season-hub entries whose markup has no
 * per-episode list (e.g. searching "Battle Through the Heavens" ranks the hub
 * page first, which parses to 0 episodes, while "…Season 5" has 100). Returning
 * the top match blindly is exactly what leaves the app with an empty episode
 * list, so every ranked candidate is probed until one yields episodes.
 */
async function resolveBestSeriesForTitle(title) {
    const clean = String(title || "").trim();
    if (!clean) return null;
    const cacheKey = "best:" + clean.toLowerCase();
    const cached = cacheGet(SERIES_CACHE, cacheKey);
    if (cached) return cached;

    const candidates = await collectSearchCandidates(clean);
    if (!candidates.length) return null;

    let best = null;
    for (const candidate of candidates.slice(0, 5)) {
        let payload = null;
        try {
            payload = await loadSeries(candidate.url);
        } catch (_error) {
            payload = null;
        }
        if (!payload || !payload.episodes.length) continue;
        if (!best || payload.episodes.length > best.episodes.length) {
            best = { candidate: candidate, series: payload.series, episodes: payload.episodes };
        }
        // A strong (exact/prefix) title match WITH episodes is the answer.
        if (candidate.score >= 8000) break;
    }

    if (!best) {
        // Nothing had a grid: fall back to the plain top match so callers can
        // still surface the series title instead of a bare "not found".
        const top = candidates[0];
        let payload = null;
        try {
            payload = await loadSeries(top.url);
        } catch (_error) {
            payload = null;
        }
        if (!payload) return null;
        best = { candidate: top, series: payload.series, episodes: payload.episodes };
    }

    return cacheSet(SERIES_CACHE, cacheKey, best);
}

async function handleSeries(request, response, requestUrl) {
    let seriesUrl = String(requestUrl.searchParams.get("url") || "").trim();
    const title = String(requestUrl.searchParams.get("title") || "").trim();

    const respond = function(payload, matched) {
        return sendApiData(response, 200, {
            series: payload.series,
            episodeCount: payload.episodes.length,
            episodes: payload.episodes,
            matchedTitle: matched ? matched.title : "",
            matchedUrl: matched ? matched.url : ""
        });
    };

    if (!seriesUrl && title) {
        try {
            const best = await resolveBestSeriesForTitle(title);
            if (!best) return sendApiError(response, 404, "No series matched " + title + ".");
            return respond(best, best.candidate);
        } catch (error) {
            return sendApiError(response, 502, error.message || "Series scrape failed.");
        }
    }
    if (!seriesUrl) return sendApiError(response, 400, "url or title is required.");

    try {
        const payload = await loadSeries(seriesUrl);
        if (payload.episodes.length) return respond(payload, null);
        // The page parsed to zero episodes: it is a hub/batch entry, so fall
        // back to the ranked title search on the page's own title.
        const fallback = await resolveBestSeriesForTitle(payload.series.title || "");
        if (fallback && fallback.episodes.length) return respond(fallback, fallback.candidate);
        return respond(payload, null);
    } catch (error) {
        return sendApiError(response, 502, error.message || "Series scrape failed.");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Episode resolution — own player (Rumble HLS) with Dailymotion fallback
// ─────────────────────────────────────────────────────────────────────────────

/** The episode page hides each server inside a base64 `data-hash` iframe blob. */
function extractServerUrls(html) {
    const servers = { own: "", dailymotion: "" };
    const hashPattern = /data-hash=["\']([A-Za-z0-9+/=]{16,})["\']/g;
    let match = hashPattern.exec(html);
    while (match) {
        let decoded = "";
        try {
            decoded = Buffer.from(match[1], "base64").toString("utf8");
        } catch (_error) {
            decoded = "";
        }
        if (decoded) {
            const src = /src=["\']([^"\']+)["\']/i.exec(decoded);
            if (src) {
                const url = absoluteUrl(src[1], SITE);
                if (url.indexOf("playing.donghuaworld") !== -1 && !servers.own) servers.own = url;
                else if (url.indexOf("dailymotion") !== -1 && !servers.dailymotion) servers.dailymotion = url;
            }
        }
        match = hashPattern.exec(html);
    }
    // Some episodes also render the own-player iframe directly (no data-hash).
    if (!servers.own) {
        const direct = /(https?:\/\/playing\.donghuaworld\.[a-z]+\/[a-z0-9]+)/i.exec(html);
        if (direct) servers.own = direct[1];
    }
    if (!servers.dailymotion) {
        const dm = /(https?:\/\/(?:www\.)?(?:geo\.)?dailymotion\.com\/(?:player[^"\'\s]*[?&]video=|embed\/video\/)([A-Za-z0-9]+))/i.exec(html);
        if (dm) servers.dailymotion = dm[1];
    }
    return servers;
}

function dailymotionVideoId(url) {
    if (!url) return "";
    const query = /[?&]video=([A-Za-z0-9]+)/i.exec(url);
    if (query) return query[1];
    const path = /dailymotion\.com\/(?:embed\/)?(?:video\/)?([A-Za-z0-9]{6,})/i.exec(url);
    return path ? path[1] : "";
}

/**
 * Parse the own player's inline
 *   jwplayer("player").setup({ playlist: [{ sources: [...], tracks: [...] }] })
 * into a direct HLS master, per-quality CDN URLs and subtitle tracks.
 */
function parseOwnPlayer(page) {
    const normalized = String(page).replace(/\\\//g, "/");
    const result = { hls: "", qualities: [], subtitles: [] };

    const master = /"file"\s*:\s*"(https:\/\/rumble\.com\/hls-vod\/[^"]+\.m3u8[^"]*)"/i.exec(normalized);
    if (master) result.hls = master[1];

    // Only the Rumble CDN entries that carry a label are real renditions.
    const qualityPattern = /"file"\s*:\s*"(https:\/\/hugh\.cdn\.rumble\.cloud\/[^"]+)"\s*,\s*"type"\s*:\s*"video\/mp4"\s*,\s*"label"\s*:\s*"([^"]+)"/gi;
    let quality = qualityPattern.exec(normalized);
    while (quality) {
        result.qualities.push({ url: quality[1], label: decodeEntities(quality[2]) });
        quality = qualityPattern.exec(normalized);
    }

    const subPattern = /\{"file"\s*:\s*"(https:[^"]+\.vtt)"\s*,\s*"label"\s*:\s*"([^"]+)"\}/gi;
    let sub = subPattern.exec(normalized);
    while (sub) {
        result.subtitles.push({ url: sub[1], label: decodeEntities(sub[2]) });
        sub = subPattern.exec(normalized);
    }
    return result;
}

const SUBTITLE_LANG_CODES = {
    english: "en", indonesian: "id", malay: "ms", thai: "th", vietnamese: "vi",
    hindi: "hi", bangla: "bn", arabic: "ar", persian: "fa", turkish: "tr",
    russian: "ru", german: "de", french: "fr", spanish: "es", italian: "it",
    portuguese: "pt", polish: "pl", khmer: "km", chinese: "zh"
};

function subtitleLangCode(label) {
    const key = String(label || "").trim().toLowerCase();
    if (SUBTITLE_LANG_CODES[key]) return SUBTITLE_LANG_CODES[key];
    return key.replace(/[^a-z]/g, "").slice(0, 5) || "und";
}

function proxyBaseFrom(request) {
    const host = request.headers.host;
    if (!host) return "";
    // Behind the Render/Netlify TLS terminator `x-forwarded-proto` is always
    // set; on a direct plain-HTTP request it is absent, so fall back to the
    // socket's own encryption state instead of blindly assuming https (an
    // https:// URL pointed at an http:// listener can never play).
    const forwarded = request.headers["x-forwarded-proto"];
    let scheme;
    if (forwarded) {
        scheme = String(forwarded).split(",")[0].trim() || "https";
    } else {
        scheme = request.socket && request.socket.encrypted ? "https" : "http";
    }
    return scheme + "://" + host;
}

function hlsProxyUrl(base, url, profile) {
    const suffix = profile && profile !== "none" ? "&h=" + encodeURIComponent(profile) : "";
    return base + "/api/donghua/proxy?url=" + encodeURIComponent(url) + suffix;
}

/**
 * Same-origin, CORS-safe wrapper for the source's own VTT tracks.
 *
 * Native players (ExoPlayer / LibVLC / AVPlayer) fetch the CDN VTT directly,
 * but a browser only accepts a cross-origin <track> when the response carries
 * `Access-Control-Allow-Origin` — so the web player (Android TV / Vidaa / iOS
 * web) loads the track through this route instead and never black-screens or
 * silently drops the subtitle.
 */
function subtitleProxyUrl(base, url) {
    return base + "/api/donghua/subtitle?url=" + encodeURIComponent(url);
}

/** Hosts this source ships subtitles from — keeps the subtitle route non-open. */
const SUBTITLE_HOST_ALLOWLIST = [
    "rumble.cloud", "rumble.com", "dmcdn.net", "dailymotion.com",
    "donghuaworld.com", "donghuaworld.in"
];

function isAllowedSubtitleUrl(url) {
    try {
        const parsed = new URL(url);
        const host = parsed.hostname.toLowerCase();
        if (parsed.pathname.toLowerCase().indexOf(".vtt") !== -1) return true;
        return SUBTITLE_HOST_ALLOWLIST.some(function(allowed) {
            return host === allowed || host.endsWith("." + allowed);
        });
    } catch (_error) {
        return false;
    }
}

/** Cheap liveness gate: only advertise URLs our own stack (or the player) can fetch. */
async function playlistIsPlayable(url, profile) {
    if (!url) return false;
    try {
        if (profile === "dm") {
            const res = await curlGetText(url, DM_HEADERS, REQUEST_TIMEOUT_MS);
            return res.ok && res.body.indexOf("#EXTM3U") !== -1;
        }
        const res = await fetchText(url, { "User-Agent": BROWSER_UA, Accept: "*/*" });
        return res.ok && res.body.indexOf("#EXTM3U") !== -1;
    } catch (_error) {
        return false;
    }
}

/** Dailymotion: metadata endpoint -> signed master HLS (needs the fingerprint headers). */
async function resolveDailymotion(videoId) {
    if (!videoId) return null;
    const metadataUrl = "https://www.dailymotion.com/player/metadata/video/" + encodeURIComponent(videoId);
    // www.dailymotion.com accepts the Node transport (only the CDN edge is
    // fingerprint-gated); curl is the fallback in case that ever changes.
    let res = await rawGetText(metadataUrl, DM_HEADERS).catch(function() { return null; });
    if (!res || !res.ok) {
        res = await curlGetText(metadataUrl, DM_HEADERS, REQUEST_TIMEOUT_MS);
    }
    if (!res || !res.ok) return null;
    let parsed;
    try {
        parsed = JSON.parse(res.body);
    } catch (_error) {
        return null;
    }
    if (parsed && parsed.error) return null;
    const qualities = parsed && parsed.qualities ? parsed.qualities : null;
    if (!qualities) return null;
    const order = ["1080", "720", "480", "380", "240", "auto"];
    for (const key of order) {
        const tracks = qualities[key];
        if (Array.isArray(tracks) && tracks.length && tracks[0] && tracks[0].url) {
            return { quality: key, url: String(tracks[0].url) };
        }
    }
    return null;
}

/**
 * Build the player-ready payload for an episode page.
 *
 * Primary: own player -> public Rumble HLS (plays with zero headers).
 * Fallback: Dailymotion master HLS (needs the CDN fingerprint, so it is always
 * exposed through our own proxy — LibVLC/AVPlayer cannot send those headers).
 */
async function buildWatchPayload(episodeUrl, request) {
    const resolvedUrl = absoluteUrl(episodeUrl, SITE);
    if (!resolvedUrl || !/^https?:\/\/(www\.)?donghuaworld\.com\//i.test(resolvedUrl)) {
        throw new Error("episode url must be a donghuaworld.com URL.");
    }
    const cacheKey = "w:" + resolvedUrl;
    const cached = cacheGet(WATCH_CACHE, cacheKey);
    if (cached) return cached;

    const page = await fetchText(resolvedUrl, {
        "User-Agent": BROWSER_UA,
        Accept: "text/html,application/xhtml+xml",
        "Accept-Language": "en-US,en;q=0.9",
        Referer: SITE + "/"
    });
    if (!page.ok || isBlockedOrErrorPage(page.body)) {
        throw new Error("episode page fetch failed (HTTP " + page.status + ").");
    }

    const base = proxyBaseFrom(request);
    const servers = extractServerUrls(page.body);
    const streams = [];
    const subtitles = [];
    let title = metaContent(page.body, "og:title");
    if (title) title = decodeEntities(String(title).replace(/\s*[-\u2013|]\s*Donghua World.*$/i, "").trim());

    const errors = [];

    // ── Primary: the site's own player -> public Rumble HLS ────────────────
    if (servers.own) {
        try {
            const player = await fetchText(servers.own, {
                "User-Agent": BROWSER_UA,
                Accept: "text/html,application/xhtml+xml",
                "Accept-Language": "en-US,en;q=0.9",
                Referer: resolvedUrl
            });
            const parsed = parseOwnPlayer(player.body);
            // Only advertise a master we can actually pull — an unverified URL
            // is exactly what produces a black screen on the device.
            if (parsed.hls && await playlistIsPlayable(parsed.hls, "none")) {
                streams.push({
                    url: parsed.hls,
                    quality: "auto",
                    type: "hls",
                    label: "Donghuaworld (Rumble)",
                    direct: true,
                    headersJson: null,
                    proxyUrl: base ? hlsProxyUrl(base, parsed.hls, "none") : ""
                });
            } else if (parsed.hls) {
                errors.push("rumble master did not return a playlist");
            }
            for (const quality of parsed.qualities) {
                streams.push({
                    url: quality.url,
                    quality: quality.label,
                    type: "hls",
                    label: "Donghuaworld " + quality.label,
                    direct: true,
                    headersJson: null,
                    proxyUrl: base ? hlsProxyUrl(base, quality.url, "none") : ""
                });
            }
            for (const track of parsed.subtitles) {
                subtitles.push({
                    url: track.url,
                    label: track.label,
                    lang: subtitleLangCode(track.label),
                    // Browser-safe copy for the web player (CORS); native players
                    // use `url` directly.
                    proxyUrl: base ? subtitleProxyUrl(base, track.url) : ""
                });
            }
            if (!parsed.hls) errors.push("own player had no HLS source");
        } catch (error) {
            errors.push("own player: " + (error.message || "failed"));
        }
    } else {
        errors.push("no own player found on episode page");
    }

    // ── Secondary: Dailymotion, only if our own proxy can really serve it ──
    // Dailymotion's CDN gates on a TLS/header fingerprint that a Node HTTPS
    // stack does not satisfy, so the master is probed first and dropped when
    // the probe fails. That keeps the stream list 100% playable instead of
    // handing a player a URL that 403s.
    let dailymotion = null;
    const dmId = dailymotionVideoId(servers.dailymotion);
    if (dmId) {
        try {
            const dm = await resolveDailymotion(dmId);
            if (dm) {
                const proxied = base ? hlsProxyUrl(base, dm.url, "dm") : "";
                if (proxied && await playlistIsPlayable(dm.url, "dm")) {
                    dailymotion = {
                        videoId: dmId,
                        quality: dm.quality,
                        m3u8: dm.url,
                        proxyUrl: proxied
                    };
                    streams.push({
                        url: proxied,
                        quality: dm.quality,
                        type: "hls",
                        label: "Donghuaworld (Dailymotion)",
                        direct: true,
                        headersJson: null,
                        proxyUrl: proxied
                    });
                } else {
                    errors.push("dailymotion master not reachable from this server");
                }
            } else {
                errors.push("dailymotion metadata returned no qualities");
            }
        } catch (error) {
            errors.push("dailymotion: " + (error.message || "failed"));
        }
    } else {
        errors.push("no dailymotion video id on episode page");
    }

    const payload = {
        episodeUrl: resolvedUrl,
        title: title,
        server: "Donghuaworld",
        ownPlayer: servers.own || "",
        streams: streams,
        subtitles: subtitles,
        dailymotion: dailymotion,
        errors: errors
    };
    if (streams.length) cacheSet(WATCH_CACHE, cacheKey, payload);
    return payload;
}

async function handleWatch(request, response, requestUrl) {
    const episodeUrl = String(requestUrl.searchParams.get("url") || "").trim();
    if (!episodeUrl) return sendApiError(response, 400, "url is required.");
    try {
        const payload = await buildWatchPayload(episodeUrl, request);
        if (!payload.streams.length) {
            return sendApiError(response, 502,
                "No playable stream found for this episode." +
                (payload.errors.length ? " [" + payload.errors.join("; ") + "]" : ""));
        }
        return sendApiData(response, 200, payload);
    } catch (error) {
        return sendApiError(response, 502, error.message || "Episode resolve failed.");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  HLS proxy — same-origin playback path for players/networks that can't reach
//  the CDN directly. Rewrites every playlist URI (segments, EXT-X-KEY,
//  EXT-X-MAP, variant playlists) to stay inside this proxy.
// ─────────────────────────────────────────────────────────────────────────────

function proxyHeadersFor(profile, ref) {
    if (profile === "dm") return Object.assign({}, DM_HEADERS);
    const headers = { "User-Agent": BROWSER_UA, Accept: "*/*" };
    if (ref && /^https?:\/\//i.test(ref)) {
        headers.Referer = ref;
        headers.Origin = new URL(ref).origin;
    }
    return headers;
}

function rewriteHlsPlaylist(body, sourceUrl, profile) {
    const base = new URL(sourceUrl);
    const suffix = profile && profile !== "none" ? "&h=" + encodeURIComponent(profile) : "";
    const wrap = function(target) {
        return "/api/donghua/proxy?url=" + encodeURIComponent(target) + suffix;
    };
    return body
        .split(/\r?\n/)
        .map(function(line) {
            const trimmed = line.trim();
            if (!trimmed) return line;
            if (trimmed.indexOf("#") === 0) {
                if (trimmed.indexOf("URI=") === -1) return line;
                return line.replace(/URI="([^"]+)"/g, function(_match, uri) {
                    return 'URI="' + wrap(new URL(uri, base).toString()) + '"';
                });
            }
            return wrap(new URL(trimmed, base).toString());
        })
        .join("\n");
}

async function handleProxy(request, response, requestUrl) {
    const url = String(requestUrl.searchParams.get("url") || "").trim();
    if (!url || !/^https?:\/\//i.test(url)) {
        return sendApiError(response, 400, "url must be absolute http(s).");
    }
    const profile = String(requestUrl.searchParams.get("h") || "none");
    const ref = String(requestUrl.searchParams.get("ref") || "");
    const headers = proxyHeadersFor(profile, ref);
    if (request.headers.range) headers.Range = request.headers.range;

    // Dailymotion goes over the curl transport: its edge rejects Node's TLS
    // fingerprint with an HTML 403 while curl gets 200 for the identical URL and
    // header set, so curling is the only way its CDN content is fetchable here.
    if (profile === "dm") {
        return await curlUpstream(url, headers, request, response);
    }

    try {
        const upstream = await fetchWithTimeout(url, headers, PROXY_TIMEOUT_MS);
        if (!upstream.ok && upstream.status !== 206) {
            return sendJson(response, upstream.status, { error: "Upstream " + upstream.status });
        }
        const contentType = String(upstream.headers.get("content-type") || "");
        const isPlaylist = /\.m3u8|\.m3u/i.test(url) || contentType.indexOf("mpegurl") !== -1;

        if (isPlaylist) {
            const text = await upstream.text();
            const head = Object.assign({
                "content-type": "application/vnd.apple.mpegurl",
                "cache-control": "public, max-age=30"
            }, corsHeaders());
            response.writeHead(200, head);
            if (text.trim().indexOf("#EXTM3U") !== 0) {
                response.end(JSON.stringify({ error: "Not a valid m3u8", body: text.slice(0, 300) }));
                return;
            }
            response.end(rewriteHlsPlaylist(text, url, profile));
            return;
        }

        const head = Object.assign({
            "content-type": contentType || "application/octet-stream",
            "cache-control": "public, max-age=3600",
            "accept-ranges": upstream.headers.get("accept-ranges") || "bytes"
        }, corsHeaders());
        const length = upstream.headers.get("content-length");
        const range = upstream.headers.get("content-range");
        if (length) head["content-length"] = length;
        if (range) head["content-range"] = range;
        response.writeHead(upstream.status, head);

        if (!upstream.body) {
            response.end(Buffer.from(await upstream.arrayBuffer()));
            return;
        }
        const reader = upstream.body.getReader();
        try {
            while (true) {
                const chunk = await reader.read();
                if (chunk.done) break;
                response.write(Buffer.from(chunk.value));
            }
        } finally {
            reader.releaseLock();
        }
        response.end();
    } catch (error) {
        return sendApiError(response, 502, error.message || "Donghua proxy failed.");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Dailymotion transport — TLS-fingerprint workaround.
//
//  dmcdn's edge answers Node's ClientHello with an HTML 403 while the very same
//  URL and header set returns 200 from curl (verified: identical headers, both
//  HTTP/1.1 and HTTP/2, 403 from Node vs 200 from curl in the same second), i.e.
//  the block keys on the TLS fingerprint. Dailymotion content is therefore
//  fetched through the system curl binary; when curl is unavailable the DM path
//  simply reports "not playable" and is dropped from the stream list — exactly
//  like before, so a missing binary can never break playback.
// ─────────────────────────────────────────────────────────────────────────────

let _curlBinary = null;

function curlBinary() {
    if (_curlBinary !== null) return _curlBinary;
    const candidates = ["curl", "/usr/bin/curl", "/usr/local/bin/curl", "/bin/curl"];
    for (const candidate of candidates) {
        try {
            require("child_process").execFileSync(candidate, ["--version"], { stdio: "ignore", timeout: 5000 });
            _curlBinary = candidate;
            return _curlBinary;
        } catch (_error) { /* try the next candidate */ }
    }
    _curlBinary = "";
    return _curlBinary;
}

function curlHeaderArgs(url, headers) {
    const args = ["-sS", "--max-time", String(Math.round(PROXY_TIMEOUT_MS / 1000)), "-D", "-"];
    const source = headers || {};
    for (const key of Object.keys(source)) {
        const value = source[key];
        if (value === undefined || value === null || value === "") continue;
        args.push("-H", key + ": " + value);
    }
    args.push(url);
    return args;
}

function parseCurlHeaders(block) {
    const meta = { status: 0, contentType: "", contentLength: "", contentRange: "", acceptRanges: "" };
    const lines = String(block || "").split(/\r?\n/);
    for (const line of lines) {
        const status = /^HTTP\/[\d.]+\s+(\d{3})/.exec(line);
        if (status) {
            meta.status = parseInt(status[1], 10);
            continue;
        }
        const colon = line.indexOf(":");
        if (colon === -1) continue;
        const name = line.slice(0, colon).trim().toLowerCase();
        const value = line.slice(colon + 1).trim();
        if (name === "content-type") meta.contentType = value;
        else if (name === "content-length") meta.contentLength = value;
        else if (name === "content-range") meta.contentRange = value;
        else if (name === "accept-ranges") meta.acceptRanges = value;
    }
    return meta;
}

/** curl GET for text (playlist probes, metadata fallback). */
function curlGetText(url, headers, timeoutMs) {
    const binary = curlBinary();
    if (!binary) return Promise.resolve({ status: 0, ok: false, body: "" });
    const seconds = Math.max(5, Math.round((timeoutMs || REQUEST_TIMEOUT_MS) / 1000));
    const args = ["-sS", "-L", "--max-time", String(seconds)];
    const source = headers || {};
    for (const key of Object.keys(source)) {
        const value = source[key];
        if (value === undefined || value === null || value === "") continue;
        args.push("-H", key + ": " + value);
    }
    args.push("-w", "\n__HTTP_STATUS__%{http_code}");
    args.push(url);
    return new Promise(function(resolve) {
        require("child_process").execFile(binary, args,
            { maxBuffer: 32 * 1024 * 1024, timeout: (seconds + 5) * 1000 },
            function(_error, stdout) {
                const raw = String(stdout || "");
                const marker = raw.lastIndexOf("__HTTP_STATUS__");
                const body = marker === -1 ? raw : raw.slice(0, marker);
                const status = marker === -1 ? 0 : parseInt(raw.slice(marker + 15).trim(), 10) || 0;
                resolve({ status: status, ok: status >= 200 && status < 300, body: body.replace(/\n$/, "") });
            });
    });
}

/** curl GET piped straight to the client, rewriting HLS playlists in-flight. */
function curlUpstream(url, headers, request, response) {
    return new Promise(function(resolve) {
        const binary = curlBinary();
        if (!binary) {
            sendApiError(response, 502, "Dailymotion transport unavailable on this server.");
            return resolve();
        }
        const child = require("child_process").spawn(binary, curlHeaderArgs(url, headers), { stdio: ["ignore", "pipe", "pipe"] });
        let pending = Buffer.alloc(0);
        let meta = null;
        let playlistChunks = null;
        let done = false;
        const abort = function() { try { child.kill("SIGKILL"); } catch (_error) {} };
        request.on("close", function() { if (!done) abort(); });
        child.stderr.on("data", function() { /* curl diagnostics are not forwarded */ });

        child.stdout.on("data", function(chunk) {
            if (!meta) {
                pending = Buffer.concat([pending, chunk]);
                const sep = pending.indexOf("\r\n\r\n");
                if (sep === -1) {
                    if (pending.length > 64 * 1024) abort();
                    return;
                }
                meta = parseCurlHeaders(pending.slice(0, sep).toString("utf8"));
                const rest = pending.slice(sep + 4);
                pending = Buffer.alloc(0);
                if (meta.status !== 200 && meta.status !== 206) {
                    sendJson(response, meta.status || 502, { error: "Upstream " + (meta.status || 0) });
                    done = true;
                    abort();
                    resolve();
                    return;
                }
                const isPlaylist = /\.m3u8|\.m3u/i.test(url) || String(meta.contentType).indexOf("mpegurl") !== -1;
                if (isPlaylist) {
                    playlistChunks = [rest];
                    return;
                }
                const head = Object.assign({
                    "content-type": meta.contentType || (/\.ts(\?|$)/i.test(url) ? "video/mp2t" : "application/octet-stream"),
                    "cache-control": "public, max-age=3600",
                    "accept-ranges": meta.acceptRanges || "bytes"
                }, corsHeaders());
                if (meta.contentLength) head["content-length"] = meta.contentLength;
                if (meta.contentRange) head["content-range"] = meta.contentRange;
                response.writeHead(meta.status, head);
                if (rest.length) response.write(rest);
                return;
            }
            if (playlistChunks) {
                playlistChunks.push(chunk);
                return;
            }
            response.write(chunk);
        });

        child.on("close", function() {
            done = true;
            if (!meta) {
                resolve(sendApiError(response, 502, "Dailymotion returned no response."));
                return;
            }
            if (playlistChunks) {
                const text = Buffer.concat(playlistChunks).toString("utf8");
                response.writeHead(200, Object.assign({
                    "content-type": "application/vnd.apple.mpegurl",
                    "cache-control": "public, max-age=30"
                }, corsHeaders()));
                if (text.trim().indexOf("#EXTM3U") !== 0) {
                    response.end(JSON.stringify({ error: "Not a valid m3u8", body: text.slice(0, 300) }));
                    return resolve();
                }
                response.end(rewriteHlsPlaylist(text, url, "dm"));
                return resolve();
            }
            try { response.end(); } catch (_error) {}
            resolve();
        });

        child.on("error", function(error) {
            done = true;
            if (!response.headersSent) sendApiError(response, 502, error.message || "Dailymotion proxy failed.");
            else try { response.end(); } catch (_error) {}
            resolve();
        });
    });
}

// ─────────────────────────────────────────────────────────────────────────────
//  Subtitle relay (web player only — native players use the CDN URL directly).
//  Serves the source's public VTT with `Access-Control-Allow-Origin: *` so a
//  <track> element never gets blocked by CORS.
// ─────────────────────────────────────────────────────────────────────────────

async function handleSubtitle(request, response, requestUrl) {
    const target = String(requestUrl.searchParams.get("url") || "").trim();
    if (!/^https?:\/\//i.test(target) || !isAllowedSubtitleUrl(target)) {
        return sendApiError(response, 400, "a supported subtitle url is required.");
    }
    try {
        // Two attempts: a browser attaching the full track list fires many of
        // these at once and the CDN can drop one under that burst.
        let upstream = null;
        for (let attempt = 0; attempt < 2; attempt++) {
            try {
                upstream = await fetchText(target, {
                    "User-Agent": BROWSER_UA,
                    Accept: "text/vtt,*/*",
                    Referer: SITE + "/"
                }, 30 * 1000);
            } catch (_error) {
                upstream = null;
            }
            if (upstream && upstream.ok) break;
            await new Promise(function(resolve) { setTimeout(resolve, 350); });
        }
        if (!upstream || !upstream.ok) {
            const status = upstream ? upstream.status : 0;
            return sendApiError(response, 502, "subtitle fetch failed (HTTP " + status + ").");
        }
        const body = Buffer.from(upstream.body || "", "utf8");
        const headers = corsHeaders();
        headers["Content-Type"] = "text/vtt; charset=utf-8";
        headers["Cache-Control"] = "public, max-age=3600";
        headers["Content-Length"] = String(body.length);
        response.writeHead(200, headers);
        response.end(body);
    } catch (error) {
        return sendApiError(response, 502, (error && error.message) || "subtitle fetch failed.");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  One-call playback: title + episode number -> ready-to-play stream.
//  Used by the app when all it has is a TMDB-sourced donghua title.
// ─────────────────────────────────────────────────────────────────────────────

async function handlePlay(request, response, requestUrl) {
    const title = String(requestUrl.searchParams.get("title") || "").trim();
    const episodeRaw = String(requestUrl.searchParams.get("ep") || "1").trim();
    if (!title) return sendApiError(response, 400, "title is required.");
    const wantEpisode = parseInt(episodeRaw, 10) || 1;

    try {
        // Ranked candidate walk: guarantees the resolved series has episodes
        // even when the top title match is a grid-less hub page.
        const match = await resolveBestSeriesForTitle(title);
        if (!match) return sendApiError(response, 404, "No donghuaworld series matched " + title + ".");
        const series = match;
        if (!series.episodes.length) {
            const label = match.candidate && match.candidate.title ? match.candidate.title : title;
            return sendApiError(response, 404, "No episodes found for " + label + ".");
        }
        // Prefer an exact episode-number match; fall back to the closest one
        // without going past the available range (donghua numbering is 1-based
        // and continuous, so a soft miss usually means a slightly stale list).
        let target = series.episodes.filter(function(ep) { return ep.number === wantEpisode; })[0];
        if (!target) {
            const notAfter = series.episodes.filter(function(ep) { return ep.number <= wantEpisode; });
            target = notAfter.length ? notAfter[notAfter.length - 1] : series.episodes[0];
        }
        const watch = await buildWatchPayload(target.url, request);
        if (!watch.streams.length) {
            return sendApiError(response, 502, "No playable stream for episode " + target.number + ".");
        }
        return sendApiData(response, 200, {
            series: series.series,
            episode: target,
            episodeCount: series.episodes.length,
            streams: watch.streams,
            subtitles: watch.subtitles,
            errors: watch.errors
        });
    } catch (error) {
        return sendApiError(response, 502, error.message || "Donghua play resolve failed.");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Router
// ─────────────────────────────────────────────────────────────────────────────

async function handleDonghua(request, response, pathname, requestUrl) {
    try {
        if (request.method === "OPTIONS") {
            response.writeHead(204, corsHeaders());
            response.end();
            return;
        }
        if (pathname === "/api/donghua/search") {
            return await handleSearch(request, response, requestUrl);
        }
        if (pathname === "/api/donghua/series") {
            return await handleSeries(request, response, requestUrl);
        }
        if (pathname === "/api/donghua/watch") {
            return await handleWatch(request, response, requestUrl);
        }
        if (pathname === "/api/donghua/play") {
            return await handlePlay(request, response, requestUrl);
        }
        if (pathname === "/api/donghua/proxy" || pathname === "/api/donghua/proxy/m3u8") {
            return await handleProxy(request, response, requestUrl);
        }
        if (pathname === "/api/donghua/subtitle") {
            return await handleSubtitle(request, response, requestUrl);
        }
        return sendApiError(response, 404, "Donghua route not found.");
    } catch (error) {
        return sendApiError(response, 500, (error && error.message) || "Donghua request failed.");
    }
}

module.exports = { handleDonghua, SITE: SITE };
