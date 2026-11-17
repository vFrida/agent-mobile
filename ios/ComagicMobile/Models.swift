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

struct MessagePart: Codable {
    var type: String?
    var text: String?
    var tool: String?
    var mime: String?
    var filename: String?
    var url: String?
}

struct MessageDto: Codable {
    var info: MessageInfo
    var parts: [MessagePart]?
}

struct ChatLine: Identifiable, Hashable {
    let id = UUID()
    var role: String
    var text: String = ""
    var tool: String?
    var image: String?
}

struct ImageAttachment {
    let mime: String
    let filename: String
    let dataURL: String
}

struct Endpoint: Codable {
    var baseURL: String = ""
    var username: String = "comagic"
    var password: String = ""
    var directory: String = ""

    var ready: Bool { !baseURL.isEmpty && !password.isEmpty }
}
