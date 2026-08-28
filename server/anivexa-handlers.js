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

    const candidates = (mediaList || []).map((media) => {
            const titleObj = (media && media.title) || {};
            const raw = titleObj.english || titleObj.romaji || "";
            const title = String(raw || "").trim();
            if (!title) return null;
            const t = normalized(title);
            if (!t) return null;

            const sequelPenalty = /(\bseason\s+[2-9]\b|\b\d+(st|nd|rd|th)\s+season\b|\bcour\s+[2-9]\b|\b(part|movie|special|ova|ona|recap)\b)/.test(t) ? 900 : 0;
            const score = t === q ? 10000 : t.startsWith(q + " ") ? 8000 - sequelPenalty : t.includes(q) ? 5000 - sequelPenalty : 0;
            return { media, title, score };
        })
        .filter((item) => item && item.score > 0)
        .sort((a, b) => b.score - a.score);

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
                query: "query ($search: String) { Page(page: 1, perPage: 12) { media(search: $search, type: ANIME, sort: SEARCH_MATCH) { id title { romaji english } } } }",
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
        return sendApiError(response, 404, "Anivexa route not found.");
    } catch (error) {
        console.error("[anivexa] Handler error:", error.stack || error.message || error);
        return sendApiError(response, 500, error.message || "Anivexa request failed.");
    }
}

module.exports = { handleAnivexa };