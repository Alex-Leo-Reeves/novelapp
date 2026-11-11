import React, { useState, useEffect } from "react";
import { safeEncodeMedia } from "../data/codec";
import MediaCard from "../components/MediaCard";

export default function Favorites({ onSelectMedia }) {
  const [favs, setFavs] = useState([]);

  useEffect(() => {
    try {
      const data = JSON.parse(localStorage.getItem("ij_favorites") || "[]");
      setFavs(data);
    } catch (_) {
      setFavs([]);
    }
  }, []);

  return (
    <div className="scroll-container">
      <div className="section-header">
        <h2 className="section-title">
          <span>❤️ Saved for IJ</span>
        </h2>
        <span style={{ fontSize: "0.85rem", fontWeight: 700, color: "var(--pink-500)" }}>
          {favs.length} titles
        </span>
      </div>

      {favs.length === 0 ? (
        <div className="empty-state">
          <div className="empty-state-emoji">💖</div>
          <h3 className="empty-state-title">No Favorites Yet</h3>
          <p className="empty-state-sub">
            Tap the heart icon on any anime, movie, or novel to save it here for IJ!
          </p>
        </div>
      ) : (
        <div className="media-grid">
          {favs.map((m) => {
            const encoded = safeEncodeMedia(m);
            return (
              <MediaCard
                key={m.id || m.title}
                media={m}
                onPress={() => onSelectMedia(m, encoded, m.kind || "anime")}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}
