#!/usr/bin/env python3
"""
Real-browser E2E for the ASIAN dedicated servers (Chinese / Indian / Filipino).

Mirrors what the app does: ask the backend for a region's stream (the same
`/api/asian/play` call `data/AsianApi.kt` makes) and play the returned URL with
hls.js — the same manifest/segments/keys that ExoPlayer (Android), LibVLC
(Android TV) and AVPlayer (iOS) consume.

Pass criteria per region:
  1. the backend returned at least one probed-playable stream
  2. the playlist is valid HLS and video decodes
  3. videoWidth/Height > 0 and a canvas sample is not uniformly black
  4. currentTime advances (no stall, no black screen)

Usage:
  python3 scripts/asian-webplayer-e2e.py [base_url] [region ...]
  python3 scripts/asian-webplayer-e2e.py http://127.0.0.1:8792 chinese indian filipino
"""
import asyncio
import json
import os
import subprocess
import sys
import tempfile
import threading
import urllib.parse
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

from playwright.async_api import async_playwright

BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8792"
REGIONS = sys.argv[2:] if len(sys.argv) > 2 else ["chinese", "indian", "filipino"]

# One well-known title per region. tmdbId is what lets the backend look the
# title up under its regional release name (Dangal -> 摔跤吧！爸爸).
TITLES = {
    "chinese": ("The Wandering Earth", "535167"),
    "indian": ("RRR", "579974"),
    "filipino": ("Hello Love Goodbye", "593961"),
}

PAGE_DIR = tempfile.mkdtemp(prefix="asian-e2e-")
HTTP_PORT = int(os.environ.get("E2E_HTTP_PORT", "8898"))


def curl_json(url, timeout=240):
    out = subprocess.run(
        ["curl", "-sS", "--max-time", str(timeout), url],
        capture_output=True, text=True,
    ).stdout
    try:
        return json.loads(out)
    except Exception:
        return {"ok": False, "error": "bad json: " + out[:120]}


def request_play(region):
    title, tmdb_id = TITLES.get(region, ("", ""))
    url = (
        f"{BASE}/api/asian/play?title={urllib.parse.quote(title)}"
        f"&region={region}&tmdbId={tmdb_id}"
    )
    return curl_json(url)


HTML = """<!doctype html>
<html><head><meta charset="utf-8"><title>asian-e2e</title></head>
<body style="margin:0;background:#000">
<video id="v" playsinline muted crossorigin="anonymous" style="width:960px;height:540px"></video>
<canvas id="c" width="160" height="90" style="display:none"></canvas>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.7/dist/hls.min.js"></script>
<script>
window.__r = { state: 'init', fatal: null, error: null, levels: 0 };
var v = document.getElementById('v');
var url = %(url)s;
function sample() {
  var c = document.getElementById('c'), x = c.getContext('2d');
  try { x.drawImage(v, 0, 0, c.width, c.height); } catch (e) { return null; }
  var d = x.getImageData(0, 0, c.width, c.height).data, sum = 0, max = 0, nonBlack = 0;
  var n = d.length / 4;
  for (var i = 0; i < d.length; i += 4) {
    var l = (d[i] + d[i+1] + d[i+2]) / 3;
    sum += l; if (l > max) max = l; if (l > 12) nonBlack++;
  }
  return { avg: sum / n, max: max, nonBlackRatio: nonBlack / n };
}
if (window.Hls && Hls.isSupported()) {
  var hls = new Hls({ enableWorker: true, lowLatencyMode: false });
  window.__hls = hls;
  hls.on(Hls.Events.MANIFEST_PARSED, function (e, d) {
    window.__r.state = 'parsed';
    window.__r.levels = (d.levels || []).length;
    try { v.play(); } catch (err) {}
  });
  hls.on(Hls.Events.ERROR, function (e, d) {
    if (d && d.fatal) { window.__r.fatal = d.type + ':' + (d.details || ''); }
  });
  hls.loadSource(url);
  hls.attachMedia(v);
} else if (v.canPlayType('application/vnd.apple.mpegurl')) {
  v.src = url; v.play();
} else {
  window.__r.error = 'no hls support';
}
window.__probe = function () {
  return {
    state: window.__r.state, fatal: window.__r.fatal, error: window.__r.error,
    levels: window.__r.levels,
    t: v.currentTime, w: v.videoWidth, h: v.videoHeight,
    ready: v.readyState, paused: v.paused,
    buffered: (function () {
      try { return v.buffered.length ? v.buffered.end(v.buffered.length - 1) : 0; }
      catch (e) { return 0; }
    })(),
    frame: sample()
  };
};
</script></body></html>
"""


