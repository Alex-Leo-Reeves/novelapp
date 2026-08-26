package com.alexleoreeves.novelapp.nodebridge

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.alexleoreeves.novelapp.data.AnivexaApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Headless Chromium WebView runtime that executes the Anivexa anime worker
 * directly on the device's residential IP network — completely replacing the
 * unstable NodeJS-Mobile C++ runtime without any native crashes.
 *
 * Architecture:
 *   1. Starts a lightweight, non-blocking local HTTP loopback server on 127.0.0.1.
 *   2. Instantiates a headless Android WebView on the Main Looper and loads
 *      the bundled `anivexa/index.html`.
 *   3. Exposes `@JavascriptInterface` bridge between Kotlin and the WebView worker.
 *   4. Forwards HTTP calls from [AnivexaApi] to the WebView, which runs native
 *      browser fetch() on the residential network and returns normalized JSON.
 *   5. Points [AnivexaApi.setEmbeddedBaseUrl] to `http://127.0.0.1:<port>`.
 */
object WebViewBridgeRuntime {

    private const val TAG = "WebViewBridge"
    private val started = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val reqCounter = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<Pair<Int, String>>>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var boundPort: Int = 0

    @Volatile
    private var isWorkerReady: Boolean = false

    /** Non-blocking entry point called once during app startup. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        scope.launch {
            try {
                // 1. Start local loopback HTTP server
                val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = server
                boundPort = server.localPort
                Log.i(TAG, "Local loopback HTTP server listening on 127.0.0.1:$boundPort")

                // Launch socket accept loop in background
                scope.launch { acceptConnections(server) }

                // 2. Initialize Headless WebView on UI thread
                mainHandler.post {
                    try {
                        initHeadlessWebView(appContext)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Failed to initialize Headless WebView", t)
                        NodeBridgeStatus.reportFailure("WebView anime engine initialization failed: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start WebView bridge server", t)
                NodeBridgeStatus.reportFailure("Failed to start local anime engine server: ${t.message}")
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initHeadlessWebView(context: Context) {
        val wv = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Standard modern browser User-Agent
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message() ?: ""
                    val line = consoleMessage?.lineNumber() ?: 0
                    Log.d(TAG, "[JS:$line] $msg")
                    return true
                }
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.i(TAG, "Headless WebView loaded: $url")
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    return super.shouldInterceptRequest(view, request)
                }
            }

            addJavascriptInterface(AndroidJsBridge(), "AndroidBridge")
        }

        webView = wv
        wv.loadUrl("file:///android_asset/anivexa/index.html")
    }

    private class AndroidJsBridge {
        @JavascriptInterface
        fun postResponse(requestId: String, statusCode: Int, payload: String) {
            val deferred = pendingRequests.remove(requestId)
            if (deferred != null) {
                deferred.complete(statusCode to payload)
            } else {
                Log.w(TAG, "postResponse: no pending deferred for requestId $requestId")
            }
        }

        @JavascriptInterface
        fun onWorkerReady() {
            Log.i(TAG, "Anivexa JS Worker reported READY in WebView")
            isWorkerReady = true
            val port = boundPort
            if (port > 0) {
                AnivexaApi.setEmbeddedBaseUrl("http://127.0.0.1:$port")
                NodeBridgeStatus.reportStarted(port)
            }
        }
    }

    private suspend fun acceptConnections(server: ServerSocket) {
        while (!server.isClosed) {
            try {
                val socket = server.accept()
                scope.launch { handleClientSocket(socket) }
            } catch (t: Throwable) {
                if (server.isClosed) break
                Log.w(TAG, "Error accepting client socket connection", t)
            }
        }
    }

    private suspend fun handleClientSocket(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 30_000
            val input = BufferedReader(InputStreamReader(s.getInputStream()))
            val output = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)

            val requestLine = input.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullUrlPath = parts[1]

            // Drain remaining HTTP headers
            var headerLine: String?
            while (input.readLine().also { headerLine = it } != null) {
                if (headerLine.isNullOrBlank()) break
            }

            if (method == "OPTIONS") {
                output.write("HTTP/1.1 204 No Content\r\n")
                output.write("Access-Control-Allow-Origin: *\r\n")
                output.write("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
                output.write("Access-Control-Allow-Headers: *\r\n")
                output.write("Connection: close\r\n\r\n")
                output.flush()
                return
            }

            val requestId = reqCounter.incrementAndGet().toString()
            val deferred = CompletableDeferred<Pair<Int, String>>()
            pendingRequests[requestId] = deferred

            mainHandler.post {
                val wv = webView
                if (wv == null) {
                    deferred.complete(503 to "{\"ok\":false,\"error\":\"WebView worker not ready\"}")
                    return@post
                }
                val quotedPath = JSONObject.quote(fullUrlPath)
                val js = "if (window.anivexaWorkerBridge) { window.anivexaWorkerBridge.handleRequest('$requestId', $quotedPath); } else { window.AndroidBridge.postResponse('$requestId', 503, '{\"ok\":false,\"error\":\"Worker bridge not ready\"}'); }"
                wv.evaluateJavascript(js, null)
            }

            val result = withTimeoutOrNull(25_000L) {
                deferred.await()
            } ?: (504 to "{\"ok\":false,\"error\":\"Anime scraper request timed out\"}")

            val (statusCode, bodyJson) = result
            val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)

            output.write("HTTP/1.1 $statusCode OK\r\n")
            output.write("Content-Type: application/json; charset=utf-8\r\n")
            output.write("Access-Control-Allow-Origin: *\r\n")
            output.write("Cache-Control: no-store\r\n")
            output.write("Content-Length: ${bodyBytes.size}\r\n")
            output.write("Connection: close\r\n\r\n")
            output.write(bodyJson)
            output.flush()
        }
    }
}
