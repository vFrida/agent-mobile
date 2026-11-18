package net.agents.mobile

import java.net.URLDecoder

data class PairPayload(
    val baseUrl: String,
    val username: String = "comagic",
    val password: String = "",
    val directory: String = ""
) {
    companion object {
        fun parse(raw: String): PairPayload? {
            val text = raw.trim()
            if (text.isEmpty()) return null
            val uri = runCatching { java.net.URI(text) }.getOrNull() ?: return null
            val params = parseQuery(uri.rawQuery)

            val url = when {
                uri.scheme == "comagic" -> params["url"]
                uri.scheme == "http" || uri.scheme == "https" -> buildString {
                    append(uri.scheme).append("://").append(uri.host)
                    if (uri.port > 0) append(':').append(uri.port)
                }
                else -> null
            } ?: return null

            if (url.isBlank()) return null

            return PairPayload(
                baseUrl = url.trimEnd('/'),
                username = (params["user"] ?: params["username"] ?: Credentials.username)
                    .ifBlank { Credentials.username },
                password = params["pass"] ?: params["password"] ?: Credentials.password,
                directory = params["dir"] ?: params["directory"] ?: ""
            )
        }

        private fun parseQuery(query: String?): Map<String, String> {
            if (query.isNullOrBlank()) return emptyMap()
            return query.split('&')
                .mapNotNull { pair ->
                    val index = pair.indexOf('=')
                    if (index <= 0) return@mapNotNull null
                    val key = decode(pair.substring(0, index))
                    val value = decode(pair.substring(index + 1))
                    if (key.isBlank()) null else key to value
                }
                .toMap()
        }

        private fun decode(value: String): String =
            runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
