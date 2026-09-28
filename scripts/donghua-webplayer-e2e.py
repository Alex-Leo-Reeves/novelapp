#!/usr/bin/env python3
"""
Real-browser E2E for the dedicated donghua server's web player contract.

Mirrors site/tvv/player.js: hls.js@1.5.7, the backend's PROXIED master playlist
and <track> elements built from each subtitle's proxyUrl (EN switched to
'showing' like showDefaultSubtitle()).

Pass criteria:
  1. video decodes and currentTime advances (no black screen / no stall)
  2. videoWidth/Height > 0 and a canvas frame sample is not uniformly black
  3. textTracks present, EN track 'showing' with cues
  4. no fatal hls.js error
"""
import asyncio
import json
import os
import shutil
import sys
import tempfile
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer

from playwright.async_api import async_playwright

PLAY_PATH = sys.argv[1] if len(sys.argv) > 1 else "/tmp/play4.json"
PAGE_DIR = os.environ.get("E2E_PAGE_DIR") or tempfile.mkdtemp(prefix="donghua-e2e-")
HTTP_PORT = int(os.environ.get("E2E_HTTP_PORT", "8899"))

with open(PLAY_PATH) as fh:
    play = json.load(fh)["data"]

MAIN_PROXY = play["streams"][0]["proxyUrl"]
MAIN_RAW = play["streams"][0]["url"]
SUBS = [s for s in play.get("subtitles", []) if s.get("proxyUrl") or s.get("url")]

HTML_HEAD = """<!doctype html>
<html>
<head><meta charset="utf-8"><title>donghua-player-e2e</title></head>
<body style="margin:0;background:#000">
<video id="v" playsinline muted crossorigin="anonymous"
       style="width:960px;height:540px"></video>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.7/dist/hls.min.js"></script>
<script>
window.__result = { state: 'init', fatal: null, attachError: null };
var video = document.getElementById('v');
var TRACKS = %(tracks)s;
var MASTER = %(master)s;
var hls = null;

function attachSubtitleTracks(tracks) {
  var added = 0, i, el, preferred = 0;
  for (i = 0; i < tracks.length; i++) {
    var probe = tracks[i] || {};
    if (/^en/i.test(String(probe.lang || '')) || /english/i.test(String(probe.label || ''))) {
      preferred = i; break;
    }
  }
  for (i = 0; i < tracks.length; i++) {
    var track = tracks[i] || {};
    var src = track.proxyUrl || track.url;
    if (!src) continue;
    el = document.createElement('track');
    el.kind = 'subtitles';
    el.label = track.label || track.lang || 'Subtitle';
    el.srclang = track.lang || 'und';
    el.src = src;
    el.setAttribute('data-source-sub', '1');
    if (i === preferred) el.setAttribute('default', 'default');
    video.appendChild(el);
    added++;
  }
  return added;
}

function showDefaultSubtitle() {
  var list = video.textTracks, picked = -1, i, lang, label;
  if (!list || !list.length) return -1;
  for (i = 0; i < list.length; i++) {
    lang = String(list[i].language || ''); label = String(list[i].label || '');
    if (/^en/i.test(lang) || /english/i.test(label)) { picked = i; break; }
  }
  if (picked === -1) picked = 0;
  for (i = 0; i < list.length; i++) {
    try { list[i].mode = (i === picked) ? 'showing' : 'disabled'; } catch (e) {}
  }
  return picked;
}
"""

