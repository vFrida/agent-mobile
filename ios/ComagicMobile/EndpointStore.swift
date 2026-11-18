import Foundation
import Security

final class EndpointStore {
    static let shared = EndpointStore()

    private let defaults = UserDefaults.standard
    private let serversKey = "comagic.servers"
    private let activeKey = "comagic.activeServer"
    private let legacyBaseKey = "comagic.baseURL"
    private let legacyUserKey = "comagic.username"
    private let legacyDirKey = "comagic.directory"
    private let legacyPasswordKey = "comagic.password"

    private var migrated = false

    var activeID: String {
        defaults.string(forKey: activeKey) ?? ""
    }

    var registryURL: String {
        get { defaults.string(forKey: "comagic.registryURL") ?? "" }
        set { defaults.set(newValue.trimmingCharacters(in: .whitespaces), forKey: "comagic.registryURL") }
    }

    var servers: [Endpoint] {
        migrateIfNeeded()
        return storedServers()
    }

    var endpoint: Endpoint {
        let all = servers
        return all.first { $0.id == activeID } ?? all.first ?? Endpoint()
    }

    var legacyEndpoint: Endpoint {
        Endpoint(
            id: UUID().uuidString,
            baseURL: defaults.string(forKey: legacyBaseKey) ?? "",
            username: defaults.string(forKey: legacyUserKey) ?? Credentials.username,
            password: Keychain.read(legacyPasswordKey) ?? "",
            directory: defaults.string(forKey: legacyDirKey) ?? ""
        )
    }

    @discardableResult
    func upsert(_ endpoint: Endpoint) -> Endpoint {
        var saved = endpoint
        if saved.id.isEmpty { saved.id = UUID().uuidString }
        var list = servers
        if let index = list.firstIndex(where: { $0.id == saved.id }) {
            list[index] = saved
            list.removeAll {
                $0.id != saved.id && $0.baseURL.normalizedURL == saved.baseURL.normalizedURL
            }
        } else if let index = list.firstIndex(where: { existing in
            existing.baseURL.normalizedURL == saved.baseURL.normalizedURL
        }) {
            saved.id = list[index].id
            list[index] = saved
        } else {
            list.append(saved)
        }
        persist(list, activeID: saved.id)
        return saved
    }

    func activate(_ endpoint: Endpoint) {
        defaults.set(endpoint.id, forKey: activeKey)
    }

    func remove(_ endpoint: Endpoint) {
        var list = servers
        list.removeAll { $0.id == endpoint.id }
        let active = activeID == endpoint.id ? (list.first?.id ?? "") : activeID
        persist(list, activeID: active)
    }

    private func storedServers() -> [Endpoint] {
        guard let raw = Keychain.read(serversKey),
              !raw.isEmpty,
              let data = Data(base64Encoded: raw),
              let list = try? JSONDecoder().decode([Endpoint].self, from: data)
        else { return [] }
        return list
    }

    private func persist(_ list: [Endpoint], activeID: String) {
        let encoded = (try? JSONEncoder().encode(list))?.base64EncodedString() ?? ""
        Keychain.write(serversKey, value: encoded)
        defaults.set(activeID, forKey: activeKey)
    }

    private func migrateIfNeeded() {
        guard !migrated else { return }
        migrated = true
        guard storedServers().isEmpty else { return }
        let legacy = legacyEndpoint
        guard !legacy.baseURL.isEmpty else { return }
        persist([legacy], activeID: legacy.id)
        defaults.removeObject(forKey: legacyBaseKey)
        defaults.removeObject(forKey: legacyUserKey)
        defaults.removeObject(forKey: legacyDirKey)
    }
}

extension String {
    var normalizedURL: String {
        var value = trimmingCharacters(in: .whitespaces).lowercased()
        while value.hasSuffix("/") { value.removeLast() }
        return value
    }
}

enum Keychain {
    private static let service = "net.pdd.comagic.mobile"

    static func read(_ key: String) -> String? {
        var query = baseQuery(key)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func write(_ key: String, value: String) {
        let data = Data(value.utf8)
        var query = baseQuery(key)
        if read(key) != nil {
            SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
            return
        }
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(query as CFDictionary, nil)
    }

    private static func baseQuery(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key
        ]
    }
}
