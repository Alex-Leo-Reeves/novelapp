"use strict";

// ─────────────────────────────────────────────────────────────────────────────
//  Upstream relay for Cloudflare-blocked hosts.
//
//  Render's egress IPs are flagged by Cloudflare: every Cloudflare-protected
//  entertainment host (donghuaworld.com, vixsrc.to, vsembed.ru, …) answers
//  403 or a "Just a moment…" challenge to ANY request issued from Render,
//  while the exact same URL returns 200 from a residential IP (verified
//  2026-09-28: /v1/proxy through cinepro-core on Render -> 403 challenge,
//  the same URLs from a home connection -> 200).
//
//  This module retries those blocked requests through public relay services
//  whose egress is not flagged, returning the origin's raw body.
//
//  Relay order:
//    1. Jina Reader  — https://r.jina.ai/<url> with `x-return-format: html`
//       returns the RAW body (verified: full HTML pages and WP REST JSON).
//    2. allorigins   — https://api.allorigins.win/raw?url=<enc>
//    3. codetabs     — https://api.codetabs.com/v1/proxy/?quest=<enc>
//
//  Direct requests are ALWAYS tried first, so healthy hosts (TMDB, ikanbot,
//  …) never depend on a relay, and relays only ever see the blocked ones.
// ─────────────────────────────────────────────────────────────────────────────

const RELAY_TIMEOUT_MS = 25 * 1000;

// Statuses that mean "the edge blocked us", not "the resource is gone".
function isBlockedStatus(status) {
    return status === 403 || status === 429 || status === 451 || status === 503;
}

function fetchWithAbort(url, headers, timeoutMs) {
    return fetch(url, {
        headers: headers,
        redirect: "follow",
        signal: AbortSignal.timeout(timeoutMs || RELAY_TIMEOUT_MS)
    });
}

/** Ordered relay descriptors for one target URL. */
function relayCandidates(targetUrl) {
    return [
        {
            name: "jina",
            url: "https://r.jina.ai/" + targetUrl,
            headers: {
                "x-return-format": "html",
                Accept: "text/html,application/json;q=0.9,*/*;q=0.8"
            }
        },
        {
            name: "allorigins",
            url: "https://api.allorigins.win/raw?url=" + encodeURIComponent(targetUrl),
            headers: { Accept: "*/*" }
        },
        {
            name: "codetabs",
            url: "https://api.codetabs.com/v1/proxy/?quest=" + encodeURIComponent(targetUrl),
            headers: { Accept: "*/*" }
        }
    ];
}

/**
 * Jina renders non-HTML payloads (JSON, plain text) as an HTML document:
 *   <html><head>…</head><body><pre …>RAW</pre></body></html>
 * Unwrap that envelope so callers receive the origin body unchanged.
 * Real HTML pages are returned raw by Jina (body starts with the origin's
 * markup, not an immediate <pre>), so they pass through untouched.
 */
function unwrapRelayEnvelope(body) {
    const match = /^<html[^>]*>[\s\S]*?<body[^>]*><pre[^>]*>([\s\S]*)<\/pre>[\s\S]*<\/body>\s*<\/html>$/i
        .exec(String(body).trim());
    if (!match) return body;
    return match[1]
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&quot;/g, "\"")
        .replace(/&#39;/g, "'")
        .replace(/&amp;/g, "&");
}

/**
 * Fetch `url` through the relay chain ONLY (no direct attempt).
 * Resolves { status, ok, body } when a relay delivered the origin body,
 * or null when every relay failed.
 */
async function relayFetchText(url, headers, timeoutMs) {
    const forwarded = {};
    // Relays fetch server-side; forward only harmless client hints, never
    // cookies/authorization (they would leak to a third party).
    if (headers) {
        for (const key of Object.keys(headers)) {
            const lower = key.toLowerCase();
            if (lower === "user-agent" || lower === "accept-language") {
                forwarded[key] = headers[key];
            }
        }
    }
    for (const relay of relayCandidates(url)) {
        try {
            const res = await fetchWithAbort(relay.url, { ...forwarded, ...relay.headers }, timeoutMs);
            if (!res.ok) continue;
            const body = unwrapRelayEnvelope(await res.text());
            // A relay that answers 200 with an empty body or its own CF
            // challenge page is itself failing — try the next one.
            if (!body || body.includes("<title>Just a moment...") || body.includes("error code: 5")) {
                continue;
            }
            return { status: 200, ok: true, body: body, relay: relay.name };
        } catch (_error) {
            // try the next relay
        }
    }
    return null;
}

/**
 * Drop-in wrapper for the handlers' fetchText: try direct first, fall back
 * to the relay chain when the edge blocks us (or the socket fails outright).
 *
 * Returns { status, ok, body }.
 */
async function relayedFetchText(url, headers, timeoutMs) {
    let direct = null;
    try {
        direct = await fetchWithAbort(url, headers, timeoutMs);
    } catch (_error) {
        const relayed = await relayFetchText(url, headers, timeoutMs);
        if (relayed) return relayed;
        throw new Error("upstream unreachable and no relay available: " + url);
    }
    if (isBlockedStatus(direct.status)) {
        const relayed = await relayFetchText(url, headers, timeoutMs);
        if (relayed) return relayed;
    }
    const body = await direct.text();
    return { status: direct.status, ok: direct.ok, body: body };
}

module.exports = {
    relayedFetchText,
    relayFetchText,
    isBlockedStatus
};
