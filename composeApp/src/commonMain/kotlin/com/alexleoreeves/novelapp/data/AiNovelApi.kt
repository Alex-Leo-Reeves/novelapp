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
data class AiNovel(
    val id: String = "",
    val author_id: String? = null,
    val author_name: String = "Anonymous",
    val title: String,
    val cover_prompt: String = "",
    val cover_url: String = "",
    val content: String = "",
    val type: String = "short",
    val source_novels: List<UnifiedSearchResult> = emptyList(),
    val genres: String = "Crossover",
    val word_count: Int = 0,
    val created_at: String = ""
)

@Serializable
data class AiQuota(
    val usedShort: Int,
    val usedLong: Int,
    val limitShort: Int,
    val limitLong: Int,
    val plan: String
)

@Serializable
data class AiNovelStartRequest(
    val type: String
)

@Serializable
data class AiNovelStartResponse(
    val ok: Boolean,
    val message: String
)

@Serializable
data class AiNovelChunkRequest(
    val chunkText: String,
    val sourceName: String
)

@Serializable
data class AiNovelChunkResponse(
    val summary: String
)

@Serializable
data class AiNovelCompleteRequest(
    val type: String,
    val sourceNovels: List<UnifiedSearchResult>,
    val userDescription: String,
    val profiles: List<String>,
    val outline: String = ""
)

@Serializable
data class AiNovelCompleteResponse(
    val title: String,
    val coverPrompt: String,
    val content: String,
    val coverUrl: String
)

@Serializable
data class AiNovelPublishRequest(
    val title: String,
    val coverPrompt: String,
    val coverUrl: String,
    val content: String,
    val type: String,
    val sourceNovels: List<UnifiedSearchResult>,
    val genres: String
)

@Serializable
data class AiNovelPublishResponse(
    val ok: Boolean,
    val novel: AiNovel? = null
)

@Serializable
data class AiNovelListResponse(
    val novels: List<AiNovel>
)

class AiNovelApi(
    private val baseUrl: String = AppReleaseConfig.API_BASE_URL
) {
    private val client = platformHttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 60_000
            socketTimeoutMillis = 60_000
        }
    }

    suspend fun fetchQuota(token: String): AiQuota {
        val response = client.get("$baseUrl/ai/quota") {
            accept(ContentType.parse("application/json"))
            bearerAuth(token)
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to fetch AI quota: ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun generateStart(type: String, token: String): AiNovelStartResponse {
        val response = client.post("$baseUrl/ai/generate/start") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            bearerAuth(token)
            setBody(AiNovelStartRequest(type = type))
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception(response.bodyAsText())
        }
        return response.body()
    }

    suspend fun generateChunk(chunkText: String, sourceName: String, token: String): AiNovelChunkResponse {
        val response = client.post("$baseUrl/ai/generate/chunk") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            bearerAuth(token)
            setBody(AiNovelChunkRequest(chunkText = chunkText, sourceName = sourceName))
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to generate chunk: ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun generateComplete(
        type: String,
        sourceNovels: List<UnifiedSearchResult>,
        userDescription: String,
        profiles: List<String>,
        token: String
    ): AiNovelCompleteResponse {
        val response = client.post("$baseUrl/ai/generate/complete") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            bearerAuth(token)
            setBody(
                AiNovelCompleteRequest(
                    type = type,
                    sourceNovels = sourceNovels,
                    userDescription = userDescription,
                    profiles = profiles
                )
            )
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to finalize AI novel: ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun fetchCommunityNovels(page: Int, token: String): List<AiNovel> {
        val response = client.get("$baseUrl/ai-novels") {
            accept(ContentType.parse("application/json"))
            bearerAuth(token)
            parameter("page", page)
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to fetch community novels: ${response.bodyAsText()}")
        }
        val res: AiNovelListResponse = response.body()
        return res.novels
    }

    suspend fun fetchNovelById(id: String, token: String): AiNovel {
        val response = client.get("$baseUrl/ai-novels/$id") {
            accept(ContentType.parse("application/json"))
            bearerAuth(token)
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to fetch AI novel: ${response.bodyAsText()}")
        }
        return response.body()
    }

    suspend fun publishNovel(
        title: String,
        coverPrompt: String,
        coverUrl: String,
        content: String,
        type: String,
        sourceNovels: List<UnifiedSearchResult>,
        genres: String,
        token: String
    ): AiNovelPublishResponse {
        val response = client.post("$baseUrl/ai-novels/publish") {
            accept(ContentType.parse("application/json"))
            contentType(ContentType.parse("application/json"))
            bearerAuth(token)
            setBody(
                AiNovelPublishRequest(
                    title = title,
                    coverPrompt = coverPrompt,
                    coverUrl = coverUrl,
                    content = content,
                    type = type,
                    sourceNovels = sourceNovels,
                    genres = genres
                )
            )
        }
        if (response.status != HttpStatusCode.OK) {
            throw Exception("Failed to publish AI novel: ${response.bodyAsText()}")
        }
        return response.body()
    }
}
