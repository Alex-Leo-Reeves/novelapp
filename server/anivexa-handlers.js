// ─────────────────────────────────────────────────────────────────────────────
//  Anivexa Anime API Bridge
//
//  Mounts the Anivexa-API worker (githubanime/Anivexa-API -> server/anivexa)
//  in-process behind the app's own backend so the mobile/TV apps only ever
//  talk to https://novelapp1.onrender.com/api/anivexa/*.
//
//  The Anivexa worker is pure ESM and uses Node 18+ global fetch; the app
//  server is CommonJS, so we lazy `import()` it (Node >=20 supports dynamic
//  import of ESM from CJS) and dispatch a synthetic Request to it.
//
//  This module is fully self-contained: all response helpers are defined
//  locally so it does not depend on globals from server/index.js.
//
//  Routes exposed:
//    GET /api/anivexa/episodes/{provider}/{anilistId}   -> { provider, sub[], dub[] }
//    GET /api/anivexa/watch/{provider}/{id}/{audio}/{provider}-{ep}
//                                                       -> { streams: [...] }
//    GET /api/anivexa/embed/{anilistId}?ep={n}          -> { tmdbId, type, season, episode }
//    GET /api/anivexa/search?q={title}                  -> { anilistId, title }
//    GET /api/anivexa/map/{anilistId}                   -> { mappings } (AniList -> TMDB/AniDB/TVDb id map)
//    GET /api/anivexa/proxy?url=&ref=&ua=               -> HLS proxy (spoofed Referer, playlists rewritten)
//
//  Anivexa providers (13): mkissa, reanime, anikoto, animegg, anineko,
//  anidbapp, 2dhive, animenosub, anizone, anibd, senshi, kaa, animedunya.
// ─────────────────────────────────────────────────────────────────────────────

const path = require("path");
const { pathToFileURL } = require("url");

const ANIVEXA_WORKER_PATH = path.join(__dirname, "anivexa", "index.js");
const ANIVEXA_UPSTREAM_ORIGIN = "http://anivexa.local";
const FORWARD_TIMEOUT_MS = 55 * 1000;
const EMBED_CACHE_TTL_MS = 5 * 60 * 1000;
const SEARCH_CACHE_TTL_MS = 10 * 60 * 1000;

// ── Self-contained response helpers (mirror server/index.js shapes) ─────────
function corsHeaders() {
    return {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS",
        "access-control-allow-headers": "Content-Type, Authorization, Accept, X-Requested-With",
        "access-control-max-age": "86400"
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
    sendJson(response, statusCode, { ok: statusCode >= 200 && statusCode < 300, data, error: null });
}

function sendApiError(response, statusCode, message) {
    sendJson(response, statusCode, { ok: false, data: null, error: message });
}

// ── Worker lazy-loader ───────────────────────────────────────────────────────
let anivexaWorkerPromise = null;

/** Lazy-load the Anivexa ESM worker once; null + retried if the import fails. */
function getAnivexaWorker() {
    if (!anivexaWorkerPromise) {
        anivexaWorkerPromise =
            import (pathToFileURL(ANIVEXA_WORKER_PATH).href)
            .then((mod) => mod.default || mod)
            .catch((error) => {
                console.warn("[anivexa] Failed to load worker:", error.message || error);
                anivexaWorkerPromise = null; // allow retry on next request
                return null;
            });
    }
    return anivexaWorkerPromise;
}

/** Forward a request to the Anivexa worker with a hard timeout. Returns parsed JSON or null. */
async function forwardWorker(fullPath, urlSearch) {
    const worker = await getAnivexaWorker();
    if (!worker || typeof worker.fetch !== "function") return null;

    const query = urlSearch || "";
    const upstreamUrl = ANIVEXA_UPSTREAM_ORIGIN + fullPath + query;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), FORWARD_TIMEOUT_MS);
    try {
        const response = await worker.fetch(new Request(upstreamUrl, { signal: controller.signal }), {});
        const text = await response.text();
        let payload = null;
        try {
            payload = text ? JSON.parse(text) : null;
        } catch (err) {
            return { ok: response.ok, status: response.status, raw: text };
        }
        return { ok: response.ok, status: response.status, payload };
    } catch (error) {
        console.warn("[anivexa] Worker fetch failed for", fullPath, ":", error.message || error);
        return { ok: false, status: 500, payload: null, errorText: error.message || "Anivexa request failed." };
    } finally {
        clearTimeout(timeout);
    }
}

