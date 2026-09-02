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
  var resumePosition = 0;
  var pendingEmbedResumeT = 0;
  var pendingOverrideCandidates = null;
  var previewLimits = null;
  var isLiveStream = false;

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
    startOverButton: null
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

    setupVideoListeners();

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
  }

  function setupVideoListeners() {
    if (!elements.video) return;
    elements.video.addEventListener('timeupdate', onTimeUpdate);
    elements.video.addEventListener('ended', onVideoEnded);
    elements.video.addEventListener('error', onVideoError);
    elements.video.addEventListener('playing', onVideoPlaying);
  }

  function onVideoPlaying() {
    clearFailoverTimer();
    clearEmbedWatchdog();
    hideServerStatus();
    showLoading(false);
    // Max volume — restore unmuted playback as soon as the TV allows it
    try {
      elements.video.volume = 1.0;
      elements.video.muted = false;
    } catch (e) {}
    if (resumePosition > 0 && Math.abs(elements.video.currentTime - resumePosition) > 2) {
      try { elements.video.currentTime = resumePosition; } catch (e) {}
      resumePosition = 0;
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
      var anyModal = document.querySelector('#tv-player-view .tv-modal.active');
      if (anyModal) return false; // modal scope handles it
      if (elements.video && elements.video.style.display !== 'none') {
        togglePlayPause();
      } else {
        // Embed playing: OK must NEVER re-trigger stale buttons / restart
        // the server walk — just acknowledge.
        showToast('Playing via ' + (activeCandidate ? activeCandidate.provider : 'embed'));
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
  var walkIndex = 0;
  var embedLoaded = false;

  function clearWalkWatchdog() {
    clearTimeout(embedWatchdog);
    embedWatchdog = null;
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

  function startServerRace(candidates) {
    if (!candidates || candidates.length === 0) {
      showLoading(false);
      showFatalOverlay('No stream servers were found for this title.');
      return;
    }
    routeCandidates = candidates;
    clearWalkWatchdog();
    clearProbeIframes();
    raceToken++;
    var currentToken = raceToken;

    if (candidates.length === 1) {
      playWinningServer(candidates[0], 0);
      return;
    }

    showLoading(true);
    showServerStatus('Searching ' + candidates.length + ' servers in parallel…');

    var hasWinner = false;
    var completedCount = 0;
    var totalCandidates = Math.min(candidates.length, 6);

    function declareWinner(candidate, index, reason) {
      if (hasWinner || currentToken !== raceToken) return;
      hasWinner = true;
      clearTimeout(raceTimeout);
      clearProbeIframes();
      console.log('[ParallelRace] Winner selected:', candidate.provider, reason);
      playWinningServer(candidate, index);
    }

    // Probe up to 6 candidate servers in parallel
    for (var i = 0; i < totalCandidates; i++) {
      (function (cand, idx) {
        if (isDirectUrl(cand.url)) {
          fetch(cand.url, { method: 'HEAD', mode: 'no-cors' })
            .then(function () {
              declareWinner(cand, idx, 'direct stream ready');
            })
            .catch(function () {
              declareWinner(cand, idx, 'direct stream priority');
            });
        } else {
          try {
            var ifr = document.createElement('iframe');
            ifr.style.width = '1px';
            ifr.style.height = '1px';
            ifr.style.opacity = '0.01';
            ifr.setAttribute('referrerpolicy', 'origin');

            var probeTimer = setTimeout(function () {
              checkAllProbes();
            }, 8000);

            ifr.onload = function () {
              clearTimeout(probeTimer);
              declareWinner(cand, idx, 'iframe loaded');
            };
            ifr.onerror = function () {
              clearTimeout(probeTimer);
              checkAllProbes();
            };

            ifr.src = decorateEmbedUrl(cand.url);
            if (elements.probeHost) elements.probeHost.appendChild(ifr);
            probeIframes.push(ifr);
          } catch (e) {
            checkAllProbes();
          }
        }
      })(candidates[i], i);
    }

    function checkAllProbes() {
      completedCount++;
      if (!hasWinner && completedCount >= totalCandidates) {
        declareWinner(candidates[0], 0, 'fallback to first priority');
      }
    }

    // Safety timeout: after 6 seconds, if no candidate has loaded first, pick priority server
    var raceTimeout = setTimeout(function () {
      if (!hasWinner && currentToken === raceToken) {
        declareWinner(candidates[0], 0, 'priority timeout');
      }
    }, 6000);
  }

  function playWinningServer(candidate, index) {
    clearWalkWatchdog();
    clearProbeIframes();
    walkIndex = index;
    activeCandidate = candidate;
    embedLoaded = false;
    showLoading(true);
    showServerStatus('Playing via ' + candidate.provider + ' (' + (index + 1) + ' of ' + routeCandidates.length + ')');

    if (isDirectUrl(candidate.url)) {
      loadNative(candidate.url);
    } else {
      loadEmbed(candidate.url);
    }

    setTimeout(function () {
      hideServerStatus();
    }, 4000);
  }

  function advanceServer(reason) {
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
    if (!elements.video) return;
    elements.video.style.display = 'block';
    elements.video.volume = 1.0;
    elements.video.muted = false;

    if (hlsInstance) {
      try { hlsInstance.destroy(); } catch (e) {}
      hlsInstance = null;
    }

    var isHls = /\.m3u8(\?|$)/i.test(String(url));
    if (isHls && window.Hls && Hls.isSupported()) {
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
        startNativePlayback();
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
      elements.video.src = url;
      elements.video.load();
      startNativePlayback();
    }
  }

  function startNativePlayback() {
    if (!elements.video) return;
    elements.video.volume = 1.0;
    elements.video.muted = false;
    var p = elements.video.play();
    if (p !== undefined) {
      p.then(function () {
        elements.video.volume = 1.0;
        elements.video.muted = false;
        hideUnmutePrompt();
        showToast('🔊 Volume max');
      }).catch(function () {
        // TV browser autoplay restriction: start muted to buffer immediately,
        // and display the prompt for remote OK to unmute to 100% volume.
        elements.video.muted = true;
        showUnmutePrompt();
        var unmute = function () {
          try {
            elements.video.muted = false;
            elements.video.volume = 1.0;
            hideUnmutePrompt();
            showToast('🔊 Volume max');
          } catch (e) {}
          elements.video.removeEventListener('playing', unmute);
        };
        elements.video.addEventListener('playing', unmute);
        elements.video.play().then(function () {
          setTimeout(unmute, 500);
          setTimeout(unmute, 1500);
        }).catch(function () {});
      });
    }
  }

  function showUnmutePrompt() {
    var p = document.getElementById('tv-player-unmute-prompt');
    if (p) p.style.display = 'flex';
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
      clearWalkWatchdog();
      showLoading(false);
      if (window.TvAutoplay && window.TvAutoplay.simulateCenterClick) {
        setTimeout(function () {
          window.TvAutoplay.simulateCenterClick(elements.embedIframe);
        }, 800);
        setTimeout(function () {
          window.TvAutoplay.simulateCenterClick(elements.embedIframe);
        }, 2200);
      }
    };
    elements.embedIframe.onerror = function () {
      advanceServer('Server unavailable — trying another…');
    };
    elements.embedIframe.src = decorateEmbedUrl(url);

    // Watchdog: only advance if the iframe completely fails to load after 28 seconds
    embedWatchdog = setTimeout(function () {
      if (!embedLoaded) {
        advanceServer('Server response timeout — switching…');
      }
    }, 28000);

    var guard = document.getElementById('tv-embed-guard');
    if (guard) {
      guard.style.display = 'block';
      guard.onclick = function () {
        if (window.TvAutoplay && window.TvAutoplay.simulateCenterClick) {
          window.TvAutoplay.simulateCenterClick(elements.embedIframe);
        }
        showToast('Remote OK controls playback');
      };
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
      var p = elements.video.play();
      if (p && p.catch) p.catch(function () {});
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

    if (elements.resumeModal && elements.resumeModal.classList.contains('active')) {
      elements.resumeModal.classList.remove('active');
      try { SpatialNav.popScope(); } catch (e) {}
    }

    if (keyUnbind) {
      keyUnbind();
      keyUnbind = null;
    }

    // Save progress before teardown (native only)
    if (elements.video && !isLiveStream) {
      var cur = elements.video.currentTime;
      var dur = elements.video.duration;
      if (cur > 5 && dur > 0) saveProgress(cur, dur);
    }

    clearFailoverTimer();
    clearEmbedWatchdog();
    clearProbeIframes();
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
