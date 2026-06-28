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
import com.alexleoreeves.novelapp.audio.GeminiTtsController
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.EmptyUserSessionStore
import com.alexleoreeves.novelapp.platform.ExternalLinkOpener
import com.alexleoreeves.novelapp.platform.NoOpExternalLinkOpener
import com.alexleoreeves.novelapp.platform.PlatformBackHandler
import com.alexleoreeves.novelapp.platform.SavedUserAccount
import com.alexleoreeves.novelapp.platform.UserSessionStore
import com.alexleoreeves.novelapp.platform.currentTimeMillis
import com.alexleoreeves.novelapp.ui.*
import com.alexleoreeves.novelapp.ui.components.MiniPlayerWidget
import com.alexleoreeves.novelapp.ui.theme.NovelAppTheme
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
import com.alexleoreeves.novelapp.ui.theme.surfaceColor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────────
//  Root composable — entry point for shared KMP UI
// ─────────────────────────────────────────────────────────────────────────────
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
    var isAuthSubmitting by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }
    // Shown as an overlay when a guest tries to use a gated action
    var showAuthSheet by remember { mutableStateOf(false) }
    val authApi = remember { AuthApi() }

    // App state
    val favorites = remember { mutableStateListOf<FavoriteNovel>() }
    val downloadRepo = remember { LocalDownloadRepository() }
    val selectedNovel = remember { mutableStateOf<UnifiedSearchResult?>(null) }
    val selectedMedia = remember { mutableStateOf<UnifiedSearchResult?>(null) }
    val selectedChapterUrl = remember { mutableStateOf<String?>(null) }
    val selectedChapterTitle = remember { mutableStateOf("") }
    val selectedNovelTitle = remember { mutableStateOf("") }
    val selectedSourceName = remember { mutableStateOf("") }

    // Anime navigation state
    val selectedAnime = remember { mutableStateOf<AnimeResult?>(null) }
    val animeStreamUrl = remember { mutableStateOf<String?>(null) }
    val animeEpisodeTitle = remember { mutableStateOf("") }
    val animeEpisodeNumber = remember { mutableStateOf(0) }

    val repository = remember {
        NovelSearchRepository(
            geminiApiKey = BuildKonfig.GEMINI_API_KEY,
            rapidApiKey = BuildKonfig.RAPID_API_KEY,
            rapidApiHost = BuildKonfig.RAPID_API_HOST
        )
    }

    val ttsController = remember {
        GeminiTtsController(BuildKonfig.GEMINI_API_KEY)
    }

    LaunchedEffect(Unit) {
        val savedAccount = userSessionStore.loadAccount()
        if (savedAccount == null) {
            isAuthChecked = true
            return@LaunchedEffect
        }

        runCatching { authApi.me(savedAccount.authToken) }
            .onSuccess { verifiedAccount ->
                userSessionStore.saveAccount(verifiedAccount)
                account = verifiedAccount
            }
            .onFailure {
                userSessionStore.clearAccount()
                account = null
            }
        isAuthChecked = true
    }

    // Helper: run [action] if the user has an account, otherwise show the auth sheet.
    val requireAuth: (() -> Unit) -> Unit = { action ->
        if (account != null) action() else showAuthSheet = true
    }

    NovelAppTheme(appTheme = appTheme.value) {
        // ── Splash Screen ───────────────────────────────────────────────────
        if (showSplash) {
            SplashScreen(onFinished = { showSplash = false })
            return@NovelAppTheme
        }

        if (!isAuthChecked) {
            AuthLoadingScreen(
                currentTheme = appTheme.value,
                message = "Checking your saved account..."
            )
            return@NovelAppTheme
        }

        // ── No hard wall — guests proceed to main app.
        // Auth sheet is shown as an overlay when they try to use gated content.

        fun openReadHistory(item: ReadHistoryItem) {
            selectedNovel.value = UnifiedSearchResult(
                id = item.parentId,
                title = item.title,
                coverUrl = item.coverUrl,
                detailPageUrl = item.chapterUrl,
                sourceName = item.sourceName,
                isManga = item.isManga
            )
            selectedNovelTitle.value = item.title
            selectedChapterTitle.value = item.chapterTitle
            selectedChapterUrl.value = item.chapterUrl
            selectedSourceName.value = item.sourceName
        }

        fun openWatchHistory(item: WatchHistoryItem) {
            selectedAnime.value = null
            animeStreamUrl.value = item.streamUrl
            animeEpisodeTitle.value = item.episodeTitle
            animeEpisodeNumber.value = item.episodeNumber
        }

        val canNavigateBack =
            animeStreamUrl.value != null ||
                selectedChapterUrl.value != null ||
                selectedAnime.value != null ||
                selectedMedia.value != null ||
                selectedNovel.value != null ||
                currentTab.value != BottomTab.DISCOVER

        PlatformBackHandler(enabled = canNavigateBack) {
            when {
                animeStreamUrl.value != null -> animeStreamUrl.value = null
                selectedChapterUrl.value != null -> selectedChapterUrl.value = null
                selectedAnime.value != null -> selectedAnime.value = null
                selectedMedia.value != null -> selectedMedia.value = null
                selectedNovel.value != null -> selectedNovel.value = null
                currentTab.value != BottomTab.DISCOVER -> currentTab.value = BottomTab.DISCOVER
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(appTheme.value.backgroundColor())
        ) {
            when {
                // ── 1. Anime full-screen player ────────────────────────────
                animeStreamUrl.value != null -> {
                    AnimePlayerScreen(
                        streamUrl = animeStreamUrl.value!!,
                        episodeTitle = animeEpisodeTitle.value,
                        currentTheme = appTheme.value,
                        initialPositionMs = downloadRepo.getWatchProgress(animeStreamUrl.value!!)?.positionMs ?: 0L,
                        onProgress = { positionMs ->
                            val anime = selectedAnime.value
                            downloadRepo.recordWatchProgress(
                                WatchHistoryItem(
                                    parentId = anime?.id ?: animeStreamUrl.value!!,
                                    title = anime?.displayTitle ?: animeEpisodeTitle.value.substringBefore(" - EP").substringBefore(" – EP").ifBlank { animeEpisodeTitle.value },
                                    coverUrl = anime?.coverUrl.orEmpty(),
                                    episodeTitle = animeEpisodeTitle.value,
                                    streamUrl = animeStreamUrl.value!!,
                                    episodeNumber = animeEpisodeNumber.value,
                                    positionMs = positionMs
                                )
                            )
                        },
                        onBack = { animeStreamUrl.value = null }
                    )
                }

                // ── 2. Anime detail screen ─────────────────────────────────
                selectedAnime.value != null -> {
                    AnimeDetailScreen(
                        anime = selectedAnime.value!!,
                        repository = repository,
                        currentTheme = appTheme.value,
                        downloadRepo = downloadRepo,
                        onPlayEpisode = { streamUrl, epTitle ->
                            animeStreamUrl.value = streamUrl
                            animeEpisodeTitle.value = epTitle
                            animeEpisodeNumber.value = epTitle.substringAfter("EP ", "0").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                        },
                        onBack = { selectedAnime.value = null },
                        requireAuth = requireAuth
                    )
                }

                // ── 3. Chapter / manga viewer ──────────────────────────────
                selectedChapterUrl.value != null -> {
                    if (selectedNovel.value?.isManga == true) {
                        MangaViewerScreen(
                            chapterUrl = selectedChapterUrl.value!!,
                            mangaTitle = selectedNovelTitle.value,
                            chapterTitle = selectedChapterTitle.value,
                            sourceName = selectedSourceName.value,
                            currentTheme = appTheme.value,
                            initialPageIndex = downloadRepo.getReadProgress(selectedChapterUrl.value!!)?.positionIndex ?: 0,
                            onProgress = { pageIndex ->
                                selectedNovel.value?.let { item ->
                                    downloadRepo.recordReadProgress(
                                        ReadHistoryItem(
                                            parentId = item.id,
                                            title = selectedNovelTitle.value,
                                            coverUrl = item.coverUrl,
                                            sourceName = selectedSourceName.value,
                                            chapterTitle = selectedChapterTitle.value,
                                            chapterUrl = selectedChapterUrl.value!!,
                                            isManga = true,
                                            positionIndex = pageIndex
                                        )
                                    )
                                }
                            },
                            onBack = { selectedChapterUrl.value = null }
                        )
                    } else {
                        ReaderScreen(
                            chapterUrl = selectedChapterUrl.value!!,
                            novelTitle = selectedNovelTitle.value,
                            chapterTitle = selectedChapterTitle.value,
                            sourceName = selectedSourceName.value,
                            currentTheme = appTheme.value,
                            onThemeChange = { appTheme.value = it },
                            initialParagraphIndex = downloadRepo.getReadProgress(selectedChapterUrl.value!!)?.positionIndex ?: 0,
                            onProgress = { paragraphIndex ->
                                selectedNovel.value?.let { item ->
                                    downloadRepo.recordReadProgress(
                                        ReadHistoryItem(
                                            parentId = item.id,
                                            title = selectedNovelTitle.value,
                                            coverUrl = item.coverUrl,
                                            sourceName = selectedSourceName.value,
                                            chapterTitle = selectedChapterTitle.value,
                                            chapterUrl = selectedChapterUrl.value!!,
                                            isManga = false,
                                            positionIndex = paragraphIndex
                                        )
                                    )
                                }
                            },
                            onBack = { selectedChapterUrl.value = null }
                        )
                    }
                }

                // ── 4. TMDB media detail screen ───────────────────────────
                selectedMedia.value != null -> {
                    MediaDetailScreen(
                        item = selectedMedia.value!!,
                        currentTheme = appTheme.value,
                        onBack = { selectedMedia.value = null }
                    )
                }

                // ── 5. Novel detail screen ─────────────────────────────────
                selectedNovel.value != null -> {
                    NovelDetailScreen(
                        novel = selectedNovel.value!!,
                        currentTheme = appTheme.value,
                        isFavorite = favorites.any { it.id == selectedNovel.value!!.id },
                        downloadRepo = downloadRepo,
                        onToggleFavorite = { novel ->
                            val fav = favorites.find { it.id == novel.id }
                            if (fav != null) favorites.remove(fav)
                            else favorites.add(
                                FavoriteNovel(
                                    id = novel.id,
                                    title = novel.title,
                                    coverUrl = novel.coverUrl,
                                    detailPageUrl = novel.detailPageUrl,
                                    sourceName = novel.sourceName,
                                    author = novel.author,
                                    genre = novel.genre,
                                    addedAt = currentTimeMillis()
                                )
                            )
                        },
                        onChapterSelected = { chapter ->
                            selectedNovelTitle.value = selectedNovel.value!!.title
                            selectedChapterTitle.value = chapter.title
                            selectedChapterUrl.value = chapter.url
                            selectedSourceName.value = selectedNovel.value!!.sourceName
                        },
                        onBack = { selectedNovel.value = null },
                        requireAuth = requireAuth
                    )
                }

                // ── 6. Main tabbed dashboard ───────────────────────────────
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            when (currentTab.value) {
                                BottomTab.DISCOVER -> DiscoverHomeScreen(
                                    currentTheme = appTheme.value,
                                    onNovelSelected = { item ->
                                        if (item.isAnime && item.animeResult != null) {
                                            selectedAnime.value = item.animeResult
                                        } else if (item.isVideo) {
                                            selectedMedia.value = item
                                        } else {
                                            selectedNovel.value = item
                                        }
                                    }
                                )
                                BottomTab.FAVORITES -> FavoritesScreen(
                                    favorites = favorites,
                                    currentTheme = appTheme.value,
                                    onNovelSelected = { fav ->
                                        requireAuth {
                                            selectedNovel.value = UnifiedSearchResult(
                                                id = fav.id,
                                                title = fav.title,
                                                coverUrl = fav.coverUrl,
                                                detailPageUrl = fav.detailPageUrl,
                                                sourceName = fav.sourceName,
                                                author = fav.author,
                                                genre = fav.genre
                                            )
                                        }
                                    },
                                    onRemoveFavorite = { fav -> favorites.remove(fav) }
                                )
                                BottomTab.READ -> UniversalReadScreen(
                                    currentTheme = appTheme.value,
                                    requireAuth = requireAuth
                                )
                                BottomTab.YOU -> {
                                    if (account == null) {
                                        // Guest tapped You tab — show auth sheet, stay on Discover
                                        showAuthSheet = true
                                        currentTab.value = BottomTab.DISCOVER
                                    } else {
                                        YouScreen(
                                            account = account!!,
                                            currentTheme = appTheme.value,
                                            downloadRepo = downloadRepo,
                                            linkOpener = linkOpener,
                                            onPlayEpisode = { path, title ->
                                                selectedAnime.value = null
                                                animeStreamUrl.value = path
                                                animeEpisodeTitle.value = title
                                                animeEpisodeNumber.value = title.substringAfter("EP ", "0").takeWhile { it.isDigit() }.toIntOrNull() ?: 0
                                            },
                                            onReadMangaChapter = { path, title ->
                                                selectedNovel.value = UnifiedSearchResult(
                                                    id = path,
                                                    title = title,
                                                    coverUrl = "",
                                                    detailPageUrl = path,
                                                    sourceName = "local",
                                                    isManga = true
                                                )
                                                selectedChapterUrl.value = path
                                                selectedChapterTitle.value = title
                                                selectedNovelTitle.value = title
                                                selectedSourceName.value = "local"
                                            },
                                            onReadNovelChapter = { path, title, source ->
                                                selectedNovel.value = UnifiedSearchResult(
                                                    id = path,
                                                    title = title,
                                                    coverUrl = "",
                                                    detailPageUrl = path,
                                                    sourceName = source
                                                )
                                                selectedChapterUrl.value = path
                                                selectedChapterTitle.value = title
                                                selectedNovelTitle.value = title
                                                selectedSourceName.value = source
                                            },
                                            onResumeRead = { item -> openReadHistory(item) },
                                            onResumeWatch = { item -> openWatchHistory(item) },
                                            onSignOut = {
                                                scope.launch {
                                                    account?.authToken?.let { token ->
                                                        runCatching { authApi.logout(token) }
                                                    }
                                                    userSessionStore.clearAccount()
                                                    account = null
                                                    currentTab.value = BottomTab.DISCOVER
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        NovelBottomNav(
                            currentTab = currentTab.value,
                            onTabSelected = { currentTab.value = it },
                            currentTheme = appTheme.value
                        )
                    }
                }
            }

            // ── Floating Draggable Mini-Player (novels/manga only) ──────────
            val isPlaying = ttsController.isPlaying.collectAsState()
            if (isPlaying.value && animeStreamUrl.value == null) {
                var isExpanded by remember { mutableStateOf(true) }
                var offsetX by remember { mutableStateOf(60f) }
                var offsetY by remember { mutableStateOf(450f) }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragEnd = {
                                    if (offsetX < 200f) { offsetX = 10f; isExpanded = false }
                                    else { offsetX = 550f; isExpanded = false }
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                offsetX = (offsetX + dragAmount.x).coerceIn(0f, 800f)
                                offsetY = (offsetY + dragAmount.y).coerceIn(0f, 1500f)
                            }
                        }
                ) {
                    MiniPlayerWidget(
                        novelTitle = selectedNovelTitle.value.ifEmpty { "Universal Read" },
                        chapterTitle = selectedChapterTitle.value.ifEmpty { "Narration active..." },
                        isPlaying = isPlaying.value,
                        currentTheme = appTheme.value,
                        isExpanded = isExpanded,
                        onToggleExpand = { isExpanded = !isExpanded },
                        onPlay = { ttsController.resume() },
                        onPause = { ttsController.pause() },
                        onSkipBack = { ttsController.skipBack() },
                        onSkipForward = { ttsController.skipForward() }
                    )
                }
            }
        }

        // ── Auth overlay sheet (shown when guest tries a gated action) ────────
        if (showAuthSheet) {
            AuthScreen(
                currentTheme = appTheme.value,
                isSubmitting = isAuthSubmitting,
                errorMessage = authError,
                onClearError = { authError = null },
                onDismiss = { showAuthSheet = false },
                onSignIn = { email, password ->
                    scope.launch {
                        isAuthSubmitting = true
                        authError = null
                        runCatching { authApi.login(email, password) }
                            .onSuccess { signedIn ->
                                userSessionStore.saveAccount(signedIn)
                                account = signedIn
                                showAuthSheet = false
                            }
                            .onFailure { authError = it.message ?: "Sign in failed." }
                        isAuthSubmitting = false
                    }
                },
                onCreateAccount = { username, email, password ->
                    scope.launch {
                        isAuthSubmitting = true
                        authError = null
                        runCatching { authApi.register(username, email, password) }
                            .onSuccess { created ->
                                userSessionStore.saveAccount(created)
                                account = created
                                showAuthSheet = false
                            }
                            .onFailure { authError = it.message ?: "Account creation failed." }
                        isAuthSubmitting = false
                    }
                }
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Bottom Navigation
// ─────────────────────────────────────────────────────────────────────────────
enum class BottomTab(val label: String, val icon: ImageVector) {
    DISCOVER("Discover", Icons.Default.Home),
    FAVORITES("Favorites", Icons.Default.Favorite),
    READ("Read", Icons.Default.MenuBook),
    YOU("You", Icons.Default.Person)
}

@Composable
fun NovelBottomNav(
    currentTab: BottomTab,
    onTabSelected: (BottomTab) -> Unit,
    currentTheme: AppTheme
) {
    NavigationBar(
        containerColor = currentTheme.surfaceColor(),
        contentColor = currentTheme.accentColor()
    ) {
        BottomTab.values().forEach { tab ->
            NavigationBarItem(
                selected = currentTab == tab,
                onClick = { onTabSelected(tab) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = currentTheme.accentColor(),
                    selectedTextColor = currentTheme.accentColor(),
                    indicatorColor = currentTheme.accentColor().copy(alpha = 0.15f),
                    unselectedIconColor = currentTheme.subTextColor(),
                    unselectedTextColor = currentTheme.subTextColor()
                )
            )
        }
    }
}
