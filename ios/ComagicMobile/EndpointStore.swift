import Foundation
import Security

final class EndpointStore {
    static let shared = EndpointStore()

    private let defaults = UserDefaults.standard
    private let baseKey = "comagic.baseURL"
    private let userKey = "comagic.username"
    private let dirKey = "comagic.directory"
    private let passwordKey = "comagic.password"

    var endpoint: Endpoint {
        get {
            Endpoint(
                baseURL: defaults.string(forKey: baseKey) ?? "",
                username: defaults.string(forKey: userKey) ?? "comagic",
                password: Keychain.read(passwordKey) ?? "",
                directory: defaults.string(forKey: dirKey) ?? ""
            )
        }
        set {
            defaults.set(newValue.baseURL, forKey: baseKey)
            defaults.set(newValue.username, forKey: userKey)
            defaults.set(newValue.directory, forKey: dirKey)
            Keychain.write(passwordKey, value: newValue.password)
        }
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
