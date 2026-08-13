package com.alexleoreeves.novelapp.tv.ui

import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.tv.audio.TvTtsEngine
import com.alexleoreeves.novelapp.tv.mediacache.TvMediaCacheController
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.tv.data.*
import com.alexleoreeves.novelapp.platform.AppUpdateTarget

import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.platform.TvWatchProgressStore
import com.alexleoreeves.novelapp.tv.platform.UserSessionStore
import com.alexleoreeves.novelapp.tv.update.TvUpdateInstaller
import androidx.compose.ui.platform.LocalContext
import com.alexleoreeves.novelapp.tv.ui.screens.TvAuthScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvSplashScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvPhonePairScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvDetailScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvHomeScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvPlayerScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvEmbedPlayerScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvNovelReaderScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvMangaViewerScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvSportsScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvDownloadsScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvYouScreen
import com.alexleoreeves.novelapp.tv.ui.screens.TvProfileScreen
import com.alexleoreeves.novelapp.tv.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TvScreen {
    SPLASH, AUTH, PAIR, PROFILE, HOME, DETAIL, PLAYER, EMBED_PLAYER, READER, MANGA_VIEWER
}

data class TvProfile(
    val id: String,
    val name: String,
    val isKids: Boolean,
    val avatarColorIndex: Int
)

data class NavigationState(
    val screen: TvScreen = TvScreen.SPLASH,
    val selectedSection: TvSection = TvSection.HOME,
    val selectedItem: UnifiedSearchResult? = null,
    val playUrl: String = "",
    val playTitle: String = "",
    val playPreviewLimitMs: Long? = null,
    val playerFromSection: TvSection? = null,
    val bingeSession: TvBingeSession? = null,
    val readerText: String = "",
    val readerTitle: String = "",
    val mangaPages: List<String> = emptyList(),
    val mangaTitle: String = "",
    val showSearch: Boolean = false,
    val account: SavedUserAccount? = null,
    val selectedProfile: TvProfile? = null,
    val localSubtitlePath: String = ""
)