HTML_TAIL = """
window.__cueState = function () {
  var list = video.textTracks, out = [];
  for (var i = 0; i < list.length; i++) {
    out.push({
      label: list[i].label, lang: list[i].language, mode: list[i].mode,
      cues: list[i].cues ? list[i].cues.length : 0
    });
  }
  return out;
};

window.__frameSample = function () {
  try {
    var c = document.createElement('canvas');
    c.width = 160; c.height = 90;
    var ctx = c.getContext('2d');
    ctx.drawImage(video, 0, 0, c.width, c.height);
    var data = ctx.getImageData(0, 0, c.width, c.height).data;
    var sum = 0, max = 0, nonBlack = 0;
    for (var i = 0; i < data.length; i += 4) {
      var lum = (data[i] * 299 + data[i + 1] * 587 + data[i + 2] * 114) / 1000;
      sum += lum; if (lum > max) max = lum;
      if (lum > 16) nonBlack++;
    }
    var px = data.length / 4;
    return { ok: true, avg: sum / px, max: max, nonBlackRatio: nonBlack / px };
  } catch (e) {
    return { ok: false, error: String(e) };
  }
};

try {
  window.__result.attached = attachSubtitleTracks(TRACKS);
} catch (e) {
  window.__result.attachError = String(e);
}

window.__subState = function () {
  var hlsSubs = [];
  try {
    if (hls && hls.subtitleTracks) {
      for (var i = 0; i < hls.subtitleTracks.length; i++) {
        var t = hls.subtitleTracks[i] || {};
        hlsSubs.push({ lang: t.lang || t.language || '', name: t.name || '' });
      }
    }
  } catch (e) {}
  return { hlsSubtitleTracks: hlsSubs, hlsSubtitleTrack: hls ? hls.subtitleTrack : null };
};

function selectManifestSubtitle() {
  try {
    if (!hls || !hls.subtitleTracks || !hls.subtitleTracks.length) return -1;
    if (video.querySelector('track[data-source-sub]')) return -1;
    var index = -1;
    for (var i = 0; i < hls.subtitleTracks.length; i++) {
      var t = hls.subtitleTracks[i] || {};
      var lang = String(t.lang || t.language || '');
      var name = String(t.name || '');
      if (/^en/i.test(lang) || /english/i.test(name)) { index = i; break; }
    }
    if (index === -1) index = 0;
    hls.subtitleTrack = index;
    return index;
  } catch (e) { return -1; }
}

if (window.Hls && Hls.isSupported()) {
  hls = new Hls({ enableWorker: true, lowLatencyMode: false, backBufferLength: 90, subtitleDisplay: true });
  hls.loadSource(MASTER);
  hls.attachMedia(video);
  hls.on(Hls.Events.MANIFEST_PARSED, function () {
    window.__result.state = 'manifest';
    showDefaultSubtitle();
    window.__result.manifestSubtitle = selectManifestSubtitle();
    video.play().catch(function (e) { window.__result.playError = String(e); });
  });
  hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, function () {
    if (hls.subtitleTrack === -1) window.__result.manifestSubtitle = selectManifestSubtitle();
  });
  hls.on(Hls.Events.ERROR, function (evt, data) {
    if (data && data.fatal) {
      window.__result.state = 'fatal';
      window.__result.fatal = data.type + '/' + data.details;
    }
  });
} else {
  video.src = MASTER;
  video.addEventListener('loadedmetadata', function () {
    window.__result.state = 'raw-manifest';
    showDefaultSubtitle();
    video.play().catch(function () {});
  });
}
</script>
</body></html>
"""

HTML = (HTML_HEAD + HTML_TAIL) % {
    "master": json.dumps(MAIN_PROXY),
    "tracks": json.dumps([
        {"url": t.get("url", ""), "proxyUrl": t.get("proxyUrl", ""),
         "label": t.get("label", ""), "lang": t.get("lang", "")}
        for t in SUBS
    ]),
}


def serve(directory):
    def factory(*args, **kwargs):
        return SimpleHTTPRequestHandler(*args, directory=directory, **kwargs)
    httpd = ThreadingHTTPServer(("127.0.0.1", HTTP_PORT), factory)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return httpd


def find_browser():
    for name in ("chromium", "chromium-browser", "google-chrome", "google-chrome-stable"):
        path = shutil.which(name)
        if path:
            return path
    return None



