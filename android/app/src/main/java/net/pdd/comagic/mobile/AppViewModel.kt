package net.pdd.comagic.mobile

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val _state = MutableStateFlow(UiState(endpoint = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            AgentRepository.events.collect { event ->
                val current = _state.value.sessionID ?: return@collect
                if (event.sessionID == null || event.sessionID == current) handle(event)
            }
        }
        viewModelScope.launch {
            var previous: StreamState? = null
            AgentRepository.state.collect { streamState ->
                val backfill = streamState == StreamState.LIVE && previous == StreamState.RECONNECTING
                previous = streamState
                _state.update { it.copy(streamState = streamState) }
                if (backfill) reloadHistory()
            }
        }
    }

    private fun client(): ComagicClient? {
        val endpoint = _state.value.endpoint
        return if (endpoint.ready) ComagicClient(endpoint) else null
    }

    fun connect(endpoint: Endpoint) {
        store.save(endpoint)
        _state.update { it.copy(endpoint = endpoint, error = null) }
        refreshSessions()
    }

    fun refreshSessions() {
        val client = client() ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.listSessions() } }
                .onSuccess { sessions -> _state.update { it.copy(sessions = sessions, loading = false) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } }
        }
    }

    fun openSession(session: SessionRef) {
        _state.update {
            it.copy(
                sessionID = session.id,
                sessionTitle = session.title.ifBlank { session.id },
                sessionAgent = session.agent,
                sessionModel = session.model,
                lines = emptyList(),
                busy = false,
                pendingPermission = null,
                error = null
            )
        }
        reloadHistory()
        val context = getApplication<Application>()
        context.startForegroundService(AgentStreamService.intent(context, session.id))
    }

    fun closeSession() {
        val context = getApplication<Application>()
        context.stopService(Intent(context, AgentStreamService::class.java))
        AgentRepository.stop()
        _state.update {
            it.copy(
                sessionID = null,
                sessionTitle = "",
                lines = emptyList(),
                busy = false,
                pendingPermission = null,
                streamState = StreamState.IDLE
            )
        }
    }

    fun reloadHistory() {
        val client = client() ?: return
        val sessionID = _state.value.sessionID ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.history(sessionID) } }
                .onSuccess { lines -> _state.update { it.copy(lines = lines) } }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun newSession() {
        val client = client() ?: return
        val current = _state.value
        val model = current.sessions.firstNotNullOfOrNull { it.model }
        if (model == null) {
            _state.update { it.copy(error = "没有可用模型：先打开一个已有会话再新建") }
            return
        }
        val directory = current.endpoint.directory.trim()
        if (directory.isEmpty()) {
            _state.update { it.copy(error = "请先在设置里填写工作目录，例如 /Users/txc") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.createSession(directory, model) } }
                .onSuccess { session ->
                    _state.update { it.copy(loading = false) }
                    openSession(session)
                    refreshSessions()
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } }
        }
    }

    fun send(text: String) {
        val client = client() ?: return
        val sessionID = _state.value.sessionID ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        val current = _state.value
        _state.update {
            it.copy(
                lines = it.lines + ChatLine(role = "user", text = trimmed) + ChatLine(role = "assistant"),
                busy = true,
                error = null
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    client.prompt(sessionID, trimmed, current.sessionAgent, current.sessionModel)
                }
            }.onSuccess {
                reloadHistory()
                _state.update { it.copy(busy = false) }
            }.onFailure { error ->
                _state.update { it.copy(busy = false, error = error.message) }
            }
        }
    }

    fun interrupt() {
        val client = client() ?: return
        val sessionID = _state.value.sessionID ?: return
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.interrupt(sessionID) } }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun replyPermission(reply: String) {
        val client = client() ?: return
        val current = _state.value
        val sessionID = current.sessionID ?: return
        val ask = current.pendingPermission ?: return
        _state.update { it.copy(pendingPermission = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.replyPermission(sessionID, ask.requestID, reply) } }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    private fun handle(event: AgentEvent) {
        when (event.type) {
            "session.text.delta", "message.part.delta" -> {
                val delta = event.delta ?: return
                _state.update { state -> state.copy(lines = appendAssistant(state.lines, delta), busy = true) }
            }

            "session.tool.called" -> {
                val name = event.toolName ?: "tool"
                _state.update { state -> state.copy(lines = state.lines + ChatLine(role = "tool", tool = name)) }
            }

            "session.tool.failed", "session.error", "message.error" -> {
                val message = event.errorText ?: event.type
                _state.update { state ->
                    state.copy(lines = state.lines + ChatLine(role = "error", text = message), busy = false)
                }
            }

            "session.idle", "session.execution.succeeded", "session.execution.failed" -> {
                _state.update { it.copy(busy = false) }
            }

            "permission.asked" -> {
                val requestID = event.requestID ?: return
                val title = event.requestTitle ?: "需要确认工具调用"
                _state.update { it.copy(pendingPermission = PermissionAsk(requestID, title), busy = false) }
            }

            "question.asked" -> {
                _state.update {
                    it.copy(pendingPermission = PermissionAsk("", "agent 有一个问题待回答（手机端暂未实现）"))
                }
            }
        }
    }

    private fun appendAssistant(lines: List<ChatLine>, delta: String): List<ChatLine> {
        if (lines.isEmpty() || lines.last().role != "assistant") {
            return lines + ChatLine(role = "assistant", text = delta)
        }
        val updated = lines.toMutableList()
        val last = updated.removeAt(updated.lastIndex)
        updated.add(last.copy(text = last.text + delta))
        return updated
    }
}
