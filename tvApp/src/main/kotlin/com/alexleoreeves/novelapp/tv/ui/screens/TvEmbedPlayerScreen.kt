package com.alexleoreeves.novelapp.tv.ui.screens

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.data.UnifiedSearchResult
import com.alexleoreeves.novelapp.tv.data.TvBingeSession
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.components.TvMovieEndRail
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * TV Embed Player Screen — full-screen WebView player for embed URLs.
 * Uses the exact same WebView approach as the working Android MaServerPlayerScreen.
 */
@Composable
fun TvEmbedPlayerScreen(
    embedUrl: String,
    title: String,
    onBack: () -> Unit,
    account: SavedUserAccount? = null,
    previewLimitMs: Long? = null,
    isEpisodic: Boolean = false,
    episodicFraction: Double = TV_EPISODIC_FREE_FRACTION,
    onUpgrade: () -> Unit = {},
    resumePositionMs: Long? = null,
    // True when the user already chose "Continue" in the pre-player resume
    // dialog (TvApp). Skips the in-player resume card and lets the poll
    // AUTO-seek once the video is ready. False = no decision yet → the
    // in-player card handles the choice.
    resumeDecided: Boolean = false,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    bingeSession: TvBingeSession? = null,
    isMovieEnded: Boolean = false,
    serverName: String? = null,
    onNext: () -> Unit = {},
    onPrev: () -> Unit = {},
    onEnded: () -> Unit = {},
    onOpenRecommendations: (UnifiedSearchResult) -> Unit = {}
) {
    var showControls by remember { mutableStateOf(true) }
    // Start as paused: the embed starts paused and only plays after a real
    // user gesture (OK/Play). Claiming "playing" before that left the UI
    // showing a Pause icon while the video sat paused.
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var previewExpired by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var webViewStartedAt by remember { mutableStateOf(0L) }
    // Resume-once flag: seek back to the saved position the first time a
    // real video becomes ready, even if the embed auto-plays.
    // Keyed on embedUrl so navigating to a new episode resets the flag.
    var hasAppliedResume by remember(embedUrl) { mutableStateOf(false) }
    var lastSavedPosition by remember(embedUrl) { mutableStateOf(0L) }
    // How many seek attempts remain when the resume seek reported "none"
    // (video inside a cross-origin iframe JS can't reach yet). We retry a few
    // times so a real resume is not silently lost.
    var pendingResumeAttempts by remember(embedUrl) { mutableStateOf(0) }
    // Set when a manual resume seek fails so we can show a notice instead of
    // silently losing the user's saved position.
    var resumeFailedNotice by remember(embedUrl) { mutableStateOf(false) }
    // Stall auto-recovery counters: consecutive polls where the video claims
    // "playing" but the playhead isn't advancing. After ~8s → synthetic center
    // tap; max 2 per episode so we never fight the user.
    var stallStreak by remember(embedUrl) { mutableStateOf(0) }
    var stallRecoveries by remember(embedUrl) { mutableStateOf(0) }
    val resumeMs = resumePositionMs?.takeIf { it > 30_000L } ?: 0L

    // End-of-media detection: fires once per episode when the embed video
    // reaches within 10 seconds of the end. Keyed on embedUrl so auto-next
    // resets it for the next episode.
    var endedFired by remember(embedUrl) { mutableStateOf(false) }
    val currentOnEnded by rememberUpdatedState(onEnded)

    val isPremium = account?.isPremium == true

    // Reset playback state when a new episode/title loads so the progress bar
    // never shows the previous title's duration or position.
    LaunchedEffect(embedUrl) {
        isPlaying = false
        currentPosition = 0L
        duration = 0L
        previewExpired = false
        pendingResumeAttempts = 0
        resumeFailedNotice = false
        stallStreak = 0
        stallRecoveries = 0
        webViewStartedAt = System.currentTimeMillis()
    }

    DisposableEffect(embedUrl) {
        onDispose {
            try {
                webViewRef?.apply {
                    stopLoading()
                    loadUrl("about:blank")
                    onPause()
                    removeAllViews()
                    destroy()
                }
            } catch (_: Exception) {}
            webViewRef = null
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000)
            showControls = false
        }
    }

    /**
     * Seeks the first reachable <video> (top-level document plus any
     * same-origin iframes). Reports "ok" when a seek landed, "none" when no
     * reachable video exists (e.g. video inside a cross-origin iframe). The
     * resume path uses the result to decide whether to retry.
     *
     * NOTE: declared before [playerSeekTo] — Kotlin local functions are not
     * hoisted, so callers must come after the function they invoke.
     */
    fun playerSeekToChecked(positionMs: Long, onResult: ((String) -> Unit)?) {
        val maxAllowed = if (!isPremium && duration > 0) {
            if (isEpisodic) (duration * episodicFraction).toLong().coerceAtLeast(1)
            else (previewLimitMs ?: TV_MOVIE_FREE_PREVIEW_MS).coerceAtMost(TV_MOVIE_FREE_PREVIEW_MS)
        } else if (!isPremium) {
            (previewLimitMs ?: TV_MOVIE_FREE_PREVIEW_MS).coerceAtMost(TV_MOVIE_FREE_PREVIEW_MS)
        } else Long.MAX_VALUE

        val targetMs = if (!isPremium && positionMs >= maxAllowed) {
            previewExpired = true
            webViewRef?.evaluateJavascript(EMBED_PAUSE_JS, null)
            maxAllowed
        } else {
            positionMs.coerceIn(0L, maxAllowed)
        }

        // Targets the REAL (longest) video via __novelAppFindBestVideo, not the
        // first <video> (which may be a short ad).
        val js = FIND_BEST_VIDEO_JS +
            "(function(t){var v=__novelAppFindBestVideo();if(v){try{v.currentTime=t;return 'ok';}catch(e){return 'none';}}return 'none';})(${targetMs / 1000.0})"
        webViewRef?.evaluateJavascript(js, onResult)
    }

    /** Fire-and-forget seek (arrow rewind/forward keys). */
    fun playerSeekTo(positionMs: Long) {
        playerSeekToChecked(positionMs, null)
    }

    /**
     * Manual "Resume": re-seek to the saved position on demand. Used when the
     * automatic resume failed because the <video> wasn't reachable yet, so the
     * user can retry with the OK button once playback has loaded.
     */
    fun playerResume() {
        if (resumeMs <= 0) return
        playerSeekToChecked(resumeMs) { rawResult ->
            val seekResult = rawResult?.trim()?.trim('"') ?: "none"
            if (seekResult == "ok") {
                hasAppliedResume = true
                pendingResumeAttempts = 0
                resumeFailedNotice = false
                currentPosition = resumeMs
            } else {
                resumeFailedNotice = true
            }
        }
    }

    // Poll video state from the WebView and enforce preview cap
    LaunchedEffect(embedUrl, previewLimitMs) {
        webViewStartedAt = System.currentTimeMillis()
        while (true) {
            val webView = webViewRef
            if (webView == null) {
                delay(200)
                continue
            }
            // CRITICAL: EMBED_VIDEO_STATE_JS calls __novelAppFindBestVideo(),
            // so FIND_BEST_VIDEO_JS MUST be injected together on EVERY poll.
            // Without it, every poll throws a ReferenceError, the JS returns
            // nothing, and the UI never updates (says "playing" while frozen,
            // resume never works, the progress bar never moves).
            webView.evaluateJavascript(FIND_BEST_VIDEO_JS + EMBED_VIDEO_STATE_JS) { raw ->
                val value = raw?.trim()?.trim('"')?.replace("\\\"", "\"") ?: return@evaluateJavascript
                runCatching {
                    val obj = JSONObject(value)
                    val positionMs = obj.optLong("currentTime", 0L)
                    val durationMs = obj.optLong("duration", 0L)
                    val paused = obj.optBoolean("paused", true)
                    val stalled = obj.optBoolean("stalled", false)
                    // Keep the longest duration ever seen so the progress bar /
                    // total time never shrinks while the stream re-buffers
                    // (embed players often report a smaller duration mid-load).
                    if (obj.optBoolean("ready", false) && durationMs > duration) duration = durationMs
                    currentPosition = positionMs
                    isPlaying = !paused

                    // Stall recovery: "playing" but the playhead is frozen for
                    // ~8s → the embed's own black/white play button is likely
                    // blocking inside a cross-origin iframe JS can't reach. A
                    // synthetic center tap dismisses it. Max 2 recoveries per
                    // episode so we never fight the user.
                    if (stalled && !paused) stallStreak++ else stallStreak = 0
                    if (stallStreak >= 8 && stallRecoveries < 2) {
                        stallStreak = 0
                        stallRecoveries++
                        dispatchCenterTouch(webView)
                    }

                    // End of media: within 10s of the end → fire onEnded ONCE
                    // per episode (auto-next / movie-end recommendations).
                    if (!endedFired && durationMs > 0 && positionMs > 0 && durationMs - positionMs <= 10_000L) {
                        endedFired = true
                        currentOnEnded()
                    }

                    // Resume-once: the first time a real video becomes ready,
                    // jump back to the saved position (power loss / app kill).
                    // If the seek reports "none" (cross-origin iframe not yet
                    // reachable), keep the flag unset and retry a few times so
                    // the resume is not silently lost.
                    //
                    // After 5 failed auto-attempts we stop retrying by
                    // ourselves, but hasAppliedResume stays FALSE so the saved
                    // position is never overwritten — the on-screen "Resume"
                    // control lets the user re-trigger the seek manually once
                    // the video has fully loaded.
                    // Auto-resume: only when the user already chose "Continue"
                    // in the pre-player dialog (resumeDecided). Gated on the
                    // video being ready + the playhead still near 0, so we seek
                    // ONLY after the video has actually loaded and never fight
                    // live playback. If the seek reports "none" (cross-origin
                    // iframe not reachable yet), retry up to 5 times — the
                    // saved position is never overwritten in the meantime.
                    if (resumeDecided && resumeMs > 0 && !hasAppliedResume &&
                        pendingResumeAttempts < 5 && obj.optBoolean("ready", false) &&
                        positionMs < 10_000L
                    ) {
                        playerSeekToChecked(resumeMs) { rawResult ->
                            val seekResult = rawResult?.trim()?.trim('"') ?: "none"
                            if (seekResult == "ok") {
                                hasAppliedResume = true
                                pendingResumeAttempts = 0
                                resumeFailedNotice = false
                                currentPosition = resumeMs
                            } else {
                                pendingResumeAttempts++
                            }
                        }
                    }

                    // Persist progress every ~5s.
                    // When a saved position exists and the user hasn't manually
                    // resumed yet, don't overwrite the saved position unless we
                    // are past it (i.e. user is watching from ahead of the saved spot).
                    val canSaveProgress = resumeMs == 0L || hasAppliedResume || positionMs > resumeMs
                    if (canSaveProgress) {
                        if (positionMs > 0 && positionMs - lastSavedPosition >= 5_000L) {
                            lastSavedPosition = positionMs
                            onProgress(positionMs, durationMs)
                        }
                    }

                    if (!isPremium && !previewExpired) {
                        val limit = if (isEpisodic && durationMs > 0) {
                            (durationMs * episodicFraction).toLong().coerceAtLeast(1)
                        } else {
                            (previewLimitMs ?: TV_MOVIE_FREE_PREVIEW_MS).coerceAtMost(TV_MOVIE_FREE_PREVIEW_MS)
                        }
                        val positionForLimit = if (durationMs > 0) positionMs else (System.currentTimeMillis() - webViewStartedAt)
                        if (positionForLimit >= limit) {
                            previewExpired = true
                            webView.evaluateJavascript(EMBED_PAUSE_JS, null)
                        }
                    }
                }
            }
            delay(1000)
        }
    }

    fun playerTogglePlay() {
        val wv = webViewRef ?: return
        // Prefer direct JS play/pause on the REAL (longest) video — reliable
        // even when the embed has hidden its controls (also unmutes). The
        // toggle script calls __novelAppFindBestVideo(), so FIND_BEST_VIDEO_JS
        // must be injected alongside it. Falls back to a synthetic center tap
        // only when the <video> is inside a cross-origin iframe JS can't reach.
        wv.evaluateJavascript(FIND_BEST_VIDEO_JS + EMBED_TOGGLE_PLAY_JS) { raw ->
            val result = raw?.trim()?.trim('"') ?: "none"
            if (result == "none") dispatchCenterTouch(wv)
        }
    }

    fun playerUnmute() {
        val wv = webViewRef ?: return
        // Unmute via JS when the video is reachable. EMBED_UNMUTE_JS uses
        // __novelAppFindBestVideo(), so FIND_BEST_VIDEO_JS is injected first.
        // No touch fallback here — a center tap could accidentally toggle
        // play/pause while the user is only adjusting volume. The volume keys
        // return false below, so the system TV volume still changes normally.
        wv.evaluateJavascript(FIND_BEST_VIDEO_JS + EMBED_UNMUTE_JS, null)
    }

    BackHandler { onBack() }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                val isVolumeKey = keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                    keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                    keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE
                if (isVolumeKey) {
                    if (event.type == KeyEventType.KeyUp) {
                        showControls = true
                        playerUnmute()
                    }
                    false
                } else if (event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp) {
                    if (previewExpired) {
                        when (event.key) {
                            Key.Back -> { onBack(); true }
                            else -> true
                        }
                    } else if (resumeMs > 0 && !hasAppliedResume && !resumeDecided) {
                        // Resume card is showing: let the remote's OK/Enter/dpad
                        // reach the focused Resume / Watch-from-Start Surface
                        // instead of stealing them for playback. Only Back is
                        // handled here so the user can still leave.
                        if (event.key == Key.Back) { onBack(); true } else false
                    } else {
                        showControls = true
                        val isOkKey = event.key == Key.DirectionCenter || event.key == Key.Enter ||
                            keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                            keyCode == android.view.KeyEvent.KEYCODE_BUTTON_A ||
                            event.key == Key.MediaPlayPause || event.key == Key.MediaPlay || event.key == Key.MediaPause

                        if (event.type == KeyEventType.KeyUp) {
                            when {
                                isOkKey -> { playerTogglePlay(); true }
                                event.key == Key.DirectionLeft -> { playerSeekTo(currentPosition - 10000); true }
                                event.key == Key.DirectionRight -> { playerSeekTo(currentPosition + 10000); true }
                                event.key == Key.MediaFastForward -> { playerSeekTo(currentPosition + 30000); true }
                                event.key == Key.MediaRewind -> { playerSeekTo(currentPosition - 15000); true }
                                event.key == Key.MediaNext -> { onNext(); true }
                                event.key == Key.MediaPrevious -> { onPrev(); true }
                                event.key == Key.Back -> { onBack(); true }
                                else -> false
                            }
                        } else {
                            // Intercept KeyDown for navigation keys so focus doesn't jump
                            when {
                                isOkKey || event.key == Key.DirectionLeft || event.key == Key.DirectionRight ||
                                event.key == Key.MediaFastForward || event.key == Key.MediaRewind ||
                                event.key == Key.MediaNext || event.key == Key.MediaPrevious -> true
                                else -> false
                            }
                        }
                    }
                } else false
            }
    ) {
        // TvEmbedPlayer — exact same WebView logic as working Android MaServerPlayerScreen
        TvEmbedPlayer(
            embedUrl = embedUrl,
            episodeTitle = title,
            onWebViewCreated = { webView -> webViewRef = webView },
            onBack = onBack
        )

        // Movie-end recommendations rail — shown when a movie reaches the end
        // (isMovieEnded + endedFired). Loads similar titles from the backend;
        // selecting one opens its detail screen (server picker) via
        // onOpenRecommendations.
        if (isMovieEnded && endedFired) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(0.65f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.Movie, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(56.dp))
                        Text("Movie finished", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("Pick what to watch next", color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                TvMovieEndRail(
                    item = bingeSession?.item,
                    onSelect = { rec -> onOpenRecommendations(rec) }
                )
            }
        }

        // Free preview ended gate
        if (previewExpired) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color(0xFF05050A).copy(0.96f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    Icon(Icons.Default.Lock, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(80.dp))
                    Text("Free Preview Ended", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black, color = Color.White)
                    Text("Go premium to watch the full title.", color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onUpgrade, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))) {
                        Text("Go Premium", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // ── Prominent Resume Card ──────────────────────────────────────────────
        // Shown as a centre-screen overlay when a saved position exists and the
        // user hasn't chosen what to do yet. Replaces the old auto-seek that
        // would jump the embed player to a random minute, breaking some servers.
        // The player loads and buffers normally in the background while the user
        // decides: Resume or Watch from Start.
        AnimatedVisibility(
            visible = resumeMs > 0 && !hasAppliedResume && !previewExpired && !resumeDecided,
            enter = fadeIn(tween(300)),
            exit = fadeOut(tween(200))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(0.78f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF0E0E1A),
                    border = BorderStroke(1.5.dp, Color(0xFF00BFFF).copy(0.45f)),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        Icon(
                            Icons.Default.Replay,
                            contentDescription = null,
                            tint = Color(0xFF00BFFF),
                            modifier = Modifier.size(56.dp)
                        )
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "Continue Watching?",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                "You left off at ${formatEmbedTime(resumeMs)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(0.6f)
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Watch from Start — dismiss card without seeking
                            var startFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = {
                                    // Mark as applied so progress saving starts
                                    // from position 0 and the card is dismissed.
                                    hasAppliedResume = true
                                },
                                shape = RoundedCornerShape(14.dp),
                                color = if (startFocused) Color(0xFF252535) else Color(0xFF1A1A2A),
                                border = if (startFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.18f)),
                                modifier = Modifier.onFocusChanged { startFocused = it.isFocused }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.SkipPrevious, null, tint = Color.White.copy(0.75f), modifier = Modifier.size(22.dp))
                                    Text("Watch from Start", color = Color.White.copy(0.85f), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                                }
                            }

                            // Resume — seek to saved position, then dismiss
                            var resumeCardFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { playerResume() },
                                shape = RoundedCornerShape(14.dp),
                                color = if (resumeCardFocused) Color(0xFF00D4FF) else Color(0xFF00BFFF),
                                border = if (resumeCardFocused) BorderStroke(2.dp, Color.White) else null,
                                modifier = Modifier.onFocusChanged { resumeCardFocused = it.isFocused }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(22.dp))
                                    Text(
                                        "Resume  ${formatEmbedTime(resumeMs)}",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        }

                        if (resumeFailedNotice) {
                            Text(
                                "Seek didn't reach the player yet — let the video fully load, then try Resume again.",
                                color = Color(0xFFFFB347),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
            }
        }

        // Controls overlay (Back + Title + Progress)
        // Hidden while the resume card is showing so the user isn't confused.
        AnimatedVisibility(
            visible = showControls && !previewExpired && (resumeMs == 0L || hasAppliedResume || resumeDecided),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent, Color.Black.copy(0.7f)))
                )
            ) {
                // Top bar: Back + Title
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    var backFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = onBack, shape = CircleShape,
                        color = if (backFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                        border = if (backFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier.size(44.dp).onFocusChanged { backFocused = it.isFocused }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))

                    // Server badge — shows which server the binge session is
                    // pinned to (auto-next/NEXT always reuse this same server).
                    if (serverName != null) {
                        Spacer(Modifier.width(12.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF00BFFF).copy(0.2f),
                            border = BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.5f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Dns, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(16.dp))
                                Text(serverName, color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // Center controls: Previous | Play/Pause | Next
                Row(modifier = Modifier.fillMaxWidth().align(Alignment.Center), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    // Previous episode (remote PREV equivalent) — visible when the
                    // binge session has an earlier episode.
                    if (bingeSession != null && bingeSession.currentIndex > 0) {
                        var prevFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { onPrev() }, shape = CircleShape,
                            color = if (prevFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                            border = if (prevFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                            modifier = Modifier.size(56.dp).onFocusChanged { prevFocused = it.isFocused }
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.SkipPrevious, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(Modifier.width(16.dp))
                    }

                    var ppFocused by remember { mutableStateOf(false) }
                    Surface(
                        onClick = { playerTogglePlay() }, shape = CircleShape,
                        color = if (ppFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                        border = if (ppFocused) BorderStroke(3.dp, Color(0xFF00BFFF)) else null,
                        modifier = Modifier.size(72.dp).onFocusChanged { ppFocused = it.isFocused }
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }

                    // Next episode (remote NEXT equivalent) — visible when the
                    // binge session has a later episode.
                    if (bingeSession != null && bingeSession.hasNext) {
                        Spacer(Modifier.width(16.dp))
                        var nextFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { onNext() }, shape = CircleShape,
                            color = if (nextFocused) Color(0xFF00BFFF) else Color.Black.copy(0.6f),
                            border = if (nextFocused) BorderStroke(2.dp, Color(0xFF00BFFF)) else null,
                            modifier = Modifier.size(56.dp).onFocusChanged { nextFocused = it.isFocused }
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.SkipNext, null, tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }

                // Bottom progress bar
                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                    val progress = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Color(0xFF00BFFF), trackColor = Color.White.copy(0.15f))
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatEmbedTime(currentPosition), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(formatEmbedTime(duration), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    }
}

/**
 * Picks the REAL content video across the top document and every reachable
 * (same-origin) iframe. Embed pages commonly carry MULTIPLE <video> elements:
 * a short pre-roll ad on top and the real stream below. Targeting the FIRST
 * video made the app report "playing" (paused=false) while the user stared at
 * a frozen frame. The longest-duration video is the real content — ads are
 * seconds long, episodes are minutes. Tie-break by largest area, then
 * readyState. Cross-origin iframe videos are not reachable via JS; the
 * synthetic center-touch fallback handles those.
 */
private const val FIND_BEST_VIDEO_JS = """
function __novelAppFindBestVideo() {
    function collect(root) {
        var out = [];
        try { root.querySelectorAll('video').forEach(function(v){ out.push(v); }); } catch(e) {}
        try {
            root.querySelectorAll('iframe').forEach(function(f){
                try {
                    var doc = f.contentDocument || f.contentWindow.document;
                    if (doc) out = out.concat(collect(doc));
                } catch(e) {}
            });
        } catch(e) {}
        return out;
    }
    var videos = collect(document);
    var best = null;
    var bestScore = -1;
    for (var i = 0; i < videos.length; i++) {
        var v = videos[i];
        var hasSrc = !!(v.src && v.src.length > 4) || !!(v.currentSrc && v.currentSrc.length > 4);
        if (!hasSrc) continue;
        var d = (v.duration && isFinite(v.duration)) ? v.duration : 0;
        var w = v.videoWidth || 0;
        var h = v.videoHeight || 0;
        var area = w * h;
        var ready = (v.readyState || 0);
        // Real content: long duration, big canvas, ready. Ads are short + often
        // small. Duration dominates; area breaks ties; readyState breaks those.
        var score = d * 1000 + area + ready;
        if (score > bestScore) { bestScore = score; best = v; }
    }
    return best;
}
"""

private const val EMBED_VIDEO_STATE_JS = """
(function(){
    var best = __novelAppFindBestVideo();
    if (!best) return JSON.stringify({currentTime:0,duration:0,paused:true,ready:false});
    var d = (best.duration && isFinite(best.duration)) ? best.duration * 1000 : 0;
    var c = (best.currentTime && isFinite(best.currentTime)) ? best.currentTime * 1000 : 0;
    // Stall detection: report `stalled` when the video claims to be playing but
    // currentTime is NOT advancing across polls (> ~1.5s per 1s poll). The
    // Kotlin poll clears it by nudging the playhead — see auto-stall-recover.
    var now = c;
    var key = '__novelAppLastT_' + (best.currentSrc || best.src || 'v');
    var last = window[key] || -1;
    var stalled = false;
    if (!best.paused && last >= 0 && now <= last && d > 0) {
        stalled = now === last;
    }
    window[key] = now;
    return JSON.stringify({currentTime:c,duration:d,paused:best.paused !== false,ready:d>0,stalled:stalled});
})()
"""

private const val EMBED_PAUSE_JS = """
(function(){
    function pauseAll(root) {
        try { root.querySelectorAll('video').forEach(function(v){ v.pause(); v.muted = true; }); } catch(e) {}
        try {
            root.querySelectorAll('iframe').forEach(function(f){
                try {
                    var doc = f.contentDocument || f.contentWindow.document;
                    if (doc) pauseAll(doc);
                } catch(e) {}
            });
        } catch(e) {}
    }
    pauseAll(document);
})()
"""

/**
 * Toggle play/pause on the REAL (longest) content video via
 * __novelAppFindBestVideo(), which walks the top document + any same-origin
 * iframes (VidLink, Nontongo and friends render their player in a same-origin
 * iframe). Unmutes + restores volume when resuming, so OK never resumes into
 * silence. The Kotlin call site prepends FIND_BEST_VIDEO_JS so the helper
 * always exists.
 *
 * Returns "toggled" when a video was toggled, "none" when no reachable video
 * exists. "none" triggers the synthetic center-touch fallback for videos that
 * live inside cross-origin iframes where JS cannot reach.
 */
private const val EMBED_TOGGLE_PLAY_JS = """
(function(){
    function boostMediaAudio(v) {
        if (!v || v.__gainBoosted) return;
        try {
            var AC = window.AudioContext || window.webkitAudioContext;
            if (!AC) return;
            if (!window.__novelAppAudioCtx) window.__novelAppAudioCtx = new AC();
            var ctx = window.__novelAppAudioCtx;
            var src = ctx.createMediaElementSource(v);
            var gain = ctx.createGain();
            gain.gain.value = 1.5;
            src.connect(gain);
            gain.connect(ctx.destination);
            v.__gainBoosted = true;
            if (ctx.state === 'suspended') {
                var resume = function() { ctx.resume(); };
                v.addEventListener('play', resume, { once: true });
            }
        } catch(e) {
            try { v.volume = 1.0; } catch(_) {}
        }
    }

    var v = __novelAppFindBestVideo();
    if (v) {
        try {
            if (v.paused) {
                v.muted = false;
                boostMediaAudio(v);
                var p = v.play();
                if (p && p.catch) p.catch(function(){});
            } else {
                v.pause();
            }
            return 'toggled';
        } catch(e) {}
    }
    try {
        var btn = document.querySelector('.play-button, .jw-icon-display, .vjs-big-play-button, .vjs-play-control, button[aria-label="Play"], button[aria-label="Pause"], .plyr__control--overlaid, .play-btn, [class*="play"], [id*="play"]');
        if (btn && btn.tagName !== 'VIDEO') {
            btn.click();
            return 'toggled';
        }
    } catch(e) {}
    return 'none';
})()
"""

/**
 * Unmute every reachable <video>/<audio> — the top-level document plus any
 * same-origin iframes. Boosts volume with clean WebAudio GainNode.
 */
private const val EMBED_UNMUTE_JS = """
(function(){
    function boostMediaAudio(v) {
        if (!v || v.__gainBoosted) return;
        try {
            var AC = window.AudioContext || window.webkitAudioContext;
            if (!AC) return;
            if (!window.__novelAppAudioCtx) window.__novelAppAudioCtx = new AC();
            var ctx = window.__novelAppAudioCtx;
            var src = ctx.createMediaElementSource(v);
            var gain = ctx.createGain();
            gain.gain.value = 1.5;
            src.connect(gain);
            gain.connect(ctx.destination);
            v.__gainBoosted = true;
            if (ctx.state === 'suspended') {
                var resume = function() { ctx.resume(); };
                v.addEventListener('play', resume, { once: true });
            }
        } catch(e) {
            try { v.volume = 1.0; } catch(_) {}
        }
    }

    function collectVideos(root) {
        var out = [];
        try { root.querySelectorAll('video,audio').forEach(function(v){ out.push(v); }); } catch(e) {}
        try {
            root.querySelectorAll('iframe').forEach(function(f){
                try {
                    var doc = f.contentDocument || f.contentWindow.document;
                    if (doc) out = out.concat(collectVideos(doc));
                } catch(e) {}
            });
        } catch(e) {}
        return out;
    }
    collectVideos(document).forEach(function(v) {
        try {
            v.muted = false;
            boostMediaAudio(v);
            var p = v.play && v.play();
            if (p) p.catch(function(){});
        } catch(e) {}
    });
    try {
        document.querySelectorAll('[aria-label*="Mute"],[title*="Mute"],[class*="mute"],[id*="mute"]').forEach(function(el) {
            try { el.click(); } catch(e) {}
        });
    } catch(e) {}
})();
"""

/** Injects a real center tap into the WebView. Used as a fallback when the
 * <video> lives inside a cross-origin iframe that injected JS cannot reach.
 * File-level so both the poll (stall recovery) and playerTogglePlay can use
 * it without Kotlin's no-forward-reference rule for local functions. */
private fun dispatchCenterTouch(webView: WebView) {
    val w = webView.width.takeIf { it > 0 } ?: 1920
    val h = webView.height.takeIf { it > 0 } ?: 1080
    val downTime = android.os.SystemClock.uptimeMillis()
    val eventDown = android.view.MotionEvent.obtain(
        downTime, downTime,
        android.view.MotionEvent.ACTION_DOWN,
        w / 2f, h / 2f, 0
    )
    val eventUp = android.view.MotionEvent.obtain(
        downTime, downTime + 100,
        android.view.MotionEvent.ACTION_UP,
        w / 2f, h / 2f, 0
    )
    webView.dispatchTouchEvent(eventDown)
    webView.dispatchTouchEvent(eventUp)
    eventDown.recycle()
    eventUp.recycle()
}

private fun formatEmbedTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
