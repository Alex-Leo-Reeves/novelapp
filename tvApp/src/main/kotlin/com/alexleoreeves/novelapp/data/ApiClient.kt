package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.SavedUserAccount
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.ContentType as KtorContentType
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

object ApiConfig {
    const val API_BASE_URL = "https://novelapp1.onrender.com/api"
    const val SITE_BASE_URL = "https://novelapp1.onrender.com"
    const val ANILIST_GRAPHQL = "https://graphql.anilist.co"
    const val MANGADEX_API = "https://api.mangadex.org"
}

val apiJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun platformHttpClient(): HttpClient = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(apiJson) }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 20_000
        socketTimeoutMillis = 20_000
    }
}

// ── Auth ────────────────────────────────────────────────────────────────────
suspend fun authRegister(username: String, email: String, password: String, recoverySecret: String): SavedUserAccount {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/auth/register") {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject {
                put("username", username)
                put("email", email)
                put("password", password)
                put("recoverySecret", recoverySecret)
            })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val token = json["token"]?.jsonPrimitive?.contentOrNull ?: ""
        val user = json["user"]?.jsonObject ?: error("No user in response")
        SavedUserAccount(
            id = user["id"]?.jsonPrimitive?.contentOrNull ?: "",
            username = user["username"]?.jsonPrimitive?.contentOrNull ?: "",
            email = user["email"]?.jsonPrimitive?.contentOrNull ?: "",
            authToken = token,
            plan = user["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
            billingStatus = user["billingStatus"]?.jsonPrimitive?.contentOrNull ?: "none",
            paidUntil = user["paidUntil"]?.jsonPrimitive?.contentOrNull,
            createdAt = user["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
            maxDevices = user["maxDevices"]?.jsonPrimitive?.intOrNull,
            isPremium = user["isPremium"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    } finally { client.close() }
}

suspend fun authLogin(email: String, password: String): SavedUserAccount {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/auth/login") {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject {
                put("email", email)
                put("password", password)
            })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val token = json["token"]?.jsonPrimitive?.contentOrNull ?: ""
        val user = json["user"]?.jsonObject ?: error("No user in response")
        SavedUserAccount(
            id = user["id"]?.jsonPrimitive?.contentOrNull ?: "",
            username = user["username"]?.jsonPrimitive?.contentOrNull ?: "",
            email = user["email"]?.jsonPrimitive?.contentOrNull ?: "",
            authToken = token,
            plan = user["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
            billingStatus = user["billingStatus"]?.jsonPrimitive?.contentOrNull ?: "none",
            paidUntil = user["paidUntil"]?.jsonPrimitive?.contentOrNull,
            createdAt = user["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
            maxDevices = user["maxDevices"]?.jsonPrimitive?.intOrNull,
            isPremium = user["isPremium"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    } finally { client.close() }
}

/**
 * Verifies a saved session token. Returns the fresh account when the token
 * is valid.
 *
 * Failure contract (THE CALLER MUST MATCH):
 *  - Returns `null` ONLY for a genuine server rejection (401/403) — the
 *    session is truly dead and must be cleared.
 *  - Throws for transient errors (timeout, DNS, 5xx) so the caller can keep
 *    the saved session and retry later. A TV boots before the network is up;
 *    clearing the session on a transient failure is what caused the
 *    "logged me out after 1 day" bug.
 */
suspend fun authMe(token: String): SavedUserAccount? {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/auth/me") {
            bearerAuth(token)
        }
        if (resp.status == HttpStatusCode.Unauthorized || resp.status == HttpStatusCode.Forbidden) return null
        if (resp.status != HttpStatusCode.OK) throw IllegalStateException("Auth check failed (${resp.status.value})")
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val user = json["user"]?.jsonObject ?: throw IllegalStateException("Auth check returned no user")
        SavedUserAccount(
            id = user["id"]?.jsonPrimitive?.contentOrNull ?: "",
            username = user["username"]?.jsonPrimitive?.contentOrNull ?: "",
            email = user["email"]?.jsonPrimitive?.contentOrNull ?: "",
            authToken = token,
            plan = user["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
            billingStatus = user["billingStatus"]?.jsonPrimitive?.contentOrNull ?: "none",
            paidUntil = user["paidUntil"]?.jsonPrimitive?.contentOrNull,
            createdAt = user["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
            maxDevices = user["maxDevices"]?.jsonPrimitive?.intOrNull,
            isPremium = user["isPremium"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    } finally { client.close() }
}

suspend fun authLogout(token: String) {
    val client = platformHttpClient()
    try { client.post("${ApiConfig.API_BASE_URL}/auth/logout") { bearerAuth(token) } }
    finally { client.close() }
}

// ── TV ↔ Phone Pairing ──────────────────────────────────────────────────────
data class TvPairStart(
    val pairId: String = "",
    val code: String = "",
    val qrContent: String = "",
    val expiresInSeconds: Int = 300
)

sealed class TvPairPollState {
    data object Pending : TvPairPollState()
    data class Approved(val account: SavedUserAccount) : TvPairPollState()
    data object Expired : TvPairPollState()
}

suspend fun startTvPair(): TvPairStart {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/tv-pair/start")
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        TvPairStart(
            pairId = json["pairId"]?.jsonPrimitive?.contentOrNull ?: "",
            code = json["code"]?.jsonPrimitive?.contentOrNull ?: "",
            qrContent = json["qrContent"]?.jsonPrimitive?.contentOrNull ?: "",
            expiresInSeconds = json["expiresInSeconds"]?.jsonPrimitive?.intOrNull ?: 300
        )
    } finally { client.close() }
}

suspend fun pollTvPairStatus(pairId: String): TvPairPollState {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/tv-pair/status") {
            parameter("pair", pairId)
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        when (json["status"]?.jsonPrimitive?.contentOrNull) {
            "approved" -> {
                val user = json["user"]?.jsonObject
                val token = json["token"]?.jsonPrimitive?.contentOrNull ?: ""
                TvPairPollState.Approved(
                    SavedUserAccount(
                        id = user?.get("id")?.jsonPrimitive?.contentOrNull ?: "",
                        username = user?.get("username")?.jsonPrimitive?.contentOrNull ?: "",
                        email = user?.get("email")?.jsonPrimitive?.contentOrNull ?: "",
                        authToken = token,
                        plan = user?.get("plan")?.jsonPrimitive?.contentOrNull ?: "free",
                        billingStatus = user?.get("billingStatus")?.jsonPrimitive?.contentOrNull ?: "none",
                        paidUntil = user?.get("paidUntil")?.jsonPrimitive?.contentOrNull,
                        createdAt = user?.get("createdAt")?.jsonPrimitive?.contentOrNull ?: "",
                        maxDevices = user?.get("maxDevices")?.jsonPrimitive?.intOrNull,
                        isPremium = user?.get("isPremium")?.jsonPrimitive?.booleanOrNull ?: false
                    )
                )
            }
            "expired" -> TvPairPollState.Expired
            else -> TvPairPollState.Pending
        }
    } catch (_: Exception) {
        TvPairPollState.Pending
    } finally { client.close() }
}

// ── Content ─────────────────────────────────────────────────────────────────
suspend fun fetchContentHome(type: String, page: Int = 1): List<UnifiedSearchResult> {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/content/home") {
            parameter("type", type)
            parameter("page", page)
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return emptyList()
        val items = data["items"]?.jsonArray ?: return emptyList()
        items.map { it.jsonObject.toUnifiedResult() }
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

suspend fun searchContent(type: String, query: String, page: Int = 1): List<UnifiedSearchResult> {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/content/search") {
            parameter("type", type)
            parameter("q", query)
            parameter("page", page)
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return emptyList()
        val items = data["items"]?.jsonArray ?: return emptyList()
        items.map { it.jsonObject.toUnifiedResult() }
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

/**
 * Fetches similar/recommended titles for a video (TMDB-backed
 * /api/content/similar). Used by the TV movie-end rail: when a movie
 * finishes, the player shows these on the right so the user can pick the
 * next thing with the remote.
 */
suspend fun fetchSimilarContent(detailUrl: String, limit: Int = 12): List<UnifiedSearchResult> {
    if (detailUrl.isBlank()) return emptyList()
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/content/similar") {
            parameter("detailUrl", detailUrl)
            parameter("limit", limit.coerceIn(1, 24))
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return emptyList()
        val items = data["items"]?.jsonArray ?: return emptyList()
        items.map { it.jsonObject.toUnifiedResult() }
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

suspend fun fetchChapters(kind: String, detailUrl: String, title: String, sourceName: String): List<Chapter> {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/content/chapters") {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject {
                put("kind", kind)
                put("detailUrl", detailUrl)
                put("title", title)
                put("sourceName", sourceName)
            })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return emptyList()
        val chapters = data["chapters"]?.jsonArray ?: return emptyList()
        chapters.map { Chapter(
            title = it.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: "",
            url = it.jsonObject["url"]?.jsonPrimitive?.contentOrNull ?: "",
            chapterNumber = it.jsonObject["chapterNumber"]?.jsonPrimitive?.intOrNull ?: 0
        ) }
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

suspend fun fetchChapterText(chapterUrl: String, title: String, sourceName: String): String {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/content/chapter-text") {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject {
                put("chapterUrl", chapterUrl)
                put("title", title)
                put("sourceName", sourceName)
            })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        json["data"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull ?: ""
    } catch (_: Exception) { "" }
    finally { client.close() }
}

suspend fun fetchMangaPages(chapterUrl: String): List<String> {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/content/manga-pages") {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject { put("chapterUrl", chapterUrl) })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return emptyList()
        data["pages"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

suspend fun fetchWatchRoute(kind: String, title: String, detailUrl: String): String? {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/content/watch-route") {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject {
                put("kind", kind)
                put("title", title)
                put("detailUrl", detailUrl)
            })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        json["url"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) { null }
    finally { client.close() }
}

suspend fun fetchWatchRoutes(kind: String, title: String, detailUrl: String): List<String> {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/content/watch-routes") {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject {
                put("kind", kind)
                put("title", title)
                put("detailUrl", detailUrl)
            })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return emptyList()
        val routes = data["routes"]?.jsonArray ?: return emptyList()
        routes.mapNotNull { route ->
            val obj = route.jsonObject
            obj["url"]?.jsonPrimitive?.contentOrNull
        }
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

// ── Real watch servers (with provider names shown in the TV server picker) ──
data class WatchRouteOption(
    val provider: String,
    val url: String,
    val route: String = "embed"
)

suspend fun fetchWatchRouteOptions(kind: String, title: String, detailUrl: String): List<WatchRouteOption> {
    val client = platformHttpClient()
    val list = mutableListOf<WatchRouteOption>()

    val episodeMatch = Regex("""tmdb-episode://(\d+)/(\d+)/(\d+)""").find(detailUrl)
    val season = episodeMatch?.groupValues?.getOrNull(2) ?: "1"
    val episode = episodeMatch?.groupValues?.getOrNull(3) ?: "1"

    try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/content/watch-routes") {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject {
                put("kind", kind)
                put("title", title)
                put("detailUrl", detailUrl)
                put("season", season)
                put("episode", episode)
                put("episodeMarker", detailUrl)
            })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject
        val routes = data?.get("routes")?.jsonArray
        routes?.mapNotNullTo(list) { route ->
            val obj = route.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNullTo null
            if (url.isBlank()) return@mapNotNullTo null
            WatchRouteOption(
                provider = obj["provider"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Server",
                url = url,
                route = obj["route"]?.jsonPrimitive?.contentOrNull ?: "embed"
            )
        }
    } catch (_: Exception) { }
    finally { client.close() }

    val encodedTitle = title.encodeURLQueryComponent()

    // Animexin Server — available for ONLY Donghua and Anime
    val isDonghuaOrAnime = kind.equals("anime", ignoreCase = true) ||
            kind.equals("donghua", ignoreCase = true) ||
            title.contains("donghua", ignoreCase = true) ||
            title.contains("anime", ignoreCase = true)
    if (isDonghuaOrAnime) {
        list.add(
            WatchRouteOption(
                provider = "Animexin (Donghua & Anime)",
                url = "https://animexin.dev/?s=$encodedTitle",
                route = "embed"
            )
        )
    }

    return list.distinctBy { it.provider }
}

