package com.alexleoreeves.novelapp.tv.platform

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import com.alexleoreeves.novelapp.tv.ui.TvProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

object TvProfileStore {

    private const val PREFS_NAME = "tv_profiles_store"
    private const val KEY_PROFILES = "profiles_json"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    val AVATAR_COLORS = listOf(
        Color(0xFF00BFFF), // Cyan / Electric Blue
        Color(0xFFFF5252), // Coral Red
        Color(0xFF06D6A0), // Emerald Green
        Color(0xFFFFD166), // Amber Gold
        Color(0xFF9B5DE5), // Vivid Purple
        Color(0xFFF15BB5), // Neon Pink
        Color(0xFF00F5D4), // Aqua Marine
        Color(0xFFFF9F1C)  // Bright Orange
    )

    fun getAvatarColor(index: Int, isKids: Boolean = false): Color {
        if (isKids) return Color(0xFF06D6A0)
        return AVATAR_COLORS.getOrElse(index % AVATAR_COLORS.size) { Color(0xFF00BFFF) }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getProfiles(context: Context, defaultUsername: String? = null): List<TvProfile> {
        val prefs = getPrefs(context)
        val raw = prefs.getString(KEY_PROFILES, null)
        if (raw.isNullOrBlank()) {
            val baseName = defaultUsername?.takeIf { it.isNotBlank() } ?: "Main"
            val defaults = listOf(
                TvProfile(id = "main", name = baseName, isKids = false, avatarColorIndex = 0),
                TvProfile(id = "kids", name = "Kids", isKids = true, avatarColorIndex = 2)
            )
            saveProfiles(context, defaults)
            return defaults
        }

        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<TvProfile>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TvProfile(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "Profile ${i + 1}"),
                        isKids = obj.optBoolean("isKids", false),
                        avatarColorIndex = obj.optInt("avatarColorIndex", i % AVATAR_COLORS.size)
                    )
                )
            }
            if (list.isEmpty()) {
                val baseName = defaultUsername?.takeIf { it.isNotBlank() } ?: "Main"
                listOf(TvProfile(id = "main", name = baseName, isKids = false, avatarColorIndex = 0))
            } else list
        } catch (e: Exception) {
            val baseName = defaultUsername?.takeIf { it.isNotBlank() } ?: "Main"
            listOf(TvProfile(id = "main", name = baseName, isKids = false, avatarColorIndex = 0))
        }
    }

    fun saveProfiles(context: Context, profiles: List<TvProfile>) {
        val arr = JSONArray()
        for (p in profiles) {
            val obj = JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("isKids", p.isKids)
                put("avatarColorIndex", p.avatarColorIndex)
            }
            arr.put(obj)
        }
        getPrefs(context).edit().putString(KEY_PROFILES, arr.toString()).apply()
    }

    fun addProfile(context: Context, name: String, isKids: Boolean, avatarColorIndex: Int): TvProfile {
        val current = getProfiles(context).toMutableList()
        val newProfile = TvProfile(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { if (isKids) "Kids" else "Profile ${current.size + 1}" },
            isKids = isKids,
            avatarColorIndex = avatarColorIndex
        )
        current.add(newProfile)
        saveProfiles(context, current)
        return newProfile
    }

    fun updateProfile(context: Context, id: String, name: String, isKids: Boolean, avatarColorIndex: Int): List<TvProfile> {
        val current = getProfiles(context).map { p ->
            if (p.id == id) {
                p.copy(
                    name = name.trim().ifBlank { p.name },
                    isKids = isKids,
                    avatarColorIndex = avatarColorIndex
                )
            } else p
        }
        saveProfiles(context, current)
        return current
    }

    fun deleteProfile(context: Context, id: String): List<TvProfile> {
        val current = getProfiles(context).toMutableList()
        if (current.size <= 1) return current // keep at least one profile
        current.removeAll { it.id == id }
        saveProfiles(context, current)
        return current
    }

    fun getActiveProfileId(context: Context): String? {
        return getPrefs(context).getString(KEY_ACTIVE_ID, null)
    }

    fun setActiveProfileId(context: Context, id: String) {
        getPrefs(context).edit().putString(KEY_ACTIVE_ID, id).apply()
    }
}
