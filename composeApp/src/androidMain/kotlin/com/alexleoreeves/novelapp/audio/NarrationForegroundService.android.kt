package com.alexleoreeves.novelapp.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.alexleoreeves.novelapp.MainActivity
import com.alexleoreeves.novelapp.sensor.AppContextHolder

fun updateNarrationForegroundService(
    enabled: Boolean,
    title: String,
    subtitle: String
) {
    val context = AppContextHolder.applicationContext ?: return
    val intent = Intent(context, NarrationForegroundService::class.java).apply {
        action = if (enabled) NarrationForegroundService.ACTION_START else NarrationForegroundService.ACTION_STOP
        putExtra(NarrationForegroundService.EXTRA_TITLE, title)
        putExtra(NarrationForegroundService.EXTRA_SUBTITLE, subtitle)
    }
    if (enabled) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    } else {
        context.stopService(Intent(context, NarrationForegroundService::class.java))
    }
}

class NarrationForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppContextHolder.applicationContext = applicationContext
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "NovelApp narration" }
        val subtitle = intent?.getStringExtra(EXTRA_SUBTITLE).orEmpty().ifBlank { "Reading in background" }
        startForeground(NOTIFICATION_ID, buildNotification(title, subtitle))
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep foreground narration alive when the recent-app card is removed.
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(title: String, subtitle: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Novel narration",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps novel narration playing in background mode."
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        const val ACTION_START = "com.alexleoreeves.novelapp.action.NARRATION_START"
        const val ACTION_STOP = "com.alexleoreeves.novelapp.action.NARRATION_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        private const val CHANNEL_ID = "novelapp_narration"
        private const val NOTIFICATION_ID = 4401
    }
}
