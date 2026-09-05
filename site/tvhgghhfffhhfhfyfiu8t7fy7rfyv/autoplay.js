/**
 * NovaRead TV — Autoplay & Max-Volume Engine for Hisense VIDAA Smart TVs
 * Safe, robust, non-blocking:
 *  - Maximizes HTML5 video volume to 1.0 (100%) natively without WebAudio CORS breakage
 *  - Dispatches center-click triggers to embed iframes upon user activation or resolution
 *  - Zero performance overhead, zero background observers
 */

(function (root, factory) {
  'use strict';
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    var instance = factory();
    root.TvAutoplay = instance;
    root.__tvAutoplay = instance;
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  function simulateCenterClick(element) {
    if (!element) return;
    try {
      var rect = element.getBoundingClientRect();
      if (rect.width === 0 || rect.height === 0) return;
      var cx = rect.left + rect.width / 2;
      var cy = rect.top + rect.height / 2;

      var evt = new MouseEvent('click', {
        bubbles: true,
        cancelable: true,
        view: window,
        clientX: cx,
        clientY: cy
      });
      element.dispatchEvent(evt);
    } catch (e) {}
  }

  function forceMaxVolume(videoEl) {
    if (!videoEl) return;
    try {
      videoEl.muted = false;
      videoEl.volume = 1.0;
    } catch (e) {}
  }

  return {
    simulateCenterClick: simulateCenterClick,
    forceMaxVolume: forceMaxVolume,
    cancel: function () {}
  };
}));
