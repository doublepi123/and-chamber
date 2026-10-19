package io.andchamber.app.data

import io.andchamber.app.runtime.WebSessionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure key/origin rules shared by profiles, auth, WebView and downloads. These are NOT a test of the
 * Android WebView provider: whether a profile actually isolates cookies needs a device test.
 */
class WebSessionOriginTest {
    @Test
    fun `default ports are filled in so explicit and implicit forms match`() {
        assertEquals("https://host:443", canonicalWebOrigin("https://HOST"))
        assertEquals("https://host:443", canonicalWebOrigin("https://host:443"))
        assertEquals("http://example.com:80", canonicalWebOrigin("http://Example.com"))
    }

    @Test
    fun `path query and fragment do not change the origin`() {
        assertEquals("https://a.example:8443", canonicalWebOrigin("https://a.example:8443/x/y?z=1#f"))
        assertEquals("http://127.0.0.1:3000", canonicalWebOrigin("http://127.0.0.1:3000/"))
    }

    @Test
    fun `ipv6 keeps brackets and the effective port`() {
        assertEquals("http://[::1]:3000", canonicalWebOrigin("http://[::1]:3000/path"))
        assertEquals("http://[::1]:80", canonicalWebOrigin("http://[::1]"))
    }

    @Test
    fun `userinfo is refused`() {
        assertNull(canonicalWebOrigin("http://user:pw@127.0.0.1:3000"))
        assertNull(canonicalWebOrigin("https://user@example.com"))
    }

    @Test
    fun `non http schemes and garbage are refused`() {
        assertNull(canonicalWebOrigin("ftp://x"))
        assertNull(canonicalWebOrigin("file:///etc/passwd"))
        assertNull(canonicalWebOrigin("not a url"))
        assertNull(canonicalWebOrigin(""))
    }

    @Test
    fun `profile key is stable for the same identity and never Default`() {
        val id = WebSessionIdentity("t1", "https://example.com:443")
        assertEquals(profileNameFor(id), profileNameFor(WebSessionIdentity("t1", "https://example.com:443")))
        assertTrue(profileNameFor(id).startsWith("andchamber_v1_"))
        assertNotEquals("Default", profileNameFor(id))
    }

    @Test
    fun `changing the target or the origin changes the key, and the same origin under two targets differs`() {
        val base = profileNameFor(WebSessionIdentity("t1", "https://example.com:443"))
        assertNotEquals(base, profileNameFor(WebSessionIdentity("t2", "https://example.com:443")))
        assertNotEquals(base, profileNameFor(WebSessionIdentity("t1", "https://example.com:8443")))
    }

    @Test
    fun `targetId and origin cannot be shifted into each other`() {
        assertNotEquals(
            profileNameFor(WebSessionIdentity("ab", "c")),
            profileNameFor(WebSessionIdentity("a", "bc")),
        )
    }
}
