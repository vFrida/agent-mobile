import Foundation

struct ModelRef: Codable, Hashable {
    let id: String
    let providerID: String
    var variant: String?
}

struct SessionRef: Codable, Identifiable, Hashable {
    let id: String
    var title: String?
    var agent: String?
    var model: ModelRef?
}

struct SessionListResponse: Codable {
    let data: [SessionRef]
}

struct SessionCreateResponse: Codable {
    let data: SessionRef?
}

struct MessageInfo: Codable {
    var id: String?
    var role: String?
}

struct MessagePart: Codable {
    var type: String?
    var text: String?
    var tool: String?
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
}

struct Endpoint: Codable {
    var baseURL: String = ""
    var username: String = "comagic"
    var password: String = ""
    var directory: String = ""

    var ready: Bool { !baseURL.isEmpty && !password.isEmpty }
}
