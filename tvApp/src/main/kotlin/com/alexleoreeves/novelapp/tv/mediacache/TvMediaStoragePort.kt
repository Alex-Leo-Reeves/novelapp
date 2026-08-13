package com.alexleoreeves.novelapp.tv.mediacache

import android.content.Context
import android.os.StatFs
import com.alexleoreeves.novelapp.data.mediacache.MediaBundleWriter
import com.alexleoreeves.novelapp.data.mediacache.MediaStoragePort
import com.alexleoreeves.novelapp.data.mediacache.MediaVolumePath
import com.alexleoreeves.novelapp.data.mediacache.StorageTarget
import java.io.File
import java.io.RandomAccessFile

/**
 * Android/TV filesystem port for the media-cache engine.
 *
 * INTERNAL targets resolve to `Context.filesDir` (app-private, never visible
 * to other apps — a second layer of containment on top of AES-256). USB targets
 * resolve against the live volume list from [TvUsbVolumeMonitor], so a write
 * never lands on a stale path after an unmount.
 *
 * All public methods are safe to call from the engine's IO dispatcher. Writes
 * go through a temp-file + atomic-rename for manifests/metadata, and through a
 * random-access [RandomAccessFile] for bundle chunks, avoiding UI-thread stalls
 * and torn files on power loss.
 */
class TvMediaStoragePort(
    private val context: Context,
    private val volumes: () -> List<UsbVolume>
) : MediaStoragePort {

    override fun cacheRoot(): MediaVolumePath =
        MediaVolumePath(
            id = "internal",
            label = "Internal Storage",
            rootAbsolutePath = context.filesDir.absolutePath
        )

    override fun resolveVolume(target: StorageTarget, usbVolumeId: String?): MediaVolumePath? =
        when (target) {
            StorageTarget.INTERNAL -> cacheRoot()
            StorageTarget.USB -> volumes()
                .firstOrNull { it.id == usbVolumeId }
                ?.let { MediaVolumePath(it.id, it.label, it.root.absolutePath) }
        }

    override fun freeBytes(path: String): Long = try {
        StatFs(path).run { availableBytes }
    } catch (e: Exception) {
        -1L
    }

    override fun ensureDir(path: String) {
        File(path).mkdirs()
    }

    override fun readBytes(path: String): ByteArray? = try {
        File(path).takeIf { it.isFile }?.readBytes()
    } catch (e: Exception) {
        null
    }

    override fun writeBytesAtomically(path: String, bytes: ByteArray) {
        val target = File(path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        // fsync BEFORE rename: a crash can only lose the in-flight checkpoint,
        // never corrupt the previously saved one.
        runCatching {
            java.io.FileOutputStream(tmp, true).use { it.fd.sync() }
        }
        if (!tmp.renameTo(target)) {
            // Some FAT32/NTFS bridges reject rename: copy + delete fallback.
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    override fun openBundleForWrite(path: String): MediaBundleWriter {
        val file = File(path)
        file.parentFile?.mkdirs()
        return TvBundleWriter(file)
    }

    override fun delete(path: String) {
        File(path).takeIf { it.exists() }?.deleteRecursively()
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun bundleSize(path: String): Long = File(path).takeIf { it.isFile }?.length() ?: 0L

    override fun listChildren(dir: String, suffix: String): List<String> =
        File(dir).listFiles { file -> file.isFile && file.name.endsWith(suffix) }
            ?.map { it.absolutePath }
            ?: emptyList()

    override fun lastModified(path: String): Long =
        File(path).takeIf { it.exists() }?.lastModified() ?: 0L

    /** Random-access ciphertext sink with fsync-on-sync. */
    private class TvBundleWriter(file: File) : MediaBundleWriter {
        private val raf: RandomAccessFile = RandomAccessFile(file, "rw")
        private var closed = false

        override fun write(atOffset: Long, bytes: ByteArray) {
            if (closed) throw IllegalStateException("Bundle writer is closed")
            raf.seek(atOffset)
            raf.write(bytes)
        }

        override fun sync() {
            raf.fd.sync()
        }

        override fun close() {
            if (closed) return
            runCatching { raf.fd.sync() }
            raf.close()
            closed = true
        }
    }
}
