package net.agents.mobile

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegistryDefaultsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesRegistrationDefaults() {
        val body = """
            {"data":[{"id":"abc","name":"mbp","type":"cursor","online":true,
            "defaultAgent":"agent",
            "defaultModel":{"id":"claude-4.5","providerID":"cursor"}}]}
        """.trimIndent()
        val server = json.decodeFromString<RegistryListResponse>(body).data.single()
        assertEquals("agent", server.defaultAgent)
        assertEquals("claude-4.5", server.defaultModel?.id)
        assertEquals("cursor", server.defaultModel?.providerID)
    }

    @Test
    fun toleratesMissingDefaults() {
        val body = """{"data":[{"id":"abc","type":"comagic","online":true}]}"""
        val server = json.decodeFromString<RegistryListResponse>(body).data.single()
        assertEquals("", server.defaultAgent)
        assertNull(server.defaultModel)
    }

    @Test
    fun uiStateDefaultsFollowActiveEndpoint() {
        val server = RegistryServer(
            id = "abc",
            defaultAgent = "plan",
            defaultModel = ModelRef(id = "m1", providerID = "p")
        )
        val state = UiState(
            endpoint = Endpoint(id = "local", baseUrl = "http://host:4908/s/abc"),
            registryServers = listOf(server)
        )
        assertEquals("abc", state.endpoint.agentId)
        assertEquals("plan", state.defaultAgent)
        assertEquals("m1", state.defaultModel?.id)
    }
}
