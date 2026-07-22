#!/usr/bin/env node

/**
 * CinePro Core Keep-Alive Script
 * 
 * Pings CinePro Core every 5 minutes to prevent Render from spinning it down.
 * Render free-tier services spin down after 15 minutes of inactivity.
 * This script keeps both:
 *   1. CinePro Core (direct stream resolver) - cinepro-core-esmh.onrender.com
 *   2. Main NovelApp server (if deployed on Render) - novelapp1.onrender.com
 * 
 * Run as a cron job / GitHub Action every 5 minutes.
 * 
 * Usage:
 *   node scripts/cinepro-keepalive.js
 */

const https = require("https");
const http = require("http");

const TARGETS = [
    // Main CinePro Core (direct stream resolution)
    { url: "https://cinepro-core-esmh.onrender.com/health", label: "CinePro Core" },
    { url: "https://cinepro-core-esmh.onrender.com/v1/movies/550?platform=web", label: "CinePro Movies" },
    // Main NovelApp server (provides VidLink/CinePro routes and headless browser)
    { url: "https://novelapp1.onrender.com/health", label: "NovelApp Server" },
    // Optional: VidLink resolver if deployed separately
    // { url: "https://your-vidlink-resolver.com/health", label: "VidLink Resolver" },
];

const TIMEOUT_MS = 30000;

function ping(target) {
    return new Promise((resolve) => {
        const isHttps = target.url.startsWith("https");
        const lib = isHttps ? https : http;

        const req = lib.get(target.url, { timeout: TIMEOUT_MS }, (res) => {
            let body = "";
            res.on("data", (chunk) => { body += chunk; });
            res.on("end", () => {
                const status = res.statusCode;
                const ok = status >= 200 && status < 400;
                console.log(
                    `${new Date().toISOString()} [${ok ? "OK" : "WARN"}] ${target.label}: HTTP ${status}`
                );
                resolve(ok);
            });
        });

        req.on("error", (err) => {
            console.log(
                `${new Date().toISOString()} [FAIL] ${target.label}: ${err.message}`
            );
            resolve(false);
        });

        req.on("timeout", () => {
            req.destroy();
            console.log(
                `${new Date().toISOString()} [FAIL] ${target.label}: TIMEOUT (${TIMEOUT_MS}ms)`
            );
            resolve(false);
        });
    });
}

async function run() {
    console.log(`\n=== Keep-Alive Ping at ${new Date().toISOString()} ===`);
    console.log(`Targets: ${TARGETS.length} URL(s)\n`);

    const results = await Promise.all(TARGETS.map(ping));

    const ok = results.filter(Boolean).length;
    const total = results.length;
    console.log(`\nResult: ${ok}/${total} alive`);

    // Exit with error code if more than half are down
    if (ok < Math.ceil(total / 2)) {
        console.error("CRITICAL: Most targets are unreachable!");
        process.exit(1);
    }
}

run();