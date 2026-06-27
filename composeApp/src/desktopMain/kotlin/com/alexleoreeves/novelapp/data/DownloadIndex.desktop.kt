package com.alexleoreeves.novelapp.data

import java.io.File

private fun getDownloadsDir(): File {
    val dir = File(System.getProperty("user.home") ?: ".", ".aninovelmanga/downloads")
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
