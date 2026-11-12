import React, { useState, useEffect, useRef } from "react";
import { ALL_SERVERS, buildEmbedUrl, parseTmdb, defaultServerFor, normalizeServerKey } from "../data/servers";

export default function Player({ media, episode, onBack, onServerChange }) {
  const [selectedServer, setSelectedServer] = useState(() =>
    normalizeServerKey(media.server || defaultServerFor(media.kind) || "vidlink")
  );
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [isLandscape, setIsLandscape] = useState(() => {
    if (typeof window === "undefined") return false;
    return window.innerWidth > window.innerHeight;
  });

  const videoRef = useRef(null);
  const iframeRef = useRef(null);

  const epNum = episode ? episode.number || 1 : 1;
  const tmdb = parseTmdb(media.detailUrl);

  // Compute active stream or embed URL
  const activeUrl = React.useMemo(() => {
    if (media.streamUrl && (media.streamUrl.includes(".m3u8") || media.streamUrl.includes(".mp4"))) {
      return media.streamUrl;
    }
    if (tmdb) {
      return buildEmbedUrl(selectedServer, tmdb.type, tmdb.id, "1", String(epNum));
    }
    return media.streamUrl || "";
  }, [media.streamUrl, media.detailUrl, tmdb, selectedServer, epNum]);

  const isDirectVideo = activeUrl.includes(".m3u8") || activeUrl.includes(".mp4");

  // Track orientation changes (e.g. autorotate on iPhone)
  useEffect(() => {
    const handleOrientation = () => {
      const landscape = window.innerWidth > window.innerHeight;
      setIsLandscape(landscape);
    };

    window.addEventListener("resize", handleOrientation);
    window.addEventListener("orientationchange", handleOrientation);
    if (window.screen && window.screen.orientation) {
      window.screen.orientation.addEventListener?.("change", handleOrientation);
    }
    return () => {
      window.removeEventListener("resize", handleOrientation);
      window.removeEventListener("orientationchange", handleOrientation);
      if (window.screen && window.screen.orientation) {
        window.screen.orientation.removeEventListener?.("change", handleOrientation);
      }
    };
  }, []);

  // Prevent loading overlay from ever getting stuck on screen (failsafe)
  useEffect(() => {
    setLoading(true);
    const timer = setTimeout(() => {
      setLoading(false);
    }, 1800);
    return () => clearTimeout(timer);
  }, [activeUrl, selectedServer]);

  // Pure logic: Auto-refocus window if an iframe tries to spawn a background popunder
  useEffect(() => {
    const handleBlur = () => {
      setTimeout(() => {
        try { window.focus(); } catch (_) {}
      }, 50);
    };
    window.addEventListener("blur", handleBlur);
    return () => window.removeEventListener("blur", handleBlur);
  }, []);

  // Pure logic: Protect PWA from top-level ad hijacks / redirects
  useEffect(() => {
    const handleBeforeUnload = (e) => {
      e.preventDefault();
      return (e.returnValue = "");
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, []);

  const handleServerClick = (serverKey) => {
    const norm = normalizeServerKey(serverKey);
    setSelectedServer(norm);
    setLoading(true);
    if (onServerChange) onServerChange(norm);
  };

  const toggleFullscreen = () => {
    // If native video tag is playing, trigger iOS native fullscreen
    if (videoRef.current && videoRef.current.webkitEnterFullscreen) {
      videoRef.current.webkitEnterFullscreen();
      return;
    }
    setIsFullscreen((prev) => !prev);
  };

  const currentServerObj = ALL_SERVERS.find((s) => s.key === selectedServer) || {
    displayName: selectedServer,
  };

  const inCinemaMode = isFullscreen || isLandscape;

  return (
    <div className={`player-modal ${inCinemaMode ? "fullscreen-mode" : "portrait-mode"}`}>
      {/* In Fullscreen/Cinema mode: only a tiny floating back button in top-left safe area,
          zero overlapping bars or touch blockers so iframe player controls are 100% accessible! */}
      {inCinemaMode && (
        <button
          className="player-floating-back-btn"
          onClick={() => {
            if (isFullscreen) setIsFullscreen(false);
            else onBack();
          }}
          aria-label="Exit fullscreen or back"
        >
          ←
        </button>
      )}

      {/* Top Header Bar (Only shown in Portrait Mode - sits cleanly above the video) */}
      {!inCinemaMode && (
        <header className="player-top-bar">
          <button className="player-back-btn" onClick={onBack} aria-label="Back to detail">
            ← Back
          </button>
          <div className="player-info">
            <div className="player-title">{media.title}</div>
            <div className="player-subtitle">
              {epNum > 0 ? `Episode ${epNum}` : "Feature Film"} • {currentServerObj.displayName}
            </div>
          </div>
          <button
            className="player-ctrl-btn"
            onClick={toggleFullscreen}
            aria-label="Fullscreen"
          >
            ⛶ Full
          </button>
        </header>
      )}

      {/* Video Viewport: 100% Direct & Unobstructed Touch - ZERO overlay interference! */}
      <div className="player-viewport">
        {isDirectVideo ? (
          <video
            ref={videoRef}
            src={activeUrl}
            className="player-video-tag"
            controls
            playsInline
            webkit-playsinline="true"
            autoPlay
            onLoadedData={() => setLoading(false)}
            onError={() => setLoading(false)}
          />
        ) : activeUrl ? (
          <iframe
            ref={iframeRef}
            key={activeUrl}
            src={activeUrl}
            className="player-iframe"
            title="Media player"
            allow="autoplay *; fullscreen *; encrypted-media *; picture-in-picture *"
            allowFullScreen
            playsInline
            webkit-playsinline="true"
            onLoad={() => setLoading(false)}
          />
        ) : (
          <div style={{ color: "#ffffff", textAlign: "center", padding: 20 }}>
            No stream URL available. Try switching server below.
          </div>
        )}

        {/* Temporary Loading indicator that automatically vanishes */}
        {loading && (
          <div className="player-loader-overlay">
            <div className="spinner" />
            <span>Connecting to {currentServerObj.displayName}…</span>
          </div>
        )}
      </div>

      {/* Server Selector Section (Positioned cleanly BELOW the video in portrait mode so it never blocks playback) */}
      {!inCinemaMode && (
        <div className="player-below-viewport">
          <div className="player-server-selector-pill">
            {ALL_SERVERS.map((srv) => (
              <button
                key={srv.key}
                onClick={() => handleServerClick(srv.key)}
                className={`player-server-chip ${selectedServer === srv.key ? "active" : ""}`}
              >
                {srv.displayName}
              </button>
            ))}
          </div>
          <div className="player-tip-text">
            💡 Tap the player's fullscreen button or rotate your iPhone for edge-to-edge cinema playback.
          </div>
        </div>
      )}
    </div>
  );
}
