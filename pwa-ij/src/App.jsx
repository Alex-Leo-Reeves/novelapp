import React, { useState, useEffect, useCallback } from "react";
import Header from "./components/Header";
import BottomNav from "./components/BottomNav";
import Player from "./components/Player";
import Reader from "./components/Reader";
import Home from "./pages/Home";
import MediaDetail from "./pages/MediaDetail";
import Favorites from "./pages/Favorites";
import "./index.css";

import ErrorBoundary from "./components/ErrorBoundary";

export default function App() {
  const [route, setRoute] = useState(() => parseRoute(window.location.pathname));
  const [player, setPlayer] = useState({ open: false, media: null, episode: null });
  const [reader, setReader] = useState({ open: false, media: null, chapter: null, chapters: [] });
  const [currentCategory, setCurrentCategory] = useState("anime");

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
    const handlePopState = (event) => {
      // 1. If video player is open, back closes player and leaves detail screen intact!
      if (player.open) {
        setPlayer({ open: false, media: null, episode: null });
        return;
      }

      // 2. If novel reader is open, back closes reader and leaves detail screen intact!
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
    const cat = typeof mediaOrId === "object" ? category : encodedId;
    if (cat) setCurrentCategory(cat);
    navigate(`/media/${enc}`, { fromPath: route.path || "/", media });
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
    // Pop the history entry back to media detail without page reload
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
      `/ij/read/${encodeURIComponent(media.id || "novel")}/${chapNum}`
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
  const isNovelsTab = route.path === "/novels";

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
              type: isNovelsTab ? "novels" : currentCategory,
            }}
            onSelectMedia={openMediaDetail}
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
  if (!p || p === "/" || p === "") return { path: "/", ...extraState };
  if (p === "/favorites" || p === "/fav") return { path: "/favorites", ...extraState };
  if (p === "/novels") return { path: "/novels", type: "novels", ...extraState };
  if (p === "/search") return { path: "/search", ...extraState };
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
  return { path: "/", ...extraState };
}
