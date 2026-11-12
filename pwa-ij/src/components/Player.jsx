import React, { useState, useEffect, useRef, useCallback } from "react";
import { ALL_SERVERS, buildEmbedUrl, parseTmdb, defaultServerFor, normalizeServerKey } from "../data/servers";

export default function Player({ media, episode, onBack, onServerChange }) {
  const [selectedServer, setSelectedServer] = useState(() =>
    normalizeServerKey(media.server || defaultServerFor(media.kind) || "vidsrcto")
  );
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [loading, setLoading] = useState(true);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [directTouchMode, setDirectTouchMode] = useState(false);
  const [directTouchCountdown, setDirectTouchCountdown] = useState(0);
  const [isLandscape, setIsLandscape] = useState(() => {
    if (typeof window === "undefined") return false;
    return window.innerWidth > window.innerHeight;
  });

  const videoRef = useRef(null);
  const iframeRef = useRef(null);
  const hideTimerRef = useRef(null);
  const directTouchTimerRef = useRef(null);

  const epNum = episode ? episode.number || 1 : 1;
  const tmdb = parseTmdb(media.detailUrl);

  // Direct touch timer logic (lets user temporarily interact with iframe controls e.g. subtitles)
  const enableDirectTouch = useCallback((seconds = 15) => {
    setDirectTouchMode(true);
    setDirectTouchCountdown(seconds);
    if (directTouchTimerRef.current) clearInterval(directTouchTimerRef.current);

    directTouchTimerRef.current = setInterval(() => {
      setDirectTouchCountdown((prev) => {
        if (prev <= 1) {
          clearInterval(directTouchTimerRef.current);
          setDirectTouchMode(false);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
  }, []);

  const disableDirectTouch = useCallback(() => {
    if (directTouchTimerRef.current) clearInterval(directTouchTimerRef.current);
    setDirectTouchMode(false);
    setDirectTouchCountdown(0);
  }, []);

  useEffect(() => {
    return () => {
      if (directTouchTimerRef.current) clearInterval(directTouchTimerRef.current);
    };
  }, []);

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

  // Reset & manage controls auto-hide timer in landscape or fullscreen mode
  const resetHideTimer = useCallback(() => {
    setControlsVisible(true);
    if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    if (isFullscreen || isLandscape) {
      hideTimerRef.current = setTimeout(() => {
        setControlsVisible(false);
      }, 4000);
    }
  }, [isFullscreen, isLandscape]);

  useEffect(() => {
    resetHideTimer();
    return () => {
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    };
  }, [isFullscreen, isLandscape, resetHideTimer]);

  // Handle taps on shield to toggle overlay controls without ever touching iframe
  const handleShieldTap = (e) => {
    if (e) {
      e.preventDefault();
      e.stopPropagation();
    }
    if (controlsVisible) {
      setControlsVisible(false);
      if (hideTimerRef.current) clearTimeout(hideTimerRef.current);
    } else {
      resetHideTimer();
    }
  };

  // Prevent loading overlay from ever getting stuck on screen (failsafe)
  useEffect(() => {
    setLoading(true);
    const timer = setTimeout(() => {
      setLoading(false);
    }, 2200);
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
    const norm = normalizeServerKey(serverKey);
    setSelectedServer(norm);
    setLoading(true);
    resetHideTimer();
    if (onServerChange) onServerChange(norm);
  };

  const toggleFullscreen = () => {
    // If native video tag is playing, trigger iOS native fullscreen
    if (videoRef.current && videoRef.current.webkitEnterFullscreen) {
      videoRef.current.webkitEnterFullscreen();
      return;
    }
    // For iframes on iPhone Safari, toggle fixed viewport CSS fullscreen
    setIsFullscreen((prev) => {
      const next = !prev;
      resetHideTimer();
      return next;
    });
  };

  const currentServerObj = ALL_SERVERS.find((s) => s.key === selectedServer) || {
    displayName: selectedServer,
  };

  return (
    <div
      className={`player-modal ${isFullscreen ? "fullscreen-mode" : ""} ${
        isLandscape ? "is-landscape" : ""
      } ${!controlsVisible ? "controls-hidden" : ""}`}
    >
      {/* Top Header Bar */}
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
          aria-label={isFullscreen ? "Exit Fullscreen" : "Fullscreen"}
        >
          {isFullscreen ? "✕ Exit" : "⛶ Full"}
        </button>
      </header>

      {/* Server Selector Chips */}
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

      {/* Video Viewport: Edge-to-Edge Fullscreen on iPhone */}
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

        {/* Anti-Trap Tap Shield:
            When controls are hidden OR direct touch is not active,
            intercepts all taps/clicks so they wake up the HUD instantly
            WITHOUT EVER REACHING the iframe's ad-trapped click listeners! */}
        {!directTouchMode && !loading && (
          <div
            className={`player-screen-touch-shield ${!controlsVisible ? "is-hidden-mode" : "is-visible-mode"}`}
            onClick={handleShieldTap}
            onTouchEnd={handleShieldTap}
            aria-label="Toggle player controls"
          >
            {/* When controls are visible, show HUD center shield status and direct touch option */}
            {controlsVisible && (
              <div
                className="player-shield-hud-center"
                onClick={(e) => e.stopPropagation()}
              >
                <div className="player-shield-pill">
                  <span className="shield-dot" /> 🛡️ Ad Shield Active • Taps Protected
                </div>
                <div className="player-shield-hint">
                  Tapping screen shows/hides controls safely without ad traps
                </div>
                <button
                  type="button"
                  className="player-shield-toggle-btn"
                  onClick={(e) => {
                    e.preventDefault();
                    e.stopPropagation();
                    enableDirectTouch(15);
                  }}
                >
                  🔓 Touch Video Directly (15s)
                </button>
              </div>
            )}
          </div>
        )}

        {/* Direct Touch Indicator when user unlocks the player for subtitles/scrubbing */}
        {directTouchMode && (
          <div className="player-direct-touch-indicator">
            <span>🔓 Direct Touch ({directTouchCountdown}s)</span>
            <button
              type="button"
              onClick={disableDirectTouch}
              className="player-reshield-btn"
            >
              🛡️ Lock Shield
            </button>
          </div>
        )}

        {/* Loading Shield Overlay: Absorbs pointer & touch events to block ad traps! */}
        {loading && (
          <div
            className="player-loader-overlay"
            onClick={(e) => {
              e.preventDefault();
              e.stopPropagation();
            }}
            onTouchStart={(e) => {
              e.preventDefault();
              e.stopPropagation();
            }}
          >
            <div className="spinner" />
            <div className="player-loader-badge">🛡️ Ad Shield Active</div>
            <span>Connecting to {currentServerObj.displayName}…</span>
          </div>
        )}
      </div>

      {/* Bottom Controls Bar */}
      <footer className="player-bottom-controls">
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", gap: 8 }}>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <span style={{ fontSize: "0.82rem", color: "var(--pink-200)", fontWeight: 700 }}>
              ⚡ {currentServerObj.displayName}
            </span>
            <button
              type="button"
              className="player-shield-badge-btn"
              onClick={() => {
                if (directTouchMode) disableDirectTouch();
                else enableDirectTouch(15);
              }}
            >
              {directTouchMode ? "🔓 Direct Touch" : "🛡️ Shielded"}
            </button>
          </div>
          <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
            <button
              type="button"
              className="player-ctrl-btn"
              onClick={() => setControlsVisible(false)}
            >
              Hide Controls
            </button>
            <button className="player-ctrl-btn" onClick={toggleFullscreen}>
              {isFullscreen ? "Exit Fullscreen" : "⛶ Fullscreen"}
            </button>
          </div>
        </div>
        <div style={{ fontSize: "0.72rem", color: "rgba(255, 255, 255, 0.7)", textAlign: "center" }}>
          💡 Tap screen once to toggle controls. Ad Shield blocks all popup traps!
        </div>
      </footer>
    </div>
  );
}
