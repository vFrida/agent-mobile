package net.pdd.comagic.mobile

import org.junit.Assert.assertTrue
import org.junit.Test

class ComagicClientTest {

    @Test
    fun healthAndSessions() {
        val baseUrl = System.getenv("COMAGIC_TEST_URL") ?: return
        val password = System.getenv("COMAGIC_TEST_PASSWORD") ?: return

        val client = ComagicClient(Endpoint(baseUrl = baseUrl, password = password))

        assertTrue(client.health().healthy)
        val sessions = client.listSessions()
        println("sessions=${sessions.size}")
        sessions.take(3).forEach { println("${it.id} ${it.title} ${it.agent} ${it.model}") }
    }

    @Test
    fun promptRoundTrip() {
        if (System.getenv("COMAGIC_TEST_PROMPT") != "1") return
        val baseUrl = System.getenv("COMAGIC_TEST_URL") ?: return
        val password = System.getenv("COMAGIC_TEST_PASSWORD") ?: return

        val client = ComagicClient(Endpoint(baseUrl = baseUrl, password = password))
        val model = client.listSessions().firstNotNullOfOrNull { it.model } ?: return
        val session = client.createSession("/tmp", model)

        client.prompt(session.id, "只回复 ok 两个字母，不要其他内容", "build", model)
        val lines = client.history(session.id)
        println(lines.joinToString("\n") { "${it.role}: ${it.text}" })
        assertTrue(lines.any { it.role == "assistant" && it.text.contains("ok") })
    }

    @Test
    fun parseToolEventWithDetails() {
        val event = AgentEvent.parse(
            """{"id":"evt_3","type":"session.tool.called","properties":{"sessionID":"ses_1","toolName":"bash","toolID":"call_1","title":"ls -la","output":"total 0"}}"""
        )
        assertTrue(event != null)
        assertTrue(event!!.toolID == "call_1")
        assertTrue(event.toolTitle == "ls -la")
        assertTrue(event.toolOutput == "total 0")
    }

    @Test
    fun parsePairPayload() {
        val payload = PairPayload.parse(
            "comagic://pair?url=http%3A%2F%2F192.168.1.10%3A4907&user=comagic&pass=abc-123&dir=%2FUsers%2Ftxc"
        )
        assertTrue(payload != null)
        assertTrue(payload!!.baseUrl == "http://192.168.1.10:4907")
        assertTrue(payload.password == "abc-123")
        assertTrue(payload.directory == "/Users/txc")
    }

    @Test
    fun parsePlainUrl() {
        val payload = PairPayload.parse("http://10.0.0.5:4907")
        assertTrue(payload != null)
        assertTrue(payload!!.baseUrl == "http://10.0.0.5:4907")
        assertTrue(payload.password == Credentials.password)
    }

    @Test
    fun parseDeltaEvent() {
        val event = AgentEvent.parse(
            """{"id":"evt_1","type":"session.text.delta","data":{"sessionID":"ses_1","assistantMessageID":"msg_1","ordinal":0,"delta":"hello"}}"""
        )
        assertTrue(event != null)
        assertTrue(event!!.sessionID == "ses_1")
        assertTrue(event.delta == "hello")
    }

    @Test
    fun parseV1EventWithProperties() {
        val event = AgentEvent.parse(
            """{"id":"evt_2","type":"permission.asked","properties":{"id":"per_1","sessionID":"ses_1","permission":"bash"}}"""
        )
        assertTrue(event != null)
        assertTrue(event!!.requestID == "per_1")
        assertTrue(event.sessionID == "ses_1")
    }
}
