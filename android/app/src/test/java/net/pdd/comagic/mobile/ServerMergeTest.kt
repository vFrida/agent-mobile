package net.pdd.comagic.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServerMergeTest {

    private val base = Endpoint(id = "a", baseUrl = "http://192.168.1.10:4907")

    @Test
    fun appendsNewServerWithGeneratedId() {
        val (servers, saved) = upsertServer(listOf(base), Endpoint(baseUrl = "http://192.168.1.11:4907")) {
            "new-id"
        }
        assertEquals(2, servers.size)
        assertEquals("new-id", saved.id)
        assertEquals("http://192.168.1.11:4907", saved.baseUrl)
    }

    @Test
    fun sameUrlWithTrailingSlashUpdatesExisting() {
        val (servers, saved) = upsertServer(listOf(base), Endpoint(baseUrl = "http://192.168.1.10:4907/")) {
            "new-id"
        }
        assertEquals(1, servers.size)
        assertEquals("a", saved.id)
        assertNotEquals("new-id", saved.id)
    }

    @Test
    fun sameUrlIgnoringCaseUpdatesExisting() {
        val (servers, saved) = upsertServer(
            listOf(base),
            Endpoint(baseUrl = "HTTP://192.168.1.10:4907", password = "next")
        ) { "new-id" }
        assertEquals(1, servers.size)
        assertEquals("a", saved.id)
        assertEquals("next", servers.single().password)
    }

    @Test
    fun editingServerToAnotherServersUrlMergesDuplicates() {
        val other = Endpoint(id = "b", baseUrl = "http://192.168.1.11:4907")
        val (servers, saved) = upsertServer(
            listOf(base, other),
            Endpoint(id = "a", baseUrl = "http://192.168.1.11:4907", name = "renamed")
        )
        assertEquals(1, servers.size)
        assertEquals("a", saved.id)
        assertEquals("renamed", servers.single().name)
    }

    @Test
    fun blankUsernameFallsBackToDefault() {
        val (_, saved) = upsertServer(emptyList(), Endpoint(baseUrl = "http://10.0.0.5:4907", username = " ", directory = " /tmp ")) {
            "id"
        }
        assertEquals(Credentials.username, saved.username)
        assertEquals("/tmp", saved.directory)
    }
}
