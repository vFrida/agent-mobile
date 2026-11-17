import Foundation

@MainActor
final class AppModel: ObservableObject {
    @Published var endpoint: Endpoint
    @Published var sessions: [SessionRef] = []
    @Published var loading = false
    @Published var error: String?
    @Published var current: SessionRef?
    @Published var lines: [ChatLine] = []
    @Published var busy = false
    @Published var reconnecting = false
    @Published var pendingPermission: String?

    private var streamTask: Task<Void, Never>?

    init() {
        endpoint = EndpointStore.shared.endpoint
    }

    private var client: ComagicClient? {
        endpoint.ready ? ComagicClient(endpoint: endpoint) : nil
    }

    func connect(_ new: Endpoint) {
        endpoint = new
        EndpointStore.shared.endpoint = new
        error = nil
        Task { await refresh() }
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
    }

    func open(_ session: SessionRef) async {
        current = session
        lines = []
        error = nil
        busy = false
        await loadHistory()
        startStream(session.id)
    }

    func close() {
        streamTask?.cancel()
        streamTask = nil
        current = nil
        lines = []
        busy = false
        reconnecting = false
    }

    func newSession() async {
        guard let client else { return }
        guard let model = sessions.compactMap({ $0.model }).first else {
            error = "没有可用模型：先打开一个已有会话"
            return
        }
        let directory = endpoint.directory.trimmingCharacters(in: .whitespaces)
        guard !directory.isEmpty else {
            error = "请先在设置里填写工作目录，例如 /Users/txc"
            return
        }
        loading = true
        defer { loading = false }
        do {
            let session = try await client.createSession(directory: directory, model: model, agent: "build")
            await open(session)
            await refresh()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func loadHistory() async {
        guard let client, let session = current else { return }
        do {
            let history = try await client.history(session.id)
            if current?.id == session.id { lines = history }
        } catch {
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
            await loadHistory()
            busy = false
        } catch {
            busy = false
            self.error = error.localizedDescription
        }
    }

    func interrupt() async {
        guard let client, let session = current else { return }
        do {
            try await client.interrupt(session.id)
        } catch {
            self.error = error.localizedDescription
        }
    }

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
            lines.append(ChatLine(role: "tool", tool: event.toolName ?? "tool"))
        case "session.tool.failed", "session.error", "message.error":
            lines.append(ChatLine(role: "error", text: event.errorText ?? event.type))
            busy = false
        case "session.idle", "session.execution.succeeded", "session.execution.failed":
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
            username: value("user", "username") ?? "comagic",
            password: value("pass", "password") ?? "",
            directory: value("dir", "directory") ?? ""
        )
    }
}