def serve(directory):
    class Handler(SimpleHTTPRequestHandler):
        def __init__(self, *a, **kw):
            super().__init__(*a, directory=directory, **kw)

        def log_message(self, *a):
            pass

    httpd = ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), Handler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


async def play(region, payload):
    streams = (payload.get("data") or {}).get("streams") or []
    if not streams:
        return {"region": region, "verdict": "NO_STREAM",
                "detail": payload.get("error") or "backend returned no stream"}
    target = streams[0]
    url = target.get("proxyUrl") or target.get("url")

    with open(os.path.join(PAGE_DIR, "index.html"), "w") as fh:
        fh.write(HTML % {"url": json.dumps(url)})

    async with async_playwright() as p:
        browser = await p.chromium.launch(
            headless=True,
            args=["--autoplay-policy=no-user-gesture-required", "--mute-audio"],
        )
        page = await browser.new_page(viewport={"width": 1000, "height": 600})
        await page.goto(f"http://127.0.0.1:{HTTP_PORT}/index.html",
                        wait_until="domcontentloaded", timeout=45000)
        probe = {}
        best = None
        # Sample several times: a single instant can land on a genuinely dark
        # scene in the film, which must not be misread as a black screen.
        for _ in range(14):
            await page.wait_for_timeout(3000)
            try:
                probe = await page.evaluate("() => window.__probe()")
            except Exception:
                probe = {}
            frame = probe.get("frame") or {}
            ratio = frame.get("nonBlackRatio") or 0
            if best is None or ratio > best:
                best = ratio
            if probe.get("t", 0) > 1.0 and ratio > 0.05:
                # Playing AND showing real picture — stop early.
                break
            try:
                await page.evaluate("() => document.getElementById('v').play()")
            except Exception:
                pass
        await browser.close()

    t = probe.get("t", 0) or 0
    w = probe.get("w", 0) or 0
    frame = probe.get("frame") or {}
    # Pass = decoded, advancing, and at least one sample showed a real picture.
    has_picture = (best or 0) > 0.05
    plays = t > 1.0 and w > 0 and not probe.get("fatal") and has_picture
    return {
        "region": region,
        "verdict": "PASS" if plays else ("FATAL" if probe.get("fatal") else "NO_PROGRESS"),
        "matched": (payload.get("data") or {}).get("matchedTitle"),
        "host": target.get("host") or url.split("/")[2],
        "quality": target.get("quality"),
        "mirrors": len(streams),
        "t": round(t, 2),
        "size": f"{w}x{probe.get('h', 0)}",
        "levels": probe.get("levels"),
        "fatal": probe.get("fatal"),
        "bestNonBlackRatio": round(best or 0, 3),
        "frame": {k: (round(v, 3) if isinstance(v, float) else v) for k, v in frame.items()},
    }


async def main():
    httpd = serve(PAGE_DIR)
    results = []
    print(f"backend: {BASE}")
    for region in REGIONS:
        payload = request_play(region)
        info = await play(region, payload)
        results.append(info)
        print(json.dumps(info, ensure_ascii=False), flush=True)
    httpd.shutdown()

    passed = sum(1 for r in results if r["verdict"] == "PASS")
    print()
    for r in results:
        print(f"  {r['region']:9} {r['verdict']:12} {r.get('matched') or '-'} "
              f"({r.get('size') or '-'}) t={r.get('t')}")
    print(f"RESULT: {passed}/{len(results)} regions play")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
