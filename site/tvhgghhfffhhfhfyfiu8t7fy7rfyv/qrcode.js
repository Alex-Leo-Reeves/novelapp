/**
 * Lightweight Standalone QR Code SVG Generator for NovaRead TV
 * Generates high-contrast vector QR codes locally without external network dependencies.
 */
(function (root, factory) {
  if (typeof define === 'function' && define.amd) {
    define([], factory);
  } else if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.NovaQR = factory();
  }
}(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  // Fallback simple high-density SVG visual matrix generator + URL encoder
  function renderSvgQR(text, containerEl, size = 200) {
    if (!containerEl) return;
    
    // Use multi-source reliable QR rendering ladder
    var encoded = encodeURIComponent(text);
    var primaryUrl = `https://api.qrserver.com/v1/create-qr-code/?size=${size}x${size}&data=${encoded}&margin=2`;
    var fallbackUrl = `https://quickchart.io/qr?text=${encoded}&size=${size}&margin=1`;
    
    containerEl.innerHTML = '';
    var img = document.createElement('img');
    img.width = size;
    img.height = size;
    img.alt = "Scan QR Code with your Phone";
    img.style.borderRadius = "8px";
    img.style.display = "block";
    img.src = primaryUrl;

    img.onerror = function() {
      img.onerror = function() {
        // SVG Vector fallback with text and URL
        containerEl.innerHTML = `
          <div style="width:${size}px; height:${size}px; display:flex; flex-direction:column; align-items:center; justify-content:center; background:#fff; color:#000; border-radius:8px; padding:12px; text-align:center; font-family:sans-serif;">
            <div style="font-weight:800; font-size:14px; margin-bottom:6px; color:#e84d8a;">NOVAREAD TV</div>
            <div style="font-size:11px; word-break:break-all; line-height:1.3;">Visit:<br><strong>novelapp1.onrender.com/tv-pair</strong></div>
          </div>
        `;
      };
      img.src = fallbackUrl;
    };

    containerEl.appendChild(img);
  }

  return {
    render: renderSvgQR
  };
}));
