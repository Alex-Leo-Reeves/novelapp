package com.alexleoreeves.novelapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppUpdateProgressState(
    val isActive: Boolean = false,
    val phase: AppUpdatePhase = AppUpdatePhase.Idle,
    val message: String = "",
    val receivedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val canDismiss: Boolean = false,
    val isError: Boolean = false
) {
    val fraction: Float?
        get() = if (totalBytes > 0L) (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else null
}

enum class AppUpdatePhase {
    Idle,
    Downloading,
    Verifying,
    ReadyToInstall,
    Installing,
    Error
}

object AppUpdateProgressBus {
    private val _state = MutableStateFlow(AppUpdateProgressState())
    val state: StateFlow<AppUpdateProgressState> = _state.asStateFlow()

    fun update(state: AppUpdateProgressState) {
        _state.value = state
    }

    fun clear() {
        _state.value = AppUpdateProgressState()
    }
}
