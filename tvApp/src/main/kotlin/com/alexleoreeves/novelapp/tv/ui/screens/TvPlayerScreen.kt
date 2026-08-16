package com.alexleoreeves.novelapp.tv.ui.screens

import android.net.Uri
import android.view.ViewGroup
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.alexleoreeves.novelapp.data.UnifiedSearchResult
import com.alexleoreeves.novelapp.tv.data.TvBingeSession
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.ui.components.TvMovieEndRail
import kotlinx.coroutines.delay
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.VLCVideoLayout

/** Hard cap copied from the backend so free users can NEVER finish a full title on TV. */
const val TV_MOVIE_FREE_PREVIEW_MS = 20 * 60 * 1000L
const val TV_EPISODIC_FREE_FRACTION = 0.2

/**
 * TV Player powered by LibVLC SDK for direct .m3u8/.mp4/.mpd streams.
 * This is the TV equivalent of Android's AnimePlayerScreen (ExoPlayer).
 *
 * Embed URLs should be routed to [TvEmbedPlayerScreen] instead.
 */
@Composable
fun TvPlayerScreen(
    streamUrl: String,
    title: String,
    onBack: () -> Unit,
    account: SavedUserAccount? = null,
    previewLimitMs: Long? = null,
    isEpisodic: Boolean = false,
    episodicFraction: Double = TV_EPISODIC_FREE_FRACTION,
    onUpgrade: () -> Unit = {},
    resumePositionMs: Long? = null,
    // True when the pre-player resume dialog already decided (Continue or
    // Watch-from-Beginning). Hides the in-player resume bar; the auto-resume
    // loop applies the seek once playback starts.
    resumeDecided: Boolean = false,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    bingeSession: TvBingeSession? = null,
    isMovieEnded: Boolean = false,
    serverName: String? = null,
    onNext: () -> Unit = {},
    onPrev: () -> Unit = {},
    onEnded: () -> Unit = {},
    onOpenRecommendations: (UnifiedSearchResult) -> Unit = {},
    subtitlePath: String? = null
) {
    val context = LocalContext.current
    var showControls by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var previewExpired by remember { mutableStateOf(false) }
    var vlcMediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }
    var libVlc by remember { mutableStateOf<LibVLC?>(null) }
    // Resume-once from the saved position (power loss / app kill).
    // Keyed on streamUrl so navigating to a new episode resets the flag.
    var hasAppliedResume by remember(streamUrl) { mutableStateOf(false) }
    var lastSavedPosition by remember(streamUrl) { mutableStateOf(0L) }
    val resumeMs = resumePositionMs?.takeIf { it > 30_000L } ?: 0L

    // End-of-media detection: fires once per episode when playback reaches
    // within 10 seconds of the end. Keyed on streamUrl so auto-next resets it.
    var endedFired by remember(streamUrl) { mutableStateOf(false) }
    val currentOnEnded by rememberUpdatedState(onEnded)

    // Reset playback state when a new episode/title loads so the progress bar
    // never shows the previous title's duration or position.
    LaunchedEffect(streamUrl) {
        isPlaying = false
        currentPosition = 0L
        duration = 0L
        errorMsg = null
        previewExpired = false
    }

    val isPremium = account?.isPremium == true
    val resolvedUrl = streamUrl.trim()

    // LibVLC SDK Initialization.
    // The player must exist BEFORE the AndroidView factory runs (composition
    // order), otherwise attachViews() is called on null and the video surface
    // is never attached — a silent black screen. IMPORTANT: do NOT call play()
    // here — LibVLC needs the surface attached first, so playback starts in the
    // AndroidView factory (after attachViews) and in the LaunchedEffect below.
    if (resolvedUrl.isNotBlank()) {
        remember(resolvedUrl) {
            runCatching {
                val args = arrayListOf(
                    "--no-drop-late-frames",
                    "--no-skip-frames",
                    "--rtsp-tcp",
                    "-vvv"
                )
                val vlc = LibVLC(context, args)
                val mp = MediaPlayer(vlc)
                mp.volume = 100

                val media = Media(vlc, Uri.parse(resolvedUrl))
                if (resolvedUrl.contains("shegu.net") || resolvedUrl.contains("febbox")) {
                    media.addOption(":http-referrer=https://www.febbox.com/")
                }
                // Offline (downloaded) bundles may carry a bundled English .srt
                // sidecar recorded in the manifest. Attach it as an external
                // subtitle slave so downloaded episodes keep their subs.
                if (!subtitlePath.isNullOrBlank()) {
                    val srt = java.io.File(subtitlePath)
                    if (srt.isFile) {
                        // libVLC URL-encodes the path for file:// URIs.
                        val uri = Uri.fromFile(srt)
                        media.addSlave(
                            IMedia.Slave(IMedia.Slave.Type.Subtitle, 0, uri.toString())
                        )
                    }
                }
                mp.media = media
                media.release()

                mp.setEventListener { event ->
                    when (event.type) {
                        MediaPlayer.Event.TimeChanged -> {
                            currentPosition = mp.time
                            if (duration <= 0 && mp.length > 0) {
                                duration = mp.length
                            }
                            // End of media: within 10s of the end → fire onEnded
                            // ONCE per episode (auto-next / movie-end card).
                            val len = mp.length
                            if (!endedFired && len > 0 && mp.time > 0 && len - mp.time <= 10_000L) {
                                endedFired = true
                                currentOnEnded()
                            }
                        }
                        MediaPlayer.Event.LengthChanged -> {
                            duration = mp.length
                        }
                        MediaPlayer.Event.EndReached -> {
                            if (!endedFired) {
                                endedFired = true
                                currentOnEnded()
                            }
                        }
                        MediaPlayer.Event.Playing -> {
                            isPlaying = true
                        }
                        MediaPlayer.Event.Paused -> {
                            isPlaying = false
                        }
                        MediaPlayer.Event.EncounteredError -> {
                            errorMsg = "LibVLC playback error encountered"
                        }
                    }
                }

                vlcMediaPlayer = mp
                libVlc = vlc
            }.onFailure { e ->
                errorMsg = e.localizedMessage ?: "VLC Init Failed"
            }
        }

        DisposableEffect(resolvedUrl) {
            onDispose {
                runCatching {
                    vlcMediaPlayer?.stop()
                    vlcMediaPlayer?.release()
                    libVlc?.release()
                }
                vlcMediaPlayer = null
                libVlc = null
            }
        }

        // Safety net: if the AndroidView factory ran before the player was
        // assigned (unlikely now, but cheap insurance), start playback as soon
        // as the player exists and the surface has had a frame to attach.
        LaunchedEffect(vlcMediaPlayer) {
            val mp = vlcMediaPlayer ?: return@LaunchedEffect
            delay(500)
            if (!mp.isPlaying) mp.play()
        }

        // Resume-once: jump back to the saved position (power loss / app kill)
        // the first time playback actually starts. LibVLC needs the surface
        // attached and the media playing before a seek lands, so we poll until
        // the player reports playing.
        LaunchedEffect(vlcMediaPlayer) {
            val mp = vlcMediaPlayer ?: return@LaunchedEffect
            while (!hasAppliedResume && !previewExpired) {
                if (resumeMs > 0 && mp.isPlaying) {
                    hasAppliedResume = true
                    currentPosition = resumeMs
                    runCatching {
                        mp.time = resumeMs.coerceIn(0L, mp.length.takeIf { it > 0 } ?: resumeMs)
                    }
                }
                delay(300)
            }
        }

        // Persist playback position every ~5s so a power cut / app kill loses
        // at most a few seconds. Skipped until resume is applied so a mid-load
        // position of 0 can never overwrite the saved spot.
        LaunchedEffect(vlcMediaPlayer) {
            val mp = vlcMediaPlayer ?: return@LaunchedEffect
            while (true) {
                delay(5_000L)
                if (hasAppliedResume || resumeMs == 0L) {
                    val pos = mp.time
                    if (pos > 0 && pos - lastSavedPosition >= 5_000L) {
                        lastSavedPosition = pos
                        onProgress(pos, duration.takeIf { it > 0 } ?: mp.length)
                    }
                }
            }
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(5000)
            showControls = false
        }
    }

    // Preview cap for direct LibVLC stream
    LaunchedEffect(vlcMediaPlayer, isPremium) {
        val mp = vlcMediaPlayer
        while (mp != null && !previewExpired) {
            currentPosition = mp.time
            val currentDuration = mp.length.takeIf { it > 0 } ?: duration
            if (!isPremium && currentDuration > 0) {
                val limit = if (isEpisodic) {
                    (currentDuration * episodicFraction).toLong().coerceAtLeast(1)
                } else {
                    (previewLimitMs ?: TV_MOVIE_FREE_PREVIEW_MS).coerceAtMost(TV_MOVIE_FREE_PREVIEW_MS)
                }
                if (currentPosition >= limit) {
                    previewExpired = true
                    runCatching { mp.pause() }
                }
            }
            delay(500)
        }
    }

    fun playerSeekTo(positionMs: Long) {
        vlcMediaPlayer?.let { it.time = positionMs.coerceIn(0L, duration) }
    }

    fun playerTogglePlay() {
        vlcMediaPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun playerUnmute() {
        vlcMediaPlayer?.let { player ->
            if (player.volume <= 0) player.volume = 100
        }
    }

    /**
     * Manual "Resume": jump straight to the saved position (power loss /
     * app kill). Shown when the automatic resume hasn't applied yet, so the
     * user can re-trigger it with the OK button.
     */
    fun playerResume() {
        vlcMediaPlayer?.let { mp ->
            hasAppliedResume = true
            currentPosition = resumeMs
            runCatching {
                mp.time = resumeMs.coerceIn(0L, mp.length.takeIf { it > 0 } ?: resumeMs)
            }
        }
    }

    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
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
                } else if (event.type == KeyEventType.KeyUp) {
                    if (previewExpired) {
                        when (event.key) {
                            Key.Back -> { onBack(); true }
                            else -> true
                        }
                    } else {
                        showControls = true
                        when (event.key) {
                            Key.DirectionLeft -> { playerSeekTo(currentPosition - 10000); true }
                            Key.DirectionRight -> { playerSeekTo(currentPosition + 10000); true }
                            Key.DirectionCenter, Key.Enter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { playerTogglePlay(); true }
                            Key.MediaFastForward -> { playerSeekTo(currentPosition + 30000); true }
                            Key.MediaRewind -> { playerSeekTo(currentPosition - 15000); true }
                            Key.MediaNext -> { onNext(); true }
                            Key.MediaPrevious -> { onPrev(); true }
                            Key.Back -> { onBack(); true }
                            else -> false
                        }
                    }
                } else false
            }
    ) {
        // LibVLC Surface View.
        // The AndroidView factory runs ONCE; a binge NEXT/PREV swaps the
        // streamUrl and re-creates the player in place, so the surface must be
        // re-attached when the player changes (otherwise direct-stream episode
        // swaps black-screen). The layout ref is stored after composition and
        // re-attached in LaunchedEffect(vlcMediaPlayer).
        var vlcLayoutRef by remember { mutableStateOf<VLCVideoLayout?>(null) }
        if (errorMsg == null) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        vlcLayoutRef = this
                        vlcMediaPlayer?.let { player ->
                            player.attachViews(this, null, false, false)
                            if (!player.isPlaying) player.play()
                        }
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Re-attach the surface whenever the player instance changes (episode
        // swap). Without this the new LibVLC player has no surface → black screen.
        LaunchedEffect(vlcMediaPlayer) {
            val layout = vlcLayoutRef ?: return@LaunchedEffect
            val player = vlcMediaPlayer ?: return@LaunchedEffect
            runCatching {
                player.attachViews(layout, null, false, false)
                if (!player.isPlaying) player.play()
            }
        }

        // Error overlay
        if (errorMsg != null) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.8f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFEF4444), modifier = Modifier.size(64.dp))
                    Text("Playback Error", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(errorMsg!!, color = Color.White.copy(0.6f), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00BFFF))) {
                        Text("Go Back", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

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

        // Controls overlay
        AnimatedVisibility(
            visible = showControls && errorMsg == null && !previewExpired,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300))
        ) {
            Box(
                modifier = Modifier.fillMaxSize().background(
                    Brush.verticalGradient(listOf(Color.Black.copy(0.7f), Color.Transparent, Color.Black.copy(0.7f)))
                )
            ) {
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

                Column(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp)) {
                    val progress = if (duration > 0) (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Color(0xFF00BFFF), trackColor = Color.White.copy(0.15f))
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(currentPosition), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                        Text(formatTime(duration), color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                    }

                    // Resume control — lets the user jump straight back to the
                    // saved position after a power loss / app kill. Hidden when
                    // the pre-player dialog already made the decision.
                    if (resumeMs > 0 && !hasAppliedResume && !resumeDecided) {
                        Spacer(Modifier.height(10.dp))
                        var resumeFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { playerResume() },
                            shape = RoundedCornerShape(8.dp),
                            color = if (resumeFocused) Color(0xFF06D6A0) else Color(0xFF06D6A0).copy(0.2f),
                            border = if (resumeFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0xFF06D6A0).copy(0.6f)),
                            modifier = Modifier.align(Alignment.CenterHorizontally).onFocusChanged { resumeFocused = it.isFocused }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Replay, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Text("Resume ${formatTime(resumeMs)}", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
