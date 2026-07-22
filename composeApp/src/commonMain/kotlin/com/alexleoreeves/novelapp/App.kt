package com.alexleoreeves.novelapp

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.alexleoreeves.novelapp.audio.SherpaNarrationController
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.*
import com.alexleoreeves.novelapp.ui.*
import com.alexleoreeves.novelapp.ui.components.MiniPlayerWidget
import com.alexleoreeves.novelapp.ui.theme.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Composable
fun App(
    userSessionStore: UserSessionStore = EmptyUserSessionStore,
    linkOpener: ExternalLinkOpener = NoOpExternalLinkOpener
) {
    val appTheme = remember { mutableStateOf(AppTheme.DARK) }
    val currentTab = remember { mutableStateOf(BottomTab.DISCOVER) }
    var showSplash by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf<SavedUserAccount?>(null) }
    var isAuthChecked by remember { mutableStateOf(false) }
    var isGuestSession by remember { mutableStateOf(false) }
    var isAuthSubmitting by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    var showAuthSheet by remember { mutableStateOf(false) }
    var startupUpdateManifest by remember { mutableStateOf<AppUpdateManifest?>(null) }
    var isStartupUpdateDismissed by remember { mutableStateOf(false) }
    var cloudSyncPulse by remember { mutableStateOf(0) }
    var hasHydratedCloudState by remember { mutableStateOf(false) }
    var searchHistoryPulse by remember { mutableStateOf(0) }
    var subscriptionMessage by remember { mutableStateOf<String?>(null) }
    val authApi = remember { AuthApi() }

    val favorites = remember { mutableStateListOf<FavoriteNovel>() }
    val downloadRepo = remember { LocalDownloadRepository() }
    val selectedNovel = remember { mutableStateOf<UnifiedSearchResult?>(null) }
    val selectedMedia = remember { mutableStateOf<UnifiedSearchResult?>(null) }
    val selectedChapterUrl = remember { mutableStateOf<String?>(null) }
    val selectedChapterTitle = remember { mutableStateOf("") }
    val selectedNovelTitle = remember { mutableStateOf("") }
    val selectedSourceName = remember { mutableStateOf("") }

    val selectedAnime = remember { mutableStateOf<AnimeResult?>(null) }
    val animeStreamUrl = remember { mutableStateOf<String?>(null) }
    val animeEpisodeTitle = remember { mutableStateOf("") }
    val animeEpisodeNumber = remember { mutableStateOf(0) }
    val animePreviewLimitMs = remember { mutableStateOf<Long?>(null) }
    val animeContentKind = remember { mutableStateOf("") }
    val animeSubtitlesJson = remember { mutableStateOf<String?>(null) }

    val maServerEmbedUrl = remember { mutableStateOf<String?>(null) }
    val maServerEmbedTitle = remember { mutableStateOf("") }

    val selectedFootballMatch = remember { mutableStateOf<FootballMatch?>(null) }
    val footballStreamUrl = remember { mutableStateOf<String?>(null) }
    val footballStreamTitle = remember { mutableStateOf("") }
    val selectedWweEvent = remember { mutableStateOf<WweEvent?>(null) }
    val wweStreamUrl = remember { mutableStateOf<String?>(null) }
    val wweStreamTitle = remember { mutableStateOf("") }

    val youtubeVideoId = remember { mutableStateOf<String?>(null) }
    val youtubeVideoTitle = remember { mutableStateOf("") }

    val repository = remember {
        NovelSearchRepository(
            rapidApiKey = BuildKonfig.RAPID_API_KEY,
            rapidApiHost = BuildKonfig.RAPID_API_HOST
        )
    }

    val ttsController = remember { SherpaNarrationController() }
    val narrationSettings = ttsController.settings.collectAsState()
    val isNarrationPlaying = ttsController.isPlaying.collectAsState()
    val keepNarrationAlive by rememberUpdatedState(
        narrationSettings.value.backgroundPlaybackEnabled && isNarrationPlaying.value
    )
    val updateClient = remember {
        platformHttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }
    val updateProgress by AppUpdateProgressBus.state.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            if (!keepNarrationAlive) ttsController.close()
            updateClient.close()
        }
    }

    fun mergeFavorites(remote: List<FavoriteNovel>) {
        val merged = (favorites + remote)
            .filter { it.id.isNotBlank() }
            .groupBy { it.id }.values
            .mapNotNull { g -> g.maxByOrNull { it.addedAt } }
            .sortedByDescending { it.addedAt }
        favorites.clear(); favorites.addAll(merged)
    }

    suspend fun hydrateCloudState(token: String) {
        runCatching { authApi.getUserState(token) }
            .onSuccess { s -> downloadRepo.mergeUserState(s); mergeFavorites(s.favorites) }
        hasHydratedCloudState = true
    }

    fun queueCloudSync() {
        if (account != null && hasHydratedCloudState) cloudSyncPulse += 1
    }

    fun beginPremiumCheckout(planId: String = "premium_3_devices") {
        val a = account ?: run { showAuthSheet = true; return }
        scope.launch {
            subscriptionMessage = "Starting subscription checkout..."
            runCatching { authApi.createBillingCheckout(a.authToken, planId) }
                .onSuccess { ch ->
                    if (ch.alreadyPremium || ch.premium) {
                        runCatching { authApi.billingStatus(a.authToken) }
                            .onSuccess { s -> account = s.account; userSessionStore.saveAccount(s.account) }
                        subscriptionMessage = "This plan is already active."
                    } else if (ch.link.isNotBlank()) {
                        linkOpener.open(ch.link)
                        subscriptionMessage = "Complete the Flutterwave checkout, then reopen the app to refresh your plan."
                    } else subscriptionMessage = "Checkout link was not returned."
                }
                .onFailure { subscriptionMessage = it.message ?: "Could not start subscription." }
        }
    }

    LaunchedEffect(account?.authToken, cloudSyncPulse) {
        val t = account?.authToken ?: return@LaunchedEffect
        if (!hasHydratedCloudState || cloudSyncPulse == 0) return@LaunchedEffect
        delay(1200)
        runCatching { authApi.putUserState(t, downloadRepo.exportUserState(favorites.toList())) }
            .onSuccess { s -> downloadRepo.mergeUserState(s); mergeFavorites(s.favorites); searchHistoryPulse++ }
    }

    LaunchedEffect(Unit) {
        val saved = userSessionStore.loadAccount()
        if (saved == null) { isGuestSession = false; isAuthChecked = true; return@LaunchedEffect }
        runCatching { authApi.me(saved.authToken) }
            .onSuccess { v -> userSessionStore.saveAccount(v); account = v; hydrateCloudState(v.authToken); queueCloudSync() }
            .onFailure {
                if ((it as? AuthApiException)?.statusCode in listOf(401, 403)) {
                    userSessionStore.clearAccount(); account = null; hasHydratedCloudState = false
                } else { account = saved; hasHydratedCloudState = true; authError = "Offline mode" }
            }
        isAuthChecked = true
    }

    LaunchedEffect(showSplash, isAuthChecked) {
        if (!showSplash && isAuthChecked) {
            startupUpdateManifest = fetchAppUpdateManifest(updateClient)?.takeIf { it.isAvailable }
        }
    }

    val requireAuth: (() -> Unit) -> Unit = { act ->
        if (account != null) act() else showAuthSheet = true
    }

    fun openReadHistory(item: ReadHistoryItem) {
        selectedNovel.value = UnifiedSearchResult(
            id = item.parentId, title = item.title, coverUrl = item.coverUrl,
            detailPageUrl = item.chapterUrl, sourceName = item.sourceName, isManga = item.isManga
        )
        selectedNovelTitle.value = item.title; selectedChapterTitle.value = item.chapterTitle
        selectedChapterUrl.value = item.chapterUrl; selectedSourceName.value = item.sourceName
    }

    fun openWatchHistory(item: WatchHistoryItem) {
        selectedAnime.value = null; animeStreamUrl.value = item.streamUrl
        animeEpisodeTitle.value = item.episodeTitle; animeEpisodeNumber.value = item.episodeNumber
        animePreviewLimitMs.value = null
        animeContentKind.value = if (item.contentType == ContentType.ANIME) "anime"
            else if (item.mediaKind.uppercase() == "DONGHUA") "donghua" else ""
    }

    val canNavigateBack = listOf(
        youtubeVideoId.value, animeStreamUrl.value, maServerEmbedUrl.value, footballStreamUrl.value,
        selectedFootballMatch.value, wweStreamUrl.value, selectedWweEvent.value, selectedChapterUrl.value,
        selectedAnime.value, selectedMedia.value, selectedNovel.value
    ).any { it != null } || currentTab.value != BottomTab.DISCOVER

    PlatformBackHandler(enabled = canNavigateBack) {
        when {
            youtubeVideoId.value != null -> { youtubeVideoId.value = null; youtubeVideoTitle.value = "" }
            maServerEmbedUrl.value != null -> { maServerEmbedUrl.value = null; maServerEmbedTitle.value = "" }
            animeStreamUrl.value != null -> { animeStreamUrl.value = null; animePreviewLimitMs.value = null }
            footballStreamUrl.value != null -> { footballStreamUrl.value = null; footballStreamTitle.value = "" }
            selectedFootballMatch.value != null -> selectedFootballMatch.value = null
            wweStreamUrl.value != null -> { wweStreamUrl.value = null; wweStreamTitle.value = "" }
            selectedWweEvent.value != null -> selectedWweEvent.value = null
            selectedChapterUrl.value != null -> selectedChapterUrl.value = null
            selectedAnime.value != null -> selectedAnime.value = null
            selectedMedia.value != null -> selectedMedia.value = null
            selectedNovel.value != null -> selectedNovel.value = null
            currentTab.value != BottomTab.DISCOVER -> currentTab.value = BottomTab.DISCOVER
        }
    }

    NovelAppTheme(appTheme = appTheme.value) {
        if (showSplash) { SplashScreen(onFinished = { showSplash = false }); return@NovelAppTheme }
        if (!isAuthChecked) { AuthLoadingScreen(currentTheme = appTheme.value, message = "Checking your saved account..."); return@NovelAppTheme }
        if (account == null && !isGuestSession) {
            AuthScreen(
                currentTheme = appTheme.value, isSubmitting = isAuthSubmitting, errorMessage = authError,
                onClearError = { authError = null },
                onDismiss = { authError = null; isGuestSession = true; currentTab.value = BottomTab.DISCOVER },
                onSignIn = { e, p -> scope.launch {
                    isAuthSubmitting = true; authError = null
                    runCatching { authApi.login(e, p) }.onSuccess { u ->
                        userSessionStore.saveAccount(u); account = u; isGuestSession = false
                        hydrateCloudState(u.authToken); queueCloudSync(); isAuthSubmitting = false
                    }.onFailure { authError = it.message; isAuthSubmitting = false }
                }},
                onCreateAccount = { un, e, p, rs -> scope.launch {
                    isAuthSubmitting = true; authError = null
                    runCatching { authApi.register(un, e, p, rs) }.onSuccess { u ->
                        userSessionStore.saveAccount(u); account = u; isGuestSession = false
                        hydrateCloudState(u.authToken); queueCloudSync(); isAuthSubmitting = false
                    }.onFailure { authError = it.message; isAuthSubmitting = false }
                }},
                onRecoverAccount = { rs, np -> scope.launch {
                    isAuthSubmitting = true; authError = null
                    runCatching { val r = authApi.recoverAccount(rs); authApi.resetPassword(r.authToken, np) }
                        .onSuccess { r ->
                            userSessionStore.saveAccount(r); account = r; isGuestSession = false
                            hydrateCloudState(r.authToken); queueCloudSync(); isAuthSubmitting = false
                        }.onFailure { authError = it.message; isAuthSubmitting = false }
                }}
            )
            return@NovelAppTheme
        }

        Box(modifier = Modifier.fillMaxSize()) {
            GlassBackground()

            when {
                footballStreamUrl.value != null -> MaServerPlayerScreen(footballStreamUrl.value!!, footballStreamTitle.value, appTheme.value) { footballStreamUrl.value = null; footballStreamTitle.value = "" }
                selectedFootballMatch.value != null -> FootballMatchScreen(selectedFootballMatch.value!!, appTheme.value, { u, t -> footballStreamUrl.value = u; footballStreamTitle.value = t }) { selectedFootballMatch.value = null }
                wweStreamUrl.value != null -> MaServerPlayerScreen(wweStreamUrl.value!!, wweStreamTitle.value, appTheme.value) { wweStreamUrl.value = null; wweStreamTitle.value = "" }
                selectedWweEvent.value != null -> WweMatchScreen(selectedWweEvent.value!!, appTheme.value, { u, t -> wweStreamUrl.value = u; wweStreamTitle.value = t }) { selectedWweEvent.value = null }

                animeStreamUrl.value != null -> AnimePlayerScreen(
                    animeStreamUrl.value!!, animeEpisodeTitle.value, appTheme.value,
                    initialPositionMs = downloadRepo.getWatchProgress(animeStreamUrl.value!!)?.positionMs ?: 0L,
                    onProgress = { ms ->
                        val a = selectedAnime.value
                        downloadRepo.recordWatchProgress(WatchHistoryItem(parentId = a?.id ?: animeStreamUrl.value!!, title = a?.displayTitle ?: animeEpisodeTitle.value, coverUrl = a?.coverUrl.orEmpty(), episodeTitle = animeEpisodeTitle.value, streamUrl = animeStreamUrl.value!!, episodeNumber = animeEpisodeNumber.value, positionMs = ms))
                        queueCloudSync()
                    },
                    previewLimitMs = animePreviewLimitMs.value,
                    onPreviewFinished = { animeStreamUrl.value = null; animePreviewLimitMs.value = null; subscriptionMessage = "Free preview ended." },
                    contentKind = animeContentKind.value, subtitlesJson = animeSubtitlesJson.value,
                    onBack = { animeStreamUrl.value = null; animePreviewLimitMs.value = null }
                )

                selectedAnime.value != null -> AnimeDetailScreen(selectedAnime.value!!, repository, appTheme.value, downloadRepo,
                    isPremium = account?.isPremium == true,
                    { u, t -> animeStreamUrl.value = u; animeEpisodeTitle.value = t; animeEpisodeNumber.value = t.substringAfter("EP ", "0").takeWhile { it.isDigit() }.toIntOrNull() ?: 0; animePreviewLimitMs.value = null; animeContentKind.value = "anime" },
                    { selectedAnime.value = null }, requireAuth)

                selectedChapterUrl.value != null -> {
                    if (selectedNovel.value?.isManga == true || selectedNovel.value?.isComic == true) MangaViewerScreen(
                        selectedChapterUrl.value!!, selectedNovelTitle.value, selectedChapterTitle.value, selectedSourceName.value, appTheme.value, ttsController,
                        initialPageIndex = downloadRepo.getReadProgress(selectedChapterUrl.value!!)?.positionIndex ?: 0,
                        onProgress = { i -> selectedNovel.value?.let { n -> downloadRepo.recordReadProgress(ReadHistoryItem(parentId = n.id, title = selectedNovelTitle.value, coverUrl = n.coverUrl, sourceName = selectedSourceName.value, chapterTitle = selectedChapterTitle.value, chapterUrl = selectedChapterUrl.value!!, isManga = true, positionIndex = i)); queueCloudSync() } },
                        onBack = { selectedChapterUrl.value = null }
                    )
                    else ReaderScreen(
                        selectedChapterUrl.value!!, selectedNovelTitle.value, selectedChapterTitle.value, selectedSourceName.value, appTheme.value, ttsController,
                        onThemeChange = { appTheme.value = it },
                        initialParagraphIndex = downloadRepo.getReadProgress(selectedChapterUrl.value!!)?.positionIndex ?: 0,
                        onProgress = { i -> selectedNovel.value?.let { n -> downloadRepo.recordReadProgress(ReadHistoryItem(parentId = n.id, title = selectedNovelTitle.value, coverUrl = n.coverUrl, sourceName = selectedSourceName.value, chapterTitle = selectedChapterTitle.value, chapterUrl = selectedChapterUrl.value!!, isManga = false, positionIndex = i)); queueCloudSync() } },
                        onBack = { selectedChapterUrl.value = null }
                    )
                }

                maServerEmbedUrl.value != null -> MaServerPlayerScreen(maServerEmbedUrl.value!!, maServerEmbedTitle.value, appTheme.value) { maServerEmbedUrl.value = null; maServerEmbedTitle.value = "" }

                selectedMedia.value != null -> MediaDetailScreen(
                    selectedMedia.value!!, appTheme.value, isPremium = account?.isPremium == true, downloadRepo, requireAuth,
                    onSubscribe = { beginPremiumCheckout("premium_3_devices") },
                    onPlayStream = { u, t, l, sj ->
                        animeStreamUrl.value = u; animeEpisodeTitle.value = t; animePreviewLimitMs.value = l; animeSubtitlesJson.value = sj
                        animeContentKind.value = selectedMedia.value?.let { i -> if (i.mediaKind.equals(VideoCategory.ANIME.name, true)) "anime" else if (i.mediaKind.equals(VideoCategory.DONGHUA.name, true)) "donghua" else "" } ?: ""
                    },
                    onPlayMaEmbed = { u, t -> maServerEmbedUrl.value = u; maServerEmbedTitle.value = t },
                    onBack = { selectedMedia.value = null }
                )

                selectedNovel.value != null -> NovelDetailScreen(
                    selectedNovel.value!!, appTheme.value, isFavorite = favorites.any { it.id == selectedNovel.value!!.id }, downloadRepo,
                    onToggleFavorite = { n ->
                        val f = favorites.find { it.id == n.id }
                        if (f != null) favorites.remove(f) else favorites.add(FavoriteNovel(id = n.id, title = n.title, coverUrl = n.coverUrl, detailPageUrl = n.detailPageUrl, sourceName = n.sourceName, author = n.author, genre = n.genre, addedAt = currentTimeMillis()))
                        queueCloudSync()
                    },
                    onChapterSelected = { ch ->
                        selectedNovelTitle.value = selectedNovel.value!!.title; selectedChapterTitle.value = ch.title; selectedChapterUrl.value = ch.url; selectedSourceName.value = selectedNovel.value!!.sourceName
                    },
                    onBack = { selectedNovel.value = null }, requireAuth, ttsController
                )

                youtubeVideoId.value != null -> YouTubePlayerScreen(youtubeVideoId.value!!, youtubeVideoTitle.value, appTheme.value) { youtubeVideoId.value = null; youtubeVideoTitle.value = "" }

                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            when (currentTab.value) {
                                BottomTab.DISCOVER -> DiscoverHomeScreen(
                                    currentTheme = appTheme.value, downloadRepo = downloadRepo,
                                    onNovelSelected = { i ->
                                        if (i.isVideo && i.mediaKind == VideoCategory.NIGERIAN.name) {
                                            val vid = i.id.removePrefix("youtube_nollywood_").takeIf { it != i.id && it.isNotBlank() }
                                            if (vid != null) { youtubeVideoId.value = vid; youtubeVideoTitle.value = i.title }
                                        } else if (i.isAnime && i.animeResult != null) selectedAnime.value = i.animeResult
                                        else if (i.isVideo) selectedMedia.value = i
                                        else selectedNovel.value = i
                                    },
                                    onSearchHistorySaved = { t, q -> downloadRepo.recordSearchQuery(t, q); searchHistoryPulse++; queueCloudSync() }
                                )
                                BottomTab.NMC -> NmcHomeScreen(
                                    currentTheme = appTheme.value, repository = repository, downloadRepo = downloadRepo,
                                    favorites = favorites.toList(),
                                    onNovelSelected = { selectedNovel.value = it },
                                    onChapterSelected = { ch ->
                                        selectedNovelTitle.value = selectedNovel.value?.title ?: ""
                                        selectedChapterTitle.value = ch.title; selectedChapterUrl.value = ch.url; selectedSourceName.value = selectedNovel.value?.sourceName ?: ""
                                    },
                                    onToggleFavorite = { n ->
                                        val f = favorites.find { it.id == n.id }
                                        if (f != null) favorites.remove(f) else favorites.add(FavoriteNovel(id = n.id, title = n.title, coverUrl = n.coverUrl, detailPageUrl = n.detailPageUrl, sourceName = n.sourceName, author = n.author, genre = n.genre, addedAt = currentTimeMillis()))
                                        queueCloudSync()
                                    },
                                    onSearchHistorySaved = { t, q -> downloadRepo.recordSearchQuery(t, q); searchHistoryPulse++; queueCloudSync() }
                                )
                                BottomTab.SPORTS -> SportsHomeScreen(appTheme.value,
                                    { selectedFootballMatch.value = it }, { selectedWweEvent.value = it })
                                BottomTab.READ -> UniversalReadScreen(
                                    currentTheme = appTheme.value, ttsController = ttsController,
                                    requireAuth = requireAuth, account = account, downloadRepo = downloadRepo,
                                    favorites = favorites.toList(),
                                    onSubscribePlan = { planId -> beginPremiumCheckout(planId) }
                                )
                                BottomTab.YOU -> {
                                    if (account == null) { showAuthSheet = true; currentTab.value = BottomTab.DISCOVER }
                                    else YouScreen(
                                        account = account!!, currentTheme = appTheme.value, downloadRepo = downloadRepo, linkOpener = linkOpener, ttsController = ttsController,
                                        favorites = favorites.toList(),
                                        onPlayEpisode = { p, t -> selectedAnime.value = null; animeStreamUrl.value = p; animeEpisodeTitle.value = t; animeEpisodeNumber.value = t.substringAfter("EP ", "0").takeWhile { it.isDigit() }.toIntOrNull() ?: 0; animePreviewLimitMs.value = null },
                                        onReadMangaChapter = { p, t -> selectedNovel.value = UnifiedSearchResult(id = p, title = t, coverUrl = "", detailPageUrl = p, sourceName = "local", isManga = true); selectedChapterUrl.value = p; selectedChapterTitle.value = t; selectedNovelTitle.value = t; selectedSourceName.value = "local" },
                                        onReadNovelChapter = { p, t, s -> selectedNovel.value = UnifiedSearchResult(id = p, title = t, coverUrl = "", detailPageUrl = p, sourceName = s); selectedChapterUrl.value = p; selectedChapterTitle.value = t; selectedNovelTitle.value = t; selectedSourceName.value = s },
                                        onResumeRead = { openReadHistory(it) },
                                        onResumeWatch = { openWatchHistory(it) },
                                        onToggleFavorite = { f ->
                                            val fav = favorites.find { it.id == f.id }
                                            if (fav != null) favorites.remove(fav) else favorites.add(f)
                                            queueCloudSync()
                                        },
                                        onSubscribePlan = { planId -> beginPremiumCheckout(planId) },
                                        onSignOut = {
                                            scope.launch {
                                                account?.authToken?.let { t -> runCatching { authApi.logout(t) } }
                                                userSessionStore.clearAccount(); account = null; isGuestSession = true; hasHydratedCloudState = false
                                                currentTab.value = BottomTab.DISCOVER
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        GlassBottomBar(currentTab = currentTab.value, onTabSelected = { currentTab.value = it })
                    }
                }
            }

            // Mini-player
            val playing = ttsController.isPlaying.collectAsState()
            if (playing.value && animeStreamUrl.value == null) {
                var expanded by remember { mutableStateOf(true) }
                var ox by remember { mutableStateOf(60f) }; var oy by remember { mutableStateOf(450f) }
                Box(Modifier.offset { IntOffset(ox.roundToInt(), oy.roundToInt()) }.pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { if (ox < 200f) { ox = 10f; expanded = false } else { ox = 550f; expanded = false } }) { ch, d -> ch.consume(); ox = (ox + d.x).coerceIn(0f, 800f); oy = (oy + d.y).coerceIn(0f, 1500f) }
                }) {
                    MiniPlayerWidget(selectedNovelTitle.value.ifEmpty { "Universal Read" }, selectedChapterTitle.value.ifEmpty { "Narration active..." }, playing.value, appTheme.value, expanded, { expanded = !expanded }, { ttsController.resume() }, { ttsController.pause() }, { ttsController.skipBack() }, { ttsController.skipForward() })
                }
            }
        }

        if (showAuthSheet) AuthScreen(
            currentTheme = appTheme.value, isSubmitting = isAuthSubmitting, errorMessage = authError,
            onClearError = { authError = null },
            onDismiss = { authError = null; isGuestSession = true; showAuthSheet = false },
            onSignIn = { e, p -> scope.launch {
                isAuthSubmitting = true; authError = null
                runCatching { authApi.login(e, p) }.onSuccess { u ->
                    userSessionStore.saveAccount(u); account = u; isGuestSession = false; showAuthSheet = false
                    hydrateCloudState(u.authToken); queueCloudSync(); isAuthSubmitting = false
                }.onFailure { authError = it.message; isAuthSubmitting = false }
            }},
            onCreateAccount = { un, e, p, rs -> scope.launch {
                isAuthSubmitting = true; authError = null
                runCatching { authApi.register(un, e, p, rs) }.onSuccess { u ->
                    userSessionStore.saveAccount(u); account = u; isGuestSession = false; showAuthSheet = false
                    hydrateCloudState(u.authToken); queueCloudSync(); isAuthSubmitting = false
                }.onFailure { authError = it.message; isAuthSubmitting = false }
            }},
            onRecoverAccount = { rs, np -> scope.launch {
                isAuthSubmitting = true; authError = null
                runCatching { val r = authApi.recoverAccount(rs); authApi.resetPassword(r.authToken, np) }
                    .onSuccess { r -> userSessionStore.saveAccount(r); account = r; isGuestSession = false; showAuthSheet = false
                        hydrateCloudState(r.authToken); queueCloudSync(); isAuthSubmitting = false }
                    .onFailure { authError = it.message; isAuthSubmitting = false }
            }}
        )

        subscriptionMessage?.let { m -> AlertDialog(onDismissRequest = { subscriptionMessage = null }, title = { Text("Premium") }, text = { Text(m) }, confirmButton = { Button(onClick = { beginPremiumCheckout("premium_3_devices") }) { Text("Subscribe") } }, dismissButton = { TextButton(onClick = { subscriptionMessage = null }) { Text("Close") } }) }

        if (updateProgress.isActive) AlertDialog(
            onDismissRequest = { if (updateProgress.canDismiss) AppUpdateProgressBus.clear() },
            icon = { Icon(if (updateProgress.isError) Icons.Default.ErrorOutline else Icons.Default.Download, null, tint = appTheme.value.accentColor()) },
            title = { Text(when (updateProgress.phase) { AppUpdatePhase.Downloading -> "Downloading update"; AppUpdatePhase.Verifying -> "Verifying update"; AppUpdatePhase.ReadyToInstall -> "Preparing install"; AppUpdatePhase.Installing -> "Finish install"; AppUpdatePhase.Error -> "Update failed"; AppUpdatePhase.Idle -> "Update" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(updateProgress.message.ifBlank { "Preparing update..." })
                    val f = updateProgress.fraction
                    if (f != null) { LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth(), color = appTheme.value.accentColor()); Text("${(f * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = appTheme.value.subTextColor()) }
                    else if (!updateProgress.canDismiss) LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = appTheme.value.accentColor())
                }
            },
            confirmButton = { if (updateProgress.canDismiss) Button(onClick = { AppUpdateProgressBus.clear() }) { Text(if (updateProgress.phase == AppUpdatePhase.Installing) "Done" else "Close") } }
        )
        else startupUpdateManifest?.takeIf { !isStartupUpdateDismissed && !showAuthSheet }?.let { u ->
            AlertDialog(
                onDismissRequest = { if (!u.forceUpdate) isStartupUpdateDismissed = true },
                icon = { Icon(Icons.Default.Download, null, tint = appTheme.value.accentColor()) },
                title = { Text("Update available") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("Version ${u.versionName} is ready to install."); if (u.releaseNotes.isNotEmpty()) Text(u.releaseNotes.joinToString("\n") { "- $it" }, style = MaterialTheme.typography.bodyMedium) } },
                confirmButton = { Button(onClick = { linkOpener.open(u.apkUrl.ifBlank { AppReleaseConfig.DOWNLOAD_URL }); if (!u.forceUpdate) isStartupUpdateDismissed = true }) { Text("Install update") } },
                dismissButton = { if (!u.forceUpdate) TextButton(onClick = { isStartupUpdateDismissed = true }) { Text("Later") } }
            )
        }
    }
}

enum class BottomTab(val label: String, val icon: ImageVector) {
    DISCOVER("Discover", Icons.Default.PlayCircle),
    NMC("NMC", Icons.Default.Book),
    SPORTS("Sports", Icons.Default.EmojiEvents),
    READ("Read", Icons.Default.MenuBook),
    YOU("You", Icons.Default.Person)
}
