package io.andchamber.app.browser.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRegistryTest {
    private val registry = ProviderRegistry()

    @Test
    fun `grant follows the caller session and a switch revokes it`() {
        val tab = registry.open("session-a", "/proj", "local", "https://example.com", null)
        registry.grant(7L, "conn", "session-a", tab.tabId)
        val request = request("session-a", tab.tabId)
        assertTrue(registry.authorized(request, "conn", tab.tabId))
        assertFalse(registry.authorized(request, "other", tab.tabId))
        assertNull(registry.tabFor("session-b", tab.tabId))
        registry.revoke()
        assertFalse(registry.authorized(request, "conn", tab.tabId))
    }

    @Test
    fun `open without tabId does not replace another session tab`() {
        val first = registry.open("session-a", "/a", "local", "https://a.example", null)
        val second = registry.open("session-b", "/b", "local", "https://b.example", null)
        assertEquals("https://a.example", registry.tabFor("session-a", null)?.url)
        assertEquals(second.tabId, registry.tabFor("session-b", null)?.tabId)
        assertFalse(first.tabId == second.tabId)
    }

    @Test
    fun `tab ids are uuids and an explicit id cannot cross sessions`() {
        val first = registry.open("session-a", "/a", "local", "https://a.example", null)
        assertTrue(first.tabId.startsWith("tab-"))
        assertTrue(first.tabId.length > "tab-".length + 8)
        val again = ProviderRegistry().open("session-a", "/a", "local", "https://a.example", null)
        assertNotEquals(first.tabId, again.tabId)
        try {
            registry.open("session-b", "/b", "local", "https://b.example", first.tabId)
            throw AssertionError("cross-session tab id was accepted")
        } catch (error: IllegalStateException) {
            assertEquals("other session", error.message)
        }
        assertEquals("https://a.example", registry.tabFor("session-a", first.tabId)?.url)
    }

    @Test
    fun `session tab switch and manual revoke drop the grant`() {
        val tab = registry.open("session-a", "/a", "local", "https://a.example", "tab-1")
        val other = registry.open("session-a", "/a", "local", "https://b.example", "tab-2")
        registry.grant(1L, "conn", "session-a", tab.tabId)
        registry.onVisibleChanged("session-a", other.tabId)
        assertNull(registry.currentGrant())
        registry.grant(1L, "conn", "session-a", other.tabId)
        registry.onVisibleChanged("session-b", other.tabId)
        assertNull(registry.currentGrant())
        registry.grant(1L, "conn", "session-a", other.tabId)
        registry.onVisibleChanged("session-a", other.tabId)
        assertEquals(other.tabId, registry.currentGrant()?.tabId)
        registry.revoke()
        assertNull(registry.currentGrant())
    }

    @Test
    fun `missing session and hidden tab are not authority`() {
        val tab = registry.open("session-a", "/a", "local", "https://a.example", "tab-1")
        registry.grant(1L, "conn", "session-a", tab.tabId)
        assertFalse(registry.authorized(request(null, tab.tabId), "conn", tab.tabId))
        assertFalse(registry.authorized(request("session-a", tab.tabId), "conn", "other-visible"))
    }

    private fun request(sessionId: String?, tabId: String?) = ProviderRequest(
        serverEpoch = "e",
        connectionId = "conn",
        requestId = "r",
        action = "browser.snapshot",
        parameters = if (tabId == null) emptyMap() else mapOf("tabId" to tabId),
        directory = "/a",
        sessionId = sessionId,
        timeoutMs = 1_000,
    )
}
