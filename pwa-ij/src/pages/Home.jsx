import React, { useState, useEffect, useCallback, useRef } from "react";
import { MEDIA_TYPES, homeContent, homeContentRows, searchContent } from "../data/api";
import { safeEncodeMedia } from "../data/codec";
import MediaCard from "../components/MediaCard";

export default function Home({ route, onSelectMedia, onNavigateTab }) {
  const [activeType, setActiveType] = useState(() => route.type || "all");
  const [query, setQuery] = useState("");
  
  // Specific category items + pagination
  const [items, setItems] = useState([]);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(true);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);

  // Discover feed rows (like TV App)
  const [feedRows, setFeedRows] = useState([]);
  const [loadingRows, setLoadingRows] = useState(false);

  // Sync route.type when bottom nav or parent updates
  useEffect(() => {
    const nextType = route.type || (route.path === "/novels" ? "novels" : route.path === "/manga" ? "manga" : "all");
    if (nextType !== activeType) {
      setActiveType(nextType);
      setPage(1);
    }
  }, [route.type, route.path]);

  // Load TV app categorized rows when on "all" (Discover) and no search query
  useEffect(() => {
    let cancelled = false;
    if (activeType === "all" && !query.trim()) {
      setLoadingRows(true);
      homeContentRows()
        .then((rows) => {
          if (!cancelled) setFeedRows(rows);
        })
        .catch(() => {})
        .finally(() => {
          if (!cancelled) setLoadingRows(false);
        });
    }
    return () => {
      cancelled = true;
    };
  }, [activeType, query]);

  // Load category grid data (initial page 1)
  const loadCategoryData = useCallback(async (type, searchQ) => {
    if (type === "all" && !searchQ.trim()) return;
    setLoading(true);
    setPage(1);
    try {
      const result = searchQ && searchQ.trim()
        ? await searchContent(searchQ.trim(), type === "all" ? "all" : type, 1)
        : await homeContent(type, 1);
      const list = Array.isArray(result) ? result : [];
      setItems(list);
      setHasMore(list.length >= 10);
    } catch (_) {
      setItems([]);
      setHasMore(false);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (activeType !== "all" || query.trim()) {
      loadCategoryData(activeType, query);
    }
  }, [activeType, loadCategoryData]);

  // Load more pages for infinite/click pagination
  const handleLoadMore = async () => {
    if (loadingMore || !hasMore) return;
    setLoadingMore(true);
    const nextPage = page + 1;
    try {
      const more = query.trim()
        ? await searchContent(query.trim(), activeType === "all" ? "all" : activeType, nextPage)
        : await homeContent(activeType, nextPage);
      const list = Array.isArray(more) ? more : [];
      if (list.length > 0) {
        setItems((prev) => {
          const seen = new Set(prev.map((i) => i.detailUrl || i.id || i.title));
          const filtered = list.filter((i) => !seen.has(i.detailUrl || i.id || i.title));
          return [...prev, ...filtered];
        });
        setPage(nextPage);
        setHasMore(list.length >= 10);
      } else {
        setHasMore(false);
      }
    } catch (_) {
      setHasMore(false);
    } finally {
      setLoadingMore(false);
    }
  };

  const handleSearchChange = (e) => {
    const val = e.target.value;
    setQuery(val);
    if (!val.trim()) {
      if (activeType !== "all") {
        loadCategoryData(activeType, "");
      }
    }
  };

  const handleSearchSubmit = (e) => {
    if (e.key === "Enter") {
      e.target.blur(); // Dismiss iPhone virtual keyboard
      if (query.trim()) {
        loadCategoryData(activeType, query);
      }
    }
  };

  const clearSearch = () => {
    setQuery("");
    if (activeType !== "all") {
      loadCategoryData(activeType, "");
    }
  };

  const handleCategorySelect = (key) => {
    setActiveType(key);
    setPage(1);
    if (onNavigateTab) {
      if (key === "all") onNavigateTab("/");
      else if (key === "novels") onNavigateTab("/novels");
      else if (key === "manga") onNavigateTab("/manga");
    }
  };

  const isDiscoverFeed = activeType === "all" && !query.trim();

  // Category chips include Discover (All)
  const allChips = [
    { key: "all", label: "Discover ✨" },
    ...MEDIA_TYPES,
  ];

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
            placeholder={activeType === "all" ? "Search everything for IJ…" : `Search ${activeType} for IJ…`}
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
        {allChips.map((cat) => (
          <button
            key={cat.key}
            className={`category-chip ${activeType === cat.key ? "active" : ""}`}
            onClick={() => handleCategorySelect(cat.key)}
          >
            {cat.label}
          </button>
        ))}
      </div>

      {/* VIEW 1: Categorized Discover Feed (Like TV App) */}
      {isDiscoverFeed ? (
        <div style={{ marginTop: 12 }}>
          {loadingRows ? (
            <div className="loading-state">
              <div className="spinner" />
              <p style={{ color: "var(--pink-500)", fontWeight: 700 }}>Preparing IJ's Romantic Feed…</p>
            </div>
          ) : (
            feedRows.map((row) => (
              <section key={row.key} className="home-row-section">
                <div className="home-row-header">
                  <h3 className="home-row-title">
                    <span>{row.icon}</span>
                    <span>{row.title}</span>
                  </h3>
                  <button
                    className="home-row-see-all-btn"
                    onClick={() => handleCategorySelect(row.type)}
                  >
                    See all ›
                  </button>
                </div>
                <div className="home-row-scroll">
                  {row.items.map((m) => {
                    const payload = {
                      id: m.detailUrl || m.id || m.title,
                      title: m.title,
                      kind: m.kind || row.type,
                      cover: m.coverUrl || m.cover || "",
                      synopsis: m.synopsis || "",
                      detailUrl: m.detailUrl || "",
                      episodes: m.episodeCount || m.episodes || 12,
                      server: m.sourceName || m.server || "Stream",
                    };
                    const encoded = safeEncodeMedia(payload);
                    return (
                      <div key={m.id || m.detailUrl || m.title} className="home-row-card-item">
                        <MediaCard
                          media={payload}
                          onPress={() => onSelectMedia(payload, encoded, row.type)}
                        />
                      </div>
                    );
                  })}
                </div>
              </section>
            ))
          )}
        </div>
      ) : (
        /* VIEW 2: Category Full Grid with Pagination ("Load More") */
        <div>
          <div className="section-header">
            <h2 className="section-title">
              <span>{query ? `Search: "${query}"` : `${activeType.charAt(0).toUpperCase() + activeType.slice(1)}`}</span>
            </h2>
            <span style={{ fontSize: "0.8rem", color: "var(--ink-muted)", fontWeight: 700 }}>
              {items.length} titles
            </span>
          </div>

          {loading ? (
            <div className="loading-state">
              <div className="spinner" />
              <p style={{ color: "var(--pink-500)", fontWeight: 700 }}>Finding best titles for IJ…</p>
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
            <>
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
                  const encoded = safeEncodeMedia(payload);
                  return (
                    <MediaCard
                      key={m.id || m.detailUrl || m.title}
                      media={payload}
                      onPress={() => onSelectMedia(payload, encoded, activeType)}
                    />
                  );
                })}
              </div>

              {/* Load More Button for plenty of items */}
              {hasMore && (
                <div className="load-more-wrap">
                  <button
                    className="load-more-btn"
                    onClick={handleLoadMore}
                    disabled={loadingMore}
                  >
                    {loadingMore ? (
                      <>
                        <div className="spinner" style={{ width: 16, height: 16, borderWidth: 2 }} />
                        <span>Loading More…</span>
                      </>
                    ) : (
                      <>
                        <span>Load More {activeType.charAt(0).toUpperCase() + activeType.slice(1)}</span>
                        <span>↓</span>
                      </>
                    )}
                  </button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
}
