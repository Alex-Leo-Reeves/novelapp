import React, { useState, useEffect, useRef } from "react";
import { ALL_SERVERS, buildEmbedUrl, parseTmdb, defaultServerFor } from "../data/servers";

export default function Player({ media, episode, onBack, onServerChange }) {
  const [selectedServer, setSelectedServer] = useState(() => media.server || defaultServerFor(media.kind) || "vidsrcto");
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [loading, setLoading] = useState(true);
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

  // Prevent loading overlay from ever getting stuck on screen
  useEffect(() => {
    setLoading(true);
    const timer = setTimeout(() => {
      setLoading(false);
    }, 1200);
    return () => clearTimeout(timer);
  }, [activeUrl, selectedServer]);

  // Protect PWA from top-level ad hijacks when user taps play
  useEffect(() => {
    const handleBeforeUnload = (e) => {
      // Prevents third-party embed scripts from redirecting the main PWA window to ad sites
      e.preventDefault();
      return (e.returnValue = "");
    };
    window.addEventListener("beforeunload", handleBeforeUnload);
    return () => window.removeEventListener("beforeunload", handleBeforeUnload);
  }, []);

  const handleServerClick = (serverKey) => {
    setSelectedServer(serverKey);
    setLoading(true);
    if (onServerChange) onServerChange(serverKey);
  };

  const toggleFullscreen = () => {
    // If native video tag is playing, trigger iOS native fullscreen
    if (videoRef.current && videoRef.current.webkitEnterFullscreen) {
      videoRef.current.webkitEnterFullscreen();
      return;
    }
    // For iframes on iPhone Safari, toggle fixed viewport CSS fullscreen
    setIsFullscreen((prev) => !prev);
  };

  return (
    <div
      className={`player-modal ${isFullscreen ? "fullscreen-mode" : ""}`}
      style={{
        zIndex: isFullscreen ? 999999 : 1000,
        position: "fixed",
        inset: 0,
      }}
    >
      {/* Top Header Bar */}
      <header className="player-top-bar">
        <button className="player-back-btn" onClick={onBack} aria-label="Back to detail">
          ← Back
        </button>
        <div className="player-info">
          <div className="player-title">{media.title}</div>
          <div className="player-subtitle">
            {epNum > 0 ? `Episode ${epNum}` : "Feature Film"} • {selectedServer}
          </div>
        </div>
        <button
          className="player-ctrl-btn"
          onClick={toggleFullscreen}
          aria-label={isFullscreen ? "Exit Fullscreen" : "Fullscreen"}
        >
          {isFullscreen ? "✕" : "⛶"}
        </button>
      </header>

      {/* Server Selector Chips (Default ON) */}
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

      {/* Video Viewport */}
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
            No stream URL available. Try switching server above.
          </div>
        )}

        {/* Transient loading indicator — automatically clears in 1.2s so it NEVER gets stuck */}
        {loading && (
          <div className="player-loader-overlay" style={{ pointerEvents: "none" }}>
            <div className="spinner" />
            <span>Connecting to {selectedServer}…</span>
          </div>
        )}
      </div>

      {/* Bottom Controls Bar & Ad-Defense Notice */}
      <footer className="player-bottom-controls" style={{ flexDirection: "column", gap: 6, alignItems: "stretch" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
          <span style={{ fontSize: "0.82rem", color: "var(--pink-200)", fontWeight: 700 }}>
            ⚡ {selectedServer} (Autoplay Active)
          </span>
          <button className="player-ctrl-btn" onClick={toggleFullscreen}>
            {isFullscreen ? "Exit Fullscreen" : "⛶ Fullscreen"}
          </button>
        </div>
        <div style={{ fontSize: "0.72rem", color: "rgba(255, 255, 255, 0.7)", textAlign: "center" }}>
          💡 Tap server above to switch anytime. If a tap opens an ad tab, close that tab to enjoy continuous playback.
        </div>
      </footer>
    </div>
  );
}
