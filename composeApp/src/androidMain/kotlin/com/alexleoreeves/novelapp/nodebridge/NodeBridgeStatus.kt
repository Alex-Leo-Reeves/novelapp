package com.alexleoreeves.novelapp.nodebridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User-facing status of the embedded nodebridge runtime. This file lives in
 * the shared androidMain nodebridge package and is compiled into BOTH the
 * phone app (composeApp) and the TV app (tvApp build.gradle kotlin.srcDir).
 *
 * Semantics of [message]:
 *  - `null`       → boot still in progress (nothing shown yet)
 *  - `""`         → the bridge started OK (nothing shown)
 *  - non-blank    → the bridge failed to start; the app shows this reason in a
 *                   dismissible dialog and continues with the backend fallback.
 *
 * The bridge is an enhancement, never a hard dependency — a failure must not
 * block or crash the app, only inform the user why some anime servers may be
 * blocked (datacenter egress → streams=0 on provider CDNs).
 */
object NodeBridgeStatus {

    private val _message = MutableStateFlow<String?>(null)

    val message: StateFlow<String?> = _message.asStateFlow()

    fun reportStarted() {
        _message.value = ""
    }

    fun reportFailure(reason: String) {
        _message.value = reason.ifBlank { "The built-in anime engine failed to start." }
    }
}
