package com.alexleoreeves.novelapp.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Paths

/**
 * Desktop (Windows) session store.
 *
 * Persists the signed-in account to the user's AppData/novelapp directory
 * (same JSON shape as Android's room-persisted SavedUserAccount), so Windows
 * users stay signed in across app restarts.
 */
class DesktopUserSessionStore : UserSessionStore {

    private val sessionFile: File by lazy {
        val base = System.getProperty("user.home")
            ?: System.getenv("USERPROFILE")
            ?: "."
        val dir = Paths.get(base, ".novelapp").toFile().apply { mkdirs() }
        File(dir, "session.json")
    }

    override fun loadAccount(): SavedUserAccount? {
        return runCatching {
            if (!sessionFile.exists()) return null
            val raw = sessionFile.readText()
            val json = Json.parseToJsonElement(raw)
            val obj = json.jsonObject
            SavedUserAccount(
                id = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                username = obj["username"]?.jsonPrimitive?.contentOrNull ?: "",
                email = obj["email"]?.jsonPrimitive?.contentOrNull ?: "",
                authToken = obj["authToken"]?.jsonPrimitive?.contentOrNull ?: "",
                plan = obj["plan"]?.jsonPrimitive?.contentOrNull ?: "free",
                billingStatus = obj["billingStatus"]?.jsonPrimitive?.contentOrNull ?: "none",
                paidUntil = obj["paidUntil"]?.jsonPrimitive?.contentOrNull,
                createdAt = obj["createdAt"]?.jsonPrimitive?.contentOrNull ?: "",
                maxDevices = obj["maxDevices"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
                isPremium = obj["isPremium"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false
            )
        }.getOrNull()
    }

    override fun saveAccount(account: SavedUserAccount) {
        runCatching {
            val json = buildString {
                append("{")
                append("\"id\":").append(quote(account.id)).append(",")
                append("\"username\":").append(quote(account.username)).append(",")
                append("\"email\":").append(quote(account.email)).append(",")
                append("\"authToken\":").append(quote(account.authToken)).append(",")
                append("\"plan\":").append(quote(account.plan)).append(",")
                append("\"billingStatus\":").append(quote(account.billingStatus)).append(",")
                append("\"paidUntil\":").append(account.paidUntil?.let { quote(it) } ?: "null").append(",")
                append("\"createdAt\":").append(quote(account.createdAt)).append(",")
                append("\"maxDevices\":").append(account.maxDevices ?: 2).append(",")
                append("\"isPremium\":").append(account.isPremium)
                append("}")
            }
            sessionFile.writeText(json)
        }.onFailure {
            println("[DesktopSession] Could not save session: ${it.message}")
        }
    }

    override fun clearAccount() {
        runCatching {
            if (sessionFile.exists()) {
                sessionFile.delete()
            }
        }
    }

    private fun quote(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
