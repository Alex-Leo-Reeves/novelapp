import React, { useState, useEffect } from "react";

export default function MediaCard({ media, onPress }) {
  const [isFav, setIsFav] = useState(false);

  useEffect(() => {
    try {
      const favs = JSON.parse(localStorage.getItem("ij_favorites") || "[]");
      const exists = favs.some((f) => f.id === media.id || f.title === media.title);
      setIsFav(exists);
    } catch (_) {}
  }, [media.id, media.title]);

  const toggleFav = (e) => {
    e.stopPropagation();
    try {
      const favs = JSON.parse(localStorage.getItem("ij_favorites") || "[]");
      const index = favs.findIndex((f) => f.id === media.id || f.title === media.title);
      let updated;
      if (index >= 0) {
        updated = favs.filter((_, i) => i !== index);
        setIsFav(false);
      } else {
        updated = [...favs, media];
        setIsFav(true);
      }
      localStorage.setItem("ij_favorites", JSON.stringify(updated));
    } catch (_) {}
  };

  const kindLabel = media.kind ? media.kind.toUpperCase() : "MEDIA";

  return (
    <div className="media-card" onClick={onPress}>
      <div className="card-cover-wrap">
        {media.cover ? (
          <img
            src={media.cover}
            alt={media.title}
            className="card-cover-img"
            loading="lazy"
            referrerPolicy="no-referrer"
            onError={(e) => {
              e.currentTarget.style.display = "none";
            }}
          />
        ) : (
          <div className="card-placeholder">{media.title ? media.title.charAt(0) : "❤"}</div>
        )}
        <span className="card-kind-badge">{kindLabel}</span>
        <button
          className="card-heart-btn"
          onClick={toggleFav}
          aria-label={isFav ? "Remove from favorites" : "Add to favorites"}
          title="Favorite"
        >
          {isFav ? "❤️" : "🤍"}
        </button>
      </div>
      <div className="card-info">
        <h3 className="card-title">{media.title}</h3>
        <span className="card-meta">
          {media.episodes ? `${media.episodes} eps` : media.server || "Stream"}
        </span>
      </div>
    </div>
  );
}
