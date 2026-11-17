package net.pdd.comagic.mobile

import kotlinx.serialization.Serializable

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
data class PartDto(
    val type: String = "",
    val text: String = "",
    val tool: String = "",
    val mime: String? = null,
    val filename: String? = null,
    val url: String? = null
)

@Serializable
data class MessageDto(
    val info: MessageInfoDto = MessageInfoDto(),
    val parts: List<PartDto> = emptyList()
)

data class Endpoint(
    val baseUrl: String = "",
    val username: String = "comagic",
    val password: String = "",
    val directory: String = ""
) {
    val ready: Boolean
        get() = baseUrl.isNotBlank() && password.isNotBlank()
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

data class UiState(
    val endpoint: Endpoint = Endpoint(),
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
