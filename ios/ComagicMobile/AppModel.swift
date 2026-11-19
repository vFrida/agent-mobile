import Foundation
import UIKit

@MainActor
final class AppModel: ObservableObject {
    @Published var endpoint: Endpoint
    @Published var servers: [Endpoint] = []
    @Published var serverStatus: [String: ServerAvailability] = [:]
    @Published var sessions: [SessionRef] = []
    @Published var agents: [AgentInfo] = []
    @Published var models: [ModelInfo] = []
    @Published var loading = false
    @Published var error: String?
    @Published var current: SessionRef?
    @Published var lines: [ChatLine] = []
    @Published var busy = false
    @Published var reconnecting = false
    @Published var pendingPermission: String?
    @Published var currentDirectory = ""
    @Published var directoryPicker: DirectoryPickerState?
    @Published var historyHasMore = false
    @Published var oldestMessageID: String?
    @Published var historyLimit = 20
    @Published var loadingOlder = false
    @Published var account: RegistryAccount?
    @Published var showHiddenDirs: Bool
    @Published var hiddenAgentIDs: Set<String> = []
    @Published var loginLoading = false
    @Published var loginError: String?
    @Published var registryURL: String
    @Published var registryURLs: [String] = []
    @Published var registryServers: [RegistryServer] = []
    @Published var registryLoading = false
    @Published var registryError: String?

    var currentAgent: String { current?.agent ?? "-" }
    var currentModelLabel: String {
        guard let model = current?.model else { return "模型" }
        if let known = models.first(where: {
            $0.id == model.id && (($0.providerID ?? "") == model.providerID || ($0.providerID ?? "").isEmpty)
        }) {
            return known.name ?? model.id
        }
        return model.id
    }
    var currentID: String { current?.id ?? "" }

    private var streamTask: Task<Void, Never>?

    init() {
        servers = EndpointStore.shared.servers
        endpoint = EndpointStore.shared.endpoint
        registryURL = EndpointStore.shared.registryURL
        registryURLs = EndpointStore.shared.registryURLs
        account = EndpointStore.shared.registryAccount
        showHiddenDirs = EndpointStore.shared.showHiddenDirs
        hiddenAgentIDs = EndpointStore.shared.hiddenAgentIDs
        if account != nil && !servers.contains(where: { $0.ready }) {
            Task { await autoConnectFromRegistry() }
        }
    }

    func connectRegistry() async {
        await autoConnectFromRegistry(force: true)
    }

    private func autoConnectFromRegistry(force: Bool = false) async {
        if !force && servers.contains(where: { $0.ready }) { return }
        let url = registryURL.trimmingCharacters(in: .whitespaces)
        guard !url.isEmpty else { return }
        registryLoading = true
        registryError = nil
        defer { registryLoading = false }
        do {
            let found = try await registryClient.listServers().filter { Self.isSupportedAgent($0) }
            registryServers = found
            guard let server = found.first(where: { $0.online == true }) ?? found.first else {
                registryError = "注册中心暂无可用服务器"
                return
            }
            let ordered = [server] + found.filter { $0.id != server.id }
            await addRegistryServers(ordered, activateFirst: !endpoint.ready)
            await refreshServerStatuses()
        } catch {
            registryError = error.localizedDescription
        }
    }

    func saveRegistryURL(_ value: String) {
        registryURL = value
        EndpointStore.shared.registryURL = value
        registryError = nil
    }

    func restoreHiddenAgents() async {
        hiddenAgentIDs = []
        EndpointStore.shared.hiddenAgentIDs = []
        await refreshRegistry()
        await refreshServerStatuses()
    }

    func setShowHiddenDirs(_ enabled: Bool) {
        showHiddenDirs = enabled
        EndpointStore.shared.showHiddenDirs = enabled
    }

    func logout() {
        EndpointStore.shared.registryAccount = nil
        EndpointStore.shared.clearServers()
        account = nil
        loginError = nil
        close()
        endpoint = Endpoint()
        servers = []
        registryServers = []
        registryError = nil
        sessions = []
        agents = []
        models = []
        error = nil
    }

    func selectRegistryAddress(_ url: String) {
        guard registryURLs.contains(url) else { return }
        EndpointStore.shared.registryURL = url
        registryURL = url
        loginError = nil
    }

