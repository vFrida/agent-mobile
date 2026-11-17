import Foundation

struct AgentEvent {
    let id: String
    let type: String
    let payload: [String: Any]

    init?(json: String) {
        guard let data = json.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = object["type"] as? String
        else { return nil }

        self.type = type
        self.id = object["id"] as? String ?? ""
        self.payload = (object["properties"] as? [String: Any])
            ?? (object["data"] as? [String: Any])
            ?? [:]
    }

    var sessionID: String? { payload["sessionID"] as? String }

    var delta: String? {
        if let delta = payload["delta"] as? String { return delta }
        if let part = payload["part"] as? [String: Any], let text = part["text"] as? String { return text }
        return payload["text"] as? String
    }

    var toolName: String? {
        if let tool = payload["tool"] as? [String: Any], let name = tool["name"] as? String { return name }
        return payload["toolName"] as? String ?? payload["name"] as? String
    }

    var errorText: String? {
        if let error = payload["error"] as? [String: Any], let message = error["message"] as? String {
            return message
        }
        return payload["message"] as? String
    }

    var requestID: String? {
        payload["requestID"] as? String ?? payload["id"] as? String ?? payload["permissionID"] as? String
    }

    var requestTitle: String? {
        if let permission = payload["permission"] as? [String: Any], let title = permission["title"] as? String {
            return title
        }
        return payload["title"] as? String ?? payload["permission"] as? String
    }
}

struct ComagicClient {
    let endpoint: Endpoint

    private var base: String {
        var value = endpoint.baseURL
        while value.hasSuffix("/") { value.removeLast() }
        return value
    }

    private func request(_ path: String, query: [URLQueryItem] = []) -> URLRequest {
        var components = URLComponents(string: base + path)!
        if !query.isEmpty { components.queryItems = query }
        var request = URLRequest(url: components.url!, timeoutInterval: 60)
        let token = Data("\(endpoint.username):\(endpoint.password)".utf8).base64EncodedString()
        request.setValue("Basic \(token)", forHTTPHeaderField: "Authorization")
        return request
    }

