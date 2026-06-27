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
import com.alexleoreeves.novelapp.platform.SavedUserAccount
import com.alexleoreeves.novelapp.platform.UserSessionStore
import com.alexleoreeves.novelapp.ui.*
import com.alexleoreeves.novelapp.ui.components.MiniPlayerWidget
import com.alexleoreeves.novelapp.ui.theme.NovelAppTheme
import com.alexleoreeves.novelapp.ui.theme.accentColor
import com.alexleoreeves.novelapp.ui.theme.backgroundColor
import com.alexleoreeves.novelapp.ui.theme.subTextColor
import com.alexleoreeves.novelapp.ui.theme.surfaceColor
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
    val currentTab = remember { mutableStateOf(BottomTab.NOVELS) }

    var splashComplete by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf(userSessionStore.loadAccount()) }
    var authError by remember { mutableStateOf<String?>(null) }

    // App state
    val favorites = remember { mutableStateListOf<FavoriteNovel>() }
    val selectedNovel = remember { mutableStateOf<UnifiedSearchResult?>(null) }
    val selectedChapterUrl = remember { mutableStateOf<String?>(null) }
    val selectedChapterTitle = remember { mutableStateOf("") }
    val selectedNovelTitle = remember { mutableStateOf("") }
    val selectedSourceName = remember { mutableStateOf("") }

    // ── Anime navigation state ──────────────────────────────────────────────
    val selectedAnime = remember { mutableStateOf<AnimeResult?>(null) }
    val animeStreamUrl = remember { mutableStateOf<String?>(null) }
    val animeEpisodeTitle = remember { mutableStateOf("") }

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

    NovelAppTheme(appTheme = appTheme.value) {
        val currentAccount = account
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(appTheme.value.backgroundColor())
        ) {
            when {
                !splashComplete -> {
                    OpeningSplashScreen(
                        currentTheme = appTheme.value,
                        onFinished = { splashComplete = true }
                    )
                }

                currentAccount == null -> {
                    AuthScreen(
                        currentTheme = appTheme.value,
                        errorMessage = authError,
                        onClearError = { authError = null },
                        onSignIn = { email, password ->
                            val stored = userSessionStore.loadAccount()
                            if (stored == null) {
                                val localAccount = SavedUserAccount(
                                    username = email.substringBefore("@").ifBlank { "Reader" },
                                    email = email,
                                    password = password
                                )
                                userSessionStore.saveAccount(localAccount)
                                account = localAccount
                                authError = null
                            } else if (stored.email.equals(email, ignoreCase = true) && stored.password == password) {
                                account = stored
                                authError = null
                            } else {
                                authError = "Email or password does not match this device account."
                            }
                        },
                        onCreateAccount = { username, email, password ->
                            val newAccount = SavedUserAccount(
                                username = username,
                                email = email,
                                password = password
                            )
                            userSessionStore.saveAccount(newAccount)
                            account = newAccount
                            authError = null
                        }
                    )
                }

                // ── 1. Anime full-screen player ────────────────────────────
                animeStreamUrl.value != null -> {
                    AnimePlayerScreen(
                        streamUrl = animeStreamUrl.value!!,
                        episodeTitle = animeEpisodeTitle.value,
                        currentTheme = appTheme.value,
                        onBack = { animeStreamUrl.value = null }
                    )
                }

                // ── 2. Anime detail screen ─────────────────────────────────
                selectedAnime.value != null -> {
                    AnimeDetailScreen(
                        anime = selectedAnime.value!!,
                        repository = repository,
                        currentTheme = appTheme.value,
                        onPlayEpisode = { streamUrl, epTitle ->
                            animeStreamUrl.value = streamUrl
                            animeEpisodeTitle.value = epTitle
                        },
                        onBack = { selectedAnime.value = null }
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
                            onBack = { selectedChapterUrl.value = null }
                        )
                    }
                }

                // ── 4. Novel detail screen ─────────────────────────────────
                selectedNovel.value != null -> {
                    NovelDetailScreen(
                        novel = selectedNovel.value!!,
                        currentTheme = appTheme.value,
                        isFavorite = favorites.any { it.id == selectedNovel.value!!.id },
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
                                    addedAt = System.currentTimeMillis()
                                )
                            )
                        },
                        onChapterSelected = { chapter ->
                            selectedNovelTitle.value = selectedNovel.value!!.title
                            selectedChapterTitle.value = chapter.title
                            selectedChapterUrl.value = chapter.url
                            selectedSourceName.value = selectedNovel.value!!.sourceName
                        },
                        onBack = { selectedNovel.value = null }
                    )
                }

                // ── 5. Main tabbed dashboard ───────────────────────────────
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Box(modifier = Modifier.weight(1f)) {
                            when (currentTab.value) {
                                BottomTab.NOVELS -> DiscoverHomeScreen(
                                    currentTheme = appTheme.value,
                                    contentTab = ContentTab.NOVELS,
                                    onNovelSelected = { item ->
                                        // Route anime items to detail screen
                                        if (item.isAnime && item.animeResult != null) {
                                            selectedAnime.value = item.animeResult
                                        } else {
                                            selectedNovel.value = item
                                        }
                                    }
                                )
                                BottomTab.MANGA -> DiscoverHomeScreen(
                                    currentTheme = appTheme.value,
                                    contentTab = ContentTab.MANGA,
                                    onNovelSelected = { item ->
                                        if (item.isAnime && item.animeResult != null) {
                                            selectedAnime.value = item.animeResult
                                        } else {
                                            selectedNovel.value = item
                                        }
                                    }
                                )
                                BottomTab.ANIME -> DiscoverHomeScreen(
                                    currentTheme = appTheme.value,
                                    contentTab = ContentTab.ANIME,
                                    onNovelSelected = { item ->
                                        if (item.isAnime && item.animeResult != null) {
                                            selectedAnime.value = item.animeResult
                                        } else {
                                            selectedNovel.value = item
                                        }
                                    }
                                )
                                BottomTab.FAVORITES -> FavoritesScreen(
                                    favorites = favorites,
                                    currentTheme = appTheme.value,
                                    onNovelSelected = { fav ->
                                        selectedNovel.value = UnifiedSearchResult(
                                            id = fav.id,
                                            title = fav.title,
                                            coverUrl = fav.coverUrl,
                                            detailPageUrl = fav.detailPageUrl,
                                            sourceName = fav.sourceName,
                                            author = fav.author,
                                            genre = fav.genre
                                        )
                                    },
                                    onRemoveFavorite = { fav -> favorites.remove(fav) }
                                )
                                BottomTab.READ -> UniversalReadScreen(
                                    currentTheme = appTheme.value
                                )
                                BottomTab.YOU -> YouScreen(
                                    account = currentAccount,
                                    currentTheme = appTheme.value,
                                    linkOpener = linkOpener,
                                    onSignOut = {
                                        userSessionStore.clearAccount()
                                        account = null
                                        currentTab.value = BottomTab.NOVELS
                                        selectedNovel.value = null
                                        selectedChapterUrl.value = null
                                        selectedAnime.value = null
                                        animeStreamUrl.value = null
                                    }
                                )
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
                                    if (offsetX < 200f) {
                                        offsetX = 10f; isExpanded = false
                                    } else {
                                        offsetX = 550f; isExpanded = false
                                    }
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
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Bottom Navigation
// ─────────────────────────────────────────────────────────────────────────────
enum class BottomTab(val label: String, val icon: ImageVector) {
    NOVELS("Novels", Icons.Default.AutoStories),
    MANGA("Manga", Icons.Default.Collections),
    ANIME("Anime", Icons.Default.PlayCircle),
    FAVORITES("Favorites", Icons.Default.Favorite),
    READ("Read", Icons.Default.MenuBook),
    YOU("You", Icons.Default.Info)
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
