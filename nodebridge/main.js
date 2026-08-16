// nodejs-mobile embedded worker bridge for the Anivexa-API (13 providers).
//
// This runs INSIDE the app on the device's residential IP — exactly like the
// repo owner's site whose browser scrapes from the user's own network. The
// provider sites (anineko, animegg, anikoto, senshi, mkissa, ...) block
// datacenter egress (Render/Vercel -> streams=0) but allow residential IPs,
// which is why the same worker plays Dragon Ball from the browser but not
// from our backend.
//
// The bridge starts an HTTP server on 127.0.0.1 with an ephemeral port, writes
// { "port": N } to bridge-port.json next to this script (Kotlin reads it from
// filesDir), then forwards every request to the EXACT unmodified Anivexa
// worker (worker.fetch(Request) -> Response).
//
// NORMALIZATION LAYER (v2): the raw worker speaks its own response shapes —
//   /episodes returns { page, type, mappings, <provider>: { episodes: { sub, dub }, meta } }
//   /watch      returns { anilistId, episode, providerEpisode, audio, streams }
// but the app client (AnivexaApi.kt) expects the SAME { ok, data, error }
// envelope that the app backend (server/anivexa-handlers.js) produces. Without
// this layer the embedded path resolved streams fine but AnivexaApi parsed
// empty results whenever embeddedBaseUrl was active. The bridge now re-shapes
// worker responses into the app contract so BOTH paths are interchangeable.
//
// Route handling:
//   /search?q={title}          -> AniList GraphQL search with validated candidate
//                                  (ported from server/anivexa-handlers.js), not in the worker
//   /embed/{anilistId}?ep={n}  -> AniList->TMDB mapping -> { tmdbId, type, season, episode }
//                                  (mirrors server/anivexa-handlers.js handleAnivexaEmbed)
//   /map/{anilistId}           -> wrapped { ok, data }
//   /episodes/...              -> wrapped { ok, data: { provider, meta, mappings, sub, dub } }
//   /watch/...                 -> wrapped { ok, data }
//   everything else            -> raw passthrough (unchanged behavior)
//
// NOTE: written without ?? / ?. on purpose — the editor formatter mangles
// those tokens into invalid JS.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import anivexaWorker from "./anivexa/index.js";

const __dirname = path.dirname(fileURLToPath(
    import.meta.url));
const PORT_FILE = path.join(__dirname, "bridge-port.json");
const HOST = "127.0.0.1";
const ANILIST_GRAPHQL = "https://graphql.anilist.co";
const EMBED_CACHE_TTL_MS = 5 * 60 * 1000;

function toNumber(value) {
    const n = Number(value);
    return Number.isFinite(n) ? n : 0;
}

function ok(data) {
    return { ok: true, data: data, error: null };
}

function fail(message) {
    return { ok: false, data: null, error: message };
}

function safeArray(value) {
    return Array.isArray(value) ? value : [];
}

// ── AniList search (mirrors server/anivexa-handlers.js) ──────────────────────
function normalizeCandidateTitle(value) {
    return String(value || "")
        .toLowerCase()
        .replace("&", "and")
        .replace(/[^a-z0-9]+/g, " ")
        .trim()
        .replace(/\s+/g, " ");
}

function pickBestAnilistCandidate(query, mediaList) {
    const q = normalizeCandidateTitle(query);
    if (!q) return null;

    const candidates = (mediaList || []).map(function(media) {
            const titleObject = (media && media.title) || {};
            const raw = titleObject.english || titleObject.romaji || "";
            const title = String(raw || "").trim();
            if (!title) return null;
            const t = normalizeCandidateTitle(title);
            if (!t) return null;

            const sequelPenalty = /(\bseason\s+[2-9]\b|\b\d+(st|nd|rd|th)\s+season\b|\bcour\s+[2-9]\b|\b(part|movie|special|ova|ona|recap)\b)/.test(t) ? 900 : 0;
            const score = t === q ? 10000 : t.startsWith(q + " ") ? 8000 - sequelPenalty : t.includes(q) ? 5000 - sequelPenalty : 0;
            return { media: media, title: title, score: score };
        })
        .filter(function(item) { return item && item.score > 0; })
        .sort(function(a, b) { return b.score - a.score; });

    return candidates[0] || null;
}

async function anilistSearch(query) {
    const controller = new AbortController();
    const timeout = setTimeout(function() { controller.abort(); }, 12000);
    try {
        const res = await fetch(ANILIST_GRAPHQL, {
            method: "POST",
            headers: { "content-type": "application/json", accept: "application/json" },
            body: JSON.stringify({
                query: "query ($search: String) { Page(page: 1, perPage: 12) { media(search: $search, type: ANIME, sort: SEARCH_MATCH) { id title { romaji english } } } }",
                variables: { search: query }
            }),
            signal: controller.signal
        });
        if (!res.ok) return fail("AniList search failed (" + res.status + ").");
        const payload = await res.json();
        const page = payload && payload.data && payload.data.Page;
        const mediaList = page && Array.isArray(page.media) ? page.media : [];
        const best = pickBestAnilistCandidate(query, mediaList);
        if (!best) return ok({ anilistId: null, title: "" });
        return ok({ anilistId: String(best.media.id), title: best.title });
    } catch (error) {
        return fail(error && error.message ? error.message : "AniList search failed.");
    } finally {
        clearTimeout(timeout);
    }
}