@Composable
fun TvApp(
    sessionStore: UserSessionStore,
    ttsEngine: TvTtsEngine,
    mediaCache: TvMediaCacheController? = null
) {
    var nav by remember { mutableStateOf(NavigationState()) }
    var isLoading by remember { mutableStateOf(true) }
    var remoteConfig by remember { mutableStateOf(TvRemoteConfigDefaults.default) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // In-app update state (Android TV APK channel)
    var startupUpdateManifest by remember { mutableStateOf<AppUpdateManifest?>(null) }
    var isStartupUpdateDismissed by remember { mutableStateOf(false) }
    val updateProgress by AppUpdateProgressBus.state.collectAsState()
    val tvUpdateInstaller = remember(context) { TvUpdateInstaller }

    // Persistent watch-progress store — survives app kills, TV power loss and
    // re-launches. Keyed by "${mediaId}::${episodeTitle}".
    val watchProgressStore = remember(context) { TvWatchProgressStore(context) }

    // Load saved account
    LaunchedEffect(Unit) {
        val saved = sessionStore.loadAccount()
        if (saved != null && saved.authToken.isNotBlank()) {
            // Verify token is still valid. authMe returns null ONLY for a real
            // 401/403 rejection; it throws for transient errors (a TV boots
            // before the network is up). So: null → session truly dead, clear.
            // Exception → keep the saved account and continue (no forced logout).
            val fresh = try { authMe(saved.authToken) } catch (_: Exception) { saved }
            if (fresh != null) {
                nav = nav.copy(account = fresh)
                sessionStore.saveAccount(fresh)
            } else {
                sessionStore.clearAccount()
            }
        }
        isLoading = false
    }

    // Server-driven config: poll on launch, then every refreshSeconds.
    // A config push (site/tv-config.json) updates every running TV live.
    LaunchedEffect(remoteConfig.version) {
        while (true) {
            val fetched = fetchTvConfig()
            if (fetched != null) remoteConfig = fetched
            delay(remoteConfig.effectiveRefreshMillis)
        }
    }

    // In-app update check (Android TV APK channel). The manifest is the
    // authoritative source (site/app-version.json) and carries tvApkSha256 /
    // tvApkBytes for verification. iOS has NO auto-update — its button only
    // opens the ipaUrl. Android/Desktop handle their own updates.
    LaunchedEffect(Unit) {
        val client = platformHttpClient()
        try {
            startupUpdateManifest = fetchAppUpdateManifest(client, AppUpdateTarget.ANDROID_TV)
                ?.takeIf { it.isAvailableFor(AppUpdateTarget.ANDROID_TV) }
        } catch (_: Exception) {
            startupUpdateManifest = null
        } finally {
            client.close()
        }
    }

    // Handle back navigation: each press moves ONE step back in the flow.
    // The app only exits when the user is already on HOME.
    fun goBack() {
        nav = when (nav.screen) {
            TvScreen.DETAIL -> nav.copy(screen = TvScreen.HOME, selectedItem = null)
            TvScreen.PLAYER, TvScreen.EMBED_PLAYER -> {
                mediaCache?.stopPlayback()
                val fromSection = nav.playerFromSection
                val selectedItem = nav.selectedItem
                when {
                    fromSection != null -> nav.copy(
                        screen = TvScreen.HOME,
                        selectedSection = fromSection,
                        playUrl = "", playTitle = "", playPreviewLimitMs = null,
                        playerFromSection = null, selectedItem = null, localSubtitlePath = ""
                    )
                    // Local (downloaded) playback: no binge session/item → return
                    // to the Downloads section instead of a blank DETAIL screen.
                    selectedItem == null -> nav.copy(
                        screen = TvScreen.HOME,
                        selectedSection = TvSection.DOWNLOADS,
                        playUrl = "", playTitle = "", playPreviewLimitMs = null,
                        playerFromSection = null, bingeSession = null, localSubtitlePath = ""
                    )
                    else -> nav.copy(
                        screen = TvScreen.DETAIL,
                        playUrl = "", playTitle = "", playPreviewLimitMs = null,
                        playerFromSection = null, localSubtitlePath = ""
                    )
                }
            }
            TvScreen.READER -> nav.copy(screen = TvScreen.DETAIL, readerText = "", readerTitle = "")
            TvScreen.MANGA_VIEWER -> nav.copy(screen = TvScreen.DETAIL, mangaPages = emptyList(), mangaTitle = "")
            TvScreen.PAIR -> nav.copy(screen = TvScreen.AUTH)
            TvScreen.AUTH -> nav
            TvScreen.PROFILE -> nav.copy(screen = TvScreen.AUTH, account = nav.account)
            TvScreen.SPLASH -> nav.copy(screen = if (nav.account != null) TvScreen.PROFILE else TvScreen.AUTH)
            else -> nav
        }
    }

    /**
     * Launches a binge session: resolves the first episode (if the session is
     * lazy), records the resume key, and routes to PLAYER or EMBED_PLAYER.
     */
    fun playBingeEpisode(
        currentNav: NavigationState,
        session: TvBingeSession,
        progressStore: TvWatchProgressStore,
        ctx: android.content.Context,
        onResult: (NavigationState) -> Unit
    ) {
        if (session.episodes.isEmpty()) {
            onResult(currentNav)
            return
        }
        val current = session.current
        val targetIndex = session.currentIndex
        val progressKey = "${session.item.id}::${session.item.title} - ${current?.chapter?.title}"
        val resumeMs = progressStore.loadResumeKey(progressKey) ?: 0L

        val resolved = session.episodes.getOrNull(targetIndex)
        if (resolved != null && resolved.url.isNotBlank() && !resolved.isDirect) {
            // Already resolved → route to embed player.
            val title = "${session.item.title} - ${resolved.chapter.title}"
            onResult(
                currentNav.copy(
                    screen = TvScreen.EMBED_PLAYER,
                    selectedItem = session.item,
                    playUrl = resolved.url,
                    playTitle = title,
                    playPreviewLimitMs = null,
                    playerFromSection = null,
                    bingeSession = session
                )
            )
        } else if (resolved != null && resolved.url.isNotBlank() && resolved.isDirect) {
            // Already resolved → direct LibVLC player.
            val title = "${session.item.title} - ${resolved.chapter.title}"
            onResult(
                currentNav.copy(
                    screen = TvScreen.PLAYER,
                    selectedItem = session.item,
                    playUrl = resolved.url,
                    playTitle = title,
                    playPreviewLimitMs = null,
                    playerFromSection = null,
                    bingeSession = session
                )
            )
        } else {
            // Lazy episode → resolve now on the session's server.
            scope.launch {
                val repo = TvMediaRepository()
                val resolvedEpisode = repo.resolveBingeEpisode(
                    context = ctx,
                    item = session.item,
                    chapter = current?.chapter,
                    server = if (session.isDonghua) null
                        else if (session.animeServer != null) session.animeServer?.toStreamServer() ?: session.server
                        else session.server,
                    donghuaServer = if (session.isDonghua) session.donghuaServer else null,
                    animeServer = session.animeServer,
                    isDonghua = session.isDonghua
                )
                if (resolvedEpisode == null || resolvedEpisode.url.isBlank()) {
                    onResult(currentNav) // stream unavailable; stay on detail
                    return@launch
                }
                val updatedSession = session.withResolvedEpisode(targetIndex, resolvedEpisode)
                val title = "${session.item.title} - ${resolvedEpisode.chapter.title}"
                onResult(
                    currentNav.copy(
                        screen = if (resolvedEpisode.isDirect) TvScreen.PLAYER else TvScreen.EMBED_PLAYER,
                        selectedItem = session.item,
                        playUrl = resolvedEpisode.url,
                        playTitle = title,
                        playPreviewLimitMs = null,
                        playerFromSection = null,
                        bingeSession = updatedSession
                    )
                )
            }
        }
    }

    /**
     * Advances (or retreats) the current binge session by [delta] episodes,
     * resolving a lazy target episode on the SAME server, then re-routes the
     * player. Used by remote NEXT/PREV and by auto-next on episode end.
     */
    fun advanceBinge(delta: Int) {
        val session = nav.bingeSession ?: return
        val targetIndex = session.currentIndex + delta
        if (targetIndex < 0 || targetIndex > session.episodes.lastIndex) return
        val target = session.episodes.getOrNull(targetIndex) ?: return
        val targetTitle = "${session.item.title} - ${target.chapter.title}"
        val targetNav = nav.copy(
            selectedItem = session.item,
            bingeSession = session.withIndex(targetIndex)
        )

        if (target.url.isNotBlank()) {
            nav = targetNav.copy(
                screen = if (target.isDirect) TvScreen.PLAYER else TvScreen.EMBED_PLAYER,
                playUrl = target.url,
                playTitle = targetTitle
            )
        } else {
            scope.launch {
                val repo = TvMediaRepository()
                val resolvedEpisode = repo.resolveBingeEpisode(
                    context = context,
                    item = session.item,
                    chapter = target.chapter,
                    server = if (session.isDonghua) null
                        else if (session.animeServer != null) session.animeServer?.toStreamServer() ?: session.server
                        else session.server,
                    donghuaServer = if (session.isDonghua) session.donghuaServer else null,
                    animeServer = session.animeServer,
                    isDonghua = session.isDonghua
                )
                if (resolvedEpisode == null || resolvedEpisode.url.isBlank()) return@launch
                val updatedSession = session.withResolvedEpisode(targetIndex, resolvedEpisode)
                nav = targetNav.copy(
                    bingeSession = updatedSession,
                    screen = if (resolvedEpisode.isDirect) TvScreen.PLAYER else TvScreen.EMBED_PLAYER,
                    playUrl = resolvedEpisode.url,
                    playTitle = "${session.item.title} - ${resolvedEpisode.chapter.title}"
                )
            }
        }
    }

    // Hardware/system back: one step per press.
    // HOME / AUTH are the exit points. PROFILE goes back to AUTH (login) via goBack().
    val backPressedDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    DisposableEffect(backPressedDispatcher) {
        val callback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (nav.screen) {
                    TvScreen.HOME, TvScreen.AUTH -> backPressedDispatcher?.onBackPressed()
                    else -> goBack()
                }
            }
        }
        backPressedDispatcher?.addCallback(callback)
        onDispose { callback.remove() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF06060A))) {
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Purple500, modifier = Modifier.size(48.dp))
                }
            }

            nav.screen == TvScreen.SPLASH -> {
                TvSplashScreen(onFinished = {
                    val account = sessionStore.loadAccount()
                    nav = if (account != null) {
                        // Auth-first: returning user picks a profile before browsing.
                        nav.copy(screen = TvScreen.PROFILE, account = account)
                    } else {
                        // Auth-first: new users must sign in before browsing. No guest bypass.
                        nav.copy(screen = TvScreen.AUTH)
                    }
                })
            }

            nav.screen == TvScreen.AUTH -> {
                TvAuthScreen(
                    onSignIn = { email, password ->
                        scope.launch {
                            try {
                                val account = authLogin(email, password)
                                sessionStore.saveAccount(account)
                                nav = nav.copy(screen = TvScreen.PROFILE, account = account)
                            } catch (e: Exception) { /* error handled inside TvAuthScreen */ }
                        }
                    },
                    onCreateAccount = { username, email, password, recoverySecret ->
                        scope.launch {
                            try {
                                val account = authRegister(username, email, password, recoverySecret)
                                sessionStore.saveAccount(account)
                                nav = nav.copy(screen = TvScreen.PROFILE, account = account)
                            } catch (e: Exception) { /* error handled inside TvAuthScreen */ }
                        }
                    },
                    onPhonePair = { nav = nav.copy(screen = TvScreen.PAIR) }
                )
            }

            nav.screen == TvScreen.PAIR -> {
                TvPhonePairScreen(
                    sessionStore = sessionStore,
                    onApproved = { account ->
                        nav = nav.copy(screen = TvScreen.PROFILE, account = account)
                    },
                    onBack = { goBack() }
                )
            }

            nav.screen == TvScreen.PROFILE -> {
                TvProfileScreen(
                    account = nav.account,
                    onSelectProfile = { profile ->
                        nav = nav.copy(screen = TvScreen.HOME, selectedProfile = profile)
                    }
                )
            }

            else -> {
                // Main layout with sidebar navigation
                Row(modifier = Modifier.fillMaxSize()) {
                    // Skip sidebar in non-home screens (player/reader/manga are full-screen)
                    if (nav.screen == TvScreen.HOME) {
                        TvSidebar(
                            config = remoteConfig,
                            selectedSection = nav.selectedSection,
                            onSectionSelected = { section ->
                                nav = nav.copy(selectedSection = section, showSearch = false)
                            },
                            account = nav.account,
                            onSignInClick = { nav = nav.copy(screen = TvScreen.AUTH) },
                            onSignOut = {
                                scope.launch {
                                    nav.account?.let { authLogout(it.authToken) }
                                }
                                sessionStore.clearAccount()
                                nav = nav.copy(account = null, selectedSection = TvSection.HOME, screen = TvScreen.AUTH)
                            }
                        )
                    }

                    // Main content area
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        when (nav.screen) {
                            TvScreen.HOME -> {
                                TvHomeScreen(
                                    section = nav.selectedSection,
                                    account = nav.account,
                                    config = remoteConfig,
                                    selectedProfile = nav.selectedProfile,
                                    mediaCache = mediaCache,
                                    onPlayLocalInternal = { taskId ->
                                        val source = mediaCache?.internalSourceFor(taskId) ?: return@TvHomeScreen
                                        scope.launch {
                                            val url = mediaCache?.playableUrlFor(source)
                                            if (url != null) {
                                                val subtitle = mediaCache.listCompletedInternal()
                                                    .firstOrNull { it.taskId == taskId }
                                                    ?.subtitleBundlePath ?: ""
                                                nav = nav.copy(
                                                    screen = TvScreen.PLAYER,
                                                    playUrl = url,
                                                    playTitle = mediaCache.listCompletedInternal()
                                                        .firstOrNull { it.taskId == taskId }?.title ?: taskId,
                                                    localSubtitlePath = subtitle
                                                )
                                            }
                                        }
                                    },
                                    onPlayLocalUsb = { bundle ->
                                        val source = mediaCache?.usbSourceFor(bundle) ?: return@TvHomeScreen
                                        scope.launch {
                                            val url = mediaCache?.playableUrlFor(source)
                                            if (url != null) {
                                                val manifest = mediaCache.decodeUsbMetadata(bundle)
                                                nav = nav.copy(
                                                    screen = TvScreen.PLAYER,
                                                    playUrl = url,
                                                    playTitle = bundle.title,
                                                    localSubtitlePath = manifest?.subtitleBundlePath ?: ""
                                                )
                                            }
                                        }
                                    },
                                    onRemoveLocalUsb = { bundle ->
                                        mediaCache?.removeUsbBundle(bundle)
                                    },
                                    onSwitchProfile = { nav = nav.copy(screen = TvScreen.PROFILE) },
                                    onMediaSelected = { item ->
                                        nav = nav.copy(screen = TvScreen.DETAIL, selectedItem = item)
                                    },
                                    onSearch = { query ->
                                        // Search is handled by TvHomeScreen internally
                                    },
                                    onReadNovel = { text, title ->
                                        nav = nav.copy(screen = TvScreen.READER, readerText = text, readerTitle = title)
                                    },
                                    onPlaySports = { url, title ->
                                        nav = nav.copy(
                                            screen = TvScreen.PLAYER,
                                            playUrl = url,
                                            playTitle = title,
                                            playerFromSection = TvSection.SPORTS
                                        )
                                    },
                                    onSignOut = {
                                        scope.launch {
                                            nav.account?.let { authLogout(it.authToken) }
                                        }
                                        sessionStore.clearAccount()
                                        nav = nav.copy(account = null, selectedSection = TvSection.HOME, screen = TvScreen.AUTH)
                                    },
                                    onBackHome = { nav = nav.copy(selectedSection = TvSection.HOME) }
                                )
                            }

                            TvScreen.DETAIL -> {
                                val item = nav.selectedItem
                                if (item != null) {
                                    TvDetailScreen(
                                        item = item,
                                        account = nav.account,
                                        onPlaySession = { session ->
                                            playBingeEpisode(nav, session, watchProgressStore, context) { updatedNav ->
                                                nav = updatedNav
                                            }
                                        },
                                        onOpenRecommendations = { recItem, _ ->
                                            nav = nav.copy(
                                                screen = TvScreen.DETAIL,
                                                selectedItem = recItem,
                                                playUrl = "",
                                                playTitle = "",
                                                bingeSession = null
                                            )
                                        },
                                        onReadNovel = { text, title ->
                                            nav = nav.copy(screen = TvScreen.READER, readerText = text, readerTitle = title)
                                        },
                                        onReadManga = { pages, title ->
                                            nav = nav.copy(screen = TvScreen.MANGA_VIEWER, mangaPages = pages, mangaTitle = title)
                                        },
                                        watchProgressStore = watchProgressStore,
                                        onBack = { goBack() }
                                    )
                                }
                            }

                            TvScreen.PLAYER -> {
                                val session = nav.bingeSession
                                val current = session?.current
                                val progressKey = "${nav.selectedItem?.id ?: nav.playUrl}::${nav.playTitle}"
                                TvPlayerScreen(
                                    streamUrl = current?.url.orEmpty().ifBlank { nav.playUrl },
                                    title = current?.chapter?.title?.let { "${session.item.title} - $it" }.orEmpty().ifBlank { nav.playTitle },
                                    account = nav.account,
                                    previewLimitMs = nav.playPreviewLimitMs,
                                    isEpisodic = nav.playerFromSection == TvSection.SPORTS || session?.current?.kind?.isEpisodic == true,
                                    resumePositionMs = watchProgressStore.loadResumeKey(progressKey),
                                    onProgress = { positionMs, durationMs ->
                                        watchProgressStore.save(progressKey, positionMs, durationMs)
                                    },
                                    bingeSession = session,
                                    isMovieEnded = session?.isMovieLike == true,
                                    serverName = session?.serverName,
                                    onNext = { advanceBinge(1) },
                                    onPrev = { advanceBinge(-1) },
                                    subtitlePath = nav.localSubtitlePath.ifBlank { null },
                                    onEnded = {
                                        if (session?.hasNext == true) {
                                            advanceBinge(1)
                                        } else {
                                            nav = nav.copy(playTitle = nav.playTitle, playUrl = nav.playUrl, bingeSession = null)
                                        }
                                    },
                                    onOpenRecommendations = { recItem ->
                                        nav = nav.copy(
                                            screen = TvScreen.DETAIL,
                                            selectedItem = recItem,
                                            playUrl = "",
                                            playTitle = "",
                                            bingeSession = null
                                        )
                                    },
                                    onUpgrade = {
                                        nav = nav.copy(
                                            screen = TvScreen.HOME,
                                            selectedSection = TvSection.YOU,
                                            playUrl = "",
                                            playTitle = "",
                                            bingeSession = null
                                        )
                                    },
                                    onBack = { goBack() }
                                )
                            }

                            TvScreen.EMBED_PLAYER -> {
                                val session = nav.bingeSession
                                val current = session?.current
                                val progressKey = "${nav.selectedItem?.id ?: nav.playUrl}::${nav.playTitle}"
                                TvEmbedPlayerScreen(
                                    embedUrl = current?.url.orEmpty().ifBlank { nav.playUrl },
                                    title = current?.chapter?.title?.let { "${session.item.title} - $it" }.orEmpty().ifBlank { nav.playTitle },
                                    account = nav.account,
                                    previewLimitMs = nav.playPreviewLimitMs,
                                    isEpisodic = nav.playerFromSection == TvSection.SPORTS || session?.current?.kind?.isEpisodic == true,
                                    resumePositionMs = watchProgressStore.loadResumeKey(progressKey),
                                    onProgress = { positionMs, durationMs ->
                                        watchProgressStore.save(progressKey, positionMs, durationMs)
                                    },
                                    bingeSession = session,
                                    isMovieEnded = session?.isMovieLike == true,
                                    serverName = session?.serverName,
                                    onNext = { advanceBinge(1) },
                                    onPrev = { advanceBinge(-1) },
                                    onEnded = {
                                        if (session?.hasNext == true) {
                                            advanceBinge(1)
                                        } else {
                                            nav = nav.copy(playTitle = nav.playTitle, playUrl = nav.playUrl, bingeSession = null)
                                        }
                                    },
                                    onOpenRecommendations = { recItem ->
                                        nav = nav.copy(
                                            screen = TvScreen.DETAIL,
                                            selectedItem = recItem,
                                            playUrl = "",
                                            playTitle = "",
                                            bingeSession = null
                                        )
                                    },
                                    onUpgrade = {
                                        nav = nav.copy(
                                            screen = TvScreen.HOME,
                                            selectedSection = TvSection.YOU,
                                            playUrl = "",
                                            playTitle = "",
                                            bingeSession = null
                                        )
                                    },
                                    onBack = { goBack() }
                                )
                            }

                            TvScreen.READER -> {
                                TvNovelReaderScreen(
                                    text = nav.readerText,
                                    title = nav.readerTitle,
                                    ttsEngine = ttsEngine,
                                    onBack = { goBack() }
                                )
                            }

                            TvScreen.MANGA_VIEWER -> {
                                TvMangaViewerScreen(
                                    pages = nav.mangaPages,
                                    title = nav.mangaTitle,
                                    onBack = { goBack() }
                                )
                            }

                            else -> {}
                        }
                    }
                }
            }
        }

        // ── In-app update (Android TV APK channel) ─────────────────────────
        // The TV APK update URL is the PERMANENT channel in AppReleaseConfig —
        // never change it for a routine release. tvApkSha256/tvApkBytes in the
        // manifest are verified before install (skipped while empty = you haven't
        // supplied the hash/size for the new build yet). iOS has NO auto-update:
        // its button only opens the ipaUrl for manual download.
        if (updateProgress.isActive) {
            AlertDialog(
                onDismissRequest = { if (updateProgress.canDismiss) AppUpdateProgressBus.clear() },
                title = {
                    Text(
                        when (updateProgress.phase) {
                            AppUpdatePhase.Downloading -> "Downloading update"
                            AppUpdatePhase.Verifying -> "Verifying update"
                            AppUpdatePhase.ReadyToInstall -> "Preparing install"
                            AppUpdatePhase.Installing -> "Finish install"
                            AppUpdatePhase.Error -> "Update failed"
                            AppUpdatePhase.Idle -> "Update"
                        }
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(updateProgress.message.ifBlank { "Preparing update..." })
                        val f = updateProgress.fraction
                        if (f != null) {
                            LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = NeonBlue)
                            Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = TvSubtext)
                        } else if (!updateProgress.canDismiss) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = NeonBlue)
                        }
                    }
                },
                confirmButton = {
                    if (updateProgress.canDismiss) {
                        Button(onClick = { AppUpdateProgressBus.clear() }) { Text(if (updateProgress.phase == AppUpdatePhase.Installing) "Done" else "Close") }
                    }
                }
            )
        } else {
            startupUpdateManifest?.takeIf { !isStartupUpdateDismissed }?.let { u ->
                AlertDialog(
                    onDismissRequest = { if (!u.forceUpdate) isStartupUpdateDismissed = true },
                    title = { Text("Update available") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("NovaRead TV ${u.versionNameFor(AppUpdateTarget.ANDROID_TV)} is ready to install.")
                            val tvNotes = u.releaseNotesFor(AppUpdateTarget.ANDROID_TV)
                            if (tvNotes.isNotEmpty()) {
                                Text(
                                    tvNotes.joinToString("\n") { "- $it" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TvSubtext
                                )
                            }
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                tvUpdateInstaller.start(
                                    context,
                                    url = u.tvApkUrl.ifBlank { AppUpdateTarget.ANDROID_TV.downloadUrl() },
                                    sha256 = u.tvApkSha256,
                                    bytes = u.tvApkBytes
                                )
                                if (!u.forceUpdate) isStartupUpdateDismissed = true
                            }
                        ) { Text("Install update") }
                    },
                    dismissButton = {
                        if (!u.forceUpdate) {
                            TextButton(onClick = { isStartupUpdateDismissed = true }) { Text("Later") }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TvSidebar(
    config: TvRemoteConfig,
    selectedSection: TvSection,
    onSectionSelected: (TvSection) -> Unit,
    account: SavedUserAccount?,
    onSignInClick: () -> Unit,
    onSignOut: () -> Unit
) {
    // Server-driven section list (order + labels editable via site/tv-config.json)
    val configuredSections = config.visibleSections()
    val sections = configuredSections.mapNotNull { section ->
        val tvSection = section.toSection()
        val icon = iconForSection(tvSection)
        if (icon != null) tvSection to (icon to section.label) else null
    }

    Column(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(Color(0xFF0A0A12))
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // App logo
        Icon(Icons.Default.AutoStories, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(36.dp).padding(bottom = 8.dp))

        // Sections
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            sections.forEach { (section, iconInfo) ->
                val (icon, label) = iconInfo
                val isSelected = selectedSection == section
                var isFocused by remember { mutableStateOf(false) }

                Surface(
                    onClick = { onSectionSelected(section) },
                    shape = RoundedCornerShape(10.dp),
                    color = when {
                        isSelected -> Color(0xFF00BFFF).copy(0.2f)
                        isFocused -> Color(0xFF1C1C2E)
                        else -> Color.Transparent
                    },
                    border = when {
                        isSelected -> BorderStroke(2.dp, Color(0xFF00BFFF))
                        isFocused -> BorderStroke(2.dp, Purple500)
                        else -> null
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .onFocusChanged { isFocused = it.isFocused }
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            icon,
                            label,
                            tint = if (isSelected) Color(0xFF00BFFF) else Color.White.copy(0.5f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }

        // Account section at bottom
        Spacer(Modifier.height(4.dp))

        if (account != null) {
            // User avatar
            var youFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = { onSectionSelected(TvSection.YOU) },
                shape = CircleShape,
                color = if (selectedSection == TvSection.YOU) Purple500.copy(0.3f)
                    else if (youFocused) Color(0xFF1C1C2E)
                    else Color(0xFF14141E),
                border = if (selectedSection == TvSection.YOU) BorderStroke(2.dp, Purple500)
                    else if (youFocused) BorderStroke(2.dp, Purple500)
                    else null,
                modifier = Modifier
                    .size(44.dp)
                    .onFocusChanged { youFocused = it.isFocused }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        account.username.take(1).uppercase(),
                        color = if (selectedSection == TvSection.YOU) Purple500 else Color.White.copy(0.7f),
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            if (account.isPremium) {
                Icon(
                    Icons.Default.Verified,
                    null,
                    tint = Purple500,
                    modifier = Modifier.size(12.dp).offset(y = (-6).dp)
                )
            }
        } else {
            // Sign in button
            var signInFocused by remember { mutableStateOf(false) }
            Surface(
                onClick = onSignInClick,
                shape = RoundedCornerShape(8.dp),
                color = if (signInFocused) Purple500.copy(0.3f) else Color.Transparent,
                border = if (signInFocused) BorderStroke(1.dp, Purple500) else null,
                modifier = Modifier
                    .size(44.dp)
                    .onFocusChanged { signInFocused = it.isFocused }
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Login, null, tint = Purple500, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

private fun iconForSection(section: TvSection): ImageVector? = when (section) {
    TvSection.HOME -> Icons.Default.Home
    TvSection.NOVELS -> Icons.Default.AutoStories
    TvSection.CREATION -> Icons.Default.Create
    TvSection.MANGA -> Icons.Default.Collections
    TvSection.COMICS -> Icons.Default.ImportContacts
    TvSection.ANIME -> Icons.Default.PlayCircle
    TvSection.DONGHUA -> Icons.Default.VideoLibrary
    TvSection.K_DRAMA -> Icons.Default.LiveTv
    TvSection.CARTOON -> Icons.Default.Animation
    TvSection.CLASSIC -> Icons.Default.Theaters
    TvSection.MOVIES -> Icons.Default.Movie
    TvSection.NOLLYWOOD -> Icons.Default.Flag
    TvSection.SPORTS -> Icons.Default.SportsSoccer
    TvSection.DOWNLOADS -> Icons.Default.Download
    TvSection.YOU -> Icons.Default.AccountCircle
}
