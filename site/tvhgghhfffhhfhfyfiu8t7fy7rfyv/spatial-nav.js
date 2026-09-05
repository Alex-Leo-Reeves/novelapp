/**
 * Spatial Navigation Engine for Hisense VIDAA TV Remote Controls
 * 
 * Supports standard TV remote D-Pad navigation:
 * - Up (38), Down (40), Left (37), Right (39)
 * - OK / Enter (13)
 * - Back / Return (8, 27, 461, 10009, 10182)
 * - Media keys (Play 415/19, Pause 19, Play/Pause 179, Stop 413, Fast-Forward 417, Rewind 412)
 * - Color keys (Red 403, Green 404, Yellow 405, Blue 406)
 * - Numeric keys (48-57)
 */

(function (root, factory) {
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.SpatialNav = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  var KEY_CODES = {
    LEFT: [37, 214],
    UP: [38, 211],
    RIGHT: [39, 213],
    DOWN: [40, 212],
    ENTER: [13, 29443, 65385],
    BACK: [8, 27, 461, 10009, 10182, 88], // Hisense/LG/Samsung/standard Back
    PLAY: [415, 19, 250],
    PAUSE: [19],
    PLAY_PAUSE: [179, 10252],
    STOP: [413],
    FAST_FWD: [417],
    REWIND: [412],
    RED: [403, 10001],
    GREEN: [404, 10002],
    YELLOW: [405, 10003],
    BLUE: [406, 10004]
  };

  var state = {
    currentFocus: null,
    scopeStack: [],
    backHandlers: [],
    customKeyHandlers: [],
    enabled: true,
    throttleTime: 120, // ms between key repeats
    lastNavTime: 0
  };

  function isVisible(el) {
    if (!el) return false;
    var style = window.getComputedStyle(el);
    if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
      return false;
    }
    var rect = el.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0;
  }

  function getFocusableElements(container) {
    var root = container || getCurrentScope() || document.body;
    var candidates = root.querySelectorAll(
      'button:not([disabled]), [tabindex="0"], a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [data-nav="true"]'
    );
    var list = [];
    for (var i = 0; i < candidates.length; i++) {
      var item = candidates[i];
      if (isVisible(item) && !item.hasAttribute('disabled') && item.getAttribute('aria-hidden') !== 'true') {
        list.push(item);
      }
    }
    return list;
  }

  function getCurrentScope() {
    if (state.scopeStack.length > 0) {
      return state.scopeStack[state.scopeStack.length - 1];
    }
    return document.body;
  }

  function getElementRect(el) {
    var r = el.getBoundingClientRect();
    return {
      left: r.left,
      top: r.top,
      right: r.right,
      bottom: r.bottom,
      width: r.width,
      height: r.height,
      cx: r.left + r.width / 2,
      cy: r.top + r.height / 2
    };
  }

  // Calculate geometric distance in direction
  function calculateDistance(currentRect, targetRect, direction) {
    var dx = 0;
    var dy = 0;
    var primaryDist = 0;
    var secondaryDist = 0;

    switch (direction) {
      case 'left':
        if (targetRect.right > currentRect.left + 5) return Infinity; // Not strictly to the left
        primaryDist = currentRect.left - targetRect.right;
        secondaryDist = Math.abs(currentRect.cy - targetRect.cy);
        break;
      case 'right':
        if (targetRect.left < currentRect.right - 5) return Infinity; // Not strictly to the right
        primaryDist = targetRect.left - currentRect.right;
        secondaryDist = Math.abs(currentRect.cy - targetRect.cy);
        break;
      case 'up':
        if (targetRect.bottom > currentRect.top + 5) return Infinity; // Not strictly above
        primaryDist = currentRect.top - targetRect.bottom;
        secondaryDist = Math.abs(currentRect.cx - targetRect.cx);
        break;
      case 'down':
        if (targetRect.top < currentRect.bottom - 5) return Infinity; // Not strictly below
        primaryDist = targetRect.top - currentRect.bottom;
        secondaryDist = Math.abs(currentRect.cx - targetRect.cx);
        break;
      default:
        return Infinity;
    }

    // Weight primary distance more than cross-axis distance
    return primaryDist * primaryDist + (secondaryDist * secondaryDist * 2.5);
  }

  function findNextElement(direction) {
    var candidates = getFocusableElements();
    if (!candidates || candidates.length === 0) return null;

    if (!state.currentFocus || !document.body.contains(state.currentFocus) || !isVisible(state.currentFocus)) {
      return candidates[0];
    }

    var currentRect = getElementRect(state.currentFocus);
    var bestTarget = null;
    var bestDistance = Infinity;

    for (var i = 0; i < candidates.length; i++) {
      var candidate = candidates[i];
      if (candidate === state.currentFocus) continue;

      var targetRect = getElementRect(candidate);
      var dist = calculateDistance(currentRect, targetRect, direction);

      if (dist < bestDistance) {
        bestDistance = dist;
        bestTarget = candidate;
      }
    }

    // Fallback: If in horizontal list and going up/down or across rows, check parent container navigation hints
    if (!bestTarget && (direction === 'up' || direction === 'down')) {
      var container = state.currentFocus.closest('[data-nav-row]');
      if (container) {
        var allRows = Array.from(document.querySelectorAll('[data-nav-row]')).filter(isVisible);
        var curRowIdx = allRows.indexOf(container);
        if (curRowIdx !== -1) {
          var nextRow = direction === 'down' ? allRows[curRowIdx + 1] : allRows[curRowIdx - 1];
          if (nextRow) {
            var rowCandidates = getFocusableElements(nextRow);
            if (rowCandidates.length > 0) {
              // Pick closest in X axis
              var bestXDist = Infinity;
              var bestXCandidate = rowCandidates[0];
              for (var j = 0; j < rowCandidates.length; j++) {
                var cRect = getElementRect(rowCandidates[j]);
                var xDist = Math.abs(cRect.cx - currentRect.cx);
                if (xDist < bestXDist) {
                  bestXDist = xDist;
                  bestXCandidate = rowCandidates[j];
                }
              }
              return bestXCandidate;
            }
          }
        }
      }
    }

    return bestTarget;
  }

  function scrollIntoViewIfNeeded(el) {
    if (!el) return;
    
    // Check if element is inside a scroll container
    var scrollParent = el.closest('#tv-main-content, .tv-scroll-container, .tv-rail-content, .tv-vertical-scroll, .tv-screen, .tv-splash-view');
    if (scrollParent) {
      var parentRect = scrollParent.getBoundingClientRect();
      var elRect = el.getBoundingClientRect();

      // Horizontal scroll
      if (scrollParent.scrollWidth > scrollParent.clientWidth) {
        var scrollLeft = scrollParent.scrollLeft;
        var offsetLeft = el.offsetLeft - scrollParent.offsetLeft;
        var targetScrollX = offsetLeft - (parentRect.width / 2) + (elRect.width / 2);
        scrollParent.scrollTo({
          left: Math.max(0, targetScrollX),
          behavior: 'smooth'
        });
      }

      // Vertical scroll
      if (scrollParent.scrollHeight > scrollParent.clientHeight) {
        var scrollTop = scrollParent.scrollTop;
        var offsetTop = el.offsetTop - scrollParent.offsetTop;
        var targetScrollY = offsetTop - (parentRect.height / 2) + (elRect.height / 2);
        scrollParent.scrollTo({
          top: Math.max(0, targetScrollY),
          behavior: 'smooth'
        });
      }
    } else {
      el.scrollIntoView({ behavior: 'smooth', block: 'nearest', inline: 'nearest' });
    }
  }

  function focus(el, silent) {
    if (!el || !isVisible(el)) return false;

    if (state.currentFocus && state.currentFocus !== el) {
      state.currentFocus.classList.remove('tv-focused');
      if (typeof state.currentFocus.blur === 'function') {
        state.currentFocus.blur();
      }
      var prevEvent = new CustomEvent('tvblur', { bubbles: true, detail: { next: el } });
      state.currentFocus.dispatchEvent(prevEvent);
    }

    state.currentFocus = el;
    el.classList.add('tv-focused');
    if (typeof el.focus === 'function') {
      el.focus();
    }
    scrollIntoViewIfNeeded(el);

    if (!silent) {
      var focusEvent = new CustomEvent('tvfocus', { bubbles: true, detail: { element: el } });
      el.dispatchEvent(focusEvent);
    }
    return true;
  }

  function handleKeyDown(e) {
    if (!state.enabled) return;

    var code = e.keyCode || e.which;
    var now = Date.now();

    // Key match helper
    function isKey(keyGroup) {
      return KEY_CODES[keyGroup] && KEY_CODES[keyGroup].indexOf(code) !== -1;
    }

    // Run custom global handlers first (e.g., video player controls)
    for (var i = state.customKeyHandlers.length - 1; i >= 0; i--) {
      var handled = state.customKeyHandlers[i](code, e);
      if (handled) {
        e.preventDefault();
        e.stopPropagation();
        return;
      }
    }

    // Back button
    if (isKey('BACK')) {
      e.preventDefault();
      e.stopPropagation();
      triggerBack();
      return;
    }

    // Enter / OK button
    if (isKey('ENTER')) {
      if (state.currentFocus) {
        e.preventDefault();
        state.currentFocus.click();
        var enterEvent = new CustomEvent('tvselect', { bubbles: true, detail: { element: state.currentFocus } });
        state.currentFocus.dispatchEvent(enterEvent);
      }
      return;
    }

    // Throttle directional moves to prevent UI lag on smart TV processors
    if (now - state.lastNavTime < state.throttleTime) {
      return;
    }

    var direction = null;
    if (isKey('LEFT')) direction = 'left';
    else if (isKey('RIGHT')) direction = 'right';
    else if (isKey('UP')) direction = 'up';
    else if (isKey('DOWN')) direction = 'down';

    if (direction) {
      e.preventDefault();
      state.lastNavTime = now;
      var nextEl = findNextElement(direction);
      if (nextEl) {
        focus(nextEl);
      }
    }
  }

  function triggerBack() {
    if (state.backHandlers.length > 0) {
      var handler = state.backHandlers[state.backHandlers.length - 1];
      var handled = handler();
      if (handled !== false) {
        return true;
      }
    }
    // Default fallback: if in modal or subscreen, close it
    var activeModal = document.querySelector('.tv-modal.active');
    if (activeModal) {
      activeModal.classList.remove('active');
      popScope();
      return true;
    }
    return false;
  }

  function pushScope(scopeEl, initialFocusEl) {
    if (!scopeEl) return;
    state.scopeStack.push(scopeEl);
    var target = initialFocusEl || getFocusableElements(scopeEl)[0];
    if (target) {
      focus(target);
    }
  }

  function popScope(restoreFocusEl) {
    if (state.scopeStack.length > 0) {
      state.scopeStack.pop();
    }
    if (restoreFocusEl && isVisible(restoreFocusEl)) {
      focus(restoreFocusEl);
    } else {
      var currentElements = getFocusableElements();
      if (currentElements.length > 0) {
        focus(currentElements[0]);
      }
    }
  }

  function onBack(handler) {
    state.backHandlers.push(handler);
    return function unbind() {
      var idx = state.backHandlers.indexOf(handler);
      if (idx !== -1) state.backHandlers.splice(idx, 1);
    };
  }

  function registerKeyHandler(handler) {
    state.customKeyHandlers.push(handler);
    return function unbind() {
      var idx = state.customKeyHandlers.indexOf(handler);
      if (idx !== -1) state.customKeyHandlers.splice(idx, 1);
    };
  }

  function init(initialContainer) {
    window.removeEventListener('keydown', handleKeyDown, true);
    window.addEventListener('keydown', handleKeyDown, true);

    // Initial focus after DOM is ready
    setTimeout(function () {
      var elements = getFocusableElements(initialContainer);
      if (elements.length > 0) {
        focus(elements[0]);
      }
    }, 150);
  }

  return {
    init: init,
    focus: focus,
    getCurrentFocus: function () { return state.currentFocus; },
    findNextElement: findNextElement,
    pushScope: pushScope,
    popScope: popScope,
    onBack: onBack,
    triggerBack: triggerBack,
    registerKeyHandler: registerKeyHandler,
    enable: function () { state.enabled = true; },
    disable: function () { state.enabled = false; },
    KEY_CODES: KEY_CODES,
    getFocusableElements: getFocusableElements
  };
}));
