// ─────────────────────────────────────────────────────────────────────────────
//  YouTube Data API v3 Handlers
//  Uses the YOUTUBE_API_KEY env var set on Render to search YouTube for
//  Nollywood/Nigerian content and return playable stream info.
//  Requires: YOUTUBE_API_KEY environment variable
// ─────────────────────────────────────────────────────────────────────────────

const YOUTUBE_API_KEY = String(process.env.YOUTUBE_API_KEY || process.env.GOOGLE_API_KEY || "").trim();
const YOUTUBE_API_BASE = "https://www.googleapis.com/youtube/v3";

let youtubeSearchCache = { results: {}, fetchedAt: 0, ttl: 300000 }; // 5 min
let youtubeVideoCache = { results: {}, fetchedAt: 0, ttl: 600000 }; // 10 min

function youtubeApiEnabled() {
    return YOUTUBE_API_KEY.length > 0;
}

async function youtubeApiRequest(endpoint, params = {}) {
    if (!youtubeApiEnabled()) {
        throw new Error("YOUTUBE_API_KEY is not configured on the server.");
    }
    const query = new URLSearchParams({ key: YOUTUBE_API_KEY, ...params }).toString();
    const url = `${YOUTUBE_API_BASE}/${endpoint}?${query}`;
    const response = await fetch(url, {
        headers: { "Accept": "application/json" },
        signal: AbortSignal.timeout(10000)
    });
    if (!response.ok) {
        const text = await response.text();
        throw new Error(`YouTube API error (${response.status}): ${text.slice(0, 200)}`);
    }
    return response.json();
}

async function handleYouTubeSearch(request, response) {
    try {
        const requestUrl = new URL(request.url, `http://${request.headers.host || "localhost"}`);
        const query = String(requestUrl.searchParams.get("q") || "").trim();
        const pageToken = String(requestUrl.searchParams.get("pageToken") || "").trim();
        const maxResults = Math.min(50, Math.max(1, Number(requestUrl.searchParams.get("maxResults") || 20)));

        if (!query) {
            return sendApiError(response, 400, "Search query 'q' is required.");
        }
        if (!youtubeApiEnabled()) {
            return sendApiError(response, 503, "YouTube API key is not configured. Set YOUTUBE_API_KEY in environment.");
        }

        const cacheKey = `${query}:${pageToken}:${maxResults}`;
        const now = Date.now();
        if (youtubeSearchCache.results[cacheKey] && (now - youtubeSearchCache.fetchedAt) < youtubeSearchCache.ttl) {
            return sendApiData(response, 200, youtubeSearchCache.results[cacheKey]);
        }

        const params = {
            part: "snippet",
            q: query,
            type: "video",
            maxResults,
            videoDuration: "long",
            videoCategoryId: "24", // Entertainment
            relevanceLanguage: "en",
            regionCode: "NG"
        };
        if (pageToken) params.pageToken = pageToken;

        const payload = await youtubeApiRequest("search", params);
        const items = (payload.items || []).map(item => {
            const snippet = item.snippet || {};
            const videoId = item.id?.videoId || "";
            const thumbnails = snippet.thumbnails || {};
            const bestThumb = thumbnails.maxres || thumbnails.high || thumbnails.medium || thumbnails.default || {};
            return {
                id: `yt_nollywood_${videoId}`,
                videoId: videoId,
                title: snippet.title || "Untitled",
                description: snippet.description || "",
                coverUrl: bestThumb.url || "",
                channelTitle: snippet.channelTitle || "",
                publishedAt: snippet.publishedAt || "",
                channelId: snippet.channelId || ""
            };
        }).filter(item => item.videoId);

        const result = {
            items,
            nextPageToken: payload.nextPageToken || "",
            prevPageToken: payload.prevPageToken || "",
            totalResults: payload.pageInfo?.totalResults || items.length
        };

        youtubeSearchCache.results[cacheKey] = result;
        youtubeSearchCache.fetchedAt = now;

        return sendApiData(response, 200, result);
    } catch (error) {
        console.error("[YouTube] Search error:", error.message || error);
        return sendApiError(response, 500, error.message || "YouTube search failed.");
    }
}

