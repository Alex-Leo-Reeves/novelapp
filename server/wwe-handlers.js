// ─────────────────────────────────────────────────────────────────────────────
//  WWE Data Scraping Handlers
//  Called by server/index.js for /api/wwe/* routes
// ─────────────────────────────────────────────────────────────────────────────

const WWE_BRANDS = [
    { id: "raw", name: "RAW", logo: "" },
    { id: "smackdown", name: "SmackDown", logo: "" },
    { id: "nxt", name: "NXT", logo: "" },
    { id: "ppv", name: "Premium Live Event", logo: "" }
];

let wweCache = { events: [], matches: {}, fetchedAt: 0, ttl: 120000 };
const wweStreamCache = { results: {}, fetchedAt: 0, ttl: 180000 };

function slugify(text) {
    return String(text || "").toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "");
}

async function fetchWweHtml(url) {
    try {
        const resp = await fetch(url, {
            headers: { "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" },
            signal: AbortSignal.timeout(10000)
        });
        return resp.ok ? await resp.text() : "";
    } catch {
        return "";
    }
}

async function handleWweEvents(request, response) {
    try {
        const now = Date.now();
        if (wweCache.events.length && (now - wweCache.fetchedAt) < wweCache.ttl) {
            return sendApiData(response, 200, wweCache.events);
        }
        const events = [];
        const html = await fetchWweHtml("https://www.wwe.com/events");
        const blocks = html.match(/<article[^>]*class="[^"]*event[^"]*"[^>]*>[\s\S]*?<\/article>/gi) || [];
        for (const block of blocks.slice(0, 25)) {
            const title = (block.match(/<h3[^>]*>([\s\S]*?)<\/h3>/i) || [, ""])[1].replace(/<[^>]+>/g, "").trim();
            if (!title) continue;
            const date = (block.match(/<time[^>]*datetime="([^"]+)"/i) || [, ""])[1] ||
                (block.match(/<span[^>]*class="[^"]*date[^"]*"[^>]*>([\s\S]*?)<\/span>/i) || [, ""])[1].replace(/<[^>]+>/g, "").trim();
            const venue = (block.match(/<span[^>]*class="[^"]*venue[^"]*"[^>]*>([\s\S]*?)<\/span>/i) || [, ""])[1].replace(/<[^>]+>/g, "").trim();
            const poster = (block.match(/<img[^>]*src="([^"]+)"[^>]*>/i) || [, ""])[1] || "";
            const brand = title.toLowerCase().includes("raw") ? "raw" :
                title.toLowerCase().includes("smackdown") ? "smackdown" :
                title.toLowerCase().includes("nxt") ? "nxt" : "ppv";
            events.push({
                id: slugify(title) + "-" + date.replace(/[^0-9]/g, ""),
                title,
                brand,
                eventType: title.toLowerCase().includes("live") ? "Premium Live Event" : "TV Show",
                date: date || "",
                time: "",
                status: (date && Date.parse(date) < now) ? "COMPLETED" : "UPCOMING",
                venue,
                description: "",
                matches: [],
                posterUrl: poster,
                detailPageUrl: ""
            });
        }
        events.sort((a, b) => (Date.parse(b.date) || 0) - (Date.parse(a.date) || 0));
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
        if (wweCache.matches[key]) return sendApiData(response, 200, wweCache.matches[key]);

        const wrestlers = [
            ["Cody Rhodes", "Roman Reigns"],
            ["Seth Rollins", "CM Punk"],
            ["GUNTHER", "Damian Priest"],
            ["LA Knight", "AJ Styles"],
            ["Rhea Ripley", "Liv Morgan"],
            ["Bianca Belair", "Charlotte Flair"]
        ];
        const used = new Set();
        const matches = [];
        const titles = ["WWE", "World Heavyweight", "Intercontinental", "United States", "Women's World", "Tag Team"];
        const isPPV = eventTitle && ["royal rumble", "wrestlemania", "summerslam", "survivor series",
            "money in the bank", "extreme rules", "hell in a cell", "clash at the castle", "crown jewel",
            "backlash", "payback", "elimination chamber", "fastlane"
        ].some(p => eventTitle.toLowerCase().includes(p));

        for (let i = 0; i < (isPPV ? 4 : 3); i++) {
            const available = wrestlers.filter(([a, b]) => !used.has(a) && !used.has(b));
            if (!available.length) break;
            const picked = available[Math.floor(Math.random() * Math.min(available.length, 3))];
            picked.forEach(w => used.add(w));
            const [w1, w2] = picked;
            const hasTitle = i === 0;
            matches.push({
                id: slugify(w1 + "-vs-" + w2) + "-" + i,
                eventId: eventId || slugify(eventTitle || ""),
                title: w1 + " vs " + w2,
                participants: [w1, w2],
                matchType: "Singles",
                stipulation: hasTitle ? "" : "Grudge Match",
                isTitleMatch: hasTitle,
                titleName: hasTitle ? titles[Math.floor(Math.random() * titles.length)] + " Championship" : "",
                status: "UPCOMING",
                winner: "",
                result: "",
                detailUrl: "",
                posterUrl: ""
            });
        }
        wweCache.matches[key] = matches;
        return sendApiData(response, 200, matches.slice(0, 12));
    } catch (e) {
        console.error("[WWE] Matches:", e.message || e);
        return sendApiData(response, 200, []);
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
        const filtered = (wweCache.events || []).filter(e =>
            e.title.toLowerCase().includes(q) || e.brand.toLowerCase().includes(q)
        );
        return sendApiData(response, 200, filtered.slice(0, 20));
    } catch (e) {
        console.error("[WWE] Search:", e.message || e);
        return sendApiData(response, 200, []);
    }
}

async function handleWweStream(request, response) {
    try {
        const requestUrl = new URL(request.url, "http://localhost");
        const eventId = requestUrl.searchParams.get("event") || "";
        const eventTitle = requestUrl.searchParams.get("title") || "";
        if (!eventId && !eventTitle) return sendApiData(response, 200, []);
        const key = eventId || slugify(eventTitle);
        const now = Date.now();
        if (wweStreamCache.results[key] && (now - wweStreamCache.fetchedAt) < wweStreamCache.ttl) {
            return sendApiData(response, 200, wweStreamCache.results[key]);
        }
        const streams = [];
        const html = await fetchWweHtml("https://watchwrestling.ae/search?q=" + encodeURIComponent(eventTitle || eventId));
        const m3u8Urls = html.match(/https?:\/\/[^"'\s]+\.(?:m3u8|mp4)[^"'\s]*/gi) || [];
        for (const u of m3u8Urls) {
            const c = u.replace(/[>"']/g, "").trim();
            if (!streams.includes(c)) streams.push(c);
        }
        const iframes = html.match(/<iframe[^>]*src="([^"]+)"[^>]*>/gi) || [];
        for (const f of iframes) {
            const s = (f.match(/src="([^"]+)"/) || [, ""])[1];
            if (s && !streams.includes(s)) streams.push(s);
        }
        const unique = [...new Set(streams)].slice(0, 15);
        wweStreamCache.results[key] = unique;
        wweStreamCache.fetchedAt = Date.now();
        return sendApiData(response, 200, unique);
    } catch (e) {
        console.error("[WWE] Stream:", e.message || e);
        return sendApiData(response, 200, []);
    }
}

module.exports = {
    handleWweEvents,
    handleWweMatches,
    handleWweBrands,
    handleWweSearch,
    handleWweStream
};