    func addRegistryAddress(_ input: String) {
        let url = Self.normalizeRegistryURL(input)
        guard !url.isEmpty else { return }
        var urls = registryURLs
        if !urls.contains(url) { urls.append(url) }
        EndpointStore.shared.registryURLs = urls
        EndpointStore.shared.registryURL = url
        registryURLs = EndpointStore.shared.registryURLs
        registryURL = url
        loginError = nil
    }

    func removeRegistryAddress(_ url: String) {
        var urls = registryURLs.filter { $0 != url }
        guard !urls.isEmpty else { return }
        EndpointStore.shared.registryURLs = urls
        urls = EndpointStore.shared.registryURLs
        registryURLs = urls
        if registryURL == url, let first = urls.first {
            registryURL = first
            EndpointStore.shared.registryURL = first
        }
    }

    func login(baseURL: String, username: String, password: String) async {
        let url = Self.normalizeRegistryURL(baseURL)
        guard !url.isEmpty else {
            loginError = "请填写注册中心地址"
            return
        }
        let trimmedUser = username.trimmingCharacters(in: .whitespaces)
        let user = trimmedUser.isEmpty ? Credentials.username : trimmedUser
        let pass = password.isEmpty ? Credentials.password : password
        loginLoading = true
        loginError = nil
        defer { loginLoading = false }
        do {
            let found = try await RegistryClient(baseURL: url, username: user, password: pass)
                .listServers()
                .filter { Self.isSupportedAgent($0) }
            let account = RegistryAccount(baseURL: url, username: user, password: pass)
            EndpointStore.shared.registryAccount = account
            var urls = registryURLs
            if !urls.contains(url) { urls.append(url) }
            EndpointStore.shared.registryURLs = urls
            EndpointStore.shared.registryURL = url
            self.account = account
            registryURLs = EndpointStore.shared.registryURLs
            registryURL = url
            registryServers = found
            registryError = nil
            await addRegistryServers(found, activateFirst: !endpoint.ready)
            await refreshServerStatuses()
        } catch {
            loginError = error.localizedDescription
        }
    }

