import React, { useState, useEffect, useCallback } from "react";
import Header from "./components/Header";
import BottomNav from "./components/BottomNav";
import Player from "./components/Player";
import Reader from "./components/Reader";
import Home from "./pages/Home";
import MediaDetail from "./pages/MediaDetail";
import Favorites from "./pages/Favorites";
import LockScreen from "./components/LockScreen";
import "./index.css";

import ErrorBoundary from "./components/ErrorBoundary";

export default function App() {
  // Authentication: matches Ij desktop password ("micheal and ij" / "michael and ij")
  // Automatically unlocks if user arrives from the authenticated Ij desktop page (?auth=...)
  const [isUnlocked, setIsUnlocked] = useState(() => {
    try {
      // 1. If already authenticated in this session, keep unlocked
      if (sessionStorage.getItem("ij_unlocked") === "true") {
        return true;
      }
      // 2. If coming from the authenticated Ij desktop app
      const params = new URLSearchParams(window.location.search);
      const authParam = params.get("auth");
      if (
        authParam === "micheal_and_ij_sanctuary" ||
        authParam === "micheal_and_ij" ||
        authParam === "ij_verified"
      ) {
        sessionStorage.setItem("ij_unlocked", "true");
        // Clean up URL query parameters so the address bar stays clean
        try {
          const url = new URL(window.location.href);
          url.searchParams.delete("auth");
          window.history.replaceState({}, "", url.pathname + (url.search ? "?" + url.searchParams.toString() : "") + url.hash);
        } catch (_) {}
        return true;
      }
    } catch (_) {}
    return false;
  });

  const [route, setRoute] = useState(() => parseRoute(window.location.pathname));
  const [player, setPlayer] = useState({ open: false, media: null, episode: null });
  const [reader, setReader] = useState({ open: false, media: null, chapter: null, chapters: [] });

  // Register PWA service worker
  useEffect(() => {
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker
        .register("/ij/service-worker.js", { scope: "/ij/" })
        .then(() => console.log("[PWA] ServiceWorker active for IJ"))
        .catch((err) => console.warn("[PWA] ServiceWorker error:", err));
    }
  }, []);

  // Multi-tier backward button & swipe-back protection:
  // Never suddenly jump to home when user presses back from Player, Reader, or Detail!
  useEffect(() => {
    const handlePopState = () => {
      // 1. If video player is open, back closes player and leaves detail screen intact!
      if (player.open) {
        setPlayer({ open: false, media: null, episode: null });
        return;
      }

      // 2. If novel/manga reader is open, back closes reader and leaves detail screen intact!
      if (reader.open) {
        setReader({ open: false, media: null, chapter: null, chapters: [] });
        return;
      }

      // 3. Otherwise update the route
      const nextRoute = parseRoute(window.location.pathname);
      setRoute(nextRoute);
    };

    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, [player.open, reader.open]);

  const navigate = useCallback((path, state = {}) => {
    const fullPath = path.startsWith("/ij") ? path : `/ij${path.startsWith("/") ? path : `/${path}`}`;
    window.history.pushState(state, "", fullPath);
    setRoute(parseRoute(fullPath, state));
  }, []);

  const openMediaDetail = useCallback((mediaOrId, encodedId, category) => {
    const media = typeof mediaOrId === "object" ? mediaOrId : null;
    const enc = typeof mediaOrId === "object" ? encodedId : mediaOrId;
    navigate(`/media/${enc}`, { fromPath: route.path || "/", media, category });
  }, [navigate, route.path]);

  const openPlayer = useCallback((media, episode) => {
    setPlayer({ open: true, media, episode });
    const epNum = episode ? episode.number || 1 : 1;
    // Push player state so browser/swipe back closes player instead of leaving app
    window.history.pushState(
      { screen: "player", mediaId: media.id, epNum },
      "",
      `/ij/play/${encodeURIComponent(media.id || "stream")}/${epNum}`
    );
  }, []);

  const closePlayer = useCallback(() => {
    setPlayer({ open: false, media: null, episode: null });
    if (window.location.pathname.includes("/play/")) {
      window.history.back();
    }
  }, []);

  const openReader = useCallback((media, chapter, chapters = []) => {
    setReader({ open: true, media, chapter, chapters });
    const chapNum = chapter ? chapter.number || 1 : 1;
    window.history.pushState(
      { screen: "reader", mediaId: media.id, chapNum },
      "",
      `/ij/read/${encodeURIComponent(media.id || "reading")}/${chapNum}`
    );
  }, []);

  const closeReader = useCallback(() => {
    setReader({ open: false, media: null, chapter: null, chapters: [] });
    if (window.location.pathname.includes("/read/")) {
      window.history.back();
    }
  }, []);

  const isMediaDetail = route.path === "/media";
  const isFavorites = route.path === "/favorites";

  // Compute active type for Home view:
  const activeHomeType = route.type ||
    (route.path === "/novels" ? "novels" :
     route.path === "/manga" ? "manga" :
     "all");

  if (!isUnlocked) {
    return <LockScreen onUnlock={() => setIsUnlocked(true)} />;
  }

  return (
    <ErrorBoundary>
      <div className="app-shell">
        {/* Header bar */}
        <Header route={route} navigate={navigate} />

        {/* Main Screen View */}
        {isMediaDetail ? (
          <MediaDetail
            route={route}
            navigate={navigate}
            onOpenPlayer={openPlayer}
            onOpenReader={openReader}
          />
        ) : isFavorites ? (
          <Favorites onSelectMedia={openMediaDetail} />
        ) : (
          <Home
            route={{
              ...route,
              type: activeHomeType,
            }}
            onSelectMedia={openMediaDetail}
            onNavigateTab={navigate}
          />
        )}

        {/* Fixed Bottom Navigation (hidden when in MediaDetail for clean screen) */}
        {!isMediaDetail && <BottomNav route={route} navigate={navigate} />}

        {/* Video Player Modal Overlay */}
        {player.open && (
          <Player
            media={player.media}
            episode={player.episode}
            onBack={closePlayer}
          />
        )}

        {/* Novel & Manga Reader Modal Overlay */}
        {reader.open && (
          <Reader
            media={reader.media}
            chapter={reader.chapter}
            chapters={reader.chapters}
            onBack={closeReader}
            onSelectChapter={(nextChap) => {
              setReader((prev) => ({ ...prev, chapter: nextChap }));
            }}
          />
        )}
      </div>
    </ErrorBoundary>
  );
}

function parseRoute(pathname, extraState = {}) {
  const base = "/ij";
  let p = pathname.startsWith(base) ? pathname.slice(base.length) : pathname;
  if (!p || p === "/" || p === "") return { path: "/", type: "all", ...extraState };
  if (p === "/favorites" || p === "/fav") return { path: "/favorites", ...extraState };
  if (p === "/novels") return { path: "/novels", type: "novels", ...extraState };
  if (p === "/manga") return { path: "/manga", type: "manga", ...extraState };
  if (p === "/search") return { path: "/search", type: "all", ...extraState };
  if (p.startsWith("/media/")) {
    const parts = p.split("/").filter(Boolean);
    return { path: "/media", id: parts[1] || "", ...extraState };
  }
  if (p.startsWith("/play/")) {
    const parts = p.split("/").filter(Boolean);
    return { path: "/play", mediaId: parts[1], epNum: parts[2] || "1", ...extraState };
  }
  if (p.startsWith("/read/")) {
    const parts = p.split("/").filter(Boolean);
    return { path: "/read", mediaId: parts[1], chapNum: parts[2] || "1", ...extraState };
  }
  return { path: "/", type: "all", ...extraState };
}
