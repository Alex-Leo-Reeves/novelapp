/**
 * NovaRead TV Main Application Coordinator v2 for Hisense VIDAA TV
 * Full parity with the Android TV app:
 *  - Every sidebar tab is its own dedicated view (Home, Novels, Manga,
 *    Comics, Anime, Donghua, K-Drama, Cartoons, Classics, Movies,
 *    Nollywood LIVE, Sports, Live TV, Downloads, You, Search)
 *  - Every title click opens a Detail page: Play / Add to Watchlist /
 *    full episode chooser (season grouped, correctly ordered)
 *  - Resume question (Continue / Start over) wherever progress exists
 *  - Nollywood = LIVE Nigerian TV channels + on-demand rails
 *  - Sports = football fixtures (flat ESPN shape) + WWE
 *  - Create Account page + referral codes + guest mode for testing
 *  - Premium gates wired to the Flutterwave checkout
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
    previousSection: 'home',
    currentDetailMedia: null,
    currentEpisodes: [],
    history: [],
    watchlist: [],
    recentSearches: [],
    searchQuery: '',
    searchDebounceTimer: null,
    tvConfig: null,
    readerChapters: [],
    readerIndex: 0,
    readerItem: null,
    readerMode: 'novel',
    mangaChapters: [],
    mangaIndex: 0
  };

  document.addEventListener('DOMContentLoaded', initApp);

  async function initApp() {
    SpatialNav.init(document.getElementById('tv-app-root'));
    TvPlayer.init();
    loadLocalState();
    SpatialNav.onBack(handleAppBack);
    setupSidebar();
    setupSearchKeyboard();
    setupAuthView();
    setupViewButtons();
    setupPremiumGateListener();
    checkAuthAndPairing();
    loadTvConfigAndFeed();

    document.addEventListener('tvplayerclosed', function (e) {
      if (e.detail && e.detail.media) addHistory(e.detail.media);
      loadContinueWatchingRail();
      var detailView = document.getElementById('tv-detail-view');
      if (detailView.classList.contains('active')) {
        var playBtn = document.getElementById('tv-detail-btn-play');
        if (playBtn) SpatialNav.focus(playBtn);
      }
    });
  }

  function setupViewButtons() {
    var epBack = document.getElementById('tv-episodes-view-back');
    if (epBack) epBack.addEventListener('click', closeEpisodesView);
    var readerBack = document.getElementById('tv-reader-btn-back');
    if (readerBack) readerBack.addEventListener('click', closeReaderView);

    // Auto-scroll focused episode cards / rail cards into view — remote
    // navigation must always reveal the row the user is on.
    document.addEventListener('focusin', function (e) {
      var el = e.target;
      if (!el || !el.classList) return;
      var isEpisode = el.classList.contains('tv-episode-card');
      var inEpisodeArea = el.closest && el.closest('.tv-episodes-grid, .tv-episodes-view-grid, #tv-player-episodes-list');
      var isCard = el.classList.contains('tv-card') && el.closest && el.closest('.tv-rail-content');
      if (isEpisode || inEpisodeArea || isCard) {
        try { el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
        catch (err) { try { el.scrollIntoView(); } catch (e2) {} }
      }
    });
  }

  function loadLocalState() {
    try {
      appState.history = JSON.parse(localStorage.getItem('tv_watch_history') || '[]');
      appState.watchlist = JSON.parse(localStorage.getItem('tv_watchlist') || '[]');
      appState.recentSearches = JSON.parse(localStorage.getItem('tv_recent_searches') || '[]');
    } catch (e) {
      appState.history = [];
      appState.watchlist = [];
      appState.recentSearches = [];
    }
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
    var filtered = appState.recentSearches.filter(function (s) { return s.toLowerCase() !== clean.toLowerCase(); });
    filtered.unshift(clean);
    appState.recentSearches = filtered.slice(0, 10);
    saveLocalState();
    renderRecentSearches();
  }

  function addHistory(item) {
    if (!item || !item.title) return;
    appState.history = appState.history.filter(function (x) {
      return x.id !== item.id && x.title !== item.title;
    });
    appState.history.unshift({
      id: item.id, title: item.title, coverUrl: item.coverUrl,
      mediaKind: item.mediaKind, detailPageUrl: item.detailPageUrl
    });
    appState.history = appState.history.slice(0, 30);
    saveLocalState();
  }

  function showToast(text) {
    var toast = document.getElementById('tv-app-toast');
    if (!toast) return;
    toast.textContent = text;
    toast.classList.add('active');
    clearTimeout(toast._timer);
    toast._timer = setTimeout(function () { toast.classList.remove('active'); }, 2200);
  }

  // ── Splash / Pairing / Guest ───────────────────────────────────────────
  async function checkAuthAndPairing() {
    var user = NovaApi.getUserSession();
    var splash = document.getElementById('tv-splash-view');
    var pairCodeEl = document.getElementById('tv-pair-code');
    var qrBox = document.getElementById('tv-splash-qr');
    var guestBtn = document.getElementById('tv-btn-guest-login');

    function dismissSplash() {
      if (splash && !splash.classList.contains('hidden')) {
        splash.classList.add('hidden');
        try { SpatialNav.popScope(); } catch (e) {}
        var target = document.querySelector('.tv-sidebar-item.active') ||
          document.querySelector('.tv-sidebar-item') ||
          document.querySelector('#tv-main-content .tv-card') ||
          document.querySelector('.tv-btn');
        if (target) SpatialNav.focus(target);
      }
    }
    appState.dismissSplash = dismissSplash;

    if (guestBtn) {
      guestBtn.addEventListener('click', function () {
        var guestUser = { id: 'guest_' + Date.now(), username: 'Guest', plan: 'free', isGuest: true };
        NovaApi.saveUserSession(guestUser);
        updateUserBadge(guestUser);
        dismissSplash();
      });
    }
    var accountBtn = document.getElementById('tv-btn-create-account');
    if (accountBtn) accountBtn.addEventListener('click', function () { openAuthView('signup'); });
    var loginBtn = document.getElementById('tv-btn-login');
    if (loginBtn) loginBtn.addEventListener('click', function () { openAuthView('login'); });

    if (user) {
      updateUserBadge(user);
      splash.classList.add('hidden');
      if (user.authToken) {
        NovaApi.authMe().then(function (freshUser) {
          if (freshUser) updateUserBadge(freshUser);
        }).catch(function () {});
      }
      return;
    }

    if (guestBtn) SpatialNav.pushScope(splash, guestBtn);

    var fallbackUrl = 'https://novelapp1.onrender.com/tv-pair.html';
    if (window.NovaQR && qrBox) NovaQR.render(fallbackUrl, qrBox, 190);

    try {
      var pair = await NovaApi.startTvPair();
      if (pair && pair.code) {
        if (pairCodeEl) pairCodeEl.textContent = pair.code;
        var pairUrl = pair.qrContent || ('https://novelapp1.onrender.com/tv-pair.html?pair=' + pair.pairId);
        if (window.NovaQR && qrBox) NovaQR.render(pairUrl, qrBox, 190);
        var pollTimer = setInterval(async function () {
          if (splash.classList.contains('hidden')) { clearInterval(pollTimer); return; }
          var res = await NovaApi.pollTvPairStatus(pair.pairId);
          if (res.status === 'approved' && res.user) {
            clearInterval(pollTimer);
            updateUserBadge(res.user);
            dismissSplash();
          } else if (res.status === 'expired') {
            clearInterval(pollTimer);
          }
        }, 3000);
      } else if (pairCodeEl) {
        pairCodeEl.textContent = 'TV-' + Math.floor(1000 + Math.random() * 9000);
      }
    } catch (e) {
      if (pairCodeEl) pairCodeEl.textContent = 'TV-' + Math.floor(1000 + Math.random() * 9000);
    }
  }

  function updateUserBadge(user) {
    var name = document.getElementById('tv-user-name');
    var avatar = document.getElementById('tv-user-avatar');
    if (user && name && avatar) {
      var isPrem = user.isPremium || user.plan === 'premium';
      name.textContent = (user.username || 'Guest') + (isPrem ? ' · PREMIUM' : '');
      avatar.textContent = (user.username || 'G').charAt(0).toUpperCase();
    }
  }

  // ── Create Account / Login view ────────────────────────────────────────
  function setupAuthView() {
    var tabSignup = document.getElementById('tv-auth-tab-signup');
    var tabLogin = document.getElementById('tv-auth-tab-login');
    var btnSignup = document.getElementById('tv-auth-btn-signup');
    var btnLogin = document.getElementById('tv-auth-btn-login');
    var btnBack = document.getElementById('tv-auth-btn-back');
    if (tabSignup) tabSignup.addEventListener('click', function () { setAuthMode('signup'); });
    if (tabLogin) tabLogin.addEventListener('click', function () { setAuthMode('login'); });
    if (btnSignup) btnSignup.addEventListener('click', submitAuthSignup);
    if (btnLogin) btnLogin.addEventListener('click', submitAuthLogin);
    if (btnBack) btnBack.addEventListener('click', closeAuthView);
  }

  function openAuthView(mode) {
    var view = document.getElementById('tv-auth-view');
    if (!view) return;
    setAuthMode(mode || 'signup');
    view.classList.add('active');
    var firstInput = view.querySelector('#tv-auth-username');
    if (!firstInput || firstInput.offsetParent === null) {
      firstInput = view.querySelector('#tv-auth-email');
    }
    SpatialNav.pushScope(view, firstInput || view.querySelector('input, button'));
  }

  function closeAuthView() {
    var view = document.getElementById('tv-auth-view');
    if (view && view.classList.contains('active')) {
      view.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
      return true;
    }
    return false;
  }

  function setAuthMode(mode) {
    var signupFields = document.getElementById('tv-auth-signup-fields');
    var signupBtn = document.getElementById('tv-auth-btn-signup');
    var loginBtn = document.getElementById('tv-auth-btn-login');
    var titleEl = document.getElementById('tv-auth-title');
    var errEl = document.getElementById('tv-auth-error');
    var tabS = document.getElementById('tv-auth-tab-signup');
    var tabL = document.getElementById('tv-auth-tab-login');
    if (errEl) errEl.textContent = '';
    if (mode === 'login') {
      signupFields.style.display = 'none';
      signupBtn.style.display = 'none';
      loginBtn.style.display = 'inline-block';
      titleEl.textContent = 'Welcome back';
      if (tabS) tabS.classList.remove('active');
      if (tabL) tabL.classList.add('active');
    } else {
      signupFields.style.display = 'block';
      signupBtn.style.display = 'inline-block';
      loginBtn.style.display = 'none';
      titleEl.textContent = 'Create your account';
      if (tabS) tabS.classList.add('active');
      if (tabL) tabL.classList.remove('active');
    }
  }

  function authError(msg) {
    var errEl = document.getElementById('tv-auth-error');
    if (errEl) errEl.textContent = msg;
  }

  async function submitAuthSignup() {
    var val = function (id) { var el = document.getElementById(id); return el ? el.value : ''; };
    var username = val('tv-auth-username');
    var email = val('tv-auth-email');
    var password = val('tv-auth-password');
    var recovery = val('tv-auth-recovery');
    var referral = val('tv-auth-referral');
    if (!username.trim() || !email.trim() || !password) {
      authError('Username, email and password are required.');
      return;
    }
    authError('Creating account…');
    var user = await NovaApi.authRegister(username.trim(), email.trim(), password, recovery.trim(), referral.trim());
    if (user) {
      if (referral.trim()) {
        try { localStorage.setItem('nova_referral_applied', referral.trim()); } catch (e) {}
      }
      authError('');
      updateUserBadge(user);
      closeAuthView();
      if (appState.dismissSplash) appState.dismissSplash();
      showToast('Welcome, ' + user.username + '!');
    } else {
      authError('Could not create the account. Try another email or check your connection.');
    }
  }

  async function submitAuthLogin() {
    var val = function (id) { var el = document.getElementById(id); return el ? el.value : ''; };
    var email = val('tv-auth-email');
    var password = val('tv-auth-password');
    if (!email.trim() || !password) {
      authError('Email and password are required.');
      return;
    }
    authError('Signing in…');
    var user = await NovaApi.login(email.trim(), password);
    if (user) {
      authError('');
      updateUserBadge(user);
      closeAuthView();
      if (appState.dismissSplash) appState.dismissSplash();
      showToast('Welcome back, ' + user.username + '!');
    } else {
      authError('Login failed. Check your email and password.');
    }
  }

  // ── Sidebar / Section switching (each tab = dedicated view) ────────────
  function setupSidebar() {
    var items = document.querySelectorAll('.tv-sidebar-item');
    items.forEach(function (item) {
      item.addEventListener('click', function () {
        switchSection(item.getAttribute('data-section'));
      });
    });
  }

  async function loadTvConfigAndFeed() {
    appState.tvConfig = await NovaApi.fetchTvConfig();
    switchSection('home');
  }

  function hideAllViews() {
    ['tv-detail-view', 'tv-reader-view', 'tv-episodes-view'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.classList.remove('active');
    });
  }

  // Allowed kinds per tab — stops content leaking across tabs
  var TAB_KIND_FILTER = {
    novels: ['novel'], novel: ['novel'],
    manga: ['manga'],
    comics: ['comic'], comic: ['comic'],
    anime: ['anime'],
    donghua: ['donghua'],
    kdrama: ['kdrama'],
    cartoon: ['cartoon'],
    classic: ['classic'],
    movies: ['movie', 'tv'],
    nigerian: ['nigerian']
  };

  function filterByTab(items, section) {
    var allowed = TAB_KIND_FILTER[section];
    if (!allowed || !Array.isArray(items)) return items || [];
    // Strict: wrong-kind items are dropped entirely (a whole page of
    // wrong-kind items shows nothing rather than leaking other tabs).
    return items.filter(function (it) {
      return allowed.indexOf(String(it.mediaKind || '').toLowerCase()) !== -1;
    });
  }

  async function switchSection(section) {
    appState.previousSection = appState.currentSection;
    appState.currentSection = section;

    document.querySelectorAll('.tv-sidebar-item').forEach(function (btn) {
      btn.classList.toggle('active', btn.getAttribute('data-section') === section);
    });

    hideAllViews();
    var authView = document.getElementById('tv-auth-view');
    if (authView) authView.classList.remove('active');

    var mainContent = document.getElementById('tv-main-content');
    mainContent.style.display = 'block';
    mainContent.innerHTML = '<div class="tv-loading-spinner"></div>';
    mainContent.scrollTop = 0;

    try {
      if (section === 'home') await renderHomeSection(mainContent);
      else if (section === 'novels') await renderNovelsSection(mainContent);
      else if (section === 'manga') await renderMangaSection(mainContent);
      else if (section === 'comics') await renderCategorySection(mainContent, 'comic');
      else if (section === 'anime') await renderCategorySection(mainContent, 'anime');
      else if (section === 'donghua') await renderCategorySection(mainContent, 'donghua');
      else if (section === 'kdrama') await renderCategorySection(mainContent, 'kdrama');
      else if (section === 'cartoon') await renderCategorySection(mainContent, 'cartoon');
      else if (section === 'classic') await renderCategorySection(mainContent, 'classic');
      else if (section === 'movies') await renderCategorySection(mainContent, 'movie');
      else if (section === 'sports') await renderSportsSection(mainContent);
      else if (section === 'live') renderLiveTvSection(mainContent);
      else if (section === 'downloads') renderDownloadsSection(mainContent);
      else if (section === 'you') await renderYouSection(mainContent);
      else if (section === 'search') renderSearchView();
      else await renderCategorySection(mainContent, section);
    } catch (e) {
      mainContent.innerHTML = '<div style="padding:60px;color:var(--tv-text-muted);font-size:20px;">' +
        'Something went wrong loading this section. Press BACK and try again.</div>';
    }
    // Landing at the TOP of every tab, always
    mainContent.scrollTop = 0;
  }

  // ── Shared UI helpers ──────────────────────────────────────────────────
  function createSearchBarTrigger(hintText) {
    var bar = document.createElement('div');
    bar.className = 'tv-search-bar-trigger';
    bar.tabIndex = 0;
    bar.setAttribute('data-nav', 'true');
    bar.innerHTML = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>' +
      '<span>' + (hintText || 'Search anime, movies, series, novels...') + '</span>' +
      '<span class="tv-search-shortcut">Press OK to Search</span>';
    bar.addEventListener('click', function () { renderSearchView(); });
    return bar;
  }

  var KIND_LABELS = {
    anime: 'ANIME', donghua: 'DONGHUA', movie: 'MOVIE', tv: 'SERIES',
    kdrama: 'K-DRAMA', cartoon: 'CARTOON', classic: 'CLASSIC',
    nigerian: 'NOLLYWOOD', novel: 'NOVEL', manga: 'MANGA', comic: 'COMIC'
  };

  function createCard(item, progressPct) {
    var card = document.createElement('div');
    card.className = 'tv-card';
    card.tabIndex = 0;
    card.setAttribute('data-nav', 'true');

    var poster = item.coverUrl || 'https://via.placeholder.com/200x300?text=No+Cover';
    var kindLabel = KIND_LABELS[item.mediaKind] || String(item.mediaKind || 'NOVAREAD').toUpperCase();
    var srcLabel = item.sourceName ? String(item.sourceName).slice(0, 14) : '';
    var progressHtml = (progressPct !== null && progressPct !== undefined)
      ? '<div class="tv-card-progress"><div class="tv-card-progress-bar" style="width: ' + progressPct + '%"></div></div>' : '';

    card.innerHTML = '<img class="tv-card-poster" src="' + poster + '" alt="" loading="lazy" ' +
      'onerror="this.onerror=null;this.src=\'https://via.placeholder.com/200x300?text=No+Cover\'"/>' +
      '<span class="tv-card-kind">' + kindLabel + '</span>' +
      (srcLabel ? '<span class="tv-card-source">' + srcLabel + '</span>' : '') +
      progressHtml +
      '<div class="tv-card-info"><div class="tv-card-title"></div><div class="tv-card-subtitle"></div></div>';

    card.querySelector('.tv-card-title').textContent = item.title || 'Untitled';
    card.querySelector('.tv-card-subtitle').textContent = item.genre || item.mediaKind || 'NovaRead';
    card.addEventListener('click', function () { openDetail(item); });
    return card;
  }

  function createRail(titleText, items) {
    var rail = document.createElement('div');
    rail.className = 'tv-rail';
    rail.setAttribute('data-nav-row', 'true');
    var header = document.createElement('div');
    header.className = 'tv-rail-header';
    header.innerHTML = '<h3 class="tv-rail-title"></h3>';
    header.querySelector('.tv-rail-title').textContent = titleText;
    rail.appendChild(header);
    var content = document.createElement('div');
    content.className = 'tv-rail-content';
    (items || []).forEach(function (item) { content.appendChild(createCard(item)); });
    rail.appendChild(content);
    return rail;
  }

  function createContinueWatchingRail() {
    var list = [];
    for (var i = 0; i < localStorage.length; i++) {
      var key = localStorage.key(i);
      if (key && key.indexOf('tv_progress_') === 0) {
        try {
          var data = JSON.parse(localStorage.getItem(key));
          if (data && data.duration > 0 && data.title) list.push(data);
        } catch (e) {}
      }
    }
    if (list.length === 0) return null;
    list.sort(function (a, b) { return (b.updatedAt || 0) - (a.updatedAt || 0); });

    var rail = document.createElement('div');
    rail.className = 'tv-rail';
    rail.setAttribute('data-nav-row', 'true');
    rail.id = 'tv-continue-rail';
    var header = document.createElement('div');
    header.className = 'tv-rail-header';
    header.innerHTML = '<h3 class="tv-rail-title">⏳ Continue Watching</h3>';
    rail.appendChild(header);
    var content = document.createElement('div');
    content.className = 'tv-rail-content';
    list.slice(0, 12).forEach(function (item) {
      var pct = Math.min(100, Math.round((item.position / item.duration) * 100));
      content.appendChild(createCard({
        id: item.id,
        title: item.title,
        coverUrl: item.coverUrl,
        mediaKind: item.mediaKind || 'movie',
        detailPageUrl: item.detailPageUrl || '',
        sourceName: item.sourceName || '',
        genre: item.episodeTitle || 'Resume playback'
      }, pct));
    });
    rail.appendChild(content);
    return rail;
  }

  function loadContinueWatchingRail() {
    var existing = document.getElementById('tv-continue-rail');
    if (existing && existing.parentNode) {
      var updated = createContinueWatchingRail();
      if (updated) existing.parentNode.replaceChild(updated, existing);
      else existing.remove();
    }
  }

  function createSectionHeader(title, subtitle) {
    var header = document.createElement('div');
    header.className = 'tv-category-header';
    header.innerHTML = '<h2 class="tv-rail-title" style="font-size: 32px;"></h2>' +
      '<span style="color: var(--tv-text-muted); font-size: 16px;"></span>';
    header.querySelector('h2').textContent = title;
    header.querySelector('span').textContent = subtitle || '';
    return header;
  }

  function focusFirstCard(container) {
    setTimeout(function () {
      var main = document.getElementById('tv-main-content');
      if (main) main.scrollTop = 0;
      var first = container.querySelector('.tv-card, .tv-match-card, .tv-channel-card');
      if (first) {
        try { first.focus({ preventScroll: true }); } catch (e) { first.focus(); }
        SpatialNav.focus(first);
      }
    }, 120);
  }

  /** Bring the focus glow back after closing any overlay view. */
  function restoreFocus() {
    setTimeout(function () {
      var active = document.querySelector('#tv-detail-view.active, #tv-reader-view.active, #tv-episodes-view.active, #tv-search-view.active');
      if (active) return; // another overlay owns focus
      var main = document.getElementById('tv-main-content');
      if (main) main.scrollTop = 0;
      var target = main && (main.querySelector('.tv-card') || main.querySelector('.tv-btn')) ||
        document.querySelector('.tv-sidebar-item.active') ||
        document.querySelector('.tv-sidebar-item');
      if (target) {
        try { target.focus({ preventScroll: true }); } catch (e) { target.focus(); }
        SpatialNav.focus(target);
      }
    }, 60);
  }

  // ── Home ───────────────────────────────────────────────────────────────
  async function renderHomeSection(container) {
    container.innerHTML = '';
    container.appendChild(createSearchBarTrigger('🔍 Search all anime, movies, series, light novels...'));

    var trendingAnime = await NovaApi.fetchContentHome('anime');
    var heroItem = trendingAnime[0] || null;

    if (heroItem) {
      var hero = document.createElement('div');
      hero.className = 'tv-hero';
      hero.style.backgroundImage = 'url(\'' + (heroItem.backdropUrl || heroItem.coverUrl) + '\')';
      var kindLabel = KIND_LABELS[heroItem.mediaKind] || 'FEATURED';
      hero.innerHTML = '<div class="tv-hero-content">' +
        '<span class="tv-hero-kind">' + kindLabel + '</span>' +
        '<h1 class="tv-hero-title"></h1>' +
        '<p class="tv-hero-synopsis"></p>' +
        '<div class="tv-hero-actions">' +
        '<button class="tv-btn tv-btn-primary" tabindex="0" data-nav="true" id="tv-hero-play">▶ Play</button>' +
        '<button class="tv-btn" tabindex="0" data-nav="true" id="tv-hero-details">ⓘ Details</button>' +
        '</div></div>';
      hero.querySelector('.tv-hero-title').textContent = heroItem.title;
      hero.querySelector('.tv-hero-synopsis').textContent =
        (heroItem.synopsis || 'Stream in crystal-clear quality with subtitles on NovaRead TV.').slice(0, 220);
      hero.querySelector('#tv-hero-play').addEventListener('click', function () { openDetail(heroItem, true); });
      hero.querySelector('#tv-hero-details').addEventListener('click', function () { openDetail(heroItem, false); });
      container.appendChild(hero);
    }

    var continueRail = createContinueWatchingRail();
    if (continueRail) container.appendChild(continueRail);
    container.appendChild(createRail('🔥 Trending Anime', trendingAnime.slice(0, 18)));

    var novels = await NovaApi.fetchNovelsMultiSource(1).catch(function () { return []; });
    if (novels.length) container.appendChild(createRail('📚 Popular Novels', novels.slice(0, 18)));

    var manga = await NovaApi.fetchContentHome('manga').catch(function () { return []; });
    if (manga.length) container.appendChild(createRail('🎨 Top Manga', manga.slice(0, 18)));

    var movies = await NovaApi.fetchContentHome('movie').catch(function () { return []; });
    if (movies.length) container.appendChild(createRail('🎬 New Movies & Series', movies.slice(0, 18)));

    var kdrama = await NovaApi.fetchContentHome('kdrama').catch(function () { return []; });
    if (kdrama.length) container.appendChild(createRail('🇰🇷 K-Drama Picks', kdrama.slice(0, 12)));

    setTimeout(function () {
      var first = container.querySelector('.tv-card, .tv-btn');
      if (first) SpatialNav.focus(first);
    }, 150);
  }

  // ── Category grids (Anime / Movies / K-Drama / Donghua / etc.) ─────────
  var CATEGORY_LABELS = {
    anime: '🎌 Anime', movie: '🎬 Movies & TV Shows', kdrama: '🇰🇷 K-Drama',
    donghua: '🐉 Donghua', cartoon: '😄 Cartoons', classic: '🕰️ Classic TV',
    comic: '💥 Comics'
  };

  async function renderCategorySection(container, category) {
    var displayName = CATEGORY_LABELS[category] || (category || '').toUpperCase();

    container.innerHTML = '';
    container.appendChild(createSearchBarTrigger('🔍 Search in ' + displayName + '...'));
    container.appendChild(createSectionHeader(displayName, 'Browse all titles'));
    var spinner = document.createElement('div');
    spinner.className = 'tv-loading-spinner';
    container.appendChild(spinner);

    var currentPage = 1;
    var isLoading = false;
    var hasMore = true;

    var items = await NovaApi.fetchContentHome(category, currentPage);
    items = filterByTab(items, category);
    spinner.remove();

    if (!items || items.length === 0) {
      var empty = document.createElement('div');
      empty.style.cssText = 'padding:60px;font-size:22px;color:var(--tv-text-muted);text-align:center;';
      empty.textContent = 'No items found for ' + displayName + ' right now — try again shortly.';
      container.appendChild(empty);
      return;
    }

    var grid = document.createElement('div');
    grid.className = 'tv-category-grid';
    grid.setAttribute('data-nav-row', 'true');

    function appendItems(newItems) {
      (newItems || []).forEach(function (item) { grid.appendChild(createCard(item)); });
    }
    appendItems(items);

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
      nextItems = filterByTab(nextItems, category);
      // Skip pages that are entirely wrong-kind (up to 3 tries) instead of
      // showing nothing — keeps "Load More" useful when the backend mixes.
      var skipped = 0;
      while ((!nextItems || nextItems.length === 0) && skipped < 3) {
        currentPage++;
        nextItems = await NovaApi.fetchContentHome(category, currentPage);
        nextItems = filterByTab(nextItems, category);
        skipped++;
      }
      if (nextItems && nextItems.length > 0) {
        if (loadMoreBtn.parentNode) loadMoreBtn.remove();
        appendItems(nextItems);
        grid.appendChild(loadMoreBtn);
        loadMoreBtn.textContent = '▼ Load More Titles...';
      } else {
        hasMore = false;
        if (loadMoreBtn.parentNode) loadMoreBtn.remove();
      }
      isLoading = false;
    }

    loadMoreBtn.addEventListener('click', loadNextPage);
    loadMoreBtn.addEventListener('focus', function () { loadNextPage(); });
    grid.appendChild(loadMoreBtn);
    container.appendChild(grid);
    focusFirstCard(container);
  }

  // ── Novels (multi-source, like the TV app) ─────────────────────────────
  async function renderNovelsSection(container) {
    container.innerHTML = '';
    container.appendChild(createSearchBarTrigger('🔍 Search light novels, web novels, fan fiction...'));
    container.appendChild(createSectionHeader('📚 Light Novels',
      'RoyalRoad · ReadNovelFull · Trending · Rising Stars'));

    var spinner = document.createElement('div');
    spinner.className = 'tv-loading-spinner';
    container.appendChild(spinner);

    var currentPage = 1;
    var isLoading = false;

    var grid = document.createElement('div');
    grid.className = 'tv-category-grid';
    grid.setAttribute('data-nav-row', 'true');

    // Background enrichment notice — updates in place when extra sources land
    var enrichNote = document.createElement('div');
    enrichNote.className = 'tv-enrich-note';
    enrichNote.textContent = '⏳ Loading more sources (ReadNovelFull, Trending, Rising Stars)…';
    container.appendChild(enrichNote);

    function onEnriched() {
      document.removeEventListener('novelenriched', onEnriched);
      if (enrichNote.parentNode) enrichNote.remove();
      if (appState.currentSection === 'novels') {
        currentPage = 1;
        loadNovelPage(1, true).then(function () {
          showToast('More novel sources loaded!');
        });
      }
    }
    document.addEventListener('novelenriched', onEnriched);
    // Hide the note after 40s regardless
    setTimeout(function () { if (enrichNote.parentNode) enrichNote.remove(); }, 40000);

    var loadMoreBtn = document.createElement('button');
    loadMoreBtn.className = 'tv-load-more-card';
    loadMoreBtn.tabIndex = 0;
    loadMoreBtn.setAttribute('data-nav', 'true');
    loadMoreBtn.textContent = '▼ Load More Novels...';
    loadMoreBtn.addEventListener('click', async function () {
      if (isLoading) return;
      isLoading = true;
      loadMoreBtn.textContent = '⏳ Loading more novels...';
      currentPage++;
      await loadNovelPage(currentPage, false);
      loadMoreBtn.textContent = '▼ Load More Novels...';
      isLoading = false;
    });

    container.appendChild(grid);

    async function loadNovelPage(page, replace) {
      var items = await NovaApi.fetchNovelsMultiSource(page).catch(function () { return []; });
      items = filterByTab(items, 'novels');
      if (spinner.parentNode) spinner.remove();
      if (replace) grid.innerHTML = '';
      if (!items || items.length === 0) {
        if (replace) {
          var empty = document.createElement('div');
          empty.style.cssText = 'padding:60px;font-size:22px;color:var(--tv-text-muted);text-align:center;';
          empty.textContent = 'Novel sources are warming up — press BACK and reopen in a moment.';
          container.appendChild(empty);
        }
        return;
      }
      items.forEach(function (item) { grid.appendChild(createCard(item)); });
      if (page === 1 && !loadMoreBtn.parentNode) grid.appendChild(loadMoreBtn);
      if (replace) focusFirstCard(container);
    }

    await loadNovelPage(1, true);
  }

  // ── Manga (WeebCentral primary · MangaDex fallback) ────────────────────
  async function renderMangaSection(container) {
    container.innerHTML = '';
    container.appendChild(createSearchBarTrigger('🔍 Search WeebCentral & MangaDex titles...'));
    container.appendChild(createSectionHeader('🎨 Manga', 'WeebCentral · MangaDex · full chapters, right on your TV'));

    var spinner = document.createElement('div');
    spinner.className = 'tv-loading-spinner';
    container.appendChild(spinner);

    var currentPage = 1;
    var isLoading = false;

    var grid = document.createElement('div');
    grid.className = 'tv-category-grid';
    grid.setAttribute('data-nav-row', 'true');

    var loadMoreBtn = document.createElement('button');
    loadMoreBtn.className = 'tv-load-more-card';
    loadMoreBtn.tabIndex = 0;
    loadMoreBtn.setAttribute('data-nav', 'true');
    loadMoreBtn.textContent = '▼ Load More Manga...';
    loadMoreBtn.addEventListener('click', async function () {
      if (isLoading) return;
      isLoading = true;
      loadMoreBtn.textContent = '⏳ Loading more manga...';
      currentPage++;
      await loadMangaPage(currentPage, false);
      loadMoreBtn.textContent = '▼ Load More Manga...';
      isLoading = false;
    });

    container.appendChild(grid);

    async function loadMangaPage(page, replace) {
      // Combine WeebCentral + MangaDex on every page
      // On desktop, weebCentralHome may fall back to MangaDex (CORS blocked),
      // so filter to only real WeebCentral items to avoid duplicates
      var wcRaw = await NovaApi.weebCentralHome(page).catch(function () { return []; });
      var wcItems = wcRaw.filter(function (it) {
        return (it.sourceName || '').toLowerCase().indexOf('weebcentral') !== -1 ||
               (it.id || '').indexOf('weebcentral') === 0;
      });
      var mdItems = await NovaApi.mangadexHome(page).catch(function () { return []; });
      var seen = {};
      var items = [];
      wcItems.forEach(function (item) { if (item.id && !seen[item.id]) { seen[item.id] = 1; items.push(item); } });
      mdItems.forEach(function (item) { if (item.id && !seen[item.id]) { seen[item.id] = 1; items.push(item); } });
      items = filterByTab(items, 'manga');
      if (spinner.parentNode) spinner.remove();
      if (replace) grid.innerHTML = '';
      if (!items || items.length === 0) {
        if (replace) {
          var empty = document.createElement('div');
          empty.style.cssText = 'padding:60px;font-size:22px;color:var(--tv-text-muted);text-align:center;';
          empty.textContent = 'Manga sources are unreachable right now — try again in a moment.';
          container.appendChild(empty);
        }
        return;
      }
      items.forEach(function (item) { grid.appendChild(createCard(item)); });
      if (!loadMoreBtn.parentNode) grid.appendChild(loadMoreBtn);
      if (replace) focusFirstCard(container);
    }

    await loadMangaPage(1, true);
  }

  // ── Global Live TV ─────────────────────────────────────────────────────
  function createLiveChannelCard(channel) {
    var card = document.createElement('div');
    card.className = 'tv-card tv-channel-card';
    card.tabIndex = 0;
    card.setAttribute('data-nav', 'true');
    var logo = channel.logo || 'https://via.placeholder.com/200x300?text=LIVE';
    card.innerHTML = '<img class="tv-card-poster" src="' + logo + '" alt="" loading="lazy" ' +
      'onerror="this.onerror=null;this.src=\'https://via.placeholder.com/200x300?text=LIVE\'"/>' +
      '<span class="tv-card-kind tv-live-kind">● LIVE</span>' +
      '<div class="tv-card-info"><div class="tv-card-title"></div><div class="tv-card-subtitle"></div></div>';
    card.querySelector('.tv-card-title').textContent = channel.title;
    card.querySelector('.tv-card-subtitle').textContent =
      (channel.source ? channel.source + ' · ' : '') + (channel.group || channel.genre || 'Live channel');
    card.addEventListener('click', function () {
      TvPlayer.open({
        id: 'live_' + channel.title.replace(/\s+/g, '_'),
        title: channel.title,
        coverUrl: channel.logo,
        mediaKind: 'live',
        isLive: true,
        isLiveChannel: true
      }, [{ title: channel.title, streamUrl: channel.streamUrl }], 0, channel.streamUrl);
    });
    return card;
  }

  function renderLiveTvSection(container) {
    container.innerHTML = '';
    container.appendChild(createSearchBarTrigger('🔍 Search live channels...'));
    container.appendChild(createSectionHeader('📡 Live TV',
      'Free public broadcaster channels — news, sports, movies, kids'));

    var grid = document.createElement('div');
    grid.className = 'tv-category-grid';
    grid.setAttribute('data-nav-row', 'true');
    NovaApi.fetchCuratedLiveChannels().forEach(function (ch) {
      grid.appendChild(createLiveChannelCard(ch));
    });
    container.appendChild(grid);
    focusFirstCard(container);
  }

  // ── Downloads (web parity note + library) ──────────────────────────────
  function renderDownloadsSection(container) {
    container.innerHTML = '';
    container.appendChild(createSectionHeader('⬇️ Downloads',
      'Offline downloads live in the Android TV app — this screen keeps your watchlist close'));
    var note = document.createElement('div');
    note.style.cssText = 'margin:10px 60px 24px;padding:18px 24px;border:1px solid var(--tv-line);border-radius:12px;color:var(--tv-text-muted);';
    note.textContent = 'TV-web cannot write files to disk. Everything you add to your watchlist is available here and on the Android app.';
    container.appendChild(note);

    if (appState.watchlist.length) {
      container.appendChild(createRail('⭐ Your Watchlist', appState.watchlist));
    } else {
      var empty = document.createElement('div');
      empty.style.cssText = 'padding:40px 60px;color:var(--tv-text-muted);font-size:20px;';
      empty.textContent = 'Your watchlist is empty — open any title and choose "+ Add to Watchlist".';
      container.appendChild(empty);
    }
  }

  // ── Sports: football (flat ESPN shape!) + WWE ──────────────────────────
  async function renderSportsSection(container) {
    container.innerHTML = '';
    container.appendChild(createSearchBarTrigger('🔍 Search live football fixtures, teams & WWE events...'));
    container.appendChild(createSectionHeader('⚽ Live Sports & Fixtures',
      'Real-time scores and direct streams — Premier League, La Liga, Serie A, UCL & WWE'));

    var spinner = document.createElement('div');
    spinner.className = 'tv-loading-spinner';
    container.appendChild(spinner);

    var fixtures = await NovaApi.fetchFootballFixtures().catch(function () { return []; });
    var wweEvents = await NovaApi.fetchWweEvents().catch(function () { return []; });
    if (spinner.parentNode) spinner.remove();

    if (fixtures.length === 0 && wweEvents.length === 0) {
      var empty = document.createElement('div');
      empty.style.cssText = 'padding:60px;font-size:22px;color:var(--tv-text-muted);text-align:center;';
      empty.textContent = 'No matches on the board right now — check back shortly!';
      container.appendChild(empty);
      return;
    }

    if (fixtures.length) {
      var byLeague = {};
      fixtures.forEach(function (f) {
        var lg = f.leagueName || 'Football';
        if (!byLeague[lg]) byLeague[lg] = [];
        byLeague[lg].push(f);
      });
      Object.keys(byLeague).forEach(function (lg) {
        var rail = document.createElement('div');
        rail.className = 'tv-rail';
        rail.setAttribute('data-nav-row', 'true');
        var header = document.createElement('div');
        header.className = 'tv-rail-header';
        header.innerHTML = '<h3 class="tv-rail-title">⚽ ' + lg + '</h3>';
        rail.appendChild(header);
        var content = document.createElement('div');
        content.className = 'tv-rail-content';
        byLeague[lg].slice(0, 10).forEach(function (f) {
          content.appendChild(createMatchCard(f));
        });
        rail.appendChild(content);
        container.appendChild(rail);
      });
    }

    if (wweEvents.length) {
      var wweRail = document.createElement('div');
      wweRail.className = 'tv-rail';
      wweRail.setAttribute('data-nav-row', 'true');
      var wweHeader = document.createElement('div');
      wweHeader.className = 'tv-rail-header';
      wweHeader.innerHTML = '<h3 class="tv-rail-title">🥊 WWE Events</h3>';
      wweRail.appendChild(wweHeader);
      var wweContent = document.createElement('div');
      wweContent.className = 'tv-rail-content';
      wweEvents.slice(0, 10).forEach(function (w) {
        wweContent.appendChild(createWweCard(w));
      });
      wweRail.appendChild(wweContent);
      container.appendChild(wweRail);
    }

    focusFirstCard(container);
  }

  function createMatchCard(fixture) {
    var card = document.createElement('div');
    card.className = 'tv-match-card';
    card.tabIndex = 0;
    card.setAttribute('data-nav', 'true');

    var status = String(fixture.status || 'NS').toUpperCase();
    var isLive = !!fixture.isLive;
    var badgeText = isLive
      ? '<span class="tv-live-dot"></span> LIVE' + (fixture.elapsed ? " " + fixture.elapsed + "'" : '')
      : (fixture.isFinished ? status : (fixture.matchTime || status));
    var score = (fixture.homeGoals !== null && fixture.homeGoals !== undefined)
      ? (fixture.homeGoals + ' - ' + fixture.awayGoals)
      : (fixture.matchTime || 'vs');

    card.innerHTML = '<div class="tv-match-header">' +
      '<span class="tv-league-name">' + (fixture.leagueName || 'Football') + '</span>' +
      '<span class="tv-live-badge ' + (isLive ? '' : 'tv-live-badge-muted') + '">' + badgeText + '</span></div>' +
      '<div class="tv-match-teams">' +
      '<div class="tv-team-info"><img class="tv-team-logo" src="' + (fixture.homeLogo || '') + '" onerror="this.style.display=\'none\'"/><span>' + fixture.homeTeam + '</span></div>' +
      '<div class="tv-match-score">' + score + '</div>' +
      '<div class="tv-team-info"><img class="tv-team-logo" src="' + (fixture.awayLogo || '') + '" onerror="this.style.display=\'none\'"/><span>' + fixture.awayTeam + '</span></div>' +
      '</div>' +
      '<div class="tv-match-hint">▶ Press OK to watch</div>';

    card.addEventListener('click', async function () {
      showToast('Finding streams for ' + fixture.homeTeam + ' vs ' + fixture.awayTeam + '…');
      var urls = await NovaApi.resolveFootballStreamList(
        fixture.homeTeam, fixture.awayTeam, fixture.leagueName
      ).catch(function () { return []; });
      if (!urls.length) {
        showToast('No stream found for this fixture yet.');
        return;
      }
      var candidates = urls.map(function (u, i) {
        return { provider: 'Football Server ' + (i + 1), url: u, route: 'embed' };
      });
      TvPlayer.open({
        id: 'fixture_' + fixture.fixtureId,
        title: fixture.homeTeam + ' vs ' + fixture.awayTeam,
        coverUrl: fixture.homeLogo || fixture.awayLogo,
        mediaKind: 'sports',
        isLive: true,
        isLiveChannel: true
      }, [{ title: fixture.homeTeam + ' vs ' + fixture.awayTeam, streamUrl: urls[0] }], 0, urls[0], candidates);
    });
    return card;
  }

  function createWweCard(event) {
    var card = document.createElement('div');
    card.className = 'tv-match-card';
    card.tabIndex = 0;
    card.setAttribute('data-nav', 'true');
    card.innerHTML = '<div class="tv-match-header">' +
      '<span class="tv-league-name">WWE · ' + (event.brand || 'Event') + '</span>' +
      '<span class="tv-live-badge tv-live-badge-muted">' + (event.eventType || 'WWE') + '</span></div>' +
      '<div class="tv-team-info" style="font-size:22px;padding:12px 0;">🥊 ' + (event.title || 'WWE Event') + '</div>' +
      '<div class="tv-match-hint">▶ Press OK to watch</div>';

    card.addEventListener('click', async function () {
      showToast('Resolving WWE stream…');
      var streamUrl = await NovaApi.fetchWweStream(event.eventId || event.id, event.title, event.detailPageUrl || event.detailUrl)
        .catch(function () { return null; });
      if (!streamUrl) {
        showToast('WWE stream unavailable right now.');
        return;
      }
      TvPlayer.open({
        id: 'wwe_' + (event.eventId || event.id),
        title: event.title || 'WWE Event',
        coverUrl: event.posterUrl,
        mediaKind: 'sports',
        isLive: true,
        isLiveChannel: true
      }, [{ title: event.title || 'WWE Event', streamUrl: streamUrl }], 0, streamUrl);
    });
    return card;
  }

  // ── Detail page (Play / Watchlist / full episode chooser) ──────────────
  async function openDetail(item, autoPlay) {
    appState.currentDetailMedia = item;
    var detailView = document.getElementById('tv-detail-view');
    var backdrop = document.getElementById('tv-detail-backdrop');
    var poster = document.getElementById('tv-detail-poster');
    var title = document.getElementById('tv-detail-title');
    var kindBadge = document.getElementById('tv-detail-kind');
    var srcBadge = document.getElementById('tv-detail-source');
    var rating = document.getElementById('tv-detail-rating');
    var year = document.getElementById('tv-detail-year');
    var genre = document.getElementById('tv-detail-genre');
    var synopsis = document.getElementById('tv-detail-synopsis');
    var episodesList = document.getElementById('tv-detail-episodes-grid');
    var episodesSection = document.getElementById('tv-detail-episodes-section');
    var playBtn = document.getElementById('tv-detail-btn-play');
    var browseBtn = document.getElementById('tv-detail-btn-episodes');
    var watchlistBtn = document.getElementById('tv-detail-btn-watchlist');

    backdrop.style.backgroundImage = 'url(\'' + (item.backdropUrl || item.coverUrl) + '\')';
    poster.src = item.coverUrl || '';
    title.textContent = item.title;
    kindBadge.textContent = KIND_LABELS[item.mediaKind] || 'FEATURED';
    srcBadge.textContent = item.sourceName || 'NovaCloud';
    srcBadge.style.display = item.sourceName ? 'inline-block' : 'none';
    rating.style.display = item.rating ? 'inline-block' : 'none';
    rating.textContent = '⭐ ' + (item.rating || '');
    year.style.display = item.year ? 'inline-block' : 'none';
    year.textContent = '📅 ' + (item.year || '');
    genre.style.display = item.genre ? 'inline-block' : 'none';
    genre.textContent = '🏷️ ' + (item.genre || '');
    synopsis.textContent = item.synopsis || 'Stream in crystal-clear Full HD with subtitles on NovaRead TV.';

    playBtn.innerHTML = kindOf(item) === 'novel' ? '📖 Read Novel' : (kindOf(item) === 'manga' ? '📖 Read' : '▶ Play');
    episodesList.innerHTML = '<div style="color: var(--tv-text-muted);">Loading episodes and chapters…</div>';
    browseBtn.style.display = 'none';
    detailView.classList.add('active');
    SpatialNav.pushScope(detailView, playBtn);

    // Load the episode/chapter list (sorted, season-aware)
    var episodes = [];
    var dKind = kindOf(item);
    if (dKind === 'novel') {
      episodes = await NovaApi.fetchChapters('novel', item.detailPageUrl, item.title, item.sourceName);
    } else if (dKind === 'manga') {
      episodes = await NovaApi.fetchChapters('manga', item.detailPageUrl, item.title, item.sourceName);
    } else if (item.videoId || String(item.detailPageUrl || '').indexOf('youtube://') === 0) {
      episodes = [];
    } else {
      episodes = await NovaApi.fetchChapters(item.mediaKind, item.detailPageUrl, item.title, item.sourceName);
    }
    appState.currentEpisodes = episodes;

    if (autoPlay) {
      startItem(item, episodes, 0);
      return;
    }

    renderDetailEpisodes(item, episodes);

    // Resume support (TV-app parity): the most recent saved progress for this
    // title turns Play into "▶ Resume · <episode>" and jumps straight to it.
    var resumeIdx = -1;
    var resumeSaved = null;
    try {
      var prefix = 'tv_progress_' + (item.id || item.title) + '__';
      for (var li = 0; li < localStorage.length; li++) {
        var lk = localStorage.key(li);
        if (lk && lk.indexOf(prefix) === 0) {
          var lsaved = JSON.parse(localStorage.getItem(lk));
          if (lsaved && lsaved.position > 15 && lsaved.duration > 0 &&
              lsaved.position < lsaved.duration - 20 &&
              (!resumeSaved || (lsaved.updatedAt || 0) > (resumeSaved.updatedAt || 0))) {
            resumeSaved = lsaved;
          }
        }
      }
    } catch (e) {}
    if (resumeSaved && typeof resumeSaved.episodeIndex === 'number' &&
        resumeSaved.episodeIndex >= 0 && resumeSaved.episodeIndex < episodes.length) {
      resumeIdx = resumeSaved.episodeIndex;
    } else if (resumeSaved && episodes.length === 0) {
      resumeIdx = 0;
    }

    if (resumeIdx >= 0) {
      playBtn.innerHTML = '▶ Resume · ' + (resumeSaved.episodeTitle || ('Episode ' + (resumeIdx + 1)));
      playBtn.onclick = function () { startItem(item, appState.currentEpisodes, resumeIdx); };
    } else {
      playBtn.innerHTML = kindOf(item) === 'novel' ? '📖 Read Novel' : (kindOf(item) === 'manga' ? '📖 Read' : '▶ Play');
      playBtn.onclick = function () { startItem(item, appState.currentEpisodes, 0); };
    }
    browseBtn.onclick = function () { openEpisodesView(item, appState.currentEpisodes); };
    updateWatchlistBtnState(watchlistBtn, item);
    watchlistBtn.onclick = function () {
      toggleWatchlist(item);
      updateWatchlistBtnState(watchlistBtn, item);
    };
  }

  function updateWatchlistBtnState(btn, item) {
    var inList = appState.watchlist.some(function (x) { return x.id === item.id || x.title === item.title; });
    btn.innerHTML = inList ? '✔ In Watchlist' : '+ Add to Watchlist';
  }

  function toggleWatchlist(item) {
    var idx = -1;
    for (var i = 0; i < appState.watchlist.length; i++) {
      if (appState.watchlist[i].id === item.id || appState.watchlist[i].title === item.title) { idx = i; break; }
    }
    if (idx !== -1) {
      appState.watchlist.splice(idx, 1);
      showToast('Removed from watchlist');
    } else {
      appState.watchlist.push({
        id: item.id, title: item.title, coverUrl: item.coverUrl,
        mediaKind: item.mediaKind, detailPageUrl: item.detailPageUrl,
        sourceName: item.sourceName, backdropUrl: item.backdropUrl
      });
      showToast('Added to watchlist');
    }
    saveLocalState();
  }

  function renderDetailEpisodes(item, episodes) {
    var episodesList = document.getElementById('tv-detail-episodes-grid');
    var episodesSection = document.getElementById('tv-detail-episodes-section');
    var browseBtn = document.getElementById('tv-detail-btn-episodes');
    episodesList.innerHTML = '';

    if (!episodes || episodes.length === 0) {
      episodesSection.style.display = 'none';
      browseBtn.style.display = 'none';
      return;
    }
    episodesSection.style.display = 'block';
    if (episodes.length > 8) browseBtn.style.display = 'inline-block';

    // Season grouping when the list spans multiple seasons
    var seasons = {};
    episodes.forEach(function (ep) {
      var s = ep.seasonNumber || 1;
      if (!seasons[s]) seasons[s] = [];
      seasons[s].push(ep);
    });
    var seasonKeys = Object.keys(seasons);
    var multiSeason = seasonKeys.length > 1;

    var maxShow = multiSeason ? 40 : 12;
    var shown = 0;
    seasonKeys.sort(function (a, b) { return parseFloat(a) - parseFloat(b); }).forEach(function (sk) {
      if (shown >= maxShow) return;
      if (multiSeason) {
        var seasonHeader = document.createElement('div');
        seasonHeader.className = 'tv-season-header';
        seasonHeader.textContent = 'Season ' + sk;
        episodesList.appendChild(seasonHeader);
      }
      seasons[sk].forEach(function (ep) {
        if (shown >= maxShow) return;
        var idx = episodes.indexOf(ep);
        episodesList.appendChild(createEpisodeChip(item, ep, idx));
        shown++;
      });
    });

    if (episodes.length > maxShow) {
      var more = document.createElement('div');
      more.className = 'tv-season-header';
      more.style.color = 'var(--tv-cyan)';
      more.textContent = '+ ' + (episodes.length - maxShow) + ' more — choose "Browse All Episodes"';
      episodesList.appendChild(more);
    }
  }

  function createEpisodeChip(item, ep, idx) {
    var chip = document.createElement('button');
    chip.className = 'tv-episode-card';
    chip.tabIndex = 0;
    chip.setAttribute('data-nav', 'true');

    var saved = null;
    try {
      var raw = localStorage.getItem('tv_progress_' + (item.id || item.title) + '__' + (ep.url || ep.title || idx));
      if (raw) saved = JSON.parse(raw);
    } catch (e) {}

    var epNum = (ep.seasonNumber && ep.seasonNumber > 1 ? 'S' + ep.seasonNumber + 'E' : 'E') +
      (ep.chapterNumber || (idx + 1));
    var label = (ep.chapterNumber ? epNum + ' · ' : '') + (ep.title || ('Episode ' + (idx + 1)));
    if (saved && saved.position > 15 && saved.duration > 0 && saved.position < saved.duration - 20) {
      chip.classList.add('has-progress');
      label = '▶ ' + label;
    }
    chip.textContent = label;
    chip.addEventListener('click', function () { startItem(item, appState.currentEpisodes, idx); });
    return chip;
  }

  // ── Full-screen episode chooser (separate UI) ──────────────────────────
  function openEpisodesView(item, episodes) {
    var view = document.getElementById('tv-episodes-view');
    var grid = document.getElementById('tv-episodes-view-grid');
    var titleEl = document.getElementById('tv-episodes-view-title');
    if (!view || !grid) return;
    titleEl.textContent = item.title + ' — Episodes';
    grid.innerHTML = '';
    (episodes || []).forEach(function (ep, idx) {
      grid.appendChild(createEpisodeChip(item, ep, idx));
    });
    view.classList.add('active');
    var first = grid.querySelector('.tv-episode-card');
    SpatialNav.pushScope(view, first);
  }

  function closeEpisodesView() {
    var view = document.getElementById('tv-episodes-view');
    if (view && view.classList.contains('active')) {
      view.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
      restoreFocus();
      return true;
    }
    return false;
  }

  // ── Start playback / reading ───────────────────────────────────────────
  function kindOf(item) {
    var k = String((item && item.mediaKind) || '').toLowerCase();
    var url = String((item && item.detailPageUrl) || '');
    if (/^weebcentral:/i.test(url) || /^mangadex:/i.test(url) || /^mangadex_/i.test(String(item && item.id) || '')) return 'manga';
    if (/^royalroad/i.test(url) || /^royalroad_/i.test(String(item && item.id) || '')) return 'novel';
    if (k === 'manga' || k === 'comic') return 'manga';
    if (k === 'novel') return 'novel';
    if (k === 'anime' || k === 'donghua' || k === 'movie' || k === 'tv' || k === 'kdrama' ||
        k === 'cartoon' || k === 'classic' || k === 'nigerian' || k === 'sports' || k === 'live') return 'video';
    return (item && item.isVideo) ? 'video' : 'video';
  }

  async function startItem(item, episodes, index) {
    episodes = episodes || [];
    var kind = kindOf(item);

    if (kind === 'novel') return openNovelReader(item, episodes, index);
    if (kind === 'manga') return openMangaViewer(item, episodes, index);

    // Everything else is video — close overlays and play
    var detailView = document.getElementById('tv-detail-view');
    detailView.classList.remove('active');
    try { SpatialNav.popScope(); } catch (e) {}
    closeEpisodesView();

    if (item.videoId || String(item.detailPageUrl || '').indexOf('youtube://') === 0) {
      var videoId = item.videoId || String(item.detailPageUrl).replace('youtube://', '');
      showToast('Resolving stream…');
      var youtubeUrl = await NovaApi.fetchYouTubeStream(videoId).catch(function () { return null; });
      if (youtubeUrl) {
        TvPlayer.open(item, [{ title: item.title, streamUrl: youtubeUrl }], 0, youtubeUrl);
        return;
      }
      var embedUrl = 'https://www.youtube.com/embed/' + videoId;
      TvPlayer.open(item, [{ title: item.title, streamUrl: embedUrl }], 0, embedUrl);
      return;
    }

    TvPlayer.open(item, episodes, index);
  }

  // ── Reader scrolling (TV remote: UP/DOWN scroll, CH+/− page) ───────────
  var readerKeyUnbind = null;

  function readerKeyHandler(code) {
    var body = document.getElementById('tv-reader-body');
    if (!body) return false;
    var step = Math.max(260, Math.round((body.clientHeight || 600) * 0.55));
    if (code === 40) { body.scrollBy(0, step); return true; }
    if (code === 38) { body.scrollBy(0, -step); return true; }
    if (code === 34 || code === 428) { body.scrollBy(0, (body.clientHeight || 600) * 0.92); return true; }
    if (code === 33 || code === 427) { body.scrollBy(0, -(body.clientHeight || 600) * 0.92); return true; }
    return false; // LEFT/RIGHT still navigate chapter chips
  }

  function bindReaderKeys() {
    if (readerKeyUnbind) readerKeyUnbind();
    readerKeyUnbind = SpatialNav.registerKeyHandler(readerKeyHandler);
  }

  function unbindReaderKeys() {
    if (readerKeyUnbind) { readerKeyUnbind(); readerKeyUnbind = null; }
  }

  // ── Novel Reader (paged, progress tracked) ─────────────────────────────
  async function openNovelReader(item, chapters, index) {
    var view = document.getElementById('tv-reader-view');
    var title = document.getElementById('tv-reader-title');
    var body = document.getElementById('tv-reader-body');
    var chipRow = document.getElementById('tv-reader-chips');
    if (!view) return;

    appState.readerItem = item;
    appState.readerMode = 'novel';
    appState.readerChapters = chapters && chapters.length ? chapters
      : await NovaApi.fetchChapters('novel', item.detailPageUrl, item.title, item.sourceName);
    appState.readerIndex = Math.max(0, Math.min(index || 0, appState.readerChapters.length - 1));

    title.textContent = item.title;
    body.innerHTML = '<div class="tv-loading-spinner"></div>';
    if (chipRow) chipRow.innerHTML = '';
    view.classList.add('active');
    SpatialNav.pushScope(view, document.getElementById('tv-reader-btn-back'));
    bindReaderKeys();

    await loadNovelChapter(appState.readerIndex);
  }

  async function loadNovelChapter(idx) {
    var body = document.getElementById('tv-reader-body');
    var chipRow = document.getElementById('tv-reader-chips');
    var chapters = appState.readerChapters;
    var item = appState.readerItem;
    if (!chapters || !chapters.length) {
      body.innerHTML = '<p style="color:var(--tv-text-muted)">No chapters found for this novel.</p>';
      return;
    }
    idx = Math.max(0, Math.min(idx, chapters.length - 1));
    appState.readerIndex = idx;
    var ch = chapters[idx];

    body.innerHTML = '<div class="tv-loading-spinner"></div>';
    if (chipRow) {
      chipRow.innerHTML = '';
      var prevBtn = document.createElement('button');
      prevBtn.className = 'tv-btn';
      prevBtn.tabIndex = 0;
      prevBtn.setAttribute('data-nav', 'true');
      prevBtn.textContent = '← Previous Chapter';
      prevBtn.disabled = idx === 0;
      prevBtn.addEventListener('click', function () { loadNovelChapter(idx - 1); });

      var nextBtn = document.createElement('button');
      nextBtn.className = 'tv-btn tv-btn-primary';
      nextBtn.tabIndex = 0;
      nextBtn.setAttribute('data-nav', 'true');
      nextBtn.textContent = 'Next Chapter →';
      nextBtn.addEventListener('click', function () { loadNovelChapter(idx + 1); });

      var label = document.createElement('span');
      label.style.cssText = 'color:var(--tv-text-muted);align-self:center;';
      label.textContent = 'Chapter ' + (ch.chapterNumber || (idx + 1)) + ' of ' + chapters.length;

      chipRow.appendChild(prevBtn);
      chipRow.appendChild(label);
      chipRow.appendChild(nextBtn);
    }

    var text = await NovaApi.fetchChapterText(ch.url, item.title, item.sourceName);
    if (!text) {
      body.innerHTML = '<p style="color:var(--tv-text-muted)">This chapter could not be loaded — try the next one.</p>';
      return;
    }
    var paras = text.split(/\n+/).filter(function (p) { return p.trim(); });
    body.innerHTML = '';
    paras.forEach(function (p) {
      var el = document.createElement('p');
      el.style.cssText = 'font-size:20px;line-height:1.8;margin:0 0 18px;color:#e8e8f0;';
      el.textContent = p;
      body.appendChild(el);
    });
    body.scrollTop = 0;
    if (chipRow) SpatialNav.focus(chipRow.querySelector('.tv-btn-primary'));
  }

  function readerPrevChapter() {
    if (appState.readerMode === 'manga') loadMangaChapter(appState.mangaIndex - 1);
    else loadNovelChapter(appState.readerIndex - 1);
  }

  function readerNextChapter() {
    if (appState.readerMode === 'manga') loadMangaChapter(appState.mangaIndex + 1);
    else loadNovelChapter(appState.readerIndex + 1);
  }

  // ── Manga Viewer (paged pages, MangaDex) ───────────────────────────────
  async function openMangaViewer(item, chapters, index) {
    var view = document.getElementById('tv-reader-view');
    var title = document.getElementById('tv-reader-title');
    var body = document.getElementById('tv-reader-body');
    var chipRow = document.getElementById('tv-reader-chips');
    if (!view) return;

    appState.readerItem = item;
    appState.readerMode = 'manga';
    appState.mangaChapters = chapters && chapters.length ? chapters
      : await NovaApi.fetchChapters('manga', item.detailPageUrl, item.title, item.sourceName);
    appState.mangaIndex = Math.max(0, Math.min(index || 0, appState.mangaChapters.length - 1));

    title.textContent = item.title;
    body.innerHTML = '<div class="tv-loading-spinner"></div>';
    if (chipRow) chipRow.innerHTML = '';
    view.classList.add('active');
    SpatialNav.pushScope(view, document.getElementById('tv-reader-btn-back'));
    bindReaderKeys();

    await loadMangaChapter(appState.mangaIndex);
  }

  async function loadMangaChapter(idx) {
    var body = document.getElementById('tv-reader-body');
    var chipRow = document.getElementById('tv-reader-chips');
    var chapters = appState.mangaChapters;
    var item = appState.readerItem;
    if (!chapters || !chapters.length) {
      // One retry through the full chapter pipeline before giving up
      body.innerHTML = '<div class="tv-loading-spinner"></div>';
      chapters = appState.mangaChapters = await NovaApi.fetchChapters(
        'manga', item.detailPageUrl, item.title, item.sourceName);
      if (!chapters.length) {
        body.innerHTML = '<p style="color:var(--tv-text-muted)">No chapters found for this manga. ' +
          'Press BACK and try another title, or reopen it in a moment.</p>';
        return;
      }
      idx = Math.max(0, Math.min(idx, chapters.length - 1));
    }
    idx = Math.max(0, Math.min(idx, chapters.length - 1));
    appState.mangaIndex = idx;
    var ch = chapters[idx];

    body.innerHTML = '<div class="tv-loading-spinner"></div>';
    if (chipRow) {
      chipRow.innerHTML = '';
      var prevBtn = document.createElement('button');
      prevBtn.className = 'tv-btn';
      prevBtn.tabIndex = 0;
      prevBtn.setAttribute('data-nav', 'true');
      prevBtn.textContent = '← Previous Chapter';
      prevBtn.disabled = idx === 0;
      prevBtn.addEventListener('click', function () { loadMangaChapter(idx - 1); });

      var nextBtn = document.createElement('button');
      nextBtn.className = 'tv-btn tv-btn-primary';
      nextBtn.tabIndex = 0;
      nextBtn.setAttribute('data-nav', 'true');
      nextBtn.textContent = 'Next Chapter →';
      nextBtn.addEventListener('click', function () { loadMangaChapter(idx + 1); });

      var label = document.createElement('span');
      label.style.cssText = 'color:var(--tv-text-muted);align-self:center;';
      label.textContent = 'Chapter ' + (ch.chapterNumber || (idx + 1)) + ' of ' + chapters.length;

      chipRow.appendChild(prevBtn);
      chipRow.appendChild(label);
      chipRow.appendChild(nextBtn);
    }

    var pages = await NovaApi.fetchMangaPages(ch.url);
    body.innerHTML = '';
    if (!pages || pages.length === 0) {
      body.innerHTML = '<p style="color:var(--tv-text-muted)">No pages available for this chapter — try the next one.</p>';
      return;
    }
    pages.forEach(function (pg) {
      var img = document.createElement('img');
      img.src = pg;
      img.referrerPolicy = 'no-referrer';
      img.style.cssText = 'display:block;max-width:70%;margin:0 auto 12px;border-radius:6px;';
      body.appendChild(img);
    });
    body.scrollTop = 0;
    if (chipRow) SpatialNav.focus(chipRow.querySelector('.tv-btn-primary'));
  }

  function closeReaderView() {
    var view = document.getElementById('tv-reader-view');
    if (view && view.classList.contains('active')) {
      view.classList.remove('active');
      unbindReaderKeys();
      try { SpatialNav.popScope(); } catch (e) {}
      restoreFocus();
      return true;
    }
    return false;
  }

  // ── You: library, account, premium, referral ───────────────────────────
  async function renderYouSection(container) {
    container.innerHTML = '';
    container.appendChild(createSearchBarTrigger('🔍 Search your library, bookmarks & watch history...'));
    container.appendChild(createSectionHeader('👤 My Library', 'Watchlist · Continue watching · Account'));
    try {
      container.appendChild(createAccountPanel());
    } catch (e) { /* account panel is non-critical */ }

    try {
      if (appState.watchlist.length) {
        container.appendChild(createRail('⭐ My Watchlist', appState.watchlist));
      } else {
        var emptyWl = document.createElement('div');
        emptyWl.style.cssText = 'padding:20px 60px;color:var(--tv-text-muted);font-size:19px;';
        emptyWl.textContent = 'Your watchlist is empty — open any title and choose "+ Add to Watchlist".';
        container.appendChild(emptyWl);
      }
    } catch (e) { /* watchlist is non-critical */ }

    try {
      var continueRail = createContinueWatchingRail();
      if (continueRail) container.appendChild(continueRail);
    } catch (e) { /* continue rail is non-critical */ }

    try {
      if (appState.history.length) {
        container.appendChild(createRail('🕘 Recently Watched', appState.history.slice(0, 18)));
      }
    } catch (e) { /* history is non-critical */ }
    focusFirstCard(container);
  }

  function referralCodeFor(user) {
    if (!user) return '';
    var base = (user.id || user.username || 'guest').replace(/[^a-zA-Z0-9]/g, '').toUpperCase();
    return ('NOVA-' + base).slice(0, 12);
  }

  function createAccountPanel() {
    var user = NovaApi.getUserSession();
    var panel = document.createElement('div');
    panel.style.cssText = 'margin:10px 60px 24px;padding:24px;border:1px solid var(--tv-line);border-radius:var(--tv-radius);background:var(--tv-panel-elevated);display:flex;align-items:center;gap:24px;flex-wrap:wrap;';
    var guest = !user || user.isGuest;
    var isPrem = user && (user.isPremium || user.plan === 'premium');
    var statusText = guest
      ? 'Guest playback is enabled for testing. Create an account to sync your library, refer friends and go Premium.'
      : (isPrem ? 'Premium active — unlimited streaming on all your devices.' : 'Free plan — previews are limited. Upgrade for unlimited streaming.');

    panel.innerHTML = '<div style="flex:1;min-width:300px;">' +
      '<h3 class="tv-rail-title">' + (guest ? 'Guest account' : (isPrem ? 'Premium account' : 'Free account')) + '</h3>' +
      '<p style="color:var(--tv-text-muted);margin-top:6px;">' + statusText + '</p>' +
      (guest ? '' : '<p style="color:var(--tv-text-muted);margin-top:6px;">Your referral code: <strong style="color:var(--tv-cyan);" id="tv-referral-code"></strong> <button class="tv-btn" id="tv-referral-copy" style="font-size:13px;padding:6px 14px;">Copy</button></p>') +
      '</div>';

    var actions = document.createElement('div');
    actions.style.cssText = 'display:flex;gap:14px;flex-wrap:wrap;';

    if (guest) {
      var signupBtn = document.createElement('button');
      signupBtn.className = 'tv-btn tv-btn-primary';
      signupBtn.textContent = 'Create Account';
      signupBtn.addEventListener('click', function () { openAuthView('signup'); });
      var loginBtn2 = document.createElement('button');
      loginBtn2.className = 'tv-btn';
      loginBtn2.textContent = 'Sign In';
      loginBtn2.addEventListener('click', function () { openAuthView('login'); });
      var premBtn = document.createElement('button');
      premBtn.className = 'tv-btn';
      premBtn.textContent = 'Go Premium';
      premBtn.addEventListener('click', function () { showPremiumCheckout('Free preview limits apply to guests too — Premium unlocks everything.'); });
      actions.appendChild(signupBtn);
      actions.appendChild(loginBtn2);
      actions.appendChild(premBtn);
    } else {
      if (!isPrem) {
        var upgradeBtn = document.createElement('button');
        upgradeBtn.className = 'tv-btn tv-btn-primary';
        upgradeBtn.textContent = 'Go Premium — ₦1,000/month';
        upgradeBtn.addEventListener('click', function () { showPremiumCheckout(); });
        actions.appendChild(upgradeBtn);
      }
      var logoutBtn = document.createElement('button');
      logoutBtn.className = 'tv-btn';
      logoutBtn.textContent = 'Sign Out';
      logoutBtn.addEventListener('click', function () {
        NovaApi.saveUserSession(null);
        updateUserBadge({ username: 'Guest' });
        switchSection('you');
      });
      actions.appendChild(logoutBtn);
    }
    panel.appendChild(actions);
    container.appendChild(panel);

    if (!guest) {
      var codeEl = panel.querySelector('#tv-referral-code');
      if (codeEl) codeEl.textContent = referralCodeFor(user);
      var copyBtn = panel.querySelector('#tv-referral-copy');
      if (copyBtn) {
        copyBtn.addEventListener('click', function () {
          try {
            var ta = document.createElement('textarea');
            ta.value = referralCodeFor(user);
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            ta.remove();
            showToast('Referral code copied!');
          } catch (e) {
            showToast('Your code: ' + referralCodeFor(user));
          }
        });
      }
    }
    return panel;
  }

  // ── Premium checkout (Flutterwave QR, phone-first like the TV app) ─────
  async function showPremiumCheckout(extraCopy) {
    var modal = document.getElementById('tv-payment-modal');
    var titleEl = document.getElementById('tv-payment-title');
    var copyEl = document.getElementById('tv-payment-copy');
    var qrBox = document.getElementById('tv-payment-qr');
    if (!modal) return;
    titleEl.textContent = 'Premium — ₦1,000/month';
    copyEl.textContent = extraCopy || 'Creating a secure Flutterwave payment link…';
    qrBox.innerHTML = '';
    modal.classList.add('active');
    var closeBtn = document.getElementById('tv-payment-close');
    closeBtn.onclick = function () {
      modal.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
    };
    SpatialNav.pushScope(modal, closeBtn);

    var checkout = await NovaApi.createBillingCheckout('premium_3_devices').catch(function () { return null; });
    if (checkout && checkout.link && window.NovaQR) {
      NovaQR.render(checkout.link, qrBox, 220);
      copyEl.textContent = 'Scan to pay with Flutterwave. This TV keeps checking your subscription after payment.';
    } else {
      copyEl.textContent = 'Checkout is unavailable right now. Confirm the paired account and Flutterwave configuration, then try again.';
    }
  }

  function setupPremiumGateListener() {
    document.addEventListener('tvpremiumgate', function (e) {
      var detail = e.detail || {};
      showPremiumCheckout(detail.message || '');
      showToast(detail.hard ? 'Free preview limit reached' : 'Preview grace used — enjoy!');
    });
  }

  // ── YouTube-TV-style search keyboard ───────────────────────────────────
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
    keys.forEach(function (row) {
      var rowEl = document.createElement('div');
      rowEl.className = 'tv-yt-keyboard-row';
      row.forEach(function (k) {
        var keyBtn = document.createElement('button');
        var isWide = k.length > 1;
        keyBtn.className = 'tv-yt-key' + (isWide ? ' tv-yt-key-wide' : '');
        keyBtn.tabIndex = 0;
        keyBtn.setAttribute('data-nav', 'true');
        keyBtn.textContent = k;
        keyBtn.addEventListener('click', function () { handleKeyInput(k); });
        rowEl.appendChild(keyBtn);
      });
      kbContainer.appendChild(rowEl);
    });

    var clearBtn = document.getElementById('tv-search-btn-clear');
    if (clearBtn) {
      clearBtn.addEventListener('click', function () {
        appState.searchQuery = '';
        updateSearchInputDisplay();
        renderSuggestions('');
      });
    }
    var backBtn = document.getElementById('tv-search-btn-back');
    if (backBtn) backBtn.addEventListener('click', closeSearchView);

    renderRecentSearches();
    renderSuggestions('');
  }

  function handleKeyInput(key) {
    if (key === 'SPACE') {
      appState.searchQuery += ' ';
    } else if (key === '⌫ BACK' || key === 'BACKSPACE') {
      appState.searchQuery = appState.searchQuery.slice(0, -1);
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
    appState.recentSearches.forEach(function (q) {
      var btn = document.createElement('button');
      btn.className = 'tv-yt-suggestion';
      btn.tabIndex = 0;
      btn.setAttribute('data-nav', 'true');
      btn.textContent = '🕒 ' + q;
      btn.addEventListener('click', function () {
        appState.searchQuery = q;
        updateSearchInputDisplay();
        performSearchAndShowResults(q);
      });
      listEl.appendChild(btn);
    });
  }

  function renderSuggestions(query) {
    var listEl = document.getElementById('tv-suggestions-list');
    if (!listEl) return;
    listEl.innerHTML = '';
    var q = (query || '').trim().toLowerCase();
    var matches = POPULAR_SUGGESTIONS.filter(function (s) {
      return s.toLowerCase().indexOf(q) !== -1;
    }).slice(0, 8);
    matches.forEach(function (s) {
      var btn = document.createElement('button');
      btn.className = 'tv-yt-suggestion';
      btn.tabIndex = 0;
      btn.setAttribute('data-nav', 'true');
      btn.textContent = '💡 ' + s;
      btn.addEventListener('click', function () {
        appState.searchQuery = s;
        updateSearchInputDisplay();
        performSearchAndShowResults(s);
      });
      listEl.appendChild(btn);
    });
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

  function closeSearchView() {
    var searchView = document.getElementById('tv-search-view');
    if (searchView && searchView.classList.contains('active')) {
      searchView.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
      var target = appState.previousSection || 'home';
      if (target === 'search') target = 'home';
      switchSection(target);
      return true;
    }
    return false;
  }

  async function performSearchAndShowResults(query) {
    addRecentSearch(query);
    var cleanQuery = String(query).trim();
    var mainContent = document.getElementById('tv-search-results');
    var titleEl = document.getElementById('tv-search-results-title');
    titleEl.textContent = 'Top Results for "' + cleanQuery + '"';
    mainContent.innerHTML = '<div class="tv-loading-spinner"></div>';

    var results = await NovaApi.searchContent('all', cleanQuery).catch(function () { return []; });
    mainContent.innerHTML = '';

    if (!results || results.length === 0) {
      var empty = document.createElement('div');
      empty.style.cssText = 'padding:60px;font-size:22px;color:var(--tv-text-muted);text-align:center;';
      empty.textContent = 'No titles found matching "' + cleanQuery + '". Try another keyword.';
      mainContent.appendChild(empty);
      return;
    }

    var grid = document.createElement('div');
    grid.className = 'tv-search-result-grid';
    grid.setAttribute('data-nav-row', 'true');
    results.forEach(function (item) { grid.appendChild(createCard(item)); });
    mainContent.appendChild(grid);
    setTimeout(function () {
      var first = grid.querySelector('.tv-card');
      if (first) {
        try { first.focus({ preventScroll: true }); } catch (e) { first.focus(); }
        SpatialNav.focus(first);
      }
    }, 120);
  }

  // ── Global Back stack (player consumes its own BACK first) ─────────────
  function handleAppBack() {
    // 1. Auth view
    if (closeAuthView()) return true;
    // 2. Full-screen episode chooser
    if (closeEpisodesView()) return true;
    // 3. Reader (novel / manga)
    if (closeReaderView()) return true;
    // 4. Search
    var searchView = document.getElementById('tv-search-view');
    if (searchView && searchView.classList.contains('active')) {
      closeSearchView();
      return true;
    }
    // 5. Detail page
    var detailView = document.getElementById('tv-detail-view');
    if (detailView && detailView.classList.contains('active')) {
      detailView.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
      restoreFocus();
      return true;
    }
    // 6. Section → home
    if (appState.currentSection !== 'home') {
      switchSection('home');
      return true;
    }
    return false;
  }
})();
