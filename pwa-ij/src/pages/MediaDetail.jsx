import React, { useState, useEffect } from "react";
import { getChapters, getMediaStream } from "../data/api";
import { ALL_SERVERS, defaultServerFor, parseMediaId, buildEmbedUrl, resolveTmdbFromAniList, normalizeServerKey } from "../data/servers";
import { safeDecodeMedia } from "../data/codec";

export default function MediaDetail({ route, navigate, onOpenPlayer, onOpenReader }) {
  const mediaPayload = route.media || safeDecodeMedia(route.id) || {
    id: route.id || "",
    title: route.id || "Media",
    kind: "anime",
    cover: "",
    synopsis: "",
    detailUrl: "",
  };

  const kind = (mediaPayload.kind || "anime").toLowerCase();
  const isReadingContent = kind === "novels" || kind === "novel" || kind === "manga" || kind === "comic";

  const [selectedServer, setSelectedServer] = useState(() =>
    normalizeServerKey(mediaPayload.server || defaultServerFor(kind) || "vidsrcto")
  );
  const [serverOn, setServerOn] = useState(true); // Server selector ON by default
  const [items, setItems] = useState([]);
  const [loadingItems, setLoadingItems] = useState(true);
  const [isFav, setIsFav] = useState(false);
  const [resolvingStream, setResolvingStream] = useState(false);

  // Check favorites
  useEffect(() => {
    try {
      const favs = JSON.parse(localStorage.getItem("ij_favorites") || "[]");
      setIsFav(favs.some((f) => f.id === mediaPayload.id || f.title === mediaPayload.title));
    } catch (_) {}
  }, [mediaPayload.id, mediaPayload.title]);

  const toggleFav = () => {
    try {
      const favs = JSON.parse(localStorage.getItem("ij_favorites") || "[]");
      const idx = favs.findIndex((f) => f.id === mediaPayload.id || f.title === mediaPayload.title);
      let updated;
      if (idx >= 0) {
        updated = favs.filter((_, i) => i !== idx);
        setIsFav(false);
      } else {
        updated = [...favs, mediaPayload];
        setIsFav(true);
      }
      localStorage.setItem("ij_favorites", JSON.stringify(updated));
    } catch (_) {}
  };

  // Load episodes or chapters
  useEffect(() => {
    let cancelled = false;
    setLoadingItems(true);

    async function fetchList() {
      try {
        const list = await getChapters(
          kind,
          mediaPayload.detailUrl || mediaPayload.id || "",
          mediaPayload.title || "",
          mediaPayload.server || "default"
        );
        if (!cancelled) {
          setItems(Array.isArray(list) ? list : []);
        }
      } catch (e) {
        if (!cancelled) setItems([]);
      } finally {
        if (!cancelled) setLoadingItems(false);
      }
    }

    fetchList();
    return () => {
      cancelled = true;
    };
  }, [kind, mediaPayload.detailUrl, mediaPayload.id, mediaPayload.title, mediaPayload.server]);

  const handlePlayEpisode = async (ep) => {
    const epNum = ep ? ep.number || 1 : 1;
    setResolvingStream(true);

    let streamUrl = "";
    try {
      const parsed = parseMediaId(mediaPayload.detailUrl);
      if (parsed && parsed.source === "tmdb") {
        streamUrl = buildEmbedUrl(selectedServer, parsed.type, parsed.id, "1", String(epNum));
      } else if (parsed && parsed.source === "anilist") {
        const tmdbMatch = await resolveTmdbFromAniList(parsed.id);
        if (tmdbMatch) {
          streamUrl = buildEmbedUrl(selectedServer, tmdbMatch.type, tmdbMatch.tmdbId, "1", String(epNum));
        }
      }
      if (!streamUrl && ep?.url && (ep.url.includes(".m3u8") || ep.url.includes(".mp4"))) {
        streamUrl = ep.url;
      }
      if (!streamUrl) {
        // Fallback to provider stream route
        const s = await getMediaStream({
          kind,
          id: mediaPayload.id,
          season: "1",
          episode: String(epNum),
          provider: selectedServer,
        });
        if (s?.url) streamUrl = s.url;
      }
    } catch (_) {}
    finally {
      setResolvingStream(false);
    }

    onOpenPlayer(
      {
        ...mediaPayload,
        server: selectedServer,
        streamUrl: streamUrl || mediaPayload.streamUrl || "",
      },
      ep || { number: epNum, title: `Episode ${epNum}` }
    );
  };

  const handleReadChapter = (chap) => {
    onOpenReader(mediaPayload, chap || items[0] || { number: 1, title: "Chapter 1" }, items);
  };

  return (
    <div className="scroll-container">
      <div className="detail-view">
        {/* Prominent Back Button at top */}
        <div className="detail-nav-bar">
          <button className="back-btn" onClick={() => navigate(route.fromPath || "/")}>
            ← Back
          </button>
          <span style={{ fontSize: "0.8rem", fontWeight: 700, color: "var(--pink-600)" }}>
            {kind.toUpperCase()}
          </span>
        </div>

        {/* Media Hero Card */}
        <div className="detail-card">
          <div className="detail-cover">
            {mediaPayload.cover ? (
              <img src={mediaPayload.cover} alt={mediaPayload.title} />
            ) : (
              <div className="card-placeholder">{mediaPayload.title ? mediaPayload.title.charAt(0) : "❤"}</div>
            )}
          </div>
          <div className="detail-body">
            <div>
              <h1 className="detail-title">{mediaPayload.title}</h1>
              <div className="detail-tags">
                {kind} • {items.length ? `${items.length} ${isReadingContent ? "chapters" : "episodes"}` : "Stream"}
              </div>
              {mediaPayload.synopsis && (
                <p className="detail-synopsis">{mediaPayload.synopsis}</p>
              )}
            </div>

            <div className="detail-actions">
              {isReadingContent ? (
                <button
                  className="btn-pink-primary"
                  onClick={() => handleReadChapter(items[0])}
                >
                  📖 Start Reading
                </button>
              ) : (
                <button
                  className="btn-pink-primary"
                  disabled={resolvingStream}
                  onClick={() => handlePlayEpisode(items[0] || { number: 1 })}
                >
                  {resolvingStream ? "Connecting…" : "▶ Play Now"}
                </button>
              )}
              <button
                className="btn-fav-large"
                onClick={toggleFav}
                aria-label={isFav ? "Remove Favorite" : "Add Favorite"}
              >
                {isFav ? "❤️" : "🤍"}
              </button>
            </div>
          </div>
        </div>

        {/* Server Selector — ON BY DEFAULT */}
        {!isReadingContent && (
          <div className="server-section">
            <div className="server-section-head">
              <span className="server-label">
                <span>⚡ Stream Server</span>
              </span>
              <div className="server-toggle-pill">
                <span>{serverOn ? "Active" : "Standard"}</span>
                <span>•</span>
                <span style={{ color: "var(--pink-500)" }}>
                  {ALL_SERVERS.find((s) => s.key === selectedServer)?.displayName || selectedServer}
                </span>
              </div>
            </div>

            <div className="server-chips-row">
              {ALL_SERVERS.map((srv) => (
                <button
                  key={srv.key}
                  onClick={() => {
                    setSelectedServer(srv.key);
                    setServerOn(true);
                  }}
                  className={`server-pill-btn ${selectedServer === srv.key ? "active" : ""}`}
                >
                  {srv.displayName}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* Episodes or Chapters List */}
        <div className="items-list-section">
          <div className="section-header" style={{ margin: 0 }}>
            <h2 className="section-title">
              {isReadingContent ? "📚 Chapters" : "🎬 Episodes"}
            </h2>
            <span style={{ fontSize: "0.8rem", color: "var(--ink-muted)", fontWeight: 700 }}>
              {items.length} total
            </span>
          </div>

          {loadingItems ? (
            <div className="loading-state" style={{ minHeight: 120 }}>
              <div className="spinner" />
              <p style={{ color: "var(--pink-500)", fontWeight: 700 }}>Loading items…</p>
            </div>
          ) : items.length === 0 ? (
            <div className="empty-state" style={{ minHeight: 120 }}>
              <p style={{ color: "var(--ink-muted)", fontSize: "0.9rem" }}>No list items found.</p>
            </div>
          ) : isReadingContent ? (
            <div className="chapters-list">
              {items.map((chap, idx) => (
                <div
                  key={chap.url || chap.id || idx}
                  className="chapter-item"
                  onClick={() => handleReadChapter(chap)}
                >
                  <span>{chap.title || `Chapter ${chap.number || idx + 1}`}</span>
                  <span style={{ color: "var(--pink-500)", fontWeight: 800 }}>Read →</span>
                </div>
              ))}
            </div>
          ) : (
            <div className="items-grid">
              {items.map((ep, idx) => (
                <button
                  key={ep.url || ep.id || idx}
                  className="episode-btn"
                  onClick={() => handlePlayEpisode(ep)}
                >
                  Ep {ep.number || idx + 1}
                </button>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
