package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.BuildKonfig
import com.alexleoreeves.novelapp.platform.platformHttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object GroqTextCleaner {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val MODEL = "llama-3.1-8b-instant"
    private const val CHUNK_SIZE = 6_000

    suspend fun cleanForKokoro(rawText: String): String {
        val apiKey = BuildKonfig.GROQ_API_KEY.trim()
        if (rawText.isBlank() || apiKey.isBlank() || apiKey.startsWith("mock_", ignoreCase = true)) {
            return rawText
        }
        return runCatching {
            rawText.chunked(CHUNK_SIZE)
                .map { chunk -> cleanChunk(apiKey, chunk) }
                .joinToString("\n\n")
                .ifBlank { rawText }
        }.getOrDefault(rawText)
    }

    private suspend fun cleanChunk(apiKey: String, chunk: String): String {
        val client = platformHttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 10_000
                requestTimeoutMillis = 35_000
                socketTimeoutMillis = 35_000
            }
        }
        return try {
            val response = client.post("https://api.groq.com/openai/v1/chat/completions") {
                accept(ContentType.Application.Json)
                contentType(ContentType.Application.Json)
                bearerAuth(apiKey)
                setBody(
                    buildJsonObject {
                        put("model", MODEL)
                        put("temperature", 0.1)
                        put("max_tokens", 2200)
                        put(
                            "messages",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("role", "system")
                                        put(
                                            "content",
                                            "Clean this novel text for audiobook narration. Remove scrape artifacts, menus, ads, duplicate headings, chapter nav, and broken spacing. Keep the story wording, dialogue, paragraph order, names, and punctuation."
                                        )
                                    }
                                )
                                add(
                                    buildJsonObject {
                                        put("role", "user")
                                        put("content", chunk)
                                    }
                                )
                            }
                        )
                    }
                )
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("message")
                ?.jsonObject
                ?.get("content")
                ?.jsonPrimitive
                ?.content
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: chunk
        } finally {
            client.close()
        }
    }
}
