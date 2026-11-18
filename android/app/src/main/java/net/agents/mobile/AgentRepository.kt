package net.pdd.comagic.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class StreamState { IDLE, CONNECTING, LIVE, RECONNECTING }

object AgentRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 512)
    private val _state = MutableStateFlow(StreamState.IDLE)
    private var job: Job? = null

    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()
    val state: StateFlow<StreamState> = _state.asStateFlow()

    fun start(endpoint: Endpoint, sessionID: String) {
        stop()
        val client = ComagicClient(endpoint)
        job = scope.launch {
            var backoff = 1_000L
            var dropped = false
            while (isActive) {
                _state.value = if (dropped) StreamState.RECONNECTING else StreamState.CONNECTING
                runCatching {
                    client.events(sessionID) {
                        _state.value = StreamState.LIVE
                        backoff = 1_000L
                    }.collect { event ->
                        _events.emit(event)
                    }
                }
                if (!isActive) break
                dropped = true
                _state.value = StreamState.RECONNECTING
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _state.value = StreamState.IDLE
    }
}
