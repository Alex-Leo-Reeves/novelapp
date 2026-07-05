package com.alexleoreeves.novelapp.data

expect fun saveDownloadedText(novelId: String, chapterNumber: Int, text: String): String
expect fun loadDownloadedText(localPath: String): String
expect fun deleteDownloadedText(localPath: String)
expect suspend fun saveDownloadedVideo(parentId: String, episodeNumber: Int, sourceUrl: String): DownloadedVideoFile
expect fun isDownloadedLocalFileAvailable(localPath: String): Boolean
expect suspend fun extractStreamFromEmbed(embedUrl: String, timeoutMs: Long = 22_000L): String?

data class DownloadedVideoFile(
    val localPath: String = "",
    val fileSizeBytes: Long = 0L,
    val error: String = ""
) {
    val success: Boolean get() = localPath.isNotBlank() && error.isBlank()
}
