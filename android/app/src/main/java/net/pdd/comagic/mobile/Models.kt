package net.pdd.comagic.mobile

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.UUID

@Serializable
data class ModelRef(
    val id: String,
    val providerID: String,
    val variant: String? = null
)

@Serializable
data class SessionTime(
    val created: Long = 0,
    val updated: Long = 0
)

@Serializable
data class SessionRef(
    val id: String,
    val title: String = "",
    val agent: String = "build",
    val model: ModelRef? = null,
    val time: SessionTime? = null,
    val location: Location? = null,
    val subpath: String = ""
)

@Serializable
data class Location(val directory: String)

@Serializable
data class SessionListResponse(val data: List<SessionRef> = emptyList())

@Serializable
data class SessionCreateResponse(val data: SessionRef? = null)

@Serializable
data class AgentInfo(
    val id: String,
    val description: String = "",
    val mode: String = "",
    val hidden: Boolean = false,
    val model: ModelRef? = null
)

@Serializable
data class AgentListResponse(val data: List<AgentInfo> = emptyList())

@Serializable
data class ModelInfo(
    val id: String,
    val providerID: String = "",
    val name: String = "",
    val status: String = "",
    val enabled: Boolean = true
) {
    fun ref(variant: String? = null) = ModelRef(id = id, providerID = providerID, variant = variant)
}

fun pickDefaultModel(models: List<ModelInfo>, sessionModel: ModelRef? = null): ModelRef? {
    val deepseek = models.firstOrNull { model ->
        listOf(model.id, model.providerID, model.name).any {
            it.contains("deepseek", ignoreCase = true)
        }
    }
    return deepseek?.ref() ?: sessionModel ?: models.firstOrNull()?.ref()
}

@Serializable
data class ProviderInfo(
    val id: String,
    val name: String = "",
    val models: Map<String, ModelInfo> = emptyMap()
)

@Serializable
data class ProvidersResponse(
    val providers: List<ProviderInfo> = emptyList(),
    val default: Map<String, String> = emptyMap()
)

@Serializable
data class CreateSessionRequest(
    val agent: String,
    val model: ModelRef,
    val location: Location
)

@Serializable
data class PromptRequest(
    val id: String,
    val text: String,
    val delivery: String? = null
)

@Serializable
data class PathInfo(
    val home: String = "",
    val state: String = "",
    val config: String = "",
    val worktree: String = "",
    val directory: String = ""
)

@Serializable
data class FsEntry(
    val path: String,
    val type: String = "directory"
)

@Serializable
data class FsListResponse(val data: List<FsEntry> = emptyList())

@Serializable
data class HealthResponse(
    val healthy: Boolean = false,
    val version: String = ""
)

@Serializable
data class MessageInfoDto(
    val id: String = "",
    val role: String = "",
    val time: SessionTime? = null
)

@Serializable
data class ToolStateDto(
    val status: String = "",
    val title: String = "",
    val input: JsonObject? = null,
    val output: JsonElement? = null
)

@Serializable
data class PartDto(
    val type: String = "",
    val text: String = "",
    val tool: String = "",
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null,
    val callID: String = "",
    val state: ToolStateDto? = null
)

@Serializable
data class MessageDto(
    val info: MessageInfoDto = MessageInfoDto(),
    val parts: List<PartDto> = emptyList()
)

object Credentials {
    const val username = "comagic"
    const val password = "YFl4cVaQ8smozi4I"
}

@Serializable
data class Endpoint(
    val id: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val username: String = Credentials.username,
    val password: String = Credentials.password,
    val directory: String = ""
) {
    val ready: Boolean
        get() = baseUrl.isNotBlank() && password.isNotBlank()

    val label: String
        get() = name.ifBlank {
            baseUrl.trim()
                .removePrefix("https://")
                .removePrefix("http://")
                .ifBlank { "未命名服务器" }
        }
}

fun upsertServer(
    servers: List<Endpoint>,
    endpoint: Endpoint,
    generateId: () -> String = { UUID.randomUUID().toString() }
): Pair<List<Endpoint>, Endpoint> {
    var saved = endpoint.copy(
        baseUrl = endpoint.baseUrl.trim().trimEnd('/'),
        username = endpoint.username.trim().ifBlank { Credentials.username },
        directory = endpoint.directory.trim()
    )
    val result = servers.toMutableList()
    val byId = result.indexOfFirst { saved.id.isNotBlank() && it.id == saved.id }
    val byUrl = result.indexOfFirst { it.baseUrl.equals(saved.baseUrl, ignoreCase = true) }
    when {
        byId >= 0 -> {
            result[byId] = saved
            result.removeAll {
                it.id != saved.id && it.baseUrl.equals(saved.baseUrl, ignoreCase = true)
            }
        }
        byUrl >= 0 -> {
            saved = saved.copy(id = result[byUrl].id)
            result[byUrl] = saved
        }
        else -> {
            saved = saved.copy(id = generateId())
            result.add(saved)
        }
    }
    return result to saved
}

data class ChatLine(
    val role: String,
    val text: String = "",
    val tool: String? = null,
    val image: String? = null
)

data class PermissionAsk(
    val requestID: String,
    val title: String
)

data class DirectoryPickerState(
    val current: String = "",
    val home: String = "",
    val entries: List<FsEntry> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null
)

data class ServerStatus(
    val checking: Boolean = true,
    val online: Boolean = false,
    val version: String = "",
    val error: String? = null
)

data class UiState(
    val endpoint: Endpoint = Endpoint(),
    val servers: List<Endpoint> = emptyList(),
    val serverStatus: Map<String, ServerStatus> = emptyMap(),
    val sessions: List<SessionRef> = emptyList(),
    val agents: List<AgentInfo> = emptyList(),
    val models: List<ModelInfo> = emptyList(),
    val sessionID: String? = null,
    val sessionTitle: String = "",
    val sessionAgent: String = "build",
    val sessionModel: ModelRef? = null,
    val sessionDirectory: String = "",
    val sessionIDText: String = "",
    val lines: List<ChatLine> = emptyList(),
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val pendingPermission: PermissionAsk? = null,
    val directoryPicker: DirectoryPickerState? = null,
    val streamState: StreamState = StreamState.IDLE
)
