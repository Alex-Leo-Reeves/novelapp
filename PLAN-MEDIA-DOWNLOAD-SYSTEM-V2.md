# PLAN — Media Download & Subscription System V2

**Date:** 2026-08-13
**Status:** IN PROGRESS
**Owner:** Principal Systems Architect
**Scope:** Multi-server selection + health check, hard quota gate (5 free per day / 20% preview / Pro unlimited), pause/resume/delete lifecycle UI, hierarchical grouped downloads, automatic English subtitle bundling, offline (local-bundle) playback routing.

---

## 1. Current State (recon summary)

Already built and reused by this plan:

| Capability | Where |
|---|---|
| Pause / Resume / Cancel / Remove (WAL crash-safe, chunk-level resume) | `DownloadEngine` + `MediaTaskRunner` (commonMain) |
| Chunked encrypted storage (AES-256-GCM), WB target internal/USB | `MediaCryptoPort`, `TvMediaStoragePort` |
| Range probe + range fetch transport | `MediaTransportPort`, `TvMediaTransportPort` |
| Hierarchical mobile Downloads screen (root → type → title → episodes) | `ui/DownloadsScreen.kt` |
| 20% episodic free-preview cap + 20 min movie cap in players | `TvPlayerScreen` (`TV_EPISODIC_FREE_FRACTION`), `TvEmbedPlayerScreen` |
| Server enums + TV server picker | `MaServerSource.kt` (`StreamServer`/`DonghuaServer`/`AnimeServer`), `TvDetailScreen` |
| Subscription status + preview config from backend | `AuthApi.billingStatus()` (`BillingStatus.freePreview`) |

Gaps this plan closes:

1. Free quota is **daily** (5/day in `DownloadIndex`); task requires **5 free downloads PER DAY** (UTC day, resets at midnight UTC), Pro = unlimited for 30 days.
2. No pre-flight **server health check** before enqueue; no dynamic server selection modal at download time.
3. TV Downloads screen is a **placeholder** ("Coming Soon") — no queue UI, no grouped media view, no local playback.
4. No **subtitle bundling** (`.srt` fetch + store + player init).
5. `MediaDownloadRequest`/`DownloadManifest` don't record the **server identity** or a **subtitle source**.
6. Completed encrypted bundles aren't routed to the local player (offline playback bypasses network).

---

## 2. Architecture

```
 Policy Gate (commonMain)                         UI (tvApp)
 ┌───────────────────────────┐        ┌───────────────────────────────────────┐
 │ MediaAccessPolicy         │        │ TvServerCheckModal                    │
 │  isDownloadAllowed()      │        │  list → probe → pick healthy server   │
 │  isStreamAllowed()        │        │ TvDownloadsScreen (REBUILD)           │
 │  previewFraction / caps   │        │  groups: media → episodes (accordion) │
 └───────────┬───────────────┘        │  active queue: pause/resume/delete     │
             │ intercepts             │  local playback: playBundle()          │
 ┌───────────▼───────────────┐        └───────────┬───────────────────────────┘
 │ DownloadEngine (EXISTING) │                    │ enqueue(server, subtitle)
 │  pause/resume/remove/WAL  │                    ▼
 └───────────┬───────────────┘   ┌────────────────────────────────────────────┐
             │                   │ MediaServerProbe (commonMain)              │
             │                   │  probe candidate URLs → health score       │
             │                   └────────────────────────────────────────────┘
             │                   ┌────────────────────────────────────────────┐
             └──────────────────▶│ SubtitleBundler (commonMain)               │
                                 │  fetch .srt → store <taskId>.srt           │
                                 │  manifest records subtitleBundlePath       │
                                 └────────────────────────────────────────────┘
                                 ┌────────────────────────────────────────────┐
                                 │ LocalBundlePlayer (tvApp)                  │
                                 │  CachedBundle → decrypt stream → LibVLC    │
                                 │  mp.addSlave(SUBTITLE, srt)                │
                                 └────────────────────────────────────────────┘
```

## 3. Implementation Phases

