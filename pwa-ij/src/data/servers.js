// ═════════════════════════════════════════════════════════════════════
// MA Server embed URL builder — EXACT copy of Android MaServerSource.kt
// Servers: Nebula(vidlink.pro), Quasar(vidsrc.to), Pulsar(autoembed.co),
// Comet(2embed.online), Astra(vidsrc.sbs), Orion(nontongo.win),
// Lyra(multiembed.mov), Vega(vidsrc.net), Sirius(smashystream)
// These build TMDB-based embed URLs — same as Android loads in WebView.
// ═════════════════════════════════════════════════════════════════════

export const StreamServer = {
  VIDLINK:   { key: "vidlink",   displayName: "Nebula",  providerName: "VidLink",   order: 1 },
  VIDSRC_TO: { key: "vidsrcto",  displayName: "Quasar",  providerName: "VidSrc.to", order: 2 },
  AUTOEMBED: { key: "autoembed", displayName: "Pulsar",  providerName: "AutoEmbed", order: 3 },
  TWO_EMBED: { key: "2embed",    displayName: "Comet",   providerName: "2Embed.online", order: 4 },
  VIDSRC_SBS:{ key: "vidsrcsbs", displayName: "Astra",   providerName: "VidSrc.sbs", order: 5 },
  NONTONGO:  { key: "nontongo",  displayName: "Orion",   providerName: "Nontongo",  order: 6 },
  MULTI_EMBED:{ key: "multiembed", displayName: "Lyra", providerName: "MultiEmbed", order: 7 },
  VIDSRC_NET:{ key: "vidsrcnet", displayName: "Vega",    providerName: "VidSrc.net", order: 8 },
  SMASHY:    { key: "smashy",    displayName: "Sirius",  providerName: "SmashyStream", order: 9 },
};

// Android MOVIE_SELECTOR: Nebula, Quasar, Pulsar, Comet, Astra
export const MOVIE_SELECTOR = [
  StreamServer.VIDLINK,
  StreamServer.VIDSRC_TO,
  StreamServer.AUTOEMBED,
  StreamServer.TWO_EMBED,
  StreamServer.VIDSRC_SBS,
];

// Android TMDB-embed anime servers: Nebula, Quasar, Astra
export const ANIME_EMBED_SELECTOR = [
  StreamServer.VIDLINK,
  StreamServer.VIDSRC_TO,
  StreamServer.VIDSRC_SBS,
];

export const ALL_SERVERS = [
  StreamServer.VIDLINK, StreamServer.VIDSRC_TO, StreamServer.AUTOEMBED,
  StreamServer.TWO_EMBED, StreamServer.VIDSRC_SBS, StreamServer.NONTONGO,
  StreamServer.MULTI_EMBED, StreamServer.VIDSRC_NET, StreamServer.SMASHY,
];

// Extract TMDB id + type from detailUrl like "tmdb://movie/862" or "tmdb://tv/94796"
export function parseTmdb(detailUrl) {
  if (!detailUrl) return null;
  const m = String(detailUrl).match(/^tmdb:\/\/(movie|tv)\/(\d+)$/);
  if (!m) return null;
  return { type: m[1], id: m[2] };
}

// Normalize any server name (key, displayName like "Nebula", or providerName like "VidLink")
export function normalizeServerKey(key) {
  if (!key) return StreamServer.VIDSRC_TO.key;
  const raw = String(key).trim().toLowerCase();
  for (const s of ALL_SERVERS) {
    if (
      s.key.toLowerCase() === raw ||
      s.displayName.toLowerCase() === raw ||
      s.providerName.toLowerCase().replace(/[^a-z0-9]/g, "") === raw.replace(/[^a-z0-9]/g, "")
    ) {
      return s.key;
    }
  }
  return raw;
}

