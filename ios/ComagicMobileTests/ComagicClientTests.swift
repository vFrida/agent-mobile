import XCTest
@testable import ComagicMobile

final class ComagicClientTests: XCTestCase {

    private func env() -> (String, String)? {
        let path = "/tmp/comagic_test_env.json"
        guard let data = FileManager.default.contents(atPath: path),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: String],
              let url = object["url"], let pass = object["pass"]
        else { return nil }
        return (url, pass)
    }

    func testHealthAndSessions() async throws {
        guard let (url, pass) = env() else { throw XCTSkip("缺少 /tmp/comagic_test_env.json") }
        let client = ComagicClient(endpoint: Endpoint(baseURL: url, password: pass))
        let healthy = try await client.health()
        XCTAssertTrue(healthy)
        let sessions = try await client.listSessions()
        XCTAssertFalse(sessions.isEmpty, "会话列表为空")
    }

    func testParsePairPayload() {
        let endpoint = PairPayload.parse(
            "comagic://pair?url=http%3A%2F%2F192.168.1.10%3A4907&user=comagic&pass=abc-123&dir=%2FUsers%2Ftxc"
        )
        XCTAssertEqual(endpoint?.baseURL, "http://192.168.1.10:4907")
        XCTAssertEqual(endpoint?.password, "abc-123")
        XCTAssertEqual(endpoint?.directory, "/Users/txc")
    }
}
