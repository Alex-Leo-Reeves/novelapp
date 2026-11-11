// API service layer for "A gift for IJ ❤️"
// Connects directly to novelapp's backend endpoints without CORS issues.
// Includes offline fallbacks so the app works seamlessly even without an active internet connection.

import { FEATURED, NOVEL_FIXTURES } from "./fixtures";

export const MEDIA_TYPES = [
  { key: "anime", label: "Anime" },
  { key: "movies", label: "Movies" },
  { key: "kdrama", label: "K-Drama" },
  { key: "donghua", label: "Donghua" },
  { key: "novels", label: "Novels" },
  { key: "manga", label: "Manga" },
  { key: "cartoon", label: "Cartoon" },
  { key: "classic", label: "Classic" },
  { key: "nigerian", label: "Nigerian" },
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

export async function homeContent(type = "anime", page = 1) {
  try {
    const data = await apiGet("/api/content/home", { type, page });
    const items = itemsOf(data);
    if (items.length > 0) return items;
  } catch (err) {
    console.warn(`[API] homeContent error for ${type}:`, err.message);
  }
  // Return offline curated fixtures if network/endpoint fails
  return getOfflineItems(type);
}

export async function searchContent(query, type = "anime", page = 1) {
  try {
    const data = await apiGet("/api/content/search", { q: query, type, page });
    const items = itemsOf(data);
    if (items.length > 0) return items;
  } catch (err) {
    console.warn(`[API] searchContent error for "${query}":`, err.message);
  }
  // Fallback search on fixtures
  const offline = getOfflineItems(type);
  const q = (query || "").toLowerCase();
  return offline.filter((item) => (item.title || "").toLowerCase().includes(q));
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
  if (type === "novels") return NOVEL_FIXTURES || [];
  return (FEATURED || []).filter((item) => (item.kind || "anime") === type);
}

function generateFallbackChapters(kind, title) {
  const isVideo = ["anime", "movies", "kdrama", "donghua", "cartoon"].includes(kind);
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
