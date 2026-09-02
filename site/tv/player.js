/**
 * TV Video Player Engine for Hisense VIDAA Smart TVs
 * Features:
 * - Pure Clean Fullscreen Video Playback (No HUD clutter / no server buttons)
 * - Native HTML5 Video + HLS.js adaptive streaming
 * - Clean Embed fallback iframe with AdShield
 * - TV Remote Key Support:
 *   - OK / Space / MediaPlayPause: Toggle Play/Pause
 *   - LEFT / RIGHT: Seek -10s / +10s with fast scrub preview
 *   - DOWN: Open Episodes drawer (if multi-episode)
 *   - BACK / ESCAPE: Exit player & save progress
 * - Auto-next episode countdown (Binge player)
 * - Watch progress saving & resume in localStorage
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
  var routeCandidates = [];
  var routeIndex = 0;
  var embedFallbackTimer = null;
  var previewTimer = null;

  var elements = {
    container: null,
    video: null,
    embedIframe: null,
    seekFeedback: null,
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
      // Block popup window ads globally
      window.open = function(url) {
        console.log('[AdShield] Blocked popup window.open:', url);
        return null;
      };

      // Intercept and prevent clickjacking popup links
      window.addEventListener('click', function(e) {
        var target = e.target;
        if (target && target.tagName === 'A' && (target.target === '_blank' || target.getAttribute('target') === '_blank')) {
          if (!target.closest('#tv-app-root')) {
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
    elements.seekFeedback = document.getElementById('tv-player-seek-feedback');
    elements.episodeDrawer = document.getElementById('tv-player-episodes-drawer');
    elements.episodeDrawerList = document.getElementById('tv-player-episodes-list');
    elements.bingeModal = document.getElementById('tv-player-binge-modal');
    elements.bingeCountdown = document.getElementById('tv-player-binge-countdown');
    elements.resumeModal = document.getElementById('tv-player-resume-modal');
    elements.resumeCopy = document.getElementById('tv-player-resume-copy');
    elements.continueButton = document.getElementById('tv-player-btn-continue');
    elements.startOverButton = document.getElementById('tv-player-btn-start-over');

    setupVideoListeners();
  }

  function setupVideoListeners() {
    if (!elements.video) return;

    elements.video.addEventListener('timeupdate', onTimeUpdate);
    elements.video.addEventListener('ended', onVideoEnded);
    elements.video.addEventListener('error', onVideoError);
  }

  function onTimeUpdate() {
    if (!elements.video) return;
    var cur = elements.video.currentTime;
    var dur = elements.video.duration;

    if (dur > 0 && Math.floor(cur) % 5 === 0 && currentMedia) {
      saveProgress(cur, dur);
    }
  }

  function saveProgress(position, duration) {
    if (!currentMedia) return;
    try {
      var key = progressKey();
      localStorage.setItem(key, JSON.stringify({
        id: currentMedia.id,
        title: currentMedia.title,
        coverUrl: currentMedia.coverUrl,
        episodeTitle: episodeList[currentEpisodeIndex]?.title || `Episode ${currentEpisodeIndex + 1}`,
        episodeIndex: currentEpisodeIndex,
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

  function progressKey() {
    var episode = episodeList[currentEpisodeIndex] || {};
    return 'tv_progress_' + (currentMedia.id || currentMedia.title) + '__' + (episode.url || episode.title || currentEpisodeIndex);
  }

  function onVideoEnded() {
    if (currentEpisodeIndex < episodeList.length - 1) {
      startBingeCountdown();
    } else {
      close();
    }
  }

  function onVideoError() {
    console.warn('Native video error, attempting embed fallback route...');
    if (currentMedia) {
      var ep = episodeList[currentEpisodeIndex];
      playNextRoute('This server failed. Trying another server…');
    }
  }

  function startBingeCountdown() {
    var count = 5;
    if (elements.bingeModal && elements.bingeCountdown) {
      elements.bingeCountdown.textContent = count;
      elements.bingeModal.classList.add('active');
      SpatialNav.pushScope(elements.bingeModal, elements.bingeModal.querySelector('button'));

      bingeCountdownTimer = setInterval(() => {
        count--;
        if (elements.bingeCountdown) elements.bingeCountdown.textContent = count;
        if (count <= 0) {
          clearInterval(bingeCountdownTimer);
          playNextEpisode();
        }
      }, 1000);
    } else {
      playNextEpisode();
    }
  }

  function cancelBingeCountdown() {
    clearInterval(bingeCountdownTimer);
    if (elements.bingeModal) {
      elements.bingeModal.classList.remove('active');
      SpatialNav.popScope();
    }
  }

  function playNextEpisode() {
    cancelBingeCountdown();
    if (currentEpisodeIndex < episodeList.length - 1) {
      currentEpisodeIndex++;
      playEpisode(currentEpisodeIndex);
    }
  }

  function playPreviousEpisode() {
    cancelBingeCountdown();
    if (currentEpisodeIndex > 0) {
      currentEpisodeIndex--;
      playEpisode(currentEpisodeIndex);
    }
  }

  async function open(media, episodes = [], startIndex = 0, directUrl = null) {
    isPlayerActive = true;
    currentMedia = media;
    episodeList = episodes.length > 0 ? episodes : [{ title: media.title, url: directUrl || media.detailPageUrl }];
    currentEpisodeIndex = Math.max(0, Math.min(startIndex, episodeList.length - 1));

    elements.container.classList.add('active');

    // Register TV remote key handlers for Player
    keyUnbind = SpatialNav.registerKeyHandler(handlePlayerRemoteKeys);

    playEpisode(currentEpisodeIndex, directUrl);
    startPreviewGate();
  }

  function startPreviewGate() {
    clearTimeout(previewTimer);
    var user = NovaApi.getUserSession ? NovaApi.getUserSession() : null;
    if ((user && user.isPremium) || currentMedia.mediaKind === 'live') return;
    var isMovie = ['movie', 'movies'].includes(String(currentMedia.mediaKind || '').toLowerCase());
    var limitMs = isMovie ? 20 * 60 * 1000 : 5 * 60 * 1000;
    previewTimer = setTimeout(function () {
      if (!isPlayerActive) return;
      if (elements.video) elements.video.pause();
      if (elements.embedIframe) { elements.embedIframe.src = 'about:blank'; elements.embedIframe.style.display = 'none'; }
      showToast('Free preview ended');
      document.dispatchEvent(new CustomEvent('tvpremiumneeded'));
    }, limitMs);
  }

  async function playEpisode(index, directUrl = null) {
    currentEpisodeIndex = index;
    var ep = episodeList[index];

    var streamUrl = directUrl || ep.streamUrl;
    routeCandidates = [];
    routeIndex = 0;

    if (!streamUrl) {
      showLoading(true);
      if (currentMedia.isAnime && ep.url && !String(ep.url).startsWith('tmdb-')) {
        var animeResult = await Promise.all([
          NovaApi.fetchAnimeStream(ep.provider || 'hianime', ep.url),
          NovaApi.fetchWatchRoutes('anime', currentMedia.title, ep.url || currentMedia.detailPageUrl)
        ]);
        streamUrl = animeResult[0];
        routeCandidates = animeResult[1] || [];
      } else {
        routeCandidates = await NovaApi.fetchWatchRoutes(currentMedia.mediaKind, currentMedia.title, ep.url || currentMedia.detailPageUrl);
        streamUrl = routeCandidates[0] && routeCandidates[0].url;
      }
      // An anime provider can legitimately have an episode listing but no
      // stream. Fall back to the full parallel server list in that case.
      if (!streamUrl && currentMedia.isAnime) {
        streamUrl = routeCandidates[0] && routeCandidates[0].url;
      }
      showLoading(false);
    }

    if (!streamUrl) {
      showToast('Stream unavailable');
      return;
    }

    // A provider-specific direct anime URL is not part of the generic server
    // list. Start fallback at candidate zero if that direct stream errors.
    routeIndex = routeCandidates.length && streamUrl !== routeCandidates[0].url ? -1 : 0;
    loadStream(streamUrl, routeCandidates[routeIndex] || null);
  }

  function loadStream(url, route) {
    clearTimeout(embedFallbackTimer);
    var isHls = url.includes('.m3u8');
    var isEmbed = !isHls && (url.startsWith('http') && !url.includes('.mp4') && (url.includes('embed') || url.includes('player') || url.includes('vidsrc') || url.includes('vidlink') || url.includes('streamseast') || url.includes('animexin')));

    if (isEmbed) {
      // Use TV embed container without any sandbox restriction
      if (elements.video) {
        elements.video.style.display = 'none';
        elements.video.pause();
      }
      if (elements.embedIframe) {
        elements.embedIframe.style.display = 'block';
        elements.embedIframe.removeAttribute('sandbox');
        elements.embedIframe.setAttribute('allow', 'autoplay; fullscreen; encrypted-media; picture-in-picture');
        elements.embedIframe.setAttribute('allowfullscreen', 'true');
        elements.embedIframe.setAttribute('referrerpolicy', 'origin');

        // Add clean autoplay param
        var cleanUrl = url;
        if (cleanUrl.includes('vidlink.pro')) {
          cleanUrl = cleanUrl + (cleanUrl.includes('?') ? '&' : '?') + 'autoplay=true&primaryColor=e84d8a';
        } else if (cleanUrl.includes('vidsrc')) {
          cleanUrl = cleanUrl + (cleanUrl.includes('?') ? '&' : '?') + 'autoplay=1';
        }
        elements.embedIframe.src = cleanUrl;
        // iframe load means the provider page loaded, not that its internal
        // stream works.  Do not auto-switch a playing embed; expose a reliable
        // remote retry key instead and only fall through on an actual iframe
        // load error.
        elements.embedIframe.onerror = function () { playNextRoute('Server unavailable. Trying another server…'); };
      }
    } else {
      if (elements.embedIframe) {
        elements.embedIframe.style.display = 'none';
        elements.embedIframe.src = 'about:blank';
      }
      if (elements.video) {
        elements.video.style.display = 'block';
        elements.video.volume = 1.0;
        elements.video.muted = false;
        
        if (hlsInstance) {
          hlsInstance.destroy();
          hlsInstance = null;
        }

        if (isHls && window.Hls && Hls.isSupported()) {
          hlsInstance = new Hls({
            enableWorker: true,
            lowLatencyMode: true,
            backBufferLength: 90
          });
          hlsInstance.loadSource(url);
          hlsInstance.attachMedia(elements.video);
          hlsInstance.on(Hls.Events.MANIFEST_PARSED, function () {
            checkResumeAndPlay();
          });
        } else {
          elements.video.src = url;
          elements.video.load();
          checkResumeAndPlay();
        }
      }
    }
  }

  function checkResumeAndPlay() {
    if (!elements.video) return;
    elements.video.volume = 1.0;
    elements.video.muted = false;

    var saved = getSavedProgress(progressKey());
    if (saved && saved.position > 10 && saved.position < (saved.duration - 30)) {
      return showResumeChoice(saved);
    }

    startNativePlayback(0);
  }

  function startNativePlayback(position) {
    if (position > 0) elements.video.currentTime = position;

    var playPromise = elements.video.play();
    if (playPromise !== undefined) {
      playPromise.then(() => {
        elements.video.volume = 1.0;
        elements.video.muted = false;
      }).catch(e => {
        console.warn('Direct unmuted autoplay blocked by TV browser policy, attempting muted autostart then unmuting:', e);
        elements.video.muted = true;
        elements.video.play().then(() => {
          elements.video.muted = false;
          elements.video.volume = 1.0;
        }).catch(() => {});
      });
    }
  }

  function showResumeChoice(saved) {
    if (!elements.resumeModal) return startNativePlayback(saved.position);
    if (elements.resumeCopy) elements.resumeCopy.textContent = 'Resume ' + Math.floor(saved.position / 60) + ' minutes into ' + (saved.episodeTitle || currentMedia.title) + '?';
    elements.resumeModal.classList.add('active');
    var closeChoice = function (position) {
      elements.resumeModal.classList.remove('active');
      SpatialNav.popScope();
      startNativePlayback(position);
    };
    elements.continueButton.onclick = function () { closeChoice(saved.position); };
    elements.startOverButton.onclick = function () { closeChoice(0); };
    SpatialNav.pushScope(elements.resumeModal, elements.continueButton);
  }

  function playNextRoute(message) {
    if (!routeCandidates.length || routeIndex >= routeCandidates.length - 1) {
      showToast('All available servers failed');
      return;
    }
    routeIndex++;
    var nextName = routeCandidates[routeIndex].provider || ('Server ' + (routeIndex + 1));
    showToast(message || ('Trying ' + nextName));
    loadStream(routeCandidates[routeIndex].url, routeCandidates[routeIndex]);
  }

  function handlePlayerRemoteKeys(code, e) {
    if (!isPlayerActive) return false;

    // Back key -> close player
    if (SpatialNav.KEY_CODES.BACK.indexOf(code) !== -1) {
      if (elements.episodeDrawer && elements.episodeDrawer.classList.contains('active')) {
        closeEpisodesDrawer();
        return true;
      }
      close();
      return true;
    }

    // Toggle Play/Pause
    if (SpatialNav.KEY_CODES.ENTER.indexOf(code) !== -1 || SpatialNav.KEY_CODES.MEDIA_PLAY_PAUSE.indexOf(code) !== -1) {
      if (elements.video && elements.video.style.display !== 'none') {
        togglePlay();
        return true;
      }
    }

    // Seek Left (-10s) / Right (+10s)
    if (SpatialNav.KEY_CODES.LEFT.indexOf(code) !== -1 || SpatialNav.KEY_CODES.MEDIA_REWIND.indexOf(code) !== -1) {
      if (elements.video && elements.video.style.display !== 'none') {
        seekRelative(-10);
        return true;
      }
    }

    if (SpatialNav.KEY_CODES.RIGHT.indexOf(code) !== -1 || SpatialNav.KEY_CODES.MEDIA_FAST_FORWARD.indexOf(code) !== -1) {
      if (elements.video && elements.video.style.display !== 'none') {
        seekRelative(10);
        return true;
      }
    }

    // DOWN key: Open episode list if series
    if (SpatialNav.KEY_CODES.DOWN.indexOf(code) !== -1) {
      if (episodeList.length > 1) {
        openEpisodesDrawer();
        return true;
      }
    }

    // UP is a deliberate, always-available server retry. It does not disturb
    // a healthy embed, but gives users an immediate escape hatch when an
    // upstream provider reports a playback error inside its cross-origin UI.
    if (SpatialNav.KEY_CODES.UP.indexOf(code) !== -1 && routeCandidates.length > 1) {
      playNextRoute('Trying another server…');
      return true;
    }

    return false;
  }

  function togglePlay() {
    if (!elements.video) return;
    if (elements.video.paused) {
      elements.video.play();
      showToast('▶ Play');
    } else {
      elements.video.pause();
      showToast('⏸ Paused');
    }
  }

  function seekRelative(deltaSeconds) {
    if (!elements.video) return;
    var newTime = Math.max(0, Math.min(elements.video.duration || 0, elements.video.currentTime + deltaSeconds));
    elements.video.currentTime = newTime;
    showToast(`${deltaSeconds > 0 ? '⏩ +' : '⏪ '}${deltaSeconds}s`);
  }

  function showToast(text) {
    if (!elements.seekFeedback) return;
    elements.seekFeedback.textContent = text;
    elements.seekFeedback.classList.add('active');
    clearTimeout(elements.seekFeedback._timer);
    elements.seekFeedback._timer = setTimeout(() => {
      elements.seekFeedback.classList.remove('active');
    }, 1200);
  }

  function openEpisodesDrawer() {
    if (!elements.episodeDrawer || !elements.episodeDrawerList) return;
    elements.episodeDrawerList.innerHTML = '';
    episodeList.forEach((ep, idx) => {
      var btn = document.createElement('button');
      btn.className = 'tv-btn' + (idx === currentEpisodeIndex ? ' tv-btn-primary' : '');
      btn.tabIndex = 0;
      btn.setAttribute('data-nav', 'true');
      btn.style.margin = '4px 8px';
      btn.textContent = ep.title || `Ep ${idx + 1}`;
      btn.addEventListener('click', () => {
        closeEpisodesDrawer();
        playEpisode(idx);
      });
      elements.episodeDrawerList.appendChild(btn);
    });

    elements.episodeDrawer.classList.add('active');
    SpatialNav.pushScope(elements.episodeDrawer, elements.episodeDrawerList.querySelector('.tv-btn-primary') || elements.episodeDrawerList.querySelector('.tv-btn'));
  }

  function closeEpisodesDrawer() {
    if (elements.episodeDrawer) {
      elements.episodeDrawer.classList.remove('active');
      SpatialNav.popScope();
    }
  }

  function showLoading(show) {
    var loading = document.getElementById('tv-player-loading');
    if (loading) {
      loading.style.display = show ? 'flex' : 'none';
    }
  }

  function close() {
    isPlayerActive = false;
    cancelBingeCountdown();
    closeEpisodesDrawer();
    if (elements.resumeModal && elements.resumeModal.classList.contains('active')) {
      elements.resumeModal.classList.remove('active');
      SpatialNav.popScope();
    }

    if (keyUnbind) {
      keyUnbind();
      keyUnbind = null;
    }

    if (elements.video) {
      elements.video.pause();
      if (elements.video.currentTime > 5 && elements.video.duration > 0) {
        saveProgress(elements.video.currentTime, elements.video.duration);
      }
      elements.video.src = '';
    }

    clearTimeout(embedFallbackTimer);
    clearTimeout(previewTimer);

    if (elements.embedIframe) {
      elements.embedIframe.src = 'about:blank';
      elements.embedIframe.style.display = 'none';
    }

    if (hlsInstance) {
      hlsInstance.destroy();
      hlsInstance = null;
    }

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
    cancelBingeCountdown: cancelBingeCountdown
  };
}));
