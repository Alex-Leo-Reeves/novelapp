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
      // Click to toggle play/pause without exiting fullscreen
      elements.video.addEventListener('click', function (e) {
        e.preventDefault();
        e.stopPropagation();
        togglePlayPause();
      });
      elements.video.style.cursor = 'pointer';
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
    streamReady = true; // enables seeker (LEFT/RIGHT) once stream actually plays
    // Confirm the current candidate as working (sequential walk)
    if (raceState.candidates.length > 0 && !raceState.verified) {
      confirmCandidate();
    }
    // Max volume — but keep the current mute state: an auto-started muted
    // stream must stay silent until the user presses OK / clicks for sound.
    if (elements.video) {
      try {
        elements.video.volume = 1.0;
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
    var dur = getReliableDuration(elements.video);
    if (dur > 0 && Math.floor(cur) % 5 === 0 && currentMedia && !isLiveStream) {
      saveProgress(cur, dur);
    }
    checkPreviewGate(cur, dur);
  }

  // Some pirate HLS manifests report a wrong total duration (0, Infinity, or
  // absurd multi-day values). Fall back to the seekable range when the
  // reported duration is unusable so progress saving / resume never shows a
  // bogus "length" for the media.
  function getReliableDuration(video) {
    if (!video) return 0;
    var d = video.duration;
    if (typeof d === 'number' && isFinite(d) && d > 0 && d <= 172800) return d;
    try {
      var s = video.seekable;
      if (s && s.length) {
        var end = s.end(s.length - 1);
        if (typeof end === 'number' && isFinite(end) && end > 0 && end <= 172800) return end;
      }
    } catch (e) {}
    return 0;
  }

  function onVideoError() {
    if (!isPlayerActive || !currentMedia) return;
    if (elements.video.style.display !== 'none') {
      failoverToNextRoute('This stream failed — switching server…');
    }
  }

  function saveProgress(position, duration) {
    // Reject bogus values: NaN/Infinity, negative, or absurd lengths
    // (>48h) would corrupt the resume modal + Continue button later.
    if (!isFinite(position) || position <= 0) return;
    if (!isFinite(duration) || duration <= 0 || duration > 172800) return;
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
      // BACK only exits the player — does NOT close on mouse movement/touch
      userInitiatedExit = true;
      close();
      return true;
    }
    if (PLAY_PAUSE.indexOf(code) !== -1 || PLAY.indexOf(code) !== -1 || PAUSE.indexOf(code) !== -1) {
      togglePlayPause();
      return true;
    }
    if (code === 37) {
      // Seeker: only seek once the stream has actually resolved&started.
      // (Trying to seek before the stream fully resolves silently fails.)
      if (streamReady) { seekRelative(-10); } else { showToast('⏳ Loading stream — wait a moment'); }
      return true;
    }
    if (code === 39) {
      if (streamReady) { seekRelative(10); } else { showToast('⏳ Loading stream — wait a moment'); }
      return true;
    }
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
        hideMutedPreview();
        // OK on native video: muted fallback → restore sound, paused → play,
        // otherwise pause (cycle is: play → OK = paused → OK = play).
        if (elements.video.muted) {
          // Our muted autoplay fallback is playing silently — this press is
          // the user gesture that grants audio.
          elements.video.muted = false;
          elements.video.volume = 1.0;
          if (elements.video.paused) {
            elements.video.play().catch(function () {});
          }
          showToast('🔊 Sound on');
        } else if (elements.video.paused) {
          elements.video.muted = false;
          elements.video.volume = 1.0;
          elements.video.play().then(function () {
            showToast('▶ Play');
          }).catch(function () {
            elements.video.muted = true;
            elements.video.play().then(function () {
              setTimeout(function () {
                elements.video.muted = false;
                elements.video.volume = 1.0;
              }, 500);
            }).catch(function () {});
          });
        } else {
          elements.video.pause();
          showToast('⏸ Paused');
        }
        return true;
      } else if (elements.embedIframe && elements.embedIframe.style.display !== 'none') {
        // Embed playing: OK must NEVER reload the frame — a reload looks like
        // the whole server search restarting. Just re-send the play pokes and
        // point the user at YELLOW (next server) if this one won't start.
        hideMutedPreview();
        embedOkPressed = true;
        pokeEmbed(elements.embedIframe);
        refocusParent();
        showToast('▶ Play request sent — no luck? Press YELLOW to switch');
        return true;
      }
      return true; // always consumed while the player is open
    }
    return false;
  }
  // ── Open / Episode plumbing ────────────────────────────────────────────
  async function open(media, episodes, startIndex, directUrl, overrideCandidates) {
    isPlayerActive = true;
    playerOpenedAt = Date.now();
    raceToken++;
    currentMedia = media;
    resumeSeekHint = 0; // never leak a stale continue-hint into a new title
    isLiveStream = !!media.isLive;
    episodeList = (episodes && episodes.length > 0)
      ? episodes
      : [{ title: media.title, url: directUrl || media.detailPageUrl || '' }];
    currentEpisodeIndex = Math.max(0, Math.min(startIndex || 0, episodeList.length - 1));
    pendingOverrideCandidates = overrideCandidates || null;

    elements.container.classList.add('active');
    keyUnbind = SpatialNav.registerKeyHandler(handlePlayerRemoteKeys);

    // Back-trap: push ONE history entry BEFORE entering fullscreen (pushing
    // while fullscreened makes Firefox drop fullscreen). The TV/browser BACK
    // key then consumes this entry → popstate → onPopState → close().
    try {
      if (window.history && window.history.pushState && !backTrapPushed) {
        backTrapPushed = true;
        window.history.pushState({ tvPlayer: true }, '');
        window.addEventListener('popstate', onPopState);
      }
    } catch (e) {}

    // Blur whatever had focus (a hidden detail Play button would otherwise
    // swallow OK/Enter and restart the whole server walk), and install the
    // focus guard so the embed can never permanently steal the remote —
    // focus inside a cross-origin frame would swallow the BACK key.
    try { if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); } catch (e) {}
    installFocusGuard();
    refocusParent();

    // Detect when the user exits fullscreen via Esc/Browser BACK key
    document.addEventListener('fullscreenchange', onFullscreenChange);
    document.addEventListener('webkitfullscreenchange', onFullscreenChange);

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
    embedAutoStarted = false;
    startedUrl = null; // fresh episode → auto-start is allowed to run again
    embedOkPressed = false;

    // Explicit résumé requested (detail-page "▶ Continue" button): jump
    // straight to the saved position, no intermediate Continue/Start-over
    // modal. Clearing the hint means a regular Play still shows the modal.
    var skipResumeModal = resumeSeekHint > 0;
    if (resumeSeekHint > 0) {
      resumePosition = resumeSeekHint;
      pendingEmbedResumeT = Math.floor(resumeSeekHint);
      resumeSeekHint = 0;
    }

    // Resume prompt: Continue / Start over whenever saved progress exists
    if (!isLiveStream && !skipResumeModal) {
      var saved = getSavedProgress(progressKey());
      if (saved && saved.position > 15 && saved.duration > 0 && saved.duration <= 172800 &&
          saved.position < saved.duration - 20) {
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

    // Direct .m3u8/.mp4 streams play through OUR OWN <video> element (hls.js)
    // and genuinely autoplay — even muted-then-unmuted. Embed players belong to
    // other websites, and no parent-page code can force them to start. So when
    // a direct route exists, it must go first; embeds are the fallback.
    routeCandidates.sort(function (a, b) {
      return (isDirectUrl(a && a.url) ? 0 : 1) - (isDirectUrl(b && b.url) ? 0 : 1);
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
  var embedAutoStarted = false; // true when the auto-start path initiated loadEmbed
  var startedUrl = null;        // guards startWinningCandidate against double-starts
  var mutedPreviewTimer = null; // auto-hide timer for the muted-preview pill
  var embedOkPressed = false;   // user pressed OK on an embed at least once

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
    if (!u) return u;

    // Append params BEFORE any hash fragment so signed/hash-routed embed
    // URLs keep working.
    var hashIdx = u.lastIndexOf('#');
    var base = hashIdx > -1 ? u.slice(0, hashIdx) : u;
    var frag = hashIdx > -1 ? u.slice(hashIdx) : '';
    var hasQuery = base.indexOf('?') !== -1;
    var already = /[?&](autoplay|autostart|autoPlay|mute|muted)([=&]|$)/i.test(u);
    var hasAuto = /[?&](autoplay|autostart|autoPlay)=/i.test(base);
    var hasMute = /[?&](mute|muted)=/i.test(base);
    var lower = base.toLowerCase();

    // Vidlink-style providers take "=true", the rest take "=1".
    var vidlinkStyle = lower.indexOf('vidlink.') !== -1;
    var youtubeStyle = lower.indexOf('youtube.com/embed') !== -1;

    function append(params) {
      if (!params) return;
      // Don't duplicate params the provider already set.
      var parts = params.split('&');
      var add = [];
      for (var i = 0; i < parts.length; i++) {
        var key = parts[i].split('=')[0];
        if (!new RegExp('[?&]' + key + '([=&]|$)', 'i').test(base + '&')) add.push(parts[i]);
      }
      if (add.length) {
        base += (hasQuery ? '&' : '?') + add.join('&');
        hasQuery = true;
      }
    }

    // Autoplay and mute are INDEPENDENT knobs: a provider URL that already
    // carries autoplay=1 still needs the mute flag for cross-origin autoplay
    // to be permitted, and vice versa. Never skip one because the other exists.
    if (!hasAuto) {
      append(vidlinkStyle ? 'autoplay=true' : 'autoplay=1');
    }
    if (!hasMute) {
      if (vidlinkStyle) append('muted=true');
      else if (youtubeStyle) append('mute=1');
      else append('mute=1&muted=1');
    }
    if (vidlinkStyle) {
      append('primaryColor=e84d8a');
      if (pendingEmbedResumeT > 0) append('t=' + pendingEmbedResumeT);
    }
    if (youtubeStyle && !/[?&]playsinline=/i.test(base)) {
      append('playsinline=1');
    }
    return base + frag;
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
    embedAutoStarted = false;
    startedUrl = null; // retry/restart may re-run auto-start for the same URL
    embedOkPressed = false;

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
   * Probe an embed URL for reachability WITHOUT loading it in a hidden
   * iframe. Loading the provider page twice (hidden probe + visible player)
   * both wasted up to 10 seconds and could hijack the playback session, so
   * some embeds showed a poster and never started. A no-cors fetch resolves
   * as soon as the host answers and never runs the embed's player code.
   */
  function probeEmbed(url, callback) {
    var probed = false;
    var done = function (ok) {
      if (probed) return;
      probed = true;
      try { callback(ok); } catch (e) {}
    };
    if (typeof fetch !== 'function') { done(true); return; } // can't check → assume ok
    try {
      var ctl = (typeof AbortController === 'function') ? new AbortController() : null;
      var to = setTimeout(function () {
        if (ctl) { try { ctl.abort(); } catch (e) {} }
        done(true); // timeout ≠ unreachable → let the real player try
      }, 8000);
      fetch(url, ctl ? { mode: 'no-cors', cache: 'no-store', signal: ctl.signal } : { mode: 'no-cors', cache: 'no-store' })
        .then(function () { clearTimeout(to); done(true); })
        .catch(function () { clearTimeout(to); done(false); });
    } catch (e) {
      done(true); // fetch threw (old engine) → assume ok, real iframe decides
    }
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
    
    // Auto-start — no Watch Now gate. Native streams try unmuted then fall back
    // to muted; embeds start muted via URL params. One OK press restores sound.
    startWinningCandidate(cand, raceState.currentIdx);
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

    // Auto-start immediately — no Watch Now gate. Native streams try unmuted
    // then fall back to muted; embeds start muted via URL params. The
    // muted-preview pill tells the user that one OK press restores sound.
    startWinningCandidate(candidate, index);

    setTimeout(function () {
      hideServerStatus();
    }, 4000);
  }

  /**
   * Start the winning stream right away (replaces the "Watch Now" gate).
   *   - Direct URLs (.m3u8/.mp4)     → loadNative(): unmuted play attempt,
   *     muted fallback if the browser refuses audio without a fresh gesture.
   *   - Embed URLs (vidsrc/vidlink…) → loadEmbed(): muted autoplay via URL
   *     params (muted autoplay is allowed cross-origin) + center-click effort.
   * A non-blocking "muted preview — press OK for sound" pill is shown so the
   * TV remote still gets audio with exactly one OK press.
   */
  function startWinningCandidate(candidate, index) {
    if (!candidate || !candidate.url) return;
    var url = candidate.url;
    // Idempotence: a video 'playing' event / iframe onload can re-confirm the
    // same candidate; never double-load the same stream URL.
    if (startedUrl === url) return;
    startedUrl = url;

    showLoading(true);
    if (isDirectUrl(url)) {
      embedAutoStarted = false;
      loadNative(url);
    } else {
      embedAutoStarted = true;
      // Tell the user the stream starts silent and OK restores sound.
      showMutedPreview();
      loadEmbed(url);
    }
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
    // Idempotence guard: an OK keydown plus the button's synthetic click can
    // both fire within the same tick → only start the stream once.
    if (watchNowInFlight) return;
    watchNowInFlight = true;
    setTimeout(function () { watchNowInFlight = false; }, 2500);

    hideWatchNowOverlay();
    hideServerStatus();
    showLoading(true);
    // Enter fullscreen from this user gesture (no-op if already fullscreen)
    enterFullscreen();
    // Load the actual stream now (after the user gesture)
    if (activeCandidate) {
      if (isDirectUrl(activeCandidate.url)) {
        loadNative(activeCandidate.url);
      } else {
        loadEmbed(activeCandidate.url);
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
    // For embeds, click into the iframe to start playback
    if (elements.embedIframe && (!elements.video || !elements.video.src)) {
      clickEmbedToPlay();
    }
  }

  function enterFullscreen() {
    // Native fullscreen. Only request if we're NOT already fullscreen —
    // re-requesting while fullscreened makes Firefox drop fullscreen
    // (and pushes killed fullscreen too), which is exactly the
    // "tap Play and it comes out of fullscreen" bug.
    var container = elements.container;
    if (!container) return;
    var fsEl = document.fullscreenElement || document.webkitFullscreenElement;
    if (fsEl) return; // already fullscreen — leave it alone
    try {
      if (container.requestFullscreen) {
        container.requestFullscreen().catch(function () {});
      } else if (container.webkitRequestFullscreen) {
        container.webkitRequestFullscreen();
      } else if (container.mozRequestFullScreen) {
        container.mozRequestFullScreen();
      }
    } catch (e) {
      // Fullscreen request failed — playback still works
    }
  }

  // Fallback: browser/tv BACK consumed the pushState entry → the user pressed
  // back. Treat it as an explicit user exit so fullscreenchange won't fight it.
  function onPopState(e) {
    if (isPlayerActive) {
      userInitiatedExit = true;
      close();
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

  // Best-effort "start playing" pokes for the embed. postMessage to a
  // cross-origin window is always deliverable — providers that implement a
  // player API (e.g. vidlink) will act on it, everyone else ignores it.
  function pokeEmbed(emb) {
    if (!emb || isPlayerActive === false) return;
    try {
      var win = emb.contentWindow;
      if (win && win.postMessage) {
        win.postMessage({ type: 'plyr', method: 'play' }, '*');
        win.postMessage({ type: 'player', method: 'play' }, '*');
        win.postMessage({ method: 'play' }, '*');
        win.postMessage('play', '*');
      }
    } catch (e) {}
    if (window.TvAutoplay && window.TvAutoplay.simulateCenterClick) {
      window.TvAutoplay.simulateCenterClick(emb);
    }
  }

  // Keep keyboard focus on OUR document while the player is open. If focus
  // ever lands inside the cross-origin embed (its own scripts can steal it,
  // or a stray focus call), the TV remote's BACK key would be swallowed by
  // the embed and the player could never be exited. The guard gently pulls
  // focus back — user activation inside the frame survives focus changes, so
  // a desktop user's click on the embed's own play button still works.
  var focusGuardInstalled = false;
  function refocusParent() {
    try {
      if (document.activeElement &&
          document.activeElement !== document.body &&
          document.activeElement.blur) {
        document.activeElement.blur();
      }
    } catch (e) {}
    try {
      var c = elements.container;
      if (c) {
        if (!c.hasAttribute('tabindex')) c.setAttribute('tabindex', '-1');
        c.focus({ preventScroll: true });
      }
    } catch (e) {
      try { if (window.focus) window.focus(); } catch (e2) {}
    }
  }
  function installFocusGuard() {
    if (focusGuardInstalled) return;
    focusGuardInstalled = true;
    window.addEventListener('blur', function () {
      if (!isPlayerActive) return;
      setTimeout(function () {
        if (isPlayerActive && document.activeElement === elements.embedIframe) {
          refocusParent();
        }
      }, 60);
    });
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
    var p = video.play();
    if (p && p.catch) {
      p.catch(function () {
        // Unmuted blocked (no fresh user gesture by the time the stream
        // resolved) → start muted. Picture plays; OK restores sound.
        video.muted = true;
        var p2 = video.play();
        if (p2 && p2.catch) {
          p2.catch(function () {
            showUnmutePrompt();
          });
        } else if (p2 && p2.then) {
          p2.then(function () { showMutedPreview(); });
        } else {
          showMutedPreview();
        }
      });
    }
  }

  function showUnmutePrompt() {
    showLoading(false);
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

  // ── Muted-preview pill ─────────────────────────────────────────────────
  // Cross-origin embeds (and native streams whose unmuted autoplay was
  // rejected) start muted on purpose. The pill explains that one OK press
  // restores sound, and auto-hides after a few seconds so it never blocks
  // the picture.
  var pillDefaultHTML = null;
  function showMutedPreview(customText) {
    hideMutedPreview();
    var pill = document.getElementById('tv-player-muted-pill');
    if (!pill) return;
    if (!pillDefaultHTML) pillDefaultHTML = pill.innerHTML;
    if (customText) {
      pill.innerHTML = '<span style="font-size:24px;">▶</span> ' + customText
        .replace('OK', '<span class="tv-muted-pill-key">OK</span>');
    } else if (pillDefaultHTML) {
      pill.innerHTML = pillDefaultHTML;
    }
    pill.style.display = 'flex';
    mutedPreviewTimer = setTimeout(hideMutedPreview, 6000);
  }

  function hideMutedPreview() {
    if (mutedPreviewTimer) {
      clearTimeout(mutedPreviewTimer);
      mutedPreviewTimer = null;
    }
    var pill = document.getElementById('tv-player-muted-pill');
    if (pill) pill.style.display = 'none';
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
      streamReady = true; // embed resolved → enables the seeker
      clearWalkWatchdog();
      showLoading(false);
      // Verify this onload is for the current candidate (not a stale iframe)
      if (currentEpoch !== raceState.epoch) return;
      // For cross-origin iframes we cannot detect internal playback, so
      // a successful load + autoplay clicks = assume it's working.
      // Confirm the candidate so the sequential walk doesn't advance —
      // but NOT when this embed was auto-started (confirmCandidate already
      // ran / the single-candidate path already started it).
      if (!embedAutoStarted && raceState.candidates.length > 0 && !raceState.verified) {
        confirmCandidate();
      }
      if (window.TvAutoplay) {
        setTimeout(function () { pokeEmbed(elements.embedIframe); }, 800);
        setTimeout(function () { pokeEmbed(elements.embedIframe); }, 2200);
        setTimeout(function () { pokeEmbed(elements.embedIframe); }, 4200);
      }
      // If the embed still hasn't started on its own after 8s, tell the truth
      // and offer the only two things that can help: OK re-sends the play
      // request, YELLOW tries the next server. Never shown after user OK.
      clearTimeout(embedPlayWatchdog);
      embedPlayWatchdog = setTimeout(function () {
        if (isPlayerActive && embedLoaded && !embedOkPressed &&
            raceState.token === raceToken) {
          showMutedPreview('▶ Not playing? Press OK to start — or YELLOW to switch');
        }
      }, 8000);
      // Keep focus in the PARENT document. Focusing the cross-origin frame
      // would send every later remote key (BACK!) into the embed's document,
      // where the player could never see it — the player became unexitable.
      refocusParent();
    };
    elements.embedIframe.onerror = function () {
      advanceToNextCandidate('Server unavailable — trying next…');
    };
    elements.embedIframe.src = decorateEmbedUrl(url);
    // The frame must never own keyboard focus — refocus our document.
    refocusParent();

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
      // Only surface a total length when the saved duration is plausible —
      // corrupted/absurd durations would display a wrong media length.
      var totalMins = (saved.duration && saved.duration <= 172800) ? Math.floor(saved.duration / 60) : 0;
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
    if (!elements.video || elements.video.style.display === 'none') {
      // Embeds have no seekable <video> element — don't fake a seek.
      showToast('Seeking not available for this stream');
      return;
    }
    var dur = getReliableDuration(elements.video) || elements.video.duration || 0;
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

    // When the user exits fullscreen via Esc/Browser BACK, close the player.
  // Grace period: a tiny layout shift / mouse movement right after entering
  // fullscreen can fire fullscreenchange — don't kill the player for that.
  var playerOpenedAt = 0;
  // Set to true only once the stream has actually started playing — used by
  // the seeker so LEFT/RIGHT can't silently fail before the stream resolves.
  var streamReady = false;
  var userInitiatedExit = false; // true when BACK/Stop was pressed by the user
  var resumeSeekHint = 0; // set by detail "▶ Continue" → seek without the modal
  var backTrapPushed = false; // history state pushed once per player session
  var watchNowInFlight = false; // guards onWatchNowClick against double-fire

  // Called by the detail page's "▶ Continue" button: remembers the exact
  // position to skip to once the stream starts, bypassing the resume modal.
  function setResumeSeek(position) {
    resumeSeekHint = (typeof position === 'number' && isFinite(position) && position > 0) ? position : 0;
  }

  function onFullscreenChange() {
    if (!isPlayerActive) return;
    var isFs = !!document.fullscreenElement || !!document.webkitFullscreenElement;
    if (!isFs && !userInitiatedExit) {
      // NEVER auto-close on fullscreen exits: embed players (JWPlayer,
      // vidlink, etc.) switch their own screen modes mid-playback and that
      // caused the movie to close on any small movement. Only an explicit
      // BACK / Stop / Exit closes the player. If the container dropped out
      // of fullscreen unexpectedly, quietly re-enter it.
      try {
        var container = elements.container;
        var elapsed = Date.now() - playerOpenedAt;
        if (elapsed > 1500 && container && container.requestFullscreen && !document.fullscreenElement) {
          container.requestFullscreen().catch(function () {});
        }
      } catch (e) {}
    }
  }

  // ── Close & cleanup ────────────────────────────────────────────────────
  function close() {
  isPlayerActive = false;
  userInitiatedExit = false;
  streamReady = false;
  raceToken++;                 // cancel any in-flight probes
  cancelBingeCountdown();
  closeEpisodesDrawer();
  hideFatalOverlay();
  hideServerStatus();
  hideWatchNowOverlay();

  // Remove fullscreen change listener
  document.removeEventListener('fullscreenchange', onFullscreenChange);
  document.removeEventListener('webkitfullscreenchange', onFullscreenChange);
  // Remove back button trap (re-armed next time the player opens)
  window.removeEventListener('popstate', onPopState);
  backTrapPushed = false;

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
        var dur = getReliableDuration(elements.video);
        if (cur > 5 && dur > 0) saveProgress(cur, dur);
  }

  clearFailoverTimer();
  clearEmbedWatchdog();
  clearRaceVerifier();
  clearProbeIframes();
  raceState.verified = false;
  raceState.candidates = [];
  hideMutedPreview();
  embedAutoStarted = false;
  startedUrl = null;
  embedOkPressed = false;
  if (window.TvAutoplay) window.TvAutoplay.cancel();

  if (elements.video) {
        try { elements.video.pause(); } catch (e) {}
        try { elements.video.removeAttribute('src'); } catch (e) {}
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
    setResumeSeek: setResumeSeek,
    isActive: function () { return isPlayerActive; }
  };
}));
