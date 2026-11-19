import Foundation

struct CachedHistory: Codable {
    var savedAt: Date
    var lines: [ChatLine]
    var hasMore: Bool = false
    var oldestID: String?
    var limit: Int = 20
}

final class HistoryCache {
    static let shared = HistoryCache()
    static let ttl: TimeInterval = 5 * 60

    private let dir: URL

    init() {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        dir = base.appendingPathComponent("history", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    func load(_ sessionID: String) -> CachedHistory? {
        guard let data = try? Data(contentsOf: url(sessionID)) else { return nil }
        return try? JSONDecoder().decode(CachedHistory.self, from: data)
    }

    func save(
        _ sessionID: String,
        lines: [ChatLine],
        hasMore: Bool = false,
        oldestID: String? = nil,
        limit: Int = 20
    ) {
        guard !sessionID.isEmpty, !lines.isEmpty else { return }
        let payload = CachedHistory(
            savedAt: Date(),
            lines: lines,
            hasMore: hasMore,
            oldestID: oldestID,
            limit: limit
        )
        guard let data = try? JSONEncoder().encode(payload) else { return }
        try? data.write(to: url(sessionID), options: .atomic)
    }

    func remove(_ sessionID: String) {
        try? FileManager.default.removeItem(at: url(sessionID))
    }

    private func url(_ sessionID: String) -> URL {
        let name = sessionID.replacingOccurrences(
            of: "[^A-Za-z0-9_.-]",
            with: "_",
            options: .regularExpression
        )
        return dir.appendingPathComponent("\(name).json")
    }
}