- [x] **P0** Recon remaining files (TvBingeSession, TvDetailScreen, TvMediaIndexer, Models, App.kt wiring, MEDIA_METADATA_EXT).
- [x] **P1** commonMain `MediaAccessPolicy` — 5 free downloads PER DAY (UTC window, `startOfEpochDayMs`), 20% stream cap, Pro bypass. (TV daily count derives from completed-bundle metadata sidecars `completedAtMs`; mobile keeps existing `DownloadIndex.dailyMediaDownloadCounts`.)
- [x] **P2** commonMain models — `serverId/serverName/subtitleUrl` on `MediaDownloadRequest` + `DownloadManifest`; `subtitleBundlePath` on metadata sidecar; `MEDIA_SUBTITLE_EXT`.
- [x] **P3** commonMain `MediaServerProbe` — score candidate server URLs via `MediaTransportPort.probe()` (reachable + range support + bytes/sec estimate).
- [x] **P4** commonMain `SubtitleBundler` — fetch `.srt`, write via port, record in manifest.
- [x] **P5** TV wiring — `TvMediaCacheController.enqueue(…, server, subtitleUrl)`; expose quota consumed. (P5a enqueue server/subtitle params + P5b `BundleDecryptingStream`, `TvBundleMediaOpener`, `completedDownloadsCount()` all DONE)
- [x] **P6** `TvServerCheckModal` — modal list of candidate servers, health-check spinner, fallback-to-next on dead server. (File was stranded at repo root as `tv`; relocated 2026-08-13 to `tvApp/.../tv/ui/components/TvServerCheckModal.kt`, byte-verified. Imports + `ServerRow` click-Surface pattern still to be validated in P10 compile pass.)
- [x] **P7** `TvDownloadsScreen` rebuild — grouped accordion (media → episodes), active queue pause/resume/delete, storage lookup, local play. (Implemented 2026-08-13: `TvDownloadsScreen` rebuilt with Active queue / On this TV / USB sections + quota banner. Local playback path added: `TvLoopbackMediaServer` (loopback HTTP Range server over decrypted bundle stream), `TvMediaCacheController.removeUsbBundle/playableUrlFor/stopPlayback`, `internalSourceFor/usbSourceFor` already existed.)
- [x] **P8** `TvApp` wiring — inject mediaCache into DOWNLOADS section; local play → bundle decoder → `TvPlayerScreen` with subtitle slave; quota gate before enqueue/stream. (Implemented 2026-08-13: TvHomeScreen threads mediaCache + local-play callbacks; TvApp routes internal/USB plays through `playableUrlFor` → `TvScreen.PLAYER` with `localSubtitlePath`; TvPlayerScreen attaches bundled `.srt` via `media.addSlave`; back-nav from local playback returns to DOWNLOADS; TvMainActivity.onStop calls `stopPlayback()`.)
- [x] **P9** Mobile parity — `DownloadsScreen` quota calls + server/subtitle passthrough (no UI rewrite; existing hierarchy already grouped). DONE 2026-08-13: `DownloadedEpisode` gained `serverId/serverName/subtitleUrl`; `AnimeDetailScreen` + `MediaDetailScreen` (episodes + movie) persist the selected server + subtitle source at save time; `DownloadsScreen` shows a quota banner (premium/remaining/exhausted states) + per-episode server label; `YouScreen` threads `isPremium`.
- [ ] **P10** Compile verify `:tvApp:compileDebugKotlin` + `:composeApp:compileKotlinDesktop`; update checkboxes. (Deferred per user — build skipped; P6–P9 code written but UNCOMPILED. Next run must verify.)

## 4. Quota Semantics

- Free user: **reset every day** at midnight UTC — 5 media downloads per UTC day. NMC (novel/manga/comic) unlimited.
- Free stream: 20% of an episode OR 20 min (movie), enforced in players (already present) — policy gate centralises the constant + exposes `freePreviewFraction`.
- Pro (active 30-day sub, `paidUntil` future): unlimited downloads + unlimited streaming.
- Re-download of the SAME taskId does not consume another quota slot (idempotent by taskId).

## 5. Non-Goals (v2)

- Server-side quota DB migration (local daily counters are authoritative for v2; billing server remains the premium-truth).
- HLS/DASH segment bundling; v2 downloads single-URL direct streams via range requests (existing engine).
- Multi-audio track selection; v2 bundles exactly one English `.srt` (or embeds internal track when server provides it).
