package net.pdd.comagic.mobile

import kotlinx.serialization.json.Json
import okhttp3.Credentials as OkHttpCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class RegistryClient(
    baseUrl: String,
    username: String = Credentials.username,
    password: String = Credentials.password
) {
    private val base = baseUrl.trim().trimEnd('/')
    private val authorization = OkHttpCredentials.basic(username, password)
    private val json = Json { ignoreUnknownKeys = true }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun listServers(): List<RegistryServer> {
        val request = Request.Builder()
            .url("$base/api/agents")
            .header("Authorization", authorization)
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("registry ${response.code}: ${body.take(200)}")
            }
            return json.decodeFromString<RegistryListResponse>(body).data
        }
    }
}
