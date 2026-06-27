package com.alexleoreeves.novelapp.sensor

actual class SleepDetector actual constructor() {
    actual fun startMonitoring(onSleepDetected: () -> Unit) {
        // iOS stub — implement CoreMotion CMMotionManager for full iOS support
        println("[SleepDetector] iOS motion monitoring stub — use CoreMotion to detect still periods")
    }

    actual fun stopMonitoring() {}
}
