// Service Worker for "A gift for IJ ❤️" PWA.
// Lightweight, fast runtime cache for static shell & assets.
const CACHE_NAME = "ij-pwa-v3";
const SHELL_URL = "/ij/index.html";

const PRECACHE_URLS = [
  SHELL_URL,
  "/ij/manifest.json",
  "/ij/favicon.svg",
  "/ij/icon.svg"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(PRECACHE_URLS)).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);

  // Never intercept or cache non-GET requests (e.g. POST /api/content/chapters)
  if (request.method !== "GET") {
    return;
  }

  // API calls and video streams bypass the cache: always live network
  if (url.pathname.startsWith("/api/") || url.pathname.includes(".m3u8") || url.pathname.includes(".mp4")) {
    return;
  }

  // SPA Navigation fallback: always return the app shell for /ij/* routes
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request).catch(() => caches.match(SHELL_URL))
    );
    return;
  }

  // Static assets: cache-first with network fallback
  event.respondWith(
    caches.match(request).then((cached) => {
      if (cached) return cached;
      return fetch(request).then((response) => {
        if (response && response.status === 200 && response.type === "basic") {
          const clone = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, clone));
        }
        return response;
      }).catch(() => null);
    })
  );
});
