package com.alexleoreeves.novelapp.data

expect fun saveDownloadedText(novelId: String, chapterNumber: Int, text: String): String
expect fun loadDownloadedText(localPath: String): String
expect fun deleteDownloadedText(localPath: String)
