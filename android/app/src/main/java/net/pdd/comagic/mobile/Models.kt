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
    val time: SessionTime? = null
)

@Serializable
data class Location(val directory: String)

@Serializable
data class SessionListResponse(val data: List<SessionRef> = emptyList())

@Serializable
data class SessionCreateResponse(val data: SessionRef? = null)

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
    val tool: String = ""
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
    val tool: String? = null
)

data class PermissionAsk(
    val requestID: String,
    val title: String
)

data class UiState(
    val endpoint: Endpoint = Endpoint(),
    val sessions: List<SessionRef> = emptyList(),
    val sessionID: String? = null,
    val sessionTitle: String = "",
    val sessionAgent: String = "build",
    val sessionModel: ModelRef? = null,
    val lines: List<ChatLine> = emptyList(),
    val busy: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val pendingPermission: PermissionAsk? = null,
    val streamState: StreamState = StreamState.IDLE
)
