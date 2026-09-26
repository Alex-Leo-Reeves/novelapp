package com.alexleoreeves.novelapp.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

// ─────────────────────────────────────────────────────────────────────────────
//  Genre-based WATCHABLE home feed — shared by the TV app home screen and the
//  mobile (Android/iOS) Discover home.
//
//  Row plan:  ✨ Recommended (learned from what you actually watched)
//             🆕 Latest (in theatres / on air / newest anime)
//             15 genre rows (Action, Horror, Supernatural, …), each mixing
//             movies + shows + anime of that genre.
//
//  Only TMDB video results and AniList anime ever enter the feed, so novels,
//  manga and comics are structurally impossible here — no manual filtering.
// ─────────────────────────────────────────────────────────────────────────────

/** One genre row: TMDB movie/tv genre ids + optional AniList genre name. */
data class HomeGenre(
    val key: String,
    val label: String,
    val movieGenreIds: List<Int>,
    val tvGenreIds: List<Int>,
    val anilistGenre: String? = null
)

object HomeGenres {
    /** 15 browsable genre rows. Movie ids use TMDB's movie genre list, tv ids the TV list. */
    val all: List<HomeGenre> = listOf(
        HomeGenre("action", "Action", listOf(28), listOf(10759), "Action"),
        HomeGenre("horror", "Horror", listOf(27), listOf(27), "Horror"),
        HomeGenre("comedy", "Comedy", listOf(35), listOf(35), "Comedy"),
        // No dedicated TMDB "Supernatural" genre → Fantasy+Horror / Sci-Fi&Fantasy
        // approximates it; AniList has the real genre.
        HomeGenre("supernatural", "Supernatural", listOf(14, 27), listOf(10765, 27), "Supernatural"),
        HomeGenre("thriller", "Thriller", listOf(53), listOf(80), "Thriller"),
        HomeGenre("scifi", "Sci-Fi", listOf(878), listOf(10765), "Sci-Fi"),
        HomeGenre("fantasy", "Fantasy", listOf(14), listOf(10765), "Fantasy"),
        HomeGenre("romance", "Romance", listOf(10749), listOf(10749), "Romance"),
        HomeGenre("mystery", "Mystery", listOf(9648), listOf(9648), "Mystery"),
        HomeGenre("drama", "Drama", listOf(18), listOf(18), "Drama"),
        HomeGenre("adventure", "Adventure", listOf(12), listOf(10759), "Adventure"),
        HomeGenre("crime", "Crime", listOf(80), listOf(80), null),
        HomeGenre("war", "War", listOf(10752), listOf(10768), null),
        HomeGenre("family", "Family", listOf(10751), listOf(10751), null),
        HomeGenre("animation", "Animation", listOf(16), listOf(16), null)
    )
}

/** Watchable = playable video. Novels/manga/comics and read-only sources are excluded. */
fun UnifiedSearchResult.isWatchableHome(): Boolean = when {
    isManga || isComic -> false
    mediaKind.equals("NOVEL", ignoreCase = true) ||
        mediaKind.equals("MANGA", ignoreCase = true) ||
        mediaKind.equals("COMIC", ignoreCase = true) -> false
    isVideo || isAnime -> true
    detailPageUrl.startsWith("anilist:") -> true
    detailPageUrl.startsWith("tmdb://") -> true
    else -> false
}

/**
 * Builds the rows of the watchable home feed.
 *
 * The recommendation row learns from what the user watched:
 *  1. Known TMDB ids (downloads/favorites) → TMDB "recommendations" for them.
 *  2. Watched titles → single TMDB match → recommendations for the match,
 *     and the match's genres are counted as *affinity*.
 *  3. Watched titles → AniList match → its genres add to the same affinity
 *     (this is how anime taste — e.g. Supernatural — is learned).
 *  4. The top-2 affinity genres get their own recommendations mixed in.
 *  5. Cold start (no history / too few results) → Latest as the fallback.
 *
 * Injected sources keep this file shared: the TV app and the mobile app each
 * construct it with their own HTTP client and TMDB keys.
 */
