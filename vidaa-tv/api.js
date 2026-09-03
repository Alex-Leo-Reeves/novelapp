/**
 * NovaRead TV API Client for Hisense VIDAA Web Application
 * Connects to Render backend: https://novelapp1.onrender.com/api
 *
 * v2 — full parity with the Android TV app:
 *  - Retry-with-backoff on every request (Render cold starts / CDN flake)
 *  - Multi-source NOVEL aggregation (RoyalRoad + LightNovelPub + BoxNovel +
 *    FreeWebNovel + WuxiaWorld + ReadNovelFull) like TvMediaRepository
 *  - Client-side MangaDex (CORS-enabled) for manga lists/chapters/pages
 *  - Episodes from the stable backend TMDB bridge (/content/chapters) with
 *    numeric season/episode ordering — fixes Mushoku Tensei & ordering bugs
 *  - Full watch-route list (Server 1-8) + client fallbacks for parallel race
 *  - Flat ESPN fixture parsing (football fixtures actually render now)
 *  - iptv-org live channel feeds for Nollywood / African Live TV
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
  var MANGADEX_URL = 'https://api.mangadex.org';
  var MANGADEX_UPLOADS = 'https://uploads.mangadex.org';

  function getBaseUrl() {
    return localStorage.getItem('nova_tv_api_url') || DEFAULT_BASE_URL;
  }

  function getAuthToken() {
    var user = getUserSession();
    return user ? (user.authToken || '') : '';
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

  // ── Core request with retry (matches tvApp retryNullable) ─────────────
  function rawRequest(url, options) {
    var controller = new AbortController();
    var timeoutId = setTimeout(function () { controller.abort(); }, options.timeout || 35000);
    return fetch(url, {
      method: options.method || 'GET',
      headers: options.headers || {},
      body: options.body,
      signal: controller.signal
    }).then(function (response) {
      clearTimeout(timeoutId);
      return response.text().then(function (text) {
        var data;
        try { data = text ? JSON.parse(text) : {}; } catch (e) { data = { raw: text }; }
        return { ok: response.ok, status: response.status, data: data };
      });
    }).catch(function (err) {
      clearTimeout(timeoutId);
      return { ok: false, status: 0, data: null, error: (err && err.message) || 'Network error' };
    });
  }

  async function request(endpoint, options) {
    options = options || {};
    var url = endpoint.startsWith('http') ? endpoint : (getBaseUrl() + endpoint);
    var headers = options.headers || {};

    if (options.body && typeof options.body === 'object' && !(options.body instanceof FormData) && !(typeof options.body === 'string')) {
      headers['Content-Type'] = 'application/json';
      options.body = JSON.stringify(options.body);
    }

    var token = getAuthToken();
    if (token && !headers['Authorization']) {
      headers['Authorization'] = 'Bearer ' + token;
    }
    options.headers = headers;

    // 1 try + 2 retries with backoff (Render cold-start / CDN flake safe)
    var delays = [0, 900, 1800];
    var last = null;
    for (var attempt = 0; attempt < delays.length; attempt++) {
      if (delays[attempt] > 0) {
        await new Promise(function (r) { setTimeout(r, delays[attempt]); });
      }
      var res = await rawRequest(url, options);
      last = res;
      if (res.ok) return res;
      // 4xx (other than 401/403/429) will not improve on retry — fail fast
      if (res.status >= 400 && res.status < 500 && res.status !== 401 && res.status !== 403 && res.status !== 429) return res;
    }
    return last;
  }


  // ── Remote Configuration & Branding ────────────────────────────────────
  async function fetchTvConfig() {
    var res = await request('/tv/config', { timeout: 10000 });
    if (res.ok && res.data) return res.data;

    // Fallback defaults
    return {
      version: 1,
      branding: {
        title: 'NovaRead TV',
        tagline: 'Anime · Novels · Manga · Movies · Sports'
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
        { key: 'home', label: 'Home' },
        { key: 'novels', label: 'Novels' },
        { key: 'manga', label: 'Manga' },
        { key: 'comics', label: 'Comics' },
        { key: 'anime', label: 'Anime' },
        { key: 'donghua', label: 'Donghua' },
        { key: 'kdrama', label: 'K-Drama' },
        { key: 'cartoon', label: 'Cartoon' },
        { key: 'classic', label: 'Classic' },
        { key: 'movies', label: 'Movies' },
        { key: 'nollywood', label: 'Nollywood' },
        { key: 'sports', label: 'Sports' },
        { key: 'downloads', label: 'Downloads' },
        { key: 'you', label: 'You' }
      ],
      homeRows: [
        { key: 'trendingAnime', label: '🔥 Trending Anime', type: 'anime' },
        { key: 'popularNovels', label: '📚 Popular Novels', type: 'novel' },
        { key: 'topManga', label: '🎨 Top Manga', type: 'manga' },
        { key: 'newMovies', label: '🎬 New Movies', type: 'movie' }
      ]
    };
  }

  // ── Auth (guest stays available; register enables full accounts) ──────
  async function authRegister(username, email, password, recoverySecret, referralCode) {
    var body = {
      username: username || '',
      email: email || '',
      password: password || '',
      recoverySecret: recoverySecret || ''
    };
    if (referralCode) body.referralCode = referralCode;
    var res = await request('/auth/register', { method: 'POST', body: body });
    if (res.ok && res.data && res.data.token) {
      var u = res.data.user || {};
      var session = {
        id: u.id || '', username: u.username || username || '', email: u.email || email || '',
        authToken: res.data.token, plan: u.plan || 'free',
        billingStatus: u.billingStatus || 'none', paidUntil: u.paidUntil || null,
        isPremium: !!u.isPremium, isGuest: false
      };
      saveUserSession(session);
      return session;
    }
    return null;
  }

  async function login(email, password) {
    var res = await request('/auth/login', { method: 'POST', body: { email: email, password: password } });
    if (res.ok && res.data && res.data.token) {
      var u = res.data.user || {};
      var session = {
        id: u.id || '', username: u.username || '', email: u.email || email || '',
        authToken: res.data.token, plan: u.plan || 'free',
        billingStatus: u.billingStatus || 'none', paidUntil: u.paidUntil || null,
        isPremium: !!u.isPremium, isGuest: false
      };
      saveUserSession(session);
      return session;
    }
    return null;
  }

  async function authMe() {
    var res = await request('/auth/me');
    if (res.ok && res.data) {
      var u = res.data.user || res.data;
      var current = getUserSession() || {};
      var merged = Object.assign({}, current, u, { authToken: current.authToken || u.token || '' });
      saveUserSession(merged);
      return merged;
    }
    return null;
  }

  // ── Pairing ────────────────────────────────────────────────────────────
  async function startTvPair() {
    var res = await request('/tv/pair', { method: 'POST', timeout: 12000 });
    if (res.ok && res.data && res.data.data) return res.data.data;
    if (res.ok && res.data) return res.data;
    return null;
  }

  async function pollTvPairStatus(pairId) {
    var res = await request('/tv/pair/' + encodeURIComponent(pairId) + '/status', { timeout: 12000 });
    if (res.ok && res.data && res.data.data) return res.data.data;
    if (res.ok && res.data) return res.data;
    return { status: 'pending' };
  }

  // ── Billing / Premium ──────────────────────────────────────────────────
  async function fetchBillingStatus() {
    var res = await request('/billing/status');
    return res.ok && res.data ? res.data : null;
  }

  async function createBillingCheckout(planId) {
    var res = await request('/billing/checkout', { method: 'POST', body: { planId: planId || 'premium_3_devices' } });
    if (res.ok && res.data && res.data.data) return res.data.data;
    return res.ok && res.data ? res.data : null;
  }

  /** Free preview limits (matches the phone/TV apps: 20% episodic, 20min movies) */
  async function fetchFreePreviewLimits() {
    var status = await fetchBillingStatus();
    var preview = status && status.freePreview ? status.freePreview : null;
    var frac = 0.2, ms = 20 * 60 * 1000;
    if (preview) {
      if (typeof preview.episodicFraction === 'string') frac = parseFloat(preview.episodicFraction) || frac;
      else if (typeof preview.episodicFraction === 'number') frac = preview.episodicFraction;
      if (typeof preview.movieMs === 'string') ms = parseInt(preview.movieMs, 10) || ms;
      else if (typeof preview.movieMs === 'number') ms = preview.movieMs;
    }
    return { episodicFraction: frac, movieMs: ms };
  }

  // ── Media kind normalisation (fixes "anime shown as movie") ───────────
  function normalizeMediaItem(item) {
    if (!item) return {};
    var rawKind = String(item.kind || item.mediaKind || '').toLowerCase();
    var detail = item.detailUrl || item.detailPageUrl || item.url || '';
    var sub = item.subtitle || item.genre || item.author || '';
    var src = item.sourceName || '';
    var id = item.id || '';

    var isDonghua = rawKind === 'donghua' || /donghua/i.test(sub) || /donghua/i.test(src);
    var isAnime = !isDonghua && (
      rawKind === 'anime' ||
      /^anilist([_:]|$)/i.test(id) || /^anilist/i.test(src) ||
      /^anilist:/i.test(detail) ||
      /anime/i.test(sub) || /japanese animation/i.test(sub)
    );
    var isManga = rawKind === 'manga' || /^mangadex([_:]|$)/i.test(id) || /^mangadex:/i.test(detail) ||
      /^weebcentral([_:]|$)/i.test(id) || /^weebcentral:/i.test(detail) || /weebcentral/i.test(src);
    var isComic = rawKind === 'comic' && !isManga;
    var isNovel = rawKind === 'novel' || rawKind === 'novels' || rawKind === 'lightnovel';

    var isTvShow = /tmdb:\/\/tv\//i.test(detail) || detail.indexOf('/tv/') !== -1;
    var effectiveKind;
    if (isDonghua) effectiveKind = 'donghua';
    else if (isAnime) effectiveKind = 'anime';
    else if (isManga) effectiveKind = 'manga';
    else if (isComic) effectiveKind = 'comic';
    else if (isNovel) effectiveKind = 'novel';
    else if (rawKind === 'kdrama') effectiveKind = 'kdrama';
    else if (rawKind === 'cartoon') effectiveKind = 'cartoon';
    else if (rawKind === 'classic') effectiveKind = 'classic';
    else if (rawKind === 'nigerian' || rawKind === 'nollywood') effectiveKind = 'nigerian';
    else if (rawKind === 'tv') effectiveKind = 'tv';
    else if (rawKind === 'movie') effectiveKind = 'movie';
    else if (isTvShow) effectiveKind = 'tv';
    else effectiveKind = 'movie';

    var isVideo = ['movie', 'tv', 'kdrama', 'cartoon', 'donghua', 'classic', 'nigerian', 'anime'].indexOf(effectiveKind) !== -1;

    return {
      id: id || (item.title ? item.title.replace(/\s+/g, '_') : Math.random().toString(36).substring(2)),
      title: item.title || 'Untitled',
      coverUrl: item.coverUrl || item.poster || item.thumbnail || '',
      backdropUrl: item.backdropUrl || item.banner || item.coverUrl || '',
      detailPageUrl: detail,
      synopsis: item.synopsis || item.description || '',
      genre: item.genre || sub || '',
      author: item.author || sub || '',
      rating: item.rating || item.score || '',
      year: item.year || item.releaseDate || '',
      sourceName: src || 'NovaCloud',
      mediaKind: effectiveKind,
      isAnime: isAnime,
      isDonghua: isDonghua,
      isManga: isManga,
      isComic: isComic,
      isNovel: isNovel,
      isVideo: isVideo,
      videoId: item.videoId || '',
      mediaType: /tmdb:\/\/tv\//i.test(detail) ? 'tv' : (/tmdb:\/\/movie\//i.test(detail) ? 'movie' : '')
    };
  }

  // ── AniList (CORS-friendly, same as the TV app) ────────────────────────
  async function anilistQuery(query, variables) {
    try {
      var resp = await fetch(ANILIST_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
        body: JSON.stringify({ query: query, variables: variables || {} })
      });
      var json = await resp.json();
      return (json && json.data) || null;
    } catch (e) {
      return null;
    }
  }

  async function fetchAnilistTrending(perPage) {
    var data = await anilistQuery(
      'query ($n: Int) { Page(page: 1, perPage: $n) { media(type: ANIME, sort: TRENDING_DESC) { id title { english romaji } coverImage { extraLarge large } bannerImage description genres averageScore seasonYear format } } }',
      { n: perPage || 20 }
    );
    var media = (data && data.Page && data.Page.media) || [];
    return media.map(function (m) {
      var kind = (m.format === 'MOVIE') ? 'anime' : 'anime'; // AniList is ALWAYS anime
      return {
        id: 'anilist_' + m.id,
        title: (m.title && (m.title.english || m.title.romaji)) || 'Anime',
        coverUrl: (m.coverImage && (m.coverImage.extraLarge || m.coverImage.large)) || '',
        backdropUrl: m.bannerImage || (m.coverImage && m.coverImage.extraLarge) || '',
        synopsis: (m.description || '').replace(/<[^>]*>/g, ''),
        genre: (m.genres || []).join(', '),
        rating: m.averageScore ? String(m.averageScore / 10) : '',
        year: m.seasonYear || '',
        sourceName: 'AniList',
        kind: kind,
        mediaKind: 'anime',
        isAnime: true,
        detailUrl: 'anilist:' + m.id
      };
    });
  }

  async function fetchAnilistSearch(query) {
    if (!query || !query.trim()) return [];
    var data = await anilistQuery(
      'query ($s: String) { Page(page: 1, perPage: 16) { media(search: $s, type: ANIME, sort: SEARCH_MATCH) { id title { english romaji } coverImage { extraLarge large } bannerImage description genres averageScore seasonYear format } } }',
      { s: query }
    );
    var media = (data && data.Page && data.Page.media) || [];
    return media.map(function (m) {
      return {
        id: 'anilist_' + m.id,
        title: (m.title && (m.title.english || m.title.romaji)) || 'Anime',
        coverUrl: (m.coverImage && (m.coverImage.extraLarge || m.coverImage.large)) || '',
        backdropUrl: m.bannerImage || '',
        synopsis: (m.description || '').replace(/<[^>]*>/g, ''),
        genre: (m.genres || []).join(', '),
        rating: m.averageScore ? String(m.averageScore / 10) : '',
        year: m.seasonYear || '',
        sourceName: 'AniList',
        kind: 'anime',
        mediaKind: 'anime',
        isAnime: true,
        detailUrl: 'anilist:' + m.id
      };
    });
  }

  // ── Content Feeds ──────────────────────────────────────────────────────
  async function fetchContentHome(type, page) {
    type = String(type || '').toLowerCase();
    page = page || 1;

    // Manga: WeebCentral (primary, via jina reader) then MangaDex
    if (type === 'manga') {
      return weebCentralHome(page);
    }

    var res = await request('/content/home?type=' + encodeURIComponent(type) + '&page=' + page);
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.items) && res.data.data.items.length) {
      return res.data.data.items.map(normalizeMediaItem);
    }

    // AniList direct fallback for anime when the server endpoint is busy
    if (type === 'anime' || type === 'trendinganime') {
      return fetchAnilistTrending();
    }
    return [];
  }

  // ── r.jina.ai reader proxy (sends CORS headers echoing the origin) ─────
  // WeebCentral is SSR'd; jina returns the rendered page.  html:true asks for
  // processed HTML (needed for chapter lists + page images).
  // fetchWithTimeout always resolves/rejects — a stalled socket can never
  // hang playback or a tab render.
  function fetchWithTimeout(url, opts, ms) {
    return new Promise(function (resolve, reject) {
      var ctrl = new AbortController();
      var timer = setTimeout(function () {
        try { ctrl.abort(); } catch (e) {}
        reject(new Error('timeout'));
      }, ms || 45000);
      fetch(url, {
        method: opts.method || 'GET',
        headers: opts.headers || {},
        body: opts.body,
        signal: ctrl.signal
      })
        .then(function (r) { clearTimeout(timer); resolve(r); })
        .catch(function (e) { clearTimeout(timer); reject(e); });
    });
  }

  async function jinaFetch(targetUrl, opts) {
    opts = opts || {};
    var headers = { 'Accept': 'text/plain, text/html, */*' };
    if (opts.html) headers['x-return-format'] = 'html';
    try {
      var resp = await fetchWithTimeout('https://r.jina.ai/' + targetUrl, { headers: headers }, opts.timeout || 45000);
      if (!resp.ok) return '';
      var textPromise = resp.text();
      var text = await Promise.race([
        textPromise,
        new Promise(function (resolve) { setTimeout(function () { resolve(''); }, 20000); })
      ]);
      return text || '';
    } catch (e) {
      return '';
    }
  }

  // ── WeebCentral (primary manga source, same site the TV app scrapes) ───
  var WEEBCENTRAL_BASE = 'https://weebcentral.com';

  function weebcentralCover(seriesId) {
    return 'https://temp.compsci88.com/cover/fallback/' + encodeURIComponent(seriesId) + '.jpg';
  }

  function slugToTitle(slug) {
    return String(slug || '').replace(/-/g, ' ').trim();
  }

  // Direct WeebCentral request (EXACT Android-app recipe): server-rendered
  // HTML, hard-bounded timeouts, jina reader as silent CORS fallback.
  // Desktop UA + Referer + HX-Request — the same headers the Android
  // WeebCentralScraper sends. Browsers ignore forbidden headers (they send
  // their own real UA, which passes Cloudflare); server-side contexts need it.
  var WC_HEADERS = {
    'Accept': 'text/html,application/xhtml+xml,*/*;q=0.8',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Referer': 'https://weebcentral.com',
    'HX-Request': 'true'
  };

  // WeebCentral direct availability: 'unknown' until first attempt, then
  // 'ok' (TV-style browsers, no CORS enforcement) or 'blocked' (desktop
  // browsers enforce CORS → switch the manga pipeline to MangaDex instantly).
  var wcDirectState = 'unknown';

  async function wcFetch(path, opts) {
    opts = opts || {};
    // 0) Skip direct entirely when CORS blocking is already known
    if (wcDirectState === 'blocked') {
      return await jinaFetch(WEEBCENTRAL_BASE + path, { html: !!opts.html, timeout: opts.jinaTimeout || 40000 });
    }
    // 1) Direct — fast path (like the Android app). CORS failures throw in
    //    milliseconds on strict browsers, so retries only cost time when the
    //    site itself hiccups (403/429/5xx) — one quick retry, then fallback.
    var attempts = [0, 1200];
    for (var i = 0; i < attempts.length; i++) {
      if (attempts[i] > 0) {
        await new Promise(function (r) { setTimeout(r, attempts[i]); });
      }
      try {
        var resp = await fetchWithTimeout(WEEBCENTRAL_BASE + path, { headers: WC_HEADERS }, opts.directTimeout || 8000);
        if (resp.ok) {
          var text = await Promise.race([
            resp.text(),
            new Promise(function (r) { setTimeout(function () { r(''); }, 6000); })
          ]);
          if (text && text.length > 500) {
            wcDirectState = 'ok';
            return text;
          }
        }
      } catch (e) {
        wcDirectState = 'blocked';
        break; // CORS/abort → reader/MangaDex fallback
      }
    }
    // 2) jina reader fallback
    return await jinaFetch(WEEBCENTRAL_BASE + path, { html: !!opts.html, timeout: opts.jinaTimeout || 40000 });
  }

  /** Home grid: the WeebCentral front page (popular + latest). */
  async function weebCentralHome(page) {
    page = Math.max(1, page || 1);
    if (page > 1) return mangadexHome(page); // deeper pages from MangaDex

    // Always try wcFetch — it handles direct → jina reader fallback internally.
    // Only fall back to MangaDex if wcFetch returns nothing.
    var html = await wcFetch('/', { html: true, directTimeout: 12000 });
    if (!html) return mangadexHome(1);

    var items = [];
    var seen = {};
    var re = /href="(?:https:\/\/weebcentral\.com)?\/series\/([A-Za-z0-9]+)\/([^"#?\s)\\]+)"/g;
    var m;
    while ((m = re.exec(html)) && items.length < 36) {
      var id = m[1];
      if (seen[id]) continue;
      seen[id] = 1;
      items.push(normalizeMediaItem({
        id: 'weebcentral_' + id,
        title: slugToTitle(m[2]),
        coverUrl: weebcentralCover(id),
        backdropUrl: weebcentralCover(id),
        sourceName: 'WeebCentral',
        kind: 'manga',
        detailUrl: 'weebcentral:' + id
      }));
    }
    return items.length ? items : mangadexHome(1);
  }

  /** Search — same POST htmx endpoint the Android scraper uses. */
  async function weebCentralSearch(query) {
    if (!query || !query.trim()) return [];
    var q = query.trim();

    // CORS-blocked browsers (desktop): MangaDex results are instant and
    // complete — don't burn 10s+ on the reader proxy.
    if (wcDirectState === 'blocked') {
      return mangadexSearch(q);
    }

    var html = '';

    // Direct POST (exact Android form call)
    try {
      var resp = await fetchWithTimeout(
        WEEBCENTRAL_BASE + '/search/simple?location=main',
        {
          headers: Object.assign({}, WC_HEADERS, { 'Content-Type': 'application/x-www-form-urlencoded' }),
          method: 'POST',
          body: 'text=' + encodeURIComponent(q)
        },
        10000
      );
      if (resp.ok) {
        html = await Promise.race([
          resp.text(),
          new Promise(function (r) { setTimeout(function () { r(''); }, 6000); })
        ]);
        if (html) wcDirectState = 'ok';
      }
    } catch (e) {
      wcDirectState = 'blocked';
      return mangadexSearch(q);
    }

    // Fallback: GET search page via jina reader
    if (!html) {
      html = await jinaFetch(WEEBCENTRAL_BASE + '/search?text=' + encodeURIComponent(q), { timeout: 40000 });
    }
    if (!html) return [];

    var items = [];
    var seen = {};
    var re = /href="(?:https:\/\/weebcentral\.com)?\/series\/([A-Za-z0-9]+)\/([^"#?\s)\\]+)"?(?=\s|>|$|\))/g;
    var m;
    while ((m = re.exec(html)) && items.length < 24) {
      var id = m[1];
      if (seen[id]) continue;
      seen[id] = 1;
      var title = slugToTitle(m[2]);
      if (!title || /^(home|search|random|login|register)$/i.test(title)) continue;
      items.push(normalizeMediaItem({
        id: 'weebcentral_' + id,
        title: title,
        coverUrl: weebcentralCover(id),
        backdropUrl: weebcentralCover(id),
        sourceName: 'WeebCentral',
        kind: 'manga',
        detailUrl: 'weebcentral:' + id
      }));
    }
    return items;
  }

  /** Full chapter list — every chapter, numeric ascending (Android path). */
  // ── WeebCentral chapter parsing (shared by direct + reader paths) ──────
  function parseWcChapters(html) {
    var chapters = [];
    if (!html) return chapters;
    var seen = {};
    // jina.ai returns markdown format: [text](url) — match both that and raw hrefs
    var re = /\]\((?:https:\/\/weebcentral\.com)?\/chapters\/([A-Za-z0-9]+)\)/gi;
    var m;
    while ((m = re.exec(html))) {
      if (seen[m[1]]) continue;
      seen[m[1]] = 1;
      // Look backward from the match for "Chapter N" or "Episode N" in the link text
      var before = html.substr(Math.max(0, m.index - 200), 200);
      var numM = /(chapter|episode)\s*([0-9]+(?:\.[0-9]+)?)/i.exec(before);
      var num = numM ? parseFloat(numM[2]) : 0;
      var label = numM ? numM[1] : 'Chapter';
      chapters.push({
        id: m[1],
        title: label.charAt(0).toUpperCase() + label.slice(1) + ' ' + (num || (chapters.length + 1)),
        url: 'weebcentral-chapter:' + m[1],
        chapterNumber: num,
        seasonNumber: 0,
        sortKey: num || 0
      });
    }
    // Fallback: also try raw href format (direct HTML)
    if (!chapters.length) {
      var re2 = /href="(?:https:\/\/weebcentral\.com)?\/chapters\/([A-Za-z0-9]+)"/gi;
      while ((m = re2.exec(html))) {
        if (seen[m[1]]) continue;
        seen[m[1]] = 1;
        var windowText = html.substr(m.index, 1600).replace(/<[^>]*>/g, ' ');
        var numM = /(chapter|episode)\s*([0-9]+(?:\.[0-9]+)?)/i.exec(windowText);
        var num = numM ? parseFloat(numM[2]) : 0;
        var label = numM ? numM[1] : 'Chapter';
        chapters.push({
          id: m[1],
          title: label.charAt(0).toUpperCase() + label.slice(1) + ' ' + (num || (chapters.length + 1)),
          url: 'weebcentral-chapter:' + m[1],
          chapterNumber: num,
          seasonNumber: 0,
          sortKey: num || 0
        });
      }
    }
    // WeebCentral lists newest-first; specials (e.g. "Chapter 10.5" extras)
    // may have no number in the markup. Infer from parsed neighbours:
    // midpoint between the newer and older parsed chapters around the gap.
    for (var k = 0; k < chapters.length; k++) {
      if (!chapters[k].chapterNumber) {
        var newerVal = 0, olderVal = 0;
        for (var up = k - 1; up >= 0; up--) {
          if (chapters[up].chapterNumber) { newerVal = chapters[up].chapterNumber; break; }
        }
        for (var dn = k + 1; dn < chapters.length; dn++) {
          if (chapters[dn].chapterNumber) { olderVal = chapters[dn].chapterNumber; break; }
        }
        if (newerVal && olderVal) chapters[k].chapterNumber = olderVal + (newerVal - olderVal) / 2;
        else if (olderVal) chapters[k].chapterNumber = olderVal + 0.5;
        else if (newerVal) chapters[k].chapterNumber = Math.max(0.5, newerVal - 0.5);
        else chapters[k].chapterNumber = (chapters.length - k);
        chapters[k].chapterNumber = Math.round(chapters[k].chapterNumber * 10) / 10;
        chapters[k].title = 'Chapter ' + chapters[k].chapterNumber;
        chapters[k].sortKey = chapters[k].chapterNumber;
      }
    }
    chapters.sort(function (a, b) { return (a.sortKey || 0) - (b.sortKey || 0); });
    return chapters;
  }

  // localStorage cache — repeated opens must be instant and never re-hit
  // the source (rate limits + TV latency make this essential).
  function lsGetJson(key, maxAgeMs) {
    try {
      var raw = localStorage.getItem(key);
      if (!raw) return null;
      var obj = JSON.parse(raw);
      if (maxAgeMs && obj.t && (Date.now() - obj.t > maxAgeMs)) return null;
      return (obj.v !== undefined) ? obj.v : obj;
    } catch (e) { return null; }
  }

  function lsSetJson(key, value) {
    try { localStorage.setItem(key, JSON.stringify({ t: Date.now(), v: value })); } catch (e) {}
  }

  async function weebCentralChapters(seriesId) {
    var id = String(seriesId || '').replace(/^weebcentral:/i, '');
    var cached = lsGetJson('wc_chapters_' + id, 30 * 60 * 1000); // 30 min
    if (cached && cached.length) return cached;

    var html = '';
    if (wcDirectState !== 'blocked') {
      // Direct path only here — the jina reader path runs later so the fast
      // MangaDex fallback can step in first on CORS-blocked browsers.
      try {
        var resp = await fetchWithTimeout(
          WEEBCENTRAL_BASE + '/series/' + encodeURIComponent(id) + '/full-chapter-list',
          { headers: WC_HEADERS }, 10000);
        if (resp.ok) {
          html = await Promise.race([
            resp.text(),
            new Promise(function (r) { setTimeout(function () { r(''); }, 6000); })
          ]);
          if (html) wcDirectState = 'ok';
        }
      } catch (e) {
        wcDirectState = 'blocked';
      }
    }
    var chapters = parseWcChapters(html);
    if (chapters.length) {
      lsSetJson('wc_chapters_' + id, chapters);
      return chapters;
    }

    // Direct unavailable (CORS-blocked desktop or source hiccup) — the reader
    // proxy carries the CORRECT WeebCentral list; MangaDex is only a
    // title-matched fallback and runs after this in fetchChapters.
    var md = await jinaFetch(WEEBCENTRAL_BASE + '/series/' + encodeURIComponent(id) + '/full-chapter-list', { timeout: 40000 });
    chapters = parseWcChapters(md);
    if (!chapters.length) {
      // Reader can 429 under burst — one patient retry clears it
      await new Promise(function (r) { setTimeout(r, 2500); });
      md = await jinaFetch(WEEBCENTRAL_BASE + '/series/' + encodeURIComponent(id) + '/full-chapter-list', { timeout: 40000 });
      chapters = parseWcChapters(md);
    }
    if (chapters.length) lsSetJson('wc_chapters_' + id, chapters);
    return chapters;
  }

  /** Page images for one chapter (reader images endpoint, Android path). */
  async function weebCentralPages(chapterId) {
    var cached = lsGetJson('wc_pages_' + chapterId, 60 * 60 * 1000); // 1 hour
    if (cached && cached.length) return cached;

    var html = await wcFetch(
      '/chapters/' + encodeURIComponent(chapterId) + '/images?reading_style=long_strip',
      { directTimeout: 8000, jinaTimeout: 40000 }
    );
    var pages = [];
    if (html) {
      var re = /<img[^>]+src="([^"]+)"|!\[[^\]]*\]\((https?:\/\/[^)]+)\)/gi;
      var m;
      while ((m = re.exec(html))) {
        var u = m[1] || m[2];
        if (!u) continue;
        if (/broken_image|logo|icon|avatar|badge|brand|\.svg(\?|$)/i.test(u)) continue;
        if (/\/cover\//i.test(u) && !/\/manga\//i.test(u)) continue;
        pages.push(u);
      }
    }
    if (pages.length) lsSetJson('wc_pages_' + chapterId, pages);
    return pages;
  }

  async function mangadexHome(page) {
    page = Math.max(1, page || 1);
    var offset = (page - 1) * 24;
    try {
      var url = MANGADEX_URL + '/manga?limit=24&offset=' + offset +
        '&includes[]=cover_art&contentRating[]=safe&contentRating[]=suggestive' +
        '&order[followedCount]=desc&hasAvailableChapters=true';
      var resp = await fetch(url);
      var json = await resp.json();
      var list = (json && json.data) || [];
      return list.map(function (m) { return mangadexToItem(m); });
    } catch (e) {
      return [];
    }
  }

  async function mangadexSearch(query) {
    if (!query || !query.trim()) return [];
    try {
      var url = MANGADEX_URL + '/manga?limit=20&includes[]=cover_art' +
        '&contentRating[]=safe&contentRating[]=suggestive&title=' + encodeURIComponent(query);
      var resp = await fetch(url);
      var json = await resp.json();
      var list = (json && json.data) || [];
      return list.map(function (m) { return mangadexToItem(m); });
    } catch (e) {
      return [];
    }
  }

  function mangadexToItem(m) {
    var cover = '';
    (m.relationships || []).forEach(function (rel) {
      if (rel.type === 'cover_art' && rel.attributes && rel.attributes.fileName) {
        cover = MANGADEX_UPLOADS + '/covers/' + m.id + '/' + rel.attributes.fileName + '.512.jpg';
      }
    });
    var attrs = m.attributes || {};
    var title = (attrs.title && (attrs.title.en || Object.values(attrs.title)[0])) || 'Manga';
    var desc = (attrs.description && (attrs.description.en || Object.values(attrs.description)[0])) || '';
    return normalizeMediaItem({
      id: 'mangadex_' + m.id,
      title: title,
      coverUrl: cover,
      backdropUrl: cover,
      synopsis: desc.replace(/<[^>]*>/g, ''),
      genre: (attrs.tags || []).map(function (t) { return (t.attributes && t.attributes.name && t.attributes.name.en) || ''; }).filter(Boolean).slice(0, 3).join(', '),
      year: (attrs.year || ''),
      sourceName: 'MangaDex',
      kind: 'manga',
      detailUrl: 'mangadex:' + m.id
    });
  }

  /** Chapter list straight from MangaDex, sorted by chapter number ascending. */
  async function mangadexChapters(mangaId) {
    var cached = lsGetJson('md_chapters_' + mangaId, 30 * 60 * 1000);
    if (cached && cached.length) return cached;

    var chapters = [];
    try {
      // Paginate (500 per page) — long series like One Piece need it.
      // NOTE: no contentRating[]=erotica and no includeExternalUrl filter —
      // both silently return an empty feed for anonymous requests.
      for (var page = 0; page < 5; page++) {
        var offset = page * 500;
        var url = MANGADEX_URL + '/manga/' + encodeURIComponent(mangaId) +
          '/feed?translatedLanguage[]=en&limit=500&offset=' + offset +
          '&order[chapter]=asc&order[volume]=asc' +
          '&contentRating[]=safe&contentRating[]=suggestive';
        var resp = await fetch(url);
        if (!resp.ok) break;
        var json = await resp.json();
        var list = (json && json.data) || [];
        if (!list.length) break;
        list.forEach(function (ch) {
          var a = ch.attributes || {};
          if (a.externalUrl) return; // external-only chapters have no pages here
          var num = parseFloat(a.chapter || '0') || 0;
          chapters.push({
            id: ch.id,
            title: a.title || ('Chapter ' + (a.chapter || '0')),
            url: 'mangadex-chapter:' + ch.id,
            chapterNumber: Math.round(num * 10) / 10,
            volumeNumber: a.volume ? parseFloat(a.volume) : 0,
            sortKey: num
          });
        });
        if (list.length < 500) break;
        offset += 500;
      }
    } catch (e) { /* return whatever was collected */ }
    chapters.sort(function (a, b) { return a.sortKey - b.sortKey; });
    if (chapters.length) lsSetJson('md_chapters_' + mangaId, chapters);
    return chapters;
  }

  /** Page image URLs via the at-home server (same flow as the Android app). */
  async function mangadexPages(chapterId) {
    try {
      var resp = await fetch(MANGADEX_URL + '/at-home/server/' + encodeURIComponent(chapterId));
      var json = await resp.json();
      var host = json && json.baseUrl;
      var ch = json && json.chapter;
      if (!host || !ch) return [];
      var files = (ch.data && ch.data.length) ? ch.data : (ch.dataSaver || []);
      return files.map(function (f) {
        return host + '/' + (ch.data && ch.data.length ? 'data' : 'data-saver') + '/' + ch.hash + '/' + f;
      });
    } catch (e) {
      return [];
    }
  }

  /** Find a title on MangaDex and return its chapters (WeebCentral fallback).
   *  Fuzzy title matching: the best-matching result wins, and up to three
   *  candidates are tried before giving up. */
  function normMangaTitle(t) {
    return String(t || '').toLowerCase().replace(/[^a-z0-9]+/g, '');
  }

  function mangaTitleScore(candidate, want) {
    var a = normMangaTitle(candidate);
    var b = normMangaTitle(want);
    if (!a || !b) return 0;
    if (a === b) return 100;
    if (a.indexOf(b) !== -1 || b.indexOf(a) !== -1) return 80;
    // Word-overlap scoring for cases like "Na Honjaman Level-Up" vs "Solo Leveling"
    var wa = a.split(/(?=[A-Z0-9])/).join(' ').split(' ').filter(Boolean);
    var wb = b.split(/(?=[A-Z0-9])/).join(' ').split(' ').filter(Boolean);
    var common = 0;
    wb.forEach(function (w) { if (wa.indexOf(w) !== -1) common++; });
    return Math.round((common / Math.max(1, wb.length)) * 60);
  }

  async function mangadexChaptersByTitle(title) {
    try {
      var results = await mangadexSearch(title);
      if (!results.length) return [];
      results.sort(function (a, b) {
        return mangaTitleScore(b.title, title) - mangaTitleScore(a.title, title);
      });
      var tried = 0;
      for (var i = 0; i < results.length && tried < 3; i++) {
        var id = String(results[i].detailPageUrl || '').replace(/^mangadex:/i, '');
        if (!id) continue;
        tried++;
        var chs = await mangadexChapters(id);
        if (chs.length) return chs;
      }
      return [];
    } catch (e) {
      return [];
    }
  }

  // ── Numeric chapter/episode ordering (fixes wrong episode order) ───────
  function parseEpisodeNumber(title, fallback) {
    if (!title) return fallback || 0;
    var m = String(title).match(/(?:ep(?:isode)?|ch(?:apter)?|cap\.?|e)\s*\.?\s*(\d+(?:\.\d+)?)/i);
    if (m) return parseFloat(m[1]) || 0;
    m = String(title).match(/^(\d+(?:\.\d+)?)/);
    if (m) return parseFloat(m[1]) || 0;
    return fallback || 0;
  }

  function sortChapters(chapters) {
    if (!Array.isArray(chapters)) return [];
    var list = chapters.slice();
    var i, ch;

    // 1) Numeric value for every entry: chapterNumber first, then the title.
    //    NEVER fabricate numbers from the backend's original order — that is
    //    what caused newest-first lists to stay unordered.
    for (i = 0; i < list.length; i++) {
      ch = list[i];
      if (typeof ch.chapterNumber === 'number' && ch.chapterNumber > 0) continue;
      var fromTitle = parseEpisodeNumber(ch.title, 0);
      ch.chapterNumber = fromTitle > 0 ? fromTitle : 0;
    }

    // 2) Unnumbered entries get interpolated between parsed neighbours
    //    (midpoint), so specials/extras land in the right slot.
    for (i = 0; i < list.length; i++) {
      if (list[i].chapterNumber > 0) continue;
      var newer = 0, older = 0, up, dn;
      for (up = i - 1; up >= 0; up--) {
        if (list[up].chapterNumber > 0) { newer = list[up].chapterNumber; break; }
      }
      for (dn = i + 1; dn < list.length; dn++) {
        if (list[dn].chapterNumber > 0) { older = list[dn].chapterNumber; break; }
      }
      if (newer && older) list[i].chapterNumber = older + (newer - older) / 2;
      else if (newer) list[i].chapterNumber = Math.max(0.5, newer - 0.5);
      else if (older) list[i].chapterNumber = older + 0.5;
      else list[i].chapterNumber = i + 1; // nothing parsed anywhere — keep flow
      list[i].chapterNumber = Math.round(list[i].chapterNumber * 100) / 100;
    }

    // 3) Season-major ascending when multi-season data exists, else pure
    //    numeric ascending. Stable (equal keys keep fetch order).
    var anySeason = false;
    for (i = 0; i < list.length; i++) {
      if ((parseFloat(list[i].seasonNumber) || 0) > 1) { anySeason = true; break; }
    }
    list.sort(function (a, b) {
      var na = parseFloat(a.chapterNumber) || 0;
      var nb = parseFloat(b.chapterNumber) || 0;
      if (anySeason) {
        var sa = parseFloat(a.seasonNumber) || 1;
        var sb = parseFloat(b.seasonNumber) || 1;
        if (sa !== sb) return sa - sb;
      }
      return na - nb;
    });
    return list;
  }

  function extractSeasonEpisode(detailUrl, chapterUrl, chapterNumber) {
    // tmdb-episode://94664/1/2 | tv:94664:1:2 | tmdb://tv/94664
    var m;
    if (chapterUrl) {
      m = /tmdb-episode:\/\/(\d+)\/(\d+)\/(\d+)/.exec(chapterUrl);
      if (m) return { tmdbId: m[1], type: 'tv', season: m[2], episode: m[3] };
      m = /^(?:tmdb|tv):(\d+):(\d+):(\d+)$/.exec(chapterUrl.trim());
      if (m) return { tmdbId: m[1], type: 'tv', season: m[2], episode: m[3] };
    }
    if (detailUrl) {
      m = /tmdb:\/\/(movie|tv)\/(\d+)/.exec(detailUrl.trim());
      if (m) {
        if (m[1] === 'movie') return { tmdbId: m[2], type: 'movie' };
        var ep = (chapterNumber && chapterNumber > 0) ? String(Math.floor(chapterNumber)) : '1';
        return { tmdbId: m[2], type: 'tv', season: '1', episode: ep };
      }
    }
    return null;
  }

  // ── Chapters / Episodes (unified, mirrors TvMediaRepository) ───────────
  async function fetchChapters(kind, detailUrl, title, sourceName, chapterNumber) {
    kind = String(kind || '').toLowerCase();
    detailUrl = detailUrl || '';
    var titleStr = (title && title.title) ? title.title : (title || '');

    // MangaDex items → client-side feed (backend bridge returns empty)
    if (kind === 'manga' && /^mangadex:/i.test(detailUrl)) {
      return mangadexChapters(detailUrl.replace(/^mangadex:/i, ''));
    }
    // WeebCentral items → direct (TV) → same title on MangaDex (desktop
    // CORS-safe) → reader proxy last resort.
    if (kind === 'manga' && /^weebcentral:/i.test(detailUrl)) {
      var wcId = detailUrl.replace(/^weebcentral:/i, '');
      var wcChapters = await weebCentralChapters(wcId);
      if (wcChapters.length) return wcChapters;

      var mdChapters = await mangadexChaptersByTitle(titleStr);
      if (mdChapters.length) return mdChapters;

      var jinaHtml = await jinaFetch(
        WEEBCENTRAL_BASE + '/series/' + encodeURIComponent(wcId) + '/full-chapter-list',
        { timeout: 40000 });
      return parseWcChapters(jinaHtml);
    }

    var res = await request('/content/chapters', {
      method: 'POST',
      body: { kind: kind, detailUrl: detailUrl, title: titleStr, sourceName: sourceName || '' }
    });
    var chapters = [];
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.chapters)) {
      chapters = res.data.data.chapters.map(function (c) {
        return {
          title: c.title || '',
          url: c.url || '',
          chapterNumber: (typeof c.chapterNumber === 'number') ? c.chapterNumber : (parseEpisodeNumber(c.title, 0)),
          seasonNumber: c.seasonNumber || c.season || 0
        };
      });
    }

    // Anime: if the backend bridge came up empty, fall back to the Consumet
    // providers (same fan-out the TV app uses) and keep the fullest list.
    if (kind === 'anime' && chapters.length === 0) {
      chapters = await fetchAnimeEpisodesParallel(titleStr);
    }

    // ReadNovelFull novels → client-side chapter archive (Android recipe)
    if (kind === 'novel' && /readnovelfull\.com/i.test(detailUrl)) {
      var rnfChapters = await readNovelFullChapters(detailUrl);
      if (rnfChapters.length) return rnfChapters;
    }

    // Novels: if this source has no chapter bridge, offer the RoyalRoad copy
    if (kind === 'novel' && chapters.length === 0 && titleStr) {
      chapters = await fetchRoyalroadChaptersByTitle(titleStr);
    }

    return sortChapters(chapters);
  }

  async function fetchRoyalroadChaptersByTitle(t) {
    try {
      var res = await request('/content/search?type=novel&q=' + encodeURIComponent(t));
      var items = (res.ok && res.data && res.data.data && res.data.data.items) || [];
      var exact = null;
      for (var i = 0; i < items.length; i++) {
        if ((items[i].title || '').toLowerCase().indexOf(String(t).toLowerCase()) === 0) { exact = items[i]; break; }
      }
      if (!exact) return [];
      var ch = await request('/content/chapters', {
        method: 'POST',
        body: { kind: 'novel', detailUrl: exact.detailUrl, title: exact.title, sourceName: 'RoyalRoad' }
      });
      var list = (ch.ok && ch.data && ch.data.data && ch.data.data.chapters) || [];
      return list.map(function (c) {
        return { title: c.title || '', url: c.url || '', chapterNumber: c.chapterNumber || 0, seasonNumber: 0 };
      });
    } catch (e) {
      return [];
    }
  }

  // ── Consumet anime providers (secondary episode source) ───────────────
  async function fetchAnimeEpisodes(provider, query) {
    var res = await request('/content/anime/episodes?provider=' + encodeURIComponent(provider || 'gogoanime') + '&q=' + encodeURIComponent(query || ''));
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.episodes)) {
      return res.data.data.episodes.map(function (ep) {
        var num = ep.number || ep.episodeNumber || 0;
        return {
          title: ep.title || ('Episode ' + num),
          url: ep.url || '',
          chapterNumber: parseFloat(num) || 0,
          seasonNumber: 0,
          provider: provider
        };
      }).filter(function (e) { return e.url; });
    }
    return [];
  }

  async function fetchAnimeEpisodesParallel(query) {
    var providers = ['hianime', 'gogoanime', 'animepahe', 'zoro'];
    var results = await Promise.all(providers.map(function (provider) {
      return fetchAnimeEpisodes(provider, query).then(function (episodes) {
        return episodes.map(function (episode) {
          episode.provider = provider;
          return episode;
        });
      }).catch(function () { return []; });
    }));
    var best = results.sort(function (a, b) { return b.length - a.length; })[0] || [];
    return sortChapters(best);
  }

  async function fetchAnimeStream(provider, episodeId) {
    var cleanId = episodeId || '';
    if (cleanId.indexOf('consumet://') === 0) {
      var parts = cleanId.replace('consumet://', '').split('/');
      if (parts.length > 1) {
        cleanId = decodeURIComponent(parts.slice(1).join('/'));
      }
    }
    var res = await request('/content/anime/stream?provider=' + encodeURIComponent(provider || 'gogoanime') + '&episodeId=' + encodeURIComponent(cleanId));
    if (res.ok && res.data && res.data.data && res.data.data.route === 'direct') {
      return res.data.data.url || null;
    }
    return null;
  }

  // ── Novel text ──────────────────────────────────────────────────────────
  async function fetchChapterText(chapterUrl, title, sourceName) {
    // ReadNovelFull chapters are scraped client-side (Android recipe)
    if (/readnovelfull\.com/i.test(String(chapterUrl || ''))) {
      var rnfText = await readNovelFullChapterText(chapterUrl);
      if (rnfText) return rnfText;
    }
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

  // ── Manga pages (backend first, MangaDex client fallback) ─────────────
  async function fetchMangaPages(chapterUrl) {
    if (/^mangadex-chapter:/i.test(String(chapterUrl || ''))) {
      return mangadexPages(String(chapterUrl).replace(/^mangadex-chapter:/i, ''));
    }
    if (/^weebcentral-chapter:/i.test(String(chapterUrl || ''))) {
      return weebCentralPages(String(chapterUrl).replace(/^weebcentral-chapter:/i, ''));
    }
    var res = await request('/content/manga-pages', {
      method: 'POST',
      body: { chapterUrl: chapterUrl || '' }
    });
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.pages)) {
      return res.data.data.pages;
    }
    return [];
  }

  // ── Multi-source novels (port of the Android NovelSource scrapers) ─────
  // ReadNovelFull is fetched DIRECTLY with desktop headers (works on TV
  // browsers; identical to WeebCentral). On CORS-strict browsers the jina
  // reader fallback carries it. RoyalRoad gets extra listings (trending /
  // rising stars / best rated) parsed from the reader proxy.

  function decodeEntities(s) {
    if (!s) return '';
    return s.replace(/&amp;/g, '&').replace(/&quot;/g, '"').replace(/&#0?39;/g, "'")
      .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&nbsp;/g, ' ').replace(/&#x27;/g, "'");
  }

  var READNOVELFULL_BASE = 'https://readnovelfull.com';
  var RNF_HEADERS = {
    'Accept': 'text/html,application/xhtml+xml,*/*;q=0.8',
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Referer': READNOVELFULL_BASE
  };

  async function readNovelFullFetch(path, opts) {
    opts = opts || {};
    if (wcDirectState !== 'blocked') {
      try {
        var resp = await fetchWithTimeout(READNOVELFULL_BASE + path, { headers: RNF_HEADERS }, opts.directTimeout || 10000);
        if (resp.ok) {
          var text = await Promise.race([
            resp.text(),
            new Promise(function (r) { setTimeout(function () { r(''); }, 6000); })
          ]);
          if (text && text.length > 500) return text;
        }
      } catch (e) { /* CORS → reader fallback */ }
    }
    return await jinaFetch(READNOVELFULL_BASE + path, { html: true, timeout: opts.jinaTimeout || 40000 });
  }

  /** ReadNovelFull hot novels — Android: GET /novel-list/hot-novel?page= */
  async function readNovelFullPopular(page) {
    page = Math.max(1, page || 1);
    var html = await readNovelFullFetch('/novel-list/hot-novel?page=' + page, {});
    if (!html) return [];
    var out = [];
    var seen = {};
    var re = /<h3 class="(?:novel-title|truyen-title)"><a href="([^"]+\.html)"[^>]*title="([^"]*)"/gi;
    var m;
    while ((m = re.exec(html)) && out.length < 30) {
      var href = m[1];
      var key = href.toLowerCase();
      if (seen[key]) continue;
      seen[key] = 1;
      var title = decodeEntities(m[2] || '').trim();
      if (!title) continue;
      // Cover: first data-src/src image after this anchor (row-level)
      var windowText = html.substr(m.index, 2500);
      var covM = /<img[^>]+(?:data-src|src)="([^"]+)"/i.exec(windowText);
      var cover = covM ? covM[1] : '';
      if (cover && cover.indexOf('http') !== 0) cover = READNOVELFULL_BASE + cover;
      var detailUrl = href.indexOf('http') === 0 ? href : READNOVELFULL_BASE + href;
      out.push(normalizeMediaItem({
        id: 'readnovelfull_' + href,
        title: title,
        coverUrl: cover,
        backdropUrl: cover,
        sourceName: 'ReadNovelFull',
        kind: 'novel',
        detailUrl: detailUrl
      }));
    }
    return out;
  }

  /** ReadNovelFull chapters — Android: data-novel-id → /ajax/chapter-archive */
  async function readNovelFullChapters(novelUrl) {
    var html = await readNovelFullFetch(novelUrl.replace(READNOVELFULL_BASE, ''), {});
    if (!html) return [];
    var novelIdM = /data-novel-id=["'](\d+)["']/i.exec(html);
    var chapterHtml = '';
    if (novelIdM) {
      chapterHtml = await readNovelFullFetch(
        '/ajax/chapter-archive?novelId=' + encodeURIComponent(novelIdM[1]), {});
      if (chapterHtml && chapterHtml.indexOf('chapter') === -1 && chapterHtml.indexOf('CHAPTER') === -1) {
        chapterHtml = '';
      }
    }
    var source = chapterHtml || html;
    var chapters = [];
    var seen = {};
    var re = /<a[^>]+href="([^"]*\/chapter-[^"]*\.html)"[^>]*>([\s\S]{0,200}?)<\/a>/gi;
    var m;
    while ((m = re.exec(source))) {
      var href = m[1];
      var key = href.toLowerCase();
      if (seen[key]) continue;
      seen[key] = 1;
      var title = decodeEntities(m[2].replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ')).trim();
      var numM = /(?:chapter|chap|ch\.?)\s*#?\s*(\d+(?:\.\d+)?)/i.exec(title) ||
                 /chapter[-_](\d+(?:\.\d+)?)/i.exec(href);
      var num = numM ? parseFloat(numM[1]) : 0;
      chapters.push({
        title: title || ('Chapter ' + num),
        url: href.indexOf('http') === 0 ? href : READNOVELFULL_BASE + href,
        chapterNumber: num,
        seasonNumber: 0,
        sortKey: num || 0
      });
    }
    if (chapters.length) {
      chapters = sortChapters(chapters);
    }
    return chapters;
  }

  /** ReadNovelFull chapter text — Android: #chr-content paragraphs. */
  async function readNovelFullChapterText(chapterUrl) {
    var html = await readNovelFullFetch(chapterUrl.replace(READNOVELFULL_BASE, ''), {});
    if (!html) return '';
    var start = html.indexOf('chr-content');
    if (start === -1) start = html.indexOf('chapter-content');
    if (start === -1) return '';
    var chunk = html.substr(start, 400000)
      .replace(/<script[\s\S]*?<\/script>/gi, ' ')
      .replace(/<style[\s\S]*?<\/style>/gi, ' ')
      .replace(/<ins[\s\S]*?<\/ins>/gi, ' ');
    var paras = [];
    var re = /<p[^>]*>([\s\S]*?)<\/p>/gi;
    var m;
    while ((m = re.exec(chunk)) && paras.length < 800) {
      var text = decodeEntities(m[1].replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ')).trim();
      if (text) paras.push(text);
    }
    return paras.join('\n\n');
  }

  /** Parse a jina-rendered RoyalRoad listing: cover+title+fiction URL. */
  function parseRoyalroadMarkdown(md) {
    var out = [];
    var seen = {};
    var re = /\[!\[[^\]]*\]\((https:\/\/www\.royalroadcdn\.com[^)]+)\)\]\((https:\/\/www\.royalroad\.com\/fiction\/(\d+)[^)]*)\)/g;
    var m;
    while ((m = re.exec(md)) && out.length < 24) {
      if (seen[m[3]]) continue;
      seen[m[3]] = 1;
      var slug = (m[2].split('/').pop() || 'fiction').replace(/-/g, ' ');
      out.push(normalizeMediaItem({
        id: 'royalroad_' + m[3],
        title: slug.charAt(0).toUpperCase() + slug.slice(1),
        coverUrl: m[1],
        backdropUrl: m[1],
        sourceName: 'RoyalRoad',
        kind: 'novel',
        detailUrl: m[2]
      }));
    }
    // Fallback titles from plain fiction links when no cover-pair matched
    if (out.length < 6) {
      var re2 = /\[([^\]\[]{4,80})\]\((https:\/\/www\.royalroad\.com\/fiction\/(\d+)[^)]*)\)/g;
      while ((m = re2.exec(md)) && out.length < 24) {
        if (seen[m[3]]) continue;
        seen[m[3]] = 1;
        out.push(normalizeMediaItem({
          id: 'royalroad_' + m[3],
          title: m[1].trim(),
          coverUrl: '',
          sourceName: 'RoyalRoad',
          kind: 'novel',
          detailUrl: m[2]
        }));
      }
    }
    return out;
  }

  var novelEnrich = { pending: false, data: null, at: 0 };

  /** Background enrichment: ReadNovelFull + extra RoyalRoad listings.
   *  Results are cached in localStorage and a 'novelenriched' event is
   *  dispatched so the open Novels tab can re-render with the new sources. */
  async function enrichNovels(baseItems) {
    if (novelEnrich.pending) return;
    novelEnrich.pending = true;
    try {
      var cached = lsGetJson('novel_enrich_v3', 10 * 60 * 1000);
      if (cached && cached.length) {
        novelEnrich.data = cached;
        novelEnrich.at = Date.now();
        document.dispatchEvent(new CustomEvent('novelenriched'));
        return;
      }
      var parts = await Promise.all([
        readNovelFullPopular(1).catch(function () { return []; }),
        jinaFetch('https://www.royalroad.com/fictions/trending', { timeout: 30000 })
          .then(parseRoyalroadMarkdown).catch(function () { return []; }),
        jinaFetch('https://www.royalroad.com/fictions/rising-stars', { timeout: 30000 })
          .then(parseRoyalroadMarkdown).catch(function () { return []; }),
        jinaFetch('https://www.royalroad.com/fictions/best-rated', { timeout: 30000 })
          .then(parseRoyalroadMarkdown).catch(function () { return []; })
      ]);
      var seen = {};
      var merged = [];
      parts.forEach(function (list) {
        (list || []).forEach(function (it) {
          var k = String(it.detailPageUrl || it.title).toLowerCase();
          if (!seen[k]) { seen[k] = 1; merged.push(it); }
        });
      });
      baseItems.forEach(function (it) {
        var k = String(it.detailPageUrl || it.title).toLowerCase();
        if (!seen[k]) { seen[k] = 1; merged.push(it); }
      });
      if (merged.length > baseItems.length) {
        lsSetJson('novel_enrich_v3', merged);
        novelEnrich.data = merged;
        novelEnrich.at = Date.now();
        document.dispatchEvent(new CustomEvent('novelenriched'));
        return;
      }
    } catch (e) { /* enrichment is best-effort */ }
    novelEnrich.pending = false;
  }

  /** Novels: instant backend list + progressive multi-source enrichment. */
  async function fetchNovelsMultiSource(page) {
    page = page || 1;
    if (page > 1) {
      return fetchContentHome('novel', page);
    }
    if (novelEnrich.data && (Date.now() - novelEnrich.at) < 180000) {
      return novelEnrich.data;
    }
    var royal = await fetchContentHome('novel', 1);
    // Enrich in the background — this call never blocks on dead proxies.
    enrichNovels(royal);
    return royal;
  }

  // ── Watch routes (Server 1-8 + client fallbacks) for parallel race ─────
  async function fetchWatchRoutes(kind, title, detailUrl, chapterUrl, chapterNumber) {
    var marker = extractSeasonEpisode(detailUrl, chapterUrl, chapterNumber) || {};
    var season = marker.season || '1';
    var episode = marker.episode || '1';

    var list = [];
    var res = await request('/content/watch-routes', {
      method: 'POST',
      timeout: 45000,
      body: {
        kind: kind,
        title: title || '',
        detailUrl: detailUrl || '',
        season: season,
        episode: episode,
        episodeMarker: detailUrl || ''
      }
    });
    if (res.ok && res.data && res.data.data && Array.isArray(res.data.data.routes)) {
      res.data.data.routes.forEach(function (r) {
        if (r && r.url) {
          list.push({
            provider: r.provider || 'Server',
            url: r.url,
            route: r.route || 'embed'
          });
        }
      });
    }

    // Client-side fallbacks (server unreachable / partial list) — same
    // providers the Android app falls back to.  Requires a TMDB id marker.
    if (marker.tmdbId) {
      var isTv = marker.type !== 'movie';
      var path = isTv ? ('tv/' + marker.tmdbId + '/' + season + '/' + episode)
                      : ('movie/' + marker.tmdbId);
      var fallbacks = [
        { provider: 'VidSrc.to (fallback)', url: 'https://vidsrc.to/embed/' + path },
        { provider: 'VidLink (fallback)', url: 'https://vidlink.pro/' + path },
        { provider: 'VidSrc.cc (fallback)', url: 'https://vidsrc.cc/v2/embed/' + path },
        { provider: 'MultiEmbed (fallback)', url: 'https://multiembed.mov/?video_id=' + marker.tmdbId + '&tmdb=1' + (isTv ? ('&s=' + season + '&e=' + episode) : '') }
      ];
      fallbacks.forEach(function (fb) {
        var dupe = list.some(function (r) { return r.url === fb.url; });
        if (!dupe) list.push(fb);
      });
    }

    // Animexin — available for Anime/Donghua only (matches the TV app)
    var titleStr = title || '';
    var isAnimeLike = String(kind).toLowerCase() === 'anime' || String(kind).toLowerCase() === 'donghua' ||
      /donghua|anime/i.test(titleStr);
    if (isAnimeLike && titleStr) {
      list.push({
        provider: 'Animexin (Donghua & Anime)',
        url: 'https://animexin.dev/?s=' + encodeURIComponent(titleStr),
        route: 'embed'
      });
    }

    return list;
  }

  // ── Football (flat ESPN fixture shape from the backend!) ────────────────
  function normalizeFixture(f) {
    if (!f) return null;
    var status = String(f.status || f.fixture?.status?.short || 'NS').toUpperCase();
    var liveStatuses = ['1H', '2H', 'HT', 'ET', 'BT', 'P', 'LIVE', 'INT'];
    var isLive = liveStatuses.indexOf(status) !== -1;
    var isFinished = status === 'FT' || status === 'AET' || status === 'PEN' || status === 'MATCH_FINISHED' || status === 'FINISHED';
    return {
      fixtureId: f.fixtureId || f.fixture?.id || '',
      homeTeam: f.homeTeam || f.teams?.home?.name || 'Home',
      awayTeam: f.awayTeam || f.teams?.away?.name || 'Away',
      homeLogo: f.homeLogo || f.teams?.home?.logo || '',
      awayLogo: f.awayLogo || f.teams?.away?.logo || '',
      homeGoals: (f.homeGoals !== null && f.homeGoals !== undefined) ? f.homeGoals : (f.goals?.home ?? null),
      awayGoals: (f.awayGoals !== null && f.awayGoals !== undefined) ? f.awayGoals : (f.goals?.away ?? null),
      leagueName: f.leagueName || f.league?.name || 'Football',
      leagueLogo: f.leagueLogo || '',
      matchDate: f.matchDate || '',
      matchTime: f.matchTime || '',
      elapsed: f.elapsed || null,
      status: status,
      isLive: isLive,
      isFinished: isFinished,
      streamHint: f.streamHint || ''
    };
  }

  async function fetchFootballFixtures() {
    var res = await request('/football/fixtures', { timeout: 45000 });
    var raw = (res.ok && res.data && (res.data.data || res.data)) || [];
    if (!Array.isArray(raw)) return [];
    return raw.map(normalizeFixture).filter(Boolean);
  }

  /** Resolution ladder identical to resolveFootballTvStreamList in ApiClient.kt */
  async function resolveFootballStreamList(home, away, league) {
    var urls = [];

    // 1. Backend direct .m3u8 scraper
    try {
      var direct = await request('/football/direct-stream', {
        method: 'POST',
        timeout: 40000,
        body: { homeTeam: home, awayTeam: away, leagueName: league || '' }
      });
      if (direct.ok && direct.data && direct.data.ok && direct.data.data) {
        var d = direct.data.data;
        if (d.direct && d.url) urls.push(d.url);
      }
    } catch (e) { /* ladder continues */ }

    // 2. Server's prioritised embed list (pipe-separated)
    try {
      var res = await request('/football/stream?home=' + encodeURIComponent(home || '') +
        '&away=' + encodeURIComponent(away || '') + '&league=' + encodeURIComponent(league || ''));
      if (res.ok && res.data && res.data.ok && typeof res.data.data === 'string') {
        res.data.data.split('|').forEach(function (u) {
          u = String(u).trim();
          if (u) urls.push(u);
        });
      }
    } catch (e) { /* ladder continues */ }

    // 3. Hard-coded client fallbacks (server unreachable)
    var slug = function (s) { return String(s || '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, ''); };
    var hs = slug(home), as = slug(away);
    var domains = ['https://streamseast.ws', 'https://streamseast.asia'];
    domains.forEach(function (dom) {
      if (hs && as) {
        urls.push(dom + '/soccer/' + hs + '-vs-' + as);
        urls.push(dom + '/stream/football-' + hs + '-vs-' + as);
      }
      urls.push(dom + '/soccer');
    });

    return urls.filter(function (u, i) { return urls.indexOf(u) === i; });
  }

  // ── WWE ─────────────────────────────────────────────────────────────────
  async function fetchWweEvents() {
    var res = await request('/wwe/events', { timeout: 45000 });
    var list = (res.ok && res.data && res.data.data) || [];
    return Array.isArray(list) ? list : [];
  }

  async function fetchWweStream(eventId, title, detailUrl) {
    var params = '?eventId=' + encodeURIComponent(eventId || '') + '&title=' + encodeURIComponent(title || '');
    if (detailUrl) params += '&detailUrl=' + encodeURIComponent(detailUrl);
    var res = await request('/wwe/stream' + params, { timeout: 60000 });
    if (res.ok && res.data) {
      if (Array.isArray(res.data.data) && res.data.data.length) return res.data.data[0];
      if (typeof res.data.data === 'string' && res.data.data) return res.data.data;
    }
    return detailUrl || null;
  }

  // ── Live TV (iptv-org open feeds — CORS OK) ─────────────────────────────
  var CURATED_LIVE_CHANNELS = [
    { title: 'Al Jazeera English', genre: 'News', logo: 'https://i.imgur.com/GWKmNPR.png', streamUrl: 'https://live-hls-web-aje.getaj.net/AJE/01.m3u8' },
    { title: 'DW English', genre: 'News', logo: 'https://i.imgur.com/1VOrT0T.png', streamUrl: 'https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8' },
    { title: 'France 24 English', genre: 'News', logo: 'https://upload.wikimedia.org/wikipedia/commons/thumb/b/b5/France_24_logo.svg/200px-France_24_logo.svg.png', streamUrl: 'https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8' },
    { title: 'CGTN', genre: 'News', logo: 'https://i.imgur.com/3pKH2V2.png', streamUrl: 'https://livesource.cgtn.com/cgtn-e/prog_index.m3u8' },
    { title: 'NHK World Japan', genre: 'News', logo: 'https://i.imgur.com/v9ixnWr.png', streamUrl: 'https://nhkwlive-ojp.akamaized.net/hls/live/2003459/nhkwlive-ojp-en/index.m3u8' },
    { title: 'TRT World', genre: 'News', logo: 'https://i.imgur.com/1GjJGXw.png', streamUrl: 'https://tv-trtworld.live.trt.com.tr/master.m3u8' },
    { title: 'Euronews English', genre: 'News', logo: 'https://i.imgur.com/Skf6vdi.png', streamUrl: 'https://euronews-euronews-enlivehd-origin-live.akamaized.net/hls/live/2006690/euronews-en-live-hd/master_5000.m3u8' },
    { title: 'ABC News Australia', genre: 'News', logo: '', streamUrl: 'https://c.mjh.nz/abc-news.m3u8' },
    { title: 'DW Film Germany', genre: 'Movies', logo: 'https://i.imgur.com/1VOrT0T.png', streamUrl: 'https://dwamdstream106.akamaized.net/hls/live/2015534/dwstream106/index.m3u8' },
    { title: 'DeporTV', genre: 'Sports', logo: 'https://i.imgur.com/iyYLNRt.png', streamUrl: 'https://5fb24b460df87.streamlock.net/live-cont.ar/deportv/playlist.m3u8' },
    { title: 'Racing.com', genre: 'Sports', logo: 'https://i.imgur.com/pma0OCf.png', streamUrl: 'https://racingvic-i.akamaized.net/hls/live/598695/racingvic/1500.m3u8' },
    { title: 'ABC Kids Australia', genre: 'Kids', logo: 'https://i.imgur.com/GWDRR1t.png', streamUrl: 'https://c.mjh.nz/abc-kids.m3u8' },
    { title: 'ABN Africa', genre: 'Entertainment · Africa', logo: 'https://i.imgur.com/5CVl5EF.png', streamUrl: 'https://mediaserver.abnvideos.com/streams/abnafrica.m3u8' }
  ];

  function parseM3U(text, sourceLabel) {
    var lines = String(text || '').split('\n');
    var channels = [];
    var pending = null;
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].trim();
      if (line.indexOf('#EXTINF') === 0) {
        var nameM = /,(.*)$/.exec(line);
        var logoM = /tvg-logo="([^"]*)"/.exec(line);
        var groupM = /group-title="([^"]*)"/.exec(line);
        pending = {
          title: nameM ? nameM[1].trim() : 'Channel',
          logo: logoM ? logoM[1] : '',
          group: groupM ? groupM[1] : 'General',
          source: sourceLabel
        };
      } else if (line && line.charAt(0) !== '#') {
        if (pending && /^https?:\/\//i.test(line)) {
          pending.streamUrl = line;
          channels.push(pending);
        }
        pending = null;
      }
    }
    return channels;
  }

  var liveFeedCache = { nollywood: null, at: 0 };

  /** Nollywood Live TV — Nigerian (+Ghana/SA) free-to-air streams. */
  async function fetchNollywoodLiveChannels() {
    if (liveFeedCache.nollywood && (Date.now() - liveFeedCache.at) < 600000) {
      return liveFeedCache.nollywood;
    }
    var feeds = await Promise.all([
      fetch('https://iptv-org.github.io/iptv/countries/ng.m3u').then(function (r) { return r.text(); }).catch(function () { return ''; }),
      fetch('https://iptv-org.github.io/iptv/countries/gh.m3u').then(function (r) { return r.text(); }).catch(function () { return ''; }),
      fetch('https://iptv-org.github.io/iptv/countries/za.m3u').then(function (r) { return r.text(); }).catch(function () { return ''; })
    ]);
    var all = [];
    all = all.concat(parseM3U(feeds[0], 'Nigeria'));
    all = all.concat(parseM3U(feeds[1], 'Ghana'));
    all = all.concat(parseM3U(feeds[2], 'South Africa'));
    // Keep only playable HLS/MP4 streams, cap the grid
    all = all.filter(function (c) { return /\.(m3u8|mp4|ts)(\?|$)/i.test(c.streamUrl); }).slice(0, 60);
    if (all.length) {
      liveFeedCache.nollywood = all;
      liveFeedCache.at = Date.now();
      return all;
    }
    return CURATED_LIVE_CHANNELS.map(function (c) {
      return { title: c.title, logo: c.logo, group: c.genre, streamUrl: c.streamUrl, source: 'Global' };
    });
  }

  /** Global Live TV — curated broadcaster channels (always available). */
  function fetchCuratedLiveChannels() {
    return CURATED_LIVE_CHANNELS.map(function (c) {
      return { title: c.title, logo: c.logo, group: c.genre, streamUrl: c.streamUrl, source: 'Global' };
    });
  }

  // ── YouTube (Nollywood trailers / live via backend resolver) ───────────
  async function fetchYouTubeStream(videoId) {
    var res = await request('/youtube/stream?videoId=' + encodeURIComponent(videoId || ''), { timeout: 60000 });
    if (res.ok && res.data) {
      if (typeof res.data.data === 'string' && res.data.data) return res.data.data;
      if (res.data.data && res.data.data.url) return res.data.data.url;
      if (typeof res.data.url === 'string' && res.data.url) return res.data.url;
    }
    return null;
  }

  // ── Unified search (backend + AniList + MangaDex + novels) ──────────────
  async function searchContent(type, query, page) {
    type = String(type || 'all').toLowerCase();
    page = page || 1;
    if (!query || !query.trim()) return [];

    var tasks = [
      request('/content/search?type=' + encodeURIComponent(type) + '&q=' + encodeURIComponent(query) + '&page=' + page)
        .then(function (res) {
          return (res.ok && res.data && res.data.data && res.data.data.items || []).map(normalizeMediaItem);
        }),
      fetchAnilistSearch(query),
      weebCentralSearch(query),
      mangadexSearch(query)
    ];

    if (type === 'all' || type === 'novel' || type === 'novels') {
      tasks.push(scrapeNovelSearch(query));
    }

    var results = await Promise.all(tasks);
    var merged = [];
    var seen = {};
    // Round-robin merge so every source is represented up top
    var maxLen = Math.max.apply(null, results.map(function (r) { return r.length; }).concat([0]));
    for (var i = 0; i < maxLen; i++) {
      results.forEach(function (list) {
        if (list[i]) {
          var k = String(list[i].detailPageUrl || list[i].title).toLowerCase();
          if (!seen[k]) { seen[k] = 1; merged.push(list[i]); }
        }
      });
    }

    if (type !== 'all' && type !== '' && type !== 'search') {
      var map = { novel: ['novel'], manga: ['manga'], anime: ['anime'], comic: ['comic'], movie: ['movie', 'tv'] };
      var allowed = map[type];
      if (allowed) merged = merged.filter(function (it) { return allowed.indexOf(it.mediaKind) !== -1; });
    }
    return merged;
  }

  async function scrapeNovelSearch(query) {
    // Search RoyalRoad via backend (other novel sites have no search bridge)
    try {
      var res = await request('/content/search?type=novel&q=' + encodeURIComponent(query));
      var items = (res.ok && res.data && res.data.data && res.data.data.items) || [];
      return items.map(normalizeMediaItem);
    } catch (e) {
      return [];
    }
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
    register: authRegister,
    authMe: authMe,
    fetchBillingStatus: fetchBillingStatus,
    fetchFreePreviewLimits: fetchFreePreviewLimits,
    createBillingCheckout: createBillingCheckout,
    fetchContentHome: fetchContentHome,
    fetchNovelsMultiSource: fetchNovelsMultiSource,
    searchContent: searchContent,
    fetchChapters: fetchChapters,
    fetchChapterText: fetchChapterText,
    fetchMangaPages: fetchMangaPages,
    mangadexHome: mangadexHome,
    mangadexSearch: mangadexSearch,
    mangadexChapters: mangadexChapters,
    mangadexChaptersByTitle: mangadexChaptersByTitle,
    mangadexPages: mangadexPages,
    weebCentralHome: weebCentralHome,
    weebCentralSearch: weebCentralSearch,
    weebCentralChapters: weebCentralChapters,
    weebCentralPages: weebCentralPages,
    readNovelFullPopular: readNovelFullPopular,
    readNovelFullChapters: readNovelFullChapters,
    readNovelFullChapterText: readNovelFullChapterText,
    fetchAnimeEpisodes: fetchAnimeEpisodes,
    fetchAnimeEpisodesParallel: fetchAnimeEpisodesParallel,
    fetchAnimeStream: fetchAnimeStream,
    fetchWatchRoutes: fetchWatchRoutes,
    fetchNollywoodLiveChannels: fetchNollywoodLiveChannels,
    fetchCuratedLiveChannels: fetchCuratedLiveChannels,
    fetchYouTubeStream: fetchYouTubeStream,
    fetchFootballFixtures: fetchFootballFixtures,
    resolveFootballStreamList: resolveFootballStreamList,
    fetchWweEvents: fetchWweEvents,
    fetchWweStream: fetchWweStream,
    normalizeMediaItem: normalizeMediaItem,
    sortChapters: sortChapters
  };
}));
