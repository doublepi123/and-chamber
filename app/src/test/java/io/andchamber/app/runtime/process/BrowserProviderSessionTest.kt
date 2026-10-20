package io.andchamber.app.runtime.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.SecureRandom

class BrowserProviderSessionTest {
    @Test
    fun `rotate issues 32 bytes and revoke drops only that launch`() {
        val seen = mutableListOf<String?>()
        val session = BrowserProviderSession(SecureRandom(), object : BrowserProviderSink {
            override fun onLocalLaunch(origin: String, token: String, launchId: Long) {
                seen += token
            }

            override fun onLocalStop(launchId: Long) {
                seen += null
            }
        })

        val first = session.rotate(1L, "http://127.0.0.1:3000")
        val second = session.rotate(2L, "http://127.0.0.1:3000")
        session.revoke(1L)

        assertEquals(64, first.token.length)
        assertNotEquals(first.token, second.token)
        assertEquals(second.token, session.currentToken())
        session.revoke(2L)
        assertNull(session.currentToken())
        assertEquals(listOf(first.token, second.token, null), seen)
    }

    @Test
    fun `issue does not connect and a failed start revokes before connect`() {
        val seen = mutableListOf<String>()
        val session = BrowserProviderSession(SecureRandom(), object : BrowserProviderSink {
            override fun trustedHostAttached(): Boolean = true
            override fun onLocalLaunch(origin: String, token: String, launchId: Long) {
                seen += "connect:$launchId"
            }

            override fun onLocalStop(launchId: Long) {
                seen += "stop:$launchId"
            }
        })

        val issued = session.issue(8L)
        session.revoke(8L)
        session.connect(8L, "http://127.0.0.1:3000", trustedHostAttached = true)

        assertEquals(64, issued.token.length)
        assertEquals(listOf("stop:8"), seen)
        assertNull(session.currentToken())
    }

    @Test
    fun `connect waits for a verified ready and a foreground host`() {
        val seen = mutableListOf<String>()
        var host = false
        val session = BrowserProviderSession(SecureRandom(), object : BrowserProviderSink {
            override fun trustedHostAttached(): Boolean = host
            override fun onLocalLaunch(origin: String, token: String, launchId: Long) {
                seen += origin
            }

            override fun onLocalStop(launchId: Long) {
                seen += "stop"
            }
        })

        session.issue(3L)
        session.connect(3L, "http://127.0.0.1:3000", session.trustedHostAttached())
        assertNull(session.currentToken())
        session.issue(4L)
        host = true
        session.connect(4L, "http://127.0.0.1:3000", session.trustedHostAttached())

        assertEquals(listOf("stop", "http://127.0.0.1:3000"), seen)
        assertEquals(64, session.currentToken()?.length)
    }

    @Test
    fun `server env carries the token only in native mode and never logs it`() {
        val token = "ab".repeat(32)
        val env = ProotCommands.serverEnvironment(
            emptyMap(),
            java.io.File("/tmp/proot"),
            "ui-secret",
            browserControl = true,
            providerToken = token,
        )

        assertEquals("android", env[BrowserProviderSession.MODE_ENV])
        assertEquals(token, env[BrowserProviderSession.TOKEN_ENV])
        assertFalse(env.containsKey(BrowserToolAssets.CONFIG_ENV))
        assertFalse(env.containsKey(BrowserToolAssets.ENDPOINT_ENV))
        assertFalse(env.entries.filter { it.key != BrowserProviderSession.TOKEN_ENV }.any { token in it.value })
    }

    @Test
    fun `legacy mode does not receive a provider token`() {
        val env = ProotCommands.serverEnvironment(emptyMap(), java.io.File("/tmp/proot"), "ui-secret")

        assertFalse(env.containsKey(BrowserProviderSession.TOKEN_ENV))
        assertFalse(env.containsKey(BrowserProviderSession.MODE_ENV))
        assertTrue(env.getValue(BrowserToolAssets.CONFIG_ENV).contains("andchamber_browser"))
    }
}