// ── Tiny in-memory TTL caches (Anivexa's own cache is disabled) ─────────────
const mapCache = new Map(); // anilistId -> { tmdbId, type, season, cachedAt }
const mapPayloadCache = new Map(); // anilistId -> { payload, cachedAt } (raw worker /map payload)
const searchCache = new Map(); // normalized title -> { anilistId, title, cachedAt }

function freshFrom(cache, key, ttlMs) {
    const entry = cache.get(key);
    return entry && Date.now() - entry.cachedAt < ttlMs ? entry : null;
}

function safeArray(value) {
    return Array.isArray(value) ? value : [];
}

/** /api/anivexa/episodes/{provider}/{anilistId} — normalized sub/dub lists. */
async function handleAnivexaEpisodes(response, pathname) {
    const rest = pathname.replace("/api/anivexa/episodes/", "");
    const segments = rest.split("/").filter(Boolean);
    if (segments.length < 2) return sendApiError(response, 400, "Expected /episodes/{provider}/{anilistId}.");
    const provider = segments[0];
    const anilistId = segments[1];
    if (!/^\d+$/.test(anilistId)) return sendApiError(response, 400, "anilistId must be numeric.");

    const result = await forwardWorker(`/episodes/${provider}/${anilistId}`, "?map=true");
    if (!result) return sendApiError(response, 503, "Anivexa anime API is unavailable right now.");
    if (!result.ok || !result.payload || result.payload.error) {
        const msg = (result.payload && result.payload.error) || result.errorText || "Anivexa episodes lookup failed.";
        return sendApiError(response, result.status >= 400 ? result.status : 502, msg);
    }

    const providerBlock = result.payload[provider];
    if (!providerBlock) return sendApiError(response, 404, "Provider '" + provider + "' returned no data.");
    const episodes = providerBlock.episodes || {};
    return sendApiData(response, 200, {
        provider: provider,
        meta: providerBlock.meta || null,
        mappings: result.payload.mappings || null,
        sub: safeArray(episodes.sub),
        dub: safeArray(episodes.dub)
    });
}

/** /api/anivexa/watch/{provider}/{anilistId}/{audio}/{provider}-{ep} — stream list. */
async function handleAnivexaWatch(response, pathname) {
    const rest = pathname.replace("/api/anivexa/watch/", "");
    const segments = rest.split("/").filter(Boolean);
    if (segments.length < 4) {
        return sendApiError(response, 400, "Expected /watch/{provider}/{anilistId}/{audio}/{provider}-{ep}.");
    }
    const [provider, anilistId, audio, episodeToken] = segments;
    if (!/^\d+$/.test(anilistId)) return sendApiError(response, 400, "anilistId must be numeric.");
    if (!provider || !audio || !episodeToken) return sendApiError(response, 400, "Malformed watch path.");

    const result = await forwardWorker(`/watch/${provider}/${anilistId}/${audio}/${episodeToken}`);
    if (!result) return sendApiError(response, 503, "Anivexa anime API is unavailable right now.");
    if (!result.ok || !result.payload || result.payload.error) {
        const msg = (result.payload && result.payload.error) || result.errorText || "Anivexa watch lookup failed.";
        return sendApiError(response, result.status >= 400 ? result.status : 502, msg);
    }
    return sendApiData(response, 200, {
        anilistId: result.payload.anilistId,
        episode: result.payload.episode,
        providerEpisode: result.payload.providerEpisode,
        audio: result.payload.audio,
        streams: safeArray(result.payload.streams)
    });
}

