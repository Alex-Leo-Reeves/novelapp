package com.alexleoreeves.novelapp.sensor

actual class SleepDetector actual constructor() {
    actual fun startMonitoring(onSleepDetected: () -> Unit) {
        println("[SleepDetector] Desktop motion monitoring is not available")
    }

    actual fun stopMonitoring() = Unit
}
