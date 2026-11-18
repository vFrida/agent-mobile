import Foundation

struct ModelRef: Codable, Hashable {
    let id: String
    let providerID: String
    var variant: String?
}

struct LocationInfo: Codable, Hashable {
    var directory: String?
    var subpath: String?
}

struct SessionTime: Codable, Hashable {
    var created: Int64?
    var updated: Int64?
}

struct SessionRef: Codable, Identifiable, Hashable {
    let id: String
    var title: String?
    var agent: String?
    var model: ModelRef?
    var location: LocationInfo?
    var time: SessionTime?
}

struct SessionListResponse: Codable {
    let data: [SessionRef]
}

struct SessionCreateResponse: Codable {
    let data: SessionRef?
}

struct AgentInfo: Codable, Identifiable, Hashable {
    let id: String
    var name: String?
    var description: String?
    var mode: String?
    var hidden: Bool?
    var model: ModelRef?
}

struct PathInfo: Codable {
    var home: String?
    var state: String?
    var config: String?
    var worktree: String?
    var directory: String?
}

struct FsEntry: Codable, Identifiable, Hashable {
    var path: String
    var type: String?
    var id: String { path }
}

struct FsListResponse: Codable {
    let data: [FsEntry]
}

struct DirectoryPickerState {
    var current: String
    var home: String = ""
    var entries: [FsEntry]
    var loading: Bool
    var error: String?
}

struct AgentListResponse: Codable {
    let data: [AgentInfo]
}

struct ModelInfo: Codable, Hashable {
    let id: String
    var providerID: String?
    var name: String?
    var status: String?
    var enabled: Bool?

    func ref(variant: String? = nil) -> ModelRef {
        ModelRef(id: id, providerID: providerID ?? "", variant: variant)
    }
}

struct ProviderInfo: Codable, Hashable {
    let id: String
    var name: String?
    var models: [String: ModelInfo]?
}

struct ProvidersResponse: Codable {
    let providers: [ProviderInfo]
}

struct MessageInfo: Codable {
    var id: String?
    var role: String?
}

struct PartState: Codable {
    var status: String?
    var title: String?
    var output: String?

    private enum CodingKeys: String, CodingKey {
        case status, title, output
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        status = try? container.decodeIfPresent(String.self, forKey: .status)
        title = try? container.decodeIfPresent(String.self, forKey: .title)
        output = try? container.decodeIfPresent(String.self, forKey: .output)
    }
}

struct MessagePart: Codable {
    var type: String?
    var text: String?
    var tool: String?
    var mime: String?
    var filename: String?
    var url: String?
    var callID: String?
    var state: PartState?

    var toolTitle: String? {
        let title = state?.title?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return title.isEmpty ? nil : title
    }

    var toolOutput: String? {
        let output = state?.output?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return output.isEmpty ? nil : String(output.prefix(4000))
    }
}

struct MessageDto: Codable {
    var info: MessageInfo
    var parts: [MessagePart]?
}

struct RegistryServer: Codable, Identifiable, Hashable {
    var id: String
    var name: String?
    var url: String?
    var directory: String?
    var version: String?
    var lastSeen: Int64?
    var online: Bool?
}

struct RegistryListResponse: Codable {
    let data: [RegistryServer]
}

struct HistoryPage {
    var lines: [ChatLine] = []
    var oldestID: String?
    var hasMore: Bool = false
}

struct ChatLine: Identifiable, Hashable, Codable {
    let id = UUID()
    var role: String
    var text: String = ""
    var tool: String?
    var image: String?
    var toolID: String?
    var toolTitle: String?
    var toolOutput: String?
}

struct ImageAttachment {
    let mime: String
    let filename: String
    let dataURL: String
}

enum Credentials {
    static let username = "comagic"
    static let password = "YFl4cVaQ8smozi4I"
}

enum RegistryDefaults {
    static let url = "http://192.168.100.136:4908"
}

struct RegistryAccount: Codable, Equatable {
    var baseURL: String = RegistryDefaults.url
    var username: String = Credentials.username
    var password: String = Credentials.password
}

struct Endpoint: Codable, Identifiable, Hashable {
    var id: String = UUID().uuidString
    var name: String = ""
    var baseURL: String = ""
    var username: String = Credentials.username
    var password: String = Credentials.password
    var directory: String = ""

    var ready: Bool { !baseURL.isEmpty && !password.isEmpty }

    var agentID: String {
        var url = baseURL
        while url.hasSuffix("/") { url.removeLast() }
        guard url.contains("/s/"), let id = url.components(separatedBy: "/s/").last, !id.isEmpty else {
            return id
        }
        return id
    }

    var label: String {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard trimmed.isEmpty else { return trimmed }
        var host = baseURL.trimmingCharacters(in: .whitespaces)
        for prefix in ["https://", "http://"] where host.hasPrefix(prefix) {
            host.removeFirst(prefix.count)
        }
        while host.hasSuffix("/") { host.removeLast() }
        return host.isEmpty ? "未命名服务器" : host
    }
}

enum ServerAvailability: Equatable {
    case checking
    case online(version: String)
    case offline

    var isOnline: Bool {
        if case .online = self { return true }
        return false
    }

    var label: String {
        switch self {
        case .checking:
            return "检测中…"
        case .online(let version):
            return version.isEmpty ? "服务可用" : "服务可用 · \(version)"
        case .offline:
            return "服务不可用"
        }
    }
}