// ── Real anime episodes via Consumet (same backend the Android app uses) ──
suspend fun fetchAnimeEpisodes(provider: String, query: String, limit: Int = 300): List<Chapter> {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/content/anime/episodes") {
            parameter("provider", provider)
            parameter("q", query)
            parameter("limit", limit.coerceAtLeast(1))
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return emptyList()
        val episodes = data["episodes"]?.jsonArray ?: return emptyList()
        episodes.mapNotNull { element ->
            val obj = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val number = obj["episodeNumber"]?.jsonPrimitive?.intOrNull
                ?: obj["number"]?.jsonPrimitive?.intOrNull ?: 0
            val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            if (url.isBlank()) return@mapNotNull null
            Chapter(
                title = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                    ?: "Episode ${number.takeIf { it > 0 } ?: ""}".trim(),
                url = url,
                chapterNumber = number
            )
        }
            .distinctBy { it.url }
            .sortedByDescending { it.chapterNumber }
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

suspend fun fetchAnimeStream(provider: String, episodeId: String): String? {
    val client = platformHttpClient()
    return try {
        // Chapter URLs from the episodes list are consumet://{provider}/{encodedId}
        // markers (same as the Android app). Strip the marker so the server
        // receives the real Consumet episode id.
        val ref = if (episodeId.startsWith("consumet://", ignoreCase = true)) {
            val rest = episodeId.removePrefix("consumet://")
            val slash = rest.indexOf('/')
            if (slash > 0) rest.substring(slash + 1).decodeURLQueryComponent() else ""
        } else {
            episodeId
        }
        if (ref.isBlank()) return null
        val resp = client.get("${ApiConfig.API_BASE_URL}/content/anime/stream") {
            parameter("provider", provider)
            parameter("episodeId", ref)
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return null
        val route = data["route"]?.jsonPrimitive?.contentOrNull ?: ""
        val streamUrl = data["url"]?.jsonPrimitive?.contentOrNull ?: ""
        if (route.equals("direct", ignoreCase = true) && streamUrl.isNotBlank()) streamUrl else null
    } catch (_: Exception) { null }
    finally { client.close() }
}

// ── Free preview limits (server-driven, matches the phone app) ──
data class FreePreviewLimits(
    val episodicFraction: Double = 0.2,
    val movieMs: Long = 20 * 60 * 1000L
)

suspend fun fetchFreePreviewLimits(): FreePreviewLimits? {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/billing/status")
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val preview = json["freePreview"]?.jsonObject ?: return null
        FreePreviewLimits(
            episodicFraction = preview["episodicFraction"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.2,
            movieMs = preview["movieMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: (20 * 60 * 1000L)
        )
    } catch (_: Exception) { null }
    finally { client.close() }
}

// ── Anilist ─────────────────────────────────────────────────────────────────
suspend fun fetchAnilistPopular(): List<UnifiedSearchResult> {
    val client = platformHttpClient()
    return try {
        val query = """{ Page(page: 1, perPage: 20) { media(type: ANIME, sort: TRENDING_DESC) { id title { romaji english } coverImage { large } genres description(asHtml: false) episodes nextAiringEpisode { episode airingAt } status } } }"""
        val resp = client.post(ApiConfig.ANILIST_GRAPHQL) {
            contentType(KtorContentType.Application.Json)
            setBody(buildJsonObject { put("query", query) })
        }
        val body = resp.bodyAsText()
        val root = apiJson.parseToJsonElement(body).jsonObject
        val media = root["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray ?: return emptyList()
        media.map { el ->
            val obj = el.jsonObject
            val title = obj["title"]?.jsonObject
            val eng = title?.get("english")?.jsonPrimitive?.contentOrNull ?: ""
            val rom = title?.get("romaji")?.jsonPrimitive?.contentOrNull ?: ""
            val display = eng.ifBlank { rom }
            UnifiedSearchResult(
                id = "anilist_${obj["id"]?.jsonPrimitive?.content}",
                title = display,
                coverUrl = obj["coverImage"]?.jsonObject?.get("large")?.jsonPrimitive?.contentOrNull ?: "",
                synopsis = (obj["description"]?.jsonPrimitive?.contentOrNull ?: "").replace(Regex("<[^>]*>"), ""),
                sourceName = "AniList",
                isAnime = true,
                genre = obj["genres"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(", ") ?: ""
            )
        }
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

// ── Billing ─────────────────────────────────────────────────────────────────
suspend fun billingStatus(token: String): JsonObject? {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/billing/status") { bearerAuth(token) }
        val body = resp.bodyAsText()
        apiJson.parseToJsonElement(body).jsonObject
    } catch (_: Exception) { null }
    finally { client.close() }
}

suspend fun createCheckout(token: String, planId: String): BillingCheckout {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/billing/checkout") {
            contentType(KtorContentType.Application.Json)
            bearerAuth(token)
            setBody(buildJsonObject { put("planId", planId) })
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        BillingCheckout(
            link = json["link"]?.jsonPrimitive?.contentOrNull ?: "",
            txRef = json["txRef"]?.jsonPrimitive?.contentOrNull ?: "",
            amount = json["amount"]?.jsonPrimitive?.intOrNull ?: 1000,
            currency = json["currency"]?.jsonPrimitive?.contentOrNull ?: "NGN",
            alreadyPremium = json["alreadyPremium"]?.jsonPrimitive?.booleanOrNull ?: false,
            premium = json["premium"]?.jsonPrimitive?.booleanOrNull ?: false
        )
    } finally { client.close() }
}

// ── User State Sync ─────────────────────────────────────────────────────────
suspend fun getUserState(token: String): JsonObject? {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/user/state") { bearerAuth(token) }
        val body = resp.bodyAsText()
        apiJson.parseToJsonElement(body).jsonObject
    } catch (_: Exception) { null }
    finally { client.close() }
}

suspend fun putUserState(token: String, state: JsonObject): Boolean {
    val client = platformHttpClient()
    return try {
        val resp = client.put("${ApiConfig.API_BASE_URL}/user/state") {
            contentType(KtorContentType.Application.Json)
            bearerAuth(token)
            setBody(state.toString())
        }
        resp.status == HttpStatusCode.OK
    } catch (_: Exception) { false }
    finally { client.close() }
}

// ── Football ─────────────────────────────────────────────────────────────────
suspend fun fetchFootballFixtures(): List<JsonObject> {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/football/fixtures")
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val data = json["data"]?.jsonObject ?: return emptyList()
        data["response"]?.jsonArray?.mapNotNull { it.jsonObject } ?: emptyList()
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

suspend fun fetchFootballStream(fixtureId: Int, home: String, away: String, league: String): String? {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/football/stream") {
            parameter("fixture", fixtureId)
            parameter("home", home)
            parameter("away", away)
            parameter("league", league)
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        json["data"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) { null }
    finally { client.close() }
}

/**
 * Resolve a football match for fullscreen playback, mirroring the Android
 * app's FootballSource ladder:
 * 1. POST /api/football/direct-stream — server scrapes aggregators for a raw
 *    .m3u8 / direct .mp4 (playable in ExoPlayer).
 * 2. Fallback — ScoreBat matchview embed (works in the WebView player and
 *    the WebView intercepts the HLS stream for ExoPlayer).
 */
suspend fun resolveFootballTvStream(
    home: String,
    away: String,
    league: String = ""
): String? {
    // Step 1: backend direct-stream scraper (Server 2)
    val direct = run {
        val client = platformHttpClient()
        try {
            val resp = client.post("${ApiConfig.API_BASE_URL}/football/direct-stream") {
                contentType(KtorContentType.Application.Json)
                setBody(buildJsonObject {
                    put("homeTeam", home)
                    put("awayTeam", away)
                    put("leagueName", league)
                })
            }
            val body = resp.bodyAsText()
            val json = apiJson.parseToJsonElement(body).jsonObject
            val ok = json["ok"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!ok) return@run null
            val data = json["data"]?.jsonObject ?: return@run null
            val url = data["url"]?.jsonPrimitive?.contentOrNull ?: return@run null
            val isDirect = data["direct"]?.jsonPrimitive?.booleanOrNull ?: false
            if (isDirect && url.isNotBlank()) url else null
        } catch (_: Exception) { null }
        finally { client.close() }
    }
    if (!direct.isNullOrBlank()) return direct

    // Step 2: ScoreBat matchview embed fallback (Servers 1 + 3)
    val searchQuery = buildString {
        if (home.isNotBlank()) append(home.replace(" ", "+"))
        if (away.isNotBlank()) {
            if (isNotEmpty()) append("+vs+")
            append(away.replace(" ", "+"))
        }
    }
    return if (searchQuery.isNotBlank()) {
        "https://www.scorebat.com/embed/livescore/?search=$searchQuery"
    } else {
        "https://www.scorebat.com/embed/"
    }
}

// ── WWE ──────────────────────────────────────────────────────────────────────
suspend fun fetchWweEvents(): List<JsonObject> {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/wwe/events")
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        json["data"]?.jsonArray?.mapNotNull { it.jsonObject } ?: emptyList()
    } catch (_: Exception) { emptyList() }
    finally { client.close() }
}

suspend fun fetchWweStream(id: String, title: String, eventType: String): String? {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/wwe/stream") {
            parameter("id", id)
            parameter("title", title)
            parameter("eventType", eventType)
        }
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        json["data"]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) { null }
    finally { client.close() }
}

// ── Helpers ─────────────────────────────────────────────────────────────────
private fun JsonObject.toUnifiedResult(): UnifiedSearchResult {
    val detailUrl = this["detailUrl"]?.jsonPrimitive?.contentOrNull
        ?: this["detail_url"]?.jsonPrimitive?.contentOrNull ?: ""
    val kind = this["kind"]?.jsonPrimitive?.contentOrNull ?: ""
    return UnifiedSearchResult(
        id = this["id"]?.jsonPrimitive?.contentOrNull ?: "",
        title = this["title"]?.jsonPrimitive?.contentOrNull ?: "",
        coverUrl = this["coverUrl"]?.jsonPrimitive?.contentOrNull ?: "",
        detailPageUrl = detailUrl,
        sourceName = this["sourceName"]?.jsonPrimitive?.contentOrNull ?: "",
        author = this["subtitle"]?.jsonPrimitive?.contentOrNull ?: "",
        genre = this["subtitle"]?.jsonPrimitive?.contentOrNull ?: "",
        synopsis = this["synopsis"]?.jsonPrimitive?.contentOrNull ?: "",
        isManga = kind == "manga",
        isComic = kind == "comic",
        isAnime = kind == "anime",
        isVideo = kind in listOf("movie", "kdrama", "cartoon", "donghua", "classic", "nigerian"),
        mediaKind = kind
    )
}
