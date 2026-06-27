package com.alexleoreeves.novelapp.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.abs

/**
 * Android actual implementation of the Smart Sleep Detector.
 *
 * Strategy:
 * - Registers to `Sensor.TYPE_ACCELEROMETER` at low power/gaming rate.
 * - Computes total variance over a rolling 10-minute window.
 * - If cumulative movement deviation stays below 0.05 m/s² threshold
 *   for the full window, `onSleepDetected` fires — pausing narration.
 */
actual class SleepDetector actual constructor() : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var accelerometerSensor: Sensor? = null
    private var onSleepDetectedCallback: (() -> Unit)? = null

    private val SLEEP_TIMEOUT_MS = 10 * 60 * 1000L  // 10 minutes
    private val MOVEMENT_THRESHOLD = 0.08f           // m/s² — below is "still"

    private var lastX = 0f
    private var lastY = 0f
    private var lastZ = 0f
    private var stillSinceMs = 0L
    private var isFirstReading = true

    actual fun startMonitoring(onSleepDetected: () -> Unit) {
        onSleepDetectedCallback = onSleepDetected
        stillSinceMs = System.currentTimeMillis()
        isFirstReading = true

        val ctx = AppContextHolder.applicationContext ?: return
        sensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return

        sensorManager?.registerListener(
            this,
            accelerometerSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )
    }

    actual fun stopMonitoring() {
        sensorManager?.unregisterListener(this)
        onSleepDetectedCallback = null
        sensorManager = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        if (isFirstReading) {
            lastX = x; lastY = y; lastZ = z
            isFirstReading = false
            return
        }

        val delta = abs(x - lastX) + abs(y - lastY) + abs(z - lastZ)
        lastX = x; lastY = y; lastZ = z

        if (delta > MOVEMENT_THRESHOLD) {
            // Movement detected — reset the timer
            stillSinceMs = System.currentTimeMillis()
        } else {
            // Check if the user has been completely still for 10 minutes
            val stillDuration = System.currentTimeMillis() - stillSinceMs
            if (stillDuration >= SLEEP_TIMEOUT_MS) {
                onSleepDetectedCallback?.invoke()
                stopMonitoring() // Stop monitoring after detection
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Minimal application context holder needed for Android sensor access
 * outside of a Composable. Initialize in MainActivity.
 */
object AppContextHolder {
    var applicationContext: Context? = null
}
