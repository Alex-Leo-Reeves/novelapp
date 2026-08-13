# PLAN — Offline Media Caching & Playback System V1

**Date:** 2026-08-13
**Status:** IN PROGRESS
**Owner:** Principal Systems Architect
**Scope:** Cross-platform (Android, iOS, Windows/EXE, Smart TV) download engine, encrypted at-rest cache, TV USB dual-target storage + indexing, unified player abstraction.

---

## 1. Objective

Replace the current ad-hoc local-download path with an enterprise-grade, reactive, state-machine-driven media caching system that:

1. Downloads media as **encrypted, pre-allocated chunks** with pause/resume/retry/background semantics.
2. Adapts to **network-type switching** (Wi-Fi ↔ Cellular) via a policy controller.
3. Keeps the UI thread at 0% download I/O (all chunk work off-main-thread, backpressured channels).
4. Binds ciphertext to the device (AES-256-GCM, keys in platform Keystore/Keychain).
5. On Smart TV: supports **internal vs USB (FAT32/exFAT/NTFS) targets**, background USB **scan/index/verify** without janking the UI, and **zero-latency** playback straight off the mounted volume.
6. Exposes a **Unified Media Source** abstraction so playback is identical whether the bytes come from encrypted local cache, a USB stream, or the network.

---

## 2. System Architecture

```
┌───────────────────────────────────────────────────────────────┐
│                  UI Layer  (Compose Multiplatform)             │
│   DownloadsScreen (mobile/desktop) · TvDownloadsScreen (TV)   │
│   observe: Flow<DownloadTask>  ·  commands: enqueue/pause/…   │
└────────────────────────────┬──────────────────────────────────┘
                             │
┌────────────────────────────▼──────────────────────────────────┐
│                  DownloadEngine  (commonMain, pure Kotlin)    │
│  ┌──────────────┐  ┌────────────────┐  ┌───────────────────┐  │
│  │ TaskQueue    │  │ TaskStateMachine│  │ ChunkScheduler    │  │
│  │ priority+    │  │ (per-task FSM) │  │ bounded parallelism│  │
│  │ coalescing   │  │ Queued→Fetching│  │ token-bucket       │  │
│  │              │  │ →Writing→…     │  │ throttling, retry  │  │
│  └──────────────┘  └────────┬───────┘  └─────────┬─────────┘  │
│                             │                    │            │
│  ┌──────────────────────────▼────────────────────▼──────────┐ │
│  │              ChunkTransportPort  (interface)             │ │
│  │  HEAD probe · Range GET · multi-slot chunk writer        │ │
│  └──────────────────────────┬───────────────────────────────┘ │
└─────────────────────────────┼─────────────────────────────────┘
          platform actuals:   │
┌─────────────────────────────▼─────────────────────────────────┐
│  Transports: Android(OkHttp) · iOS(NSURLSession) · Desktop    │
│  (java.net.http)                                             │
│  NetworkPolicyController (Wi-Fi/Cellular gating)              │
└─────────────────────────────┬─────────────────────────────────┘
                              │ ciphertext + manifest
┌─────────────────────────────▼─────────────────────────────────┐
│              EncryptedChunkStore  (crypto port)               │
│   AES-256-GCM per 4 MiB chunk · key in Keystore/Keychain      │
│   crash-safe WAL manifest (.downloadstate, atomic rename)     │
└─────────────────────────────┬─────────────────────────────────┘
                              │
┌─────────────────────────────▼─────────────────────────────────┐
│            Unified Media Source Abstraction                   │
│  CachedLocalSource · UsbVolumeSource · NetworkSource          │
│  common interface: open() -> SeekableDecryptingStream         │
│  ABR lookup of local multi-res variants if present            │
└───────────────────────────────────────────────────────────────┘

  Smart TV extensions (tvApp, Android SDK only):
  ┌─────────────────────────────────────────────────────────────┐
  │ TvUsbVolumeMonitor (BroadcastReceiver: ACTION_MEDIA_MOUNTED/│
  │   EJECT/REMOVED/UNMOUNTED) → volume detection + mounting    │
  │ TvMediaIndexer (single background executor): walks dirs,    │
  │   verifies integrity (header sniff + size), builds index    │
  │ TvUsbVolumeSource: streams directly from mounted volume     │
  └─────────────────────────────────────────────────────────────┘
```

---

## 3. Data Flow (chunk → encrypt → write → play)

