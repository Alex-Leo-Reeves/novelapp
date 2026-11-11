// API service layer for "A gift for IJ ❤️"
// Connects directly to novelapp's backend endpoints without CORS issues.
// Includes offline fallbacks and deduplication so the app works seamlessly.

import { FEATURED, NOVEL_FIXTURES, MANGA_FIXTURES } from "./fixtures";

export const MEDIA_TYPES = [
  { key: "anime", label: "Anime" },
  { key: "movies", label: "Movies" },
  { key: "kdrama", label: "K-Drama" },
  { key: "donghua", label: "Donghua" },
  { key: "novels", label: "Novels" },
  { key: "manga", label: "Manga" },
  { key: "nigerian", label: "Nigerian" },
  { key: "cartoon", label: "Cartoon" },
  { key: "classic", label: "Classic" },
];

async function apiGet(path, params = {}) {
  const url = new URL(path, window.location.origin);
  Object.entries(params).forEach(([k, v]) => {
    if (v != null && v !== "") url.searchParams.set(k, String(v));
  });

  const res = await fetch(url.toString(), { credentials: "same-origin" });
  if (!res.ok) throw new Error(`API ${path} failed with status ${res.status}`);
  const json = await res.json();
  if (!json.ok) throw new Error(json.error || `API ${path} error`);
  return json.data;
}

async function apiPost(path, body = {}) {
  const res = await fetch(new URL(path, window.location.origin).toString(), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    credentials: "same-origin",
  });
  if (!res.ok) throw new Error(`API POST ${path} failed with status ${res.status}`);
  const json = await res.json();
  if (!json.ok) throw new Error(json.error || `API POST ${path} error`);
  return json.data;
}

const itemsOf = (data) => {
  if (!data) return [];
  if (Array.isArray(data)) return data;
  if (data.items && Array.isArray(data.items)) return data.items;
  return [];
};

function dedupeItems(items) {
  const seen = new Set();
  return items.filter((item) => {
    const key = (item.detailUrl || item.id || item.title || "").toLowerCase().trim();
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

export async function homeContent(type = "anime", page = 1) {
  try {
    const data = await apiGet("/api/content/home", { type, page });
    const items = itemsOf(data);
    
    // For novels, merge with our rich curated library if live scraper returned few items
    if (type === "novels" || type === "novel") {
      const combined = [...items, ...(NOVEL_FIXTURES || [])];
      return dedupeItems(combined);
    }
    
    // For manga, merge with curated manga fixtures
    if (type === "manga") {
      const combined = [...items, ...(MANGA_FIXTURES || [])];
      return dedupeItems(combined);
    }

    if (items.length > 0) return dedupeItems(items);
  } catch (err) {
    console.warn(`[API] homeContent error for ${type}:`, err.message);
  }
  // Return offline curated fixtures if network/endpoint fails
  return getOfflineItems(type);
}

// Fetches TV-app styled rows for the Discover screen
export async function homeContentRows() {
  const rowDefs = [
    { key: "anime", title: "Trending Anime", icon: "🔥", type: "anime" },
    { key: "movies", title: "Blockbuster Movies", icon: "🎬", type: "movies" },
    { key: "kdrama", title: "Romantic K-Dramas", icon: "🌸", type: "kdrama" },
    { key: "donghua", title: "Epic Donghua", icon: "🐉", type: "donghua" },
    { key: "novels", title: "Bestselling Novels", icon: "📖", type: "novels" },
    { key: "manga", title: "Trending Manga", icon: "📚", type: "manga" },
    { key: "nigerian", title: "Nollywood Hits", icon: "🇳🇬", type: "nigerian" },
  ];

  const results = await Promise.all(
    rowDefs.map(async (row) => {
      try {
        const items = await homeContent(row.type, 1);
        return {
          ...row,
          items: items.slice(0, 16),
        };
      } catch (_) {
        return {
          ...row,
          items: getOfflineItems(row.type).slice(0, 12),
        };
      }
    })
  );

  return results.filter((r) => r.items && r.items.length > 0);
}

export async function searchContent(query, type = "all", page = 1) {
  try {
    const data = await apiGet("/api/content/search", { q: query, type, page });
    const items = itemsOf(data);
    if (items.length > 0) return dedupeItems(items);
  } catch (err) {
    console.warn(`[API] searchContent error for "${query}":`, err.message);
  }
  // Fallback search on fixtures
  const q = (query || "").toLowerCase();
  const allOffline = [
    ...(FEATURED || []),
    ...(NOVEL_FIXTURES || []),
    ...(MANGA_FIXTURES || []),
  ];
  return allOffline.filter((item) => {
    const matchesQ = (item.title || "").toLowerCase().includes(q) ||
                     (item.synopsis || "").toLowerCase().includes(q);
    if (type === "all" || !type) return matchesQ;
    return matchesQ && ((item.kind || "").toLowerCase() === type.toLowerCase());
  });
}

export async function getChapters(kind, detailUrl, title, sourceName) {
  try {
    const data = await apiPost("/api/content/chapters", {
      kind,
      detailUrl,
      title,
      sourceName: sourceName || "default",
    });
    if (Array.isArray(data)) return data;
    if (data && data.chapters) return data.chapters;
  } catch (err) {
    console.warn(`[API] getChapters error:`, err.message);
  }
  // Fallback default chapters if backend is offline
  return generateFallbackChapters(kind, title);
}

export async function getChapterText(chapterUrl, title, sourceName) {
  try {
    const data = await apiPost("/api/content/chapter-text", {
      chapterUrl,
      title,
      sourceName: sourceName || "default",
    });
    if (data && data.text) return data.text;
  } catch (err) {
    console.warn(`[API] getChapterText error:`, err.message);
  }
  return `Chapter text for "${title}" is currently unavailable offline. Please ensure your device is connected to the internet.`;
}

export async function getMangaPages(chapterUrl) {
  try {
    const data = await apiPost("/api/content/manga-pages", { chapterUrl });
    if (data && Array.isArray(data.pages)) return data.pages;
  } catch (err) {
    console.warn(`[API] getMangaPages error:`, err.message);
  }
  return [];
}

export async function getEpisodes(query, provider = "hianime") {
  try {
    const data = await apiGet("/api/content/anime/episodes", { q: query, provider });
    return itemsOf(data && data.episodes ? { episodes: data.episodes } : data);
  } catch (err) {
    console.warn(`[API] getEpisodes error:`, err.message);
    return [];
  }
}

export async function getMediaStream({ kind, id, season = "1", episode = "1", provider = "vidlink" }) {
  try {
    const mediaType = kind === "movie" ? "movie" : "tv";
    return await apiGet("/api/content/stream", {
      type: mediaType,
      id,
      season,
      episode,
      provider,
    });
  } catch (err) {
    console.warn(`[API] getMediaStream error:`, err.message);
    return null;
  }
}

function getOfflineItems(type) {
  if (type === "novels" || type === "novel") return NOVEL_FIXTURES || [];
  if (type === "manga") return MANGA_FIXTURES || [];
  return (FEATURED || []).filter((item) => (item.kind || "anime") === type);
}

function generateFallbackChapters(kind, title) {
  const isVideo = ["anime", "movies", "kdrama", "donghua", "cartoon", "classic", "nigerian"].includes(kind);
  const count = isVideo ? 12 : 25;
  const list = [];
  for (let i = 1; i <= count; i++) {
    list.push({
      number: i,
      title: isVideo ? `Episode ${i}` : `Chapter ${i}`,
      url: `fallback://${kind}/${encodeURIComponent(title || "item")}/${i}`,
      id: `fallback-${i}`,
    });
  }
  return list;
}
