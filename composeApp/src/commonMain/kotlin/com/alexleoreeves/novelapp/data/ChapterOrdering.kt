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

internal fun List<Chapter>.normalizedChapterOrder(): List<Chapter> {
    val sorted = mapIndexed { index, chapter ->
        chapterSortNumber(chapter.title, chapter.url, chapter.chapterNumber, index) to chapter
    }
        .distinctBy { it.second.url.ifBlank { it.second.title }.lowercase() }
        .sortedWith(compareBy<Pair<Double, Chapter>> { it.first }.thenBy { it.second.title.lowercase() })
        .map { it.second }

    // Sanitize: re-assign chapter numbers if there are wild jumps (>10x the median gap)
    return sanitizeChapterSequence(sorted)
}

internal fun List<MangaChapter>.normalizedMangaChapterOrder(): List<MangaChapter> =
    mapIndexed { index, chapter ->
        chapterSortNumber(chapter.title, chapter.url, chapter.chapterNumber, index) to chapter
    }
        .distinctBy { it.second.url.ifBlank { it.second.title }.lowercase() }
        .sortedWith(compareBy<Pair<Double, MangaChapter>> { it.first }.thenBy { it.second.title.lowercase() })
        .map { it.second }

/** Remove chapters with wildly out-of-order numbers (e.g. 46 → 2560 → 47). */
private fun sanitizeChapterSequence(chapters: List<Chapter>): List<Chapter> {
    if (chapters.size < 3) return chapters
    val numbers = chapters.map { it.chapterNumber.toDouble() }
    val median = numbers.sorted().let { s -> s[s.size / 2] }
    // Allow numbers up to max(1000, 5 * median) — anything beyond is suspicious
    val ceiling = maxOf(1000.0, median * 5)
    // Filter outliers: keep chapters whose number is within the ceiling or positionally reasonable
    return chapters.filterIndexed { idx, ch ->
        ch.chapterNumber <= 0 || ch.chapterNumber.toDouble() <= ceiling ||
            (idx > 0 && chapters[idx - 1].chapterNumber + 50 >= ch.chapterNumber)
    }
}


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
