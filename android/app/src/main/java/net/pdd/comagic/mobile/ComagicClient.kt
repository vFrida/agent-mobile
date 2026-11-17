package net.pdd.comagic.mobile

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.io.IOException
import java.util.concurrent.TimeUnit

class SessionBusyException(message: String) : IOException(message)

class ComagicClient(endpoint: Endpoint) {

    private val base = endpoint.baseUrl.trimEnd('/')
    private val authorization = Credentials.basic(endpoint.username, endpoint.password)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun request(path: String): Request.Builder =
        Request.Builder().url("$base$path").header("Authorization", authorization)

    private inline fun <T> blockingWithHeaders(
        path: String,
        errorLabel: String,
        block: (String, okhttp3.Headers) -> T
    ): T {
        http.newCall(request(path).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("$errorLabel ${response.code}: ${body.take(300)}")
            return block(body, response.headers)
        }
    }

    private inline fun <T> blocking(path: String, errorLabel: String, block: (String) -> T): T {
        http.newCall(request(path).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("$errorLabel ${response.code}: ${body.take(300)}")
            return block(body)
        }
    }

    fun health(timeoutMillis: Long = 5_000): HealthResponse {
        val probe = http.newBuilder().callTimeout(timeoutMillis, TimeUnit.MILLISECONDS).build()
        return probe.newCall(request("/global/health").get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("health ${response.code}: ${body.take(300)}")
            json.decodeFromString(body)
        }
    }

    fun listSessions(): List<SessionRef> =
        blocking("/api/session?limit=50&order=desc", "sessions") {
            json.decodeFromString<SessionListResponse>(it).data
        }

    fun history(sessionID: String, limit: Int = 20, before: String? = null): HistoryPage {
        val path = buildString {
            append("/session/$sessionID/message?limit=$limit")
            if (!before.isNullOrBlank()) append("&before=${encode(before)}")
        }
        return blockingWithHeaders(path, "history") { body, headers ->
            val messages = json.decodeFromString<List<MessageDto>>(body)
            val lines = messages.mapNotNull { message ->
                val texts = message.parts.filter { it.type == "text" }.map { it.text }.joinToString("")
                val images = message.parts
                    .filter { it.type == "file" && it.mime?.startsWith("image/") == true && !it.url.isNullOrBlank() }
                    .mapNotNull { it.url }
                when (message.info.role) {
                    "user" -> when {
                        images.isNotEmpty() -> images.map { ChatLine(role = "user", image = it) } +
                            if (texts.isBlank()) emptyList() else listOf(ChatLine(role = "user", text = texts))
                        texts.isBlank() -> emptyList()
                        else -> listOf(ChatLine(role = "user", text = texts))
                    }
                    "assistant" -> {
                        val toolLines = message.parts.filter { it.type == "tool" }.map { part ->
                            ChatLine(
                                role = "tool",
                                tool = part.tool.ifBlank { "tool" },
                                toolID = part.callID.ifBlank { null },
                                toolTitle = part.toolTitle(),
                                toolOutput = part.toolOutput()?.take(4000)
                            )
                        }
                        val textLines = if (texts.isNotBlank()) {
                            listOf(ChatLine(role = "assistant", text = texts))
                        } else {
                            emptyList()
                        }
                        toolLines + textLines + images.map { ChatLine(role = "assistant", image = it) }
                    }
                    else -> emptyList()
                }
            }.flatten()
            HistoryPage(
                lines = lines,
                oldestID = messages.firstOrNull { it.info.id.isNotBlank() }?.info?.id,
                hasMore = headers["X-History-Has-More"] == "1"
            )
        }
    }

    fun createSession(directory: String, model: ModelRef, agent: String = "build"): SessionRef {
        val payload = json.encodeToString(
            CreateSessionRequest(agent = agent, model = model, location = Location(directory))
        ).toRequestBody(JSON)
        http.newCall(request("/api/session").post(payload).build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("create ${response.code}: ${body.take(300)}")
            return json.decodeFromString<SessionCreateResponse>(body).data
                ?: throw IOException("create: empty session")
        }
    }