// Build embed URL — exactly like Android StreamServer.buildEmbedUrl
export function buildEmbedUrl(serverKey, tmdbType, tmdbId, season = "1", episode = "1") {
  const normKey = normalizeServerKey(serverKey);
  const isMovie = tmdbType === "movie";
  switch (normKey) {
    case StreamServer.VIDLINK.key:
      // Vidlink works best with primaryColor matching theme and without forced unmuted autoplay
      // (which iOS Safari WebKit blocks, causing infinite loading spinners)
      return isMovie
        ? `https://vidlink.pro/movie/${tmdbId}?primaryColor=ff3385`
        : `https://vidlink.pro/tv/${tmdbId}/${season}/${episode}?primaryColor=ff3385`;
    case StreamServer.VIDSRC_TO.key:
      return isMovie
        ? `https://vidsrc.to/embed/movie/${tmdbId}`
        : `https://vidsrc.to/embed/tv/${tmdbId}/${season}/${episode}`;
    case StreamServer.AUTOEMBED.key:
      return isMovie
        ? `https://autoembed.co/movie/tmdb/${tmdbId}`
        : `https://autoembed.co/tv/tmdb/${tmdbId}-${season}-${episode}`;
    case StreamServer.TWO_EMBED.key:
      return isMovie
        ? `https://www.2embed.online/embed/movie/${tmdbId}`
        : `https://www.2embed.online/embed/tv/${tmdbId}/${season}/${episode}`;
    case StreamServer.VIDSRC_SBS.key:
      // Mirror fallback to vidsrc.net for reliable movie streaming
      return isMovie
        ? `https://vidsrc.net/embed/movie?tmdb=${tmdbId}`
        : `https://vidsrc.net/embed/tv?tmdb=${tmdbId}&season=${season}&episode=${episode}`;
    case StreamServer.NONTONGO.key:
      return isMovie
        ? `https://www.nontongo.win/embed/movie/${tmdbId}`
        : `https://www.nontongo.win/embed/tv/${tmdbId}/${season}/${episode}`;
    case StreamServer.MULTI_EMBED.key:
      return isMovie
        ? `https://multiembed.mov/?video_id=${tmdbId}&tmdb=1`
        : `https://multiembed.mov/?video_id=${tmdbId}&tmdb=1&s=${season}&e=${episode}`;
    case StreamServer.VIDSRC_NET.key:
      return isMovie
        ? `https://vidsrc.net/embed/movie?tmdb=${tmdbId}`
        : `https://vidsrc.net/embed/tv?tmdb=${tmdbId}&season=${season}&episode=${episode}`;
    case StreamServer.SMASHY.key:
      return isMovie
        ? `https://embed.smashystream.com/playere.php?tmdb=${tmdbId}`
        : `https://embed.smashystream.com/playere.php?tmdb=${tmdbId}&season=${season}&ep=${episode}`;
    default:
      // Safe fallback to Quasar (vidsrc.to) if unknown key
      return isMovie
        ? `https://vidsrc.to/embed/movie/${tmdbId}`
        : `https://vidsrc.to/embed/tv/${tmdbId}/${season}/${episode}`;
  }
}

export function defaultServerFor(kind) {
  // Quasar (VidSrc.to) has proven instant movie playback and zero client-side exceptions
  return StreamServer.VIDSRC_TO.key;
}

export function selectorFor(kind) {
  if (kind === "movie") return MOVIE_SELECTOR;
  return ANIME_EMBED_SELECTOR;
}

// Resolve AniList ID to TMDB ID using the public AniList GraphQL API
// (no auth required). Returns { type: "movie"|"tv", tmdbId: string } or null.
export async function resolveTmdbFromAniList(anilistId) {
  try {
    const res = await fetch("https://graphql.anilist.co", {
      method: "POST",
      headers: { "Content-Type": "application/json", "Accept": "application/json" },
      body: JSON.stringify({
        query: `query($id: Int){Media(id:$id){id type format tmdbId}}`,
        variables: { id: parseInt(anilistId, 10) },
      }),
    });
    const json = await res.json();
    const media = json.data && json.data.Media;
    if (!media) return null;
    const type = media.type === "ANIME" || media.format === "TV" || media.format === "TV_SHORT" ? "tv" : "movie";
    return media.tmdbId ? { type, tmdbId: String(media.tmdbId) } : null;
  } catch (e) {
    return null;
  }
}

// Parse detailUrl: supports "tmdb://movie/862", "tmdb://tv/94796", and "anilist:151807"
export function parseMediaId(detailUrl) {
  if (!detailUrl) return null;
  const m = String(detailUrl).match(/^tmdb:\/\/(movie|tv)\/(\d+)$/);
  if (m) return { type: m[1], id: m[2], source: "tmdb" };
  const m2 = String(detailUrl).match(/^anilist:(\d+)$/);
  if (m2) return { type: "anime", id: m2[1], source: "anilist" };
  return null;
}