/** /api/anivexa/embed/{anilistId}?ep={n} — build a VidLink-compatible embed reference. */
async function handleAnivexaEmbed(response, pathname, requestUrl) {
    const anilistId = pathname.replace("/api/anivexa/embed/", "").split("/")[0];
    if (!/^\d+$/.test(anilistId)) return sendApiError(response, 400, "anilistId must be numeric.");
    const episode = Math.max(1, Number(requestUrl.searchParams.get("ep") || 1) || 1);

    const cacheKey = "map:" + anilistId;
    const cached = freshFrom(mapCache, cacheKey, EMBED_CACHE_TTL_MS);
    if (cached) {
        return sendApiData(response, 200, {
            tmdbId: cached.tmdbId,
            type: cached.type,
            season: cached.season,
            episode: episode
        });
    }

    const result = await forwardWorker(`/map/${anilistId}`);
    if (!result) return sendApiError(response, 503, "Anivexa anime API is unavailable right now.");
    if (!result.ok || !result.payload || !result.payload.mappings) {
        const msg = (result.payload && result.payload.error) || "AniList to TMDB mapping not found.";
        return sendApiError(response, result.status >= 400 ? result.status : 502, msg);
    }

    const mappings = result.payload.mappings || {};
    const tmdbId = String(mappings.themoviedbId || mappings.tmdbId || "").trim();
    if (!tmdbId || !/^\d+$/.test(tmdbId)) return sendApiError(response, 404, "No TMDB id mapping exists for this anime.");

    const format = String(mappings.format || "").toUpperCase();
    const hasSeason = Boolean(mappings.tmdbSeason || mappings.defaultTvdbSeason);
    const isMovie = format === "MOVIE" || (format === "SPECIAL" && !hasSeason);
    const season = String(mappings.tmdbSeason || mappings.defaultTvdbSeason || "1").trim();

    mapCache.set(cacheKey, {
        tmdbId: tmdbId,
        type: isMovie ? "movie" : "tv",
        season: season,
        cachedAt: Date.now()
    });
    return sendApiData(response, 200, {
        tmdbId: tmdbId,
        type: isMovie ? "movie" : "tv",
        season: season,
        episode: episode
    });
}

/**
 * /api/anivexa/map/{anilistId} — passthrough of the worker's raw AniList ->
 * TMDB/AniDB/TVDb id mapping payload.
 *
 * AnivexaApi.resolveTmdbIdForAnilist() calls GET {base}/map/{anilistId} and
 * reads data.mappings.themoviedbId to VERIFY a bridged title actually maps to
 * the expected TMDB show before keying the 13 Anivexa providers off it. The
 * embedded nodebridge implements /map, but the backend handler only exposed
 * episodes/watch/embed/search — so on the Render fallback path /map 404'd,
 * resolveTmdbIdForAnilist returned null, and every TMDB-sourced anime
 * (Dragon Ball Z included) silently lost its AniList id → 0 episodes on the
 * 13 Anivexa servers. This route mirrors the bridge's normalizer exactly.
 */
async function handleAnivexaMap(response, pathname) {
    const anilistId = pathname.replace("/api/anivexa/map/", "").split("/")[0];
    if (!/^\d+$/.test(anilistId)) return sendApiError(response, 400, "anilistId must be numeric.");

    const cacheKey = "rawmap:" + anilistId;
    const cached = freshFrom(mapPayloadCache, cacheKey, EMBED_CACHE_TTL_MS);
    if (cached) return sendApiData(response, 200, cached.payload);

    const result = await forwardWorker(`/map/${anilistId}`);
    if (!result) return sendApiError(response, 503, "Anivexa anime API is unavailable right now.");
    if (!result.ok || !result.payload || result.payload.error) {
        const msg = (result.payload && result.payload.error) || result.errorText || "Anivexa map lookup failed.";
        return sendApiError(response, result.status >= 400 ? result.status : 502, msg);
    }

    // Cache the FULL payload so repeat /map + /embed calls share one worker hit.
    mapPayloadCache.set(cacheKey, { payload: result.payload, cachedAt: Date.now() });
    return sendApiData(response, 200, result.payload);
}

