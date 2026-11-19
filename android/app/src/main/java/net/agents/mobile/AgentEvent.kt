package net.agents.mobile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

data class AgentEvent(
    val id: String,
    val type: String,
    val payload: JsonObject
) {
    private fun JsonObject.firstString(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    private fun JsonObject.nested(container: String): JsonObject? = this[container] as? JsonObject

    val sessionID: String?
        get() = payload.firstString("sessionID")

    val delta: String?
        get() = payload.firstString("delta")
            ?: payload.nested("part")?.firstString("text")
            ?: payload.firstString("text")

    val toolName: String?
        get() = payload.nested("tool")?.firstString("name")
            ?: payload.firstString("toolName", "name")

    val toolID: String?
        get() = payload.firstString("toolID")

    val toolTitle: String?
        get() = payload.firstString("title")?.takeIf { it.isNotBlank() }

    val toolOutput: String?
        get() = payload.firstString("output")?.takeIf { it.isNotBlank() }

    val errorText: String?
        get() = payload.nested("error")?.firstString("message")
            ?: payload.firstString("message")

    val requestID: String?
        get() = payload.firstString("requestID", "id", "permissionID")

    val requestTitle: String?
        get() = payload.nested("permission")?.firstString("title")
            ?: payload.firstString("title", "message")
            ?: payload.firstString("action")?.let { action ->
                val resources = (payload["resources"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                if (resources.isEmpty()) action else "$action: ${resources.joinToString(", ")}"
            }
            ?: payload.firstString("permission")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): AgentEvent? {
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            val type = (root["type"] as? JsonPrimitive)?.contentOrNull ?: return null
            val id = (root["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val payload = (root["data"] as? JsonObject)
                ?: (root["properties"] as? JsonObject)
                ?: JsonObject(emptyMap())
            return AgentEvent(id = id, type = type, payload = payload)
        }
    }
}
