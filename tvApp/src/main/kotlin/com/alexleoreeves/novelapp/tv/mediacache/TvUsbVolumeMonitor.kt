package com.alexleoreeves.novelapp.tv.mediacache

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Mounted removable volume on the TV.
 */
data class UsbVolume(
    val id: String,                  // volume UUID or path-hash fallback
    val label: String,
    val root: File
)

/**
 * Tracks USB attach/detach for Smart TV dual-target storage.
 *
 * Uses StorageManager.storageVolumes (Android 7+; guarded for the TV minSdk 23
 * build). Each mounted removable volume is published through [volumes]; the
 * download engine's storage port resolves StorageTarget.USB against this list
 * so bundles are always written into a live mounted volume — never a stale path.
 *
 * Registration uses the explicit-safe receiver + manual [start]/[stop] so the
 * TV activity owns the lifecycle (no manifest receiver needed).
 */
class TvUsbVolumeMonitor(private val context: Context) {

    private val _volumes = MutableStateFlow<List<UsbVolume>>(emptyList())
    val volumes: StateFlow<List<UsbVolume>> = _volumes

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        runCatching { context.registerReceiver(receiver, filter) }
        refresh()
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun refresh() {
        val manager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (manager == null) {
            _volumes.value = emptyList()
            return
        }
        val mounted = manager.storageVolumes
            .mapNotNull(::toUsbVolume)
            .filter { it.root.exists() && it.root.canRead() }
        _volumes.value = mounted
    }

    private fun toUsbVolume(volume: StorageVolume): UsbVolume? {
        val state = runCatching { volume.state }.getOrNull()
            ?: volume.directory?.let { Environment.getStorageState(it) }
        if (state != Environment.MEDIA_MOUNTED) return null
        val root = volume.directory ?: return null
        val id = volume.uuid ?: root.absolutePath.hashCode().toUInt().toString(16)
        return UsbVolume(
            id = id,
            label = volume.getDescription(context) ?: "USB Storage",
            root = root
        )
    }
}
