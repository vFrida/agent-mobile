import Foundation
import UIKit

@MainActor
final class AppModel: ObservableObject {
    @Published var endpoint: Endpoint
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
        lines = []
        error = nil
        busy = false
        await loadHistory()
        await loadOptions()
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
        EndpointStore.shared.endpoint = endpoint
        directoryPicker = nil
        await newSession(directory: path)
    }

    func newSession(directory: String) async {
        guard let client else { return }
        guard let model = current?.model ?? models.first?.ref() ?? sessions.compactMap({ $0.model }).first else {
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
            await loadHistory()
            busy = false
        } catch {
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
