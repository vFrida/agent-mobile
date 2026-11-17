package net.pdd.comagic.mobile

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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

    private inline fun <T> blocking(path: String, errorLabel: String, block: (String) -> T): T {
        http.newCall(request(path).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("$errorLabel ${response.code}: ${body.take(300)}")
            return block(body)
        }
    }

    fun health(): HealthResponse =
        blocking("/global/health", "health") { json.decodeFromString(it) }

    fun listSessions(): List<SessionRef> =
        blocking("/api/session?limit=50&order=desc", "sessions") {
            json.decodeFromString<SessionListResponse>(it).data
        }

    fun history(sessionID: String): List<ChatLine> =
        blocking("/session/$sessionID/message", "history") { body ->
            val messages = json.decodeFromString<List<MessageDto>>(body)
            messages.mapNotNull { message ->
                val texts = message.parts.filter { it.type == "text" }.map { it.text }.joinToString("")
                when (message.info.role) {
                    "user" -> if (texts.isBlank()) null else ChatLine(role = "user", text = texts)
                    "assistant" -> {
                        val tool = message.parts.lastOrNull { it.type == "tool" }?.tool
                        when {
                            texts.isNotBlank() -> ChatLine(role = "assistant", text = texts)
                            tool != null -> ChatLine(role = "tool", tool = tool)
                            else -> null
                        }
                    }
                    else -> null
                }
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

    fun prompt(sessionID: String, text: String, agent: String?, model: ModelRef?) {
        val payload = buildJsonObject {
            put("parts", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
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
                    throw IOException("prompt ${response.code}: ${response.body?.string()?.take(300)}")
                }
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

    fun events(sessionID: String? = null): Flow<AgentEvent> = callbackFlow {
        val listener = object : EventSourceListener() {
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
    }
}
