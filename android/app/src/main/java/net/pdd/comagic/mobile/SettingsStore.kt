package net.pdd.comagic.mobile

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SettingsStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "comagic_endpoint",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun load(): Endpoint = Endpoint(
        baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        username = prefs.getString(KEY_USERNAME, "comagic").orEmpty().ifBlank { "comagic" },
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        directory = prefs.getString(KEY_DIRECTORY, "").orEmpty()
    )

    fun save(endpoint: Endpoint) {
        prefs.edit()
            .putString(KEY_BASE_URL, endpoint.baseUrl.trim())
            .putString(KEY_USERNAME, endpoint.username.trim().ifBlank { "comagic" })
            .putString(KEY_PASSWORD, endpoint.password)
            .putString(KEY_DIRECTORY, endpoint.directory.trim())
            .apply()
    }

    private companion object {
        const val KEY_BASE_URL = "baseUrl"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_DIRECTORY = "directory"
    }
}
