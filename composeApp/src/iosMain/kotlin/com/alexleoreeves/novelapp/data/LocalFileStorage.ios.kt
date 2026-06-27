package com.alexleoreeves.novelapp.data

actual fun saveDownloadedText(novelId: String, chapterNumber: Int, text: String): String = ""
actual fun loadDownloadedText(localPath: String): String = "Offline viewing not supported on iOS yet."
actual fun deleteDownloadedText(localPath: String) {}