/**
 * /api/anivexa/proxy?url={encoded}&ref={referer}&ua={ua} — HLS proxy.
 *
 * Mirrors the technique AniVault (api.anivault.co/api/proxy/hls) and the
 * Anivexa reference site (khankirpola...workers.dev/p/) use: fetch the
 * upstream playlist/segment with the provider-required Referer (e.g. anikoto's
 * kryntal CDN demands `Referer: https://megaplay.buzz/` — verified live: no
 * Referer → 403 Cloudflare block), then rewrite every nested playlist URL so
 * variant playlists and segments keep flowing through this proxy. Used as the
 * playback fallback when the app's direct-with-headers fetch is rejected.
 */
const PROXY_TIMEOUT_MS = 55 * 1000;
const PROXY_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

function proxyTargetBlocked(target) {
    try {
        const u = new URL(target);
        if (u.protocol !== "http:" && u.protocol !== "https:") return true;
        const host = u.hostname.toLowerCase();
        if (host === "localhost" || host.endsWith(".local") || host === "::1" || host === "[::1]") return true;
        if (/^127\.|^10\.|^0\.|^169\.254\.|^192\.168\.|^172\.(1[6-9]|2\d|3[01])\./.test(host)) return true;
        return false;
    } catch {
        return true;
    }
}

function rewriteHlsToProxy(body, baseUrl, referer, userAgent) {
    const proxied = (targetUrl) =>
        "/api/anivexa/proxy?url=" + encodeURIComponent(targetUrl) +
        "&ref=" + encodeURIComponent(referer || "") +
        "&ua=" + encodeURIComponent(userAgent || "");
    return body.split("\n").map((line) => {
        const trimmed = line.trim();
        if (!trimmed) return line;
        if (trimmed.startsWith("#")) {
            // Rewrite URI="..." attributes (EXT-X-KEY / EXT-X-MAP / EXT-X-MEDIA ...)
            return line.replace(/URI="([^"]+)"/g, (match, uri) => {
                try {
                    return 'URI="' + proxied(new URL(uri, baseUrl).toString()) + '"';
                } catch {
                    return match;
                }
            });
        }
        try {
            return proxied(new URL(trimmed, baseUrl).toString());
        } catch {
            return line;
        }
    }).join("\n");
}

async function handleAnivexaProxy(response, requestUrl) {
    const target = String(requestUrl.searchParams.get("url") || "").trim();
    const ref = String(requestUrl.searchParams.get("ref") || "").trim();
    const ua = String(requestUrl.searchParams.get("ua") || "").trim() || PROXY_UA;
    if (!target || proxyTargetBlocked(target)) {
        return sendApiError(response, 400, "A valid http(s) url parameter is required.");
    }
    const referer = ref || new URL(target).origin + "/";

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), PROXY_TIMEOUT_MS);
    let upstream;
    try {
        upstream = await fetch(target, {
            headers: {
                "User-Agent": ua,
                "Referer": referer,
                "Origin": referer.replace(/\/$/, ""),
                "Accept": "*/*"
            },
            signal: controller.signal,
            redirect: "follow"
        });
    } catch (error) {
        clearTimeout(timeout);
        return sendApiError(response, 502, "Upstream fetch failed: " + (error.message || "unknown"));
    }
    clearTimeout(timeout);

    const contentType = upstream.headers.get("content-type") || "";
    const isPlaylist = target.split("?")[0].toLowerCase().endsWith(".m3u8") ||
        contentType.includes("mpegurl") || contentType.includes("m3u");
    const cors = corsHeaders();

    if (isPlaylist) {
        const text = await upstream.text();
        const rewritten = rewriteHlsToProxy(text, target, referer, ua);
        response.writeHead(200, {
            "content-type": "application/vnd.apple.mpegurl",
            "cache-control": "no-store",
            ...cors
        });
        return response.end(rewritten);
    }

    // Segments / binary payloads: stream straight through.
    response.writeHead(upstream.status, {
        "content-type": contentType || "application/octet-stream",
        "cache-control": "no-store",
        ...cors
    });
    if (!upstream.body) return response.end();
    const { Readable } = require("stream");
    const nodeStream = Readable.fromWeb(upstream.body);
    nodeStream.pipe(response);
    nodeStream.on("error", () => {
        try { response.end(); } catch (e) { /* already closed */ }
    });
}

