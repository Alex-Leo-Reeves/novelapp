package com.alexleoreeves.novelapp.nodebridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface NodeBridgeState {
    data object Starting : NodeBridgeState
    data class Ready(val port: Int) : NodeBridgeState
    data class Failed(val reason: String) : NodeBridgeState
}

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
 */
object NodeBridgeStatus {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _state = MutableStateFlow<NodeBridgeState>(NodeBridgeState.Starting)
    val state: StateFlow<NodeBridgeState> = _state.asStateFlow()

    fun reportStarted(port: Int = 0) {
        _message.value = ""
        _state.value = NodeBridgeState.Ready(port)
    }

    fun reportFailure(reason: String) {
        val msg = reason.ifBlank { "The built-in anime engine failed to start." }
        _message.value = msg
        _state.value = NodeBridgeState.Failed(msg)
    }
}
