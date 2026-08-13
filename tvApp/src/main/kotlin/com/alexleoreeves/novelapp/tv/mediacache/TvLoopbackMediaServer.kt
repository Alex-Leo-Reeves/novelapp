package com.alexleoreeves.novelapp.tv.mediacache

import com.alexleoreeves.novelapp.data.mediacache.SeekableMediaStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * Minimal loopback HTTP server that serves the decrypted bytes of a
 * [SeekableMediaStream] (encrypted bundle → plaintext) to LibVLC.
 *
 * LibVLC cannot read our on-disk ciphertext: bundles are AES/HMAC'd, so a raw
 * `file://` URI would be garbage. Instead the controller opens the bundle
 * through [TvBundleMediaOpener], wraps the resulting plaintext stream in this
 * server and hands VLC `http://127.0.0.1:<port>/stream.<ext>`. VLC seeks with
 * standard `Range: bytes=a-b` requests; this server answers `206 Partial
 * Content` from the stream's random-access [SeekableMediaStream.readAt].
 *
 * Scope: one stream at a time, loopback only. Destroyed via [stop] when the
 * player screen is torn down.
 */
class TvLoopbackMediaServer {

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val workers = mutableListOf<Thread>()
    private val workerLock = Any()

    /** The decrypted source currently being served (single stream). */
    private var stream: SeekableMediaStream? = null
    private var contentType: String = "video/mp4"

    /**
     * Start serving [stream] on an ephemeral loopback port.
     * @return base URL like `http://127.0.0.1:PORT/stream.mp4` or null on failure.
     */
    fun start(stream: SeekableMediaStream, contentTypeValue: String): String? {
        if (running.get()) return null
        this.stream = stream
        this.contentType = contentTypeValue
        return try {
            val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            socket.reuseAddress = true
            serverSocket = socket
            running.set(true)
            val acceptThread = Thread({ acceptLoop() }, "TvLoopbackMediaServer")
            acceptThread.isDaemon = true
            acceptThread.start()
            "http://127.0.0.1:${socket.localPort}/stream.mp4"
        } catch (e: Exception) {
            null
        }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        stream?.close()
        stream = null
        synchronized(workerLock) {
            workers.forEach { thread -> runCatching { thread.interrupt() } }
            workers.clear()
        }
    }

    private fun acceptLoop() {
        val socket = serverSocket ?: return
        while (running.get()) {
            try {
                val client = socket.accept()
                spawnWorker(client)
            } catch (e: SocketException) {
                return // Socket closed by stop()
            } catch (e: Exception) {
                if (!running.get()) return
            }
        }
    }

    private fun spawnWorker(client: Socket) {
        val worker = Thread({ handleClient(client) }, "TvLoopbackWorker")
        worker.isDaemon = true
        synchronized(workerLock) { workers.add(worker) }
        worker.start()
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 20_000
            val reader = client.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            if (requestLine.isBlank()) return

            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0) ?: return
            val path = parts.getOrNull(1) ?: "/"
            if (method != "GET" || !path.startsWith("/stream")) {
                writeHead(client.getOutputStream(), 404, "text/plain", null, "Not found".encodeToByteArray())
                return
            }

            // Consume remaining request headers, capturing Range (lowercased key).
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                if (line.substring(0, colon).trim().lowercase() == "range") {
                    rangeHeader = line.substring(colon + 1).trim()
                }
            }

            val current = stream ?: run {
                writeHead(client.getOutputStream(), 500, "text/plain", null, "No stream".encodeToByteArray())
                return
            }
            val total = current.totalBytes
            val range = parseRange(rangeHeader, total)
            val output = client.getOutputStream()

            when (range) {
                null -> {
                    // 200 full response (VLC usually comes back with a Range request next).
                    writeHead(output, 200, contentType, null, null)
                    pumpRange(current, 0L, (total - 1L).coerceAtLeast(0L), output)
                }
                else -> {
                    val (start, endInclusive) = range
                    val length = endInclusive - start + 1
                    val extra = "Content-Range: bytes $start-$endInclusive/$total\r\n"
                    writeHead(output, 206, contentType, extra, null)
                    pumpRange(current, start, endInclusive, output)
                }
            }
        } catch (_: Exception) {
            // Client disconnected mid-transfer; server keeps running.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null || total <= 0L) return null
        val match = Regex("bytes=(\\d*)-(\\d*)").find(header) ?: return null
        val startRaw = match.groupValues[1]
        val endRaw = match.groupValues[2]
        if (startRaw.isEmpty()) {
            // Suffix range: last N bytes.
            val suffix = endRaw.toLongOrNull() ?: return null
            val start = (total - suffix).coerceAtLeast(0L)
            return start to (total - 1L)
        }
        val start = startRaw.toLongOrNull() ?: return null
        if (start >= total) return null
        val end = if (endRaw.isEmpty()) total - 1L
        else endRaw.toLongOrNull()?.coerceAtMost(total - 1L) ?: (total - 1L)
        return start to end
    }

    /**
     * Write the HTTP status line + headers (+ optional body) WITHOUT closing the
     * stream. For 206 the body is pumped by [pumpRange] after the head; for a
     * 4xx/5xx [body] (when non-null) is written here.
     */
    private fun writeHead(
        output: OutputStream,
        status: Int,
        type: String,
        extraHeaders: String?,
        body: ByteArray?
    ) {
        val reason = when (status) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val contentLength = body?.size?.toLong() ?: -1L
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status $reason\r\n")
        sb.append("Content-Type: $type\r\n")
        if (contentLength >= 0L) sb.append("Content-Length: $contentLength\r\n")
        if (extraHeaders != null) sb.append(extraHeaders)
        sb.append("Accept-Ranges: bytes\r\n")
        sb.append("Connection: close\r\n\r\n")
        val head = sb.toString().encodeToByteArray()

        val buffered = BufferedOutputStream(output)
        buffered.write(head)
        if (body != null) buffered.write(body)
        buffered.flush()
    }

    /** Stream [start, endInclusive] plaintext bytes from the seekable source. */
    private fun pumpRange(stream: SeekableMediaStream, start: Long, endInclusive: Long, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        var offset = start
        val end = endInclusive.coerceAtMost(stream.totalBytes - 1L)
        val outputStream = BufferedOutputStream(output, 256 * 1024)
        try {
            while (offset <= end && running.get()) {
                val remaining = end - offset + 1L
                val want = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                // readAt fills up to dst.size; reuse the full buffer and only
                // shrink (copy) for the final partial read so the Content-Length
                // header stays accurate.
                val dst = if (want == buffer.size) buffer else buffer.copyOf(want)
                val read = runBlocking { stream.readAt(offset, dst) }
                if (read <= 0) break
                outputStream.write(dst, 0, read)
                offset += read
            }
            outputStream.flush()
        } catch (_: Exception) {
            // Client gone — stop pumping.
        }
    }
}
