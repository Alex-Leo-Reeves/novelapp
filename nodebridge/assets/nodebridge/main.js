// nodejs-mobile embedded worker bridge for the Anivexa-API (13 providers).
//
// This runs INSIDE the app on the device's residential IP — exactly like the
// repo owner's site whose browser scrapes from the user's own network. The
// provider sites (anineko, animegg, anikoto, senshi, mkissa, ...) block
// datacenter egress (Render/Vercel -> streams=0) but allow residential IPs,
// which is why the same worker plays Dragon Ball from the browser but not
// from our backend.
//
// The bridge starts an HTTP server on 127.0.0.1 with an ephemeral port, writes
// { "port": N } to bridge-port.json next to this script (Kotlin reads it from
// filesDir), then forwards every request to the EXACT unmodified Anivexa
// worker (worker.fetch(Request) -> Response). The existing Android/TV
// AnivexaApi client only needs its base URL pointed at the loopback server —
// no provider code is reimplemented.
//
// NOTE: written without ?? / ?. on purpose — the editor formatter mangles
// those tokens into invalid JS.
import http from "node:http";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import anivexaWorker from "./anivexa/index.js";

const __dirname = path.dirname(fileURLToPath(
    import.meta.url));
const PORT_FILE = path.join(__dirname, "bridge-port.json");
const HOST = "127.0.0.1";

function toNumber(value) {
    const n = Number(value);
    return Number.isFinite(n) ? n : 0;
}

const server = http.createServer(function(req, res) {
            (async function() {
                try {
                    const reqUrl = req.url || "/";
                    const hostHeader = req.headers.host || (HOST + ":80");
                    const url = new URL(reqUrl, "http://" + hostHeader);

                    let body = null;
                    const chunks = [];
                    for await (const chunk of req) chunks.push(chunk);
                    if (chunks.length) body = Buffer.concat(chunks);

                    const headers = {};
                    for (let i = 0; i < req.rawHeaders.length; i += 2) {
                        const key = req.rawHeaders[i];
                        const value = req.rawHeaders[i + 1];
                        headers[key] = headers[key] ? headers[key] + ", " + value : value;
                    }

                    const init = {
                        method: req.method || "GET",
                        headers: headers,
                        duplex: "half"
                    };
                    if (body && body.length) init.body = new Uint8Array(body);

                    const request = new Request(url.toString(), init);
                    const response = await anivexaWorker.fetch(request, {});

                    const responseHeaders = {};
                    response.headers.forEach(function(value, key) {
                        responseHeaders[key] = value;
                    });

                    res.writeHead(response.status, responseHeaders);
                    const buf = Buffer.from(await response.arrayBuffer());
                    res.end(buf);
                } catch (err) {
                    const message = err && err.message ? err.message : String(err);
                    const stack = err && err.stack ? err.stack : null;
                    const payload = JSON.stringify({ error: message, stack: stack });
                    res.writeHead(500, {
                        "Content-Type": "application/json",
                        "Access-Control-Allow-Origin": "*"
                    });
                    res.end(payload);
                }
            })().catch(function(err) {
                        const message = err && err.message ? err.message : String(err);
                        res.writeHead(500, { "Content-Type": "application/json" });    res.end(JSON.stringify({ error: message }));
  });
});

server.listen(0, HOST, function () {
  const address = server.address();
  const port = address && typeof address === "object" ? toNumber(address.port) : 0;
  try {
    fs.writeFileSync(PORT_FILE, JSON.stringify({ port: port, host: HOST }));
  } catch (e) {
    console.error("[nodebridge] failed to write port file:", e && e.message);
  }
  console.log("[nodebridge] Anivexa worker listening on http://127.0.0.1:" + port);
});
