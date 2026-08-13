package com.alexleoreeves.novelapp.tv.mediacache

import com.alexleoreeves.novelapp.data.mediacache.MediaProbe
import com.alexleoreeves.novelapp.data.mediacache.MediaTransportPort
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess

/**
 * OkHttp-backed network port for the media-cache engine on Smart TV.
 *
 * [probe] issues a HEAD + a 1-byte Range GET to learn total size, range
 * support and content type without pulling the payload. [fetchRange] streams a
 * single encrypted-chunk byte range; transient failures (timeouts, resets,
 * 5xx) return null so the engine's scheduler retries with backoff, and only a
 * logically-done 416 surfaces as a clean stop.
 *
 * One shared OkHttp client per engine; [close] releases it on app teardown.
 */
class TvMediaTransportPort : MediaTransportPort {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
    }

    override suspend fun probe(url: String): MediaProbe {
        // HEAD can be filtered by some CDNs; treat failure as "unknown" and
        // fall through to the Range GET, which is authoritative anyway.
        val head = try {
            client.head(url)
        } catch (e: Exception) {
            null
        }
        val headType = head?.headers?.get(HttpHeaders.ContentType)

        val range = try {
            client.get(url) {
                header(HttpHeaders.Range, "bytes=0-0")
            }
        } catch (e: Exception) {
            return MediaProbe(
                totalBytes = -1L,
                supportsRanges = false,
                contentType = headType ?: "application/octet-stream"
            )
        }

        val supportsRanges = range.status == HttpStatusCode.PartialContent
        val contentRange = range.headers[HttpHeaders.ContentRange]
        var totalBytes = range.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1L

        // "Content-Range: bytes 0-0/1234567" → the /N is the real size.
        if (supportsRanges && totalBytes <= 0L && contentRange != null) {
            totalBytes = contentRange.substringAfter("/").toLongOrNull() ?: -1L
        }

        return MediaProbe(
            totalBytes = totalBytes,
            supportsRanges = supportsRanges,
            contentType = range.headers[HttpHeaders.ContentType] ?: headType ?: "application/octet-stream",
            variants = emptyList() // adaptive-ladder probing is source-specific; not needed for V1
        )
    }

    override suspend fun fetchRange(url: String, start: Long, endInclusive: Long): ByteArray? {
        return try {
            val response = client.get(url) {
                header(HttpHeaders.Range, "bytes=$start-$endInclusive")
            }
            if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                // Resume race: all bytes are already in the bundle. Null tells
                // the scheduler this chunk is effectively done.
                return null
            }
            if (!response.status.isSuccess() && response.status != HttpStatusCode.PartialContent) {
                return null
            }
            val bytes = response.bodyAsBytes()
            if (bytes.isEmpty()) null else bytes
        } catch (e: Exception) {
            null // transient — scheduler retries with exponential backoff
        }
    }

    override suspend fun fetchFull(url: String): ByteArray? {
        return try {
            val response = client.get(url)
            if (!response.status.isSuccess()) return null
            val bytes = response.bodyAsBytes()
            if (bytes.isEmpty()) null else bytes
        } catch (e: Exception) {
            null
        }
    }

    fun close() {
        client.close()
    }
}
