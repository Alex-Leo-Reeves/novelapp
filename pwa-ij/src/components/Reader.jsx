import React, { useState, useEffect } from "react";
import { getChapterText } from "../data/api";

export default function Reader({ media, chapter, chapters = [], onBack, onSelectChapter }) {
  const [text, setText] = useState("");
  const [loading, setLoading] = useState(true);
  const [fontSize, setFontSize] = useState(() => {
    return Number(localStorage.getItem("ij_reader_font_size") || 17);
  });

  const currentIndex = chapters.findIndex(
    (c) => c.number === chapter.number || c.title === chapter.title
  );
  const hasPrev = currentIndex > 0;
  const hasNext = currentIndex >= 0 && currentIndex < chapters.length - 1;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    async function load() {
      try {
        const content = await getChapterText(
          chapter.url || chapter.id || "",
          chapter.title || media.title || "",
          media.server || "NovelSource"
        );
        if (!cancelled) setText(content);
      } catch (e) {
        if (!cancelled) setText("Unable to load chapter content. Please check your connection.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    }
    load();
    return () => {
      cancelled = true;
    };
  }, [chapter, media]);

  const changeFontSize = (delta) => {
    const next = Math.max(14, Math.min(26, fontSize + delta));
    setFontSize(next);
    localStorage.setItem("ij_reader_font_size", String(next));
  };

  return (
    <div className="reader-modal" style={{ "--reader-font-size": `${fontSize}px` }}>
      <header className="reader-header">
        <button className="back-btn" onClick={onBack}>
          ← Back
        </button>
        <div style={{ textAlign: "center", maxWidth: "60%" }}>
          <div style={{ fontWeight: 800, fontSize: "0.9rem", color: "var(--ink)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {chapter.title || `Chapter ${chapter.number || 1}`}
          </div>
          <div style={{ fontSize: "0.75rem", color: "var(--ink-muted)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {media.title}
          </div>
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          <button
            onClick={() => changeFontSize(-1)}
            style={{
              background: "var(--pink-100)",
              border: "1px solid var(--border)",
              borderRadius: "8px",
              padding: "4px 8px",
              fontSize: "0.8rem",
              fontWeight: 800,
              color: "var(--pink-700)",
              cursor: "pointer",
            }}
            aria-label="Decrease font size"
          >
            A-
          </button>
          <button
            onClick={() => changeFontSize(1)}
            style={{
              background: "var(--pink-100)",
              border: "1px solid var(--border)",
              borderRadius: "8px",
              padding: "4px 8px",
              fontSize: "0.8rem",
              fontWeight: 800,
              color: "var(--pink-700)",
              cursor: "pointer",
            }}
            aria-label="Increase font size"
          >
            A+
          </button>
        </div>
      </header>

      <div className="reader-content-wrap">
        {loading ? (
          <div className="loading-state">
            <div className="spinner" />
            <p style={{ color: "var(--pink-500)", fontWeight: 700 }}>Opening Chapter…</p>
          </div>
        ) : (
          <div>
            <h2 style={{ fontFamily: "var(--font-romantic)", fontSize: "1.4rem", color: "var(--pink-600)", marginBottom: 16 }}>
              {chapter.title || `Chapter ${chapter.number || 1}`}
            </h2>
            {text.split(/\n\s*\n/).map((para, idx) => (
              <p key={idx}>{para.trim()}</p>
            ))}
          </div>
        )}
      </div>

      <footer className="reader-footer">
        <button
          className="back-btn"
          disabled={!hasPrev}
          style={{ opacity: hasPrev ? 1 : 0.4 }}
          onClick={() => hasPrev && onSelectChapter(chapters[currentIndex - 1])}
        >
          ← Prev
        </button>
        <span style={{ fontSize: "0.8rem", fontWeight: 700, color: "var(--ink-muted)" }}>
          {currentIndex >= 0 ? `${currentIndex + 1} / ${chapters.length}` : ""}
        </span>
        <button
          className="back-btn"
          disabled={!hasNext}
          style={{ opacity: hasNext ? 1 : 0.4 }}
          onClick={() => hasNext && onSelectChapter(chapters[currentIndex + 1])}
        >
          Next →
        </button>
      </footer>
    </div>
  );
}