    private static func isSupportedAgent(_ server: RegistryServer) -> Bool {
        guard server.online == true else { return false }
        let type = (server.type ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return type.isEmpty || type == "comagic" || type == "cursor"
    }

    private static func normalizeRegistryURL(_ input: String) -> String {
        var value = input.trimmingCharacters(in: .whitespaces)
        guard !value.isEmpty else { return "" }
        if !value.hasPrefix("http://") && !value.hasPrefix("https://") {
            value = "http://" + value
        }
        if let range = value.range(of: "://") {
            let host = String(value[range.upperBound...])
            if !host.contains(":") && !host.contains("/") {
                value += ":4908"
            }
        }
        while value.hasSuffix("/") { value.removeLast() }
        return value
    }

    func selectRegistryURL(_ url: String) async {
        guard registryURLs.contains(url) else { return }
        EndpointStore.shared.registryURL = url
        registryURL = url
        registryServers = []
        registryError = nil
        await refreshRegistry()
        await refreshServerStatuses()
    }

    func addRegistryURL(_ url: String) async {
        let trimmed = Self.normalizeRegistryURL(url)
        guard !trimmed.isEmpty else { return }
        var urls = registryURLs
        if !urls.contains(trimmed) { urls.append(trimmed) }
        EndpointStore.shared.registryURLs = urls
        EndpointStore.shared.registryURL = trimmed
        registryURLs = EndpointStore.shared.registryURLs
        registryURL = trimmed
        registryServers = []
        registryError = nil
        await refreshRegistry()
        await refreshServerStatuses()
    }

    func removeRegistryURL(_ url: String) async {
        var urls = registryURLs
        urls.removeAll { $0 == url }
        guard !urls.isEmpty else { return }
        let wasActive = registryURL == url
        EndpointStore.shared.registryURLs = urls
        registryURLs = EndpointStore.shared.registryURLs
        if wasActive {
            registryURL = registryURLs[0]
            EndpointStore.shared.registryURL = registryURL
            registryServers = []
            registryError = nil
            await refreshRegistry()
            await refreshServerStatuses()
        }
    }

    func refreshRegistry() async {
        let url = registryURL.trimmingCharacters(in: .whitespaces)
        guard !url.isEmpty else {
            registryError = "请先填写注册中心地址"
            return
        }
        registryLoading = true
        registryError = nil
        do {
            let found = try await registryClient.listServers().filter { Self.isSupportedAgent($0) }
            registryServers = found
            let added = await addRegistryServers(found, activateFirst: !endpoint.ready)
            if added > 0 { await refreshServerStatuses() }
        } catch {
            registryError = error.localizedDescription
        }
        registryLoading = false
    }

    @discardableResult
    func addRegistryServers(_ found: [RegistryServer], activateFirst: Bool) async -> Int {
        var base = registryURL.trimmingCharacters(in: .whitespaces)
        while base.hasSuffix("/") { base.removeLast() }
        guard !base.isEmpty else { return 0 }
        pruneStaleRegistryServers(found, prefix: "\(base)/s/")
        var added = 0
        for server in found where !server.id.isEmpty {
            let address = "\(base)/s/\(server.id)"
            if hiddenAgentIDs.contains(server.id) { continue }
            if servers.contains(where: { $0.baseURL.normalizedURL == address.normalizedURL }) { continue }
            var endpoint = Endpoint(
                name: server.name ?? server.id,
                agentType: server.type,
                agentVersion: server.version,
                baseURL: address,
                username: Credentials.username,
                password: Credentials.password,
                directory: server.directory ?? ""
            )
            if activateFirst && added == 0 {
                endpoint = EndpointStore.shared.upsert(endpoint)
                EndpointStore.shared.activate(endpoint)
                self.endpoint = endpoint
                close()
                await refresh()
            } else {
                _ = EndpointStore.shared.upsert(endpoint)
            }
            servers = EndpointStore.shared.servers
            added += 1
        }
        return added
    }

    var loggedIn: Bool { account != nil }

    private var registryClient: RegistryClient {
        RegistryClient(
            baseURL: registryURL,
            username: account?.username ?? Credentials.username,
            password: account?.password ?? Credentials.password
        )
    }

    private func pruneStaleRegistryServers(_ found: [RegistryServer], prefix: String) {
        let alive = Set(found.map { "\(prefix)\($0.id)" })
        let current = servers
        let remaining = current.filter { endpoint in
            var url = endpoint.baseURL
            while url.hasSuffix("/") { url.removeLast() }
            return !url.hasPrefix(prefix) || alive.contains(url)
        }
        guard remaining.count != current.count else { return }
        let next = remaining.first(where: { $0.id == endpoint.id }) ?? remaining.first ?? Endpoint()
        EndpointStore.shared.replaceAll(remaining, activeID: next.id)
        servers = remaining
        endpoint = next
        close()
    }

    private var client: ComagicClient? {
        endpoint.ready ? ComagicClient(endpoint: endpoint) : nil
    }

    func connect(_ new: Endpoint) {
        let saved = EndpointStore.shared.upsert(new)
        endpoint = saved
        servers = EndpointStore.shared.servers
        close()
        error = nil
        Task { await refresh() }
    }

    func selectServer(_ server: Endpoint) async {
        guard server.id != endpoint.id else { return }
        EndpointStore.shared.activate(server)
        close()
        endpoint = server
        sessions = []
        agents = []
        models = []
        error = nil
        await refresh()
    }

    func deleteServer(_ server: Endpoint) async {
        hiddenAgentIDs.insert(server.agentID)
        EndpointStore.shared.hiddenAgentIDs = hiddenAgentIDs
        let wasActive = endpoint.id == server.id
        EndpointStore.shared.remove(server)
        servers = EndpointStore.shared.servers
        serverStatus[server.id] = nil
        guard wasActive else { return }
        close()
        endpoint = EndpointStore.shared.endpoint
        sessions = []
        agents = []
        models = []
        error = nil
        if endpoint.ready { await refresh() }
    }

    func refreshServerStatuses() async {
        let current = servers
        guard !current.isEmpty else { return }
        for server in current { serverStatus[server.id] = .checking }
        let results = await withTaskGroup(of: (String, ServerAvailability).self) { group in
            for server in current {
                group.addTask { (server.id, await Self.probe(server)) }
            }
            var map: [String: ServerAvailability] = [:]
            for await (id, status) in group { map[id] = status }
            return map
        }
        let ids = Set(servers.map(\.id))
        serverStatus = results.filter { ids.contains($0.key) }
    }

    private static func probe(_ server: Endpoint) async -> ServerAvailability {
        guard server.ready else { return .offline }
        do {
            let health = try await ComagicClient(endpoint: server).healthInfo()
            return health.healthy == true ? .online(version: health.version ?? "") : .offline
        } catch {
            return .offline
        }
    }

    func refresh() async {
        guard let client else { return }
        loading = true
        defer { loading = false }
        do {
            sessions = try await client.listSessions()
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
        await loadOptions()
    }

    private var optionsDirectory: String {
        let current = currentDirectory.trimmingCharacters(in: .whitespaces)
        return current.isEmpty ? endpoint.directory.trimmingCharacters(in: .whitespaces) : current
    }

    func loadOptions() async {
        guard let client else { return }
        let directory = optionsDirectory
        guard !directory.isEmpty else { return }
        if let loaded = try? await client.listAgents(directory: directory) {
            agents = loaded.filter { ($0.mode ?? "") == "primary" && !($0.hidden ?? false) }
        }
        if let loaded = try? await client.listModels(directory: directory) {
            models = loaded
        }
    }

    func selectAgent(_ agent: String) async {
        guard let client, let session = current, (session.agent ?? "") != agent else { return }
        if var updated = current {
            updated.agent = agent
            current = updated
        }
        do {
            try await client.switchAgent(session.id, agent: agent)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func selectModel(_ model: ModelRef) async {
        guard let client, let session = current else { return }
        if var updated = current {
            updated.model = model
            current = updated
        }
        do {
            try await client.switchModel(session.id, model: model)
        } catch {
            self.error = error.localizedDescription
        }
    }

    func delete(_ session: SessionRef) async {
        guard let client else { return }
        loading = true
        defer { loading = false }
        do {
            try await client.deleteSession(session.id)
            HistoryCache.shared.remove(session.id)
            if current?.id == session.id { close() }
            sessions.removeAll { $0.id == session.id }
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    func open(_ session: SessionRef) async {
        current = session
        currentDirectory = session.location?.directory ?? endpoint.directory
        let cached = HistoryCache.shared.load(session.id)
        lines = cached?.lines ?? []
        historyHasMore = cached?.hasMore ?? false
        oldestMessageID = cached?.oldestID
        historyLimit = max(cached?.limit ?? 20, 20)
        loadingOlder = false
        error = nil
        busy = false
        let stale = cached == nil || Date().timeIntervalSince(cached!.savedAt) > HistoryCache.ttl
        await loadHistory(force: stale)
        await loadOptions()
        startStream(session.id)
    }

    func close() {
        if let session = current {
            HistoryCache.shared.save(
                session.id,
                lines: lines,
                hasMore: historyHasMore,
                oldestID: oldestMessageID,
                limit: historyLimit
            )
        }
        streamTask?.cancel()
        streamTask = nil
        current = nil
        lines = []
        busy = false
        reconnecting = false
    }

    func openDirectoryPicker() async {
        guard let client else { return }
        directoryPicker = DirectoryPickerState(
            current: endpoint.directory,
            home: "",
            entries: [],
            loading: true,
            error: nil
        )
        do {
            let paths: PathInfo
            if let primary = try? await client.paths(directory: endpoint.directory) {
                paths = primary
            } else {
                paths = try await client.paths(directory: "")
            }
            let directory = paths.directory?.isEmpty == false
                ? (paths.directory ?? "")
                : (paths.home ?? "")
            let entries = directory.isEmpty
                ? []
                : try await client.listEntries(directory: directory, path: directory)
            directoryPicker = DirectoryPickerState(
                current: directory,
                home: paths.home ?? "",
                entries: entries,
                loading: false,
                error: nil
            )
        } catch {
            directoryPicker?.loading = false
            directoryPicker?.error = error.localizedDescription
        }
    }

    func loadDirectory(_ path: String) async {
        guard let client, var picker = directoryPicker else { return }
        let target = path.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !target.isEmpty else { return }
        picker.current = target
        picker.loading = true
        picker.error = nil
        directoryPicker = picker
        do {
            let entries = try await client.listEntries(directory: target, path: target)
            picker.entries = entries
            picker.loading = false
            directoryPicker = picker
        } catch {
            picker.loading = false
            picker.error = error.localizedDescription
            directoryPicker = picker
        }
    }

    func closeDirectoryPicker() {
        directoryPicker = nil
    }

    func confirmDirectory(_ path: String) async {
        guard !path.isEmpty else { return }
        endpoint.directory = path
        endpoint = EndpointStore.shared.upsert(endpoint)
        servers = EndpointStore.shared.servers
        directoryPicker = nil
        await newSession(directory: path)
    }

    private func defaultModel() -> ModelRef? {
        let deepseek = models.first { model in
            [model.id, model.providerID ?? "", model.name ?? ""].contains {
                $0.localizedCaseInsensitiveContains("deepseek")
            }
        }
        return deepseek?.ref() ?? current?.model ?? models.first?.ref()
    }

    func newSession(directory: String) async {
        guard let client else { return }
        guard let model = defaultModel() ?? sessions.compactMap({ $0.model }).first else {
            error = "没有可用模型：先打开一个已有会话"
            return
        }
        let directory = directory.trimmingCharacters(in: .whitespaces)
        guard !directory.isEmpty else {
            error = "请选择工作目录"
            return
        }
        loading = true
        defer { loading = false }
        do {
            let session = try await client.createSession(directory: directory, model: model, agent: current?.agent ?? "build")
            await open(session)
            await refresh()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func loadHistory(force: Bool = true) async {
        guard let client, let session = current else { return }
        if !force,
           let cached = HistoryCache.shared.load(session.id),
           Date().timeIntervalSince(cached.savedAt) <= HistoryCache.ttl {
            return
        }
        let limit = max(historyLimit, 20)
        do {
            let page = try await client.history(session.id, limit: limit)
            HistoryCache.shared.save(
                session.id,
                lines: page.lines,
                hasMore: page.hasMore,
                oldestID: page.oldestID,
                limit: limit
            )
            if current?.id == session.id {
                lines = page.lines
                historyHasMore = page.hasMore
                oldestMessageID = page.oldestID
                loadingOlder = false
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    func loadOlder() async {
        guard historyHasMore, !loadingOlder,
              let client, let session = current,
              let before = oldestMessageID
        else { return }
        loadingOlder = true
        do {
            let page = try await client.history(session.id, limit: 20, before: before)
            let merged = page.lines + lines
            let oldest = page.oldestID ?? oldestMessageID
            historyLimit = historyLimit + 20
            HistoryCache.shared.save(
                session.id,
                lines: merged,
                hasMore: page.hasMore,
                oldestID: oldest,
                limit: historyLimit
            )
            guard current?.id == session.id else {
                loadingOlder = false
                return
            }
            lines = merged
            historyHasMore = page.hasMore
            oldestMessageID = oldest
            loadingOlder = false
        } catch {
            loadingOlder = false
            self.error = error.localizedDescription
        }
    }

    func send(_ raw: String) async {
        guard let client, let session = current else { return }
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }

        lines.append(ChatLine(role: "user", text: text))
        lines.append(ChatLine(role: "assistant"))
        busy = true
        error = nil

        do {
            try await client.prompt(session.id, text: text, agent: session.agent, model: session.model)
        } catch {
            await handlePromptError(error)
        }
    }

    func sendImage(_ data: Data, caption: String) async {
        guard let client, let session = current else { return }
        guard let image = Self.prepareImage(data) else {
            error = "无法读取图片"
            return
        }
        let text = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        lines.append(ChatLine(role: "user", text: text, image: image.dataURL))
        lines.append(ChatLine(role: "assistant"))
        busy = true
        error = nil
        do {
            try await client.prompt(session.id, text: text, agent: session.agent, model: session.model, image: image)
        } catch {
            await handlePromptError(error)
        }
    }

    private func handlePromptError(_ error: Error) async {
        if case ClientError.http(409, _) = error {
            busy = true
            await loadHistory()
            self.error = "agent 还在执行上一条消息，等它结束或中断后再发"
        } else {
            busy = false
            self.error = error.localizedDescription
        }
    }

    private static func prepareImage(_ data: Data, maxEdge: CGFloat = 1600) -> ImageAttachment? {
        guard let source = UIImage(data: data) else { return nil }
        let edge = max(source.size.width, source.size.height)
        let ratio = edge > maxEdge ? maxEdge / edge : 1
        let size = CGSize(width: source.size.width * ratio, height: source.size.height * ratio)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)
        let jpeg = renderer.jpegData(withCompressionQuality: 0.82) { _ in
            source.draw(in: CGRect(origin: .zero, size: size))
        }
        return ImageAttachment(
            mime: "image/jpeg",
            filename: "image-\(Int(Date().timeIntervalSince1970)).jpg",
            dataURL: "data:image/jpeg;base64," + jpeg.base64EncodedString()
        )
    }

    func interrupt() async {
        guard let client, let session = current else { return }
        do {
            try await client.interrupt(session.id)
        } catch {
            self.error = error.localizedDescription
        }
    }

    #if DEBUG
    func applyDebugArguments() async {
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "-pairURL"), index + 1 < args.count,
           let endpoint = PairPayload.parse(args[index + 1]), endpoint.ready {
            connect(endpoint)
            await refresh()
        }
        if let index = args.firstIndex(of: "-sessionID"), index + 1 < args.count,
           let session = sessions.first(where: { $0.id == args[index + 1] }) {
            await open(session)
        }
    }
    #endif

    private func startStream(_ sessionID: String) {
        streamTask?.cancel()
        guard let client else { return }
        streamTask = Task { [weak self] in
            var backoff: UInt64 = 1_000_000_000
            while !Task.isCancelled {
                self?.reconnecting = false
                var received = false
                for await event in client.events() {
                    if Task.isCancelled { return }
                    if !received {
                        received = true
                        self?.reconnecting = false
                        backoff = 1_000_000_000
                    }
                    guard event.sessionID == nil || event.sessionID == sessionID else { continue }
                    self?.handle(event)
                }
                if Task.isCancelled { return }
                self?.reconnecting = true
                try? await Task.sleep(nanoseconds: backoff)
                backoff = min(backoff * 2, 15_000_000_000)
                self?.busy = false
                await self?.loadHistory()
            }
        }
    }

    private func handle(_ event: AgentEvent) {
        switch event.type {
        case "message.part.delta", "session.text.delta":
            guard let delta = event.delta else { return }
            appendAssistant(delta)
        case "session.tool.called":
            let line = ChatLine(
                role: "tool",
                tool: event.toolName ?? "tool",
                toolID: event.toolID,
                toolTitle: event.toolTitle,
                toolOutput: event.toolOutput
            )
            if let id = line.toolID, let index = lines.lastIndex(where: { $0.role == "tool" && $0.toolID == id }) {
                lines[index].toolTitle = line.toolTitle ?? lines[index].toolTitle
                lines[index].toolOutput = line.toolOutput ?? lines[index].toolOutput
            } else {
                lines.append(line)
            }
        case "session.tool.failed", "session.error", "message.error":
            lines.append(ChatLine(role: "error", text: event.errorText ?? event.type))
            busy = false
        case "session.idle":
            busy = false
            Task { await loadHistory() }

        case "session.execution.succeeded", "session.execution.failed":
            busy = false
        case "permission.asked":
            pendingPermission = event.requestTitle ?? "需要确认工具调用"
        default:
            break
        }
    }

    private func appendAssistant(_ delta: String) {
        if let last = lines.last, last.role == "assistant" {
            lines[lines.count - 1].text += delta
        } else {
            lines.append(ChatLine(role: "assistant", text: delta))
        }
    }
}

enum PairPayload {
    static func parse(_ raw: String) -> Endpoint? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: text) else { return nil }
        let items = components.queryItems ?? []
        func value(_ names: String...) -> String? {
            for name in names {
                if let item = items.first(where: { $0.name == name }), let v = item.value { return v }
            }
            return nil
        }

        let baseURL: String?
        if components.scheme == "comagic" {
            baseURL = value("url")
        } else if components.scheme == "http" || components.scheme == "https" {
            var url = "\(components.scheme!)://\(components.host ?? "")"
            if let port = components.port { url += ":\(port)" }
            baseURL = url
        } else {
            baseURL = nil
        }

        guard var url = baseURL, !url.isEmpty else { return nil }
        while url.hasSuffix("/") { url.removeLast() }

        return Endpoint(
            baseURL: url,
            username: value("user", "username") ?? Credentials.username,
            password: value("pass", "password") ?? Credentials.password,
            directory: value("dir", "directory") ?? ""
        )
    }
}
