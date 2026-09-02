/**
 * NovaRead TV API Client for Hisense VIDAA Web Application
 * Connects to Render backend: https://novelapp1.onrender.com/api
 */

(function (root, factory) {
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.NovaApi = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var DEFAULT_BASE_URL = 'https://novelapp1.onrender.com/api';
  var ANILIST_URL = 'https://graphql.anilist.co';

  function getBaseUrl() {
    return localStorage.getItem('nova_tv_api_url') || DEFAULT_BASE_URL;
  }

  function getAuthToken() {
    var user = getUserSession();
    return user ? user.authToken : '';
  }

  function getUserSession() {
    try {
      var raw = localStorage.getItem('nova_tv_user');
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function saveUserSession(user) {
    if (!user) {
      localStorage.removeItem('nova_tv_user');
    } else {
      localStorage.setItem('nova_tv_user', JSON.stringify(user));
    }
  }

  async function request(endpoint, options = {}) {
    var url = endpoint.startsWith('http') ? endpoint : (getBaseUrl() + endpoint);
    var headers = options.headers || {};
    
    if (options.body && typeof options.body === 'object' && !(options.body instanceof FormData)) {
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(options.body);
    }

    var token = getAuthToken();
    if (token && !headers['Authorization']) {
      headers['Authorization'] = 'Bearer ' + token;
    }

    var controller = new AbortController();
    var timeoutId = setTimeout(() => controller.abort(), options.timeout || 35000);

    try {
      var response = await fetch(url, {
        ...options,
        headers: headers,
        signal: controller.signal
      });
      clearTimeout(timeoutId);

      if (response.status === 401 || response.status === 403) {
        // Session dead
        return { ok: false, status: response.status, data: null, error: 'Unauthorized' };
      }

      var text = await response.text();
      var data;
      try {
        data = text ? JSON.parse(text) : {};
      } catch (e) {
        data = { raw: text };
      }

      return { ok: response.ok, status: response.status, data: data };
    } catch (err) {
      clearTimeout(timeoutId);
      console.warn('API Request Failed:', url, err);
      return { ok: false, error: err.message || 'Network error' };
    }
  }

  // --- Remote Configuration & Branding ---
  async function fetchTvConfig() {
    var res = await request('/tv/config', { timeout: 10000 });
    if (res.ok && res.data) return res.data;
    
    // Fallback defaults
    return {
      version: 1,
      branding: {
        title: "NovaRead TV",
        tagline: "Anime · Novels · Manga · Movies · Sports"
      },
      featureFlags: {
        showSports: true,
        showDownloads: true,
        showPhonePair: true,
        showComics: true,
        showDonghua: true,
        showKDrama: true,
        showCartoons: true,
        showClassic: true,
        showNollywood: true
      },
      sidebar: [
        { key: "home", label: "Home", icon: "home" },
        { key: "anime", label: "Anime", icon: "play-circle" },
        { key: "movies", label: "Movies & TV", icon: "film" },
        { key: "kdrama", label: "K-Drama", icon: "tv" },
        { key: "donghua", label: "Donghua", icon: "zap" },
        { key: "cartoon", label: "Cartoons", icon: "smile" },
        { key: "classic", label: "Classics", icon: "clock" },
        { key: "sports", label: "Sports Live", icon: "activity" },
        { key: "novels", label: "Novels", icon: "book-open" },
        { key: "manga", label: "Manga", icon: "grid" },
        { key: "comics", label: "Comics", icon: "layers" },
        { key: "creation", label: "Creation", icon: "edit-3" },
        { key: "you", label: "Watchlist & History", icon: "bookmark" }
      ],
      homeRows: [
        { key: "recommended", label: "✨ Recommended For You", type: "recommended" },
        { key: "trendingAnime", label: "🔥 Trending Anime", type: "anime" },
        { key: "newMovies", label: "🎬 New Movies & Shows", type: "movie" },
        { key: "kdramaTop", label: "🇰🇷 Popular K-Drama", type: "kdrama" },
        { key: "popularNovels", label: "📚 Top Light Novels", type: "novel" },
        { key: "topManga", label: "🎨 Popular Manga", type: "manga" }
      ]
    };
  }

  // --- Phone QR Pairing & Auth ---
  async function startTvPair() {
    var res = await request('/tv-pair/start', { method: 'POST' });
    if (res.ok && res.data) {
      return {
        pairId: res.data.pairId || '',
        code: res.data.code || '',
        qrContent: res.data.qrContent || ('https://novelapp1.onrender.com/tv-pair?pair=' + (res.data.pairId || '')),
        expiresInSeconds: res.data.expiresInSeconds || 300
      };
    }
    return null;
  }

  async function pollTvPairStatus(pairId) {
    if (!pairId) return { status: 'pending' };
    var res = await request('/tv-pair/status?pair=' + encodeURIComponent(pairId), { timeout: 10000 });
    if (res.ok && res.data) {
      if (res.data.status === 'approved' && res.data.user) {
        var user = {
          id: res.data.user.id || '',
          username: res.data.user.username || '',
          email: res.data.user.email || '',
          authToken: res.data.token || '',
          plan: res.data.user.plan || 'free',
          isPremium: !!res.data.user.isPremium
        };
        saveUserSession(user);
        return { status: 'approved', user: user };
      } else if (res.data.status === 'expired') {
        return { status: 'expired' };
      }
    }
    return { status: 'pending' };
  }

  async function login(email, password) {
    var res = await request('/auth/login', {
      method: 'POST',
      body: { email: email, password: password }
    });
    if (res.ok && res.data && res.data.user) {
      var user = {
        id: res.data.user.id || '',
        username: res.data.user.username || '',
        email: res.data.user.email || '',
        authToken: res.data.token || '',
        plan: res.data.user.plan || 'free',
        isPremium: !!res.data.user.isPremium
      };
      saveUserSession(user);
      return { ok: true, user: user };
    }
    return { ok: false, error: res.data?.error || 'Login failed' };
  }

  async function authMe() {
    var token = getAuthToken();
    if (!token) return null;
    var res = await request('/auth/me');
    if (res.ok && res.data && res.data.user) {
      var user = {
        id: res.data.user.id,
        username: res.data.user.username,
        email: res.data.user.email,
        authToken: token,
        plan: res.data.user.plan || 'free',
        isPremium: !!res.data.user.isPremium
      };
      saveUserSession(user);
      return user;
    }
    return null;
  }

  async function fetchBillingStatus() {
    var res = await request('/billing/status');
    return res.ok && res.data ? res.data : null;
  }

  async function createBillingCheckout(planId) {
    var res = await request('/billing/checkout', { method: 'POST', body: { planId: planId } });
    return res.ok && res.data ? res.data : null;
  }

  // --- Content Feeds & Search ---
  async function fetchContentHome(type, page = 1) {
    // Nollywood is a live, rotating TV-style feed when the server has its
    // YouTube integration configured.  Keep the catalogue fallback so the tab
    // is never blank while that provider is unavailable.
    if (['nigerian', 'nollywood', 'naija'].includes(String(type).toLowerCase())) {
      var live = await fetchNollywoodLive(page);
      if (live.length) return live;
    }
    var res = await request('/content/home?type=' + encodeURIComponent(type) + '&page=' + page);
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.items)) {
      return res.data.data.items.map(normalizeMediaItem);
    }
    // AniList direct fallback for anime if server endpoint is busy
    if (type === 'anime' || type === 'trendingAnime') {
      return fetchAnilistTrending();
    }
    return [];
  }

  async function fetchAnilistSearch(query) {
    if (!query || !query.trim()) return [];
    var gql = `
      query ($search: String) {
        Page (page: 1, perPage: 16) {
          media (search: $search, type: ANIME, sort: SEARCH_MATCH) {
            id
            title { english romaji native }
            coverImage { extraLarge large }
            bannerImage
            description
            genres
            averageScore
            seasonYear
          }
        }
      }
    `;
    try {
      var res = await fetch(ANILIST_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify({ query: gql, variables: { search: query } })
      });
      var data = await res.json();
      var list = (data.data && data.data.Page && data.data.Page.media) || [];
      return list.map(m => ({
        id: 'anilist_' + m.id,
        title: m.title.english || m.title.romaji || m.title.native || 'Anime',
        rating: (m.averageScore ? (m.averageScore / 10).toFixed(1) : '8.8'),
        year: m.seasonYear || '2024',
        coverUrl: m.coverImage.extraLarge || m.coverImage.large || '',
        backdropUrl: m.bannerImage || m.coverImage.extraLarge || '',
        synopsis: (m.description || '').replace(/<[^>]*>/g, ''),
        genre: (m.genres || []).join(', '),
        sourceName: 'AniList',
        isAnime: true,
        mediaKind: 'anime',
        detailPageUrl: 'anilist:' + m.id
      }));
    } catch (e) {
      return [];
    }
  }

  async function searchContent(type, query, page = 1) {
    if (!query || !query.trim()) return [];
    var searchType = (type || 'all').toLowerCase();

    // ── Parallel Multi-Source Search ──────────────────────────────────
    // Fire backend API + AniList concurrently for speed, then merge & deduplicate

    var isAnimeSearch = (searchType === 'anime');
    var isAllSearch = (searchType === 'all' || searchType === 'home' || searchType === 'everything');

    if (isAllSearch) {
      // Fan out across EVERY category in parallel for maximum coverage
      var [backendAll, anilistRes] = await Promise.all([
        request('/content/search?type=all&q=' + encodeURIComponent(query) + '&page=' + page)
          .then(r => (r.ok && r.data && r.data.data && Array.isArray(r.data.data.items)) ? r.data.data.items.map(normalizeMediaItem) : [])
          .catch(() => []),
        fetchAnilistSearch(query).then(r => r.map(normalizeMediaItem)).catch(() => [])
      ]);

      // Interleave: AniList anime first (most relevant for anime queries), then backend results
      var combined = [...anilistRes, ...backendAll];
      return deduplicateResults(combined);
    }

    if (isAnimeSearch) {
      // Search AniList + backend anime in parallel
      var [anilistRes, backendRes] = await Promise.all([
        fetchAnilistSearch(query).then(r => r.map(normalizeMediaItem)).catch(() => []),
        request('/content/search?type=anime&q=' + encodeURIComponent(query) + '&page=' + page)
          .then(r => (r.ok && r.data && r.data.data && Array.isArray(r.data.data.items)) ? r.data.data.items.map(normalizeMediaItem) : [])
          .catch(() => [])
      ]);

      var combined = [...anilistRes, ...backendRes];
      return deduplicateResults(combined);
    }

    // For all other specific categories (movies, kdrama, donghua, manga, novels, etc.)
    var res = await request('/content/search?type=' + encodeURIComponent(searchType) + '&q=' + encodeURIComponent(query) + '&page=' + page);
    var items = [];
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.items)) {
      items = res.data.data.items.map(normalizeMediaItem);
    }

    // If no results from backend, try AniList as fallback for video categories
    if (items.length === 0 && ['movie', 'movies', 'kdrama', 'donghua', 'cartoon', 'classic', 'nigerian'].includes(searchType)) {
      var fallback = await fetchAnilistSearch(query).catch(() => []);
      if (fallback.length > 0) {
        return fallback.map(normalizeMediaItem);
      }
    }

    return items;
  }

  function deduplicateResults(results) {
    var seen = new Set();
    return results.filter(x => {
      var key = (x.title || '').toLowerCase().replace(/[^a-z0-9]/g, '');
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  async function fetchSimilarContent(detailUrl, limit = 12) {
    if (!detailUrl) return [];
    var res = await request('/content/similar?detailUrl=' + encodeURIComponent(detailUrl) + '&limit=' + limit);
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.items)) {
      return res.data.data.items.map(normalizeMediaItem);
    }
    return [];
  }

  // --- Chapters & Media Details ---
  async function fetchChapters(kind, detailUrl, title, sourceName) {
    var res = await request('/content/chapters', {
      method: 'POST',
      body: {
        kind: kind || 'movie',
        detailUrl: detailUrl || '',
        title: title || '',
        sourceName: sourceName || ''
      }
    });
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.chapters)) {
      return res.data.data.chapters.map((ch, idx) => ({
        title: ch.title || ('Episode ' + (idx + 1)),
        url: ch.url || '',
        chapterNumber: ch.chapterNumber || ch.number || (idx + 1),
        seasonNumber: ch.seasonNumber || ch.season || 1
      }));
    }
    return [];
  }

  async function fetchWatchRoutes(kind, title, detailUrl) {
    var match = detailUrl ? detailUrl.match(/tmdb-episode:\/\/(\d+)\/(\d+)\/(\d+)/) : null;
    var season = match ? match[2] : '1';
    var episode = match ? match[3] : '1';

    var res = await request('/content/watch-routes', {
      method: 'POST',
      body: {
        kind: kind || 'movie',
        title: title || '',
        detailUrl: detailUrl || '',
        season: season,
        episode: episode,
        episodeMarker: detailUrl || ''
      }
    });

    var list = [];
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.routes)) {
      res.data.data.routes.forEach(r => {
        if (r && r.url) {
          list.push({
            provider: r.provider || 'Fast Stream',
            url: r.url,
            route: r.route || 'embed'
          });
        }
      });
    }

    // Include Donghua/Anime Animexin fallback route if relevant
    var isDonghuaOrAnime = (kind === 'anime' || kind === 'donghua' || /donghua|anime/i.test(title));
    if (isDonghuaOrAnime) {
      list.push({
        provider: 'Animexin (Donghua & Anime)',
        url: 'https://animexin.dev/?s=' + encodeURIComponent(title),
        route: 'embed'
      });
    }

    // Default fast embed fallback
    if (list.length === 0 && detailUrl) {
      var tmdbId = detailUrl.replace('tmdb://', '').split('/')[0];
      if (tmdbId && !isNaN(tmdbId)) {
        list.push({
          provider: 'Auto Server 1',
          url: 'https://vidsrc.to/embed/' + (kind === 'tv' ? `tv/${tmdbId}/${season}/${episode}` : `movie/${tmdbId}`),
          route: 'embed'
        });
        list.push({
          provider: 'Auto Server 2',
          url: 'https://vidlink.pro/' + (kind === 'tv' ? `tv/${tmdbId}/${season}/${episode}` : `movie/${tmdbId}`),
          route: 'embed'
        });
      }
    }

    return list;
  }

  async function fetchAnimeEpisodes(provider, query) {
    var res = await request('/content/anime/episodes?provider=' + encodeURIComponent(provider || 'gogoanime') + '&q=' + encodeURIComponent(query || ''));
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.episodes)) {
      return res.data.data.episodes.map(ep => ({
        title: ep.title || ('Episode ' + (ep.number || ep.episodeNumber || '')),
        url: ep.url || '',
        chapterNumber: ep.number || ep.episodeNumber || 1
      }));
    }
    return [];
  }

  async function fetchAnimeEpisodesParallel(query) {
    // Different providers regularly carry different shows.  Keep each episode
    // tied to the provider that returned it, then prefer the fullest episode
    // list instead of hard-coding a single fragile server.
    var providers = ['hianime', 'gogoanime', 'animepahe', 'animesuge'];
    var results = await Promise.all(providers.map(function (provider) {
      return fetchAnimeEpisodes(provider, query).then(function (episodes) {
        return episodes.map(function (episode) {
          episode.provider = provider;
          return episode;
        });
      }).catch(function () { return []; });
    }));
    return results.sort(function (a, b) { return b.length - a.length; })[0] || [];
  }

  async function fetchAnimeStream(provider, episodeId) {
    var cleanId = episodeId;
    if (episodeId.startsWith('consumet://')) {
      var parts = episodeId.replace('consumet://', '').split('/');
      if (parts.length > 1) {
        cleanId = decodeURIComponent(parts.slice(1).join('/'));
      }
    }
    var res = await request('/content/anime/stream?provider=' + encodeURIComponent(provider || 'gogoanime') + '&episodeId=' + encodeURIComponent(cleanId));
    if (res.ok && res.data && res.data.data) {
      return res.data.data.url || null;
    }
    return null;
  }

  // --- Novel Text & Manga Pages ---
  async function fetchChapterText(chapterUrl, title, sourceName) {
    var res = await request('/content/chapter-text', {
      method: 'POST',
      body: {
        chapterUrl: chapterUrl || '',
        title: title || '',
        sourceName: sourceName || ''
      }
    });
    if (res.ok && res.data && res.data.data) {
      return res.data.data.text || '';
    }
    return '';
  }

  async function fetchMangaPages(chapterUrl) {
    var res = await request('/content/manga-pages', {
      method: 'POST',
      body: { chapterUrl: chapterUrl || '' }
    });
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.pages)) {
      return res.data.data.pages;
    }
    return [];
  }

  async function fetchNollywoodLive(page) {
    var res = await request('/youtube/nollywood-feed?page=' + encodeURIComponent(page || 1));
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.items)) {
      return res.data.data.items.map(function (item) {
        return normalizeMediaItem({
          id: item.id,
          title: item.title,
          coverUrl: item.coverUrl,
          synopsis: item.description,
          subtitle: item.channelTitle || 'Nollywood Live',
          sourceName: 'YouTube Live',
          kind: 'nigerian',
          detailUrl: 'youtube://' + item.videoId,
          videoId: item.videoId
        });
      });
    }
    return [];
  }

  async function fetchYouTubeStream(videoId) {
    var res = await request('/youtube/stream?videoId=' + encodeURIComponent(videoId || ''));
    return res.ok && res.data && res.data.data ? (res.data.data.playbackUrl || null) : null;
  }

  // --- Live Football & WWE Sports ---
  async function fetchFootballFixtures() {
    var res = await request('/football/fixtures');
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.response)) {
      return res.data.data.response;
    }
    return [];
  }

  async function resolveFootballStream(fixtureId, home, away, league) {
    // 1. Direct stream scraper
    var directRes = await request('/football/direct-stream', {
      method: 'POST',
      body: { homeTeam: home, awayTeam: away, leagueName: league }
    });
    if (directRes.ok && directRes.data && directRes.data.data && directRes.data.data.url) {
      return { url: directRes.data.data.url, isDirect: true };
    }

    // 2. Aggregator stream endpoint
    var streamRes = await request('/football/stream?home=' + encodeURIComponent(home) + '&away=' + encodeURIComponent(away) + '&league=' + encodeURIComponent(league || ''));
    if (streamRes.ok && streamRes.data && streamRes.data.data) {
      var raw = streamRes.data.data;
      var candidate = typeof raw === 'string' ? raw.split('|')[0].trim() : raw.url;
      if (candidate) {
        return { url: candidate, isDirect: candidate.includes('.m3u8') };
      }
    }

    // 3. Fallback to StreamEast / ScoreBat
    var homeSlug = (home || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    var awaySlug = (away || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '');
    var fallbackUrl = `https://streamseast.ws/soccer/${homeSlug}-vs-${awaySlug}`;
    return { url: fallbackUrl, isDirect: false };
  }

  async function fetchWweEvents() {
    var res = await request('/wwe/events');
    if (res.ok && res.data && Array.isArray(res.data.data)) {
      return res.data.data;
    }
    return [];
  }

  async function fetchWweStream(id, title, detailUrl) {
    var res = await request('/wwe/stream?eventId=' + encodeURIComponent(id) + '&title=' + encodeURIComponent(title) + '&detailUrl=' + encodeURIComponent(detailUrl || ''));
    if (res.ok && res.data) {
      if (Array.isArray(res.data.data) && res.data.data.length > 0) return res.data.data[0];
      if (typeof res.data.data === 'string') return res.data.data;
    }
    return detailUrl || null;
  }

  // --- AniList Direct Fallback ---
  async function fetchAnilistTrending() {
    try {
      var query = `query { Page(page: 1, perPage: 24) { media(type: ANIME, sort: TRENDING_DESC) { id title { english romaji } coverImage { large extraLarge } bannerImage genres description status episodes } } }`;
      var resp = await fetch(ANILIST_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ query: query })
      });
      var json = await resp.json();
      var media = json?.data?.Page?.media || [];
      return media.map(m => ({
        id: 'anilist_' + m.id,
        title: m.title.english || m.title.romaji || 'Anime',
        coverUrl: m.coverImage.extraLarge || m.coverImage.large || '',
        backdropUrl: m.bannerImage || m.coverImage.extraLarge || '',
        synopsis: (m.description || '').replace(/<[^>]*>/g, ''),
        genre: (m.genres || []).join(', '),
        sourceName: 'AniList',
        isAnime: true,
        mediaKind: 'anime',
        detailPageUrl: 'anilist:' + m.id
      }));
    } catch (e) {
      return [];
    }
  }

  function normalizeMediaItem(item) {
    if (!item) return {};
    var rawKind = (item.kind || item.mediaKind || '').toLowerCase();
    var detail = item.detailUrl || item.detailPageUrl || item.url || '';
    var sub = item.subtitle || item.genre || item.author || '';
    var src = item.sourceName || '';

    var isDonghua = rawKind === 'donghua' || /donghua/i.test(sub) || /donghua/i.test(src);
    var isAnime = !isDonghua && (rawKind === 'anime' || /anilist/i.test(src) || /anime/i.test(sub));
    var isManga = rawKind === 'manga';
    var isComic = rawKind === 'comic';
    var isNovel = rawKind === 'novel' || rawKind === 'novels';

    var effectiveKind = isDonghua ? 'donghua' : (isAnime ? 'anime' : (rawKind || 'movie'));

    return {
      id: item.id || (item.title ? item.title.replace(/\s+/g, '_') : Math.random().toString(36).substring(2)),
      title: item.title || 'Untitled',
      coverUrl: item.coverUrl || item.poster || '',
      backdropUrl: item.backdropUrl || item.banner || item.coverUrl || '',
      detailPageUrl: detail,
      synopsis: item.synopsis || item.description || '',
      genre: item.genre || sub || '',
      author: item.author || sub || '',
      rating: item.rating || item.score || '8.5',
      year: item.year || item.releaseDate || '2025',
      sourceName: src || 'NovaCloud',
      mediaKind: effectiveKind,
      isAnime: isAnime,
      isDonghua: isDonghua,
      isManga: isManga,
      isComic: isComic,
      isNovel: isNovel,
      isVideo: !isManga && !isComic && !isNovel,
      videoId: item.videoId || ''
    };
  }

  return {
    getBaseUrl: getBaseUrl,
    setBaseUrl: function (url) { localStorage.setItem('nova_tv_api_url', url); },
    getUserSession: getUserSession,
    saveUserSession: saveUserSession,
    fetchTvConfig: fetchTvConfig,
    startTvPair: startTvPair,
    pollTvPairStatus: pollTvPairStatus,
    login: login,
    authMe: authMe,
    fetchBillingStatus: fetchBillingStatus,
    createBillingCheckout: createBillingCheckout,
    fetchContentHome: fetchContentHome,
    searchContent: searchContent,
    fetchSimilarContent: fetchSimilarContent,
    fetchChapters: fetchChapters,
    fetchWatchRoutes: fetchWatchRoutes,
    fetchAnimeEpisodes: fetchAnimeEpisodes,
    fetchAnimeEpisodesParallel: fetchAnimeEpisodesParallel,
    fetchAnimeStream: fetchAnimeStream,
    fetchChapterText: fetchChapterText,
    fetchMangaPages: fetchMangaPages,
    fetchNollywoodLive: fetchNollywoodLive,
    fetchYouTubeStream: fetchYouTubeStream,
    fetchFootballFixtures: fetchFootballFixtures,
    resolveFootballStream: resolveFootballStream,
    fetchWweEvents: fetchWweEvents,
    fetchWweStream: fetchWweStream
  };
}));