/**
 * Rank AniList candidates against the queried title and pick the strongest,
 * well-matched one. Returns null when NO candidate is a good match.
 *
 * This is the fix for "some contents showing the WRONG episodes": the old
 * handler took AniList's first SEARCH_MATCH hit with zero validation. For
 * sequels ("Bleach: Thousand-Year Blood War"), romanized titles, or broad
 * names, that first hit is a DIFFERENT show — which then mis-keyed all 13
 * Anivexa providers. We now fetch the top candidates and require a real
 * normalized-title match before returning an id.
 */
function pickBestAnilistCandidate(query, mediaList) {
    const normalized = (value) => String(value || "")
        .toLowerCase()
        .replace("&", "and")
        .replace(/[^a-z0-9]+/g, " ")
        .trim()
        .replace(/\s+/g, " ");
    const q = normalized(query);
    if (!q) return null;

    // Stopwords dropped from token coverage so "battle of the gods" and
    // "battle of gods" score identically.
    const STOP_WORDS = new Set(["the", "a", "an", "of", "no", "wa"]);
    const qTokens = q.split(" ").filter((w) => w && !STOP_WORDS.has(w));
    const minimumTokenHits = Math.min(2, qTokens.length);

    const candidates = (mediaList || []).map((media) => {
            const titleObj = (media && media.title) || {};
            const rawTitles = [titleObj.english, titleObj.romaji]
                .map((v) => String(v || "").trim())
                .filter(Boolean);
            if (!rawTitles.length) return null;

            // Sequel/special penalty keeps chibi spin-offs and numbered seasons
            // from winning. NOTE: "movie" is deliberately NOT penalized — when
            // the user asks for a film, the film must be allowed to win.
            const penalty = 900;
            let bestScore = 0;
            let bestTitle = rawTitles[0];
            for (const raw of rawTitles) {
                const t = normalized(raw);
                if (!t) continue;
                const sequelly = /(\bseason\s+[2-9]\b|\b\d+(st|nd|rd|th)\s+season\b|\bcour\s+[2-9]\b|\b(part|special|ova|ona|recap)\b)/.test(t) ? penalty : 0;
                let score;
                if (t === q) score = 10000;
                else if (t.startsWith(q + " ") || q.startsWith(t + " ")) score = 8000 - sequelly;
                else if (t.includes(q) || q.includes(t)) score = 5000 - sequelly;
                else {
                    // Token-overlap fallback. Pure substring matching fails when
                    // the query drops or adds tokens the title has —
                    // "dragon ball battle of gods" vs the actual
                    // "Dragon Ball Z: Battle of Gods" (the stray "z" breaks
                    // includes()), which made the title bridge return null and
                    // pushed movies to the TMDB/vidlink fallback with the wrong
                    // episode list. Require most of the query's meaningful
                    // tokens to appear in the title so unrelated shows
                    // (e.g. anything merely containing "gods") still lose.
                    const tTokens = t.split(" ").filter(Boolean);
                    const hits = qTokens.filter((w) => tTokens.includes(w)).length;
                    const coverage = qTokens.length ? hits / qTokens.length : 0;
                    score = hits >= minimumTokenHits && coverage >= 0.6
                        ? Math.round(2200 * coverage) - sequelly
                        : 0;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestTitle = raw;
                }
            }
            if (bestScore <= 0) return null;
            return { media, title: bestTitle, score: bestScore };
        })
        .filter(Boolean)
        .sort((a, b) => {
            if (b.score !== a.score) return b.score - a.score;
            // Tiebreaks when titles score identically: prefer a full TV series,
            // then higher AniList popularity, then more episodes — so shorts,
            // chibi spin-offs and specials lose to the main show.
            const af = String((a.media && a.media.format) || "").toUpperCase();
            const bf = String((b.media && b.media.format) || "").toUpperCase();
            const aTv = af === "TV" ? 2 : af === "TV_SHORT" ? 1 : 0;
            const bTv = bf === "TV" ? 2 : bf === "TV_SHORT" ? 1 : 0;
            if (aTv !== bTv) return bTv - aTv;
            const ap = Number((a.media && a.media.popularity) || 0);
            const bp = Number((b.media && b.media.popularity) || 0);
            if (bp !== ap) return bp - ap;
            const ae = Number((a.media && a.media.episodes) || 0);
            const be = Number((b.media && b.media.episodes) || 0);
            return be - ae;
        });

    const best = candidates[0];
    if (!best) return null;
    return best;
}

