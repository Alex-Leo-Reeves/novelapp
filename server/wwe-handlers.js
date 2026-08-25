// ─────────────────────────────────────────────────────────────────────────────
//  WWE Data Scraping & Stream Resolution Handlers
//
//  Two-layer approach:
//    Layer 1 (Option A): Scrape watchwrestling post pages for lightweight
//      embed iframes (doodstream, vidmoly, streamtape, voe.sx, etc.).
//      These iframe URLs are loaded in MaServerPlayerScreen (WebView).
//
//    Layer 2 (Option B): Scrape the same pages for direct .m3u8 URLs.
//      Also try sports streaming aggregators (streamed.su, crackstreams, etc.)
//      for any live wrestling events. Direct .m3u8 URLs play in AnimePlayerScreen
//      (ExoPlayer).
// ─────────────────────────────────────────────────────────────────────────────

const WWE_BRANDS = [
    { id: "raw", name: "RAW", logo: "" },
    { id: "smackdown", name: "SmackDown", logo: "" },
    { id: "nxt", name: "NXT", logo: "" },
    { id: "aew", name: "AEW", logo: "" },
    { id: "ppv", name: "Premium Live Event", logo: "" }
];

function sendJson(response, statusCode, payload) {
    const body = JSON.stringify(payload);
    if (!response.headersSent) {
        response.writeHead(statusCode, {
            "content-type": "application/json; charset=utf-8",
            "content-length": Buffer.byteLength(body),
            "access-control-allow-origin": "*",
            "access-control-allow-methods": "GET, POST, PUT, DELETE, OPTIONS",
            "access-control-allow-headers": "Content-Type, Authorization"
        });
    }
    response.end(body);
}

function sendApiData(response, statusCode, data) {
    return sendJson(response, statusCode, { ok: true, data });
}

function sendApiError(response, statusCode, message) {
    return sendJson(response, statusCode, { ok: false, data: null, error: message });
}

let wweCache = { events: [], matches: {}, fetchedAt: 0, ttl: 120000 };
const wweStreamCache = { embedUrls: {}, directUrls: {}, fetchedAt: 0, ttl: 180000 };

/**
 * Known embed video host domains that work well in MaServerPlayerScreen WebView.
 * These are lightweight iframe players, not full-page websites.
 */
const KNOWN_EMBED_HOSTS = [
    "fastvid",
    "afiyukent",
    "dailymotion",
    "doodstream",
    "dood",
    "vidmoly",
    "streamtape",
    "voe.sx",
    "voe",
    "ok.ru",
    "streamwish",
    "filelions",
    "dropload",
    "netu",
    "youtube.com/embed",
    "streamhub",
    "embed",
    "player",
    "play.php",
    "watchwrestling"
];

/**
 * Known live-streaming aggregator patterns for wrestling content.
 */
function isEmbedHost(url) {
    const lower = (url || "").toLowerCase();
    return KNOWN_EMBED_HOSTS.some(host => lower.includes(host));
}