async function handleYouTubeStream(request, response) {
    try {
        const requestUrl = new URL(request.url, `http://${request.headers.host || "localhost"}`);
        const videoId = String(requestUrl.searchParams.get("videoId") || "").trim();

        if (!videoId) {
            return sendApiError(response, 400, "videoId parameter is required.");
        }
        if (!youtubeApiEnabled()) {
            return sendApiError(response, 503, "YouTube API key is not configured.");
        }

        const cacheKey = videoId;
        const now = Date.now();
        if (youtubeVideoCache.results[cacheKey] && (now - youtubeVideoCache.fetchedAt) < youtubeVideoCache.ttl) {
            return sendApiData(response, 200, youtubeVideoCache.results[cacheKey]);
        }

        // Get video details
        const videoPayload = await youtubeApiRequest("videos", {
            part: "snippet,contentDetails",
            id: videoId
        });

        const videoItem = (videoPayload.items || [])[0];
        if (!videoItem) {
            return sendApiError(response, 404, "Video not found.");
        }

        const snippet = videoItem.snippet || {};
        const contentDetails = videoItem.contentDetails || {};
        const duration = parseIso8601Duration(contentDetails.duration || "PT0S");
        const thumbnails = snippet.thumbnails || {};
        const bestThumb = thumbnails.maxres || thumbnails.high || thumbnails.medium || thumbnails.default || {};

        // Return video metadata - the client uses YouTube Player library to play
        const result = {
            id: `yt_nollywood_${videoId}`,
            videoId: videoId,
            title: snippet.title || "Untitled",
            description: snippet.description || "",
            coverUrl: bestThumb.url || "",
            channelTitle: snippet.channelTitle || "",
            publishedAt: snippet.publishedAt || "",
            duration,
            durationIso: contentDetails.duration || "PT0S",
            // The Android YouTube Player library plays natively via the YouTube app/API
            // so we just return the videoId and the native player handles playback
            playbackUrl: `https://www.youtube.com/watch?v=${videoId}`
        };

        youtubeVideoCache.results[cacheKey] = result;
        youtubeVideoCache.fetchedAt = now;

        return sendApiData(response, 200, result);
    } catch (error) {
        console.error("[YouTube] Stream info error:", error.message || error);
        return sendApiError(response, 500, error.message || "YouTube stream info failed.");
    }
}

async function handleYouTubeNollywoodFeed(request, response) {
    try {
        const requestUrl = new URL(request.url, `http://${request.headers.host || "localhost"}`);
        const page = Math.max(1, Number(requestUrl.searchParams.get("page") || 1));
        const pageToken = String(requestUrl.searchParams.get("pageToken") || "").trim();

        if (!youtubeApiEnabled()) {
            return sendApiError(response, 503, "YouTube API key is not configured.");
        }

        // Rotate through Nollywood search queries for variety
        const keywords = [
            "Nigerian movie full length",
            "Nollywood movie 2024 full",
            "Nigerian drama movie",
            "Nollywood latest movie full",
            "Yoruba movie full length",
            "Nigerian comedy movie",
            "Nigerian action movie",
            "Naija movie full",
            "Nollywood romance movie",
            "Nigerian epic movie"
        ];
        const seedIndex = (page - 1) % keywords.length;
        const query = keywords[seedIndex];

        const params = {
            part: "snippet",
            q: query,
            type: "video",
            maxResults: 20,
            videoDuration: "long",
            videoCategoryId: "24",
            relevanceLanguage: "en",
            regionCode: "NG"
        };
        if (pageToken) params.pageToken = pageToken;

        const payload = await youtubeApiRequest("search", params);
        const items = (payload.items || []).map(item => {
            const snippet = item.snippet || {};
            const videoId = item.id?.videoId || "";
            const thumbnails = snippet.thumbnails || {};
            const bestThumb = thumbnails.maxres || thumbnails.high || thumbnails.medium || thumbnails.default || {};
            return {
                id: `yt_nollywood_${videoId}`,
                videoId: videoId,
                title: snippet.title || "Untitled",
                description: snippet.description || "",
                coverUrl: bestThumb.url || "",
                channelTitle: snippet.channelTitle || "",
                publishedAt: snippet.publishedAt || "",
                genres: "Nigerian, Nollywood, YouTube",
                mediaKind: "NIGERIAN",
                sourceName: "YouTube",
                isVideo: true
            };
        }).filter(item => item.videoId);

        return sendApiData(response, 200, {
            items,
            nextPageToken: payload.nextPageToken || "",
            totalResults: payload.pageInfo?.totalResults || items.length
        });
    } catch (error) {
        console.error("[YouTube] Nollywood feed error:", error.message || error);
        return sendApiError(response, 500, error.message || "Nollywood feed failed.");
    }
}

/**
 * Parse ISO 8601 duration (PT1H30M15S) to seconds
 */
function parseIso8601Duration(duration) {
    const match = duration.match(/PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?/);
    if (!match) return 0;
    const hours = parseInt(match[1] || "0", 10);
    const minutes = parseInt(match[2] || "0", 10);
    const seconds = parseInt(match[3] || "0", 10);
    return hours * 3600 + minutes * 60 + seconds;
}

module.exports = {
    handleYouTubeSearch,
    handleYouTubeStream,
    handleYouTubeNollywoodFeed,
    youtubeApiEnabled
};