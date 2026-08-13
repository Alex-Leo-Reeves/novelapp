package com.alexleoreeves.novelapp.data.mediacache

import com.alexleoreeves.novelapp.platform.SavedUserAccount

// ─────────────────────────────────────────────────────────────────────────────
//  Media Access Policy — the single enforcement gate shared by every platform.
//
//  This is the subscription/quota middleware. Both the download pipeline and
//  the streaming players route their "may I?" questions here, so a tier rule
//  changes in ONE place and applies to Android, iOS, Desktop and TV.
//
//  Tier rules (v2):
//   • FREE  — exactly 5 media downloads PER DAY (UTC day), then hard-blocked.
//             Streaming is capped at 20% of an episode OR 20 minutes for a
//             movie (the player enforces by wall-clock position).
//   • PRO   — active 30-day subscription (paidUntil in the future):
//             unlimited downloads + unlimited streaming. Bypasses all caps.
//   • NMC   — novels / manga / comics are always unlimited (never quota'd).
//
//  A re-download of the SAME task id is idempotent and does not consume a
//  second quota slot; the engine's task map is the dedupe source.
//
//  The daily window is UTC (epoch-day), matching the mobile `DownloadIndex`
//  daily counters, so free users get a fresh 5-slot budget every day.
// ─────────────────────────────────────────────────────────────────────────────

object MediaAccessPolicy {

    /** Free-tier daily download allowance (per UTC day). */
    const val FREE_DAILY_DOWNLOADS: Int = 5

    /** Trial threshold: free users may watch at most this fraction of an episode. */
    const val FREE_PREVIEW_FRACTION: Double = 0.2

    /** Trial threshold: free users may watch at most this many ms of a movie. */
    const val FREE_MOVIE_PREVIEW_MS: Long = 20L * 60L * 1000L

    /**
     * Whether a subscription is currently active. The server is truth for
     * [SavedUserAccount.isPremium], but we defend against stale local caches
     * by also requiring `paidUntil` to be in the future when present.
     */
    fun isPremiumActive(account: SavedUserAccount?): Boolean {
        if (account?.isPremium != true) return false
        val paidUntil = account.paidUntil?.trim().orEmpty()
        if (paidUntil.isBlank()) return account.isPremium
        // Backend sends ISO-8601 (2026-08-13T…). Parse as epoch by hand:
        // first 10 chars are yyyy-MM-dd; a bare date means "paid through end of day".
        val datePart = paidUntil.substring(0, 10.coerceAtMost(paidUntil.length))
        val year = datePart.substring(0, 4).toIntOrNull() ?: return account.isPremium
        if (year <= 0) return account.isPremium
        val month = datePart.substring(5, 7).toIntOrNull() ?: return account.isPremium
        val day = datePart.substring(8, 10).toIntOrNull() ?: return account.isPremium
        val endOfPaidDayUtcMs = epochOfUtc(year, month, day) + 86_400_000L
        return endOfPaidDayUtcMs > nowUtcMs()
    }

    /**
     * How many media downloads a user may still perform today.
     *
     * @param usedDownloadsToday number of media downloads already completed
     *                           since the start of the current UTC day.
     */
    fun remainingDownloads(contentType: String, account: SavedUserAccount?, usedDownloadsToday: Int): Int {
        if (isPremiumActive(account)) return Int.MAX_VALUE
        if (contentType.uppercase() in NMC_CONTENT_TYPES) return Int.MAX_VALUE
        return (FREE_DAILY_DOWNLOADS - usedDownloadsToday).coerceAtLeast(0)
    }

    /** Enforcement gate for download requests — intercepts BEFORE enqueue. */
    fun canDownload(contentType: String, account: SavedUserAccount?, usedDownloadsToday: Int): Boolean {
        if (isPremiumActive(account)) return true
        if (contentType.uppercase() in NMC_CONTENT_TYPES) return true
        return usedDownloadsToday < FREE_DAILY_DOWNLOADS
    }

    /** Enforcement gate for streaming — intercepts BEFORE the player starts. */
    fun canStream(account: SavedUserAccount?): Boolean = isPremiumActive(account)

    /**
     * Preview ceiling for a free stream in ms, or null when the user is premium.
     * Episodic content is capped at [FREE_PREVIEW_FRACTION] of [durationMs];
     * movies are capped at [FREE_MOVIE_PREVIEW_MS] (or the supplied cap).
     */
    fun previewLimitMs(
        account: SavedUserAccount?,
        isEpisodic: Boolean,
        durationMs: Long,
        movieCapMs: Long = FREE_MOVIE_PREVIEW_MS
    ): Long? {
        if (canStream(account)) return null
        if (durationMs <= 0L) return null
        return if (isEpisodic) (durationMs * FREE_PREVIEW_FRACTION).toLong().coerceAtLeast(1L)
        else movieCapMs.coerceAtMost(FREE_MOVIE_PREVIEW_MS)
    }

    /** Human-readable quota line for the UI (e.g. "3 of 5 free downloads left today"). */
    fun quotaMessage(remaining: Int, isPremium: Boolean): String = when {
        isPremium -> "Unlimited downloads • Premium active"
        remaining <= 0 -> "Today's free download limit reached — come back tomorrow or go Premium for unlimited downloads"
        else -> "$remaining of $FREE_DAILY_DOWNLOADS free downloads left today"
    }

    /** Content types that never consume a quota slot (novel / manga / comic). */
    val NMC_CONTENT_TYPES: Set<String> = setOf(
        com.alexleoreeves.novelapp.data.ContentType.NOVEL,
        com.alexleoreeves.novelapp.data.ContentType.MANGA,
        com.alexleoreeves.novelapp.data.ContentType.COMIC
    )

    /**
     * Epoch milliseconds at the start of the UTC day containing [millis].
     * The daily quota window rolls over at midnight UTC.
     */
    fun startOfEpochDayMs(millis: Long): Long =
        (millis / 86_400_000L) * 86_400_000L

    // ── helpers (no kotlinx-datetime dependency in tvApp) ───────────────────
    private fun nowUtcMs(): Long = System.currentTimeMillis()

    private fun epochOfUtc(year: Int, month: Int, day: Int): Long {
        // Days from civil to Unix epoch: 1970-01-01 = day 0.
        val days = daysFromCivil(year, month, day)
        return days * 86_400_000L
    }

    private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
        var year = y
        if (m <= 2) year -= 1
        val era = (if (year >= 0) year else year - 399) / 400
        val yoe = year - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