    fun prompt(
        sessionID: String,
        text: String,
        agent: String?,
        model: ModelRef?,
        image: ImageAttachment? = null
    ) {
        val payload = buildJsonObject {
            put("parts", buildJsonArray {
                if (image != null) {
                    add(buildJsonObject {
                        put("type", "file")
                        put("mime", image.mime)
                        put("filename", image.filename)
                        put("url", image.dataUrl)
                    })
                }
                if (text.isNotBlank()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                }
            })
            if (!agent.isNullOrBlank()) put("agent", agent)
            if (model != null) {
                put("model", buildJsonObject {
                    put("providerID", model.providerID)
                    put("modelID", model.id)
                    model.variant?.let { put("variant", it) }
                })
            }
        }.toString().toRequestBody(JSON)

        http.newCall(request("/session/$sessionID/message").post(payload).build())
            .execute().use { response ->
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    if (response.code == 409) throw SessionBusyException("session is busy")
                    throw IOException("prompt ${response.code}: ${body.take(300)}")
                }
            }
    }

    fun paths(directory: String): PathInfo =
        blocking("/path?directory=${encode(directory)}", "path") {
            json.decodeFromString<PathInfo>(it)
        }

    fun listEntries(directory: String, path: String): List<FsEntry> =
        blocking(
            "/api/fs/list?location%5Bdirectory%5D=${encode(directory)}&path=${encode(path)}",
            "fs"
        ) {
            val base = directory.trimEnd('/').ifBlank { "/" }
            json.decodeFromString<FsListResponse>(it).data.map { entry ->
                val absolute = when {
                    entry.path.startsWith("/") -> entry.path
                    base == "/" -> "/" + entry.path.trimStart('/')
                    else -> "$base/${entry.path.trimStart('/')}"
                }
                entry.copy(path = absolute.trimEnd('/').ifBlank { "/" })
            }
        }

    fun listAgents(directory: String): List<AgentInfo> =
        blocking("/api/agent?location%5Bdirectory%5D=${encode(directory)}", "agents") {
            json.decodeFromString<AgentListResponse>(it).data
        }

    fun listModels(directory: String): List<ModelInfo> =
        blocking("/config/providers?directory=${encode(directory)}", "models") {
            val response = json.decodeFromString<ProvidersResponse>(it)
            response.providers
                .flatMap { provider ->
                    provider.models.values.map { model ->
                        if (model.providerID.isBlank()) model.copy(providerID = provider.id) else model
                    }
                }
                .filter { it.enabled }
                .distinctBy { "${it.providerID}/${it.id}" }
        }

    fun switchAgent(sessionID: String, agent: String) {
        val payload = buildJsonObject { put("agent", agent) }.toString().toRequestBody(JSON)
        http.newCall(request("/api/session/$sessionID/agent").post(payload).build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IOException("agent ${response.code}")
            }
    }

    fun switchModel(sessionID: String, model: ModelRef) {
        val payload = buildJsonObject {
            put("model", buildJsonObject {
                put("id", model.id)
                put("providerID", model.providerID)
                model.variant?.let { put("variant", it) }
            })
        }.toString().toRequestBody(JSON)
        http.newCall(request("/api/session/$sessionID/model").post(payload).build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IOException("model ${response.code}")
            }
    }

    fun deleteSession(sessionID: String) {
        http.newCall(request("/api/session/$sessionID").delete().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("delete ${response.code}: ${body.take(300)}")
        }
    }

    fun interrupt(sessionID: String) {
        http.newCall(request("/api/session/$sessionID/interrupt").post(EMPTY).build())
            .execute().close()
    }

    fun replyPermission(sessionID: String, requestID: String, reply: String) {
        val payload = buildJsonObject { put("reply", reply) }.toString().toRequestBody(JSON)
        http.newCall(request("/api/session/$sessionID/permission/$requestID/reply").post(payload).build())
            .execute().use { response ->
                if (!response.isSuccessful) throw IOException("permission ${response.code}")
            }
    }

    fun events(sessionID: String? = null, onOpen: () -> Unit = {}): Flow<AgentEvent> = callbackFlow {
        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                onOpen()
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val event = AgentEvent.parse(data) ?: return
                if (sessionID != null && event.sessionID != null && event.sessionID != sessionID) return
                trySend(event)
            }

            override fun onClosed(eventSource: EventSource) {
                close(IOException("event stream closed"))
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                close(t ?: IOException("event stream failed ${response?.code ?: ""}"))
            }
        }

        val source = EventSources.createFactory(http)
            .newEventSource(request("/event").get().build(), listener)

        awaitClose { source.cancel() }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)

        private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
    }
}

private fun PartDto.toolTitle(): String? =
    state?.title?.takeIf { it.isNotBlank() }
        ?: (state?.input?.get("command") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun PartDto.toolOutput(): String? =
    (state?.output as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
