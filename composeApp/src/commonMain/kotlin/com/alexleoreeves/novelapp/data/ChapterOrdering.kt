package com.alexleoreeves.novelapp.data

internal fun chapterSortNumber(
    title: String,
    url: String,
    currentChapterNumber: Int = 0,
    fallbackIndex: Int = 0
): Double {
    extractChapterNumber(title)?.let { return it }
    extractChapterNumber(url)?.let { return it }
    if (currentChapterNumber > 0) return currentChapterNumber.toDouble()
    return fallbackIndex + 1.0
}

internal fun List<Chapter>.normalizedChapterOrder(): List<Chapter> =
    mapIndexed { index, chapter ->
        chapterSortNumber(chapter.title, chapter.url, chapter.chapterNumber, index) to chapter
    }
        .distinctBy { it.second.url.ifBlank { it.second.title }.lowercase() }
        .sortedWith(compareBy<Pair<Double, Chapter>> { it.first }.thenBy { it.second.title.lowercase() })
        .map { it.second }

internal fun List<MangaChapter>.normalizedMangaChapterOrder(): List<MangaChapter> =
    mapIndexed { index, chapter ->
        chapterSortNumber(chapter.title, chapter.url, chapter.chapterNumber, index) to chapter
    }
        .distinctBy { it.second.url.ifBlank { it.second.title }.lowercase() }
        .sortedWith(compareBy<Pair<Double, MangaChapter>> { it.first }.thenBy { it.second.title.lowercase() })
        .map { it.second }

private fun extractChapterNumber(text: String): Double? {
    if (text.isBlank()) return null

    val patterns = listOf(
        Regex("""(?:chapter|chap|ch\.?|episode|ep\.?)\s*#?\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:chapter|chap|ch|episode|ep)[-_](\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:episode_no|chapter_no|chapter|chap|ch|ep)=(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""/(?:chapter|chap|ch|episode|ep)[-_/](\d+(?:\.\d+)?)(?:[/._-]|$)""", RegexOption.IGNORE_CASE)
    )

    return patterns
        .asSequence()
        .mapNotNull { pattern -> pattern.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() }
        .firstOrNull()
}
