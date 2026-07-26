package com.alexleoreeves.novelapp.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformHttpClient(
    block: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit
): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            connectTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
    block()
}
