/**
 * Unified manga aggregation for the TV app.
 *
 * The TV app pulls its Manga tab/search from the backend (/api/content/home,
 * /api/content/search). Previously the backend only queried MangaDex, so the
 * entire TV Manga section was 100% MangaDex. This module merges MangaDex,
 * WeebCentral, Webtoon and MangaPill, and caps MangaDex so no single provider
 * dominates the grid.
 *
 * All providers are best-effort: any failure degrades to the other providers
 * and never breaks the merged list.
 */

const WEBTOON_BASE = "https://www.webtoons.com";
const WEBCENTRAL_BASE = "https://weebcentral.com";
const MANGAPILL_BASE = "https://mangapill.com";
const UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

function createMangaUnified({ contentItem, fetchWithAbort }) {
    async function fetchText(url, options = {}, timeoutMillis = 20000) {
        const response = await fetchWithAbort(url, options, timeoutMillis);
        const text = await response.text();
        if (!response.ok) {
            throw new Error(`HTTP ${response.status}`);
        }
        return text;
    }

    function decodeEntities(value) {
        return String(value || "")
            .replace(/&amp;/g, "&")
            .replace(/&lt;/g, "<")
            .replace(/&gt;/g, ">")
            .replace(/&quot;/g, '"')
            .replace(/"/g, '"')
            .replace(/'/g, "'")
            .replace(/&#x27;/g, "'")
            .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
            .trim();
    }

    function stripTags(value) {
        return decodeEntities(String(value || "").replace(/<[^>]*>/g, " ").replace(/\s+/g, " ").trim());
    }

    function normalizeTitle(value) {
        return String(value || "")
            .toLowerCase()
            .replace(/levelling/g, "leveling")
            .replace(/leveling/g, "level")
            .replace(/[^a-z0-9]+/g, " ")
            .trim();
    }

    function resolveUrl(value, base) {
        const url = decodeEntities(value);
        if (!url) return "";
        if (/^https?:\/\//i.test(url)) return url;
        return `${base}${url.startsWith("/") ? url : `/${url}`}`;
    }

    function searchTokens(query) {
        return normalizeTitle(query)
            .split(/\s+/)
            .map((token) => token.trim())
            .filter((token) => token.length > 1 && !["the", "and", "manga", "manhwa", "novel"].includes(token));
    }

    function titleMatchesQuery(title, query) {
        const tokens = searchTokens(query);
        if (!tokens.length) return true;
        const normalized = ` ${normalizeTitle(title)} `;
    // Rank a candidate title against the query: exact title match wins, then
    // prefix matches, then titles containing more query words first.
    function titleMatchesQuery(title, query) {
        const qNorm = normalizeTitle(query);
        const tNorm = normalizeTitle(title);
        if (!qNorm || !tNorm) return true;
        if (tNorm.includes(qNorm) || qNorm.includes(tNorm)) return true;
        const tokens = qNorm.split(" ").filter((t) => t.length > 1);
        if (!tokens.length) return true;
        return tokens.some((token) => tNorm.includes(token));
    }

    function relevanceScore(title, query) {
        const q = normalizeTitle(query);
        const t = normalizeTitle(title);
        if (!q || !t) return 0;
        if (t === q) return 1000;
        if (t.startsWith(q)) return 800;
        const tokens = q.split(" ").filter(Boolean);
        const hits = tokens.filter((token) => t.indexOf(token) !== -1).length;
        return hits * 100 - Math.abs(t.length - q.length);
    }

    function uniqueUrls(regex, html) {
        const set = new Set();
        const urls = [];
        let match;
        while ((match = regex.exec(html)) !== null) {
            const url = decodeEntities(match[1]);
            if (!url || set.has(url)) continue;
            set.add(url);
            urls.push(url);
        }
        return urls;
    }

    // ── WeebCentral ──────────────────────────────────────────────────────────
    async function weebCentralItems(query, page = 1) {
        const title = String(query || "").trim();
        if (!title) return []; // no anonymous popular feed — search only
        const body = `text=${encodeURIComponent(title)}`;
        let html = "";
        try {
            html = await fetchText(`${WEBCENTRAL_BASE}/search/simple?location=main`, {
                method: "POST",
                headers: {
                    "content-type": "application/x-www-form-urlencoded",
                    "user-agent": UA,
                    "referer": WEBCENTRAL_BASE,
                    "hx-request": "true"
                },
                body
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const results = [];
        const anchorRegex = /<a[^>]*href="([^"]*\/series\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
        let match;
        const seen = new Set();
        while ((match = anchorRegex.exec(html)) !== null) {
            let href = match[1];
            const inner = match[2];
            const titleDiv = /<div[^>]*class="[^"]*line-clamp-2[^"]*"[^>]*>([\s\S]*?)<\/div>/i.exec(inner);
            const titleMatch = titleDiv ? stripTags(titleDiv[1]) : stripTags(inner);
            if (!titleMatch || titleMatch.length < 2 || seen.has(href)) continue;
            seen.add(href);
            href = resolveUrl(href, WEBCENTRAL_BASE);
            // The cover lives inside the anchor's own inner content.
            const coverMatch =
                /<img[^>]*src="([^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/i.exec(inner) ||
                /<source[^>]*srcset="([^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/i.exec(inner);
            results.push(contentItem({
                id: `weebcentral_${href}`,
                title: titleMatch,
                subtitle: "WeebCentral",
                coverUrl: coverMatch ? resolveUrl(coverMatch[1], WEBCENTRAL_BASE) : "",
                detailUrl: href,
                sourceName: "WeebCentral",
                kind: "manga",
                synopsis: ""
            }));
            if (results.length >= 20) break;
        }
        return results;
    }

    async function weebCentralChapters(detailUrl) {
        let html = "";
        try {
            html = await fetchText(String(detailUrl || ""), {
                headers: { "user-agent": UA, "referer": WEBCENTRAL_BASE }
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const fullListPath = /hx-get=["']([^"']*chapter[^"']*)["']/i.exec(html);
        let chapterHtml = html;
        if (fullListPath) {
            try {
                const partial = await fetchText(resolveUrl(fullListPath[1], WEBCENTRAL_BASE), {
                    headers: { "user-agent": UA, "referer": detailUrl, "hx-request": "true" }
                }, 20000);
                if (partial && partial.length > 100) chapterHtml = `${html}\n${partial}`;
            } catch (e) {
                // fall through to series-page regex
            }
        }

        const results = [];
        const seen = new Set();
        const anchorRegex = /<a[^>]*href="(\/chapters\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
        let match;
        let index = 0;
        while ((match = anchorRegex.exec(chapterHtml)) !== null) {
            const href = match[1];
            if (seen.has(href)) continue;
            seen.add(href);
            const rawTitle = (stripTags(match[2]) || `Chapter ${index + 1}`)
                .replace(/\s*Last Read\s+\d{4}-\d{2}-\d{2}T.*$/i, "")
                .trim();
            const numMatch = /(\d+(?:\.\d+)?)/.exec(rawTitle);
            results.push({
                title: rawTitle,
                url: resolveUrl(href, WEBCENTRAL_BASE),
                chapterNumber: numMatch ? Number.parseInt(numMatch[1], 10) : (index + 1)
            });
            index += 1;
            if (results.length >= 200) break;
        }
        return results;
    }

    async function weebCentralPages(chapterUrl) {
        let html = "";
        try {
            html = await fetchText(String(chapterUrl || ""), {
                headers: { "user-agent": UA, "referer": WEBCENTRAL_BASE }
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const imagePath = /hx-get=["']([^"']+\/images[^"']*)["']/i.exec(html);
        if (imagePath) {
            try {
                const separator = imagePath[1].includes("?") ? "&" : "?";
                const partial = await fetchText(resolveUrl(`${imagePath[1]}${separator}reading_style=long_strip`, WEBCENTRAL_BASE), {
                    headers: { "user-agent": UA, "referer": chapterUrl, "hx-request": "true" }
                }, 20000);
                const pages = uniqueUrls(
                    /<img[^>]*src="([^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/gi,
                    partial
                );
                const filtered = pages
                    .map((url) => resolveUrl(url, WEBCENTRAL_BASE))
                    .filter((url) => !/broken_image|logo|avatar|icon|brand|static\/images/i.test(url));
                if (filtered.length) return filtered;
            } catch (e) {
                // fall through
            }
        }

        const direct = [
            ...uniqueUrls(/<link[^>]*rel=["']preload["'][^>]*href=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']/gi, html),
            ...uniqueUrls(/(?:data-src|src|href)=["']([^"']+\.(?:jpg|jpeg|png|webp)[^"']*)["']/gi, html),
            ...uniqueUrls(/(https?:\/\/(?:hot|img|temp|cdn)[^"'\s<>]+\.(?:jpg|jpeg|png|webp)[^"'\s<>]*)/gi, html)
        ];
        return direct
            .map((url) => resolveUrl(url, WEBCENTRAL_BASE))
            .filter((url, index, all) => all.indexOf(url) === index)
            .filter((url) => !/logo|avatar|icon|banner|brand|static\/images|cover\/fallback/i.test(url));
    }

    // ── MangaPill ────────────────────────────────────────────────────────────
    // MangaPill <img alt> repeats the title twice ("Solo Leveling Novel Solo
    // Leveling Novel"); collapse consecutive duplicate runs.
    function collapseDuplicateTitle(value) {
        const title = stripTags(value);
        const duplicate = /^(.+?)(?:\s+\1)+$/i.exec(title);
        return duplicate ? duplicate[1].trim() : title;
    }

    async function mangapillItems(query, page = 1) {
        const title = String(query || "").trim();
        if (!title) return []; // no anonymous popular feed — search only
        let html = "";
        try {
            html = await fetchText(`${MANGAPILL_BASE}/search?q=${encodeURIComponent(title)}`, {
                headers: { "user-agent": UA, "referer": MANGAPILL_BASE }
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const results = [];
        const seen = new Set();
        const cardRegex = /<a[^>]*href="(\/manga\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
        let match;
        while ((match = cardRegex.exec(html)) !== null) {
            const href = match[1];
            if (seen.has(href)) continue;
            seen.add(href);
            const inner = match[2];
            const coverMatch =
                /<img[^>]*data-src="([^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/i.exec(inner) ||
                /<img[^>]*src="([^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/i.exec(inner);
            const altMatch = /<img[^>]*alt="([^"]+)"/i.exec(inner);
            const titleMatch = collapseDuplicateTitle(
                (altMatch && altMatch[1]) ||
                (/<h3[^>]*>([\s\S]*?)<\/h3>/i.exec(inner) || [])[1] ||
                inner
            );
            if (!titleMatch || titleMatch.length < 2) continue;
            results.push(contentItem({
                id: `mangapill_${href}`,
                title: titleMatch,
                subtitle: "MangaPill",
                coverUrl: coverMatch ? coverMatch[1] : "",
                detailUrl: `${MANGAPILL_BASE}${href}`,
                sourceName: "MangaPill",
                kind: "manga",
                synopsis: ""
            }));
            if (results.length >= 20) break;
        }
        return results;
    }

    async function mangapillChapters(detailUrl) {
        let html = "";
        try {
            html = await fetchText(String(detailUrl || ""), {
                headers: { "user-agent": UA, "referer": MANGAPILL_BASE }
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const results = [];
        const seen = new Set();
        const anchorRegex = /<a[^>]*href="(\/chapters\/[^"]+)"[^>]*>([\s\S]*?)<\/a>/g;
        let match;
        let index = 0;
        while ((match = anchorRegex.exec(html)) !== null) {
            const href = match[1];
            if (seen.has(href)) continue;
            seen.add(href);
            const inner = match[2];
            const attrTitle = /title="([^"]*)"/i.exec(match[0]);
            const rawTitle = stripTags(attrTitle ? attrTitle[1] : inner) || `Chapter ${index + 1}`;
            // Prefer the number right after "Chapter", then the trailing number,
            // then the first number (titles like "Group 2 Chapter 203" otherwise
            // resolve to 2 instead of 203).
            const chapterNumMatch =
                /chapter\s+(\d+(?:\.\d+)?)/i.exec(rawTitle) ||
                /(\d+(?:\.\d+)?)\s*$/.exec(rawTitle) ||
                /(\d+(?:\.\d+)?)/.exec(rawTitle);
            results.push({
                title: rawTitle,
                url: `${MANGAPILL_BASE}${href}`,
                chapterNumber: chapterNumMatch ? Number.parseInt(chapterNumMatch[1], 10) : (index + 1)
            });
            index += 1;
            if (results.length >= 200) break;
        }
        return results;
    }

    async function mangapillPages(chapterUrl) {
        let html = "";
        try {
            html = await fetchText(String(chapterUrl || ""), {
                headers: { "user-agent": UA, "referer": MANGAPILL_BASE }
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const pages = uniqueUrls(
            /<img[^>]*class="[^"]*js-page[^"]*"[^>]*data-src="([^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/gi,
            html
        );
        return pages.filter((url) => !/logo|avatar|icon/i.test(url));
    }

    // ── Webtoon ──────────────────────────────────────────────────────────────
    async function webtoonItems(query, page = 1) {
        const title = String(query || "").trim();
        if (!title) return []; // no anonymous popular feed — search only
        let html = "";
        try {
            html = await fetchText(`${WEBTOON_BASE}/en/search?keyword=${encodeURIComponent(title)}`, {
                headers: { "user-agent": UA, "accept-language": "en-US,en;q=0.9" }
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const results = [];
        const seen = new Set();
        const anchorRegex = /<a[^>]*href="([^"]*title_no=\d+[^"]*)"[^>]*>([\s\S]*?)<\/a>/g;
        let match;
        while ((match = anchorRegex.exec(html)) !== null) {
            const href = match[1];
            if (seen.has(href)) continue;
            seen.add(href);
            const body = match[2];
            // Webtoon card titles live in <strong class="title">; thumbnail
            // <img alt> is frequently empty, so try .title first, then .subj,
            // then img alt, then text.
            const strongTitle = /<strong[^>]*class="[^"]*title[^"]*"[^>]*>([\s\S]*?)<\/strong>/i.exec(body);
            const subjMatch = /class="[^"]*subj[^"]*"[^>]*>([\s\S]*?)<\/[^>]+>/i.exec(body);
            const titleMatch = stripTags(
                (strongTitle && strongTitle[1]) ||
                (subjMatch && subjMatch[1]) ||
                (/<img[^>]*alt="([^"]+)"/i.exec(body) || [])[1] ||
                body
            );
            if (!titleMatch || titleMatch.length < 2) continue;
            // Same as WeebCentral: extract from the anchor's own inner content,
            // not a lookbehind region that can grab the previous card's <img>.
            const inner = match[2];
            const coverMatch = /<img[^>]*src="([^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/i.exec(inner);
            results.push(contentItem({
                id: `webtoon_${href}`,
                title: titleMatch,
                subtitle: "Webtoon",
                coverUrl: coverMatch ? coverMatch[1] : "",
                detailUrl: href.startsWith("http") ? href : `${WEBTOON_BASE}${href}`,
                sourceName: "Webtoon",
                kind: "manga",
                synopsis: ""
            }));
            if (results.length >= 20) break;
        }
        return results;
    }

    async function webtoonChapters(detailUrl) {
        let html = "";
        try {
            html = await fetchText(String(detailUrl || ""), {
                headers: { "user-agent": UA }
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const results = [];
        const seen = new Set();
        const anchorRegex = /<a[^>]*href="([^"]*episode_no=\d+[^"]*)"[^>]*>([\s\S]*?)<\/a>/g;
        let match;
        let index = 0;
        while ((match = anchorRegex.exec(html)) !== null) {
            const href = match[1];
            if (seen.has(href)) continue;
            seen.add(href);
            const innerText = stripTags(match[2]);
            const subj = /class="[^"]*subj[^"]*"[^>]*>([\s\S]*?)</i.exec(match[2]);
            const titleRaw = subj ? stripTags(subj[1]) : innerText;
            const numMatch = /(\d+)/.exec(titleRaw || href);
            results.push({
                title: titleRaw || `Episode ${index + 1}`,
                url: href.startsWith("http") ? href : `${WEBTOON_BASE}${href}`,
                chapterNumber: numMatch ? Number.parseInt(numMatch[1], 10) : (index + 1)
            });
            index += 1;
            if (results.length >= 200) break;
        }
        return results;
    }

    async function webtoonPages(chapterUrl) {
        let html = "";
        try {
            html = await fetchText(String(chapterUrl || ""), {
                headers: { "user-agent": UA }
            }, 20000);
        } catch (e) {
            return [];
        }
        if (!html || html.length < 200) return [];

        const fromDataUrl = uniqueUrls(
            /data-url="(https:\/\/[^"]+\.(?:jpg|jpeg|png|webp)[^"]*)"/gi,
            html
        );
        if (fromDataUrl.length) return fromDataUrl;

        const fromPhinf = uniqueUrls(
            /(https:\/\/webtoon-phinf\.pstatic\.net\/[^"'\s]+\.(?:jpg|jpeg|png|webp)[^"'\s]*)/gi,
            html
        );
        return fromPhinf;
    }

    // ── Merged manga search + feed (MangaDex + WeebCentral + Webtoon + MangaPill) ──
    async function mangaItems(mangadexItems, query, page = 1) {
        const isSearch = String(query || "").trim().length > 0;
        const pageNum = Math.max(1, Number(page) || 1);

        // Home feed: MangaDex has a true popular endpoint, but WeebCentral and
        // Webtoon only expose search. Mirror the Android client and query each
        // with a rotating popular seed so every provider contributes to the tab.
        const seeds = ["solo leveling", "one piece", "tower of god", "berserk", "solo", "one"];
        const feedQuery = isSearch ? String(query).trim() : seeds[(pageNum - 1) % seeds.length];
        
        // Home feed: WeebCentral's listing endpoints are Cloudflare-protected
        // (probe returned 400), but /search/simple works and returns real
        // covers. Seed each home page with the rotating popular query so the
        // Manga tab actually shows WeebCentral/manhwa titles with covers.
        // (The seeds array above already drives this.)

        const [dex, weeb, webtoon, pill] = await Promise.all([
            mangadexItems(isSearch ? String(query).trim() : "", pageNum).catch(() => []),
            weebCentralItems(feedQuery, pageNum).catch(() => []),
            webtoonItems(feedQuery, pageNum).catch(() => []),
            mangapillItems(feedQuery, pageNum).catch(() => [])
        ]);

        // Round-robin interleave so every provider is visible & none can dominate,
        // with MangaDex additionally capped.
        const sources = [
            { name: "MangaDex", items: dex },
            { name: "WeebCentral", items: weeb },
            { name: "Webtoon", items: webtoon },
            { name: "MangaPill", items: pill }
        ];

        const totalAvailable = sources.reduce((sum, s) => sum + s.items.length, 0);
        const dexLimit = isSearch
            ? Math.min(10, Math.max(3, Math.ceil(totalAvailable / 3)))
            : Math.max(6, Math.ceil(totalAvailable / 3));
        const dexIndex = Math.min(dexLimit, dex.length);

        // Search: drop any title that does not contain every meaningful query
        // word (normalized "levelling"→"leveling"), then rank by relevance so
        // exact/prefix matches surface ahead of fuzzy Webtoon/engine results.
        // This stops "Omniscient Reader" appearing for "solo levelling".
        const lists = sources.map((s) => {
            const rawItems = s.name === "MangaDex" ? s.items.slice(0, dexIndex) : s.items;
            let items = rawItems.filter((item) => item && item.title);
            if (isSearch) {
                items = items
                    .filter((item) => titleMatchesQuery(item.title, query))
                    .sort((a, b) => relevanceScore(b.title, query) - relevanceScore(a.title, query));
            }
            return items;
        });

        const merged = [];
        const seenTitles = new Set();
        let maxLen = Math.max(0, ...lists.map((l) => l.length));
        for (let i = 0; i < maxLen; i++) {
            for (const list of lists) {
                const item = list[i];
                if (!item) continue;
                const key = normalizeTitle(item.title);
                if (seenTitles.has(key)) continue;
                seenTitles.add(key);
                merged.push(item);
            }
        }
        return merged;
    }

    // ── Provider-agnostic chapter/page routing ───────────────────────────────
    async function mangaChapters(detailUrl) {
        const url = String(detailUrl || "");
        if (url.includes("weebcentral.com")) return weebCentralChapters(url);
        if (url.includes("webtoons.com")) return webtoonChapters(url);
        if (url.includes("mangapill.com")) return mangapillChapters(url);
        return [];
    }

    async function mangaPages(chapterUrl) {
        const url = String(chapterUrl || "");
        if (url.includes("weebcentral.com")) return weebCentralPages(url);
        if (url.includes("webtoons.com")) return webtoonPages(url);
        if (url.includes("mangapill.com")) return mangapillPages(url);
        return [];
    }

    return {
        mangaItems,
        mangaChapters,
        mangaPages,
        mangapillItems,
        mangapillChapters,
        mangapillPages,
        weebCentralItems,
        webtoonItems
    };
}

module.exports = { createMangaUnified };