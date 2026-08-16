# PLAN-TV-WEBPLAYER-OK-AUTOPLAY-V147

Goal: fix the remaining webplayer issues from the user's 2026-08-15 report.

## User-reported symptoms
1. Vidlink sometimes "just stays and starts loading and never stops loading".
2. Vidlink shows "cannot find content", then after leaving it a while it plays.
3. OK button behavior requested:
   - OK#1 → video plays (first time)
   - OK#2 → shows the controls overlay
   - OK#3 → pauses
   - OK#4 → plays again (cycle repeats: controls → pause → play)
4. Autoplay upon loading the webplayer was removed — add it back, unmuted,
   volume at MAX.
5. Continue/resume UI should only show after clicking an episode; the
   webplayer must NOT start until the user chooses Continue or Start.
6. "Even when I put Start from Beginning it doesn't play."
7. The remote cannot control the start/resume UI.
8. Suspects pausing may be caused by episode caching — check it.

## Already implemented (V146, verified in code)
- Pre-player "Continue Watching?" AlertDialog in TvApp.playBingeEpisode —
  shown BEFORE any player composes; video doesn't load until the choice is made.
- resumeDecided/resumePositionMs routing; in-player resume card fallback with
  key passthrough.
- Best-video targeting (longest duration) + stall recovery.
- WebView reload on episode