1. **Enqueue** — UI submits `DownloadRequest(titleId, quality, targetStorage)`.
2. **Probe** — scheduler issues HEAD + first `Range` byte request to learn `Content-Length`, `Accept-Ranges`, and (if multi-res) variant manifest.
3. **Partition** — file split into fixed 4 MiB logical chunks. Sparse file pre-allocated on target volume.
4. **Admit** — obey concurrency cap (default 3) + network policy. Emits `Fetching`.
5. **Fetch/Write** — each chunk downloaded over bounded channel; rate-limited by token bucket (no UI-freeze). Data passes through `EncryptedChunkStore`: encrypt-then-write with AES-256-GCM; `nonce`+`tag` recorded per chunk in manifest.
6. **Checkpoint** — after every chunk, manifest rewritten atomically (tmp + fsync + rename). Resume is crash-safe to the last completed chunk.
7. **Pause/Resume** — pause persists in-flight chunk offsets; resume issues `Range` requests only for incomplete chunks.
8. **Network switch** — `NetworkPolicyController` gates chunk admission; `CellularBlocked` state emitted so UI can prompt.
9. **Complete** — integrity sweep (per-chunk SHA-256), finalize `.mediabundle`, update `Completed` state.
10. **Play** — player resolves `UnifiedMediaSource`; for local cache it opens the decrypting stream (zero-copy into platform decoder, HW acceleration), for USB it reads directly from the mounted volume.

---

## 4. Error Handling & Edge-Case Matrix

| Scenario | Strategy |
|---|---|
| Low storage before start | Reserve check: require `2 × content size`; else emit `LOW_STORAGE` + suggest cleanup |
| Disk full mid-write | Catch `ENOSPC`/`IOException` → auto-pause, run cache cleanup policy, resume on next policy tick |
| Sudden USB unmount during playback | Receiver on `ACTION_MEDIA_REMOVED/EJECT`: immediately pause playback, mark volume index stale, keep open FD semantics where OS permits, prompt user |
| Corrupt chunk on verify | Per-chunk SHA-256 in manifest → re-download only that chunk (self-healing) |
| Interrupted network | Exponential backoff (+jitter), Range-based resume, reconnect on network callback |
| App killed mid-download | WAL manifest; on boot reconcile: incomplete tasks resume or purge per policy |
| Cellular switch | Policy gate pauses chunk admission; UI prompt / auto-continue flag |
| CACode decrypt failure | Manifest integrity mismatch → quarantine chunk, refetch |
| Zero-length / truncated file | Probe validates size before commit; `VERIFY_FAILED` state, full redownload |

---

## 5. Deliverables Mapping

1. **Architecture & Data Flow** → this document + `docs/ARCHITECTURE-MEDIA-CACHE.md`.
2. **Production code / core logic** → modules listed in §6 (compiled into `composeApp` commonMain + platform source sets + `tvApp`).
3. **Error handling** → §4 matrix + code paths (retry policy, cleanup policy, unmount handling).

---

## 6. Implementation Phases

- [ ] **P0 Recon** — read existing `DownloadModels`, `LocalDownloadRepository`, `LocalFileStorage.*`, TV downloads screen; reconcile (no regressions).
- [ ] **P1 Models + State Machine** — `DownloadTask`, `ChunkRef`, `DownloadTaskState` sealed FSM (commonMain).
- [ ] **P2 Engine** — `DownloadEngine` queue + `ChunkScheduler` (concurrency cap, token-bucket throttle, backoff retry).
- [ ] **P3 Crypto + Store** — `EncryptedChunkStore` port + AES-256-GCM impls (Android/Desktop = javax.crypto; iOS = CommonCrypto) + WAL manifest.
- [ ] **P4 Transport** — `ChunkTransportPort` + Android/iOS/Desktop actual transports + `NetworkPolicyController`.
- [ ] **P5 Cache Cleaner** — LRU/age/size policies, safe-space reserve.
- [ ] **P6 Unified Player Abstraction** — `UnifiedMediaSource` sealed set + decrypting seekable stream + ABR variant lookup.
- [ ] **P7 TV USB Service** — `TvUsbVolumeMonitor`, `TvMediaIndexer`, `TvUsbVolumeSource`.
- [ ] **P8 Verify** — `:composeApp:compileKotlinDesktop`, `:tvApp:compileDebugKotlin` green; update plan checkboxes.
- [ ] **P9 Memory** — persist progress to server-memory knowledge graph.

---

## 7. Non-Goals (v1)

- No license/DRM handshake protocols (Widevine/FairPlay) — keys are app-generated, stored in platform secure storage.
- HLS/DASH segment downloader is out; v1 targets progressive MP4/stream sources via range requests. ABR local variants handled when manifest advertises them.
- No server-side change required for v1 (server already serves range-capable media).
