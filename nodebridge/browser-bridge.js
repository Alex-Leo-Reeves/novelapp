// Browser-adapted bridge for the Anivexa worker running inside Headless Android WebView
import anivexaWorker from "./assets/nodebridge/anivexa/index.js";

const ANILIST_GRAPHQL = "https://graphql.anilist.co";
const EMBED_CACHE_TTL_MS = 5 * 60 * 1000;

function ok(data) {
    return { ok: true, data: data, error: null };
}

function fail(message) {
    return { ok: false, data: null, error: message };
}

function safeArray(value) {
    return Array.isArray(value) ? value : [];
}

// ── AniList search ─────────────────────────────────────────────────────────
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
        const format = String((media && media.format) || "").toUpperCase();
        let score = 0;
        if (t === q) score += 120;
        else if (t.startsWith(q)) score += 80;
        else if (t.includes(q)) score += 50;

        if (format === "TV" || format === "TV_SHORT") score += 20;
        else if (format === "MOVIE") score += 10;
        else if (format === "ONA" || format === "OVA") score += 10;

        const popularity = Number((media && media.popularity) || 0) || 0;
        score += Math.min(25, Math.floor(popularity / 10000));
        return { media: media, score: score };
    }).filter(Boolean);

    candidates.sort(function(a, b) { return b.score - a.score; });
    return candidates.length > 0 ? candidates[0].media : null;
}

async function anilistSearch(query) {
    const queryGql = `
        query ($search: String) {
            Page(page: 1, perPage: 10) {
                media(search: $search, type: ANIME, sort: SEARCH_MATCH) {
                    id
                    title { romaji english native }
                    format
                    status
                    episodes
                    popularity
                }
            }
        }
    `;
    try {
        const response = await fetch(ANILIST_GRAPHQL, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
                "Accept": "application/json",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            },
            body: JSON.stringify({ query: queryGql, variables: { search: query } })
        });
        if (!response.ok) {
            return fail("AniList search HTTP error: " + response.status);
        }
        const json = await response.json();
        const mediaList = json && json.data && json.data.Page && json.data.Page.media;
        const best = pickBestAnilistCandidate(query, mediaList);
        if (!best) return fail("No matching anime found on AniList for: " + query);

        const titleObject = best.title || {};
        const displayTitle = titleObject.english || titleObject.romaji || titleObject.native || query;
        return ok({
            anilistId: String(best.id),
            id: String(best.id),
            title: displayTitle,
            format: best.format || null,
            status: best.status || null,
            episodes: best.episodes || null
        });
    } catch (e) {
        return fail(e && e.message ? e.message : "AniList search failed");
    }
}

// ── Worker request dispatch ────────────────────────────────────────────────
async function workerFetch(pathname, search) {
    const url = "http://localhost" + pathname + (search || "");
    const request = new Request(url, { method: "GET" });
    return anivexaWorker.fetch(request, {});
}

async function workerJson(pathname, search) {
    const response = await workerFetch(pathname, search);
    const text = await response.text();
    let payload = null;
    try {
        payload = text ? JSON.parse(text) : null;
    } catch (ignore) {
        return { payload: null, status: response.status, raw: text };
    }
    return { payload: payload, status: response.status };
}

// ── Embed route ────────────────────────────────────────────────────────────
const embedCache = new Map();

