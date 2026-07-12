package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.platform.AppReleaseConfig
import com.alexleoreeves.novelapp.platform.platformHttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class UserNovel(
    val id: String = "",
    val author_id: String? = null,
    val author_name: String = "Anonymous",
    val title: String = "",
    val cover_url: String = "",
    val description: String = "",
    val status: String = "draft",
    val created_at: String = "",
    val updated_at: String = "",
    val chapters: List<UserNovelChapter> = emptyList()
)

@Serializable
data class UserNovelChapter(
    val id: String = "",
    val novel_id: String = "",
    val chapter_number: Int = 0,
    val title: String = "",
    val content: String = "",
    val created_at: String = ""
)

@Serializable
data class CreateNovelRequest(
    val title: String,
    val cover_url: String = "",
    val description: String = ""
)

@Serializable
data class CreateNovelResponse(
    val ok: Boolean,
    val novel: UserNovel? = null
)

@Serializable
data class NovelListResponse(
    val novels: List<UserNovel>
)

@Serializable
data class AddChapterRequest(
    val novel_id: String,
    val chapter_number: Int,
    val title: String = "",
    val content: String = ""
)

@Serializable
data class AddChapterResponse(
    val ok: Boolean,
    val chapter: UserNovelChapter? = null
)

@Serializable
data class SimpleResponse(
    val ok: Boolean
)

class UserNovelApi(
    private val baseUrl: String = AppReleaseConfig.API_BASE_URL
) {
    private val client = platformHttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
            socketTimeoutMillis = 30_000
        }
    }

    /** Create a new novel */
    suspend fun createNovel(title: String, coverUrl: String, description: String, token: String): UserNovel {
        val response = client.post("$baseUrl/novels/create") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            bearerAuth(token)
            setBody(CreateNovelRequest(title = title, cover_url = coverUrl, description = description))
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Create novel failed: ${response.bodyAsText()}")
        }
        val res: CreateNovelResponse = response.body()
        return res.novel ?: throw Exception("No novel returned")
    }

    /** Add a chapter to a novel */
    suspend fun addChapter(novelId: String, chapterNumber: Int, title: String, content: String, token: String): UserNovelChapter {
        val response = client.post("$baseUrl/novels/chapter") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            bearerAuth(token)
            setBody(AddChapterRequest(novel_id = novelId, chapter_number = chapterNumber, title = title, content = content))
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Add chapter failed: ${response.bodyAsText()}")
        }
        val res: AddChapterResponse = response.body()
        return res.chapter ?: throw Exception("No chapter returned")
    }

    /** Publish a novel (change status from draft to published) */
    suspend fun publishNovel(novelId: String, token: String) {
        val response = client.post("$baseUrl/novels/publish") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            bearerAuth(token)
            setBody(mapOf("novel_id" to novelId))
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Publish failed: ${response.bodyAsText()}")
        }
    }

    /** Get all published novels (community feed) */
    suspend fun getNovels(page: Int = 1, token: String): List<UserNovel> {
        val response = client.get("$baseUrl/novels") {
            accept(ContentType.parse("application/json"))
            bearerAuth(token)
            parameter("page", page)
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Fetch novels failed: ${response.bodyAsText()}")
        }
        val res: NovelListResponse = response.body()
        return res.novels
    }

    /** Get novels by current user */
    suspend fun getMyNovels(token: String): List<UserNovel> {
        val response = client.get("$baseUrl/novels/mine") {
            accept(ContentType.parse("application/json"))
            bearerAuth(token)
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Fetch my novels failed: ${response.bodyAsText()}")
        }
        val res: NovelListResponse = response.body()
        return res.novels
    }

    /** Get a single novel by id with chapters */
    suspend fun getNovelById(id: String, token: String): UserNovel {
        val response = client.get("$baseUrl/novels/$id") {
            accept(ContentType.parse("application/json"))
            bearerAuth(token)
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Fetch novel failed: ${response.bodyAsText()}")
        }
        return response.body()
    }
}
