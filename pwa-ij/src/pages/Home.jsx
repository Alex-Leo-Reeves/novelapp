import React, { useState, useEffect, useCallback } from "react";
import { MEDIA_TYPES, homeContent, searchContent } from "../data/api";
import MediaCard from "../components/MediaCard";

export default function Home({ route, onSelectMedia }) {
  const [activeType, setActiveType] = useState(route.type || "anime");
  const [query, setQuery] = useState("");
  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(true);

  // If route specified a novel or search initial type
  useEffect(() => {
    if (route.type && route.type !== activeType) {
      setActiveType(route.type);
    }
  }, [route.type]);

  const loadData = useCallback(async (type, searchQ) => {
    setLoading(true);
    try {
      const result = searchQ && searchQ.trim()
        ? await searchContent(searchQ.trim(), type)
        : await homeContent(type);
      setItems(Array.isArray(result) ? result : []);
    } catch (_) {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadData(activeType, query);
  }, [activeType, loadData]);

  const handleSearchChange = (e) => {
    const val = e.target.value;
    setQuery(val);
    if (!val.trim()) {
      loadData(activeType, "");
    }
  };

  const handleSearchSubmit = (e) => {
    if (e.key === "Enter") {
      e.target.blur(); // Dismiss iPhone virtual keyboard
      loadData(activeType, query);
    }
  };

  const clearSearch = () => {
    setQuery("");
    loadData(activeType, "");
  };

  return (
    <div className="scroll-container">
      {/* Native Keyboard Search Input */}
      <div className="search-bar-wrap">
        <div className="search-pill">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
            <circle cx="11" cy="11" r="8"></circle>
            <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
          </svg>
          <input
            type="search"
            className="search-input"
            placeholder={`Search ${activeType} for IJ…`}
            value={query}
            onChange={handleSearchChange}
            onKeyDown={handleSearchSubmit}
            enterKeyHint="search"
            autoCapitalize="none"
            autoCorrect="off"
            spellCheck={false}
          />
          {query && (
            <button className="search-clear-btn" onClick={clearSearch} aria-label="Clear search">
              ✕
            </button>
          )}
        </div>
      </div>

      {/* Category Horizontal Scrolling Chips */}
      <div className="category-chips-scroll">
        {MEDIA_TYPES.map((cat) => (
          <button
            key={cat.key}
            className={`category-chip ${activeType === cat.key ? "active" : ""}`}
            onClick={() => {
              setActiveType(cat.key);
              loadData(cat.key, query);
            }}
          >
            {cat.label}
          </button>
        ))}
      </div>

      {/* Section Title */}
      <div className="section-header">
        <h2 className="section-title">
          <span>{query ? `Search: "${query}"` : `${activeType.charAt(0).toUpperCase() + activeType.slice(1)}`}</span>
        </h2>
        <span style={{ fontSize: "0.8rem", color: "var(--ink-muted)", fontWeight: 700 }}>
          {items.length} titles
        </span>
      </div>

      {/* Media Grid or States */}
      {loading ? (
        <div className="loading-state">
          <div className="spinner" />
          <p style={{ color: "var(--pink-500)", fontWeight: 700 }}>Finding best streams for IJ…</p>
        </div>
      ) : items.length === 0 ? (
        <div className="empty-state">
          <div className="empty-state-emoji">🔍</div>
          <h3 className="empty-state-title">No Titles Found</h3>
          <p className="empty-state-sub">
            {query ? `No results for "${query}". Try another title or category!` : "No items available in this category."}
          </p>
        </div>
      ) : (
        <div className="media-grid">
          {items.map((m) => {
            const payload = {
              id: m.detailUrl || m.id || m.title,
              title: m.title,
              kind: m.kind || activeType,
              cover: m.coverUrl || m.cover || "",
              synopsis: m.synopsis || "",
              detailUrl: m.detailUrl || "",
              episodes: m.episodeCount || m.episodes || 12,
              server: m.sourceName || m.server || "Stream",
            };
            const encoded = encodeURIComponent(btoa(JSON.stringify(payload)));
            return (
              <MediaCard
                key={m.id || m.detailUrl || m.title}
                media={payload}
                onPress={() => onSelectMedia(encoded, activeType)}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}
