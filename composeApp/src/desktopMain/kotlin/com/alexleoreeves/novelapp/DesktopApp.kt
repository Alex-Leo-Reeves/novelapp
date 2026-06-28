package com.alexleoreeves.novelapp

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.alexleoreeves.novelapp.audio.GeminiTtsController
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.ui.*
import com.alexleoreeves.novelapp.ui.theme.*

// ─────────────────────────────────────────────────────────────────────────────
//  DesktopApp — Full-width PC layout with left sidebar navigation
// ─────────────────────────────────────────────────────────────────────────────
@Composable
fun DesktopApp() {
    val appTheme = remember { mutableStateOf(AppTheme.DARK) }
    var currentSection by remember { mutableStateOf(DesktopSection.DISCOVER) }
    var showSplash by remember { mutableStateOf(true) }

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

    val repository = remember {
        NovelSearchRepository(
            geminiApiKey = BuildKonfig.GEMINI_API_KEY,
            rapidApiKey = BuildKonfig.RAPID_API_KEY,
            rapidApiHost = BuildKonfig.RAPID_API_HOST
        )
    }
    val ttsController = remember { GeminiTtsController(BuildKonfig.GEMINI_API_KEY) }

    NovelAppTheme(appTheme = appTheme.value) {
        // Splash
        if (showSplash) {
            SplashScreen(onFinished = { showSplash = false })
            return@NovelAppTheme
        }

        // Full-screen immersive modes
        when {
            animeStreamUrl.value != null -> {
                AnimePlayerScreen(
                    streamUrl = animeStreamUrl.value!!,
                    episodeTitle = animeEpisodeTitle.value,
                    currentTheme = appTheme.value,
                    initialPositionMs = 0L,
                    onProgress = {},
                    onBack = { animeStreamUrl.value = null }
                )
                return@NovelAppTheme
            }
            selectedChapterUrl.value != null -> {
                if (selectedNovel.value?.isManga == true) {
                    MangaViewerScreen(
                        chapterUrl = selectedChapterUrl.value!!,
                        mangaTitle = selectedNovelTitle.value,
                        chapterTitle = selectedChapterTitle.value,
                        sourceName = selectedSourceName.value,
                        currentTheme = appTheme.value,
                        initialPageIndex = 0,
                        onProgress = {},
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
                        initialParagraphIndex = 0,
                        onProgress = {},
                        onBack = { selectedChapterUrl.value = null }
                    )
                }
                return@NovelAppTheme
            }
        }

        // ── Main Two-Column Desktop Layout ─────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(appTheme.value.backgroundColor())
        ) {
            // ── Left Sidebar Navigation ─────────────────────────────────────
            DesktopSidebar(
                currentSection = currentSection,
                currentTheme = appTheme.value,
                ttsController = ttsController,
                onSectionChange = {
                    currentSection = it
                    selectedNovel.value = null
                    selectedAnime.value = null
                    selectedMedia.value = null
                },
                onThemeChange = { appTheme.value = it }
            )

            // ── Main Content Area ───────────────────────────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when {
                    selectedAnime.value != null -> AnimeDetailScreen(
                        anime = selectedAnime.value!!,
                        repository = repository,
                        currentTheme = appTheme.value,
                        downloadRepo = downloadRepo,
                        onPlayEpisode = { url, title -> animeStreamUrl.value = url; animeEpisodeTitle.value = title },
                        onBack = { selectedAnime.value = null }
                    )
                    selectedMedia.value != null -> MediaDetailScreen(
                        item = selectedMedia.value!!,
                        currentTheme = appTheme.value,
                        onBack = { selectedMedia.value = null }
                    )
                    selectedNovel.value != null -> NovelDetailScreen(
                        novel = selectedNovel.value!!,
                        currentTheme = appTheme.value,
                        isFavorite = favorites.any { it.id == selectedNovel.value!!.id },
                        downloadRepo = downloadRepo,
                        onToggleFavorite = { novel ->
                            val fav = favorites.find { it.id == novel.id }
                            if (fav != null) favorites.remove(fav)
                            else favorites.add(FavoriteNovel(id = novel.id, title = novel.title,
                                coverUrl = novel.coverUrl, detailPageUrl = novel.detailPageUrl,
                                sourceName = novel.sourceName, author = novel.author, genre = novel.genre,
                                addedAt = System.currentTimeMillis()))
                        },
                        onChapterSelected = { chapter ->
                            selectedNovelTitle.value = selectedNovel.value!!.title
                            selectedChapterTitle.value = chapter.title
                            selectedChapterUrl.value = chapter.url
                            selectedSourceName.value = selectedNovel.value!!.sourceName
                        },
                        onBack = { selectedNovel.value = null }
                    )
                    else -> when (currentSection) {
                        DesktopSection.DISCOVER -> DiscoverHomeScreen(
                            currentTheme = appTheme.value,
                            isDesktop = true,
                            onNovelSelected = { item ->
                                if (item.isAnime && item.animeResult != null) selectedAnime.value = item.animeResult
                                else if (item.isVideo) selectedMedia.value = item
                                else selectedNovel.value = item
                            }
                        )
                        DesktopSection.FAVORITES -> FavoritesScreen(
                            favorites = favorites,
                            currentTheme = appTheme.value,
                            onNovelSelected = { fav ->
                                selectedNovel.value = UnifiedSearchResult(id = fav.id, title = fav.title,
                                    coverUrl = fav.coverUrl, detailPageUrl = fav.detailPageUrl,
                                    sourceName = fav.sourceName, author = fav.author, genre = fav.genre)
                            },
                            onRemoveFavorite = { fav -> favorites.remove(fav) }
                        )
                        DesktopSection.READ -> UniversalReadScreen(currentTheme = appTheme.value)
                        DesktopSection.DOWNLOADS -> DownloadsScreen(
                            currentTheme = appTheme.value,
                            downloadRepo = downloadRepo,
                            onPlayEpisode = { path, title -> animeStreamUrl.value = path; animeEpisodeTitle.value = title },
                            onReadMangaChapter = { path, title -> selectedChapterUrl.value = path; selectedChapterTitle.value = title; selectedNovelTitle.value = title; selectedSourceName.value = "local" },
                            onReadNovelChapter = { path, title, src -> selectedChapterUrl.value = path; selectedChapterTitle.value = title; selectedNovelTitle.value = title; selectedSourceName.value = src }
                        )
                        DesktopSection.ABOUT -> DesktopAboutScreen(appTheme.value)
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Desktop Left Sidebar
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DesktopSidebar(
    currentSection: DesktopSection,
    currentTheme: AppTheme,
    ttsController: GeminiTtsController,
    onSectionChange: (DesktopSection) -> Unit,
    onThemeChange: (AppTheme) -> Unit
) {
    val isPlaying = ttsController.isPlaying.collectAsState()
    Column(
        modifier = Modifier
            .width(220.dp)
            .fillMaxHeight()
            .background(currentTheme.surfaceColor())
            .padding(vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // App logo/title
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFE040FB)))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.AutoStories, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Text(
                    "AniNovelManga",
                    style = MaterialTheme.typography.titleMedium,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Black
                )
            }

            Spacer(Modifier.height(20.dp))
            Divider(color = currentTheme.subTextColor().copy(0.12f))
            Spacer(Modifier.height(8.dp))

            // Nav items
            DesktopSection.values().forEach { section ->
                SidebarItem(
                    icon = section.icon,
                    label = section.label,
                    selected = currentSection == section,
                    currentTheme = currentTheme,
                    onClick = { onSectionChange(section) }
                )
            }
        }

        // Bottom: playing indicator + theme switcher + credit
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (isPlaying.value) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = currentTheme.accentColor().copy(0.15f),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.VolumeUp, null, tint = currentTheme.accentColor(), modifier = Modifier.size(16.dp))
                        Text("Narration playing", style = MaterialTheme.typography.labelSmall, color = currentTheme.accentColor())
                    }
                }
            }
            Divider(color = currentTheme.subTextColor().copy(0.12f))
            Spacer(Modifier.height(10.dp))
            Text("Developed by", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor())
            Text("Mike A. (Alex Leo Reeves)", style = MaterialTheme.typography.labelSmall, color = currentTheme.subTextColor(), fontWeight = FontWeight.SemiBold)
            Text("masteralexleoreevesd1@gmail.com", style = MaterialTheme.typography.labelSmall, color = currentTheme.accentColor().copy(0.7f))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SidebarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    currentTheme: AppTheme,
    onClick: () -> Unit
) {
    val bg = if (selected) currentTheme.accentColor().copy(0.15f) else Color.Transparent
    val fgColor = if (selected) currentTheme.accentColor() else currentTheme.subTextColor()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, null, tint = fgColor, modifier = Modifier.size(20.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = fgColor,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  About Screen — desktop only
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun DesktopAboutScreen(currentTheme: AppTheme) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor()),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFFE040FB)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.AutoStories, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Text("Watch Anime · Read Novels · Read Manga",
                style = MaterialTheme.typography.headlineMedium, color = currentTheme.textColor(),
                fontWeight = FontWeight.Black)
            Text("— All in One —", style = MaterialTheme.typography.titleMedium, color = currentTheme.subTextColor())
            Spacer(Modifier.height(20.dp))
            Text("Version 1.0.0", style = MaterialTheme.typography.bodyMedium, color = currentTheme.subTextColor())
            Text("Developed by Mike A. (Alex Leo Reeves)",
                style = MaterialTheme.typography.bodyMedium, color = currentTheme.textColor(), fontWeight = FontWeight.SemiBold)
            Text("masteralexleoreevesd1@gmail.com",
                style = MaterialTheme.typography.bodyMedium, color = currentTheme.accentColor())
        }
    }
}

enum class DesktopSection(val label: String, val icon: ImageVector) {
    DISCOVER("Discover", Icons.Default.Home),
    FAVORITES("Favorites", Icons.Default.Favorite),
    READ("Read", Icons.Default.MenuBook),
    DOWNLOADS("Downloads", Icons.Default.Download),
    ABOUT("About", Icons.Default.Info)
}
