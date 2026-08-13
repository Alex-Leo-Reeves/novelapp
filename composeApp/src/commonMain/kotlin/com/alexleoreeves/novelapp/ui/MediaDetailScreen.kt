package com.alexleoreeves.novelapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.alexleoreeves.novelapp.data.*
import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.ui.theme.*
import com.alexleoreeves.novelapp.platform.platformHttpClient
import kotlinx.coroutines.launch
import kotlin.math.ceil

@Composable
fun MediaDetailScreen(
    item: UnifiedSearchResult,
    currentTheme: AppTheme,
    isPremium: Boolean,
    downloadRepo: LocalDownloadRepository,
    requireAuth: (() -> Unit) -> Unit,
    onSubscribe: () -> Unit,
    onPlayStream: (streamUrl: String, title: String, previewLimitMs: Long?, subtitlesJson: String?) -> Unit,
    onPlayMaEmbed: (embedUrl: String, title: String) -> Unit = { _, _ -> },
    // Fail closed: if a caller only supplies onPlayMaEmbed and a preview limit
    // is set, refuse to play rather than drop the limit and bypass the gate.
    // When the limit is null (premium / no cap) there is nothing to bypass,
    // so delegate to onPlayMaEmbed as before.
    onPlayMaEmbedWithLimit: (embedUrl: String, title: String, previewLimitMs: Long?) -> Unit = { u, t, l ->
        if (l == null) onPlayMaEmbed(u, t)
        else println("MediaDetailScreen: onPlayMaEmbedWithLimit invoked without a limit-aware callback (url=$u, title=$t, limit=$l); refusing to play to avoid a preview bypass")
    },
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val httpClient = remember { platformHttpClient() }
    val tmdbScraper = remember { TMDBMovieScraper(httpClient) }
    val dramaScraper = remember { DramaCoolScraper(httpClient) }
    val cartoonScraper = remember { KimCartoonScraper(httpClient) }
    val wcoStreamScraper = remember { WcoStreamScraper(httpClient) }
    val youtubeNollywoodScraper = remember { YouTubeNollywoodScraper(httpClient) }
    val donghuaStreamScraper = remember { DonghuaSiteScraper.donghuaStream(httpClient) }
    val luciferDonghuaScraper = remember { DonghuaSiteScraper.luciferDonghua(httpClient) }
    val animeXinScraper = remember { AnimeXinScraper(httpClient) }
    val anivexaApi = remember { AnivexaApi(httpClient) }

    val parts = item.detailPageUrl.removePrefix("tmdb://").split("/")
    val mediaType = parts.getOrNull(0) ?: "movie"
    val tmdbId = parts.getOrNull(1) ?: ""

    var episodesList by remember { mutableStateOf<List<MediaEpisode>>(emptyList()) }
    var isLoadingEpisodes by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var providerTmdbId by remember(item.detailPageUrl) { mutableStateOf("") }
    var providerTmdbType by remember(item.detailPageUrl) { mutableStateOf("tv") }
    var providerAnilistId by remember(item.detailPageUrl) { mutableStateOf("") }

    var isMovieContent by remember(mediaType) { mutableStateOf(mediaType == "movie") }
    val isYouTubeNollywood = item.id.startsWith("youtube_nollywood_")

    val isDonghuaItem = item.mediaKind.equals(VideoCategory.DONGHUA.name, ignoreCase = true) ||
        item.genre.contains("Donghua", ignoreCase = true) ||
        item.sourceName.contains("Donghua", ignoreCase = true) ||
        item.id.contains("donghua", ignoreCase = true) ||
        item.title.contains("Renegade Immortal", ignoreCase = true) ||
        item.title.contains("Swallowed Star", ignoreCase = true) ||
        item.title.contains("Soul Land", ignoreCase = true) ||
        item.title.contains("Perfect World", ignoreCase = true) ||
        item.title.contains("Battle Through The Heavens", ignoreCase = true) ||
        item.title.contains("Shrouding the Heavens", ignoreCase = true) ||
        item.title.contains("Demon Hunter", ignoreCase = true) ||
        item.title.contains("Throne of Seal", ignoreCase = true) ||
        item.title.contains("A Will Eternal", ignoreCase = true) ||
        item.title.contains("Xian Ni", ignoreCase = true) ||
        item.title.contains("Stellar Transformation", ignoreCase = true) ||
        item.title.contains("Martial Universe", ignoreCase = true)
    // Content-aware anime detection: TMDB-sourced Japanese anime shows the
    // anime-only selector (13 Anivexa providers + VidLink LAST), never the
    // generic movie/TV server list. Donghua stays on its own DonghuaServer.
    val isAnimeItem = !isDonghuaItem && (
        item.mediaKind.equals(VideoCategory.ANIME.name, ignoreCase = true) ||
        item.isAnime ||
        item.genre.contains("Anime", ignoreCase = true) ||
        item.genre.contains("Japanese Animation", ignoreCase = true)
        )
    val isTmdbDetail = item.detailPageUrl.startsWith("tmdb://")
    val isDramaCoolDetail = item.detailPageUrl.contains("dramacool", ignoreCase = true)
    val isKimCartoonDetail = item.detailPageUrl.contains("kimcartoon", ignoreCase = true)
    val isWcoStreamDetail = item.sourceName == "WCOStream" || item.detailPageUrl.contains("wcostream", ignoreCase = true)

    // ── Server selector ──────────────────────────────────────────────
    // All 2 servers displayed inline. Default to Server 1 (VidLink).
    var selectedServer by remember { mutableStateOf(StreamServer.VIDLINK) }
    var selectedDonghuaServer by remember { mutableStateOf(DonghuaServer.DONGHUA_STREAM) }
    var selectedAnimeServer by remember { mutableStateOf(AnimeServer.ANINEKO) }

    val freeMoviePreviewMs = 20 * 60 * 1000L

    fun playWithServer(embedUrl: String, title: String, previewLimitMs: Long?) {
        onPlayStream(embedUrl, title, previewLimitMs, null)
    }

    fun playEpisodeWithServer(embedUrl: String, title: String) {
        onPlayStream(embedUrl, title, null, null)
    }

    fun selectedDonghuaScraper(): DonghuaSiteScraper =
        when (selectedDonghuaServer) {
            DonghuaServer.NONTONGO -> donghuaStreamScraper
            DonghuaServer.AUTOEMBED -> donghuaStreamScraper
            DonghuaServer.DONGHUA_STREAM -> donghuaStreamScraper
            DonghuaServer.EMBEDSU -> donghuaStreamScraper
            DonghuaServer.LUCIFER_DONGHUA -> luciferDonghuaScraper
            DonghuaServer.VIDSRC -> donghuaStreamScraper // URL built directly, scraper not used
            DonghuaServer.ANIMEXIN -> donghuaStreamScraper // AnimeXin has its own scraper; episodes loaded separately
        }

    /** Resolve the AniList ID for anime-only servers (Anivexa providers). */
    suspend fun resolveAnimeAnilistId(): String? {
        if (providerAnilistId.isNotBlank()) return providerAnilistId
        val detailUrl = item.detailPageUrl.ifBlank { item.url }
        if (detailUrl.startsWith("anilist:")) {
            val directId = detailUrl.removePrefix("anilist:").trim()
            if (directId.isNotBlank() && directId.all { it.isDigit() }) {
                providerAnilistId = directId
                return directId
            }
        }
        item.animeResult?.let {
            val directId = it.id.trim()
            if (directId.isNotBlank() && directId.all { c -> c.isDigit() }) {
                providerAnilistId = directId
                return directId
            }
        }
        val bridged = runCatching { anivexaApi.searchAnilistId(item.title) }.getOrNull()
        if (bridged != null) providerAnilistId = bridged
        return bridged
    }

    fun buildDonghuaAutoEmbedUrl(ep: MediaEpisode): String? {
        val detailParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
        val detailType = detailParts.getOrNull(0).orEmpty()
        val detailTmdbId = detailParts.getOrNull(1).orEmpty()
        if (!item.detailPageUrl.startsWith("tmdb://") || detailTmdbId.isBlank()) return null

        val urlParts = ep.url.split(":")
        val season = urlParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
        val episode = urlParts.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: ep.episodeNumber.coerceAtLeast(1).toString()

        return if (detailType == "movie") {
            "https://player.autoembed.cc/embed/movie/$detailTmdbId"
        } else {
            "https://player.autoembed.cc/embed/tv/$detailTmdbId/$season/$episode"
        }
    }

    fun buildDonghuaEmbedSuUrl(ep: MediaEpisode): String? {
        val detailParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
        val detailType = detailParts.getOrNull(0).orEmpty()
        val detailTmdbId = detailParts.getOrNull(1).orEmpty()
        if (!item.detailPageUrl.startsWith("tmdb://") || detailTmdbId.isBlank()) return null

        val urlParts = ep.url.split(":")
        val season = urlParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
        val episode = urlParts.getOrNull(3)?.takeIf { it.isNotBlank() }
            ?: ep.episodeNumber.coerceAtLeast(1).toString()

        return if (detailType == "movie") {
            "https://embed.su/embed/movie/$detailTmdbId"
        } else {
            "https://embed.su/embed/tv/$detailTmdbId/$season/$episode"
        }
    }

    suspend fun resolveDonghuaEpisodeUrl(ep: MediaEpisode): String? {
        return when (selectedDonghuaServer) {
            DonghuaServer.NONTONGO -> {
                val detailParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                val detailType = detailParts.getOrNull(0).orEmpty()
                val detailTmdbId = detailParts.getOrNull(1).orEmpty()
                if (!item.detailPageUrl.startsWith("tmdb://") || detailTmdbId.isBlank()) return null

                val urlParts = ep.url.split(":")
                val s = urlParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
                val e = urlParts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: ep.episodeNumber.coerceAtLeast(1).toString()

                if (detailType == "movie") {
                    "https://nontongo.win/embed/movie/$detailTmdbId"
                } else {
                    "https://nontongo.win/embed/tv/$detailTmdbId/$s/$e"
                }
            }
            DonghuaServer.AUTOEMBED -> {
                buildDonghuaAutoEmbedUrl(ep) ?: ep.url.takeIf { it.isNotBlank() }
            }
            DonghuaServer.DONGHUA_STREAM -> {
                ep.url.takeIf { it.isNotBlank() }
            }
            DonghuaServer.EMBEDSU -> {
                buildDonghuaEmbedSuUrl(ep) ?: ep.url.takeIf { it.isNotBlank() }
            }
            DonghuaServer.LUCIFER_DONGHUA -> {
                // Resolve the episode page URL via the LuciferDonghua scraper to get
                // an embed/player URL that MaServerPlayerScreen can open directly.
                // The WebView already has luciferdonghua.in iframe-extraction JS built in,
                // so the page loads fullscreen video just like DonghuaStream (Server 3).
                val playerUrl = luciferDonghuaScraper.resolveEpisodePlayerUrl(ep.url)
                playerUrl ?: ep.url.takeIf { it.isNotBlank() }
            }
            DonghuaServer.VIDSRC -> {
                // VidSrc.to: build the embed URL directly from the TMDB ID — no scraping,
                // no bot-protection risk. Widest Donghua/anime TMDB coverage.
                val detailParts = item.detailPageUrl.removePrefix("tmdb://").split("/")
                val detailType = detailParts.getOrNull(0).orEmpty()
                val detailTmdbId = detailParts.getOrNull(1).orEmpty()
                if (item.detailPageUrl.startsWith("tmdb://") && detailTmdbId.isNotBlank()) {
                    val urlParts = ep.url.split(":")
                    val s = urlParts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "1"
                    val e = urlParts.getOrNull(3)?.takeIf { it.isNotBlank() } ?: ep.episodeNumber.coerceAtLeast(1).toString()
                    if (detailType == "movie") "https://vidsrc.to/embed/movie/$detailTmdbId"
                    else "https://vidsrc.to/embed/tv/$detailTmdbId/$s/$e"
                } else ep.url.takeIf { it.isNotBlank() }
            }
            DonghuaServer.ANIMEXIN -> {
                // AnimeXin: resolve the episode page URL to extract the best embed player URL.
                // animexin.dev hosts Donghua and Anime with direct iframe embed extraction.
                val playerUrl = animeXinScraper.resolveEpisodePlayerUrl(ep.url)
                playerUrl ?: ep.url.takeIf { it.isNotBlank() }
            }
        }
    }

    /**
     * Resolve a downloadable stream URL for offline saving.
     *
     * Priority:
     * 1. CinePro Core via server proxy (returns direct .m3u8 from 10+ providers)
     * 2. Direct playable stream URL (extension check)
     * 3. Hidden WebView embed scraping (fallback)
     */
    suspend fun resolveDownloadableQualities(
        sourceUrl: String,
        tmdbContext: Triple<String, String, String>? = null,  // (tmdbId, type, seasonEpisode)
        onStatus: ((String) -> Unit)? = null
    ): List<CineProSource> {
        // ── Phase 1: Try CinePro Core for any TMDB-based content ──────────
        if (tmdbContext != null) {
            val (tmdbIdCtx, mediaTypeCtx, seasonEpisode) = tmdbContext
            val parts = seasonEpisode.split(":")
            val season = parts.getOrNull(0) ?: "1"
            val episode = parts.getOrNull(1) ?: "1"
            onStatus?.invoke("CinePro: searching 10+ providers for download...")
            val result = resolveAllCineProSources(httpClient, AppReleaseConfig.SERVER_BASE_URL, mediaTypeCtx, tmdbIdCtx, season, episode)
            val directSources = result.sources.filter { it.url.isDirectPlayableStreamUrl() }
            if (directSources.isNotEmpty()) {
                onStatus?.invoke("CinePro: found direct stream.")
                return directSources
            }
            onStatus?.invoke("CinePro: no sources. Trying embed fallback...")
        }

        // ── Phase 2: Check if the source itself is a direct stream URL ────
        val trimmed = sourceUrl.trim()
        if (trimmed.isNotBlank() && trimmed.isDirectPlayableStreamUrl()) {
            return listOf(CineProSource(url = trimmed, quality = "Direct"))
        }

        // ── Phase 3: Hidden WebView scraping ─────────────────────────────
        onStatus?.invoke("Embed: scraping stream (up to 45s)...")
        val scrapedUrl = extractStreamFromEmbed(trimmed, timeoutMs = 45_000L)
            ?.takeIf { it.isDirectPlayableStreamUrl() }
        
        return if (scrapedUrl != null) listOf(CineProSource(url = scrapedUrl, quality = "Auto")) else emptyList()
    }

    val freeEpisodeCount = remember(episodesList, isPremium) {
        if (isPremium || episodesList.isEmpty()) episodesList.size
        else ceil(episodesList.size * 0.2).toInt().coerceAtLeast(1)
    }

    var downloadingEpisodes by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var refreshTrigger by remember { mutableStateOf(0) }
    
    var downloadQualityOptions by remember { mutableStateOf<List<CineProSource>?>(null) }
    var pendingDownloadAction by remember { mutableStateOf<((String) -> Unit)?>(null) }


    fun contentTypeForItem(): String = when (item.mediaKind.uppercase()) {
        "MOVIE" -> ContentType.MOVIE
        "CARTOON" -> ContentType.CARTOON
        "K_DRAMA" -> ContentType.K_DRAMA
        "CLASSIC" -> ContentType.CLASSIC
        "DONGHUA" -> ContentType.MOVIE
        "NIGERIAN" -> ContentType.NIGERIAN
        else -> ContentType.ANIME
    }

    fun downloadEpisode(ep: MediaEpisode) {
        requireAuth {
            val ct = contentTypeForItem()
            if (!downloadRepo.canDownloadMedia(ct, isPremium)) {
                val remaining = downloadRepo.remainingMediaDownloadsToday(isPremium)
                statusText = if (remaining <= 0)
                    "Daily download limit (5) reached. Upgrade to premium for unlimited downloads."
                else
                    "Download limit reached. Premium users get unlimited downloads."
                return@requireAuth
            }
            if (downloadRepo.isEpisodeDownloaded(item.id, ep.episodeNumber)) {
                downloadRepo.deleteEpisode(item.id, ep.episodeNumber)
                if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                    downloadRepo.deleteItem(item.id)
                }
                refreshTrigger++
            } else {
                downloadingEpisodes = downloadingEpisodes + ep.episodeNumber
                statusText = "Resolving download link for Episode ${ep.episodeNumber}..."
                scope.launch {
                    try {
                        downloadRepo.addItem(
                            DownloadedItem(
                                id = item.id,
                                title = item.title,
                                coverUrl = item.coverUrl,
                                type = contentTypeForItem(),
                                sourceName = item.sourceName
                            )
                        )

                        val sourceUrl = when {
                            isDonghuaItem -> resolveDonghuaEpisodeUrl(ep)
                            isAnimeItem -> {
                                if (selectedAnimeServer.isAnivexa) {
                                    anivexaApi.resolveStream(ep.url)?.url
                                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                                } else {
                                    // VidLink (LAST): TMDB marker → vidlink embed,
                                    // scraped by resolveDownloadableQualities below.
                                    val urlParts = ep.url.split(":")
                                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                                    val s = urlParts.getOrNull(2) ?: "1"
                                    val e = urlParts.getOrNull(3) ?: "1"
                                    StreamServer.VIDLINK.buildEmbedUrl(tvId, "tv", s, e)
                                }
                            }
                            isTmdbDetail -> {
                                val urlParts = ep.url.split(":")
                                val tvId = urlParts.getOrNull(1) ?: tmdbId
                                val s = urlParts.getOrNull(2) ?: "1"
                                val e = urlParts.getOrNull(3) ?: "1"
                                selectedServer.buildEmbedUrl(tvId, "tv", s, e)
                            }
                            isDramaCoolDetail -> {
                                if (providerTmdbId.isNotBlank()) {
                                    val epNum = ep.episodeNumber.coerceAtLeast(1).toString()
                                    selectedServer.buildEmbedUrl(providerTmdbId, "tv", "1", epNum)
                                } else {
                                    dramaScraper.extractStreamUrl(ep.url)
                                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                                }
                            }
                            isKimCartoonDetail -> {
                                if (providerTmdbId.isNotBlank()) {
                                    val epNum = ep.episodeNumber.coerceAtLeast(1).toString()
                                    selectedServer.buildEmbedUrl(providerTmdbId, "tv", "1", epNum)
                                } else {
                                    cartoonScraper.extractStreamUrl(ep.url)
                                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                                }
                            }
                            isWcoStreamDetail -> {
                                wcoStreamScraper.extractStreamUrl(ep.url)
                                    ?.takeIf { it.isDirectPlayableStreamUrl() }
                            }
                            else -> ep.url
                        }

                        // Build TMDB context for CinePro download resolution
                        val downloadTmdbContext: Triple<String, String, String>? = when {
                            isDonghuaItem -> {
                                val urlParts = ep.url.split(":")
                                val tvId = urlParts.getOrNull(1).orEmpty()
                                val s = urlParts.getOrNull(2) ?: "1"
                                val e = urlParts.getOrNull(3) ?: ep.episodeNumber.coerceAtLeast(1).toString()
                                if (tvId.isNotBlank()) Triple(tvId, "tv", "$s:$e") else null
                            }
                            isTmdbDetail -> {
                                val urlParts = ep.url.split(":")
                                val tvId = urlParts.getOrNull(1) ?: tmdbId
                                val s = urlParts.getOrNull(2) ?: "1"
                                val e = urlParts.getOrNull(3) ?: "1"
                                Triple(tvId, "tv", "$s:$e")
                            }
                            providerTmdbId.isNotBlank() -> {
                                val epNum = ep.episodeNumber.coerceAtLeast(1).toString()
                                Triple(providerTmdbId, providerTmdbType, "1:$epNum")
                            }
                            else -> null
                        }

                        val downloadQualities = sourceUrl?.let {
                            resolveDownloadableQualities(it, tmdbContext = downloadTmdbContext, onStatus = { msg -> statusText = msg })
                        } ?: emptyList()

                        if (downloadQualities.isNotEmpty()) {
                            val processDownload = { finalUrl: String ->
                                scope.launch {
                                    statusText = "Downloading Episode ${ep.episodeNumber}..."
                                    val saved = saveDownloadedVideo(
                                        parentId = item.id,
                                        episodeNumber = ep.episodeNumber,
                                        sourceUrl = finalUrl
                                    )
                                    if (saved.success) {
                                        downloadRepo.addEpisode(
                                            DownloadedEpisode(
                                                parentId = item.id,
                                                episodeNumber = ep.episodeNumber,
                                                episodeTitle = ep.title,
                                                localFilePath = saved.localPath,
                                                fileSizeBytes = saved.fileSizeBytes
                                            )
                                        )
                                        downloadRepo.recordMediaDownload(ct)
                                        statusText = "Episode ${ep.episodeNumber} saved offline."
                                    } else {
                                        statusText = saved.error.ifBlank { "Download failed." }
                                        if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                            downloadRepo.deleteItem(item.id)
                                        }
                                    }
                                    downloadingEpisodes = downloadingEpisodes - ep.episodeNumber
                                    refreshTrigger++
                                }
                                Unit
                            }

                            if (downloadQualities.size == 1) {
                                processDownload(downloadQualities.first().url)
                            } else {
                                downloadQualityOptions = downloadQualities
                                pendingDownloadAction = processDownload
                            }
                        } else {
                            statusText = "Stream unavailable for download."
                            if (downloadRepo.getEpisodesFor(item.id).isEmpty()) {
                                downloadRepo.deleteItem(item.id)
                            }
                            downloadingEpisodes = downloadingEpisodes - ep.episodeNumber
                            refreshTrigger++
                        }
                    } catch (e: Exception) {
                        statusText = "Download failed: ${e.message}"
                        downloadingEpisodes = downloadingEpisodes - ep.episodeNumber
                        refreshTrigger++
                    }
                }
            }
        }
    }

    LaunchedEffect(item.detailPageUrl, selectedDonghuaServer, selectedAnimeServer) {
        providerTmdbId = ""
        providerTmdbType = "tv"
        isLoadingEpisodes = true

        // Phase 1: Load episodes from the URL the item came from
        val initialEpisodes = when {
            isDonghuaItem -> selectedDonghuaScraper().fetchEpisodes(
                titleQuery = item.title,
                alternateQueries = listOf(item.title.substringBefore(":")),
                maxEpisodes = 300
            )
            isAnimeItem -> {
                if (selectedAnimeServer.isAnivexa) {
                    // Anivexa-API provider: episodes keyed by AniList ID.
                    val anilistId = resolveAnimeAnilistId()
                    if (anilistId == null) {
                        emptyList()
                    } else {
                        anivexaApi.fetchEpisodes(
                            provider = selectedAnimeServer.anivexaProviderKey.orEmpty(),
                            anilistId = anilistId
                        ).map { ep ->
                            MediaEpisode(episodeNumber = ep.episodeNumber, title = ep.title, url = ep.url)
                        }
                    }
                } else if (mediaType == "tv") {
                    // VidLink (LAST anime server): reload episodes from TMDB so
                    // the numbered markers resolve through StreamServer.VIDLINK.
                    tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId)
                } else {
                    emptyList()
                }
            }
            isTmdbDetail -> {
                if (mediaType == "tv") {
                    tmdbScraper.fetchTVSeasonsAndEpisodes(tmdbId)
                } else {
                    emptyList()
                }
            }
            isDramaCoolDetail -> dramaScraper.fetchEpisodes(item.detailPageUrl)
            isKimCartoonDetail -> cartoonScraper.fetchEpisodes(item.detailPageUrl)
            isWcoStreamDetail -> wcoStreamScraper.fetchEpisodes(item.detailPageUrl)
            else -> emptyList()
        }
        episodesList = initialEpisodes
        if (isDonghuaItem && initialEpisodes.isNotEmpty()) {
            isMovieContent = false
        }

        // Phase 2: If episodes are still empty (or it's a movie without a TMDB ID),
        // try to find a TMDB match by title and load episodes from it.
        val isMovie = mediaType == "movie"
        val needsTmdbMatch = !isDonghuaItem && ((episodesList.isEmpty() && !isMovie) || (isMovie && !isTmdbDetail))
        if (needsTmdbMatch) {
            val isAnimeLike = item.isAnime ||
                item.mediaKind.equals(VideoCategory.ANIME.name, ignoreCase = true)
            val tmdbMatch = runCatching {
                tmdbScraper.search(item.title)
                    .sortedWith(
                        compareByDescending<MediaResult> { it.title.normalizedMediaTitle() == item.title.normalizedMediaTitle() }
                            .thenByDescending {
                                when {
                                    // For anime: strongly prefer TV shows with Animation genre
                                    isAnimeLike -> (if (it.type == "TVSHOW") 10 else 0) +
                                        (if (it.genres.contains("Animation", ignoreCase = true)) 5 else 0) +
                                        (if (it.genres.contains("Anime", ignoreCase = true)) 5 else 0)
                                    item.mediaKind == VideoCategory.K_DRAMA.name -> if (it.type == "TVSHOW") 1 else 0
                                    item.mediaKind == VideoCategory.CARTOON.name -> if (it.type == "TVSHOW" || it.genres.contains("Animation", ignoreCase = true)) 1 else 0
                                    else -> 0
                                }
                            }
                    )
                    // For anime: only accept TVSHOW results to avoid fetching a wrong movie TMDB ID
                    .filter { if (isAnimeLike) it.type == "TVSHOW" else true }
                    .firstOrNull()
            }.getOrNull()
            if (tmdbMatch != null) {
                providerTmdbId = tmdbMatch.id
                providerTmdbType = if (tmdbMatch.type == "MOVIE") "movie" else "tv"
                if (providerTmdbType == "movie") {
                    isMovieContent = true
                }
                if (providerTmdbType == "tv") {
                    val tmdbEpisodes = tmdbScraper.fetchTVSeasonsAndEpisodes(providerTmdbId)
                    if (tmdbEpisodes.isNotEmpty()) {
                        episodesList = tmdbEpisodes
                        isMovieContent = false
                    }
                }
            }
        }

        // Phase 3: Broader search fallback
        if (!isDonghuaItem && episodesList.isEmpty() && !isMovieContent) {
            val genreHint = when {
                item.mediaKind == VideoCategory.K_DRAMA.name -> "korean drama"
                item.mediaKind == VideoCategory.CARTOON.name -> "cartoon"
                item.mediaKind == VideoCategory.CLASSIC.name -> "tv series"
                item.mediaKind == VideoCategory.DONGHUA.name -> "donghua chinese"
                else -> "tv show"
            }
            val broaderSearch = runCatching {
                tmdbScraper.search("$genreHint ${item.title}")
                    .filter { it.type == "TVSHOW" }
                    .firstOrNull()
            }.getOrNull()
            if (broaderSearch != null) {
                providerTmdbId = broaderSearch.id
                providerTmdbType = "tv"
                val tmdbEpisodes = tmdbScraper.fetchTVSeasonsAndEpisodes(broaderSearch.id)
                if (tmdbEpisodes.isNotEmpty()) {
                    episodesList = tmdbEpisodes
                }
            }
        }

        isLoadingEpisodes = false
    }

    val playEpisode: (MediaEpisode) -> Unit = { ep ->
        scope.launch {
            val serverLabel = when {
                isDonghuaItem -> "${selectedDonghuaServer.displayName} (${selectedDonghuaServer.providerName})"
                isAnimeItem -> selectedAnimeServer.displayName
                else -> selectedServer.displayName
            }
            statusText = "Resolving stream via $serverLabel..."

            // ── CinePro: Fetch ALL direct stream sources from the server ─────
            if (!isDonghuaItem && selectedServer == StreamServer.CINEPRO) {
                val urlParts = ep.url.split(":")
                val tvId = urlParts.getOrNull(1) ?: tmdbId
                val s = urlParts.getOrNull(2) ?: "1"
                val e = urlParts.getOrNull(3) ?: "1"
                val serverBase = AppReleaseConfig.SERVER_BASE_URL
                val result = resolveAllCineProSources(httpClient, serverBase, "tv", tvId, s, e)
                val sources = result.sources
                val subtitlesJson = result.subtitlesJson
                if (sources.isNotEmpty()) {
                    for ((idx, source) in sources.withIndex()) {
                        statusText = "CinePro: trying link ${idx + 1}/${sources.size} (${source.provider.ifBlank { "direct" }})..."
                        if (source.url.isDirectPlayableStreamUrl()) {
                            statusText = ""
                            onPlayStream(source.url, "${item.title} - ${ep.title}", null, subtitlesJson)
                            return@launch
                        }
                    }
                }
                // Fallback to webview player if direct scrapers return no playable links.
                // Try vidsrc.to first (widest anime/TV coverage), then nontongo, then vidlink.
                statusText = "CinePro direct stream unavailable. Loading embed..."
                val fallbackEmbed = "https://vidsrc.to/embed/tv/$tvId/$s/$e"
                onPlayMaEmbedWithLimit(
                    fallbackEmbed,
                    "${item.title} - ${ep.title}",
                    if (isPremium) null else freeMoviePreviewMs
                )
                return@launch
            }

            // ── Server 5 (ExoPlayer): Pass VidLink embed to AnimePlayerScreen ──
            if (!isDonghuaItem && selectedServer == StreamServer.VIDLINK_EXO) {
                statusText = "Server 5: Passing VidLink to ExoPlayer scraper..."
                val urlParts = ep.url.split(":")
                val tvId = urlParts.getOrNull(1) ?: tmdbId
                val s = urlParts.getOrNull(2) ?: "1"
                val e = urlParts.getOrNull(3) ?: "1"
                val embedUrl = StreamServer.VIDLINK_EXO.buildEmbedUrl(tvId, "tv", s, e)
                statusText = ""
                onPlayStream(embedUrl, "${item.title} - ${ep.title}", null, null)
                return@launch
            }

            // Build the embed URL using the selected server's URL builder
            val embedUrl = when {
                isDonghuaItem -> {
                    val resolved = resolveDonghuaEpisodeUrl(ep)
                    if (resolved == null) {
                        statusText = "Donghua stream unavailable. Try a different server."
                        return@launch
                    }
                    resolved
                }
                isAnimeItem -> {
                    if (selectedAnimeServer.isAnivexa && AnivexaApi.isAnivexaEpisodeUrl(ep.url)) {
                        val stream = anivexaApi.resolveStream(ep.url)
                        if (stream == null) {
                            statusText = "Stream unavailable for this episode. Try a different server."
                            return@launch
                        }
                        if (stream.isDirect) {
                            onPlayStream(stream.url, "${item.title} - ${ep.title}", if (isPremium) null else freeMoviePreviewMs, null)
                            return@launch
                        }
                        onPlayMaEmbedWithLimit(stream.url, "${item.title} - ${ep.title}", if (isPremium) null else freeMoviePreviewMs)
                        return@launch
                    }
                    // VidLink (LAST anime server): TMDB marker → vidlink embed.
                    val urlParts = ep.url.split(":")
                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                    val s = urlParts.getOrNull(2) ?: "1"
                    val e = urlParts.getOrNull(3) ?: "1"
                    StreamServer.VIDLINK.buildEmbedUrl(tvId, "tv", s, e)
                }
                isTmdbDetail -> {
                    val urlParts = ep.url.split(":")
                    val tvId = urlParts.getOrNull(1) ?: tmdbId
                    val s = urlParts.getOrNull(2) ?: "1"
                    val e = urlParts.getOrNull(3) ?: "1"
                    selectedServer.buildEmbedUrl(tvId, "tv", s, e)
                }
                isDramaCoolDetail -> {
                    if (providerTmdbId.isNotBlank()) {
                        val epNum = ep.episodeNumber.coerceAtLeast(1).toString()
                        selectedServer.buildEmbedUrl(providerTmdbId, "tv", "1", epNum)
                    } else {
                        val extracted = dramaScraper.extractStreamUrl(ep.url)
                            ?.takeIf { it.isDirectPlayableStreamUrl() }
                        if (extracted == null) {
                            statusText = "Direct stream unavailable for this episode. Try a different server."
                            return@launch
                        }
                        extracted
                    }
                }
                isKimCartoonDetail -> {
                    if (providerTmdbId.isNotBlank()) {
                        val epNum = ep.episodeNumber.coerceAtLeast(1).toString()
                        selectedServer.buildEmbedUrl(providerTmdbId, "tv", "1", epNum)
                    } else {
                        val extracted = cartoonScraper.extractStreamUrl(ep.url)
                            ?.takeIf { it.isDirectPlayableStreamUrl() }
                        if (extracted == null) {
                            statusText = "Direct stream unavailable for this episode."
                            return@launch
                        }
                        extracted
                    }
                }
                isWcoStreamDetail -> {
                    val extracted = wcoStreamScraper.extractStreamUrl(ep.url)
                        ?.takeIf { it.isDirectPlayableStreamUrl() }
                    if (extracted == null) {
                        statusText = "This episode stream is unavailable from the cartoon provider."
                        return@launch
                    }
                    extracted
                }
                else -> ep.url
            }

            // Route to player based on selected server.
            // All embed paths pass a 20-minute hard cap for free users — the
            // WebView player can't read duration reliably, so a flat cap
            // guarantees free users can never finish a full episode/movie.
            onPlayMaEmbedWithLimit(
                embedUrl,
                "${item.title} - ${ep.title}",
                if (isPremium) null else freeMoviePreviewMs
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(currentTheme.backgroundColor())
            .verticalScroll(rememberScrollState())
            .padding(bottom = 60.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.15f), currentTheme.backgroundColor()),
                            startY = 120f
                        )
                    )
            )
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    
                    .padding(10.dp)
                    .align(Alignment.TopStart)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    item.title,
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black
                )
            }
        }

        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (item.genre.isNotBlank()) {
                Text(
                    item.genre,
                    color = currentTheme.accentColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Surface(
                color = currentTheme.cardColor(),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    item.synopsis.ifBlank { "Synopsis unavailable." },
                    color = currentTheme.textColor(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }

            // ── Server Selector ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isDonghuaItem) {
                    DonghuaServer.ALL_IN_ORDER.forEach { server ->
                        val isSelected = selectedDonghuaServer == server
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedDonghuaServer = server },
                            label = {
                                Text(
                                    server.displayName,
                                    color = if (isSelected) Color.White else currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = currentTheme.accentColor(),
                                containerColor = currentTheme.cardColor(),
                                labelColor = currentTheme.subTextColor()
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = isSelected,
                                selectedBorderColor = currentTheme.accentColor(),
                                borderColor = currentTheme.subTextColor().copy(0.3f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                } else if (isAnimeItem) {
                    // Anime-only selector: 13 Anivexa providers + VidLink LAST.
                    AnimeServer.ALL_IN_ORDER.forEach { server ->
                        val isSelected = selectedAnimeServer == server
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedAnimeServer = server },
                            label = {
                                Text(
                                    server.displayName,
                                    color = if (isSelected) Color.White else currentTheme.subTextColor(),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = currentTheme.accentColor(),
                                containerColor = currentTheme.cardColor(),
                                labelColor = currentTheme.subTextColor()
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = isSelected,
                                selectedBorderColor = currentTheme.accentColor(),
                                borderColor = currentTheme.subTextColor().copy(0.3f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                } else {
                    StreamServer.ALL_IN_ORDER.forEach { server ->
                        val isSelected = selectedServer == server
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedServer = server },
                        label = {
                            Text(
                                server.displayName,
                                color = if (isSelected) Color.White else currentTheme.subTextColor(),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = currentTheme.accentColor(),
                            containerColor = currentTheme.cardColor(),
                            labelColor = currentTheme.subTextColor()
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true, selected = isSelected,
                            selectedBorderColor = currentTheme.accentColor(),
                            borderColor = currentTheme.subTextColor().copy(0.3f)
                        ),
                        shape = RoundedCornerShape(20.dp)
                    )
                    }
                }
            }

            if (statusText.isNotEmpty()) {
                Text(
                    text = statusText,
                    color = currentTheme.accentColor(),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // YouTube Nollywood
            if (isYouTubeNollywood) {
                Button(
                    onClick = {
                        scope.launch {
                            statusText = "Resolving ad-free stream via Piped..."
                            val prefix = "youtube_nollywood_"
                            val videoId = item.id.removePrefix(prefix)
                            val streamUrl = youtubeNollywoodScraper.extractStreamUrl(videoId)
                            if (streamUrl != null) {
                                onPlayStream(streamUrl, item.title, null, null)
                            } else {
                                statusText = "Could not resolve stream."
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Watch on ExoPlayer (Piped)", fontWeight = FontWeight.Bold)
                }
            }

            // Movie: Single Play button
            val hasMovieId = !isDonghuaItem && !isAnimeItem &&
                ((isTmdbDetail && mediaType == "movie") || (providerTmdbType == "movie" && providerTmdbId.isNotBlank()))
            if (hasMovieId) {
                if (!isPremium) {
                    Surface(
                        color = currentTheme.accentColor().copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "Free account preview: first 20 minutes.",
                            color = currentTheme.textColor(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }

                var downloadingMovie by remember { mutableStateOf(false) }
                val isMovieDownloaded = remember(refreshTrigger) { downloadRepo.isEpisodeDownloaded(item.id, 1) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                // Movie play button — uses selected server
                Button(
                    onClick = {
                        scope.launch {
                            val resolvedTmdbId = if (isTmdbDetail) tmdbId else providerTmdbId
                            // ── CinePro: Fetch ALL direct stream sources from the server ─
                            if (selectedServer == StreamServer.CINEPRO) {
                                val serverBase = AppReleaseConfig.SERVER_BASE_URL
                                val result = resolveAllCineProSources(httpClient, serverBase, "movie", resolvedTmdbId)
                                val sources = result.sources
                                val subtitlesJson = result.subtitlesJson
                                if (sources.isNotEmpty()) {
                                    for ((idx, source) in sources.withIndex()) {
                                        statusText = "CinePro: trying link ${idx + 1}/${sources.size} (${source.provider.ifBlank { "direct" }})..."
                                        if (source.url.isDirectPlayableStreamUrl()) {
                                            statusText = ""
                                            onPlayStream(source.url, item.title, if (isPremium) null else freeMoviePreviewMs, subtitlesJson)
                                            return@launch
                                        }
                                    }
                                }
                                // Fallback to vidsrc.to embed — wider movie coverage than vidlink.pro alone
                                statusText = "CinePro direct stream unavailable. Loading embed..."
                                val fallbackEmbed = "https://vidsrc.to/embed/movie/$resolvedTmdbId"
                                onPlayMaEmbedWithLimit(
                                    fallbackEmbed,
                                    item.title,
                                    if (isPremium) null else freeMoviePreviewMs
                                )
                                return@launch
                            }
                            // ── Server 5 (ExoPlayer): Pass VidLink embed to AnimePlayerScreen ─
                            if (selectedServer == StreamServer.VIDLINK_EXO) {
                                statusText = "Server 5: Passing VidLink to ExoPlayer scraper..."
                                val embedUrl = StreamServer.VIDLINK_EXO.buildEmbedUrl(resolvedTmdbId, "movie", "1", "1")
                                statusText = ""
                                onPlayStream(embedUrl, item.title, if (isPremium) null else freeMoviePreviewMs, null)
                                return@launch
                            }
                            val embedUrl = selectedServer.buildEmbedUrl(resolvedTmdbId, "movie", "1", "1")
                            onPlayMaEmbedWithLimit(
                                embedUrl,
                                item.title,
                                if (isPremium) null else freeMoviePreviewMs
                            )
                        }
                    },
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = currentTheme.accentColor())
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (isPremium) "Watch Movie Now" else "Watch 20-minute Preview", fontWeight = FontWeight.Bold)
                    }

                    IconButton(
                        onClick = {
                            requireAuth {
                                if (isMovieDownloaded) {
                                    downloadRepo.deleteEpisode(item.id, 1)
                                    if (downloadRepo.getEpisodesFor(item.id).isEmpty()) downloadRepo.deleteItem(item.id)
                                    refreshTrigger++
                                } else {
                                    val ct = contentTypeForItem()
                                    if (!downloadRepo.canDownloadMedia(ct, isPremium)) {
                                        val remaining = downloadRepo.remainingMediaDownloadsToday(isPremium)
                                        statusText = if (remaining <= 0)
                                            "Daily download limit (5) reached. Upgrade to premium for unlimited downloads."
                                        else
                                            "Download limit reached. Premium users get unlimited downloads."
                                        return@requireAuth
                                    }
                                    downloadingMovie = true
                                    statusText = "Resolving download link..."
                                    scope.launch {
                                        try {
                                            downloadRepo.addItem(DownloadedItem(item.id, item.title, item.coverUrl, contentTypeForItem(), item.sourceName))
                                            val resolvedTmdbId = if (isTmdbDetail) tmdbId else providerTmdbId
                                            val sourceUrl = selectedServer.buildEmbedUrl(resolvedTmdbId, "movie", "1", "1")
                                            // CinePro context for movie download
                                            val movieTmdbContext = if (resolvedTmdbId.isNotBlank()) Triple(resolvedTmdbId, "movie", "1:1") else null
                                            val downloadQualities = resolveDownloadableQualities(sourceUrl, tmdbContext = movieTmdbContext, onStatus = { msg -> statusText = msg })
                                            if (downloadQualities.isNotEmpty()) {
                                                val processDownload = { finalUrl: String ->
                                                    scope.launch {
                                                        statusText = "Downloading movie..."
                                                        val saved = saveDownloadedVideo(item.id, 1, finalUrl)
                                                        if (saved.success) {
                                                            downloadRepo.addEpisode(DownloadedEpisode(item.id, 1, item.title, saved.localPath, saved.fileSizeBytes))
                                                            statusText = "Movie saved offline."
                                                        } else { 
                                                            statusText = saved.error.ifBlank { "Download failed." }
                                                            if (downloadRepo.getEpisodesFor(item.id).isEmpty()) downloadRepo.deleteItem(item.id) 
                                                        }
                                                        downloadingMovie = false
                                                        refreshTrigger++
                                                    }
                                                    Unit
                                                }
                                                
                                                if (downloadQualities.size == 1) {
                                                    processDownload(downloadQualities.first().url)
                                                } else {
                                                    downloadQualityOptions = downloadQualities
                                                    pendingDownloadAction = processDownload
                                                }
                                            } else { 
                                                statusText = "Movie stream unavailable for download."
                                                if (downloadRepo.getEpisodesFor(item.id).isEmpty()) downloadRepo.deleteItem(item.id) 
                                                downloadingMovie = false
                                                refreshTrigger++
                                            }
                                        } catch (e: Exception) { 
                                            statusText = "Download failed: ${e.message}" 
                                            downloadingMovie = false
                                            refreshTrigger++
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.size(50.dp).background(currentTheme.cardColor(), RoundedCornerShape(12.dp))
                    ) {
                        if (downloadingMovie) CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = currentTheme.accentColor())
                        else if (isMovieDownloaded) Icon(Icons.Default.OfflinePin, null, tint = Color(0xFF4CAF50))
                        else Icon(Icons.Default.Download, null, tint = currentTheme.textColor())
                    }
                }

                if (!isPremium) {
                    OutlinedButton(onClick = onSubscribe, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Text("Subscribe for full access")
                    }
                }
            }
            else {
                // TV / Episodic
                Text(
                    text = "Episodes",
                    style = MaterialTheme.typography.titleLarge,
                    color = currentTheme.textColor(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 6.dp)
                )

                if (isLoadingEpisodes) {
                    Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = currentTheme.accentColor())
                    }
                } else if (episodesList.isEmpty()) {
                    Text("No episodes found for this show.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodyMedium)
                } else {
                    if (!isPremium) {
                        Text("Free account access: $freeEpisodeCount of ${episodesList.size} episodes unlocked.", color = currentTheme.subTextColor(), style = MaterialTheme.typography.bodySmall)
                    }
                    
                    val chunkSize = 50
                    var currentChunkIndex by remember(episodesList) { mutableStateOf(0) }
                    
                    if (episodesList.size > chunkSize) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            items(episodesList.chunked(chunkSize).size) { chunkIndex ->
                                val startEp = chunkIndex * chunkSize + 1
                                val endEp = minOf((chunkIndex + 1) * chunkSize, episodesList.size)
                                val isSelected = currentChunkIndex == chunkIndex
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { currentChunkIndex = chunkIndex },
                                    label = { Text("$startEp - $endEp", color = if (isSelected) currentTheme.backgroundColor() else currentTheme.textColor()) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = currentTheme.accentColor(),
                                        containerColor = currentTheme.cardColor()
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(
                                        borderColor = if (isSelected) currentTheme.accentColor() else currentTheme.subTextColor().copy(alpha = 0.3f),
                                        enabled = true,
                                        selected = isSelected
                                    )
                                )
                            }
                        }
                    }

                    val displayedEpisodes = if (episodesList.size > chunkSize) {
                        episodesList.chunked(chunkSize).getOrNull(currentChunkIndex) ?: emptyList()
                    } else {
                        episodesList
                    }

                    displayedEpisodes.forEachIndexed { i, ep ->
                        val actualIndex = currentChunkIndex * chunkSize + i
                        val locked = !isPremium && actualIndex >= freeEpisodeCount
                        val isDownloaded = remember(refreshTrigger, ep.episodeNumber) { downloadRepo.isEpisodeDownloaded(item.id, ep.episodeNumber) }
                        val isDownloading = ep.episodeNumber in downloadingEpisodes

                        Card(
                            onClick = { if (locked) onSubscribe() else playEpisode(ep) },
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(containerColor = currentTheme.cardColor()),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        ) {
                            Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (locked) Icons.Default.Lock else Icons.Default.PlayArrow, null, tint = currentTheme.accentColor(), modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(ep.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = currentTheme.textColor(), modifier = Modifier.weight(1f))
                                if (locked) { Spacer(Modifier.width(8.dp)); Text("Premium", style = MaterialTheme.typography.labelSmall, color = currentTheme.accentColor(), fontWeight = FontWeight.Bold) }
                                else {
                                    IconButton(onClick = { downloadEpisode(ep) }, modifier = Modifier.size(36.dp)) {
                                        if (isDownloading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = currentTheme.accentColor())
                                        else if (isDownloaded) Icon(Icons.Default.OfflinePin, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(20.dp))
                                        else Icon(Icons.Outlined.Download, null, tint = currentTheme.subTextColor(), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (downloadQualityOptions != null) {
        AlertDialog(
            onDismissRequest = { 
                downloadQualityOptions = null
                pendingDownloadAction = null
            },
            title = { Text("Select Download Quality") },
            text = {
                Column {
                    downloadQualityOptions!!.forEach { option ->
                        TextButton(
                            onClick = {
                                val action = pendingDownloadAction
                                downloadQualityOptions = null
                                pendingDownloadAction = null
                                action?.invoke(option.url)
                            },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text("${option.quality} ${if (option.provider.isNotBlank()) "(${option.provider})" else ""}".trim(), fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { 
                    downloadQualityOptions = null 
                    pendingDownloadAction = null
                }) {
                    Text("Cancel")
                }
            },
            containerColor = currentTheme.cardColor(),
            titleContentColor = currentTheme.textColor(),
            textContentColor = currentTheme.textColor()
        )
    }
}

private fun String.normalizedMediaTitle(): String =
    lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
