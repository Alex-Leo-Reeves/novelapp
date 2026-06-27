package com.alexleoreeves.novelapp.data

import com.alexleoreeves.novelapp.sensor.AppContextHolder
import java.io.File

private fun getDownloadsDir(): File {
    val ctx = AppContextHolder.applicationContext ?: return File("/tmp")
    val dir = File(ctx.filesDir, "downloads")
    dir.mkdirs()
    return dir
}

actual fun readIndexJson(): String? {
    return try {
        val f = File(getDownloadsDir(), "index.json")
        if (f.exists()) f.readText() else null
    } catch (e: Exception) { null }
}

actual fun writeIndexJson(json: String) {
    try {
        File(getDownloadsDir(), "index.json").writeText(json)
    } catch (e: Exception) {
        println("[Downloads] Failed to write index: ${e.message}")
    }
}