    private func jsonRequest(_ path: String, method: String, body: [String: Any]) -> URLRequest {
        var request = request(path)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)
        return request
    }

    private func send(_ request: URLRequest) async throws -> Data {
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ClientError.badResponse }
        guard (200..<300).contains(http.statusCode) else {
            let text = String(data: data, encoding: .utf8)?.prefix(300) ?? ""
            throw ClientError.http(http.statusCode, String(text))
        }
        return data
    }

    func health() async throws -> Bool {
        let data = try await send(request("/global/health"))
        struct Health: Codable { var healthy: Bool? }
        return (try? JSONDecoder().decode(Health.self, from: data))?.healthy ?? false
    }

    func listSessions() async throws -> [SessionRef] {
        let data = try await send(request("/api/session?limit=50&order=desc"))
        return try JSONDecoder().decode(SessionListResponse.self, from: data).data
    }

    func createSession(directory: String, model: ModelRef, agent: String) async throws -> SessionRef {
        var modelBody: [String: Any] = ["id": model.id, "providerID": model.providerID]
        if let variant = model.variant { modelBody["variant"] = variant }
        let body: [String: Any] = [
            "agent": agent,
            "model": modelBody,
            "location": ["directory": directory]
        ]
        let data = try await send(jsonRequest("/api/session", method: "POST", body: body))
        guard let session = try JSONDecoder().decode(SessionCreateResponse.self, from: data).data else {
            throw ClientError.badResponse
        }
        return session
    }

    func history(_ sessionID: String) async throws -> [ChatLine] {
        let data = try await send(request("/session/\(sessionID)/message"))
        let messages = try JSONDecoder().decode([MessageDto].self, from: data)
        return messages.flatMap { message -> [ChatLine] in
            let texts = (message.parts ?? [])
                .filter { $0.type == "text" }
                .compactMap { $0.text }
                .joined()
            let images = (message.parts ?? [])
                .filter { $0.type == "file" && $0.mime?.hasPrefix("image/") == true }
                .compactMap { $0.url }
            switch message.info.role {
            case "user":
                var lines = images.map { ChatLine(role: "user", image: $0) }
                if !texts.isEmpty { lines.append(ChatLine(role: "user", text: texts)) }
                return lines
            case "assistant":
                if !texts.isEmpty {
                    return [ChatLine(role: "assistant", text: texts)] + images.map { ChatLine(role: "assistant", image: $0) }
                }
                if let tool = message.parts?.last(where: { $0.type == "tool" })?.tool {
                    return [ChatLine(role: "tool", tool: tool)]
                }
                return images.map { ChatLine(role: "assistant", image: $0) }
            default:
                return []
            }
        }
    }

    func prompt(
        _ sessionID: String,
        text: String,
        agent: String?,
        model: ModelRef?,
        image: ImageAttachment? = nil
    ) async throws {
        var parts: [[String: Any]] = []
        if let image {
            parts.append([
                "type": "file",
                "mime": image.mime,
                "filename": image.filename,
                "url": image.dataURL
            ])
        }
        if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            parts.append(["type": "text", "text": text])
        }
        var body: [String: Any] = ["parts": parts]
        if let agent { body["agent"] = agent }
        if let model {
            var modelBody: [String: Any] = ["providerID": model.providerID, "modelID": model.id]
            if let variant = model.variant { modelBody["variant"] = variant }
            body["model"] = modelBody
        }
        _ = try await send(jsonRequest("/session/\(sessionID)/message", method: "POST", body: body))
    }

    func paths(directory: String) async throws -> PathInfo {
        let data = try await send(
            request("/path", query: [URLQueryItem(name: "directory", value: directory)])
        )
        return try JSONDecoder().decode(PathInfo.self, from: data)
    }

    func listEntries(directory: String, path: String) async throws -> [FsEntry] {
        let data = try await send(
            request(
                "/api/fs/list",
                query: [
                    URLQueryItem(name: "location[directory]", value: directory),
                    URLQueryItem(name: "path", value: path)
                ]
            )
        )
        let entries = try JSONDecoder().decode(FsListResponse.self, from: data).data
        let base = directory.hasSuffix("/") && directory != "/" ? String(directory.dropLast()) : directory
        return entries.map { entry in
            var entry = entry
            if !entry.path.hasPrefix("/") {
                entry.path = base == "/" ? "/" + entry.path : base + "/" + entry.path
            }
            while entry.path.count > 1 && entry.path.hasSuffix("/") { entry.path.removeLast() }
            return entry
        }
    }

    func listAgents(directory: String) async throws -> [AgentInfo] {
        let data = try await send(
            request("/api/agent", query: [URLQueryItem(name: "location[directory]", value: directory)])
        )
        return try JSONDecoder().decode(AgentListResponse.self, from: data).data
    }

    func listModels(directory: String) async throws -> [ModelInfo] {
        let data = try await send(
            request("/config/providers", query: [URLQueryItem(name: "directory", value: directory)])
        )
        let response = try JSONDecoder().decode(ProvidersResponse.self, from: data)
        var seen = Set<String>()
        var models: [ModelInfo] = []
        for provider in response.providers {
            for var model in (provider.models ?? [:]).values {
                if (model.providerID ?? "").isEmpty { model.providerID = provider.id }
                let key = "\(model.providerID ?? "")/\(model.id)"
                guard !seen.contains(key) else { continue }
                seen.insert(key)
                if model.enabled ?? true { models.append(model) }
            }
        }
        return models
    }

    func switchAgent(_ sessionID: String, agent: String) async throws {
        _ = try await send(
            jsonRequest("/api/session/\(sessionID)/agent", method: "POST", body: ["agent": agent])
        )
    }

    func switchModel(_ sessionID: String, model: ModelRef) async throws {
        var modelBody: [String: Any] = ["id": model.id, "providerID": model.providerID]
        if let variant = model.variant { modelBody["variant"] = variant }
        _ = try await send(
            jsonRequest("/api/session/\(sessionID)/model", method: "POST", body: ["model": modelBody])
        )
    }

    func deleteSession(_ sessionID: String) async throws {
        var request = request("/api/session/\(sessionID)")
        request.httpMethod = "DELETE"
        _ = try await send(request)
    }

    func interrupt(_ sessionID: String) async throws {
        _ = try await send(jsonRequest("/session/\(sessionID)/abort", method: "POST", body: [:]))
    }

    func events() -> AsyncStream<AgentEvent> {
        AsyncStream { continuation in
            let task = Task {
                do {
                    let (bytes, response) = try await URLSession.shared.bytes(for: request("/event"))
                    guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                        continuation.finish()
                        return
                    }
                    for try await line in bytes.lines {
                        guard line.hasPrefix("data:") else { continue }
                        let json = String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)
                        if let event = AgentEvent(json: json) {
                            continuation.yield(event)
                        }
                    }
                } catch {
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }
}

enum ClientError: LocalizedError {
    case badResponse
    case http(Int, String)

    var errorDescription: String? {
        switch self {
        case .badResponse:
            return "响应格式不对"
        case let .http(code, body):
            return "HTTP \(code) \(body)"
        }
    }
}
