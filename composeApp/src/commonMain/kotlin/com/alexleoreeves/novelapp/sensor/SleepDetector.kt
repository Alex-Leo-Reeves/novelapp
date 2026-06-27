package com.alexleoreeves.novelapp.sensor

/**
 * Expect contract for the Accelerometer-based Smart Sleep Detector.
 */
expect class SleepDetector() {
    /**
     * Start listening to device micro-movements. If the device remains
     * completely stationary for a duration, trigger the callback.
     */
    fun startMonitoring(onSleepDetected: () -> Unit)

    /**
     * Terminate sensor listeners.
     */
    fun stopMonitoring()
}