// ── Worker forwarding helpers ────────────────────────────────────────────────
function loopbackUrl(req, pathname, search) {
    const hostHeader = req.headers.host || (HOST + ":80");
    return new URL(pathname + (search || ""), "http://" + hostHeader);
}

async function workerFetch(url, req) {
    const headers = {};
    for (let i = 0; i < req.rawHeaders.length; i += 2) {
        const key = req.rawHeaders[i];
        const value = req.rawHeaders[i + 1];
        headers[key] = headers[key] ? headers[key] + ", " + value : value;
    }
    const init = {
        method: req.method || "GET",
        headers: headers,
        duplex: "half"
    };
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    if (chunks.length) init.body = new Uint8Array(Buffer.concat(chunks));

    const request = new Request(url.toString(), init);
    return anivexaWorker.fetch(request, {});
}

async function workerJson(url, req) {
    const response = await workerFetch(url, req);
    const text = await response.text();
    let payload = null;
    try {
        payload = text ? JSON.parse(text) : null;
    } catch (ignore) {
        return { payload: null, status: response.status, raw: text };
    }
    return { payload: payload, status: response.status };
}

function writeJson(res, statusCode, payload) {
    const body = JSON.stringify(payload);
    res.writeHead(statusCode, {
        "Content-Type": "application/json; charset=utf-8",
        "Cache-Control": "no-store",
        "Access-Control-Allow-Origin": "*"
    });
    res.end(body);
}

// /embed/{anilistId}?ep={n} — build a VidLink-compatible embed reference from
// the worker's /map payload. Mirrors server/anivexa-handlers.js handleAnivexaEmbed.
const embedCache = new Map(); // anilistId -> { tmdbId, type, season, cachedAt }

async function handleEmbed(req, res, reqUrl, anilistId) {
    const episode = Math.max(1, Number(reqUrl.searchParams.get("ep") || 1) || 1);
    const cached = embedCache.get(anilistId);
    if (cached && Date.now() - cached.cachedAt < EMBED_CACHE_TTL_MS) {
        return writeJson(res, 200, ok({
            tmdbId: cached.tmdbId,
            type: cached.type,
            season: cached.season,
            episode: episode
        }));
    }

    let result;
    try {
        result = await workerJson(loopbackUrl(req, "/map/" + anilistId, ""), req);
    } catch (error) {
        return writeJson(res, 502, fail(error && error.message ? error.message : "Embed lookup failed."));
    }
    const payload = result.payload;
    if (!payload || !payload.mappings) {
        return writeJson(res, result.status >= 400 ? result.status : 502, fail((payload && payload.error) || "AniList to TMDB mapping not found."));
    }

    const mappings = payload.mappings || {};
    const tmdbId = String(mappings.themoviedbId || mappings.tmdbId || "").trim();
    if (!tmdbId || !/^\d+$/.test(tmdbId)) {
        return writeJson(res, 404, fail("No TMDB id mapping exists for this anime."));
    }

    const format = String(mappings.format || "").toUpperCase();
    const hasSeason = Boolean(mappings.tmdbSeason || mappings.defaultTvdbSeason);
    const isMovie = format === "MOVIE" || (format === "SPECIAL" && !hasSeason);
    const season = String(mappings.tmdbSeason || mappings.defaultTvdbSeason || "1").trim();

    embedCache.set(anilistId, {
        tmdbId: tmdbId,
        type: isMovie ? "movie" : "tv",
        season: season,
        cachedAt: Date.now()
    });
    return writeJson(res, 200, ok({
        tmdbId: tmdbId,
        type: isMovie ? "movie" : "tv",
        season: season,
        episode: episode
    }));
}