async def run_case(label, launch_kwargs, http_port):
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=True, **launch_kwargs)
        page = await browser.new_page(viewport={"width": 1280, "height": 720})
        console = []
        page.on("console", lambda msg: console.append(msg.text[:200]))
        page.on("pageerror", lambda err: console.append("pageerror: " + str(err)[:220]))
        await page.goto("http://127.0.0.1:%d/index.html" % http_port)

        codecs = await page.evaluate(
            "() => ({avc: MediaSource.isTypeSupported('video/mp4; codecs=\"avc1.640028\"'),"
            " hls: !!window.Hls, mse: window.MediaSource ? true : false})"
        )
        print("[%s] codecs=%s" % (label, codecs))
        if not codecs.get("avc"):
            await browser.close()
            return None, "no H.264 (proprietary codec) support"

        played = False
        state = {}
        started = time.time()
        while time.time() - started < 90:
            state = await page.evaluate(
                "() => ({ t: video.currentTime, rs: video.readyState,"
                " w: video.videoWidth, h: video.videoHeight,"
                " state: window.__result.state, fatal: window.__result.fatal,"
                " playError: window.__result.playError || null })"
            )
            if state.get("fatal"):
                break
            if state.get("t", 0) and state["t"] > 1.5 and state.get("w"):
                played = True
                break
            await asyncio.sleep(1.0)

        # Let the VTT land so cues become active around the first cue (3.2s).
        await asyncio.sleep(1.5)
        tracks = await page.evaluate("() => window.__cueState()")
        substate = await page.evaluate("() => window.__subState()")
        frame = await page.evaluate("() => window.__frameSample()")
        final = await page.evaluate(
            "() => ({ t: video.currentTime, w: video.videoWidth, h: video.videoHeight,"
            " state: window.__result.state, fatal: window.__result.fatal,"
            " attached: window.__result.attached,"
            " attachError: window.__result.attachError || null })"
        )
        showing = [t for t in tracks if t["mode"] == "showing"]

        print("[%s] played=%s video=%sx%s t=%.2f fatal=%s"
              % (label, played, final.get("w"), final.get("h"), final.get("t", 0), final.get("fatal")))
        print("[%s] frame=%s" % (label, frame))
        print("[%s] textTracks=%d showing=%s" % (label, len(tracks), showing))
        print("[%s] manifest subtitle state=%s" % (label, substate))
        print("[%s] attached=%s attachError=%s" % (label, final.get("attached"), final.get("attachError")))
        if console:
            print("[%s] console tail: %s" % (label, console[-6:]))

        ok = bool(
            played
            and frame.get("ok")
            and frame.get("nonBlackRatio", 0) > 0.02
            and showing
            and showing[0].get("cues", 0) > 0
        )
        await browser.close()
        return ok, ("PASS" if ok else "FAIL")


async def main():
    with open(os.path.join(PAGE_DIR, "index.html"), "w") as fh:
        fh.write(HTML)
    httpd = serve(PAGE_DIR)

    system = find_browser()
    attempts = []
    if system:
        attempts.append(("system:" + os.path.basename(system),
                         dict(executable_path=system,
                              args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])))
    attempts.append(("bundled",
                     dict(args=["--no-sandbox", "--autoplay-policy=no-user-gesture-required"])))

    last = "no browser attempt succeeded"
    for label, kwargs in attempts:
        try:
            ok, note = await run_case(label, kwargs, HTTP_PORT)
        except Exception as exc:  # noqa: BLE001
            print("[%s] launch/run error: %s" % (label, exc))
            last = "%s: %s" % (label, exc)
            continue
        if ok is None:
            last = "%s: %s" % (label, note)
            continue
        print("[%s] RESULT: %s" % (label, note))
        httpd.shutdown()
        return 0 if ok else 2

    print("E2E could not run: %s" % last)
    httpd.shutdown()
    return 3


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))

