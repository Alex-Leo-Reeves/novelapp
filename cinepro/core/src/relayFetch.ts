/**
 * Upstream relay for Cloudflare-blocked hosts.
 *
 * Render's egress IPs are flagged by Cloudflare: the entertainment hosts our
 * providers scrape (vsembed.ru, vixsrc.to, …) answer 403 / a "Just a moment…"
 * challenge to ANY request issued from Render, while the exact same URL
 * returns 200 from a residential IP (verified 2026-09-28 through /v1/proxy).
 * That made every provider fail instantly on the deployed instance
 * (allProvidersFailed) even though the providers themselves are fine.
 *
 * installRelayFetch() wraps globalThis.fetch: the direct request is ALWAYS
 * tried first (healthy hosts never depend on a relay), and only when the edge
 * blocks us (403/429/451/503) or the socket fails does the request retry
 * through public relays whose egress is not flagged:
 *
 *   1. Jina Reader  — https://r.jina.ai/<url>, x-return-format: html → raw body
 *   2. allorigins   — https://api.allorigins.win/raw?url=<enc>
 *   3. codetabs     — https://api.codetabs.com/v1/proxy/?quest=<enc>
 *
 * Same logic as server/relay-fetch.js in the main backend.
 */

const RELAY_TIMEOUT_MS = 25_000;

type FetchLike = typeof globalThis.fetch;

interface Relay {
    name: string;
    url: string;
    headers: Record<string, string>;
}

/** Statuses that mean "the edge blocked us", not "the resource is gone". */
function isBlockedStatus(status: number): boolean {
    return status === 403 || status === 429 || status === 451 || status === 503;
}

/**
 * Jina renders non-HTML payloads (JSON, plain text) as an HTML document:
 *   <html><head>…</head><body><pre …>RAW</pre></body></html>
 * Unwrap that envelope so callers receive the origin body unchanged. Real
 * HTML pages come back raw from Jina, so they pass through untouched.
 */
function unwrapRelayEnvelope(body: string): string {
    const match =
        /^<html[^>]*>[\s\S]*?<body[^>]*><pre[^>]*>([\s\S]*)<\/pre>[\s\S]*<\/body>\s*<\/html>$/i.exec(
            body.trim()
        );
    if (!match) return body;
    return match[1]
        .replace(/&lt;/g, "<")
        .replace(/&gt;/g, ">")
        .replace(/&quot;/g, '"')
        .replace(/&#39;/g, "'")
        .replace(/&amp;/g, "&");
}

/** Guess a content type for the synthetic Response from the body itself. */
function guessContentType(body: string): string {
    const head = body.trimStart().slice(0, 64);
    if (head.startsWith("{") || head.startsWith("[")) {
        return "application/json; charset=utf-8";
    }
    if (head.startsWith("#EXTM3U")) {
        return "application/vnd.apple.mpegurl";
    }
    return "text/html; charset=utf-8";
}

function relayCandidates(target: string): Relay[] {
    return [
        {
            name: "jina",
            url: "https://r.jina.ai/" + target,
            headers: {
                "x-return-format": "html",
                Accept: "text/html,application/json;q=0.9,*/*;q=0.8"
            }
        },
        {
            name: "allorigins",
            url: "https://api.allorigins.win/raw?url=" + encodeURIComponent(target),
            headers: { Accept: "*/*" }
        },
        {
            name: "codetabs",
            url: "https://api.codetabs.com/v1/proxy/?quest=" + encodeURIComponent(target),
            headers: { Accept: "*/*" }
        }
    ];
}

function targetUrlOf(input: string | URL | Request): string {
    if (typeof input === "string") return input;
    if (input instanceof URL) return input.toString();
    if (typeof Request !== "undefined" && input instanceof Request) return input.url;
    return String(input);
}

function methodOf(input: string | URL | Request, init?: RequestInit): string {
    if (init?.method) return String(init.method).toUpperCase();
    if (typeof Request !== "undefined" && input instanceof Request) return input.method.toUpperCase();
    return "GET";
}

/** Try the relay chain for `target`. Resolves a synthetic Response or null. */
export async function relayResponse(target: string, original: FetchLike): Promise<Response | null> {
    for (const relay of relayCandidates(target)) {
        try {
            const res = await original(relay.url, {
                headers: relay.headers,
                redirect: "follow",
                signal: AbortSignal.timeout(RELAY_TIMEOUT_MS)
            });
            if (!res.ok) continue;
            const body = unwrapRelayEnvelope(await res.text());
            // A relay answering 200 with an empty body or its own CF challenge
            // page is itself failing — try the next one.
            if (
                !body ||
                body.includes("<title>Just a moment...") ||
                body.includes("error code: 5")
            ) {
                continue;
            }
            return new Response(body, {
                status: 200,
                headers: { "content-type": guessContentType(body) }
            });
        } catch (_error) {
            // try the next relay
        }
    }
    return null;
}

/**
 * Wrap globalThis.fetch with the relay fallback. Must be called BEFORE any
 * provider issues a request (server.ts calls it at startup).
 */
export function installRelayFetch(): void {
    const original: FetchLike = globalThis.fetch.bind(globalThis);

    const patched: FetchLike = async (input, init) => {
        let direct: Response;
        try {
            direct = await original(input, init);
        } catch (error) {
            const relayed = await relayResponse(targetUrlOf(input), original);
            if (relayed) return relayed;
            throw error;
        }
        // Only GETs go through a relay (relays cannot replay bodies/headers),
        // and only when the edge actually blocked us.
        if (isBlockedStatus(direct.status) && methodOf(input, init) === "GET") {
            const relayed = await relayResponse(targetUrlOf(input), original);
            if (relayed) return relayed;
        }
        return direct;
    };

    globalThis.fetch = patched;
}
