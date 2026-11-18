package net.agents.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

class SettingsStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "comagic_endpoint",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun loadServers(): List<Endpoint> {
        val stored = prefs.getString(KEY_SERVERS, null)
        if (stored.isNullOrBlank()) return migrateLegacy()
        return runCatching { json.decodeFromString<List<Endpoint>>(stored) }.getOrDefault(emptyList())
    }

    fun loadActiveId(): String = prefs.getString(KEY_ACTIVE, "").orEmpty()

    fun loadHiddenAgentIds(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_AGENTS, emptySet()).orEmpty()

    fun saveHiddenAgentIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_AGENTS, ids).apply()
    }

    fun loadShowHiddenDirs(): Boolean = prefs.getBoolean(KEY_SHOW_HIDDEN, false)

    fun saveShowHiddenDirs(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_HIDDEN, enabled).apply()
    }

    fun loadRegistryAccount(): RegistryAccount? {
        val raw = prefs.getString(KEY_REGISTRY_ACCOUNT, null)
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString<RegistryAccount>(raw) }.getOrNull()
    }

    fun clearRegistryAccount() {
        prefs.edit().remove(KEY_REGISTRY_ACCOUNT).apply()
    }

    fun saveRegistryAccount(account: RegistryAccount) {
        prefs.edit().putString(KEY_REGISTRY_ACCOUNT, json.encodeToString(account)).apply()
    }

    fun loadRegistryUrls(): List<String> {
        val raw = prefs.getString(KEY_REGISTRY_LIST, null)
        val stored = if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        }
        val cleaned = stored.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        return cleaned.ifEmpty { listOf(RegistryDefaults.url) }
    }

    fun loadRegistryUrl(urls: List<String>): String {
        val active = prefs.getString(KEY_REGISTRY, "").orEmpty().trim()
        return if (active in urls) active else urls.first()
    }

    fun saveRegistryUrls(urls: List<String>) {
        prefs.edit().putString(KEY_REGISTRY_LIST, json.encodeToString(urls)).apply()
    }

    fun saveRegistryUrl(url: String) {
        prefs.edit().putString(KEY_REGISTRY, url.trim()).apply()
    }

    fun load(): Endpoint {
        val servers = loadServers()
        val activeId = loadActiveId()
        return servers.firstOrNull { it.id == activeId } ?: servers.firstOrNull() ?: Endpoint()
    }

    fun saveServers(servers: List<Endpoint>, activeId: String) {
        prefs.edit()
            .putString(KEY_SERVERS, json.encodeToString(servers))
            .putString(KEY_ACTIVE, activeId)
            .apply()
    }

    fun save(endpoint: Endpoint) {
        val activeId = endpoint.id.ifBlank { UUID.randomUUID().toString() }
        val saved = endpoint.copy(id = activeId)
        val servers = loadServers().toMutableList()
        val index = servers.indexOfFirst { it.id == activeId }
        if (index >= 0) servers[index] = saved else servers.add(saved)
        saveServers(servers, activeId)
    }

    private fun migrateLegacy(): List<Endpoint> {
        val baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty().trim()
        if (baseUrl.isBlank()) return emptyList()
        val migrated = Endpoint(
            id = UUID.randomUUID().toString(),
            baseUrl = baseUrl,
            username = prefs.getString(KEY_USERNAME, Credentials.username)
                .orEmpty()
                .ifBlank { Credentials.username },
            password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
            directory = prefs.getString(KEY_DIRECTORY, "").orEmpty()
        )
        saveServers(listOf(migrated), migrated.id)
        prefs.edit()
            .remove(KEY_BASE_URL)
            .remove(KEY_USERNAME)
            .remove(KEY_PASSWORD)
            .remove(KEY_DIRECTORY)
            .apply()
        return listOf(migrated)
    }

    private companion object {
        const val KEY_SERVERS = "servers"
        const val KEY_ACTIVE = "activeServer"
        const val KEY_REGISTRY = "registryUrl"
        const val KEY_REGISTRY_LIST = "registryUrls"
        const val KEY_REGISTRY_ACCOUNT = "registryAccount"
        const val KEY_SHOW_HIDDEN = "showHiddenDirs"
        const val KEY_HIDDEN_AGENTS = "hiddenAgents"

        const val KEY_BASE_URL = "baseUrl"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_DIRECTORY = "directory"
    }
}
