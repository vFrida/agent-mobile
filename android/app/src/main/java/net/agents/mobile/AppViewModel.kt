package net.agents.mobile

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)
    private val historyCache = HistoryCache(application)

    private companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1000L
        const val PAGE_SIZE = 20
    }
    private val _state = MutableStateFlow(run {
        val registryUrls = store.loadRegistryUrls()
        UiState(
            account = store.loadRegistryAccount(),
            showHiddenDirs = store.loadShowHiddenDirs(),
            hiddenAgentIds = store.loadHiddenAgentIds(),
            endpoint = store.load(),
            servers = store.loadServers(),
            registryUrl = store.loadRegistryUrl(registryUrls),
            registryUrls = registryUrls
        )
    })
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        if (_state.value.account != null) {
            viewModelScope.launch { autoConnectFromRegistry() }
        }
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
                _state.update {
                    it.copy(
                        streamState = streamState,
                        busy = if (backfill) false else it.busy
                    )
                }
                if (backfill) reloadHistory()
            }
        }
    }

    private fun client(): ComagicClient? {
        val endpoint = _state.value.endpoint
        return if (endpoint.ready) ComagicClient(endpoint) else null
    }

    fun saveServer(endpoint: Endpoint) {
        val (servers, saved) = upsertServer(_state.value.servers, endpoint)
        store.saveServers(servers, saved.id)
        closeSession()
        _state.update {
            it.copy(
                servers = servers,
                endpoint = saved,
                sessions = emptyList(),
                agents = emptyList(),
                models = emptyList(),
                error = null
            )
        }
        if (saved.ready) refreshSessions()
    }

    fun selectServer(server: Endpoint) {
        if (server.id == _state.value.endpoint.id) return
        store.saveServers(_state.value.servers, server.id)
        closeSession()
        _state.update {
            it.copy(
                endpoint = server,
                sessions = emptyList(),
                agents = emptyList(),
                models = emptyList(),
                error = null
            )
        }
        if (server.ready) refreshSessions()
    }

    fun deleteServer(server: Endpoint) {
        val hidden = _state.value.hiddenAgentIds + server.agentId
        store.saveHiddenAgentIds(hidden)
        val remaining = _state.value.servers.filterNot { it.id == server.id }
        if (_state.value.endpoint.id != server.id) {
            store.saveServers(remaining, _state.value.endpoint.id)
            _state.update {
                it.copy(
                    servers = remaining,
                    serverStatus = it.serverStatus - server.id,
                    hiddenAgentIds = hidden
                )
            }
            return
        }
        val next = remaining.firstOrNull() ?: Endpoint()
        store.saveServers(remaining, next.id)
        closeSession()
        _state.update {
            it.copy(
                servers = remaining,
                hiddenAgentIds = hidden,
                endpoint = next,
                sessions = emptyList(),
                agents = emptyList(),
                models = emptyList(),
                error = null
            )
        }
        if (next.ready) refreshSessions()
    }

    fun refreshServerStatuses() {
        val servers = _state.value.servers
        if (servers.isEmpty()) return
        _state.update { current ->
            current.copy(serverStatus = servers.associate { it.id to ServerStatus() })
        }
        viewModelScope.launch {
            val results = coroutineScope {
                servers.map { server ->
                    async(Dispatchers.IO) { server.id to probe(server) }
                }.awaitAll()
            }.toMap()
            _state.update { current ->
                current.copy(
                    serverStatus = current.serverStatus + results.filterKeys { id ->
                        current.servers.any { it.id == id }
                    }
                )
            }
        }
    }

    private fun probe(server: Endpoint): ServerStatus {
        if (!server.ready) {
            return ServerStatus(checking = false, error = "未填写地址或密码")
        }
        return runCatching { ComagicClient(server).health() }.fold(
            onSuccess = { health ->
                ServerStatus(checking = false, online = health.healthy, version = health.version)
            },
            onFailure = { error ->
                ServerStatus(checking = false, error = error.message ?: "无法连接")
            }
        )
    }

    fun refreshSessions() {
        val client = client() ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.listSessions() } }
                .onSuccess { sessions -> _state.update { it.copy(sessions = sessions, loading = false) } }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } }
            refreshOptions()
        }
    }

    private fun optionsDirectory(): String =
        _state.value.sessionDirectory.ifBlank { _state.value.endpoint.directory }

    fun refreshOptions() {
        val client = client() ?: return
        val directory = optionsDirectory()
        if (directory.isBlank()) return
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val agents = client.listAgents(directory)
                        .filter { it.mode == "primary" && !it.hidden }
                    val models = client.listModels(directory)
                    agents to models
                }
            }.onSuccess { (agents, models) ->
                _state.update { it.copy(agents = agents, models = models) }
            }
        }
    }

    fun selectAgent(agent: String) {
        val client = client() ?: return
        val sessionID = _state.value.sessionID ?: return
        if (agent == _state.value.sessionAgent) return
        _state.update { it.copy(sessionAgent = agent) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.switchAgent(sessionID, agent) } }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun selectModel(model: ModelRef) {
        val client = client() ?: return
        val sessionID = _state.value.sessionID ?: return
        _state.update { it.copy(sessionModel = model) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.switchModel(sessionID, model) } }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun connectRegistry() {
        viewModelScope.launch { autoConnectFromRegistry(force = true) }
    }

    private suspend fun autoConnectFromRegistry(force: Boolean = false) {
        if (!force && _state.value.servers.any { it.ready }) return
        val url = _state.value.registryUrl.trim()
        if (url.isBlank()) return
        _state.update { it.copy(registryLoading = true, registryError = null) }
        val account = _state.value.account
        val found = runCatching {
            withContext(Dispatchers.IO) {
                RegistryClient(url, account?.username ?: Credentials.username, account?.password ?: Credentials.password)
                    .listServers()
                    .comagicOnly()
            }
        }.getOrElse { error ->
            _state.update {
                it.copy(registryLoading = false, registryError = error.message ?: "注册中心请求失败")
            }
            return
        }
        _state.update { it.copy(registryServers = found, registryLoading = false, registryError = null) }
        val server = found.firstOrNull { it.online } ?: found.firstOrNull()
        if (server == null) {
            _state.update { it.copy(registryError = "注册中心暂无可用服务器") }
            return
        }
        val ordered = listOf(server) + found.filterNot { it.id == server.id }
        addRegistryServers(ordered, activateFirst = !_state.value.endpoint.ready)
        refreshServerStatuses()
    }

    fun logout() {
        store.clearRegistryAccount()
        closeSession()
        store.saveServers(emptyList(), "")
        _state.update {
            it.copy(
                account = null,
                loginLoading = false,
                loginError = null,
                endpoint = Endpoint(),
                servers = emptyList(),
                serverStatus = emptyMap(),
                registryServers = emptyList(),
                registryError = null,
                sessions = emptyList(),
                agents = emptyList(),
                models = emptyList(),
                error = null
            )
        }
    }

    fun restoreHiddenAgents() {
        store.saveHiddenAgentIds(emptySet())
        _state.update { it.copy(hiddenAgentIds = emptySet()) }
        refreshAgents()
    }

    fun setShowHiddenDirs(enabled: Boolean) {
        store.saveShowHiddenDirs(enabled)
        _state.update { it.copy(showHiddenDirs = enabled) }
    }

    fun selectRegistryAddress(url: String) {
        if (url !in _state.value.registryUrls) return
        store.saveRegistryUrl(url)
        _state.update { it.copy(registryUrl = url, loginError = null) }
    }

    fun addRegistryAddress(input: String) {
        val url = normalizeRegistryUrl(input)
        if (url.isBlank()) return
        val urls = (_state.value.registryUrls + url).distinct()
        store.saveRegistryUrls(urls)
        store.saveRegistryUrl(url)
        _state.update { it.copy(registryUrls = urls, registryUrl = url, loginError = null) }
    }

    fun removeRegistryAddress(url: String) {
        val urls = _state.value.registryUrls.filterNot { it == url }
        if (urls.isEmpty()) return
        store.saveRegistryUrls(urls)
        val active = if (_state.value.registryUrl == url) urls.first() else _state.value.registryUrl
        store.saveRegistryUrl(active)
        _state.update { it.copy(registryUrls = urls, registryUrl = active) }
    }

    fun login(baseUrl: String, username: String, password: String) {
        val url = normalizeRegistryUrl(baseUrl)
        if (url.isBlank()) {
            _state.update { it.copy(loginError = "请填写注册中心地址") }
            return
        }
        val user = username.trim().ifBlank { Credentials.username }
        val pass = password.ifBlank { Credentials.password }
        _state.update { it.copy(loginLoading = true, loginError = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { RegistryClient(url, user, pass).listServers() }
            }.onSuccess { raw ->
                val servers = raw.comagicOnly()
                val account = RegistryAccount(baseUrl = url, username = user, password = pass)
                store.saveRegistryAccount(account)
                val urls = (_state.value.registryUrls + url).distinct()
                store.saveRegistryUrls(urls)
                store.saveRegistryUrl(url)
                _state.update {
                    it.copy(
                        account = account,
                        loginLoading = false,
                        loginError = null,
                        registryUrl = url,
                        registryUrls = urls,
                        registryServers = servers
                    )
                }
                addRegistryServers(servers, activateFirst = !_state.value.endpoint.ready)
                refreshServerStatuses()
            }.onFailure { error ->
                _state.update {
                    it.copy(loginLoading = false, loginError = error.message ?: "登录失败")
                }
            }
        }
    }

    private fun pruneStaleRegistryServers(found: List<RegistryServer>, registryUrl: String) {
        val prefix = "$registryUrl/s/"
        val alive = found.map { "$prefix${it.id}".trimEnd('/') }.toSet()
        val current = _state.value.servers
        val remaining = current.filter { endpoint ->
            val url = endpoint.baseUrl.trimEnd('/')
            !url.startsWith(prefix) || url in alive
        }
        if (remaining.size == current.size) return
        val active = _state.value.endpoint
        val nextActive = when {
            remaining.any { it.id == active.id } -> active
            remaining.isNotEmpty() -> remaining.first()
            else -> Endpoint()
        }
        store.saveServers(remaining, nextActive.id)
        _state.update {
            it.copy(
                servers = remaining,
                endpoint = nextActive,
                serverStatus = it.serverStatus.filterKeys { id -> remaining.any { server -> server.id == id } }
            )
        }
    }

    private fun List<RegistryServer>.comagicOnly(): List<RegistryServer> =
        filter {
            it.online && (
                it.type.isBlank() ||
                    it.type.equals("comagic", ignoreCase = true) ||
                    it.type.equals("cursor", ignoreCase = true)
                )
        }

    private fun normalizeRegistryUrl(input: String): String {
        var value = input.trim()
        if (value.isBlank()) return ""
        if (!value.startsWith("http://") && !value.startsWith("https://")) value = "http://$value"
        val schemeEnd = value.indexOf("://") + 3
        val host = value.substring(schemeEnd)
        if (!host.contains(":") && !host.contains("/")) value = "$value:4908"
        return value.trimEnd('/')
    }

    fun selectRegistryUrl(url: String) {
        if (url !in _state.value.registryUrls) return
        store.saveRegistryUrl(url)
        _state.update {
            it.copy(registryUrl = url, registryServers = emptyList(), registryError = null)
        }
        refreshAgents()
    }

    fun addRegistryUrl(url: String) {
        val trimmed = normalizeRegistryUrl(url)
        if (trimmed.isBlank()) return
        val urls = (_state.value.registryUrls + trimmed).distinct()
        store.saveRegistryUrls(urls)
        store.saveRegistryUrl(trimmed)
        _state.update {
            it.copy(
                registryUrls = urls,
                registryUrl = trimmed,
                registryServers = emptyList(),
                registryError = null
            )
        }
        refreshAgents()
    }

    fun removeRegistryUrl(url: String) {
        val urls = _state.value.registryUrls.filterNot { it == url }
        if (urls.isEmpty()) return
        val wasActive = _state.value.registryUrl == url
        val active = if (wasActive) urls.first() else _state.value.registryUrl
        store.saveRegistryUrls(urls)
        store.saveRegistryUrl(active)
        _state.update {
            it.copy(
                registryUrls = urls,
                registryUrl = active,
                registryServers = emptyList(),
                registryError = null
            )
        }
        if (wasActive) refreshAgents()
    }

    fun refreshAgents() {
        refreshRegistry()
        refreshServerStatuses()
    }

    fun refreshRegistry() {
        val url = _state.value.registryUrl.trim()
        if (url.isBlank()) {
            _state.update { it.copy(registryError = "请先填写注册中心地址") }
            return
        }
        _state.update { it.copy(registryLoading = true, registryError = null) }
        viewModelScope.launch {
            val account = _state.value.account
            runCatching {
                withContext(Dispatchers.IO) {
                    RegistryClient(url, account?.username ?: Credentials.username, account?.password ?: Credentials.password)
                        .listServers()
                }
            }
                .onSuccess { raw ->
                    val servers = raw.comagicOnly()
                    val added = addRegistryServers(servers, activateFirst = !_state.value.endpoint.ready)
                    _state.update {
                        it.copy(registryServers = servers, registryLoading = false, registryError = null)
                    }
                    if (added > 0) refreshServerStatuses()
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(registryLoading = false, registryError = error.message ?: "注册中心请求失败")
                    }
                }
        }
    }

    fun addRegistryServer(server: RegistryServer) {
        if (addRegistryServers(listOf(server), activateFirst = true) > 0) {
            refreshServerStatuses()
        }
    }

    private fun addRegistryServers(found: List<RegistryServer>, activateFirst: Boolean): Int {
        val registryUrl = _state.value.registryUrl.trim().trimEnd('/')
        if (registryUrl.isBlank()) return 0
        pruneStaleRegistryServers(found, registryUrl)
        var added = 0
        found.forEach { server ->
            if (server.id.isBlank()) return@forEach
            if (server.id in _state.value.hiddenAgentIds) return@forEach
            val baseUrl = "$registryUrl/s/${server.id}"
            if (_state.value.servers.any { it.baseUrl.equals(baseUrl, ignoreCase = true) }) return@forEach
            val endpoint = Endpoint(
                name = server.name.ifBlank { server.id },
                agentType = server.type,
                agentVersion = server.version,
                baseUrl = baseUrl,
                username = Credentials.username,
                password = Credentials.password,
                directory = server.directory
            )
            if (activateFirst && added == 0) {
                saveServer(endpoint)
            } else {
                val (servers, _) = upsertServer(_state.value.servers, endpoint)
                store.saveServers(servers, _state.value.endpoint.id)
                _state.update { it.copy(servers = servers) }
            }
            added++
        }
        return added
    }

    fun openSession(session: SessionRef) {
        val cached = historyCache.load(session.id)
        _state.update {
            it.copy(
                sessionID = session.id,
                sessionTitle = session.title.ifBlank { session.id },
                sessionAgent = session.agent,
                sessionModel = session.model,
                sessionDirectory = session.location?.directory ?: "",
                sessionIDText = session.id,
                lines = cached?.lines ?: emptyList(),
                historyHasMore = cached?.hasMore ?: false,
                oldestMessageID = cached?.oldestID,
                historyLimit = cached?.limit?.coerceAtLeast(PAGE_SIZE) ?: PAGE_SIZE,
                loadingOlder = false,
                busy = false,
                pendingPermission = null,
                error = null
            )
        }
        val stale = cached == null || System.currentTimeMillis() - cached.savedAt > CACHE_TTL_MS
        reloadHistory(force = stale)
        refreshOptions()
        val context = getApplication<Application>()
        context.startForegroundService(AgentStreamService.intent(context, session.id))
    }

    fun ensureStream() {
        val current = _state.value
        val sessionID = current.sessionID ?: return
        if (!current.endpoint.ready) return
        val context = getApplication<Application>()
        runCatching {
            context.startForegroundService(AgentStreamService.intent(context, sessionID))
        }
        if (current.busy) _state.update { it.copy(busy = false) }
        reloadHistory()
    }

    fun closeSession() {
        val current = _state.value
        if (current.sessionID != null) {
            historyCache.save(
                current.sessionID,
                current.lines,
                current.historyHasMore,
                current.oldestMessageID,
                current.historyLimit
            )
        }
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

    fun deleteSession(session: SessionRef) {
        historyCache.remove(session.id)
        val client = client() ?: return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.deleteSession(session.id) } }
                .onSuccess {
                    if (_state.value.sessionID == session.id) closeSession()
                    _state.update { state ->
                        state.copy(
                            sessions = state.sessions.filterNot { it.id == session.id },
                            loading = false
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(loading = false, error = error.message) } }
        }
    }

    fun reloadHistory(force: Boolean = true) {
        val client = client() ?: return
        val sessionID = _state.value.sessionID ?: return
        if (!force) {
            val cached = historyCache.load(sessionID)
            if (cached != null && System.currentTimeMillis() - cached.savedAt <= CACHE_TTL_MS) return
        }
        val limit = _state.value.historyLimit.coerceAtLeast(PAGE_SIZE)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.history(sessionID, limit = limit) } }
                .onSuccess { page ->
                    historyCache.save(
                        sessionID = sessionID,
                        lines = page.lines,
                        hasMore = page.hasMore,
                        oldestID = page.oldestID,
                        limit = limit
                    )
                    _state.update {
                        it.copy(
                            lines = page.lines,
                            historyHasMore = page.hasMore,
                            oldestMessageID = page.oldestID,
                            loadingOlder = false
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    fun loadOlder() {
        val current = _state.value
        if (current.loadingOlder || !current.historyHasMore) return
        val before = current.oldestMessageID ?: return
        val client = client() ?: return
        val sessionID = current.sessionID ?: return
        _state.update { it.copy(loadingOlder = true) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.history(sessionID, limit = PAGE_SIZE, before = before) }
            }.onSuccess { page ->
                val state = _state.value
                val merged = page.lines + state.lines
                val hasMore = page.hasMore
                val oldest = page.oldestID ?: state.oldestMessageID
                val limit = state.historyLimit + PAGE_SIZE
                historyCache.save(sessionID, merged, hasMore, oldest, limit)
                _state.update {
                    it.copy(
                        lines = merged,
                        historyHasMore = hasMore,
                        oldestMessageID = oldest,
                        historyLimit = limit,
                        loadingOlder = false
                    )
                }
            }.onFailure { error ->
                _state.update { it.copy(loadingOlder = false, error = error.message) }
            }
        }
    }

    fun openDirectoryPicker() {
        val client = client() ?: return
        val start = _state.value.endpoint.directory
        _state.update {
            it.copy(directoryPicker = DirectoryPickerState(loading = true), error = null)
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val paths = runCatching { client.paths(start) }.getOrElse { client.paths("") }
                    val base = paths.directory.ifBlank { paths.home }
                    Triple(base, paths.home, client.listEntries(base, base))
                }
            }.onSuccess { (base, home, entries) ->
                _state.update {
                    it.copy(
                        directoryPicker = DirectoryPickerState(
                            current = base,
                            home = home,
                            entries = entries,
                            loading = false
                        )
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(
                        directoryPicker = it.directoryPicker?.copy(
                            loading = false,
                            error = error.message
                        )
                    )
                }
            }
        }
    }

    fun navigateDirectory(path: String) {
        val client = client() ?: return
        val picker = _state.value.directoryPicker ?: return
        _state.update {
            it.copy(directoryPicker = picker.copy(current = path, loading = true, error = null))
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.listEntries(path, path) } }
                .onSuccess { entries ->
                    _state.update {
                        it.copy(directoryPicker = it.directoryPicker?.copy(entries = entries, loading = false))
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(directoryPicker = it.directoryPicker?.copy(loading = false, error = error.message))
                    }
                }
        }
    }

    fun closeDirectoryPicker() {
        _state.update { it.copy(directoryPicker = null) }
    }

    fun confirmDirectory(path: String) {
        val endpoint = _state.value.endpoint.copy(directory = path)
        val servers = _state.value.servers.map { if (it.id == endpoint.id) endpoint else it }
        store.saveServers(servers, endpoint.id)
        _state.update { it.copy(endpoint = endpoint, servers = servers, directoryPicker = null) }
        newSession(path)
    }

    fun newSession(directory: String) {
        val client = client() ?: return
        val current = _state.value
        val defaults = current.registryDefaults
        val model = defaults?.defaultModel
            ?: pickDefaultModel(current.models, current.sessionModel)
            ?: current.sessions.firstNotNullOfOrNull { it.model }
        if (model == null) {
            _state.update { it.copy(error = "没有可用模型：先打开一个已有会话再新建") }
            return
        }
        val agent = defaults?.defaultAgent?.takeIf { it.isNotBlank() }
            ?: current.sessionAgent.takeIf { it.isNotBlank() }
            ?: "build"
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.createSession(directory, model, agent) } }
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
            }.onFailure { error ->
                val busy = error is SessionBusyException
                if (busy) reloadHistory()
                _state.update { it.copy(busy = busy, error = friendlyError(error)) }
            }
        }
    }

    fun sendImage(uri: Uri, caption: String) {
        val client = client() ?: return
        val current = _state.value
        val sessionID = current.sessionID ?: return
        val text = caption.trim()

        _state.update {
            it.copy(
                lines = it.lines +
                    ChatLine(role = "user", text = text, image = uri.toString()) +
                    ChatLine(role = "assistant"),
                busy = true,
                error = null
            )
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val image = getApplication<Application>().prepareImage(uri)
                    client.prompt(sessionID, text, current.sessionAgent, current.sessionModel, image)
                }
            }.onFailure { error ->
                val busy = error is SessionBusyException
                if (busy) reloadHistory()
                _state.update { it.copy(busy = busy, error = friendlyError(error)) }
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

    private fun friendlyError(error: Throwable): String =
        if (error is SessionBusyException) {
            "agent 还在执行上一条消息，等它结束或点「中断」后再发"
        } else {
            error.message ?: "发送失败"
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
                val line = ChatLine(
                    role = "tool",
                    tool = name,
                    toolID = event.toolID,
                    toolTitle = event.toolTitle,
                    toolOutput = event.toolOutput
                )
                _state.update { state ->
                    val index = line.toolID
                        ?.takeIf { it.isNotBlank() }
                        ?.let { id -> state.lines.indexOfLast { it.role == "tool" && it.toolID == id } }
                        ?: -1
                    if (index < 0) {
                        state.copy(lines = state.lines + line)
                    } else {
                        val old = state.lines[index]
                        val lines = state.lines.toMutableList()
                        lines[index] = line.copy(
                            toolTitle = line.toolTitle ?: old.toolTitle,
                            toolOutput = line.toolOutput ?: old.toolOutput
                        )
                        state.copy(lines = lines)
                    }
                }
            }

            "session.tool.failed", "session.error", "message.error" -> {
                val message = event.errorText ?: event.type
                _state.update { state ->
                    state.copy(lines = state.lines + ChatLine(role = "error", text = message), busy = false)
                }
            }

            "session.idle" -> {
                _state.update { it.copy(busy = false) }
                reloadHistory()
            }

            "session.execution.succeeded", "session.execution.failed" -> {
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