/** /api/anivexa/search?q={title} — AniList GraphQL search -> validated ANIME id. */
async function handleAnivexaSearch(response, requestUrl) {
    const query = String(requestUrl.searchParams.get("q") || "").trim();
    if (!query) return sendApiError(response, 400, "Anime title query is required.");
    // Strip qualifiers like "(TV)", "(Dub)", "(Sub)" and trailing years so the
    // search finds the base show instead of failing on the qualifier.
    const cleanQuery = query
        .replace(/\s*\((TV|Dub|Sub|Dubs|Subs|Movie|Film)\)/gi, "")
        .replace(/\s*\(\d{4}\)$/, "")
        .trim();
    if (!cleanQuery) return sendApiError(response, 400, "Anime title query is required.");
    const baseKey = cleanQuery.toLowerCase();
    const searchKey = "q:" + baseKey;

    const cached = freshFrom(searchCache, searchKey, SEARCH_CACHE_TTL_MS);
    if (cached) return sendApiData(response, 200, { anilistId: cached.anilistId, title: cached.title });

    try {
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 12000);
        const res = await fetch("https://graphql.anilist.co", {
            method: "POST",
            headers: { "content-type": "application/json", accept: "application/json" },
            body: JSON.stringify({
                query: "query ($search: String) { Page(page: 1, perPage: 12) { media(search: $search, type: ANIME, sort: SEARCH_MATCH) { id title { romaji english } format episodes popularity } } }",
                variables: { search: cleanQuery }
            }),
            signal: controller.signal
        });
        clearTimeout(timeout);
        if (!res.ok) return sendApiError(response, 502, "AniList search failed (" + res.status + ").");
        const payload = await res.json();
        const page = payload && payload.data && payload.data.Page;
        const mediaList = page && Array.isArray(page.media) ? page.media : [];

        const best = pickBestAnilistCandidate(cleanQuery, mediaList);
        if (!best) {
            // No candidate matched well enough — return null so the app falls
            // back to AnimeXin / TMDB instead of keying the 13 providers to the
            // wrong show's episodes.
            searchCache.set(searchKey, { anilistId: null, title: query, cachedAt: Date.now() });
            return sendApiData(response, 200, { anilistId: null, title: "" });
        }

        const title = best.title;
        searchCache.set(searchKey, {
            anilistId: String(best.media.id),
            title: title,
            cachedAt: Date.now()
        });
        return sendApiData(response, 200, { anilistId: String(best.media.id), title: title });
    } catch (error) {
        console.warn("[anivexa] Search failed:", error.message || error);
        return sendApiError(response, 502, error.message || "AniList search failed.");
    }
}

/** Root dispatcher mounted at /api/anivexa/* */
async function handleAnivexa(request, response, pathname, requestUrl) {
    try {
        if (pathname.startsWith("/api/anivexa/episodes/")) {
            return await handleAnivexaEpisodes(response, pathname);
        }
        if (pathname.startsWith("/api/anivexa/watch/")) {
            return await handleAnivexaWatch(response, pathname);
        }
        if (pathname.startsWith("/api/anivexa/embed/")) {
            return await handleAnivexaEmbed(response, pathname, requestUrl);
        }
        if (pathname.startsWith("/api/anivexa/map/")) {
            return await handleAnivexaMap(response, pathname);
        }
        if (pathname === "/api/anivexa/search") {
            return await handleAnivexaSearch(response, requestUrl);
        }
        if (pathname === "/api/anivexa/proxy" || pathname.startsWith("/api/anivexa/proxy/")) {
            return await handleAnivexaProxy(response, requestUrl);
        }
        return sendApiError(response, 404, "Anivexa route not found.");
    } catch (error) {
        console.error("[anivexa] Handler error:", error.stack || error.message || error);
        return sendApiError(response, 500, error.message || "Anivexa request failed.");
    }
}

module.exports = { handleAnivexa };