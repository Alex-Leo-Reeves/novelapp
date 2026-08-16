# PLAN TV VOLUME BOOST (V146)

## Goal
Auto-boost volume in the TV embed WebView player with zero user interaction.

## Approach
Web Audio gain node injected via JS (player-agnostic):
`MediaElementAudioSource(video) -> DynamicsCompressor -> Gain(1.75x) -> destination`

- Works across same-origin embeds (vidlink, vidsrc, embed.su, nontongo, smashystream, autoembed) and HLS `blob:` streams.
- Capped at 1.75x with soft-knee compressor to avoid clipping.
- Guarded per-element (`v.__novelAppBoosted`) — no stacking gain nodes.
- Cross-origin media without CORS is skipped (`isSafeToRoute`) to avoid tainted-silence.
- AudioContext resume hooked to READY synthetic touch + pointerdown/touchend/keydown once-listeners.

## Injection points (already present in TvEmbedPlayer.kt)
- [x] READY synthetic touch handler -> `evaluateJavascript(AUDIO_BOOST_JS, null)`
- [x] Periodic re-apply every 3s x 30 (~90s) to catch rebuilt `<video>` elements

## Steps
- [x] 1. Write AUDIO_BOOST_JS constant (missing from file)
- [ ] 2. Verify Kotlin compiles (gradle :tvApp:compileDebugKotlin) — user requested build be left running/skipped

## Notes
- No native Kotlin volume boost needed — JS path is correct for WebView.
- AudioContext shared per-document via `window.__NOVEL_BOOST_CTX`.
