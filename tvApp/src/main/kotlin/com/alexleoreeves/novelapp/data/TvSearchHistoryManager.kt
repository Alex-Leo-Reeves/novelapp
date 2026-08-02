package com.alexleoreeves.novelapp.data

import android.content.Context
import android.content.SharedPreferences

class TvSearchHistoryManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("tv_search_history", Context.MODE_PRIVATE)

    fun getHistory(): List<String> {
        val raw = prefs.getString("history_items", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split("|||").filter { it.isNotBlank() }
    }

    fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        val current = getHistory().toMutableList()
        current.remove(trimmed)
        current.add(0, trimmed)
        val updated = current.take(15).joinToString("|||")
        prefs.edit().putString("history_items", updated).apply()
    }

    fun clearHistory() {
        prefs.edit().remove("history_items").apply()
    }
}
