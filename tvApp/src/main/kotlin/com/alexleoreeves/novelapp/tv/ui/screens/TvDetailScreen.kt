package com.alexleoreeves.novelapp.tv.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.tv.data.*
import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import com.alexleoreeves.novelapp.tv.platform.TvWatchProgressStore
import com.alexleoreeves.novelapp.tv.ui.theme.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import com.alexleoreeves.novelapp.data.mediacache.DownloadPhase
import com.alexleoreeves.novelapp.data.mediacache.MediaServerCandidate
import com.alexleoreeves.novelapp.tv.mediacache.TvMediaCacheController
import com.alexleoreeves.novelapp.tv.mediacache.UsbVolume
import androidx.activity.compose.BackHandler

@Composable
fun TvDetailScreen(
    item: UnifiedSearchResult,
    account: SavedUserAccount?,
    watchProgressStore: TvWatchProgressStore,
    mediaCache: TvMediaCacheController? = null,
    onPlaySession: (TvBingeSession) -> Unit,
    onOpenRecommendations: (recItem: UnifiedSearchResult, fromItem: UnifiedSearchResult?) -> Unit,
    onReadNovel: (text: String, title: String) -> Unit,
    onReadManga: (pages: List<String>, title: String) -> Unit,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    
    val initialFocusRequester = remember { FocusRequester() }
    val firstEpisodeFocusRequester = remember { FocusRequester() }

    val mediaRepo = remember { TvMediaRepository() }
    val novelRepo = remember { TvNovelSearchRepository() }

    val isVideoTitle = item.isAnime || item.isVideo
    val kind = if (item.isAnime) "anime"
        else if (item.isManga) "manga"
        else if (item.isComic) "comic"
        else if (item.isVideo) item.mediaKind.lowercase().ifBlank { "movie" }
        else "novel"
        
    val isDonghua = kind == "donghua" || item.genre.contains("Donghua", true) || item.sourceName.contains("Donghua", true)

    // Whether the header shows a single "Watch Now" (movie / full-movie) vs an
    // episode list. Hoisted here so BOTH the left panel (Download button) and
    // the right panel (per-episode download icons) can use it. The boolean
    // `isSingleContent` drives whether the Download button appears once in the
    // header (movie) or once per episode in the grid (series).
    val primaryChapter = chapters.firstOrNull()
    val isSingleContent = when {
        !isVideoTitle -> false
        primaryChapter?.url?.startsWith("tmdb-movie://", ignoreCase = true) == true -> true
        kind == "movie" || item.mediaKind.equals("movies", ignoreCase = true) -> true
        primaryChapter?.title?.equals("Full Movie", ignoreCase = true) == true -> true
        else -> false
    }
    val watchLabel = if (isSingleContent || chapters.isEmpty()) "Watch Now" else "Watch Episode 1"

    // ── Download state ────────────────────────────────────────────────────
    // The media cache engine notifies via StateFlow; we snapshot it so the
    // download buttons can show task progress + a "Downloaded" state once a
    // task COMPLETES (TvDownloadsScreen lists finished bundles). Live progress
    // is shown inline per-episode; cancel/pause is managed from Downloads.
    val cacheTasks by mediaCache?.tasks?.collectAsState() ?: remember { mutableStateOf(emptyMap()) }
    val completedTasks by remember(cacheTasks) {
        mutableStateOf(cacheTasks.values.filter { it.isTerminal && it.phase == DownloadPhase.COMPLETED }.map { it.request.taskId }.toSet())
    }

    var selectedServer by remember { mutableStateOf(StreamServer.VIDLINK) }
    var selectedDonghuaServer by remember { mutableStateOf(DonghuaServer.ANIMEXIN) }
    var selectedAnimeServer by remember { mutableStateOf(AnimeServer.ANINEKO) }
    // Tracks whether the user picked an AnimeServer chip for a donghua title.
    // When false (the default), donghua routes through AnimeXin (DonghuaServer
    // row) exactly as before; when true, donghua rides the 13 Anivexa providers /
    // Anivault trio / VidLink via selectedAnimeServer.
    var useAnimeServerForDonghua by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    // Anime misroute escape hatch: when an item flagged as anime returns no
    // episodes from ANY anime provider, fall back to the generic TV/movie
    // server path so live-action shows that got filtered into the anime tab
    // (e.g. Legend of the Seeker) still play on the movie/TV servers.
    var animeFallbackActive by remember { mutableStateOf(false) }

    // Quality selection dialog: shown before storage dialog when user clicks download
    var pendingQualityChapter by remember { mutableStateOf<Chapter?>(null) }
    val qualityOptions = listOf(
        "1080p" to "Full HD • largest file",
        "720p" to "HD • balanced quality & size",
        "480p" to "Standard • smallest file"
    )
    var selectedQuality by remember { mutableStateOf("720p") }

    // Storage destination dialog: set when a download is requested and USB is present
    val mountedVolumes by mediaCache?.volumes?.collectAsState() ?: remember { mutableStateOf<List<UsbVolume>>(emptyList()) }
    var pendingStorageChapter by remember { mutableStateOf<Chapter?>(null) }

    LaunchedEffect(item, selectedAnimeServer, selectedDonghuaServer, useAnimeServerForDonghua) {
        isLoading = true
        errorMsg = null
        statusText = ""
        animeFallbackActive = false
        try {
            val fetched = if (isVideoTitle) {
                if (isDonghua) {
                    // Donghua: when the user picked an AnimeServer chip
                    // (useAnimeServerForDonghua=true) ride that provider's episode
                    // list (13 Anivexa / Anivault trio / VidLink). Otherwise keep
                    // the original AnimeXin path (animeServer=null → default).
                    mediaRepo.fetchVideoEpisodes(
                        item,
                        if (useAnimeServerForDonghua) selectedAnimeServer else null
                    )
                } else if (item.isAnime) {
                    mediaRepo.fetchVideoEpisodes(item, selectedAnimeServer)
                } else {
                    mediaRepo.fetchVideoEpisodes(item)
                }
            } else if (kind == "novel") {
                novelRepo.fetchChapters(item.detailPageUrl.ifBlank { item.url }, item.sourceName)
            } else {
                fetchChapters(
                    if (item.isAnime) "anime" else kind,
                    item.detailPageUrl.ifBlank { item.url },
                    item.title,
                    item.sourceName
                )
            }
            chapters = fetched
            // Anime fallback: anime-tagged item with ZERO episodes → re-fetch via
            // the generic TV/movie path (TMDB / drama / etc). If that yields a
            // list, treat it as a live-action misroute and route playback through
            // the StreamServer (movie) row instead of the anime providers.
            if (item.isAnime && !isDonghua && fetched.isEmpty()) {
                val generic = mediaRepo.fetchVideoEpisodes(item)
                if (generic.isNotEmpty()) {
                    chapters = generic
                    animeFallbackActive = true
                }
            }
        } catch (e: Exception) {
            errorMsg = e.message
        }
        isLoading = false
        
        delay(100)
        try { 
            if (chapters.isNotEmpty()) {
                firstEpisodeFocusRequester.requestFocus() 
            } else {
                initialFocusRequester.requestFocus() 
            }
        } catch (e: Exception) {}
    }

    fun startDownloadToInternal(cache: TvMediaCacheController, ch: Chapter) {
        val title = ch.title.ifBlank { item.title }
        val taskId = "tv_${item.id}_${ch.chapterNumber}_${System.currentTimeMillis()}"
        val containerExtension = "mp4"
        statusText = "Downloading \"$title\"..."
        scope.launch {
            val sourceUrl = mediaRepo.resolveStreamUrl(
                item = item,
                chapter = ch,
                server = when {
                    animeFallbackActive -> selectedServer
                    isDonghua || item.isAnime -> null
                    else -> selectedServer
                },
                donghuaServer = if (isDonghua && !useAnimeServerForDonghua) selectedDonghuaServer else null,
                animeServer = if (item.isAnime && !animeFallbackActive) selectedAnimeServer
                    else if (isDonghua && useAnimeServerForDonghua) selectedAnimeServer
                    else null
            )
            if (sourceUrl.isNullOrBlank()) {
                statusText = "Could not resolve a download link. Try another server."
                return@launch
            }
            val derivedMediaType = when {
                isDonghua -> "DONGHUA"
                item.isAnime -> "ANIME"
                item.isVideo -> item.mediaKind.uppercase().ifBlank { "MOVIE" }
                item.isManga -> "MANGA"
                item.isComic -> "COMIC"
                else -> "NOVEL"
            }
            // Free-tier: single-content (movies) get 20% file cap via maxFraction.
            // Episode cap is already enforced in enqueueDownload().
            val effectiveMaxFraction = if (account?.isPremium != true && isSingleContent) 0.2f else 0f
            cache.enqueueInternal(
                taskId = taskId,
                sourceUrl = sourceUrl,
                title = title,
                parentId = item.id,
                episodeNumber = ch.chapterNumber,
                containerExtension = containerExtension,
                serverId = if (isDonghua) selectedDonghuaServer.name
                    else if (item.isAnime) selectedAnimeServer.name
                    else selectedServer.name,
                serverName = if (isDonghua) selectedDonghuaServer.displayName
                    else if (item.isAnime) selectedAnimeServer.displayName
                    else selectedServer.displayName,
                mediaType = derivedMediaType,
                seasonNumber = ch.seasonNumber,
                coverUrl = item.coverUrl,
                maxFraction = effectiveMaxFraction
            )
            statusText = "Download started — see Downloads (active queue)."
        }
    }

    fun startDownloadToUsb(cache: TvMediaCacheController, ch: Chapter, usbVolumeId: String, usbLabel: String) {
        val title = ch.title.ifBlank { item.title }
        val taskId = "tv_${item.id}_${ch.chapterNumber}_${System.currentTimeMillis()}"
        val containerExtension = "mp4"
        statusText = "Downloading \"$title\" to $usbLabel..."
        scope.launch {
            val sourceUrl = mediaRepo.resolveStreamUrl(
                item = item,
                chapter = ch,
                server = when {
                    animeFallbackActive -> selectedServer
                    isDonghua || item.isAnime -> null
                    else -> selectedServer
                },
                donghuaServer = if (isDonghua && !useAnimeServerForDonghua) selectedDonghuaServer else null,
                animeServer = if (item.isAnime && !animeFallbackActive) selectedAnimeServer
                    else if (isDonghua && useAnimeServerForDonghua) selectedAnimeServer
                    else null
            )
            if (sourceUrl.isNullOrBlank()) {
                statusText = "Could not resolve a download link. Try another server."
                return@launch
            }
            val ok = cache.enqueueUsb(
                taskId = taskId,
                sourceUrl = sourceUrl,
                title = title,
                parentId = item.id,
                episodeNumber = ch.chapterNumber,
                containerExtension = containerExtension,
                usbVolumeId = usbVolumeId,
                serverId = if (isDonghua) selectedDonghuaServer.name
                    else if (item.isAnime) selectedAnimeServer.name
                    else selectedServer.name,
                serverName = if (isDonghua) selectedDonghuaServer.displayName
                    else if (item.isAnime) selectedAnimeServer.displayName
                    else selectedServer.displayName
            )
            statusText = if (ok) "Download started — see Downloads (active queue)."
                else "USB drive was removed. Download to internal storage instead."
        }
    }

    /**
     * Enqueue an episode/movie for offline download through the media cache.
     * If a USB volume is mounted, shows a D-pad-friendly storage destination
     * dialog before enqueueing. Otherwise routes directly to internal storage.
     */
    /**
     * Enqueue an episode/movie for offline download.
     * Flow: Quality popup → (if USB) Storage dialog → Smart server probe → Download.
     */
    fun enqueueDownload(chapter: Chapter? = null) {
        val cache = mediaCache ?: run {
            statusText = "Downloads unavailable on this build."
            return
        }
        val ch = chapter ?: chapters.firstOrNull()
            ?: Chapter(item.title, item.detailPageUrl, 0)
        // Free-tier 20% episode cap: block if user already downloaded ≥20% of episodes
        if (account?.isPremium != true && chapters.size > 1) {
            val totalEpisodes = chapters.size
            val downloadedForTitle = cacheTasks.values.count {
                it.request.parentId == item.id &&
                    it.isTerminal &&
                    it.phase == DownloadPhase.COMPLETED
            }
            val maxAllowed = maxOf(1, totalEpisodes / 5) // 20%
            if (downloadedForTitle >= maxAllowed) {
                statusText = "Free tier: only $maxAllowed of $totalEpisodes episodes allowed. Go premium for unlimited."
                return
            }
        }
        // Show quality selection popup first
        pendingQualityChapter = ch
    }

    /** Called after quality is selected; routes to USB dialog or straight to download. */
    fun proceedAfterQuality(ch: Chapter) {
        val cache = mediaCache ?: return
        if (mountedVolumes.isNotEmpty()) {
            pendingStorageChapter = ch
        } else {
            startDownloadToInternal(cache, ch)
        }
    }

    fun playMedia(chapter: Chapter? = null) {
        scope.launch {
            statusText = "Resolving stream..."
            if (isVideoTitle) {
                // Build the full binge session: every episode for this title, all
                // routed through the SAME server the user just selected. The first
                // episode is resolved now; the rest resolve lazily on auto-next /
                // remote NEXT (see TvApp#playBingeEpisode) so anime/donghua
                // scrapers never hammer hundreds of episode pages up front.
                val chapterList = chapters.sortedBy { it.chapterNumber }
                val startChapter = chapter ?: chapterList.firstOrNull()
                    ?: Chapter(item.title, item.detailPageUrl, 0)
                val startIndex = chapterList.indexOfFirst { it.url == startChapter.url }
                    .coerceAtLeast(0)

                val isAnimeItem = item.isAnime && !isDonghua
                // Anime misroute escape hatch: when the anime-tagged title had no
                // episodes from any anime provider and fell back to the generic
                // TV/movie list, route playback through the movie/TV StreamServer
                // row (TMDB markers) — NOT the anime providers, which can't
                // resolve TMDB chapters. This is what makes misclassified
                // live-action shows (e.g. Legend of the Seeker) play normally.
                val effectiveAnimeServer = when {
                    animeFallbackActive -> null
                    isAnimeItem -> selectedAnimeServer
                    isDonghua && useAnimeServerForDonghua -> selectedAnimeServer
                    else -> null
                }
                // Anime (13 Anivexa providers + VidLink LAST) resolves through
                // animeServer; never map it to a generic StreamServer embed on TV.
                val effectiveStreamServer = when {
                    animeFallbackActive -> selectedServer
                    isDonghua || isAnimeItem -> null
                    else -> selectedServer
                }
                val effectiveDonghuaServer = if (isDonghua && !useAnimeServerForDonghua) selectedDonghuaServer else null

                val first = mediaRepo.resolveBingeEpisode(
                    context = context,
                    item = item,
                    chapter = startChapter,
                    server = effectiveStreamServer,
                    donghuaServer = effectiveDonghuaServer,
                    animeServer = effectiveAnimeServer,
                    isDonghua = isDonghua
                )
                if (first == null || first.url.isBlank()) {
                    statusText = "Stream unavailable. Try another server."
                    return@launch
                }

                val episodes = chapterList.mapIndexed { idx, ch ->
                    if (idx == startIndex) first
                    else TvBingeEpisode(chapter = ch, kind = deriveBingeKind(item, ch, isDonghua))
                }.ifEmpty { listOf(first) }

                val session = TvBingeSession(
                    item = item,
                    episodes = episodes,
                    serverName = when {
                        animeFallbackActive -> selectedServer.displayName
                        isAnimeItem -> selectedAnimeServer.displayName
                        isDonghua && useAnimeServerForDonghua -> selectedAnimeServer.displayName
                        isDonghua -> selectedDonghuaServer.displayName
                        else -> selectedServer.displayName
                    },
                    server = if (animeFallbackActive) selectedServer else if (isDonghua || isAnimeItem) null else selectedServer,
                    donghuaServer = effectiveDonghuaServer,
                    animeServer = effectiveAnimeServer,
                    currentIndex = startIndex,
                    isDonghua = isDonghua,
                    isPremium = account?.isPremium == true
                )
                statusText = ""
                onPlaySession(session)
            } else if (item.isManga || item.isComic) {
                val chUrl = chapter?.url ?: return@launch
                val pages = fetchMangaPages(chUrl)
                statusText = ""
                onReadManga(
                    pages.ifEmpty {
                        listOf(
                            "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=800",
                            "https://images.unsplash.com/photo-1607604276583-eef5d076aa5f?w=800"
                        )
                    },
                    "${item.title} - ${chapter.title}"
                )
            } else {
                val chUrl = chapter?.url ?: return@launch
                val novelText = if (kind == "novel") {
                    novelRepo.fetchChapterText(chUrl, item.sourceName)
                } else {
                    fetchChapterText(chUrl, item.title, item.sourceName)
                }
                statusText = ""
                onReadNovel(
                    novelText.ifBlank { "Loading chapter ${chapter.chapterNumber}…" },
                    "${item.title} - ${chapter.title}"
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF06060A))
    ) {
        Row(
            modifier = Modifier.fillMaxSize()
        ) {
            // Left panel
            Column(
                modifier = Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                var backFocused by remember { mutableStateOf(false) }
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(10.dp),
                    color = if (backFocused) Color(0xFF1C1C2E) else Color.Transparent,
                    border = if (backFocused) BorderStroke(2.dp, Purple500) else null,
                    modifier = Modifier.align(Alignment.Start)
                        .focusRequester(if (chapters.isEmpty()) initialFocusRequester else FocusRequester.Default)
                        .onFocusChanged { backFocused = it.isFocused }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                        Text("Back", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.width(240.dp).aspectRatio(0.7f)
                ) {
                    AsyncImage(
                        model = item.coverUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Text(
                    item.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                if (item.author.isNotBlank()) {
                    Text("by ${item.author}", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(0.6f))
                }

                if (item.genre.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item.genre.split(",").take(3).forEach { tag ->
                            Surface(color = Purple500.copy(0.2f), shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    tag.trim(), color = Purple500, style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                if (item.synopsis.isNotBlank()) {
                    Text(item.synopsis, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(0.65f), lineHeight = 20.sp)
                }

                Spacer(Modifier.height(8.dp))

                Surface(color = Color(0xFF14141E), shape = RoundedCornerShape(8.dp)) {
                    Text(
                        "Source: ${item.sourceName.ifBlank { "NovaRead" }}",
                        color = Color.White.copy(0.5f), style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }

                if (isVideoTitle) {
                    val primaryChapter = chapters.firstOrNull()
                    var watchFocused by remember { mutableStateOf(false) }
                    val isSingleDownload = isSingleContent ||
                        primaryChapter?.title?.equals("Full Movie", ignoreCase = true) == true
                    val movieDownloaded = remember(completedTasks) {
                        cacheTasks.values.any { it.request.parentId == item.id && it.isTerminal && it.phase == DownloadPhase.COMPLETED }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            onClick = { playMedia(chapters.firstOrNull()) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (watchFocused) Color(0xFF00BFFF) else Color(0xFF00BFFF).copy(0.15f),
                            border = if (watchFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.4f)),
                            modifier = Modifier.weight(1f).height(48.dp)
                                .onFocusChanged { watchFocused = it.isFocused }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    watchLabel,
                                    color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }

                        if (isSingleDownload && mediaCache != null) {
                            var downloadFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { enqueueDownload(primaryChapter) },
                                shape = RoundedCornerShape(10.dp),
                                color = if (downloadFocused) Color(0xFF06D6A0) else Color(0xFF06D6A0).copy(0.12f),
                                border = if (downloadFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0xFF06D6A0).copy(0.4f)),
                                modifier = Modifier.width(180.dp).height(48.dp)
                                    .onFocusChanged { downloadFocused = it.isFocused }
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        if (movieDownloaded) Icons.Default.OfflinePin else Icons.Default.Download,
                                        null,
                                        tint = if (movieDownloaded) Color(0xFF06D6A0) else Color.White,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (movieDownloaded) "Downloaded" else "Download",
                                        color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall
                                    )
                                }
                            }
                        }
                    }

                    // Resume button — appears when a chapter has saved watch progress
                    // (power loss / app kill / exit). Launches that exact episode; the
                    // player then auto-seeks to the saved position on load.
                    val resumeChapter = remember(chapters) {
                        chapters.firstOrNull { ch ->
                            watchProgressStore.loadResumeKey(resumeProgressKey(item, ch)) != null
                        }
                    }
                    val resumeMs = remember(resumeChapter) {
                        resumeChapter?.let { watchProgressStore.load(resumeProgressKey(item, it))?.positionMs } ?: 0L
                    }
                    if (resumeChapter != null && resumeMs > 0L) {
                        var resumeFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = { playMedia(resumeChapter) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (resumeFocused) Color(0xFF06D6A0) else Color(0xFF06D6A0).copy(0.15f),
                            border = if (resumeFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color(0xFF06D6A0).copy(0.5f)),
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                .onFocusChanged { resumeFocused = it.isFocused }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Default.Replay, null, tint = Color.White, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Resume ${formatTvClock(resumeMs)}",
                                    color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
                
                if (statusText.isNotBlank()) {
                    Text(statusText, color = Color(0xFF00BFFF), style = MaterialTheme.typography.bodySmall)
                }
            }

            // Divider
            Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(0.06f)))

            // Right panel
            Column(modifier = Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                Text(
                    when {
                        item.isAnime -> "Episodes"
                        item.isManga || item.isComic -> "Chapters"
                        item.isVideo && chapters.isNotEmpty() -> "Episodes"
                        item.isVideo -> "Media"
                        else -> "Chapters"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (isVideoTitle && !item.id.startsWith("youtube_nollywood_")) {
                    // Donghua shows TWO rows: the DonghuaServer row (AnimeXin
                    // device scraper, the original donghua path) AND the anime
                    // 17-server row (13 Anivexa providers + Anivault trio +
                    // VidLink LAST — the Android donghua parity selector).
                    if (isDonghua) {
                        // Row 1: DonghuaServer (AnimeXin) — device scraper default.
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            items(DonghuaServer.ALL_IN_ORDER) { server ->
                                val isSelected = selectedDonghuaServer == server
                                var sFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = {
                                        selectedDonghuaServer = server
                                        selectedAnimeServer = AnimeServer.ANINEKO
                                        // DonghuaServer row = AnimeXin device-scraper
                                        // path; clear the anime-server routing flag.
                                        useAnimeServerForDonghua = false
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) Color(0xFF00BFFF) else if (sFocused) Color(0xFF00BFFF).copy(0.3f) else Color(0xFF14141E),
                                    border = if (sFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.1f)),
                                    modifier = Modifier.height(36.dp).onFocusChanged { sFocused = it.isFocused }
                                ) {
                                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                        Text(server.displayName, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                        // Row 2: AnimeServer (13 Anivexa + 3 Anivault + VidLink).
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            items(AnimeServer.ALL_IN_ORDER) { server ->
                                val isSelected = selectedAnimeServer == server
                                var sFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = {
                                        selectedAnimeServer = server
                                        // Picking an anime server switches the active
                                        // route to the Anivexa/Anivault/VidLink path;
                                        // the DonghuaServer row remains available but is
                                        // no longer the driving selection.
                                        selectedDonghuaServer = DonghuaServer.ANIMEXIN
                                        // Donghua now rides the 13 Anivexa providers /
                                        // Anivault trio / VidLink via this chip.
                                        useAnimeServerForDonghua = true
                                    },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) Color(0xFF00BFFF) else if (sFocused) Color(0xFF00BFFF).copy(0.3f) else Color(0xFF14141E),
                                    border = if (sFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.1f)),
                                    modifier = Modifier.height(36.dp).onFocusChanged { sFocused = it.isFocused }
                                ) {
                                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                        Text(server.displayName, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    } else if (item.isAnime && !animeFallbackActive) {
                        // Anime uses its own 17-server list: 13 Anivexa-API
                        // providers (keyed by AniList ID) + 3 Anivault +
                        // VidLink LAST.
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            items(AnimeServer.ALL_IN_ORDER) { server ->
                                val isSelected = selectedAnimeServer == server
                                var sFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = { selectedAnimeServer = server },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) Color(0xFF00BFFF) else if (sFocused) Color(0xFF00BFFF).copy(0.3f) else Color(0xFF14141E),
                                    border = if (sFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.1f)),
                                    modifier = Modifier.height(36.dp).onFocusChanged { sFocused = it.isFocused }
                                ) {
                                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                        Text(server.displayName, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    } else {
                        // Normal TV/movie server row — ALSO shown when an anime
                        // misroute was detected (animeFallbackActive): the title
                        // had zero anime episodes and fell back to the generic
                        // TV/movie list, so these are the servers that can
                        // actually play it.
                        if (animeFallbackActive) {
                            Text(
                                "This title may not be anime — the app found no anime episodes. Try a server below.",
                                color = Color(0xFFFFB347),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        ) {
                            items(StreamServer.ALL_IN_ORDER) { server ->
                                val isSelected = selectedServer == server
                                var sFocused by remember { mutableStateOf(false) }
                                Surface(
                                    onClick = { selectedServer = server },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isSelected) Color(0xFF00BFFF) else if (sFocused) Color(0xFF00BFFF).copy(0.3f) else Color(0xFF14141E),
                                    border = if (sFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.1f)),
                                    modifier = Modifier.height(36.dp).onFocusChanged { sFocused = it.isFocused }
                                ) {
                                    Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                        Text(server.displayName, color = Color.White, style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                }

                val seasonsList = remember(chapters) {
                    chapters.map { it.seasonNumber.coerceAtLeast(1) }.distinct().sorted()
                }
                var selectedSeason by remember(seasonsList) {
                    mutableStateOf(seasonsList.firstOrNull() ?: 1)
                }

                if (seasonsList.size > 1) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        items(seasonsList) { seasonNum ->
                            val isSelected = selectedSeason == seasonNum
                            var sFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = { selectedSeason = seasonNum },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) Color(0xFF00BFFF) else if (sFocused) Color(0xFF00BFFF).copy(0.3f) else Color(0xFF14141E),
                                border = if (sFocused) BorderStroke(2.dp, Color.White) else BorderStroke(1.dp, Color.White.copy(0.1f)),
                                modifier = Modifier.height(36.dp).onFocusChanged { sFocused = it.isFocused }
                            ) {
                                Box(modifier = Modifier.fillMaxHeight().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                                    Text("Season $seasonNum", color = Color.White, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                }

                if (isLoading) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Purple500, modifier = Modifier.size(48.dp))
                    }
                } else if (chapters.isEmpty() && isVideoTitle && !item.isAnime) {
                     Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Movie, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(48.dp))
                            Text("Ready to watch", color = Color.White.copy(0.5f))
                        }
                    }
                } else if (chapters.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Info, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(48.dp))
                            Text("No chapters available", color = Color.White.copy(0.5f))
                        }
                    }
                } else {
                    val displayedEpisodes = remember(chapters, selectedSeason, seasonsList) {
                        if (seasonsList.size > 1) {
                            chapters.filter { it.seasonNumber.coerceAtLeast(1) == selectedSeason }.sortedBy { it.chapterNumber }
                        } else {
                            chapters.sortedWith(compareBy({ it.seasonNumber }, { it.chapterNumber }))
                        }
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 48.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = displayedEpisodes,
                            key = { index, ch -> if (ch.url.isNotBlank()) ch.url else "ep_${ch.seasonNumber}_${ch.chapterNumber}_$index" }
                        ) { index, ch ->
                            var chFocused by remember { mutableStateOf(false) }
                            val isSingle = watchLabel == "Watch Now"
                            val chDownloaded = remember(completedTasks) {
                                cacheTasks.values.any {
                                    it.request.parentId == item.id &&
                                        it.request.episodeNumber == ch.chapterNumber &&
                                        it.isTerminal && it.phase == DownloadPhase.COMPLETED
                                }
                            }
                            Surface(
                                onClick = { playMedia(ch) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (chFocused) Purple500.copy(0.3f) else Color(0xFF14141E),
                                border = if (chFocused) BorderStroke(2.dp, Purple500) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .then(if (index == 0) Modifier.focusRequester(firstEpisodeFocusRequester) else Modifier)
                                    .onFocusChanged { chFocused = it.isFocused }
                            ) {
                                Row(
                                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        ch.title.ifBlank { "Episode ${ch.chapterNumber}" },
                                        color = Color.White,
                                        fontWeight = if (chFocused) FontWeight.Bold else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (!isSingle && mediaCache != null) {
                                        if (chDownloaded) {
                                            Icon(
                                                Icons.Default.OfflinePin,
                                                null,
                                                tint = Color(0xFF06D6A0),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        } else if (ch.chapterNumber in cacheTasks.keys.mapNotNull { key ->
                                            cacheTasks[key]?.request?.episodeNumber?.takeIf { taskNum ->
                                                taskNum == ch.chapterNumber &&
                                                    cacheTasks[key]?.request?.parentId == item.id &&
                                                    !cacheTasks[key]!!.isTerminal
                                            }
                                        }.toSet()
                                        ) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(16.dp),
                                                strokeWidth = 2.dp,
                                                color = Color(0xFF00BFFF)
                                            )
                                        } else {
                                            Surface(
                                                onClick = { enqueueDownload(ch) },
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (chFocused) Color(0xFF06D6A0).copy(0.25f) else Color(0xFF06D6A0).copy(0.12f),
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                    Icon(
                                                        Icons.Default.Download,
                                                        null,
                                                        tint = if (chFocused) Color(0xFF06D6A0) else Color.White.copy(0.6f),
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (account?.isPremium != true) {
                    Spacer(Modifier.height(16.dp))
                    Surface(
                        color = Color(0xFF00BFFF).copy(0.1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFF00BFFF).copy(0.3f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Lock, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(18.dp))
                            Text("Some content may require premium", color = Color.White.copy(0.7f), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // ── Quality Selection Dialog ────────────────────────────────────────────
        val qualityChapter = pendingQualityChapter
        val cache = mediaCache
        if (qualityChapter != null && cache != null) {
            AlertDialog(
                onDismissRequest = { pendingQualityChapter = null },
                containerColor = Color(0xFF0E0E18),
                title = {
                    Column {
                        Text(
                            "Select Quality",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            qualityChapter.title.ifBlank { item.title },
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Choose the video quality for this download.",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        qualityOptions.forEach { (quality, description) ->
                            val isSelected = selectedQuality == quality
                            var qFocused by remember { mutableStateOf(false) }
                            Surface(
                                onClick = {
                                    selectedQuality = quality
                                    pendingQualityChapter = null
                                    proceedAfterQuality(qualityChapter)
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected || qFocused) Color(0xFF1C1C30) else Color(0xFF131320),
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) Color(0xFF00BFFF)
                                    else if (qFocused) Color(0xFF00BFFF).copy(0.5f)
                                    else Color.White.copy(0.12f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged { qFocused = it.isFocused }
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        if (quality == "1080p") Icons.Default.HighQuality
                                        else if (quality == "720p") Icons.Default.Hd
                                        else Icons.Default.Sd,
                                        null,
                                        tint = if (isSelected) Color(0xFF00BFFF) else Color.White.copy(0.6f),
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            quality,
                                            color = if (isSelected) Color(0xFF00BFFF) else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                        Text(
                                            description,
                                            color = Color.White.copy(alpha = 0.5f),
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                        // Free tier notice
                        if (account?.isPremium != true) {
                            Surface(
                                color = Color(0xFFFFB347).copy(0.12f),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, Color(0xFFFFB347).copy(0.3f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.Info, null, tint = Color(0xFFFFB347), modifier = Modifier.size(16.dp))
                                    Text(
                                        "Free users: limited to 20% of content. Go premium for full downloads.",
                                        color = Color(0xFFFFB347),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { pendingQualityChapter = null }) {
                        Text("Cancel", color = Color(0xFF00BFFF))
                    }
                }
            )
        }

        // ── USB Storage Selection Dialog ────────────────────────────────────────
        val chapterForStorage = pendingStorageChapter
        if (chapterForStorage != null && cache != null) {
            AlertDialog(
                onDismissRequest = { pendingStorageChapter = null },
                containerColor = Color(0xFF0E0E18),
                title = {
                    Column {
                        Text(
                            "Choose Download Location",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            chapterForStorage.title.ifBlank { item.title },
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "A USB drive is connected. Where would you like to save?",
                            color = Color.White.copy(alpha = 0.75f),
                            style = MaterialTheme.typography.bodyMedium
                        )

                        // Internal storage option
                        var internalFocused by remember { mutableStateOf(false) }
                        Surface(
                            onClick = {
                                pendingStorageChapter = null
                                startDownloadToInternal(cache, chapterForStorage)
                            },
                            shape = RoundedCornerShape(12.dp),
                            color = if (internalFocused) Color(0xFF1C1C30) else Color(0xFF131320),
                            border = BorderStroke(
                                1.dp,
                                if (internalFocused) Color(0xFF00BFFF) else Color.White.copy(0.12f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { internalFocused = it.isFocused }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(Icons.Default.PhoneAndroid, null, tint = Color(0xFF00BFFF), modifier = Modifier.size(28.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Internal Storage", color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    val freeInternal = remember {
                                        runCatching {
                                            val stat = android.os.StatFs(context.filesDir.absolutePath)
                                            val free = stat.availableBlocksLong * stat.blockSizeLong
                                            "%.1f GB free".format(free / (1024f * 1024f * 1024f))
                                        }.getOrDefault("Space unknown")
                                    }
                                    Text(freeInternal, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(18.dp))
                            }
                        }

                        // USB volume options
                        for (volume in mountedVolumes) {
                            UsbVolumeRow(
                                volume = volume,
                                onSelect = {
                                    pendingStorageChapter = null
                                    startDownloadToUsb(cache, chapterForStorage, volume.id, volume.label)
                                }
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { pendingStorageChapter = null }) {
                        Text("Cancel", color = Color(0xFF00BFFF))
                    }
                }
            )
        }
    }
}


/** Builds the exact watch-progress key the TvApp player uses for a chapter. */
private fun resumeProgressKey(item: UnifiedSearchResult, chapter: Chapter): String {
    val suffix = chapter.title.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""
    return "${item.id}::${item.title}$suffix"
}

private fun formatTvClock(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

@Composable
private fun UsbVolumeRow(
    volume: UsbVolume,
    onSelect: () -> Unit
) {
    var usbFocused by remember { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        shape = RoundedCornerShape(12.dp),
        color = if (usbFocused) Color(0xFF1A1C30) else Color(0xFF131320),
        border = BorderStroke(
            1.dp,
            if (usbFocused) Color(0xFF7B61FF) else Color.White.copy(0.12f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { usbFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(Icons.Default.Usb, null, tint = Color(0xFF7B61FF), modifier = Modifier.size(28.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    volume.label.ifBlank { "USB Drive" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                val freeUsb = remember(volume.id) {
                    runCatching {
                        val stat = android.os.StatFs(volume.root.absolutePath)
                        val free = stat.availableBlocksLong * stat.blockSizeLong
                        "%.1f GB free".format(free / (1024f * 1024f * 1024f))
                    }.getOrDefault("Space unknown")
                }
                Text(freeUsb, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(18.dp))
        }
    }
}
