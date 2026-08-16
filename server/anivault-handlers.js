// ─────────────────────────────────────────────────────────────────────────────
//  AniVault Anime Bridge (dependency-free)
//
//  Ports the Anivault-Scraper back-end trick WITHOUT requiring axios/cheerio/
//  crypto-js — the app server is CommonJS with only @consumet/extensions in
//  package.json, so everything here is built on global fetch + regex so it
//  deploys on Render with zero new dependencies.
//
//  WHY THIS EXISTS:
//  The 13 Anivexa providers (server/anivexa) are scraped from Render's
//  datacenter IPs and their stream sites return zero streams for popular anime
//  (Cloudflare/datacenter blocking). AnimeHeaven is explicitly NOT behind
//  Cloudflare, serves direct MP4s, and the repo owner's own pipeline
//  (Anivault-Scraper) plays Dragon Ball through it. This handler adds
//  AnimeHeaven + AnimePahe + AniDao to the anime server list (miruro excluded
//  per user request), and exposes a stream proxy that forwards video/m3u8 with
//  the correct Referer/Origin and rewrites HLS EXT-X-KEY + segment URIs through
//  the same proxy — the exact trick that makes CDN 403s disappear.
//
//  NOTE: this file deliberately avoids optional chaining (?.) and nullish
//  coalescing (??) because the codebase formatter rewrites those tokens into
//  invalid syntax. All null checks use explicit ternaries / && / ||.
//
//  ROUTES (/api/anivault):
//    GET /api/anivault/search?q={title}&source={s}
//    GET /api/anivault/episodes?source={s}&q={title}
//    GET /api/anivault/episodes?source=animeheaven&heavenId={code}
//    GET /api/anivault/watch?source={s}&id={id}&ep={n}
//    GET /api/anivault/proxy/stream?url=&ref=&origin=
//    GET /api/anivault/proxy/m3u8?url=&ref=
// ─────────────────────────────────────────────────────────────────────────────

const ANIME_HEAVEN = "https://animeheaven.me";
const ANIME_PAHE_CANDIDATES = [
    "https://animepahe.pw",
    "https://animepahe.ch",
    "https://animepahe.ng",
    "https://animepahe.org"
];
const ANI_DAO = "https://anidao.to";
const SOURCES = ["animeheaven", "animepahe", "anidao"];
const BROWSER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36";
const REQUEST_TIMEOUT_MS = 20 * 1000;

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
    sendJson(response, statusCode, {
        ok: statusCode >= 200 && statusCode < 300,
        data,
        error: null
    });
}

function sendApiError(response, statusCode, message) {
    sendJson(response, statusCode, { ok: false, data: null, error: message });
}