async function handleEmbed(anilistId, ep) {
    const episode = Math.max(1, Number(ep || 1) || 1);
    const cached = embedCache.get(anilistId);
    if (cached && Date.now() - cached.cachedAt < EMBED_CACHE_TTL_MS) {
        return {
            status: 200,
            payload: ok({
                tmdbId: cached.tmdbId,
                type: cached.type,
                season: cached.season,
                episode: episode
            })
        };
    }

    let result;
    try {
        result = await workerJson("/map/" + anilistId, "");
    } catch (error) {
        return { status: 502, payload: fail(error && error.message ? error.message : "Embed lookup failed.") };
    }
    const payload = result.payload;
    if (!payload || !payload.mappings) {
        return { status: result.status >= 400 ? result.status : 502, payload: fail((payload && payload.error) || "AniList to TMDB mapping not found.") };
    }

    const mappings = payload.mappings || {};
    const tmdbId = String(mappings.themoviedbId || mappings.tmdbId || "").trim();
    if (!tmdbId || !/^\d+$/.test(tmdbId)) {
        return { status: 404, payload: fail("No TMDB id mapping exists for this anime.") };
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
    return {
        status: 200,
        payload: ok({
            tmdbId: tmdbId,
            type: isMovie ? "movie" : "tv",
            season: season,
            episode: episode
        })
    };
}

// ── Episodes route ─────────────────────────────────────────────────────────
async function handleEpisodes(pathname, search) {
    const rest = pathname.replace(/^\/episodes\//, "");
    const segments = rest.split("/").filter(Boolean);
    const provider = segments[0];
    const anilistId = segments[1];
    if (!provider || !/^\d+$/.test(anilistId || "")) {
        return { status: 400, payload: fail("Expected /episodes/{provider}/{anilistId}.") };
    }

    let result;
    try {
        result = await workerJson("/episodes/" + provider + "/" + anilistId, search || "?map=true");
    } catch (error) {
        return { status: 502, payload: fail(error && error.message ? error.message : "Anivexa episodes lookup failed.") };
    }
    const payload = result.payload;
    if (!payload) {
        return { status: result.status >= 400 ? result.status : 502, payload: fail("Anivexa episodes lookup failed.") };
    }
    if (payload.error) {
        return { status: result.status >= 400 ? result.status : 502, payload: fail(payload.error) };
    }

    const providerBlock = payload[provider];
    if (!providerBlock) {
        return { status: 404, payload: fail("Provider '" + provider + "' returned no data.") };
    }
    const episodes = providerBlock.episodes || {};
    return {
        status: 200,
        payload: ok({
            provider: provider,
            meta: providerBlock.meta || null,
            mappings: payload.mappings || null,
            sub: safeArray(episodes.sub),
            dub: safeArray(episodes.dub)
        })
    };
}

// ── Watch route ────────────────────────────────────────────────────────────
async function handleWatch(pathname, search) {
    let result;
    try {
        result = await workerJson(pathname, search);
    } catch (error) {
        return { status: 502, payload: fail(error && error.message ? error.message : "Anivexa watch lookup failed.") };
    }
    const payload = result.payload;
    if (!payload) {
        return { status: result.status >= 400 ? result.status : 502, payload: fail("Anivexa watch lookup failed.") };
    }
    if (payload.error) {
        return { status: result.status >= 400 ? result.status : 502, payload: fail(payload.error) };
    }
    return { status: 200, payload: ok(payload) };
}

// ── Map route ──────────────────────────────────────────────────────────────
async function handleMap(pathname, search) {
    let result;
    try {
        result = await workerJson(pathname, search);
    } catch (error) {
        return { status: 502, payload: fail(error && error.message ? error.message : "Anivexa map lookup failed.") };
    }
    const payload = result.payload;
    if (!payload) {
        return { status: result.status >= 400 ? result.status : 502, payload: fail("Anivexa map lookup failed.") };
    }
    if (payload.error) {
        return { status: result.status >= 400 ? result.status : 502, payload: fail(payload.error) };
    }
    return { status: 200, payload: ok(payload) };
}

// ── Main dispatcher exposed to Android WebView ─────────────────────────────
window.anivexaWorkerBridge = {
    async handleRequest(requestId, urlPath) {
        try {
            const parsed = new URL(urlPath, "http://localhost");
            const pathname = parsed.pathname;
            const search = parsed.search;

            let result = null;

            if (pathname === "/search") {
                const query = String(parsed.searchParams.get("q") || "").trim();
                if (!query) {
                    result = { status: 400, payload: fail("Anime title query is required.") };
                } else {
                    const searchRes = await anilistSearch(query);
                    result = { status: 200, payload: searchRes };
                }
            } else if (/^\/embed\/(\d+)\/?$/.test(pathname)) {
                const match = /^\/embed\/(\d+)\/?$/.exec(pathname);
                result = await handleEmbed(match[1], parsed.searchParams.get("ep"));
            } else if (/^\/episodes\//.test(pathname)) {
                result = await handleEpisodes(pathname, search);
            } else if (/^\/watch\//.test(pathname)) {
                result = await handleWatch(pathname, search);
            } else if (/^\/map\//.test(pathname)) {
                result = await handleMap(pathname, search);
            } else {
                // Passthrough
                const workerRes = await workerFetch(pathname, search);
                const text = await workerRes.text();
                let payload;
                try { payload = JSON.parse(text); } catch { payload = text; }
                result = { status: workerRes.status, payload: payload };
            }

            const jsonStr = typeof result.payload === "string" ? result.payload : JSON.stringify(result.payload);
            if (window.AndroidBridge && window.AndroidBridge.postResponse) {
                window.AndroidBridge.postResponse(String(requestId), result.status, jsonStr);
            }
        } catch (err) {
            console.error("[anivexaWorkerBridge] error:", err);
            const errStr = JSON.stringify(fail(err && err.message ? err.message : String(err)));
            if (window.AndroidBridge && window.AndroidBridge.postResponse) {
                window.AndroidBridge.postResponse(String(requestId), 500, errStr);
            }
        }
    }
};

if (window.AndroidBridge && window.AndroidBridge.onWorkerReady) {
    window.AndroidBridge.onWorkerReady();
}
console.log("[anivexaWorkerBridge] Ready in Headless WebView");
