package com.alexleoreeves.novelapp.tv.data

import com.alexleoreeves.novelapp.tv.platform.SavedUserAccount
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

object ApiConfig {
    const val API_BASE_URL = "https://novelapp1.onrender.com/api"
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
            contentType(ContentType.Application.Json)
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
            maxDevices = user["maxDevices"]?.jsonPrimitive?.intOrNull
        )
    } finally { client.close() }
}

suspend fun authLogin(email: String, password: String): SavedUserAccount {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/auth/login") {
            contentType(ContentType.Application.Json)
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
            maxDevices = user["maxDevices"]?.jsonPrimitive?.intOrNull
        )
    } finally { client.close() }
}

suspend fun authMe(token: String): SavedUserAccount? {
    val client = platformHttpClient()
    return try {
        val resp = client.get("${ApiConfig.API_BASE_URL}/auth/me") {
            bearerAuth(token)
        }
        if (resp.status != HttpStatusCode.OK) return null
        val body = resp.bodyAsText()
        val json = apiJson.parseToJsonElement(body).jsonObject
        val user = json["user"]?.jsonObject ?: return null
        SavedUserAccount(
            id = user["id"]?.jsonPrimitive?.contentOrNull ?: "",
            username = user["username"]?.jsonPrimitive?.contentOrNull ?: "",
            email = user["email"]?.jsonPrimitive?.contentOrNull ?: "",
            authToken = token,
            plan = user["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
            billingStatus = user["billingStatus"]?.jsonPrimitive?.contentOrNull ?: "none",
            paidUntil = user["paidUntil"]?.jsonPrimitive?.contentOrNull,
            createdAt = user["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
            maxDevices = user["maxDevices"]?.jsonPrimitive?.intOrNull
        )
    } finally { client.close() }
}

suspend fun authLogout(token: String) {
    val client = platformHttpClient()
    try { client.post("${ApiConfig.API_BASE_URL}/auth/logout") { bearerAuth(token) } }
    finally { client.close() }
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

suspend fun fetchChapters(kind: String, detailUrl: String, title: String, sourceName: String): List<Chapter> {
    val client = platformHttpClient()
    return try {
        val resp = client.post("${ApiConfig.API_BASE_URL}/content/chapters") {
            contentType(ContentType.Application.Json)
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
            contentType(ContentType.Application.Json)
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
            contentType(ContentType.Application.Json)
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
            contentType(ContentType.Application.Json)
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
            contentType(ContentType.Application.Json)
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

// ── Anilist ─────────────────────────────────────────────────────────────────
suspend fun fetchAnilistPopular(): List<UnifiedSearchResult> {
    val client = platformHttpClient()
    return try {
        val query = """{ Page(page: 1, perPage: 20) { media(type: ANIME, sort: TRENDING_DESC) { id title { romaji english } coverImage { large } genres description(asHtml: false) episodes nextAiringEpisode { episode airingAt } status } } }"""
        val resp = client.post(ApiConfig.ANILIST_GRAPHQL) {
            contentType(ContentType.Application.Json)
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
            contentType(ContentType.Application.Json)
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
            contentType(ContentType.Application.Json)
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