function slugify(text) {
    return String(text || "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}

async function fetchWweHtml(url, referer = "") {
    try {
        const headers = {
            "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "accept-language": "en-US,en;q=0.9"
        };
        if (referer) headers["referer"] = referer;

        const resp = await fetch(url, {
            headers,
            signal: AbortSignal.timeout(10000)
        });
        return resp.ok ? await resp.text() : "";
    } catch (e) {
        console.error("[WWE] Fetch failed for", url, e.message);
        return "";
    }
}

/**
 * Extract raw candidate embed/player links from HTML, searching:
 * 1. Standard <iframe> src attributes
 * 2. Hidden <textarea> blocks used by watchwrestling
 * 3. Script append blocks with encoded HTML
 * 4. <a> tags with embed host URLs
 */
function extractCandidateLinks(html, pageUrl) {
    const rawLinks = [];
    if (!html || html.length < 100) return rawLinks;

    // 1. Textarea content
    const textareaRegex = /<textarea[^>]*>([\s\S]*?)<\/textarea>/gi;
    let match;
    while ((match = textareaRegex.exec(html)) !== null) {
        const inner = match[1];
        const linkRe = /href=["']([^"']+)["']/gi;
        let m2;
        while ((m2 = linkRe.exec(inner)) !== null) {
            rawLinks.push(m2[1].trim());
        }
    }

    // 2. JavaScript script append blocks
    const jsScriptRe = /append\(["']([\s\S]*?)["']\)/gi;
    while ((match = jsScriptRe.exec(html)) !== null) {
        const inner = match[1].replace(/\\"/g, '"').replace(/\\'/g, "'").replace(/\\\//g, "/");
        const linkRe = /href=["']([^"']+)["']/gi;
        let m2;
        while ((m2 = linkRe.exec(inner)) !== null) {
            rawLinks.push(m2[1].trim());
        }
    }

    // 3. <iframe> src tags
    const iframeRegex = /<iframe[^>]*src=["']([^"']+)["'][^>]*>/gi;
    while ((match = iframeRegex.exec(html)) !== null) {
        const src = match[1].trim();
        if (src && !src.startsWith("javascript") && !src.startsWith("about:")) {
            rawLinks.push(src.startsWith("//") ? "https:" + src : src);
        }
    }

    // 4. <a> links to known hosts
    const linkRegex = /<a[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi;
    while ((match = linkRegex.exec(html)) !== null) {
        const href = match[1].trim();
        if (!href || href.startsWith("#") || href.startsWith("javascript")) continue;
        if (isEmbedHost(href)) {
            const absUrl = href.startsWith("http") ? href : new URL(href, pageUrl).href;
            rawLinks.push(absUrl);
        }
    }

    return [...new Set(rawLinks)].filter(l => l.startsWith("http"));
}

/**
 * Resolves candidate embed links and unwraps any play.php / afiyukent wrappers
 * into final direct player iframes (e.g. FastVid, Dailymotion, VidMoly).
 */
async function extractEmbedUrls(html, pageUrl) {
    const candidates = extractCandidateLinks(html, pageUrl);
    const resolvedEmbeds = [];

    // Parallel unwrap of wrapper links (e.g. afiyukent.one/play.php, fastvid.xyz)
    const unwrapTasks = candidates.slice(0, 12).map(async (candidate) => {
        if (candidate.includes("play.php") || candidate.includes("afiyukent")) {
            try {
                const wrapperHtml = await fetchWweHtml(candidate, pageUrl);
                if (wrapperHtml) {
                    const iframes = wrapperHtml.match(/<iframe[^>]*src=["']([^"']+)["']/gi) || [];
                    for (const ifr of iframes) {
                        const srcMatch = ifr.match(/src=["']([^"']+)["']/i);
                        if (srcMatch) {
                            let s = srcMatch[1].trim().replace(/&amp;/g, "&");
                            if (s.startsWith("//")) s = "https:" + s;
                            resolvedEmbeds.push(s);
                        }
                    }
                }
            } catch (_) {}
        } else if (candidate.includes("fastvid.xyz") || isEmbedHost(candidate)) {
            resolvedEmbeds.push(candidate.replace(/&amp;/g, "&"));
        }
    });

    await Promise.allSettled(unwrapTasks);

    // If wrappers resolved inner players, prioritize them; else return candidates
    const all = [...resolvedEmbeds, ...candidates.filter(c => isEmbedHost(c))];
    return [...new Set(all)].slice(0, 15);
}

/**
 * Extract direct .m3u8/.mp4 stream URLs from an HTML page.
 */
function extractDirectStreamUrls(html) {
    const urls = [];
    if (!html || html.length < 200) return urls;

    // .m3u8 URLs in src/data-src/href attributes
    const srcPattern = /(?:src|data-src|href)=["']([^"']*\.(?:m3u8|mp4)[^"']*)["']/gi;
    let match;
    while ((match = srcPattern.exec(html)) !== null) {
        const url = match[1].trim();
        if (url.startsWith("http") || url.startsWith("//")) {
            urls.push(url.startsWith("//") ? "https:" + url : url);
        }
    }

    // .m3u8 URLs in JavaScript variables
    const varPattern = /["']([^"']*\.(?:m3u8|mp4)[^"']*?)["']/gi;
    while ((match = varPattern.exec(html)) !== null) {
        const url = match[1].trim();
        if (url.startsWith("http") || url.startsWith("//")) {
            urls.push(url.startsWith("//") ? "https:" + url : url);
        }
    }

    // Plain .m3u8 URLs in text
    const textPattern = /(https?:\/\/[^\s<>"']+\.(?:m3u8|mp4)[^\s<>"']*)/gi;
    while ((match = textPattern.exec(html)) !== null) {
        urls.push(match[1]);
    }

    return [...new Set(urls)].slice(0, 10);
}

async function handleWweEvents(request, response) {
    try {
        const now = Date.now();
        if (wweCache.events.length && (now - wweCache.fetchedAt) < wweCache.ttl) {
            return sendApiData(response, 200, wweCache.events);
        }

        // Scrape watchwrestling.ae for event listings
        const events = [];
        const html = await fetchWweHtml("https://watchwrestling.ae");
        if (html && html.length > 200) {
            const postBlocks = html.match(/<div[^>]*class="[^"]*post[^"]*"[^>]*>[\s\S]*?<\/div>\s*<\/div>\s*<\/div>\s*<\/div>/gi) ||
                html.match(/<article[^>]*>[\s\S]*?<\/article>/gi) ||
                html.match(/<div[^>]*class="[^"]*(?:post|item|entry)[^"]*"[^>]*>[\s\S]*?<\/div>/gi) || [];

            for (const block of postBlocks.slice(0, 30)) {
                const linkMatch = block.match(/<a[^>]*href=["']([^"']+)["'][^>]*>/i);
                if (!linkMatch) continue;
                const href = linkMatch[1];
                if (!href.startsWith("http") || href.includes("category") || href.includes("/page/")) continue;

                const titleMatch = block.match(/<a[^>]*title=["']([^"']+)["']/i) ||
                    block.match(/alt=["']([^"']+?)["']/i) ||
                    href.match(/\/([^/]+?)(?:\/|$)/);
                const title = (titleMatch ? titleMatch[1] : "").trim()
                    .replace(/<[^>]+>/g, "")
                    .replace(/&#?[a-z0-9]+;/gi, " ")
                    .replace(/\s+/g, " ")
                    .trim();
                if (!title || title.length < 5) continue;

                const imgMatch = block.match(/<img[^>]*src=["']([^"']+)["']/i);
                let cover = imgMatch ? imgMatch[1] : "";
                if (cover.includes("dummy") || cover.includes("blank") || cover.includes("spacer")) {
                    const dataSrc = block.match(/data-src=["']([^"']+)["']/i);
                    if (dataSrc) cover = dataSrc[1];
                }

                let brand = "WWE";
                if (/raw/i.test(title)) brand = "RAW";
                else if (/smackdown|sd/i.test(title)) brand = "SmackDown";
                else if (/nxt/i.test(title)) brand = "NXT";
                else if (/aew/i.test(title)) brand = "AEW";

                events.push({
                    eventId: href.replace(/^https?:\/\//, "").replace(/[/?#]/g, "_"),
                    title: title,
                    brand: brand,
                    eventType: /ppv|premium|live/i.test(title) ? "Premium Live Event" : "TV Show",
                    date: "Recent",
                    time: "",
                    status: "COMPLETED",
                    venue: "",
                    description: "",
                    matches: [],
                    posterUrl: cover,
                    detailPageUrl: href
                });
            }
        }

        // Also try fetching from WWE official site as secondary source
        const wweHtml = await fetchWweHtml("https://www.wwe.com/events");
        if (wweHtml && wweHtml.length > 500) {
            const articleBlocks = wweHtml.match(/<article[^>]*class="[^"]*event[^"]*"[^>]*>[\s\S]*?<\/article>/gi) || [];
            for (const block of articleBlocks.slice(0, 10)) {
                const titleMatch = block.match(/<h3[^>]*>([\s\S]*?)<\/h3>/i);
                const title = titleMatch ? titleMatch[1].replace(/<[^>]+>/g, "").trim() : "";
                if (!title || events.some(e => e.title.includes(title) || title.includes(e.title))) continue;

                const dateMatch = block.match(/<time[^>]*datetime=["']([^"']+)["']/i) ||
                    block.match(/<span[^>]*class="[^"]*date[^"]*"[^>]*>([\s\S]*?)<\/span>/i);
                const date = dateMatch ? dateMatch[1].replace(/<[^>]+>/g, "").trim() : "";

                let brand = "WWE";
                if (/raw/i.test(title)) brand = "RAW";
                else if (/smackdown/i.test(title)) brand = "SmackDown";
                else if (/nxt/i.test(title)) brand = "NXT";

                events.push({
                    eventId: slugify(title) + "-" + date.replace(/[^0-9]/g, ""),
                    title: title,
                    brand: brand,
                    eventType: title.toLowerCase().includes("live") ? "Premium Live Event" : "TV Show",
                    date: date || "",
                    time: "",
                    status: (date && Date.parse(date) < Date.now()) ? "COMPLETED" : "UPCOMING",
                    venue: "",
                    description: "",
                    matches: [],
                    posterUrl: "",
                    detailPageUrl: ""
                });
            }
        }

        events.sort((a, b) => {
            if (a.brand === "RAW" && b.brand !== "RAW") return -1;
            if (b.brand === "RAW" && a.brand !== "RAW") return 1;
            return 0;
        });

        wweCache = { events, matches: wweCache.matches, fetchedAt: Date.now(), ttl: 120000 };
        return sendApiData(response, 200, events.slice(0, 30));
    } catch (e) {
        console.error("[WWE] Events:", e.message || e);
        return sendApiData(response, 200, []);
    }
}

async function handleWweMatches(request, response) {
    try {
        const requestUrl = new URL(request.url, "http://localhost");
        const eventId = requestUrl.searchParams.get("eventId") || "";
        const eventTitle = requestUrl.searchParams.get("eventTitle") || "";
        if (!eventId && !eventTitle) return sendApiData(response, 200, []);
        const key = eventId || slugify(eventTitle);

        // Try to scrape the event page for actual match listing
        if (eventId || eventTitle) {
            const pageUrl = eventId ? eventId.replace(/_/g, "/") : "";
            const fullUrl = pageUrl.startsWith("http") ? "https://" + pageUrl : "";
            if (fullUrl) {
                const html = await fetchWweHtml(fullUrl);
                if (html && html.length > 300) {
                    // Extract match info: look for wrestler names in the page
                    const wrestlerNames = extractWrestlerNames(html);
                    if (wrestlerNames.length >= 2) {
                        const matches = [];
                        for (let i = 0; i < Math.min(wrestlerNames.length, 8); i += 2) {
                            const w1 = wrestlerNames[i];
                            const w2 = wrestlerNames[i + 1] || "TBA";
                            matches.push({
                                matchId: slugify(w1 + "-vs-" + w2) + "-" + i,
                                eventId: eventId || slugify(eventTitle || ""),
                                title: w1 + " vs " + w2,
                                participants: [w1, w2],
                                matchType: "Singles",
                                stipulation: "",
                                isTitleMatch: false,
                                titleName: "",
                                status: "COMPLETED",
                                winner: "",
                                result: "",
                                detailUrl: "",
                                posterUrl: ""
                            });
                        }
                        return sendApiData(response, 200, matches.slice(0, 10));
                    }
                }
            }
        }

        // Fallback: return synthetic matches
        const wrestlers = [
            ["Cody Rhodes", "Roman Reigns"],
            ["Seth Rollins", "CM Punk"],
            ["GUNTHER", "Damian Priest"],
            ["LA Knight", "AJ Styles"],
            ["Rhea Ripley", "Liv Morgan"],
            ["Bianca Belair", "Charlotte Flair"]
        ];
        const isPPV = eventTitle ? ["royal rumble", "wrestlemania", "summerslam", "survivor series",
            "money in the bank", "extreme rules", "hell in a cell"
        ].some(p => eventTitle.toLowerCase().includes(p)) : false;

        const used = new Set();
        const matches = [];
        for (let i = 0; i < (isPPV ? 4 : 3); i++) {
            const available = wrestlers.filter(([a, b]) => !used.has(a) && !used.has(b));
            if (!available.length) break;
            const picked = available[Math.floor(Math.random() * Math.min(available.length, 3))];
            picked.forEach(w => used.add(w));
            const [w1, w2] = picked;
            matches.push({
                matchId: slugify(w1 + "-vs-" + w2) + "-" + i,
                eventId: eventId || slugify(eventTitle || ""),
                title: w1 + " vs " + w2,
                participants: [w1, w2],
                matchType: "Singles",
                stipulation: "",
                isTitleMatch: i === 0,
                titleName: i === 0 ? ["WWE", "World Heavyweight", "Intercontinental"][Math.floor(Math.random() * 3)] + " Championship" : "",
                status: "COMPLETED",
                winner: "",
                result: "",
                detailUrl: "",
                posterUrl: ""
            });
        }
        wweCache.matches[key] = matches;
        return sendApiData(response, 200, matches.slice(0, 10));
    } catch (e) {
        console.error("[WWE] Matches:", e.message || e);
        return sendApiData(response, 200, []);
    }
}

/**
 * Extract wrestler names from a watchwrestling page.
 * Looks for patterns like "wrestler1 VS wrestler2" or listing in match cards.
 */
function extractWrestlerNames(html) {
    const names = [];
    const patterns = [
        /<strong>([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)<\/strong>\s*vs\s*<strong>([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)<\/strong>/gi,
        /([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)\s+(?:VS|vs|Vs)\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)/gi,
        /(?:^|\s)([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)\s+def\.\s+([A-Z][a-z]+(?:\s+[A-Z][a-z]+)*)/gi,
    ];
    for (const pattern of patterns) {
        let match;
        while ((match = pattern.exec(html)) !== null) {
            for (let i = 1; i < match.length; i++) {
                const name = match[i].trim();
                if (name.length > 3 && !names.includes(name)) names.push(name);
            }
        }
    }
    return names;
}

/**
 * Option A: Resolve embed iframe URLs from watchwrestling post pages.
 * Returns an array of embed URLs suitable for MaServerPlayerScreen.
 */
async function handleWweStream(request, response) {
    try {
        const requestUrl = new URL(request.url, "http://localhost");
        const eventId = requestUrl.searchParams.get("event") || requestUrl.searchParams.get("eventId") || "";
        const eventTitle = requestUrl.searchParams.get("title") || "";
        const detailUrl = requestUrl.searchParams.get("detailUrl") || requestUrl.searchParams.get("detailPageUrl") || "";
        if (!eventId && !eventTitle && !detailUrl) return sendApiData(response, 200, []);

        const key = detailUrl || eventId || slugify(eventTitle);
        const now = Date.now();

        // Check cache
        if (wweStreamCache.embedUrls[key] && (now - wweStreamCache.fetchedAt) < wweStreamCache.ttl) {
            return sendApiData(response, 200, wweStreamCache.embedUrls[key]);
        }

        let pageUrl = detailUrl;
        if (!pageUrl && eventId) {
            if (eventId.startsWith("http://") || eventId.startsWith("https://")) {
                pageUrl = eventId;
            } else if (eventId.startsWith("watchwrestling.ae_")) {
                const slug = eventId.replace(/^watchwrestling\.ae_/, "").replace(/_+$/, "");
                pageUrl = `https://watchwrestling.ae/${slug}/`;
            } else {
                pageUrl = `https://watchwrestling.ae/?s=${encodeURIComponent(eventTitle || eventId)}`;
            }
        } else if (!pageUrl && eventTitle) {
            pageUrl = `https://watchwrestling.ae/?s=${encodeURIComponent(eventTitle)}`;
        }

        let html = await fetchWweHtml(pageUrl);
        if (!html || html.length < 200) {
            // If primary URL failed and we have title, try search
            if (eventTitle) {
                const searchUrl = `https://watchwrestling.ae/?s=${encodeURIComponent(eventTitle)}`;
                html = await fetchWweHtml(searchUrl);
            }
        }

        if (!html || html.length < 200) {
            return sendApiData(response, 200, []);
        }

        // Try to find the actual post page if this was a search result
        let postHtml = html;
        let postUrl = pageUrl;
        const postLinkMatch = html.match(/<a[^>]*href=["'](https?:\/\/watchwrestling\.ae\/[^"']+)["'][^>]*>/i);
        if (pageUrl.includes("?s=") && postLinkMatch && postLinkMatch[1]) {
            const foundUrl = postLinkMatch[1];
            const foundHtml = await fetchWweHtml(foundUrl);
            if (foundHtml && foundHtml.length > 300) {
                postHtml = foundHtml;
                postUrl = foundUrl;
            }
        }

        const embedUrls = await extractEmbedUrls(postHtml, postUrl);
        const directUrls = extractDirectStreamUrls(postHtml);

        wweStreamCache.embedUrls[key] = embedUrls;
        wweStreamCache.directUrls[key] = directUrls;
        wweStreamCache.fetchedAt = Date.now();

        return sendApiData(response, 200, embedUrls);
    } catch (e) {
        console.error("[WWE] Stream:", e.message || e);
        return sendApiData(response, 200, []);
    }
}

/**
 * Option B: Resolve direct .m3u8 stream URLs from watchwrestling post pages
 * and sports streaming aggregators.
 *
 * POST /api/wwe/direct-stream
 * Body: { eventId, eventTitle, detailUrl }
 * Returns: { urls: ["https://...m3u8", ...], embedUrls: ["https://...", ...] }
 */
async function handleWweDirectStream(request, response) {
    if (request.method !== "POST") {
        return sendApiError(response, 405, "Direct stream requires POST.");
    }
    try {
        let body;
        try {
            const raw = await new Promise((resolve, reject) => {
                let data = "";
                request.on("data", chunk => data += chunk);
                request.on("end", () => resolve(data));
                request.on("error", reject);
                setTimeout(() => reject(new Error("Body read timeout")), 5000);
            });
            body = raw ? JSON.parse(raw) : {};
        } catch {
            return sendApiError(response, 400, "Invalid JSON body.");
        }

        const eventId = String(body.eventId || "").trim();
        const eventTitle = String(body.eventTitle || "").trim();
        const detailUrl = String(body.detailUrl || body.detailPageUrl || "").trim();
        if (!eventId && !eventTitle && !detailUrl) {
            return sendApiError(response, 400, "eventId, eventTitle, or detailUrl required.");
        }

        const key = detailUrl || eventId || slugify(eventTitle);
        const now = Date.now();

        // Check cache
        if (wweStreamCache.directUrls[key] && (now - wweStreamCache.fetchedAt) < wweStreamCache.ttl) {
            return sendApiData(response, 200, {
                urls: wweStreamCache.directUrls[key],
                embedUrls: wweStreamCache.embedUrls[key] || [],
                message: "Cached stream URLs."
            });
        }

        let pageUrl = detailUrl;
        if (!pageUrl && eventId) {
            if (eventId.startsWith("http://") || eventId.startsWith("https://")) {
                pageUrl = eventId;
            } else if (eventId.startsWith("watchwrestling.ae_")) {
                const slug = eventId.replace(/^watchwrestling\.ae_/, "").replace(/_+$/, "");
                pageUrl = `https://watchwrestling.ae/${slug}/`;
            } else {
                pageUrl = `https://watchwrestling.ae/?s=${encodeURIComponent(eventTitle || eventId)}`;
            }
        } else if (!pageUrl && eventTitle) {
            pageUrl = `https://watchwrestling.ae/?s=${encodeURIComponent(eventTitle)}`;
        }

        const allDirectUrls = [];
        const allEmbedUrls = [];
        let html = await fetchWweHtml(pageUrl);

        if (html && html.length > 200) {
            let postHtml = html;
            let postUrl = pageUrl;
            const postLinkMatch = html.match(/<a[^>]*href=["'](https?:\/\/watchwrestling\.ae\/[^"']+)["'][^>]*>/i);
            if (pageUrl.includes("?s=") && postLinkMatch && postLinkMatch[1]) {
                const found = await fetchWweHtml(postLinkMatch[1]);
                if (found && found.length > 300) {
                    postHtml = found;
                    postUrl = postLinkMatch[1];
                }
            }

            allDirectUrls.push(...extractDirectStreamUrls(postHtml));
            allEmbedUrls.push(...await extractEmbedUrls(postHtml, postUrl));
        }

        // Strategy 2: Try sports streaming aggregators for live wrestling
        const searchQuery = encodeURIComponent((eventTitle || eventId).replace(/[_-]/g, " "));
        if (searchQuery) {
            const streamedUrl = `https://streamed.su/search?q=${searchQuery}`;
            const streamedHtml = await fetchWweHtml(streamedUrl).catch(() => "");
            if (streamedHtml && streamedHtml.length > 200) {
                allDirectUrls.push(...extractDirectStreamUrls(streamedHtml));
                allEmbedUrls.push(...await extractEmbedUrls(streamedHtml, streamedUrl));
            }
        }

        const directUrls = [...new Set(allDirectUrls)].slice(0, 10);
        const embedUrls = [...new Set(allEmbedUrls)].slice(0, 10);

        // Cache
        wweStreamCache.directUrls[key] = directUrls;
        wweStreamCache.embedUrls[key] = embedUrls;
        wweStreamCache.fetchedAt = Date.now();

        return sendApiData(response, 200, {
            urls: directUrls,
            embedUrls: embedUrls,
            message: "Resolved stream URLs."
        });
    } catch (e) {
        console.error("[WWE] Direct stream:", e.message || e);
        return sendApiData(response, 200, { urls: [], embedUrls: [] });
    }
}

async function handleWweBrands(request, response) {
    return sendApiData(response, 200, WWE_BRANDS);
}

async function handleWweSearch(request, response) {
    try {
        const requestUrl = new URL(request.url, "http://localhost");
        const q = (requestUrl.searchParams.get("q") || "").trim().toLowerCase();
        if (!q) return sendApiData(response, 200, []);

        // Refresh events if cache is empty
        if (wweCache.events.length === 0) {
            await handleWweEvents(request, response);
        }

        const filtered = (wweCache.events || []).filter(e =>
            e.title.toLowerCase().includes(q) || e.brand.toLowerCase().includes(q)
        );
        return sendApiData(response, 200, filtered.slice(0, 20));
    } catch (e) {
        console.error("[WWE] Search:", e.message || e);
        return sendApiData(response, 200, []);
    }
}

module.exports = {
    handleWweEvents,
    handleWweMatches,
    handleWweBrands,
    handleWweSearch,
    handleWweStream,
    handleWweDirectStream,
    sendApiError,
    sendApiData
};