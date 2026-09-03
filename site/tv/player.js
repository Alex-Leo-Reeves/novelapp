/**
 * TV Video Player Engine v2 for Hisense VIDAA Smart TVs
 * Parity with the Android TV player:
 *  - PARALLEL SERVER SEARCH: direct/HLS routes probed concurrently, embed
 *    servers raced in hidden-iframe batches — first working server wins.
 *  - Max-volume autoplay (muted fallback, then unmute on 'playing')
 *  - Resume choice (Continue / Start over) with saved positions
 *  - Free preview gates (20% episodic / 20min movies) with premium hand-off
 *  - Binge auto-next countdown, episodes drawer, seek toasts
 *  - BACK key always closes the player (never trapped while watching)
 */

(function (root, factory) {
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.TvPlayer = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var hlsInstance = null;
  var currentMedia = null;
  var currentEpisodeIndex = 0;
  var episodeList = [];
  var isPlayerActive = false;
  var keyUnbind = null;
  var bingeCountdownTimer = null;

  // Parallel server race state
  var routeCandidates = [];
  var activeCandidate = null;
  var raceToken = 0;              // increments to cancel stale probes
  var probeIframes = [];
  var failoverTimer = null;
  var embedWatchdog = null;
  var embedPlayWatchdog = null;
  var resumePosition = 0;
  var pendingEmbedResumeT = 0;
  var pendingOverrideCandidates = null;
  var previewLimits = null;
  var isLiveStream = false;

  // Sequential priority walk state
  var raceState = {
    candidates: [],
    currentIdx: 0,
    token: 0,
    verified: false,
    verifierTimer: null,
    epoch: 0  // increments each tryCandidate to invalidate stale iframe onloads
  };

  var elements = {
    container: null,
    video: null,
    embedIframe: null,
    probeHost: null,
    seekFeedback: null,
    serverStatus: null,
    serverStatusText: null,
    fatalOverlay: null,
    episodeDrawer: null,
    episodeDrawerList: null,
    bingeModal: null,
    bingeCountdown: null,
    resumeModal: null,
    resumeCopy: null,
    continueButton: null,
    startOverButton: null,
    watchNowOverlay: null,
    watchNowBtn: null,
    watchNowTitle: null,
    watchNowServer: null
  };

  // ── Global AdShield for TV Media Playback ──────────────────────────────
  (function installAdShield() {
    try {
      window.open = function (url) {
        console.log('[AdShield] Blocked popup window.open:', url);
        return null;
      };
      window.addEventListener('click', function (e) {
        var target = e.target;
        if (target && target.tagName === 'A' && (target.target === '_blank' || target.getAttribute('target') === '_blank')) {
          if (!target.closest('#tv-app-root') && !target.closest('#tv-player-view')) {
            e.preventDefault();
            e.stopPropagation();
            console.log('[AdShield] Intercepted popup click');
          }
        }
      }, true);
    } catch (e) {}
  })();

  function init() {
    elements.container = document.getElementById('tv-player-view');
    elements.video = document.getElementById('tv-video-element');
    elements.embedIframe = document.getElementById('tv-embed-iframe');
    elements.probeHost = document.getElementById('tv-probe-host');
    elements.seekFeedback = document.getElementById('tv-player-seek-feedback');
    elements.serverStatus = document.getElementById('tv-player-server-status');
    elements.serverStatusText = document.getElementById('tv-player-server-status-text');
    elements.fatalOverlay = document.getElementById('tv-player-fatal');
    elements.episodeDrawer = document.getElementById('tv-player-episodes-drawer');
    elements.episodeDrawerList = document.getElementById('tv-player-episodes-list');
    elements.bingeModal = document.getElementById('tv-player-binge-modal');
    elements.bingeCountdown = document.getElementById('tv-player-binge-countdown');
    elements.resumeModal = document.getElementById('tv-player-resume-modal');
    elements.resumeCopy = document.getElementById('tv-player-resume-copy');
    elements.continueButton = document.getElementById('tv-player-btn-continue');
    elements.startOverButton = document.getElementById('tv-player-btn-start-over');
    elements.watchNowOverlay = document.getElementById('tv-player-watch-now');
    elements.watchNowBtn = document.getElementById('tv-watch-now-btn');
    elements.watchNowTitle = document.getElementById('tv-watch-now-title');
    elements.watchNowServer = document.getElementById('tv-watch-now-server');

    // Native video event listeners
    if (elements.video) {
      elements.video.addEventListener('playing', onVideoPlaying);
      elements.video.addEventListener('error', onVideoError);
      elements.video.addEventListener('ended', onVideoEnded);
      elements.video.addEventListener('timeupdate', onTimeUpdate);
    }

    var retryBtn = document.getElementById('tv-player-btn-retry');
    if (retryBtn) {
      retryBtn.addEventListener('click', function () {
        hideFatalOverlay();
        showLoading(true);
        startServerRace(routeCandidates.length ? routeCandidates.slice() : []);
      });
    }

    var fatalBackBtn = document.getElementById('tv-player-btn-fatal-back');
    if (fatalBackBtn) {
      fatalBackBtn.addEventListener('click', function () { close(); });
    }

    var exitBtn = document.getElementById('tv-player-exit');
    if (exitBtn) {
      exitBtn.addEventListener('click', function () { close(); });
    }

    // Watch Now button: user gesture unlocks fullscreen + autoplay
    if (elements.watchNowBtn) {
      elements.watchNowBtn.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        onWatchNowClick();
      });
    }
  }



  function onVideoPlaying() {
    clearFailoverTimer();
    clearEmbedWatchdog();
    hideServerStatus();
    showLoading(false);
    embedActuallyPlaying = true;
    // Confirm the current candidate as working (sequential walk)
    if (raceState.candidates.length > 0 && !raceState.verified) {
      confirmCandidate();
    }
    // Max volume — restore unmuted playback
    if (elements.video) {
      try {
        elements.video.volume = 1.0;
        elements.video.muted = false;
      } catch (e) {}
      if (resumePosition > 0 && Math.abs(elements.video.currentTime - resumePosition) > 2) {
        try { elements.video.currentTime = resumePosition; } catch (e) {}
        resumePosition = 0;
      }
    }
  }

  function onTimeUpdate() {
    if (!elements.video) return;
    var cur = elements.video.currentTime;
    var dur = elements.video.duration;
    if (dur > 0 && Math.floor(cur) % 5 === 0 && currentMedia && !isLiveStream) {
      saveProgress(cur, dur);
    }
    checkPreviewGate(cur, dur);
  }

  function onVideoError() {
    if (!isPlayerActive || !currentMedia) return;
    if (elements.video.style.display !== 'none') {
      failoverToNextRoute('This stream failed — switching server…');
    }
  }

  function saveProgress(position, duration) {
    if (!currentMedia || isLiveStream) return;
    try {
      localStorage.setItem(progressKey(), JSON.stringify({
        id: currentMedia.id,
        title: currentMedia.title,
        coverUrl: currentMedia.coverUrl,
        backdropUrl: currentMedia.backdropUrl || '',
        episodeTitle: episodeList[currentEpisodeIndex]
          ? (episodeList[currentEpisodeIndex].title || ('Episode ' + (currentEpisodeIndex + 1)))
          : currentMedia.title,
        episodeIndex: currentEpisodeIndex,
        episodeUrl: episodeList[currentEpisodeIndex] ? (episodeList[currentEpisodeIndex].url || '') : '',
        episodeCount: episodeList.length,
        mediaKind: currentMedia.mediaKind || 'movie',
        detailPageUrl: currentMedia.detailPageUrl || '',
        sourceName: currentMedia.sourceName || '',
        isAnime: !!currentMedia.isAnime,
        position: position,
        duration: duration,
        updatedAt: Date.now()
      }));
    } catch (e) {}
  }

  function getSavedProgress(key) {
    try {
      var raw = localStorage.getItem(key);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  }

  function clearSavedProgress(key) {
    try { localStorage.removeItem(key); } catch (e) {}
  }

  function progressKey() {
    var ep = episodeList[currentEpisodeIndex] || {};
    var marker = ep.url || ep.title || currentEpisodeIndex;
    return 'tv_progress_' + (currentMedia.id || currentMedia.title) + '__' + marker;
  }

  function episodeProgressKey(item, episode, index) {
    var marker = (episode && (episode.url || episode.title)) || index;
    return 'tv_progress_' + (item.id || item.title) + '__' + marker;
  }

  // ── TV Remote Keys (player scope consumes keys first) ──────────────────
  function handlePlayerRemoteKeys(code, e) {
    if (!isPlayerActive) return false;

    var BACK = [8, 27, 461, 10009, 10182, 88];
    var PLAY_PAUSE = [179, 10252];
    var PLAY = [415, 250];
    var PAUSE = [19];
    var STOP = [413];
    var CHANNEL_UP = [427];
    var CHANNEL_DOWN = [428];
    var RED = [403, 10001];
    var GREEN = [404, 10002];
    var YELLOW = [405, 10003];
    var BLUE = [406, 10004];
    var ENTER = [13, 29443, 65385];

    if (BACK.indexOf(code) !== -1) {
      // BACK while watching ALWAYS exits the player (fixes "can't go back")
      close();
      return true;
    }
    if (PLAY_PAUSE.indexOf(code) !== -1 || PLAY.indexOf(code) !== -1 || PAUSE.indexOf(code) !== -1) {
      togglePlayPause();
      return true;
    }
    if (code === 37) { seekRelative(-10); return true; }
    if (code === 39) { seekRelative(10); return true; }
    if (code === 38) { bumpVolume(0.1); return true; }
    if (code === 40) { bumpVolume(-0.1); return true; }
    if (CHANNEL_UP.indexOf(code) !== -1 || RED.indexOf(code) !== -1) { playPreviousEpisode(); return true; }
    if (CHANNEL_DOWN.indexOf(code) !== -1 || GREEN.indexOf(code) !== -1) { playNextEpisode(); return true; }
    if (YELLOW.indexOf(code) !== -1) {
      failoverToNextRoute('Manual server switch…');
      return true;
    }
    if (BLUE.indexOf(code) !== -1) {
      if (elements.episodeDrawer && elements.episodeDrawer.classList.contains('active')) {
        closeEpisodesDrawer();
      } else {
        openEpisodesDrawer();
      }
      return true;
    }
    if (STOP.indexOf(code) !== -1) { close(); return true; }
    if (ENTER.indexOf(code) !== -1) {
      // Watch Now overlay: OK starts playback (user gesture unlocks fullscreen + autoplay)
      if (elements.watchNowOverlay && elements.watchNowOverlay.style.display === 'flex') {
        onWatchNowClick();
        return true;
      }
      var anyModal = document.querySelector('#tv-player-view .tv-modal.active');
      if (anyModal) return false; // modal scope handles it
      if (elements.video && elements.video.style.display !== 'none') {
        togglePlayPause();
      } else if (elements.embedIframe && elements.embedIframe.style.display !== 'none') {
        // Embed playing: OK clicks into the iframe to toggle playback
        if (window.TvAutoplay && window.TvAutoplay.simulateCenterClick) {
          window.TvAutoplay.simulateCenterClick(elements.embedIframe);
        }
      }
      return true; // always consumed while the player is open
    }
    return false;
  }
  // ── Open / Episode plumbing ────────────────────────────────────────────
  async function open(media, episodes, startIndex, directUrl, overrideCandidates) {
    isPlayerActive = true;
    raceToken++;
    currentMedia = media;
    isLiveStream = !!media.isLive;
    episodeList = (episodes && episodes.length > 0)
      ? episodes
      : [{ title: media.title, url: directUrl || media.detailPageUrl || '' }];
    currentEpisodeIndex = Math.max(0, Math.min(startIndex || 0, episodeList.length - 1));
    pendingOverrideCandidates = overrideCandidates || null;

    elements.container.classList.add('active');
    keyUnbind = SpatialNav.registerKeyHandler(handlePlayerRemoteKeys);

    // Blur whatever had focus (a hidden detail Play button would otherwise
    // swallow OK/Enter and restart the whole server walk)
    try { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); } catch (e) {}

    // Max volume + fullscreen from ANY entry point (tab-independent)
    try {
      if (elements.video) { elements.video.volume = 1.0; elements.video.muted = false; }
    } catch (e) {}
    try {
      var fsEl = document.fullscreenElement || document.webkitFullscreenElement;
      if (!fsEl && elements.container.requestFullscreen) {
        elements.container.requestFullscreen().catch(function () {});
      } else if (!fsEl && elements.container.webkitRequestFullscreen) {
        elements.container.webkitRequestFullscreen();
      }
    } catch (e) {}

    if (!previewLimits) {
      previewLimits = await NovaApi.fetchFreePreviewLimits().catch(function () { return null; });
    }

    playEpisode(currentEpisodeIndex, directUrl);
  }

  async function playEpisode(index, directUrl) {
    clearFailoverTimer();
    clearEmbedWatchdog();
    hideFatalOverlay();
    showLoading(true);
    showServerStatus('Preparing stream…');
    cancelBingeCountdown();

    currentEpisodeIndex = Math.max(0, Math.min(index, episodeList.length - 1));
    var ep = episodeList[currentEpisodeIndex] || {};
    resumePosition = 0;
    pendingEmbedResumeT = 0;

    // Resume prompt: Continue / Start over whenever saved progress exists
    if (!isLiveStream) {
      var saved = getSavedProgress(progressKey());
      if (saved && saved.position > 15 && saved.duration > 0 && saved.position < saved.duration - 20) {
        showLoading(false);
        var choice = await showResumeChoice(saved);
        if (choice === 'continue') {
          resumePosition = saved.position;
          pendingEmbedResumeT = Math.floor(saved.position);
        } else {
          clearSavedProgress(progressKey());
        }
        showLoading(true);
      }
    }

    // Pre-resolved candidates (football/WWE/live) skip route discovery
    var candidates = [];
    if (pendingOverrideCandidates && pendingOverrideCandidates.length) {
      candidates = pendingOverrideCandidates.slice();
      if (directUrl && !candidates.some(function (c) { return c.url === directUrl; })) {
        candidates.unshift({ provider: 'Direct Stream', url: directUrl, route: 'direct' });
      }
      pendingOverrideCandidates = null; // only applies to the first episode
    } else {
      if (directUrl) candidates.push({ provider: 'Direct Stream', url: directUrl, route: 'direct' });
      if (ep.streamUrl && !directUrl) candidates.push({ provider: 'Direct Stream', url: ep.streamUrl, route: 'direct' });

      var isAnimeLike = currentMedia && (currentMedia.mediaKind === 'anime' || currentMedia.mediaKind === 'donghua');

      // ANIME-SPECIFIC SERVERS FIRST (different from the movie/TV servers):
      // resolve this episode through the Consumet anime providers.
      if (isAnimeLike && currentMedia.title) {
        showServerStatus('Resolving anime servers…');
        try {
          var animeEps = await NovaApi.fetchAnimeEpisodesParallel(currentMedia.title);
          var wanted = parseFloat(ep.chapterNumber || (currentEpisodeIndex + 1)) || 1;
          var match = null;
          for (var ae = 0; ae < animeEps.length; ae++) {
            if (parseFloat(animeEps[ae].chapterNumber) === wanted) { match = animeEps[ae]; break; }
          }
          if (!match && animeEps.length > currentEpisodeIndex) match = animeEps[currentEpisodeIndex];
          if (match) {
            var animeUrl = await NovaApi.fetchAnimeStream(match.provider, match.url).catch(function () { return null; });
            if (animeUrl) {
              candidates.push({ provider: 'Anime Server (' + match.provider + ')', url: animeUrl, route: 'direct' });
            } else {
              candidates.push({ provider: 'Anime Server (' + match.provider + ')', url: match.url, route: 'embed' });
            }
          }
        } catch (e) { /* anime path is best-effort */ }
      }

      if (ep.provider && ep.url && String(ep.url).indexOf('consumet://') === 0) {
        showServerStatus('Resolving anime server…');
        var directAnime = await NovaApi.fetchAnimeStream(ep.provider, ep.url).catch(function () { return null; });
        if (directAnime) candidates.push({ provider: 'Anime Server (' + ep.provider + ')', url: directAnime, route: 'direct' });
      }

      if (currentMedia && !currentMedia.isLiveChannel) {
        showServerStatus('Searching servers…');
        var routes = await NovaApi.fetchWatchRoutes(
          currentMedia.mediaKind,
          currentMedia.title,
          currentMedia.detailPageUrl,
          ep.url || '',
          ep.chapterNumber
        ).catch(function () { return []; });
        routes.forEach(function (r) { candidates.push(r); });
      }
    }

    var seen = {};
    routeCandidates = candidates.filter(function (c) {
      if (!c || !c.url || seen[c.url]) return false;
      seen[c.url] = 1;
      return true;
    });

    if (routeCandidates.length === 0) {
      showLoading(false);
      showFatalOverlay('No stream servers were found for this title.');
      return;
    }

    startServerRace(routeCandidates);
  }

  // ── Parallel Server Race ───────────────────────────────────────────────
  function isDirectUrl(url) {
    return /\.(m3u8|mp4|mpd|webm|mkv|mov|ts)(\?|$)/i.test(String(url || ''));
  }

  // ── Parallel Server Search & Race ───────────────────────────────────────
  // Priority-aware sequential walk: tries Server 1 first, verifies it actually
  // plays, only advances to Server 2 if Server 1 demonstrably fails. This
  // prevents fast-loading but broken servers from winning the race.
  var walkIndex = 0;
  var embedLoaded = false;
  var embedActuallyPlaying = false;
  var currentEpoch = 0;  // set before loadEmbed so onload can verify it's not stale

  function clearWalkWatchdog() {
    clearTimeout(embedWatchdog);
    embedWatchdog = null;
    clearTimeout(embedPlayWatchdog);
    embedPlayWatchdog = null;
  }

  function clearProbeIframes() {
    probeIframes.forEach(function (ifr) {
      try { ifr.src = 'about:blank'; } catch (e) {}
      try { ifr.remove(); } catch (e) {}
    });
    probeIframes = [];
  }

  function decorateEmbedUrl(url) {
    var u = String(url || '');
    if (u.indexOf('vidlink.pro') !== -1) {
      u += (u.indexOf('?') !== -1 ? '&' : '?') + 'autoplay=true&primaryColor=e84d8a';
      if (pendingEmbedResumeT > 0) u += '&t=' + pendingEmbedResumeT;
    } else if (u.indexOf('vidsrc') !== -1 || u.indexOf('autoembed') !== -1 || u.indexOf('embed.su') !== -1 || u.indexOf('multiembed') !== -1) {
      u += (u.indexOf('?') !== -1 ? '&' : '?') + 'autoplay=1';
    } else if (u.indexOf('youtube.com/embed') !== -1) {
      u += (u.indexOf('?') !== -1 ? '&' : '?') + 'autoplay=1&playsinline=1';
    }
    return u;
  }

  function clearRaceVerifier() {
    if (raceState.verifierTimer) {
      clearTimeout(raceState.verifierTimer);
      raceState.verifierTimer = null;
    }
  }

  function startServerRace(candidates) {
    if (!candidates || candidates.length === 0) {
      showLoading(false);
      showFatalOverlay('No stream servers were found for this title.');
      return;
    }
    routeCandidates = candidates;
    clearWalkWatchdog();
    clearProbeIframes();
    clearRaceVerifier();
    raceToken++;
    raceState.candidates = candidates;
    raceState.currentIdx = 0;
    raceState.token = raceToken;
    raceState.verified = false;

    if (candidates.length === 1) {
      playWinningServer(candidates[0], 0);
      return;
    }

    showLoading(true);
    showServerStatus('Trying Server 1 of ' + candidates.length + '…');

    // Start with the highest-priority candidate
    tryCandidate(0);
  }

  /**
   * Try a candidate by index. Verifies playback before declaring success.
   * If the candidate fails verification, advances to the next one.
   */
  function tryCandidate(idx) {
    if (raceState.token !== raceToken) return; // stale
    if (idx >= raceState.candidates.length) {
      // All candidates exhausted
      clearRaceVerifier();
      clearProbeIframes();
      showLoading(false);
      showFatalOverlay('Tried all ' + raceState.candidates.length + ' servers with no luck.');
      return;
    }

    raceState.currentIdx = idx;
    walkIndex = idx;
    var cand = raceState.candidates[idx];
    activeCandidate = cand;
    embedLoaded = false;
    embedActuallyPlaying = false;
    raceState.epoch++;
    currentEpoch = raceState.epoch;

    showServerStatus('Trying Server ' + (idx + 1) + ' of ' + raceState.candidates.length + '…');

    if (isDirectUrl(cand.url)) {
      // Direct stream: probe the URL first (don't load yet)
      probeUrl(cand.url, function (ok) {
        if (raceState.token !== raceToken || raceState.verified) return;
        if (ok) {
          confirmCandidate();
        } else {
          tryCandidate(idx + 1);
        }
      });
    } else {
      // Embed: probe by loading iframe headlessly
      probeEmbed(cand.url, function (ok) {
        if (raceState.token !== raceToken || raceState.verified) return;
        if (ok) {
          confirmCandidate();
        } else {
          tryCandidate(idx + 1);
        }
      });
    }
  }

  /**
   * Probe a direct URL with a HEAD request to check if it's reachable.
   * Callback receives true if the URL is reachable, false otherwise.
   */
  function probeUrl(url, callback) {
    try {
      // Use fetch with HEAD method to check if URL is reachable
      fetch(url, { method: 'HEAD', mode: 'no-cors' })
        .then(function () { callback(true); })
        .catch(function () { callback(false); });
      // Timeout after 8 seconds
      setTimeout(function () { callback(false); }, 8000);
    } catch (e) {
      callback(false);
    }
  }

  /**
   * Probe an embed URL by loading it in a hidden iframe.
   * Callback receives true if the iframe loads, false otherwise.
   */
  function probeEmbed(url, callback) {
    var probed = false;
    var iframe = document.createElement('iframe');
    iframe.style.cssText = 'position:absolute;width:1px;height:1px;opacity:0;pointer-events:none;';
    iframe.src = url;
    iframe.onload = function () {
      if (probed) return;
      probed = true;
      callback(true);
    };
    iframe.onerror = function () {
      if (probed) return;
      probed = true;
      callback(false);
    };
    document.body.appendChild(iframe);
    // Timeout after 10 seconds
    setTimeout(function () {
      if (probed) return;
      probed = true;
      callback(false);
    }, 10000);
    // Cleanup
    setTimeout(function () {
      try { iframe.remove(); } catch (e) {}
    }, 12000);
  }

  /**
   * Confirm the current candidate as working. Clears all pending verifications.
   */
  function confirmCandidate() {
    if (raceState.token !== raceToken || raceState.verified) return;
    raceState.verified = true;
    clearRaceVerifier();
    clearWalkWatchdog();
    clearProbeIframes();
    var cand = raceState.candidates[raceState.currentIdx];
    showServerStatus('Playing via ' + cand.provider);
    setTimeout(function () { hideServerStatus(); }, 3000);
    
    // Show "Watch Now" overlay — user gesture unlocks fullscreen + autoplay
    // Don't load the actual stream yet — wait for the user to click Watch Now
    showWatchNowOverlay(cand, raceState.currentIdx);
  }

  /**
   * Advance to the next candidate in priority order.
   * Called by loadEmbed's watchdog when current server fails.
   */
  function advanceToNextCandidate(reason) {
    if (raceState.token !== raceToken || raceState.verified) return;
    var nextIdx = raceState.currentIdx + 1;
    if (nextIdx >= raceState.candidates.length) {
      clearRaceVerifier();
      clearProbeIframes();
      showLoading(false);
      showFatalOverlay('Tried all ' + raceState.candidates.length + ' servers with no luck.');
      return;
    }
    showToast(reason || 'Switching server…');
    tryCandidate(nextIdx);
  }

  function playWinningServer(candidate, index) {
    clearWalkWatchdog();
    clearProbeIframes();
    walkIndex = index;
    activeCandidate = candidate;
    embedLoaded = false;
    showServerStatus('Playing via ' + candidate.provider + ' (' + (index + 1) + ' of ' + routeCandidates.length + ')');

    // Show "Watch Now" overlay — user gesture unlocks fullscreen + autoplay
    // Don't load the actual stream yet — wait for the user to click Watch Now
    showWatchNowOverlay(candidate, index);

    setTimeout(function () {
      hideServerStatus();
    }, 4000);
  }

  /**
   * Show the "Watch Now" overlay when a stream resolves.
   * The user's click/OK is the user gesture that unlocks fullscreen + autoplay.
   */
  function showWatchNowOverlay(candidate, index) {
    if (!elements.watchNowOverlay) return;
    // Hide loading overlay so it doesn't compete with Watch Now
    showLoading(false);
    hideServerStatus();
    if (elements.watchNowTitle) {
      elements.watchNowTitle.textContent = (currentMedia && currentMedia.title) || 'Ready to Watch';
    }
    if (elements.watchNowServer) {
      elements.watchNowServer.textContent = 'Server ' + (index + 1) + ' of ' + routeCandidates.length + ' • ' + (candidate.provider || 'Unknown');
    }
    elements.watchNowOverlay.style.display = 'flex';
    // Trap focus inside the overlay so remote navigation can't escape
    try { SpatialNav.pushScope(elements.watchNowOverlay, elements.watchNowBtn); } catch (e) {}
    // Auto-focus the button so remote OK triggers it immediately
    if (elements.watchNowBtn) {
      elements.watchNowBtn.focus();
    }
  }

  function hideWatchNowOverlay() {
    if (elements.watchNowOverlay) {
      elements.watchNowOverlay.style.display = 'none';
    }
    // Pop the scope we pushed (if active)
    try { SpatialNav.popScope(); } catch (e) {}
  }

  /**
   * Called when the user clicks "Watch Now" or presses OK.
   * This user gesture unlocks fullscreen + unmuted autoplay.
   */
  function onWatchNowClick() {
    hideWatchNowOverlay();
    hideServerStatus();
    showLoading(true);

    // Enter fullscreen from this user gesture (don't let failure block playback)
    try {
      enterFullscreen();
    } catch (e) {}

    // Load the actual stream now (after the user gesture)
    if (activeCandidate) {
      if (isDirectUrl(activeCandidate.url)) {
        loadNative(activeCandidate.url);
      } else {
        loadEmbed(activeCandidate.url);
        // For embeds: click into the iframe AFTER it loads to start playback
        scheduleEmbedAutoplay();
      }
    }

    // Native autoplay after user gesture (for direct URLs)
    if (elements.video) {
      elements.video.volume = 1.0;
      elements.video.muted = false;
      var p = elements.video.play();
      if (p && p.catch) {
        p.catch(function () {
          elements.video.muted = true;
          elements.video.play().then(function () {
            setTimeout(function () {
              elements.video.muted = false;
              elements.video.volume = 1.0;
            }, 500);
          }).catch(function () {});
        });
      }
    }
  }

  /**
   * Schedule clicks into the embed iframe to trigger autoplay.
   * The iframe needs time to load its player, so we click multiple times
   * with delays to catch when it's ready.
   */
  function scheduleEmbedAutoplay() {
    var delays = [1500, 3000, 5000, 7000];
    for (var i = 0; i < delays.length; i++) {
      (function (delay) {
        setTimeout(function () {
          if (elements.embedIframe && elements.embedIframe.style.display !== 'none') {
            clickEmbedToPlay();
          }
        }, delay);
      })(delays[i]);
    }
  }

  function enterFullscreen() {
    // Native fullscreen
    var container = elements.container;
    if (!container) return;
    try {
      if (container.requestFullscreen) {
        container.requestFullscreen();
      } else if (container.webkitRequestFullscreen) {
        container.webkitRequestFullscreen();
      } else if (container.mozRequestFullScreen) {
        container.mozRequestFullScreen();
      }
    } catch (e) {
      // Fullscreen request failed — playback still works
    }
  }

  function forcePlayMaxVolume() {
    if (!elements.video) return;
    elements.video.muted = false;
    elements.video.volume = 1.0;
    var playPromise = elements.video.play();
    if (playPromise && playPromise.then) {
      playPromise.then(function () {
        elements.video.volume = 1.0;
      }).catch(function () {
        // Play blocked — try muted then unmute
        elements.video.muted = true;
        elements.video.play().then(function () {
          elements.video.muted = false;
          elements.video.volume = 1.0;
        }).catch(function () {});
      });
    }
  }

  function clickEmbedToPlay() {
    // Click into the center of the iframe to trigger embed playback
    if (!elements.embedIframe) return;
    try {
      var rect = elements.embedIframe.getBoundingClientRect();
      var x = rect.left + rect.width / 2;
      var y = rect.top + rect.height / 2;
      var clickEvent = new MouseEvent('click', {
        bubbles: true,
        cancelable: true,
        clientX: x,
        clientY: y
      });
      elements.embedIframe.dispatchEvent(clickEvent);
    } catch (e) {}
  }

  function advanceServer(reason) {
    // Use the new sequential race if active, otherwise fall back to old behavior
    if (raceState.candidates.length > 0 && raceState.token === raceToken) {
      advanceToNextCandidate(reason);
      return;
    }
    if (walkIndex + 1 >= routeCandidates.length) {
      showToast('Tried all ' + routeCandidates.length + ' servers');
      return;
    }
    showToast(reason || 'Switching server…');
    playWinningServer(routeCandidates[walkIndex + 1], walkIndex + 1);
  }

  function failoverToNextRoute(message) {
    advanceServer(message);
  }

  function clearFailoverTimer() {
    clearTimeout(failoverTimer);
    failoverTimer = null;
  }

  function clearEmbedWatchdog() {
    clearTimeout(embedWatchdog);
    embedWatchdog = null;
  }

  // ── Loading the winning stream with Max-Volume Autoplay ─────────────────
  function loadNative(url) {
    clearEmbedWatchdog();
    if (elements.embedIframe) {
      elements.embedIframe.style.display = 'none';
      try { elements.embedIframe.src = 'about:blank'; } catch (e) {}
    }
    var guard = document.getElementById('tv-embed-guard');
    if (guard) guard.style.display = 'none';

    // Use native video element
    if (!elements.video) return;
    elements.video.style.display = 'block';
    elements.video.volume = 1.0;
    elements.video.muted = false;

    var isHls = /\.m3u8(\?|$)/i.test(String(url));

    if (isHls && window.Hls && Hls.isSupported()) {
      // HLS via HLS.js
      if (hlsInstance) {
        try { hlsInstance.destroy(); } catch (e) {}
        hlsInstance = null;
      }
      hlsInstance = new Hls({
        enableWorker: true,
        lowLatencyMode: false,
        backBufferLength: 90,
        manifestLoadingTimeOut: 15000,
        manifestLoadingMaxRetry: 2,
        levelLoadingTimeOut: 15000,
        fragLoadingTimeOut: 25000
      });
      hlsInstance.loadSource(url);
      hlsInstance.attachMedia(elements.video);
      hlsInstance.on(Hls.Events.MANIFEST_PARSED, function () {
        elements.video.play().catch(function () {
          elements.video.muted = true;
          elements.video.play().catch(function () {});
        });
      });
      hlsInstance.on(Hls.Events.ERROR, function (evt, data) {
        if (data && data.fatal) {
          if (data.type === Hls.ErrorTypes.NETWORK_ERROR && (!data.details || data.details !== 'manifestLoadError')) {
            try { hlsInstance.startLoad(); } catch (e) {}
          } else {
            advanceServer('Stream error — switching server…');
          }
        }
      });
    } else {
      // Direct MP4/stream
      elements.video.src = url;
      elements.video.load();
      startNativePlayback();
    }
  }

  function startNativePlayback() {
    if (!elements.video) return;
    var video = elements.video;
    video.volume = 1.0;
    video.muted = false;
    video.play().catch(function () {
      video.muted = true;
      video.play().catch(function () {});
    });
  }

  function showUnmutePrompt() {
    var p = document.getElementById('tv-player-unmute-prompt');
    if (p) {
      p.style.display = 'flex';
      // Clicking the prompt is a real user gesture — unmute + play at max volume
      p.onclick = function () {
        if (elements.video) {
          elements.video.muted = false;
          elements.video.volume = 1.0;
          var playPromise = elements.video.play();
          if (playPromise && playPromise.catch) playPromise.catch(function () {});
        }
        hideUnmutePrompt();
        showToast('🔊 Volume max');
      };
    }
  }

  function hideUnmutePrompt() {
    var p = document.getElementById('tv-player-unmute-prompt');
    if (p) p.style.display = 'none';
  }

  function loadEmbed(url) {
    clearFailoverTimer();
    clearEmbedWatchdog();
    if (elements.video) {
      elements.video.style.display = 'none';
      try { elements.video.pause(); } catch (e) {}
    }
    if (hlsInstance) {
      try { hlsInstance.destroy(); } catch (e) {}
      hlsInstance = null;
    }
    if (!elements.embedIframe) return;

    embedLoaded = false;
    elements.embedIframe.style.display = 'block';
    elements.embedIframe.removeAttribute('sandbox');
    elements.embedIframe.setAttribute('allow', 'autoplay; fullscreen; encrypted-media; picture-in-picture');
    elements.embedIframe.setAttribute('allowfullscreen', 'true');
    elements.embedIframe.setAttribute('referrerpolicy', 'origin');
    elements.embedIframe.onload = function () {
      embedLoaded = true;
      embedActuallyPlaying = true;
      clearWalkWatchdog();
      showLoading(false);
      // Verify this onload is for the current candidate (not a stale iframe)
      if (currentEpoch !== raceState.epoch) return;
      // For cross-origin iframes we cannot detect internal playback, so
      // a successful load + autoplay clicks = assume it's working.
      // Confirm the candidate so the sequential walk doesn't advance.
      if (raceState.candidates.length > 0 && !raceState.verified) {
        confirmCandidate();
      }
      if (window.TvAutoplay && window.TvAutoplay.simulateCenterClick) {
        setTimeout(function () {
          window.TvAutoplay.simulateCenterClick(elements.embedIframe);
        }, 800);
        setTimeout(function () {
          window.TvAutoplay.simulateCenterClick(elements.embedIframe);
        }, 2200);
        setTimeout(function () {
          window.TvAutoplay.simulateCenterClick(elements.embedIframe);
        }, 4200);
      }
    };
    elements.embedIframe.onerror = function () {
      advanceToNextCandidate('Server unavailable — trying next…');
    };
    elements.embedIframe.src = decorateEmbedUrl(url);

    // Watchdog: if iframe fails to load within 15 seconds, advance to next
    embedWatchdog = setTimeout(function () {
      if (!embedLoaded && raceState.token === raceToken && !raceState.verified) {
        advanceToNextCandidate('Server response timeout — trying next…');
      }
    }, 15000);

    var guard = document.getElementById('tv-embed-guard');
    if (guard) {
      guard.style.display = 'block';
      // Guard stays visible to block ad popups, but pointer-events: none
      // lets mouse clicks pass through to the iframe's play button.
      // The window.open blocker in installAdShield() catches popup tabs.
      guard.style.pointerEvents = 'none';
    }

    showServerStatus('Playing via ' + (activeCandidate ? activeCandidate.provider : 'embed server') + ' — press YELLOW to switch');
    setTimeout(function () {
      if (embedLoaded) {
        hideServerStatus();
        showLoading(false);
      }
    }, 3500);
  }

  // ── Free Preview Gates (premium parity with the TV app) ────────────────
  function isPremiumUser() {
    var user = NovaApi.getUserSession ? NovaApi.getUserSession() : null;
    return !!(user && (user.isPremium || user.plan === 'premium'));
  }

  function graceKey() {
    return 'tv_preview_grace_' + (currentMedia ? (currentMedia.id || currentMedia.title) : 'x');
  }

  function graceAvailable() {
    try {
      var raw = localStorage.getItem(graceKey());
      if (!raw) return true;
      return (Date.now() - parseInt(raw, 10)) > 6 * 60 * 60 * 1000; // once per 6h
    } catch (e) { return true; }
  }

  function markGraceUsed() {
    try { localStorage.setItem(graceKey(), String(Date.now())); } catch (e) {}
  }

  function checkPreviewGate(cur, dur) {
    if (!isPlayerActive || isLiveStream || isPremiumUser() || !previewLimits) return;
    if (!elements.video || elements.video.style.display === 'none') return; // embeds: no timing signal
    if (dur <= 0 || cur <= 5) return;

    var episodic = episodeList.length > 1 || ['anime', 'tv', 'kdrama', 'cartoon', 'donghua', 'nigerian'].indexOf(String(currentMedia.mediaKind)) !== -1;
    var capSeconds = episodic
      ? Math.max(60, dur * (previewLimits.episodicFraction || 0.2))
      : (previewLimits.movieMs || 20 * 60 * 1000) / 1000;

    if (cur >= capSeconds) {
      if (graceAvailable()) {
        elements.video.pause();
        markGraceUsed();
        document.dispatchEvent(new CustomEvent('tvpremiumgate', {
          detail: {
            title: currentMedia.title,
            episodic: episodic,
            message: 'Free preview ended. ' + (episodic
              ? 'Free accounts watch the first ' + Math.round((previewLimits.episodicFraction || 0.2) * 100) + '% of every episode.'
              : 'Free accounts watch the first 20 minutes of every movie.') + ' Go Premium for unlimited streaming.'
          }
        }));
        // Give a short post-grace buffer then resume for testing
        setTimeout(function () {
          if (isPlayerActive && elements.video && elements.video.paused) {
            var p = elements.video.play();
            if (p && p.catch) p.catch(function () {});
          }
        }, 300);
      } else {
        elements.video.pause();
        document.dispatchEvent(new CustomEvent('tvpremiumgate', {
          detail: {
            title: currentMedia.title,
            episodic: episodic,
            hard: true,
            message: 'You have reached the free preview limit. Upgrade to Premium to keep watching without limits.'
          }
        }));
      }
    }
  }

  // ── Resume Choice (Continue / Start over) ──────────────────────────────
  function showResumeChoice(saved) {
    return new Promise(function (resolve) {
      if (!elements.resumeModal || !elements.continueButton || !elements.startOverButton) {
        resolve('continue');
        resumePosition = saved.position;
        return;
      }
      var mins = Math.floor(saved.position / 60);
      var totalMins = Math.floor((saved.duration || 0) / 60);
      if (elements.resumeCopy) {
        elements.resumeCopy.textContent = 'You were ' + mins + ' minute' + (mins === 1 ? '' : 's') +
          ' into this' + (totalMins ? ' (of ' + totalMins + ' min)' : '') + '. Continue from where you stopped, or start over?';
      }
      elements.resumeModal.classList.add('active');
      SpatialNav.pushScope(elements.resumeModal, elements.continueButton);

      function done(choice) {
        clearTimeout(autoT);
        elements.resumeModal.classList.remove('active');
        SpatialNav.popScope();
        elements.continueButton.removeEventListener('click', onContinue);
        elements.startOverButton.removeEventListener('click', onStartOver);
        resolve(choice);
      }
      function onContinue() { done('continue'); }
      function onStartOver() { done('startover'); }

      elements.continueButton.addEventListener('click', onContinue);
      elements.startOverButton.addEventListener('click', onStartOver);

      // Auto-continue after 12s so playback is never blocked for long
      var autoT = setTimeout(function () { done('continue'); }, 12000);
    });
  }

  // ── Binge Auto-Next ────────────────────────────────────────────────────
  function onVideoEnded() {
    if (currentEpisodeIndex < episodeList.length - 1) {
      startBingeCountdown();
    } else {
      close();
    }
  }

  function startBingeCountdown() {
    var count = 5;
    if (elements.bingeModal && elements.bingeCountdown) {
      elements.bingeCountdown.textContent = count;
      elements.bingeModal.classList.add('active');
      var cancelBtn = elements.bingeModal.querySelector('button');
      SpatialNav.pushScope(elements.bingeModal, cancelBtn);
      bingeCountdownTimer = setInterval(function () {
        count--;
        if (elements.bingeCountdown) elements.bingeCountdown.textContent = count;
        if (count <= 0) {
          clearInterval(bingeCountdownTimer);
          bingeCountdownTimer = null;
          playNextEpisode();
        }
      }, 1000);
    } else {
      playNextEpisode();
    }
  }

  function cancelBingeCountdown() {
    clearInterval(bingeCountdownTimer);
    bingeCountdownTimer = null;
    if (elements.bingeModal && elements.bingeModal.classList.contains('active')) {
      elements.bingeModal.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
    }
  }

  function playNextEpisode() {
    cancelBingeCountdown();
    if (currentEpisodeIndex < episodeList.length - 1) {
      playEpisode(currentEpisodeIndex + 1);
    }
  }

  function playPreviousEpisode() {
    cancelBingeCountdown();
    if (currentEpisodeIndex > 0) {
      playEpisode(currentEpisodeIndex - 1);
    }
  }

  // ── Playback controls & HUD ────────────────────────────────────────────
  function togglePlayPause() {
    if (!elements.video) return;
    if (elements.video.paused) {
      elements.video.muted = false;
      elements.video.volume = 1.0;
      elements.video.play().catch(function () {
        elements.video.muted = true;
        elements.video.play().catch(function () {});
      });
      showToast('▶ Play');
    } else {
      elements.video.pause();
      showToast('⏸ Paused');
    }
  }

  function seekRelative(deltaSeconds) {
    if (!elements.video || elements.video.style.display === 'none') return;
    var dur = elements.video.duration || 0;
    var newTime = Math.max(0, Math.min(dur, elements.video.currentTime + deltaSeconds));
    try { elements.video.currentTime = newTime; } catch (e) {}
    showToast((deltaSeconds > 0 ? '⏩ +' : '⏪ ') + deltaSeconds + 's');
  }

  function bumpVolume(delta) {
    if (!elements.video || elements.video.style.display === 'none') return;
    try {
      elements.video.muted = false;
      elements.video.volume = Math.max(0, Math.min(1, elements.video.volume + delta));
      showToast('🔊 ' + Math.round(elements.video.volume * 100) + '%');
    } catch (e) {}
  }

  function showToast(text) {
    if (!elements.seekFeedback) return;
    elements.seekFeedback.textContent = text;
    elements.seekFeedback.classList.add('active');
    clearTimeout(elements.seekFeedback._timer);
    elements.seekFeedback._timer = setTimeout(function () {
      elements.seekFeedback.classList.remove('active');
    }, 1200);
  }

  function openEpisodesDrawer() {
    if (!elements.episodeDrawer || !elements.episodeDrawerList) return;
    if (episodeList.length < 2) {
      showToast('No episode list for this title');
      return;
    }
    elements.episodeDrawerList.innerHTML = '';
    episodeList.forEach(function (ep, idx) {
      var btn = document.createElement('button');
      btn.className = 'tv-btn' + (idx === currentEpisodeIndex ? ' tv-btn-primary' : '');
      btn.tabIndex = 0;
      btn.setAttribute('data-nav', 'true');
      btn.style.margin = '4px 8px';
      var label = ep.title || ('Episode ' + (idx + 1));
      var saved = getSavedProgress(episodeProgressKey(currentMedia, ep, idx));
      if (saved && saved.position > 15 && saved.duration > 0 && saved.position < saved.duration - 20) {
        label = '▶ ' + label;
      }
      btn.textContent = label;
      btn.addEventListener('click', function () {
        closeEpisodesDrawer();
        playEpisode(idx);
      });
      elements.episodeDrawerList.appendChild(btn);
    });
    elements.episodeDrawer.classList.add('active');
    var target = elements.episodeDrawerList.querySelector('.tv-btn-primary') || elements.episodeDrawerList.querySelector('.tv-btn');
    SpatialNav.pushScope(elements.episodeDrawer, target);
  }

  function closeEpisodesDrawer() {
    if (elements.episodeDrawer && elements.episodeDrawer.classList.contains('active')) {
      elements.episodeDrawer.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
    }
  }

  function showLoading(show) {
    var loading = document.getElementById('tv-player-loading');
    if (loading) loading.style.display = show ? 'flex' : 'none';
  }

  function showServerStatus(text) {
    if (statusGuard(text)) return;
    if (elements.serverStatus && elements.serverStatusText) {
      elements.serverStatusText.textContent = text;
      elements.serverStatus.classList.add('active');
    }
  }

  var lastStatusText = '';
  function statusGuard(text) {
    if (lastStatusText === text) return false;
    lastStatusText = text;
    return false;
  }

  function hideServerStatus() {
    lastStatusText = '';
    if (elements.serverStatus) elements.serverStatus.classList.remove('active');
  }

  function showFatalOverlay(message) {
    hideServerStatus();
    if (elements.fatalOverlay) {
      var msg = elements.fatalOverlay.querySelector('.tv-fatal-message');
      if (msg) msg.textContent = message || 'Stream unavailable.';
      elements.fatalOverlay.classList.add('active');
      var retry = document.getElementById('tv-player-btn-retry');
      if (retry) SpatialNav.pushScope(elements.fatalOverlay, retry);
    }
  }

  function hideFatalOverlay() {
    if (elements.fatalOverlay && elements.fatalOverlay.classList.contains('active')) {
      elements.fatalOverlay.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
    }
  }

  // ── Close & cleanup ────────────────────────────────────────────────────
  function close() {
    isPlayerActive = false;
    raceToken++;                 // cancel any in-flight probes
    cancelBingeCountdown();
    closeEpisodesDrawer();
    hideFatalOverlay();
    hideServerStatus();
    hideWatchNowOverlay();

    if (elements.resumeModal && elements.resumeModal.classList.contains('active')) {
      elements.resumeModal.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
    }

    if (keyUnbind) {
      keyUnbind();
      keyUnbind = null;
    }

    // Save progress before teardown
    if (elements.video && !isLiveStream) {
      var cur = elements.video.currentTime;
      var dur = elements.video.duration;
      if (cur > 5 && dur > 0) saveProgress(cur, dur);
    }

    clearFailoverTimer();
    clearEmbedWatchdog();
    clearRaceVerifier();
    clearProbeIframes();
    raceState.verified = false;
    raceState.candidates = [];
    if (window.TvAutoplay) window.TvAutoplay.cancel();

    if (elements.video) {
      try { elements.video.pause(); } catch (e) {}
      try { elements.video.removeAttribute('src'); } catch (e) {}
      try { elements.video.load(); } catch (e) {}
      elements.video.style.display = 'none';
    }

    if (elements.embedIframe) {
      try { elements.embedIframe.src = 'about:blank'; } catch (e) {}
      elements.embedIframe.style.display = 'none';
    }

    // Hide the ad-click guard and leave fullscreen
    var guard = document.getElementById('tv-embed-guard');
    if (guard) guard.style.display = 'none';
    try {
      if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen();
      else if (document.webkitFullscreenElement && document.webkitExitFullscreen) document.webkitExitFullscreen();
    } catch (e) {}

    if (hlsInstance) {
      try { hlsInstance.destroy(); } catch (e) {}
      hlsInstance = null;
    }

    activeCandidate = null;
    resumePosition = 0;

    if (elements.container) {
      elements.container.classList.remove('active');
    }

    document.dispatchEvent(new CustomEvent('tvplayerclosed', { detail: { media: currentMedia } }));
  }

  return {
    init: init,
    open: open,
    close: close,
    playEpisode: playEpisode,
    playNextEpisode: playNextEpisode,
    playPreviousEpisode: playPreviousEpisode,
    cancelBingeCountdown: cancelBingeCountdown,
    isActive: function () { return isPlayerActive; }
  };
}));