async function fetchText(url, headers, timeoutMs) {
    const finalHeaders = headers || {};
    const timeout = timeoutMs || REQUEST_TIMEOUT_MS;
    const controller = new AbortController();
    const timer = setTimeout(function() { controller.abort(); }, timeout);
    try {
        const res = await fetch(url, {
            headers: Object.assign({ "User-Agent": BROWSER_UA, Accept: "text/html,*/*" },
                finalHeaders
            ),
            signal: controller.signal,
            redirect: "follow"
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        return await res.text();
    } finally {
        clearTimeout(timer);
    }
}

async function fetchJson(url, headers) {
    const controller = new AbortController();
    const timer = setTimeout(function() { controller.abort(); }, REQUEST_TIMEOUT_MS);
    try {
        const res = await fetch(url, {
            headers: Object.assign({ "User-Agent": BROWSER_UA, Accept: "application/json,*/*" },
                headers || {}
            ),
            signal: controller.signal,
            redirect: "follow"
        });
        if (!res.ok) throw new Error("HTTP " + res.status);
        return await res.json();
    } finally {
        clearTimeout(timer);
    }
}

function decodeEntities(value) {
    return String(value || "")
        .replace(/&/g, "&")
        .replace(/"/g, '"')
        .replace(/'/g, "'")
        .replace(/</g, "<")
        .replace(/>/g, ">");
}

function normalizeTitle(value) {
    return String(value || "")
        .toLowerCase()
        .replace(/[^a-z0-9]+/g, " ")
        .trim()
        .replace(/\s+/g, " ");
}

function scoreTitle(query, title) {
    const q = normalizeTitle(query);
    const t = normalizeTitle(title);
    if (!q || !t) return 0;
    if (t === q) return 100;
    const ratio = Math.min(q.length, t.length) / Math.max(q.length, t.length);
    if (t.startsWith(q) || q.startsWith(t)) {
        const qMissing = q.split(" ").length - t.split(" ").length;
        if (qMissing >= 2) return Math.floor(ratio * 30);
        return ratio >= 0.6 ? 80 : Math.floor(ratio * 60);
    }
    if (t.includes(q) || q.includes(t)) {
        return ratio >= 0.6 ? 60 : Math.floor(ratio * 45);
    }
    return 0;
}

// ── AnimeHeaven ──────────────────────────────────────────────────────────────
async function heavenSearch(query) {
    const html = await fetchText(
        ANIME_HEAVEN + "/fastsearch.php?xhr=1&s=" + encodeURIComponent(query)
    );
    const results = [];
    // href may be "/anime.php?xxxxx" (fastsearch) or "anime.php?xxxxx" (full page)
    const re =
        /<a[^>]+href=["']\/?anime\.php\?([^"'&]+)["'][^>]*>[\s\S]*?<img[^>]+src=["']([^"']+)["'][^>]*alt=["']([^"']+)["']/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        results.push({
            id: decodeEntities(m[1].trim()),
            title: decodeEntities(m[3].trim()),
            image: m[2]
        });
    }
    if (results.length === 0) {
        const fallback = /<a[^>]+href=["']\/?anime\.php\?([^"'&]+)["'][^>]*>([\s\S]*?)<\/a>/gi;
        while ((m = fallback.exec(html)) !== null) {
            const inner = m[2].replace(/<[^>]*>/g, "").trim();
            if (!inner) continue;
            results.push({
                id: decodeEntities(m[1].trim()),
                title: decodeEntities(inner),
                image: ""
            });
        }
    }
    const seen = new Set();
    return results.filter(function(r) {
        if (seen.has(r.id)) return false;
        seen.add(r.id);
        return true;
    });
}

async function heavenBestId(query) {
    const noPossessive = query.replace(/[']s\b/gi, "").replace(/[']/g, "");
    const rawVariants = [
        query,
        noPossessive,
        query.split(/[:(|]/)[0].trim(),
        noPossessive.split(/[:(|]/)[0].trim()
    ];
    const variants = [];
    const seen = new Set();
    for (const v of rawVariants) {
        const clean = v.trim().replace(/\s+/g, " ");
        if (clean.length >= 3 && !seen.has(clean)) {
            seen.add(clean);
            variants.push(clean);
        }
    }
    for (const variant of variants) {
        try {
            const results = await heavenSearch(variant);
            const scored = results
                .map(function(r) { return { r: r, s: scoreTitle(query, r.title) }; })
                .sort(function(a, b) { return b.s - a.s; });
            const best = scored[0];
            if (best && best.s >= 60) return best.r.id;
        } catch (err) {
            /* try next variant */
        }
    }
    return null;
}

async function heavenEpisodes(animeId) {
    const html = await fetchText(
        ANIME_HEAVEN + "/anime.php?" + encodeURIComponent(animeId)
    );
    const episodes = [];
    const re =
        /<a[^>]+(?:onmouseover|onclick)=["']gate[ha]\(["']([^"']+)["'][^>]*>[\s\S]*?<div[^>]*class=["'][^"']*watch2[^"']*["'][^>]*>([^<]+)<\/div>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        const numRaw = m[2].trim();
        const num = Number(numRaw.replace(/^0+(\d)/, "$1"));
        if (!m[1] || !Number.isFinite(num)) continue;
        episodes.push({
            id: decodeEntities(m[1]),
            number: num,
            title: "Episode " + numRaw
        });
    }
    const seen = new Set();
    return episodes
        .filter(function(e) {
            if (seen.has(e.id)) return false;
            seen.add(e.id);
            return true;
        })
        .sort(function(a, b) { return a.number - b.number; });
}

async function heavenWatch(episodeId) {
    const html = await fetchText(
        ANIME_HEAVEN + "/gate.php?id=" + encodeURIComponent(episodeId), { Cookie: "key=" + episodeId, Referer: ANIME_HEAVEN + "/" }
    );
    const urls = Array.from(html.matchAll(/<source[^>]+src=["']([^"']+)["']/gi))
        .map(function(m) { return m[1].trim(); })
        .filter(function(u) { return /^https?:\/\//i.test(u); });
    const primary =
        urls.find(function(u) { return u.includes("/video.mp4"); }) || urls[0];
    if (!primary) throw new Error("AnimeHeaven returned no video source.");
    return {
        url: primary,
        type: "mp4",
        isDirect: true,
        referer: ANIME_HEAVEN + "/",
        origin: ANIME_HEAVEN
    };
}

// ── AnimePahe ────────────────────────────────────────────────────────────────
let paheBase = null;

async function resolvePaheBase() {
    if (paheBase) return paheBase;
    for (const candidate of ANIME_PAHE_CANDIDATES) {
        try {
            const controller = new AbortController();
            const timer = setTimeout(function() { controller.abort(); }, 8000);
            const res = await fetch(candidate + "/api?m=search&q=test", {
                headers: { "User-Agent": BROWSER_UA },
                signal: controller.signal
            });
            clearTimeout(timer);
            const contentType = res.headers.get("content-type") || "";
            if (res.ok && contentType.includes("json")) {
                paheBase = candidate;
                return candidate;
            }
        } catch (err) {
            /* try next mirror */
        }
    }
    throw new Error("No AnimePahe mirror reachable.");
}

async function paheSearch(query) {
    const base = await resolvePaheBase();
    const data = await fetchJson(
        base + "/api?m=search&q=" + encodeURIComponent(query), { Referer: base + "/", "X-Requested-With": "XMLHttpRequest" }
    );
    const list = data && Array.isArray(data.data) ? data.data : [];
    return list.map(function(a) {
        return {
            id: String(a.id || ""),
            session: String(a.session || ""),
            title: decodeEntities(a.title || "")
        };
    });
}

async function paheBest(query) {
    const noPossessive = query.replace(/[']s\b/gi, "").replace(/[']/g, "");
    const rawVariants = [
        query,
        noPossessive,
        query.split(/[:(|]/)[0].trim(),
        query.split(" ").slice(0, 2).join(" ")
    ];
    const variants = [];
    const seen = new Set();
    for (const v of rawVariants) {
        const clean = v.trim().replace(/\s+/g, " ");
        if (clean.length >= 3 && !seen.has(clean)) {
            seen.add(clean);
            variants.push(clean);
        }
    }
    for (const variant of variants) {
        try {
            const results = await paheSearch(variant);
            const scored = results
                .map(function(r) { return { r: r, s: scoreTitle(query, r.title) }; })
                .sort(function(a, b) { return b.s - a.s; });
            if (scored[0] && scored[0].s >= 45) return scored[0].r;
        } catch (err) {
            /* next */
        }
    }
    return null;
}

async function paheEpisodes(session) {
    const base = await resolvePaheBase();
    const episodes = [];
    let page = 1;
    let lastPage = 1;
    do {
        const data = await fetchJson(
            base + "/api?m=release&id=" + encodeURIComponent(session) +
            "&sort=episode_asc&page=" + page, { Referer: base + "/", "X-Requested-With": "XMLHttpRequest" }
        );
        if (data && (data.last_page || data.lastPage)) {
            lastPage = Number(data.last_page || data.lastPage || lastPage);
        }
        const list = data && Array.isArray(data.data) ? data.data : [];
        for (const e of list) {
            const num = Number(e.episode || 0);
            if (!Number.isFinite(num) || num <= 0) continue;
            if (e.session) {
                episodes.push({
                    number: num,
                    session: String(e.session),
                    duration: e.duration || "",
                    title: "Episode " + num
                });
            }
        }
        page++;
    } while (page <= lastPage && page <= 20);
    return episodes;
}

async function paheWatch(animeSession, episodeSession) {
    const base = await resolvePaheBase();
    const html = await fetchText(
        base + "/play/" + encodeURIComponent(animeSession) + "/" +
        encodeURIComponent(episodeSession), { Referer: base + "/", Accept: "text/html,*/*" }
    );
    const kwik = Array.from(
        html.matchAll(/data-src=["']([^"']*kwik[^"']*)["']/gi)
    ).map(function(m) { return m[1]; });
    const anyEmbed = Array.from(
            html.matchAll(/data-src=["']([^"']+)["']/gi)
        )
        .map(function(m) { return m[1]; })
        .filter(function(u) { return /^https?:\/\//i.test(u); });
    const embedUrl = kwik[0] || anyEmbed[0];
    if (embedUrl) {
        return {
            url: embedUrl,
            type: "embed",
            isDirect: false,
            referer: base + "/",
            origin: base
        };
    }
    const directMatches = Array.from(
        html.matchAll(/(?:https?:)?\/\/[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*/gi)
    );
    const direct = directMatches.length ? directMatches[0][0] : "";
    const resolvedDirect = direct.startsWith("//") ? "https:" + direct : direct;
    if (resolvedDirect) {
        return {
            url: resolvedDirect,
            type: resolvedDirect.includes(".m3u8") ? "hls" : "mp4",
            isDirect: true,
            referer: base + "/",
            origin: base
        };
    }
    throw new Error("AnimePahe returned no playable source for this episode.");
}

// ── AniDao ───────────────────────────────────────────────────────────────────
async function daoSearch(query) {
    const html = await fetchText(
        ANI_DAO + "/search.html?keyword=" + encodeURIComponent(query)
    );
    const results = [];
    const re = /<a[^>]+href=["']([^"']*\/anime\/[^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        const href = m[1];
        const parts = href.split("/").filter(Boolean);
        const idPart = parts.length ? parts[parts.length - 1] : "";
        const id = (idPart.split("?")[0] || "").trim();
        if (!id) continue;
        const title = m[2]
            .replace(/<[^>]*>/g, "")
            .trim()
            .replace(/\s+/g, " ");
        if (!title || title.length < 2) continue;
        results.push({
            id: decodeEntities(id),
            title: decodeEntities(title),
            url: href.startsWith("http") ? href : ANI_DAO + href
        });
    }
    const seen = new Set();
    return results.filter(function(r) {
        if (seen.has(r.id)) return false;
        seen.add(r.id);
        return true;
    });
}

async function daoBest(query) {
    const compressed = query.split(/[:(|]/)[0].trim().replace(/\s+/g, " ");
    const variants = [query, compressed, query.replace(/[']/g, "")];
    for (const variant of variants) {
        try {
            const results = await daoSearch(variant);
            const scored = results
                .map(function(r) { return { r: r, s: scoreTitle(query, r.title) }; })
                .sort(function(a, b) { return b.s - a.s; });
            const best = scored.find(function(x) { return x.s >= 50; });
            if (best) return best.r;
        } catch (err) {
            /* next */
        }
    }
    return null;
}

async function daoEpisodes(animeId) {
    const slug = animeId.replace(/-\d+$/, "");
    const html = await fetchText(ANI_DAO + "/anime/" + encodeURIComponent(slug));
    const episodes = [];
    const re =
        /<a[^>]+href=["']([^"']*\/watch-online\/[^"']+episode-(\d+(?:\.\d+)?)[^"']*)["'][^>]*>([\s\S]*?)<\/a>/gi;
    let m;
    while ((m = re.exec(html)) !== null) {
        const num = Number(m[2]);
        if (!Number.isFinite(num)) continue;
        const title = m[3].replace(/<[^>]*>/g, "").trim() || "Episode " + num;
        episodes.push({
            number: num,
            url: m[1].startsWith("http") ? m[1] : ANI_DAO + m[1],
            title: decodeEntities(title)
        });
    }
    const seen = new Set();
    return episodes
        .filter(function(e) {
            if (seen.has(e.number)) return false;
            seen.add(e.number);
            return true;
        })
        .sort(function(a, b) { return a.number - b.number; });
}

async function daoWatch(episodeUrl) {
    const html = await fetchText(episodeUrl, { Referer: ANI_DAO + "/" });
    const iframes = Array.from(html.matchAll(/<iframe[^>]+src=["']([^"']+)["']/gi))
        .map(function(m) { return m[1]; });
    const iframe = iframes.find(function(u) {
        return /^https?:\/\//i.test(u) && !/google|facebook|disqus/i.test(u);
    });
    const directMatches = Array.from(
        html.matchAll(/(?:https?:)?\/\/[^"'\s<>]+\.(?:m3u8|mp4|mpd)[^"'\s<>]*/gi)
    );
    const directRaw = directMatches.length ? directMatches[0][0] : "";
    const direct = directRaw.startsWith("//") ? "https:" + directRaw : directRaw;
    if (direct) {
        return {
            url: direct,
            type: direct.includes(".m3u8") ? "hls" : "mp4",
            isDirect: true,
            referer: ANI_DAO + "/",
            origin: ANI_DAO
        };
    }
    if (iframe) {
        return {
            url: iframe,
            type: "embed",
            isDirect: false,
            referer: episodeUrl,
            origin: new URL(episodeUrl).origin
        };
    }
    throw new Error("AniDao returned no playable source for this episode.");
}

// ── unified source dispatch ──────────────────────────────────────────────────
const EPISODE_CACHE = new Map();
const WATCH_CACHE = new Map();
const TTL_EPISODES = 10 * 60 * 1000;
const TTL_WATCH = 5 * 60 * 1000;

async function resolveSourceId(source, query) {
    if (source === "animeheaven") {
        return { id: await heavenBestId(query), label: "AnimeHeaven" };
    }
    if (source === "animepahe") {
        const best = await paheBest(query);
        if (best) {
            return {
                id: best.session,
                label: best.title,
                session: best.session
            };
        }
        return { id: null, label: "" };
    }
    if (source === "anidao") {
        const best = await daoBest(query);
        if (best) return { id: best.id, label: best.title };
        return { id: null, label: "" };
    }
    return { id: null, label: "" };
}

async function fetchSourceEpisodes(source, id, session, heavenId) {
    if (source === "animeheaven") {
        return await heavenEpisodes(heavenId || id);
    }
    if (source === "animepahe") return await paheEpisodes(session || id);
    if (source === "anidao") return await daoEpisodes(id);
    return [];
}

async function resolveSourceWatch(source, episode, resolve) {
    if (source === "animeheaven") return await heavenWatch(episode.id);
    if (source === "animepahe") {
        const animeSession = (resolve && resolve.session) || "";
        return await paheWatch(animeSession, episode.session);
    }
    if (source === "anidao") return await daoWatch(episode.url);
    throw new Error("Unknown source");
}

// ── handlers ─────────────────────────────────────────────────────────────────
async function handleSearch(request, response, requestUrl) {
    const query = String(requestUrl.searchParams.get("q") || "").trim();
    const source = String(
        requestUrl.searchParams.get("source") || "animeheaven"
    ).toLowerCase();
    if (SOURCES.indexOf(source) === -1) {
        return sendApiError(response, 400, "source must be: " + SOURCES.join(", "));
    }
    if (!query) return sendApiError(response, 400, "q is required.");
    try {
        const found = await resolveSourceId(source, query);
        return sendApiData(response, 200, Object.assign({ source: source }, found));
    } catch (error) {
        return sendApiError(response, 502, error.message || "Search failed.");
    }
}

async function handleEpisodes(request, response, requestUrl) {
    const source = String(
        requestUrl.searchParams.get("source") || "animeheaven"
    ).toLowerCase();
    if (SOURCES.indexOf(source) === -1) {
        return sendApiError(response, 400, "source must be: " + SOURCES.join(", "));
    }
    const query = String(requestUrl.searchParams.get("q") || "").trim();
    const heavenId = String(requestUrl.searchParams.get("heavenId") || "").trim();
    const id = String(requestUrl.searchParams.get("id") || "").trim();
    const session = String(requestUrl.searchParams.get("session") || "").trim();

    let resolved;
    if (heavenId && source === "animeheaven") {
        resolved = { id: heavenId, session: null, label: "" };
    } else if (id || (session && source === "animepahe")) {
        resolved = { id: id, session: session, label: "" };
    } else if (query) {
        resolved = await resolveSourceId(source, query);
    } else {
        return sendApiError(
            response,
            400,
            "Provide ?q=title, or ?id=, or ?heavenId= for AnimeHeaven."
        );
    }
    const hasKey = resolved.id || (source === "animepahe" && resolved.session);
    if (!hasKey) {
        return sendApiData(response, 200, {
            source: source,
            siteId: null,
            title: resolved.label || "",
            episodes: []
        });
    }

    const cacheKey = source + ":" + (resolved.id || resolved.session);
    const cached = EPISODE_CACHE.get(cacheKey);
    let episodes;
    if (cached && Date.now() - cached.at < TTL_EPISODES) {
        episodes = cached.episodes;
    } else {
        episodes = await fetchSourceEpisodes(
            source,
            resolved.id,
            resolved.session,
            heavenId
        );
        EPISODE_CACHE.set(cacheKey, { at: Date.now(), episodes: episodes });
    }
    return sendApiData(response, 200, {
        source: source,
        siteId: resolved.id || null,
        session: resolved.session || null,
        title: resolved.label || "",
        count: episodes.length,
        episodes: episodes
    });
}

async function handleWatch(request, response, requestUrl) {
    const source = String(
        requestUrl.searchParams.get("source") || "animeheaven"
    ).toLowerCase();
    if (SOURCES.indexOf(source) === -1) {
        return sendApiError(response, 400, "source must be: " + SOURCES.join(", "));
    }
    const epRaw = String(requestUrl.searchParams.get("ep") || "").trim();
    const id = String(requestUrl.searchParams.get("id") || "").trim();
    const session = String(requestUrl.searchParams.get("session") || "").trim();
    const heavenId = String(requestUrl.searchParams.get("heavenId") || "").trim();
    if (!epRaw) return sendApiError(response, 400, "ep is required.");
    const epNum = Number(epRaw);
    if (!Number.isFinite(epNum)) {
        return sendApiError(response, 400, "ep must be numeric.");
    }

    try {
        let resolve;
        if (heavenId || id || session) {
            resolve = { id: id, session: session, heavenId: heavenId };
        } else {
            const q = String(requestUrl.searchParams.get("q") || "").trim();
            if (!q) {
                return sendApiError(
                    response,
                    400,
                    "Need id/session/heavenId or q."
                );
            }
            resolve = await resolveSourceId(source, q);
        }
        if (!resolve.id && !resolve.session && !resolve.heavenId) {
            return sendApiError(response, 404, "Anime not found for this source.");
        }
        const episodes = await fetchSourceEpisodes(
            source,
            resolve.id,
            resolve.session,
            resolve.heavenId
        );
        const episode = episodes.find(function(e) {
            return Math.round(e.number) === Math.round(epNum);
        }) || null;
        if (!episode) {
            return sendApiError(response, 404, "Episode " + epNum + " not found.");
        }

        const watchCacheKey =
            source +
            ":" +
            JSON.stringify({
                id: resolve.id,
                session: resolve.session,
                heavenId: resolve.heavenId,
                ep: epNum
            });
        const watchCached = WATCH_CACHE.get(watchCacheKey);
        let result;
        if (watchCached && Date.now() - watchCached.at < TTL_WATCH) {
            result = watchCached.result;
        } else {
            result = await resolveSourceWatch(source, episode, resolve);
            WATCH_CACHE.set(watchCacheKey, { at: Date.now(), result: result });
        }
        return sendApiData(response, 200, {
            source: source,
            episode: epNum,
            ...result
        });
    } catch (error) {
        return sendApiError(response, 502, error.message || "Watch resolution failed.");
    }
}

function rewriteHlsPlaylist(body, sourceUrl, ref) {
    const base = new URL(sourceUrl);
    const refParam = ref ? "&ref=" + encodeURIComponent(ref) : "";
    return body
        .split(/\r?\n/)
        .map(function(line) {
            const trimmed = line.trim();
            if (!trimmed) return line;
            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.includes("URI=")) {
                return line.replace(/URI="([^"]+)"/, function(match, uri) {
                    const absolute = new URL(uri, base).toString();
                    return (
                        'URI="/api/anivault/proxy/m3u8?url=' +
                        encodeURIComponent(absolute) +
                        refParam +
                        '"'
                    );
                });
            }
            if (trimmed.startsWith("#")) return line;
            const absolute = new URL(trimmed, base).toString();
            return (
                "/api/anivault/proxy/m3u8?url=" +
                encodeURIComponent(absolute) +
                refParam
            );
        })
        .join("\n");
}

async function handleProxyM3u8(request, response, requestUrl) {
    const url = String(requestUrl.searchParams.get("url") || "").trim();
    if (!url || !/^https?:\/\//i.test(url)) {
        return sendApiError(response, 400, "url must be absolute http(s).");
    }
    const ref = String(requestUrl.searchParams.get("ref") || "").trim();
    const headers = { "User-Agent": BROWSER_UA, Accept: "*/*" };
    if (ref && /^https?:\/\//i.test(ref)) {
        headers.Referer = ref;
        headers.Origin = new URL(ref).origin;
    }
    try {
        const controller = new AbortController();
        const timer = setTimeout(function() { controller.abort(); }, REQUEST_TIMEOUT_MS);
        const res = await fetch(url, {
            headers: headers,
            signal: controller.signal
        });
        const buf = Buffer.from(await res.arrayBuffer());
        clearTimeout(timer);
        if (!res.ok) {
            return sendJson(response, res.status, { error: "Upstream " + res.status });
        }
        const contentType = String(res.headers.get("content-type") || "");
        const isPlaylist =
            url.includes(".m3u8") ||
            url.includes(".m3u") ||
            contentType.includes("mpegurl");
        response.writeHead(200, Object.assign({
                "content-type": isPlaylist ?
                    "application/vnd.apple.mpegurl" : "application/octet-stream",
                "cache-control": "public, max-age=30"
            },
            corsHeaders()
        ));
        if (isPlaylist) {
            const text = buf.toString("utf8");
            if (text.trim().indexOf("#EXTM3U") !== 0) {
                response.end(
                    JSON.stringify({ error: "Not a valid m3u8", body: text.slice(0, 300) })
                );
                return;
            }
            response.end(rewriteHlsPlaylist(text, url, ref));
        } else {
            response.end(buf);
        }
    } catch (error) {
        return sendApiError(response, 502, error.message || "M3U8 proxy failed.");
    }
}

async function handleProxyStream(request, response, requestUrl) {
    const url = String(requestUrl.searchParams.get("url") || "").trim();
    if (!url || !/^https?:\/\//i.test(url)) {
        return sendApiError(response, 400, "url must be absolute http(s).");
    }
    const ref = String(requestUrl.searchParams.get("ref") || "").trim();
    const origin = String(requestUrl.searchParams.get("origin") || "").trim();
    const headers = { "User-Agent": BROWSER_UA, Accept: "*/*" };
    if (ref && /^https?:\/\//i.test(ref)) headers.Referer = ref;
    if (origin && /^https?:\/\//i.test(origin)) headers.Origin = origin;
    else if (ref && /^https?:\/\//i.test(ref)) headers.Origin = new URL(ref).origin;
    const range = request.headers.range;
    if (range) headers.Range = range;

    try {
        const controller = new AbortController();
        const timer = setTimeout(function() { controller.abort(); }, 30 * 1000);
        const res = await fetch(url, {
            headers: headers,
            signal: controller.signal,
            redirect: "follow"
        });
        clearTimeout(timer);
        if (!res.ok && res.status !== 206) {
            return sendJson(response, res.status, { error: "Upstream " + res.status });
        }
        const contentType = res.headers.get("content-type") || "application/octet-stream";
        const contentLength = res.headers.get("content-length");
        const contentRange = res.headers.get("content-range");
        const acceptRanges = res.headers.get("accept-ranges") || "bytes";

        const head = Object.assign({
                "content-type": contentType,
                "cache-control": "public, max-age=3600",
                "accept-ranges": acceptRanges
            },
            corsHeaders()
        );
        if (contentLength) head["content-length"] = contentLength;
        if (contentRange) head["content-range"] = contentRange;
        response.writeHead(res.status, head);

        const reader = res.body.getReader();
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
        return sendApiError(response, 502, error.message || "Stream proxy failed.");
    }
}

async function handleAnivault(request, response, pathname, requestUrl) {
    try {
        if (pathname === "/api/anivault/search") {
            return await handleSearch(request, response, requestUrl);
        }
        if (pathname === "/api/anivault/episodes") {
            return await handleEpisodes(request, response, requestUrl);
        }
        if (pathname === "/api/anivault/watch") {
            return await handleWatch(request, response, requestUrl);
        }
        if (pathname === "/api/anivault/proxy/m3u8") {
            return await handleProxyM3u8(request, response, requestUrl);
        }
        if (pathname === "/api/anivault/proxy/stream") {
            return await handleProxyStream(request, response, requestUrl);
        }
        return sendApiError(response, 404, "Anivault route not found.");
    } catch (error) {
        return sendApiError(
            response,
            500,
            (error && error.message) || "Anivault request failed."
        );
    }
}

module.exports = { handleAnivault };