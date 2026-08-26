package com.alexleoreeves.novelapp.nodebridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ResidentialScraperState {
    data object Starting : ResidentialScraperState
    data class Ready(val port: Int) : ResidentialScraperState
    data class Failed(val reason: String) : ResidentialScraperState
}

// Backward compatibility alias
typealias NodeBridgeState = ResidentialScraperState

/**
 * User-facing status of the embedded residential IP scraper runtime.
 * Reports starting, ready (active on local residential IP), or failure.
 */
object ResidentialScraperStatus {

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _state = MutableStateFlow<ResidentialScraperState>(ResidentialScraperState.Starting)
    val state: StateFlow<ResidentialScraperState> = _state.asStateFlow()

    fun reportStarted(port: Int = 0) {
        _message.value = ""
        _state.value = ResidentialScraperState.Ready(port)
    }

    fun reportFailure(reason: String) {
        val msg = reason.ifBlank { "The residential IP scraper failed to start." }
        _message.value = msg
        _state.value = ResidentialScraperState.Failed(msg)
    }
}

// Backward compatibility alias
typealias NodeBridgeStatus = ResidentialScraperStatus
