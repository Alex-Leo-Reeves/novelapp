package com.alexleoreeves.novelapp.tv.data

import android.content.Context
import com.alexleoreeves.novelapp.data.AnimeServer
import com.alexleoreeves.novelapp.data.Chapter
import com.alexleoreeves.novelapp.data.DonghuaServer
import com.alexleoreeves.novelapp.data.StreamServer
import com.alexleoreeves.novelapp.data.TvMediaRepository
import com.alexleoreeves.novelapp.data.UnifiedSearchResult
import com.alexleoreeves.novelapp.data.extractTvStreamFromEmbed
import com.alexleoreeves.novelapp.data.isTvPlayableStreamUrl

/**
 * Content kind for a binge session — decides whether the session is episodic
 * (auto-next enabled) or a single movie (movie-end recommendations).
 */
enum class BingeContentKind {
    MOVIE,
    TV,
    ANIME,
    DONGHUA;

    val isEpisodic: Boolean
        get() = this != MOVIE
}

/**
 * One episode in a binge session.
 *
 * The stream URL is resolved lazily: [url] is blank until a route is resolved
 * for that episode. Auto-next and remote NEXT resolve through the SAME server
 * captured in [TvBingeSession.server]/[TvBingeSession.donghuaServer] — never a
 * different one.
 *
 * @param isDirect true → LibVLC TvPlayerScreen, false → TvEmbedPlayerScreen
 */
data class TvBingeEpisode(
    val chapter: Chapter,
    val url: String = "",
    val kind: BingeContentKind = BingeContentKind.TV,
    val isDirect: Boolean = false
)

/**
 * A binge playback session: the full ordered episode list for a title, the
 * server the user chose, and a current index. Immutable — navigation does
 * `session.withIndex(n)` to move around.
 *
 * The chosen server is stored as the enum (not just the display name) so the
 * router can re-resolve any episode on the same server when auto-next fires.
 */
data class TvBingeSession(
    val item: UnifiedSearchResult,
    val episodes: List<TvBingeEpisode> = emptyList(),
    val serverName: String = "",
    val server: StreamServer? = null,
    val donghuaServer: DonghuaServer? = null,
    val animeServer: AnimeServer? = null,
    val currentIndex: Int = 0,
    val isDonghua: Boolean = false,
    val isPremium: Boolean = true,
    val isTVSection: Boolean = false
) {
    val current: TvBingeEpisode?
        get() = episodes.getOrNull(currentIndex)

    val isLastEpisode: Boolean
        get() = currentIndex >= episodes.lastIndex

    val hasNext: Boolean
        get() = currentIndex < episodes.lastIndex

    val hasPrev: Boolean
        get() = currentIndex > 0

    val nextIndex: Int
        get() = (currentIndex + 1).coerceAtMost(episodes.lastIndex)

    val prevIndex: Int
        get() = (currentIndex - 1).coerceAtLeast(0)

    /** Whether this session is a single movie / last-episode end card. */
    val isMovieLike: Boolean
        get() = episodes.size <= 1

    /** Copy pointing at [index]. The player keys off the current URL/title. */
    fun withIndex(index: Int): TvBingeSession =
        copy(currentIndex = index.coerceIn(0, episodes.lastIndex.coerceAtLeast(0)))

    fun withNext(): TvBingeSession = withIndex(nextIndex)

    fun withPrev(): TvBingeSession = withIndex(prevIndex)

    /** Replaces the episode at [index] with a freshly resolved one. */
    fun withResolvedEpisode(index: Int, resolved: TvBingeEpisode): TvBingeSession {
        val updated = episodes.toMutableList()
        if (index in updated.indices) updated[index] = resolved
        return copy(episodes = updated)
    }

    companion object {
        /** Empty session used as a default when nothing is playing. */
        val EMPTY = TvBingeSession(
            item = UnifiedSearchResult(id = "", title = "", coverUrl = "", detailPageUrl = "", sourceName = ""),
            episodes = emptyList(),
            serverName = ""
        )
    }
}

/**
 * Derives the binge content kind for an item/chapter.
 */
fun deriveBingeKind(
    item: UnifiedSearchResult,
    chapter: Chapter?,
    isDonghua: Boolean
): BingeContentKind = when {
    isDonghua -> BingeContentKind.DONGHUA
    item.isAnime -> BingeContentKind.ANIME
    item.mediaKind.equals("movie", ignoreCase = true) ||
        item.mediaKind.equals("movies", ignoreCase = true) ||
        chapter?.title?.equals("Full Movie", ignoreCase = true) == true ||
        chapter?.url?.startsWith("tmdb-movie://", ignoreCase = true) == true -> BingeContentKind.MOVIE
    else -> BingeContentKind.TV
}

/**
 * Classifies a resolved stream URL into the right TV player:
 *  - Server 5 (VidLink Exo): scrape the embed for a direct .m3u8/.mp4 first.
 *  - Direct stream URL (.m3u8/.mp4/.mpd/...) → LibVLC TvPlayerScreen.
 *  - Everything else (embed page / CinePro page / Donghua) → TvEmbedPlayerScreen.
 */
fun buildTvBingeEpisode(
    chapter: Chapter,
    rawUrl: String,
    kind: BingeContentKind,
    isDonghua: Boolean
): TvBingeEpisode {
    val trimmed = rawUrl.trim()
    val isDirect = !isDonghua && isTvPlayableStreamUrl(trimmed)
    return TvBingeEpisode(
        chapter = chapter,
        url = trimmed,
        kind = kind,
        isDirect = isDirect
    )
}

/**
 * Resolves one episode's playback route on the session's chosen server.
 *
 * @param scrapeExo when true (Server 5), scrapes the VidLink page for a direct
 *                  stream via the hidden WebView. Requires [context].
 */
suspend fun TvMediaRepository.resolveBingeEpisode(
    context: Context,
    item: UnifiedSearchResult,
    chapter: Chapter?,
    server: StreamServer?,
    donghuaServer: DonghuaServer?,
    animeServer: AnimeServer? = null,
    isDonghua: Boolean
): TvBingeEpisode? {
    val resolved = resolveStreamUrl(item, chapter, server, donghuaServer, animeServer) ?: return null

    if (!isDonghua && server == StreamServer.VIDLINK_EXO) {
        val scraped = extractTvStreamFromEmbed(context, resolved)
        if (scraped != null) {
            return TvBingeEpisode(
                chapter = chapter ?: Chapter(item.title, item.detailPageUrl, 0),
                url = scraped.url,
                kind = deriveBingeKind(item, chapter, isDonghua),
                isDirect = true
            )
        }
        // Fall through — the embed page itself is still playable.
    }
    return buildTvBingeEpisode(
        chapter = chapter ?: Chapter(item.title, item.detailPageUrl, 0),
        rawUrl = resolved,
        kind = deriveBingeKind(item, chapter, isDonghua),
        isDonghua = isDonghua
    )
}