// /episodes/{provider}/{anilistId}?map=true — unwrap the provider block and
// normalize into { ok, data: { provider, meta, mappings, sub, dub } }.
async function handleEpisodes(req, res, reqUrl, pathname) {
    const rest = pathname.replace(/^\/episodes\//, "");
    const segments = rest.split("/").filter(Boolean);
    const provider = segments[0];
    const anilistId = segments[1];
    if (!provider || !/^\d+$/.test(anilistId || "")) {
        return writeJson(res, 400, fail("Expected /episodes/{provider}/{anilistId}."));
    }

    const query = reqUrl.searchParams.get("map") === "false" ? "" : "?map=true";
    let result;
    try {
        result = await workerJson(loopbackUrl(req, "/episodes/" + provider + "/" + anilistId, query), req);
    } catch (error) {
        return writeJson(res, 502, fail(error && error.message ? error.message : "Anivexa episodes lookup failed."));
    }
    const payload = result.payload;
    if (!payload) {
        return writeJson(res, result.status >= 400 ? result.status : 502, fail("Anivexa episodes lookup failed."));
    }
    if (payload.error) {
        return writeJson(res, result.status >= 400 ? result.status : 502, fail(payload.error));
    }

    const providerBlock = payload[provider];
    if (!providerBlock) {
        return writeJson(res, 404, fail("Provider '" + provider + "' returned no data."));
    }
    const episodes = providerBlock.episodes || {};
    return writeJson(res, 200, ok({
        provider: provider,
        meta: providerBlock.meta || null,
        mappings: payload.mappings || null,
        sub: safeArray(episodes.sub),
        dub: safeArray(episodes.dub)
    }));
}

// /watch/... — the worker already returns { anilistId, episode, providerEpisode,
// audio, streams }; wrap it so AnivexaApi.resolveStream parses it.
async function handleWatch(req, res, reqUrl, pathname) {
    let result;
    try {
        result = await workerJson(loopbackUrl(req, pathname, reqUrl.search), req);
    } catch (error) {
        return writeJson(res, 502, fail(error && error.message ? error.message : "Anivexa watch lookup failed."));
    }
    const payload = result.payload;
    if (!payload) {
        return writeJson(res, result.status >= 400 ? result.status : 502, fail("Anivexa watch lookup failed."));
    }
    if (payload.error) {
        return writeJson(res, result.status >= 400 ? result.status : 502, fail(payload.error));
    }
    return writeJson(res, 200, ok(payload));
}

// /map/{anilistId} — wrap the worker payload so the app's
// resolveTmdbIdForAnilist can read data.mappings (plus raw root readiness).
async function handleMap(req, res, reqUrl, pathname) {
    let result;
    try {
        result = await workerJson(loopbackUrl(req, pathname, reqUrl.search), req);
    } catch (error) {
        return writeJson(res, 502, fail(error && error.message ? error.message : "Anivexa map lookup failed."));
    }
    const payload = result.payload;
    if (!payload) {
        return writeJson(res, result.status >= 400 ? result.status : 502, fail("Anivexa map lookup failed."));
    }
    if (payload.error) {
        return writeJson(res, result.status >= 400 ? result.status : 502, fail(payload.error));
    }
    return writeJson(res, 200, ok(payload));
}

// ── HTTP server ──────────────────────────────────────────────────────────────
const server = http.createServer(function(req, res) {
    (async function() {
        try {
            const hostHeader = req.headers.host || (HOST + ":80");
            const reqUrl = new URL(req.url || "/", "http://" + hostHeader);
            const pathname = reqUrl.pathname;

            if (pathname === "/search") {
                const query = String(reqUrl.searchParams.get("q") || "").trim();
                if (!query) return writeJson(res, 400, fail("Anime title query is required."));
                return writeJson(res, 200, await anilistSearch(query));
            }

            let match = /^\/embed\/(\d+)\/?$/.exec(pathname);
            if (match) return await handleEmbed(req, res, reqUrl, match[1]);

            match = /^\/episodes\//.exec(pathname);
            if (match) return await handleEpisodes(req, res, reqUrl, pathname);

            match = /^\/watch\//.exec(pathname);
            if (match) return await handleWatch(req, res, reqUrl, pathname);

            match = /^\/map\//.exec(pathname);
            if (match) return await handleMap(req, res, reqUrl, pathname);

            // Default: raw passthrough for anything else (health, unknown routes).
            const workerRes = await workerFetch(
                loopbackUrl(req, pathname, reqUrl.search),
                req
            );
            const responseHeaders = {};
            workerRes.headers.forEach(function(value, key) {
                responseHeaders[key] = value;
            });
            res.writeHead(workerRes.status, responseHeaders);
            const buf = Buffer.from(await workerRes.arrayBuffer());
            res.end(buf);
        } catch (err) {
            const message = err && err.message ? err.message : String(err);
            writeJson(res, 500, fail(message));
        }
    })().catch(function(err) {
        const message = err && err.message ? err.message : String(err);
        res.writeHead(500, {
            "Content-Type": "application/json",
            "Access-Control-Allow-Origin": "*"
        });
        res.end(JSON.stringify({ ok: false, data: null, error: message }));
    });
});

server.listen(0, HOST, function() {
    const address = server.address();
    const port = address && typeof address === "object" ? toNumber(address.port) : 0;
    try {
        fs.writeFileSync(PORT_FILE, JSON.stringify({ port: port, host: HOST }));
    } catch (e) {
        console.error("[nodebridge] failed to write port file:", e && e.message);
    }
    console.log("[nodebridge] Anivexa worker listening on http://127.0.0.1:" + port);
});