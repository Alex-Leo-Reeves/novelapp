package com.alexleoreeves.novelapp.data

expect fun clearTemporaryMangaPageCache()

expect suspend fun cacheMangaChapterPages(
    chapterKey: String,
    pageUrls: List<String>,
    persistent: Boolean,
    onProgress: (completed: Int, total: Int) -> Unit
): List<String>