class HomeFeedRepository(
    private val tmdb: TmdbSource,
    private val anilist: AniListSource
) {
    /** 🆕 Latest: movies in theatres + shows on air + newest anime, interleaved. */
    suspend fun latestRow(page: Int = 1): List<UnifiedSearchResult> = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
        coroutineScope {
            val tmdbJob = async { runCatching { tmdb.fetchLatestMixed(page) }.getOrElse { emptyList() } }
            val animeJob = async {
                runCatching { anilist.fetchLatestAnime(page).map { it.toUnifiedSearchResult() } }.getOrElse { emptyList() }
            }
            interleave(tmdbJob.await(), animeJob.await())
                .filter { it.isWatchableHome() }
                .distinctBy { it.id }
        }
    } ?: emptyList()

    /** A genre row: genre movies + genre shows + genre anime, interleaved. */
    suspend fun genreRow(genre: HomeGenre, page: Int = 1): List<UnifiedSearchResult> = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
        val movieJob = async {
            runCatching { tmdb.discoverByGenre("movie", genre.movieGenreIds, page, VideoCategory.MOVIES) }
                .getOrElse { emptyList() }
        }
        val tvJob = async {
            runCatching { tmdb.discoverByGenre("tv", genre.tvGenreIds, page, VideoCategory.MOVIES) }
                .getOrElse { emptyList() }
        }
        val animeJob = async {
            genre.anilistGenre?.let { g ->
                runCatching { anilist.searchByGenre(g, page).map { it.toUnifiedSearchResult() } }.getOrElse { emptyList() }
            } ?: emptyList()
        }
        interleave(interleave(movieJob.await(), tvJob.await()), animeJob.await())
            .filter { it.isWatchableHome() }
            .distinctBy { it.id }
    } ?: emptyList()

    /**
     * ✨ Recommended row — see class doc for the learning algorithm.
     * @param seedTitles watched titles (history / downloads)
     * @param seedTmdbIds ids already known to be TMDB (`tmdb_movie_123` style)
     */
    suspend fun recommendedRow(
        seedTitles: List<String>,
        seedTmdbIds: List<String> = emptyList(),
        limit: Int = 24
    ): List<UnifiedSearchResult> = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
        val seen = LinkedHashMap<String, UnifiedSearchResult>()
        val normalizedSeeds = seedTitles
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
            .toSet()
        fun addAll(list: List<UnifiedSearchResult>) {
            list.filter { it.isWatchableHome() }.forEach { item ->
                if (item.id in seedTmdbIds) return@forEach
                if (item.title.trim().lowercase() in normalizedSeeds) return@forEach
                if (item.id !in seen) seen[item.id] = item
            }
        }
        val affinity = mutableMapOf<String, Int>()
        fun noteAffinity(token: String) {
            affinityGenre(token)?.let { g -> affinity[g.key] = (affinity[g.key] ?: 0) + 1 }
        }

        // 1) Known TMDB ids → direct recommendation calls.
        val idJobs = seedTmdbIds.take(3).map { rawId ->
            async {
                val digits = rawId.replace(Regex("[^0-9]"), "")
                if (!rawId.startsWith("tmdb") || digits.isBlank()) {
                    emptyList<UnifiedSearchResult>()
                } else when {
                    rawId.startsWith("tmdb_movie") -> tmdb.fetchRecommendations("movie", digits)
                    rawId.startsWith("tmdb_tv") -> tmdb.fetchRecommendations("tv", digits)
                    else -> tmdb.fetchRecommendations("movie", digits) +
                        tmdb.fetchRecommendations("tv", digits)
                }
            }
        }

        // 2) Watched titles → TMDB match → recommendations + genre affinity.
        val titleJobs = seedTitles.take(3).distinctBy { it.lowercase() }.map { title ->
            async {
                val match = tmdb.searchSeedMatch(title).firstOrNull()
                if (match == null) {
                    emptyList<UnifiedSearchResult>()
                } else {
                    match.genre.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        .forEach(::noteAffinity)
                    val type = if (match.detailPageUrl.startsWith("tmdb://movie/")) "movie" else "tv"
                    val rawId = match.detailPageUrl.substringAfterLast("/")
                    if (rawId.isBlank()) emptyList()
                    else tmdb.fetchRecommendations(type, rawId)
                }
            }
        }

        // 3) AniList match → anime genre affinity (Supernatural, Mecha, …).
        val animeSeedJobs = seedTitles.take(3).map { title ->
            async {
                val match = runCatching { anilist.search(title, 1).firstOrNull() }.getOrNull()
                match?.genres?.forEach(::noteAffinity)
            }
        }

        idJobs.awaitAll().forEach(::addAll)
        titleJobs.awaitAll().forEach(::addAll)
        animeSeedJobs.awaitAll()

        // 4) Top learned genres mixed into the row — the visible "learning".
        val topGenres = affinity.entries
            .sortedByDescending { it.value }
            .take(2)
            .mapNotNull { (key, _) -> HomeGenres.all.firstOrNull { it.key == key } }
        topGenres
            .map { g -> async { genreRow(g, 1) } }
            .awaitAll()
            .forEach(::addAll)

        // 5) Cold start → Latest.
        if (seen.size < 10) addAll(latestRow(1))

        seen.values.toList().shuffled().take(limit)
    } ?: emptyList()

    private companion object {
        /** Hard cap per row fetch — a hung request must never leave a row loading forever. */
        const val FETCH_TIMEOUT_MS = 20_000L

        /** Maps a genre token (from TMDB genre text or AniList genres) to a home genre row. */
        fun affinityGenre(token: String): HomeGenre? {
            val t = token.trim().lowercase()
            if (t.isEmpty()) return null
            return HomeGenres.all.firstOrNull { g ->
                g.label.lowercase() == t ||
                    (g.key == "scifi" && (t == "sci-fi" || t == "science fiction")) ||
                    (g.key == "animation" && t == "animated")
            }
        }

        /** Round-robin merge of two rows so movies/shows/anime alternate instead of banding. */
        fun interleave(a: List<UnifiedSearchResult>, b: List<UnifiedSearchResult>): List<UnifiedSearchResult> {
            val out = ArrayList<UnifiedSearchResult>(a.size + b.size)
            for (i in 0 until maxOf(a.size, b.size)) {
                if (i < a.size) out.add(a[i])
                if (i < b.size) out.add(b[i])
            }
            return out
        }
    }
}
