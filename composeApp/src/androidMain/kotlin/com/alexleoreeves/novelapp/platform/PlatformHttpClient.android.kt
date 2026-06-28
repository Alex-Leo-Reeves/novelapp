package com.alexleoreeves.novelapp.platform

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.okhttp.OkHttp

actual fun platformHttpClient(
    block: HttpClientConfig<out HttpClientEngineConfig>.() -> Unit
): HttpClient = HttpClient(OkHttp) {
    block()
}
