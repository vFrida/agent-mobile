package net.pdd.comagic.mobile

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class HistoryCache(context: Context) {

    private val dir = File(context.filesDir, "history").apply { mkdirs() }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun load(sessionID: String): CachedHistory? {
        val file = fileFor(sessionID)
        if (!file.exists()) return null
        return runCatching { json.decodeFromString<CachedHistory>(file.readText()) }.getOrNull()
    }

    fun save(sessionID: String, lines: List<ChatLine>) {
        if (sessionID.isBlank() || lines.isEmpty()) return
        runCatching {
            val file = fileFor(sessionID)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(CachedHistory(savedAt = System.currentTimeMillis(), lines = lines)))
            tmp.renameTo(file)
        }
    }

    fun remove(sessionID: String) {
        runCatching { fileFor(sessionID).delete() }
    }

    private fun fileFor(sessionID: String): File =
        File(dir, sessionID.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")
}
