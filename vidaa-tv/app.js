/**
 * NovaRead TV Main Application Coordinator for Hisense VIDAA TV
 * Features:
 * - Direct Autoplay with 100% Volume
 * - Multi-Column Infinite Grid of Rows
 * - Login Session Persistence
 * - Global Search Bar on All Tabs
 * - YouTube TV Type Search Keyboard with Live Suggestions & Recent Searches
 */

(function () {
  'use strict';

  var POPULAR_SUGGESTIONS = [
    'Solo Leveling', 'Jujutsu Kaisen', 'Demon Slayer', 'One Piece', 'Attack on Titan',
    'Naruto Shippuden', 'Bleach: Thousand-Year Blood War', 'Chainsaw Man', 'My Hero Academia',
    'Death Note', 'Dragon Ball Z', 'Hunter x Hunter', 'Spider-Man', 'Avengers: Endgame',
    'Interstellar', 'Dune: Part Two', 'Oppenheimer', 'Arcane', 'Breaking Bad', 'The Boys',
    'House of the Dragon', 'Stranger Things', 'Squid Game', 'Wednesday', 'All of Us Are Dead'
  ];

  var appState = {
    currentSection: 'home',
    currentDetailMedia: null,
    currentEpisodes: [],
    history: [],
    watchlist: [],
    recentSearches: [],
    searchQuery: '',
    searchDebounceTimer: null,
    sportsFixtures: [],
    tvConfig: null,
    readerChapterIndex: 0,
    readerChapters: [],
    mangaPages: []
  };

  // These are freely available broadcaster feeds, deliberately kept separate
  // from fixture scraping.  Live TV stays useful even when there is no match.
  var LIVE_CHANNELS = [
    { id: 'aje', title: 'Al Jazeera English', genre: 'News · Qatar', coverUrl: 'https://i.imgur.com/GWKmNPR.png', streamUrl: 'https://live-hls-web-aje.getaj.net/AJE/01.m3u8' },
    { id: 'dw', title: 'DW English', genre: 'News · Germany', coverUrl: 'https://i.imgur.com/1VOrT0T.png', streamUrl: 'https://dwamdstream102.akamaized.net/hls/live/2015525/dwstream102/index.m3u8' },
    { id: 'france24', title: 'France 24 English', genre: 'News · France', coverUrl: 'https://upload.wikimedia.org/wikipedia/commons/thumb/b/b5/France_24_logo.svg/200px-France_24_logo.svg.png', streamUrl: 'https://static.france24.com/live/F24_EN_LO_HLS/live_web.m3u8' },
    { id: 'cgtn', title: 'CGTN', genre: 'News · China', coverUrl: 'https://i.imgur.com/3pKH2V2.png', streamUrl: 'https://livesource.cgtn.com/cgtn-e/prog_index.m3u8' },
    { id: 'deportv', title: 'DeporTV', genre: 'Sports · Argentina', coverUrl: 'https://i.imgur.com/iyYLNRt.png', streamUrl: 'https://5fb24b460df87.streamlock.net/live-cont.ar/deportv/playlist.m3u8' },
    { id: 'racing', title: 'Racing.com', genre: 'Sports · Australia', coverUrl: 'https://i.imgur.com/pma0OCf.png', streamUrl: 'https://racingvic-i.akamaized.net/hls/live/598695/racingvic/1500.m3u8' }
  ];

  document.addEventListener('DOMContentLoaded', initApp);

  async function initApp() {
    // 1. Initialize Spatial Navigation
    SpatialNav.init(document.getElementById('tv-app-root'));

    // 2. Initialize Video Player Engine
    TvPlayer.init();

    // 3. Load Watchlist, History & Recent Searches from localStorage
    loadLocalState();

    // 4. Setup Global Back Navigation
    SpatialNav.onBack(handleAppBack);

    // 5. Setup Sidebar Navigation
    setupSidebar();

    // 6. Setup YouTube TV Keyboard & Search System
    setupSearchKeyboard();

    // 7. Check Authentication / Phone Pairing (with session persistence)
    checkAuthAndPairing();

    // 8. Load TV Config & Home Feeds
    loadTvConfigAndFeed();

    // 9. Listen for Player events
    document.addEventListener('tvplayerclosed', (e) => {
      if (e.detail && e.detail.media) addHistory(e.detail.media);
      loadContinueWatchingRail();
    });
    document.addEventListener('tvpremiumneeded', function () {
      var user = NovaApi.getUserSession();
      if (user && !user.isGuest) showPremiumCheckout();
      else showPairingHelp();
    });
  }

  function loadLocalState() {
    try {
      var savedHistory = localStorage.getItem('tv_watch_history');
      appState.history = savedHistory ? JSON.parse(savedHistory) : [];
      var savedWatchlist = localStorage.getItem('tv_watchlist');
      appState.watchlist = savedWatchlist ? JSON.parse(savedWatchlist) : [];
      var savedSearches = localStorage.getItem('tv_recent_searches');
      appState.recentSearches = savedSearches ? JSON.parse(savedSearches) : [
        'Solo Leveling', 'Demon Slayer', 'Jujutsu Kaisen', 'One Piece', 'Interstellar'
      ];
    } catch (e) {}
  }

  function saveLocalState() {
    try {
      localStorage.setItem('tv_watch_history', JSON.stringify(appState.history));
      localStorage.setItem('tv_watchlist', JSON.stringify(appState.watchlist));
      localStorage.setItem('tv_recent_searches', JSON.stringify(appState.recentSearches));
    } catch (e) {}
  }

  function addRecentSearch(query) {
    if (!query || !query.trim()) return;
    var clean = query.trim();
    var filtered = appState.recentSearches.filter(s => s.toLowerCase() !== clean.toLowerCase());
    filtered.unshift(clean);
    appState.recentSearches = filtered.slice(0, 10);
    saveLocalState();
    renderRecentSearches();
  }

  // ── Authentication & Persistent Session ────────────────────────────
  async function checkAuthAndPairing() {
    var user = NovaApi.getUserSession();
    var splash = document.getElementById('tv-splash-view');
    var pairCodeEl = document.getElementById('tv-pair-code');
    var qrBox = document.querySelector('.tv-qr-box');
    var guestBtn = document.getElementById('tv-btn-guest-login');

    function dismissSplash() {
      if (!splash.classList.contains('hidden')) {
        splash.classList.add('hidden');
        SpatialNav.popScope();
        var target = document.querySelector('.tv-sidebar-item.active') || document.querySelector('.tv-sidebar-item') || document.querySelector('.tv-btn');
        if (target) SpatialNav.focus(target);
      }
    }

    if (guestBtn) {
      guestBtn.addEventListener('click', () => {
        var guestUser = { id: 'guest_' + Date.now(), username: 'Guest', plan: 'free', isGuest: true };
        NovaApi.saveUserSession(guestUser);
        updateUserBadge(guestUser);
        dismissSplash();
      });
    }

    // If user login is already saved in localStorage, immediately restore session!
    if (user) {
      updateUserBadge(user);
      splash.classList.add('hidden');
      if (user.authToken) {
        NovaApi.authMe().then(freshUser => {
          if (freshUser) updateUserBadge(freshUser);
        }).catch(() => {});
      }
      return;
    }

    // Set initial focus to guest button so OK key enters immediately
    if (guestBtn) {
      SpatialNav.pushScope(splash, guestBtn);
    }

    // Render fallback QR code immediately
    var fallbackUrl = 'https://novelapp1.onrender.com/tv-pair.html';
    if (window.NovaQR && qrBox) {
      NovaQR.render(fallbackUrl, qrBox, 190);
    }

    // Attempt to start pairing session with backend
    try {
      var pair = await NovaApi.startTvPair();
      if (pair && pair.code) {
        if (pairCodeEl) pairCodeEl.textContent = pair.code;
        var pairUrl = pair.qrContent || `https://novelapp1.onrender.com/tv-pair.html?pair=${pair.pairId}`;
        if (window.NovaQR && qrBox) {
          NovaQR.render(pairUrl, qrBox, 190);
        }

        // Poll pairing status in background
        var pollTimer = setInterval(async () => {
          if (splash.classList.contains('hidden')) {
            clearInterval(pollTimer);
            return;
          }
          var res = await NovaApi.pollTvPairStatus(pair.pairId);
          if (res.status === 'approved' && res.user) {
            clearInterval(pollTimer);
            updateUserBadge(res.user);
            dismissSplash();
          } else if (res.status === 'expired') {
            clearInterval(pollTimer);
          }
        }, 3000);
      } else {
        if (pairCodeEl) pairCodeEl.textContent = 'TV-' + Math.floor(1000 + Math.random() * 9000);
      }
    } catch (e) {
      console.warn('Pairing init error:', e);
      if (pairCodeEl) pairCodeEl.textContent = 'TV-' + Math.floor(1000 + Math.random() * 9000);
    }
  }

  function updateUserBadge(user) {
    var badge = document.getElementById('tv-user-badge');
    var name = document.getElementById('tv-user-name');
    var avatar = document.getElementById('tv-user-avatar');
    if (user && badge && name && avatar) {
      name.textContent = user.username || 'Subscriber';
      avatar.textContent = (user.username || 'U').charAt(0).toUpperCase();
    }
  }

  async function loadTvConfigAndFeed() {
    appState.tvConfig = await NovaApi.fetchTvConfig();
    switchSection('home');
  }

  function setupSidebar() {
    var items = document.querySelectorAll('.tv-sidebar-item');
    items.forEach(item => {
      item.addEventListener('click', () => {
        var section = item.getAttribute('data-section');
        switchSection(section);
      });
    });
  }

  async function switchSection(section) {
    appState.currentSection = section;

    // Update active sidebar state
    document.querySelectorAll('.tv-sidebar-item').forEach(btn => {
      btn.classList.toggle('active', btn.getAttribute('data-section') === section);
    });

    // Hide subviews
    hideAllViews();

    var mainContent = document.getElementById('tv-main-content');
    mainContent.style.display = 'block';
    mainContent.innerHTML = '<div class="tv-loading-spinner"></div>';

    if (section === 'home') {
      await renderHomeSection(mainContent);
    } else if (section === 'sports') {
      await renderSportsSection(mainContent);
    } else if (section === 'live') {
      renderLiveTvSection(mainContent);
    } else if (section === 'you') {
      await renderYouSection(mainContent);
    } else if (section === 'search') {
      renderSearchView();
    } else {
      await renderCategorySection(mainContent, section);
    }
  }

  function hideAllViews() {
    document.getElementById('tv-detail-view').classList.remove('active');
    document.getElementById('tv-reader-view').classList.remove('active');
    document.getElementById('tv-search-view').classList.remove('active');
  }

  // ── Global Search Bar Trigger ──────────────────────────────────────
  function createSearchBarTrigger(hintText) {
    var bar = document.createElement('div');
    bar.className = 'tv-search-bar-trigger';
    bar.tabIndex = 0;
    bar.setAttribute('data-nav', 'true');
    bar.innerHTML = `
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
      <span>${hintText || 'Search anime, movies, series, novels...'}</span>
      <span class="tv-search-shortcut">Press OK to Search</span>
    `;
    bar.addEventListener('click', () => {
      renderSearchView();
    });
    return bar;
  }

  // ── Home Section ───────────────────────────────────────────────────
  async function renderHomeSection(container) {
    container.innerHTML = '';

    // Search Bar at Top of Home
    var searchBar = createSearchBarTrigger('🔍 Search all anime, movies, series, light novels...');
    container.appendChild(searchBar);

    // 1. Fetch Trending Anime for Billboard
    var trendingAnime = await NovaApi.fetchContentHome('anime');
    var heroItem = trendingAnime[0] || {
      title: "Solo Leveling: Arise",
      genre: "Action, Fantasy, Anime",
      synopsis: "In a world where hunters battle deadly monsters, Sung Jinwoo discovers an extraordinary secret power to level up infinitely.",
      backdropUrl: "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=1920&q=80",
      rating: "9.8",
      year: "2025",
      mediaKind: "anime",
      isAnime: true
    };

    // Render Billboard
    var billboard = createBillboard(heroItem);
    container.appendChild(billboard);

    // 2. Continue Watching Rail (if any)
    var continueRail = createContinueWatchingRail();
    if (continueRail) {
      container.appendChild(continueRail);
    }

    // 3. Recommended Rails
    var animeRail = createRail('🔥 Trending Anime', trendingAnime);
    container.appendChild(animeRail);

    var movies = await NovaApi.fetchContentHome('movie');
    var movieRail = createRail('🎬 New Movies & Series', movies);
    container.appendChild(movieRail);

    var kdrama = await NovaApi.fetchContentHome('kdrama');
    var kdramaRail = createRail('🇰🇷 Popular K-Drama', kdrama);
    container.appendChild(kdramaRail);

    var novels = await NovaApi.fetchContentHome('novel');
    var novelRail = createRail('📚 Top Light Novels', novels);
    container.appendChild(novelRail);

    var manga = await NovaApi.fetchContentHome('manga');
    var mangaRail = createRail('🎨 Popular Manga', manga);
    container.appendChild(mangaRail);

    // Focus search bar or play button
    setTimeout(() => {
      var playBtn = billboard.querySelector('.tv-btn-primary');
      if (playBtn) SpatialNav.focus(playBtn);
    }, 100);
  }

  function createBillboard(item) {
    var div = document.createElement('div');
    div.className = 'tv-billboard';
    var bg = item.backdropUrl || item.coverUrl || 'https://images.unsplash.com/photo-1578632767115-351597cf2477?w=1920&q=80';
    div.style.backgroundImage = `url('${bg}')`;

    div.innerHTML = `
      <div class="tv-billboard-overlay"></div>
      <div class="tv-billboard-info">
        <div class="tv-badge">✨ Featured Spotlight</div>
        <h2 class="tv-billboard-title">${item.title}</h2>
        <div class="tv-billboard-meta">
          <span>⭐ ${item.rating || '8.8'}</span>
          <span>📅 ${item.year || '2025'}</span>
          <span>🏷️ ${item.genre || 'Trending'}</span>
        </div>
        <p class="tv-billboard-synopsis">${item.synopsis || 'Stream in Full HD with multi-server playback and instant subtitles on NovaRead TV.'}</p>
        <div class="tv-billboard-actions">
          <button class="tv-btn tv-btn-primary" tabindex="0" data-nav="true" id="tv-hero-play-btn">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="currentColor"><polygon points="5 3 19 12 5 21 5 3"/></svg>
            Play Now
          </button>
          <button class="tv-btn" tabindex="0" data-nav="true" id="tv-hero-detail-btn">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><line x1="12" y1="16" x2="12" y2="12"/><line x1="12" y1="8" x2="12.01" y2="8"/></svg>
            Details
          </button>
        </div>
      </div>
    `;

    div.querySelector('#tv-hero-play-btn').addEventListener('click', () => openDetail(item, true));
    div.querySelector('#tv-hero-detail-btn').addEventListener('click', () => openDetail(item, false));
    return div;
  }

  function createRail(title, items) {
    var rail = document.createElement('div');
    rail.className = 'tv-rail';
    rail.setAttribute('data-nav-row', 'true');

    var header = document.createElement('div');
    header.className = 'tv-rail-header';
    header.innerHTML = `<h3 class="tv-rail-title">${title}</h3>`;
    rail.appendChild(header);

    var content = document.createElement('div');
    content.className = 'tv-rail-content';

    items.forEach(item => {
      var card = createCard(item);
      content.appendChild(card);
    });

    rail.appendChild(content);
    return rail;
  }

  function createCard(item, progressPct = null) {
    var card = document.createElement('div');
    card.className = 'tv-card';
    card.tabIndex = 0;
    card.setAttribute('data-nav', 'true');

    var poster = item.coverUrl || 'https://via.placeholder.com/200x300?text=No+Cover';
    var progressHtml = progressPct !== null ? 
      `<div class="tv-card-progress"><div class="tv-card-progress-bar" style="width: ${progressPct}%"></div></div>` : '';

    card.innerHTML = `
      <img class="tv-card-poster" src="${poster}" alt="${item.title}" loading="lazy" />
      <span class="tv-card-badge">⭐ ${item.rating || '8.5'}</span>
      ${progressHtml}
      <div class="tv-card-info">
        <div class="tv-card-title">${item.title}</div>
        <div class="tv-card-subtitle">${item.genre || item.mediaKind || 'NovaRead'}</div>
      </div>
    `;

    // Every title opens its detail page first.  This gives the viewer the
    // same deliberate Play / Watchlist / episode choice as the Android TV app.
    card.addEventListener('click', () => openDetail(item));

    return card;
  }

  function createContinueWatchingRail() {
    var list = [];
    for (var i = 0; i < localStorage.length; i++) {
      var key = localStorage.key(i);
      if (key && key.startsWith('tv_progress_')) {
        try {
          var data = JSON.parse(localStorage.getItem(key));
          if (data && data.duration > 0) {
            list.push(data);
          }
        } catch (e) {}
      }
    }
    if (list.length === 0) return null;

    list.sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0));

    var rail = document.createElement('div');
    rail.className = 'tv-rail';
    rail.setAttribute('data-nav-row', 'true');
    rail.id = 'tv-continue-rail';

    var header = document.createElement('div');
    header.className = 'tv-rail-header';
    header.innerHTML = `<h3 class="tv-rail-title">⏳ Continue Watching</h3>`;
    rail.appendChild(header);

    var content = document.createElement('div');
    content.className = 'tv-rail-content';

    list.slice(0, 10).forEach(item => {
      var pct = Math.min(100, Math.round((item.position / item.duration) * 100));
      var card = createCard({
        id: item.id,
        title: item.title,
        coverUrl: item.coverUrl,
        genre: item.episodeTitle || 'Resume playback'
      }, pct);
      content.appendChild(card);
    });

    rail.appendChild(content);
    return rail;
  }

  function loadContinueWatchingRail() {
    var existing = document.getElementById('tv-continue-rail');
    if (existing && existing.parentNode) {
      var updated = createContinueWatchingRail();
      if (updated) {
        existing.parentNode.replaceChild(updated, existing);
      } else {
        existing.remove();
      }
    }
  }

  // ── Category Multi-Column Infinite Grid ────────────────────────────
  var CATEGORY_LABELS = {
    anime: '🎌 Anime',
    movie: '🎬 Movies & TV Shows',
    movies: '🎬 Movies & TV Shows',
    kdrama: '🇰🇷 K-Drama',
    donghua: '🐉 Donghua',
    cartoon: '😄 Cartoons',
    classic: '🕰️ Classic TV',
    nigerian: '🇳🇬 Nollywood',
    novel: '📚 Light Novels',
    novels: '📚 Light Novels',
    manga: '🎨 Manga',
    comic: '💥 Comics'
  };

  async function renderCategorySection(container, category) {
    var displayName = CATEGORY_LABELS[category] || category.toUpperCase();

    container.innerHTML = `
      <div class="tv-category-header">
        <h2 class="tv-rail-title" style="font-size: 32px;">${displayName}</h2>
        <span style="color: var(--tv-text-muted); font-size: 16px;">Browse All Titles</span>
      </div>
      <div class="tv-loading-spinner"></div>
    `;

    var currentPage = 1;
    var isLoading = false;
    var hasMore = true;

    var items = await NovaApi.fetchContentHome(category, currentPage);
    container.innerHTML = '';

    // Search Bar at Top of Category Tab
    var searchBar = createSearchBarTrigger(`🔍 Search in ${displayName}...`);
    container.appendChild(searchBar);

    var header = document.createElement('div');
    header.className = 'tv-category-header';
    header.innerHTML = `
      <h2 class="tv-rail-title" style="font-size: 30px;">${displayName}</h2>
      <span style="color: var(--tv-text-muted); font-size: 16px;">All Titles</span>
    `;
    container.appendChild(header);

    if (!items || items.length === 0) {
      container.innerHTML += `<div style="padding: 60px; font-size: 22px; color: var(--tv-text-muted);">No items found for ${displayName}.</div>`;
      return;
    }

    var grid = document.createElement('div');
    grid.className = 'tv-category-grid';
    grid.setAttribute('data-nav-row', 'true');

    function appendItems(newItems) {
      newItems.forEach(item => {
        var card = createCard(item);
        grid.appendChild(card);
      });
    }

    appendItems(items);

    // Infinite scroll load-more element
    var loadMoreBtn = document.createElement('button');
    loadMoreBtn.className = 'tv-load-more-card';
    loadMoreBtn.tabIndex = 0;
    loadMoreBtn.setAttribute('data-nav', 'true');
    loadMoreBtn.textContent = '▼ Load More Titles...';

    async function loadNextPage() {
      if (isLoading || !hasMore) return;
      isLoading = true;
      loadMoreBtn.textContent = '⏳ Loading more titles...';
      currentPage++;
      var nextItems = await NovaApi.fetchContentHome(category, currentPage);
      if (nextItems && nextItems.length > 0) {
        loadMoreBtn.remove();
        appendItems(nextItems);
        grid.appendChild(loadMoreBtn);
        loadMoreBtn.textContent = '▼ Load More Titles...';
      } else {
        hasMore = false;
        loadMoreBtn.remove();
      }
      isLoading = false;
    }

    loadMoreBtn.addEventListener('click', loadNextPage);
    loadMoreBtn.addEventListener('focus', () => {
      loadNextPage();
    });

    grid.appendChild(loadMoreBtn);
    container.appendChild(grid);

    // Auto-scroll infinite loading
    container.onscroll = function() {
      if (container.scrollTop + container.clientHeight >= container.scrollHeight - 500) {
        loadNextPage();
      }
    };

    setTimeout(() => {
      var firstCard = grid.querySelector('.tv-card');
      if (firstCard) SpatialNav.focus(firstCard);
    }, 100);
  }

  // ── Sports Section ─────────────────────────────────────────────────
  async function renderSportsSection(container) {
    container.innerHTML = '';

    var searchBar = createSearchBarTrigger('🔍 Search live football fixtures, teams & WWE events...');
    container.appendChild(searchBar);

    var header = document.createElement('div');
    header.style.padding = '20px 60px 20px';
    header.innerHTML = `
      <h2 class="tv-rail-title" style="font-size: 32px; margin-bottom: 8px;">⚽ Live Sports & Fixtures</h2>
      <p style="color: var(--tv-text-muted); font-size: 16px;">Real-time scores and direct video streaming for Premier League, Champions League, La Liga & WWE</p>
    `;
    container.appendChild(header);

    var spinner = document.createElement('div');
    spinner.className = 'tv-loading-spinner';
    container.appendChild(spinner);

    var fixtures = await NovaApi.fetchFootballFixtures();
    var wweEvents = await NovaApi.fetchWweEvents();
    spinner.remove();

    var grid = document.createElement('div');
    grid.className = 'tv-sports-grid';
    grid.setAttribute('data-nav-row', 'true');

    if (fixtures.length === 0 && wweEvents.length === 0) {
      grid.innerHTML = '<div style="color: var(--tv-text-muted); font-size: 18px;">No live matches currently in progress. Check back shortly!</div>';
    } else {
      fixtures.slice(0, 16).forEach(f => {
        var card = createMatchCard(f);
        grid.appendChild(card);
      });

      wweEvents.slice(0, 8).forEach(w => {
        var card = createWweCard(w);
        grid.appendChild(card);
      });
    }

    container.appendChild(grid);
    setTimeout(() => {
      var firstMatch = grid.querySelector('.tv-match-card');
      if (firstMatch) SpatialNav.focus(firstMatch);
    }, 100);
  }

  function renderLiveTvSection(container) {
    container.innerHTML = '';
    var header = document.createElement('div');
    header.className = 'tv-category-header';
    header.innerHTML = '<h2 class="tv-rail-title" style="font-size:32px;">📡 Live TV</h2><span style="color:var(--tv-text-muted);">Free public broadcaster channels</span>';
    container.appendChild(header);
    var grid = document.createElement('div');
    grid.className = 'tv-category-grid';
    grid.setAttribute('data-nav-row', 'true');
    LIVE_CHANNELS.forEach(function (channel) {
      var card = document.createElement('div');
      card.className = 'tv-card'; card.tabIndex = 0; card.setAttribute('data-nav', 'true');
      card.innerHTML = '<img class="tv-card-poster" src="' + channel.coverUrl + '" alt="' + channel.title + '" loading="lazy" /><span class="tv-card-badge">● LIVE</span><div class="tv-card-info"><div class="tv-card-title">' + channel.title + '</div><div class="tv-card-subtitle">' + channel.genre + '</div></div>';
      card.addEventListener('click', function () {
        TvPlayer.open(channel, [{ title: channel.title, streamUrl: channel.streamUrl }], 0, channel.streamUrl);
      });
      grid.appendChild(card);
    });
    container.appendChild(grid);
  }

  function createMatchCard(fixture) {
    var card = document.createElement('div');
    card.className = 'tv-match-card';
    card.tabIndex = 0;
    card.setAttribute('data-nav', 'true');

    var league = fixture.league?.name || 'Football Match';
    var status = fixture.fixture?.status?.short || 'NS';
    var isLive = ['1H', '2H', 'HT', 'ET', 'P', 'LIVE'].includes(status);

    var home = fixture.teams?.home || { name: 'Home Team' };
    var away = fixture.teams?.away || { name: 'Away Team' };
    var goals = fixture.goals || { home: 0, away: 0 };

    card.innerHTML = `
      <div class="tv-match-header">
        <span class="tv-league-name">${league}</span>
        <span class="tv-live-badge ${isLive ? '' : 'style="background: rgba(255,255,255,0.1); color: #fff;"'}">
          ${isLive ? '<span class="tv-live-dot"></span> LIVE' : status}
        </span>
      </div>
      <div class="tv-match-teams">
        <div class="tv-team-info">
          <img class="tv-team-logo" src="${home.logo || ''}" alt="${home.name}" onerror="this.style.display='none'" />
          <span>${home.name}</span>
        </div>
        <div class="tv-match-score">${goals.home ?? 0} - ${goals.away ?? 0}</div>
        <div class="tv-team-info">
          <img class="tv-team-logo" src="${away.logo || ''}" alt="${away.name}" onerror="this.style.display='none'" />
          <span>${away.name}</span>
        </div>
      </div>
    `;

    card.addEventListener('click', async () => {
      var res = await NovaApi.resolveFootballStream(fixture.fixture?.id, home.name, away.name, league);
      if (res && res.url) {
        TvPlayer.open({
          id: 'fixture_' + fixture.fixture?.id,
          title: `${home.name} vs ${away.name} (${league})`,
          coverUrl: home.logo || away.logo
        }, [{ title: `${home.name} vs ${away.name}`, streamUrl: res.url }], 0, res.url);
      }
    });

    return card;
  }

  function createWweCard(event) {
    var card = document.createElement('div');
    card.className = 'tv-match-card';
    card.tabIndex = 0;
    card.setAttribute('data-nav', 'true');

    card.innerHTML = `
      <div class="tv-match-header">
        <span class="tv-league-name">WWE Event</span>
        <span class="tv-live-badge" style="background: rgba(232, 77, 138, 0.2); color: var(--tv-pink);">WWE</span>
      </div>
      <div class="tv-team-info" style="font-size: 22px; padding: 12px 0;">
        🥊 ${event.title || 'WWE Premium Live Event'}
      </div>
    `;

    card.addEventListener('click', async () => {
      var streamUrl = await NovaApi.fetchWweStream(event.id, event.title, event.detailUrl);
      if (streamUrl) {
        TvPlayer.open({
          id: 'wwe_' + event.id,
          title: event.title,
          coverUrl: event.coverUrl
        }, [{ title: event.title, streamUrl: streamUrl }], 0, streamUrl);
      }
    });

    return card;
  }

  // ── Watchlist & History ("You" Section) ────────────────────────────
  async function renderYouSection(container) {
    container.innerHTML = '';

    var searchBar = createSearchBarTrigger('🔍 Search your library, bookmarks & watch history...');
    container.appendChild(searchBar);

    var header = document.createElement('div');
    header.style.padding = '20px 60px 10px';
    header.innerHTML = `<h2 class="tv-rail-title" style="font-size: 32px;">👤 My Library & Watchlist</h2>`;
    container.appendChild(header);

    container.appendChild(createAccountPanel());

    var watchlistRail = createRail('⭐ My Watchlist', appState.watchlist);
    container.appendChild(watchlistRail);

    var continueRail = createContinueWatchingRail();
    if (continueRail) {
      container.appendChild(continueRail);
    }

    var historyRail = createRail('🕘 Recently Watched', appState.history.slice(0, 20));
    container.appendChild(historyRail);
  }

  function addHistory(item) {
    if (!item || !item.title) return;
    appState.history = appState.history.filter(function (x) { return x.id !== item.id && x.title !== item.title; });
    appState.history.unshift(item);
    appState.history = appState.history.slice(0, 30);
    saveLocalState();
  }

  function createAccountPanel() {
    var user = NovaApi.getUserSession();
    var panel = document.createElement('div');
    panel.style.cssText = 'margin:20px 60px;padding:24px;border:1px solid var(--tv-line);border-radius:var(--tv-radius);background:var(--tv-panel-elevated);display:flex;align-items:center;gap:24px;';
    var guest = !user || user.isGuest;
    panel.innerHTML = '<div style="flex:1"><h3 class="tv-rail-title">' + (guest ? 'Guest account' : 'Subscription') + '</h3><p style="color:var(--tv-text-muted);margin-top:6px;">' + (guest ? 'Guest playback is enabled for testing. Pair with your phone to create an account or purchase Premium.' : 'Manage your plan and payment securely on your phone.') + '</p></div><button class="tv-btn tv-btn-primary" tabindex="0" data-nav="true">' + (guest ? 'Pair / Create Account' : 'Go Premium') + '</button>';
    panel.querySelector('button').addEventListener('click', function () { guest ? showPairingHelp() : showPremiumCheckout(); });
    return panel;
  }

  function showPairingHelp() {
    var modal = document.getElementById('tv-payment-modal');
    document.getElementById('tv-payment-title').textContent = 'Create your account on your phone';
    document.getElementById('tv-payment-copy').textContent = 'Open the QR pairing page, choose Create Account, then approve this TV. Guest mode remains available for testing.';
    var box = document.getElementById('tv-payment-qr'); box.innerHTML = '';
    if (window.NovaQR) NovaQR.render('https://novelapp1.onrender.com/tv-pair.html', box, 200);
    openPaymentModal(modal);
  }

  async function showPremiumCheckout() {
    var modal = document.getElementById('tv-payment-modal');
    document.getElementById('tv-payment-title').textContent = 'Premium — ₦1,000/month';
    document.getElementById('tv-payment-copy').textContent = 'Creating a secure Flutterwave payment link…';
    document.getElementById('tv-payment-qr').innerHTML = '';
    openPaymentModal(modal);
    var checkout = await NovaApi.createBillingCheckout('premium_3_devices');
    if (checkout && checkout.link && window.NovaQR) {
      NovaQR.render(checkout.link, document.getElementById('tv-payment-qr'), 220);
      document.getElementById('tv-payment-copy').textContent = 'Scan to pay with Flutterwave. This TV will keep checking your subscription after payment.';
    } else {
      document.getElementById('tv-payment-copy').textContent = 'Checkout is unavailable. Confirm your paired account and Flutterwave server configuration, then try again.';
    }
  }

  function openPaymentModal(modal) {
    modal.classList.add('active');
    document.getElementById('tv-payment-close').onclick = function () { modal.classList.remove('active'); SpatialNav.popScope(); };
    SpatialNav.pushScope(modal, document.getElementById('tv-payment-close'));
  }

  // ── Content Detail Modal ───────────────────────────────────────────
  async function openDetail(item, autoPlay = false) {
    appState.currentDetailMedia = item;
    var detailView = document.getElementById('tv-detail-view');
    var backdrop = document.getElementById('tv-detail-backdrop');
    var poster = document.getElementById('tv-detail-poster');
    var title = document.getElementById('tv-detail-title');
    var rating = document.getElementById('tv-detail-rating');
    var year = document.getElementById('tv-detail-year');
    var genre = document.getElementById('tv-detail-genre');
    var synopsis = document.getElementById('tv-detail-synopsis');
    var episodesList = document.getElementById('tv-detail-episodes-grid');
    var playBtn = document.getElementById('tv-detail-btn-play');
    var watchlistBtn = document.getElementById('tv-detail-btn-watchlist');

    backdrop.style.backgroundImage = `url('${item.backdropUrl || item.coverUrl}')`;
    poster.src = item.coverUrl || '';
    title.textContent = item.title;
    rating.textContent = '⭐ ' + (item.rating || '8.8');
    year.textContent = '📅 ' + (item.year || '2025');
    genre.textContent = '🏷️ ' + (item.genre || item.mediaKind || 'NovaRead');
    synopsis.textContent = item.synopsis || 'Stream in crystal-clear Full HD with subtitles on NovaRead TV.';

    playBtn.textContent = item.isNovel ? 'Read Novel' : (item.isManga || item.isComic ? 'Read Manga' : 'Play');
    episodesList.innerHTML = '<div style="color: var(--tv-text-muted);">Loading episodes and chapters…</div>';
    detailView.classList.add('active');
    SpatialNav.pushScope(detailView, playBtn);

    // Fetch the full selectable episode/chapter list before playback. Anime
    // queries fan out across providers; generic TMDB chapters are the stable
    // fallback for titles such as Mushoku Tensei that appear in a mixed rail.
    var episodes = [];
    if (item.isAnime) {
      episodes = await NovaApi.fetchAnimeEpisodesParallel(item.title);
      if (!episodes.length) {
        episodes = await NovaApi.fetchChapters('anime', item.detailPageUrl, item.title, item.sourceName);
      }
    } else {
      episodes = await NovaApi.fetchChapters(item.mediaKind, item.detailPageUrl, item.title, item.sourceName);
    }
    appState.currentEpisodes = episodes;

    if (autoPlay) {
      startItem(item, episodes, 0);
      return;
    }

    episodesList.innerHTML = '';
    if (episodes.length === 0) {
      episodesList.innerHTML = '<div style="color: var(--tv-text-muted);">Single Full Feature / Movie</div>';
    } else {
      episodes.forEach((ep, idx) => {
        var card = document.createElement('button');
        card.className = 'tv-episode-card' + (idx === 0 ? ' active' : '');
        card.tabIndex = 0;
        card.setAttribute('data-nav', 'true');
        card.innerHTML = `<strong>${ep.title || `Episode ${idx + 1}`}</strong>`;
        card.addEventListener('click', () => {
          startItem(item, episodes, idx);
        });
        episodesList.appendChild(card);
      });
    }

    // Play button click
    playBtn.onclick = () => startItem(item, appState.currentEpisodes, 0);

    // Watchlist toggle
    updateWatchlistBtnState(watchlistBtn, item);
    watchlistBtn.onclick = () => {
      toggleWatchlist(item);
      updateWatchlistBtnState(watchlistBtn, item);
    };

  }

  async function startItem(item, episodes, index) {
    if (item.isNovel) return openNovelReader(item, episodes, index);
    if (item.isManga || item.isComic) return openMangaViewer(item, episodes, index);
    if (item.videoId || String(item.detailPageUrl || '').startsWith('youtube://')) {
      var videoId = item.videoId || item.detailPageUrl.replace('youtube://', '');
      var youtubeUrl = await NovaApi.fetchYouTubeStream(videoId);
      if (youtubeUrl) return TvPlayer.open(item, [{ title: item.title, streamUrl: youtubeUrl }], 0, youtubeUrl);
    }
    TvPlayer.open(item, episodes, index);
  }

  function updateWatchlistBtnState(btn, item) {
    var inList = appState.watchlist.some(x => x.id === item.id || x.title === item.title);
    btn.innerHTML = inList ? '✔ In Watchlist' : '+ Add to Watchlist';
  }

  function toggleWatchlist(item) {
    var idx = appState.watchlist.findIndex(x => x.id === item.id || x.title === item.title);
    if (idx !== -1) {
      appState.watchlist.splice(idx, 1);
    } else {
      appState.watchlist.push(item);
    }
    saveLocalState();
  }

  // ── Novel Reader Screen ────────────────────────────────────────────
  async function openNovelReader(item, chapters, index) {
    var view = document.getElementById('tv-reader-view');
    var title = document.getElementById('tv-reader-title');
    var body = document.getElementById('tv-reader-body');
    title.textContent = item.title;
    body.innerHTML = '<div class="tv-loading-spinner"></div>';
    view.classList.add('active');
    SpatialNav.pushScope(view);

    chapters = chapters || await NovaApi.fetchChapters('novel', item.detailPageUrl, item.title, item.sourceName);
    appState.readerChapters = chapters;
    var firstChapUrl = chapters[index || 0]?.url || item.detailPageUrl;
    var text = await NovaApi.fetchChapterText(firstChapUrl, item.title, item.sourceName);

    body.innerHTML = text ? text.split('\n').map(p => `<p>${p}</p>`).join('') : '<p>Loading chapter text...</p>';
  }

  // ── Manga Viewer Screen ────────────────────────────────────────────
  async function openMangaViewer(item, chapters, index) {
    var view = document.getElementById('tv-reader-view');
    var title = document.getElementById('tv-reader-title');
    var body = document.getElementById('tv-reader-body');
    title.textContent = item.title + ' (Manga Viewer)';
    body.innerHTML = '<div class="tv-loading-spinner"></div>';
    view.classList.add('active');
    SpatialNav.pushScope(view);

    chapters = chapters || await NovaApi.fetchChapters('manga', item.detailPageUrl, item.title, item.sourceName);
    var firstChapUrl = chapters[index || 0]?.url || item.detailPageUrl;
    var pages = await NovaApi.fetchMangaPages(firstChapUrl);

    body.innerHTML = '';
    if (pages.length === 0) {
      body.innerHTML = '<p>No manga pages available.</p>';
    } else {
      pages.forEach(pg => {
        var img = document.createElement('img');
        img.src = pg;
        img.style.maxWidth = '100%';
        img.style.marginBottom = '20px';
        img.style.borderRadius = '8px';
        body.appendChild(img);
      });
    }
  }

  // ── YouTube TV Keyboard & Search Engine ────────────────────────────
  function setupSearchKeyboard() {
    var keys = [
      ['A', 'B', 'C', 'D', 'E', 'F', 'G'],
      ['H', 'I', 'J', 'K', 'L', 'M', 'N'],
      ['O', 'P', 'Q', 'R', 'S', 'T', 'U'],
      ['V', 'W', 'X', 'Y', 'Z', '1', '2'],
      ['3', '4', '5', '6', '7', '8', '9'],
      ['0', 'SPACE', '⌫ BACK', '🔍 SEARCH']
    ];

    var kbContainer = document.getElementById('tv-search-keyboard');
    if (!kbContainer) return;
    kbContainer.innerHTML = '';

    keys.forEach(row => {
      var rowEl = document.createElement('div');
      rowEl.className = 'tv-yt-keyboard-row';
      row.forEach(k => {
        var keyBtn = document.createElement('button');
        var isWide = k.length > 1;
        keyBtn.className = 'tv-yt-key' + (isWide ? ' tv-yt-key-wide' : '');
        keyBtn.tabIndex = 0;
        keyBtn.setAttribute('data-nav', 'true');
        keyBtn.textContent = k;
        keyBtn.addEventListener('click', () => handleKeyInput(k));
        rowEl.appendChild(keyBtn);
      });
      kbContainer.appendChild(rowEl);
    });

    var clearBtn = document.getElementById('tv-search-btn-clear');
    if (clearBtn) {
      clearBtn.addEventListener('click', () => {
        appState.searchQuery = '';
        updateSearchInputDisplay();
        renderSuggestions('');
      });
    }

    var backBtn = document.getElementById('tv-search-btn-back');
    if (backBtn) {
      backBtn.addEventListener('click', closeSearchView);
    }

    renderRecentSearches();
    renderSuggestions('');
  }

  function handleKeyInput(key) {
    if (key === 'SPACE') {
      appState.searchQuery += ' ';
    } else if (key === '⌫ BACK' || key === 'BACKSPACE') {
      appState.searchQuery = appState.searchQuery.slice(0, -1);
    } else if (key === 'CLEAR') {
      appState.searchQuery = '';
    } else if (key === '🔍 SEARCH' || key === 'SEARCH') {
      if (appState.searchQuery.trim().length > 0) {
        performSearchAndShowResults(appState.searchQuery);
      }
      return;
    } else {
      appState.searchQuery += key;
    }

    updateSearchInputDisplay();
    renderSuggestions(appState.searchQuery);
  }

  function updateSearchInputDisplay() {
    var searchInput = document.getElementById('tv-search-input-text');
    if (searchInput) {
      searchInput.textContent = appState.searchQuery || 'Search anime, movies, series...';
      searchInput.style.color = appState.searchQuery ? '#ffffff' : 'var(--tv-text-muted)';
    }
  }

  function renderRecentSearches() {
    var listEl = document.getElementById('tv-recent-searches-list');
    if (!listEl) return;
    listEl.innerHTML = '';

    if (appState.recentSearches.length === 0) {
      listEl.innerHTML = '<span style="color: var(--tv-text-dim); font-size: 13px;">No recent searches</span>';
      return;
    }

    appState.recentSearches.slice(0, 5).forEach(query => {
      var chip = document.createElement('button');
      chip.className = 'tv-yt-suggestion-chip';
      chip.tabIndex = 0;
      chip.setAttribute('data-nav', 'true');
      chip.innerHTML = `<span>🕒</span> <span>${query}</span>`;
      chip.addEventListener('click', () => {
        performSearchAndShowResults(query);
      });
      listEl.appendChild(chip);
    });
  }

  function renderSuggestions(currentQuery) {
    var listEl = document.getElementById('tv-suggestions-list');
    if (!listEl) return;
    listEl.innerHTML = '';

    var clean = currentQuery.trim().toLowerCase();
    var matches = [];

    if (clean.length > 0) {
      matches = POPULAR_SUGGESTIONS.filter(s => s.toLowerCase().includes(clean)).slice(0, 6);
    } else {
      matches = POPULAR_SUGGESTIONS.slice(0, 6);
    }

    if (matches.length === 0) {
      listEl.innerHTML = '<span style="color: var(--tv-text-dim); font-size: 13px;">No match found</span>';
      return;
    }

    matches.forEach(item => {
      var chip = document.createElement('button');
      chip.className = 'tv-yt-suggestion-chip';
      chip.tabIndex = 0;
      chip.setAttribute('data-nav', 'true');
      chip.innerHTML = `<span>💡</span> <span>${item}</span>`;
      chip.addEventListener('click', () => {
        performSearchAndShowResults(item);
      });
      listEl.appendChild(chip);
    });
  }

  // ── Execute Search, Close Keyboard, & Render Fullscreen Results Grid ──
  async function performSearchAndShowResults(query) {
    if (!query || !query.trim()) return;
    var cleanQuery = query.trim();
    appState.searchQuery = cleanQuery;
    addRecentSearch(cleanQuery);

    // 1. Close/Hide the search keyboard overlay completely
    var searchView = document.getElementById('tv-search-view');
    if (searchView) {
      searchView.classList.remove('active');
      SpatialNav.popScope();
    }

    // 2. Clear subviews & show loading in main content area
    hideAllViews();
    var mainContent = document.getElementById('tv-main-content');
    mainContent.style.display = 'block';
    mainContent.innerHTML = `
      <div style="padding: 40px 60px 20px; display: flex; align-items: center; justify-content: space-between;">
        <div>
          <h2 class="tv-rail-title" style="font-size: 32px; margin-bottom: 6px;">🔍 Results for "${cleanQuery}"</h2>
          <span style="color: var(--tv-text-muted); font-size: 16px;">Searching catalog across Anime, Movies, TV Shows & Novels...</span>
        </div>
        <button class="tv-btn" tabindex="0" data-nav="true" id="tv-btn-search-again" style="font-size: 16px; padding: 12px 24px;">
          🔍 Search Again
        </button>
      </div>
      <div class="tv-loading-spinner"></div>
    `;

    var searchAgainBtn = mainContent.querySelector('#tv-btn-search-again');
    if (searchAgainBtn) {
      searchAgainBtn.addEventListener('click', () => renderSearchView());
    }

    // 3. Determine search scope (searches all categories if on Home, or specific category if on Anime/Movies/etc.)
    var searchCategory = 'all';
    if (appState.previousSection && appState.previousSection !== 'home' && appState.previousSection !== 'search' && appState.previousSection !== 'you') {
      searchCategory = appState.previousSection;
    }

    var results = await NovaApi.searchContent(searchCategory, cleanQuery);

    mainContent.innerHTML = `
      <div style="padding: 40px 60px 20px; display: flex; align-items: center; justify-content: space-between;">
        <div>
          <h2 class="tv-rail-title" style="font-size: 32px; margin-bottom: 6px;">🔍 Results for "${cleanQuery}"</h2>
          <span style="color: var(--tv-text-muted); font-size: 16px;">Found ${results.length} titles</span>
        </div>
        <button class="tv-btn" tabindex="0" data-nav="true" id="tv-btn-search-again" style="font-size: 16px; padding: 12px 24px;">
          🔍 Search Again
        </button>
      </div>
    `;

    var newSearchAgainBtn = mainContent.querySelector('#tv-btn-search-again');
    if (newSearchAgainBtn) {
      newSearchAgainBtn.addEventListener('click', () => renderSearchView());
    }

    if (!results || results.length === 0) {
      mainContent.innerHTML += `
        <div style="padding: 60px; font-size: 22px; color: var(--tv-text-muted); text-align: center;">
          No titles found matching "${cleanQuery}". Try another keyword or check spelling.
        </div>
      `;
      if (newSearchAgainBtn) SpatialNav.focus(newSearchAgainBtn);
      return;
    }

    // 4. Render 6-Column Fullscreen Results Grid
    var grid = document.createElement('div');
    grid.className = 'tv-category-grid';
    grid.setAttribute('data-nav-row', 'true');

    results.forEach(item => {
      var card = createCard(item);
      grid.appendChild(card);
    });

    mainContent.appendChild(grid);

    // 5. Automatically focus the first result card for instant autoplay on OK!
    setTimeout(() => {
      var firstCard = grid.querySelector('.tv-card');
      if (firstCard) {
        SpatialNav.focus(firstCard);
      }
    }, 120);
  }

  function closeSearchView() {
    var searchView = document.getElementById('tv-search-view');
    if (searchView && searchView.classList.contains('active')) {
      searchView.classList.remove('active');
      SpatialNav.popScope();
      var targetSection = appState.previousSection && appState.previousSection !== 'search' ? appState.previousSection : 'home';
      switchSection(targetSection);
    }
  }

  function renderSearchView(sourceSection) {
    if (sourceSection) {
      appState.previousSection = sourceSection;
    } else if (appState.currentSection !== 'search') {
      appState.previousSection = appState.currentSection;
    }
    var searchView = document.getElementById('tv-search-view');
    searchView.classList.add('active');
    renderRecentSearches();
    renderSuggestions(appState.searchQuery);
    updateSearchInputDisplay();
    SpatialNav.pushScope(searchView, document.querySelector('.tv-yt-key'));
  }

  // ── Global App Back Handler ────────────────────────────────────────
  function handleAppBack() {
    var splash = document.getElementById('tv-splash-view');
    if (splash && !splash.classList.contains('hidden')) {
      splash.classList.add('hidden');
      SpatialNav.popScope();
      var target = document.querySelector('.tv-sidebar-item.active') || document.querySelector('.tv-sidebar-item');
      if (target) SpatialNav.focus(target);
      return true;
    }

    var searchView = document.getElementById('tv-search-view');
    if (searchView.classList.contains('active')) {
      closeSearchView();
      return true;
    }

    var detailView = document.getElementById('tv-detail-view');
    if (detailView.classList.contains('active')) {
      detailView.classList.remove('active');
      SpatialNav.popScope();
      return true;
    }

    var readerView = document.getElementById('tv-reader-view');
    if (readerView.classList.contains('active')) {
      readerView.classList.remove('active');
      SpatialNav.popScope();
      return true;
    }

    var searchView = document.getElementById('tv-search-view');
    if (searchView.classList.contains('active')) {
      searchView.classList.remove('active');
      SpatialNav.popScope();
      switchSection('home');
      return true;
    }

    if (appState.currentSection !== 'home') {
      switchSection('home');
      return true;
    }

    return false;
  }
})();
