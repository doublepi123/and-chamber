package io.andchamber.app.ui

import io.andchamber.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatTest {
    @Test
    fun `a plain address without userinfo is still accepted`() {
        assertEquals(
            AddressCheck.Valid(url = "https://example.com:8443/openchamber", host = "example.com"),
            checkServerAddress("  https://example.com:8443/openchamber/  "),
        )
        // The loose host fallback for names java.net.URI rejects (an underscore) stays available.
        assertEquals(
            AddressCheck.Valid(url = "http://my_host:3000", host = "my_host"),
            checkServerAddress("http://my_host:3000"),
        )
    }

    @Test
    fun `userinfo is refused instead of hidden by the loose host fallback`() {
        val userinfo =
            listOf(
                "http://user:pw@example.com:3000",
                "https://user@example.com",
                "user:pw@127.0.0.1:3000",
                // The strict parse fails on the underscore host, so this used to fall back and pass.
                "http://user:pw@my_host:3000",
                // An @ in the authority always means userinfo, whatever the host looks like.
                "http://example.com@evil.example",
                "http://user%40name:pw@example.com",
            )
        userinfo.forEach { raw ->
            val check = checkServerAddress(raw)
            assertTrue(
                "expected $raw to be refused, got $check",
                check is AddressCheck.Invalid && check.messageRes == R.string.address_no_userinfo,
            )
        }
    }

    @Test
    fun `other address problems keep their own message`() {
        assertEquals(AddressCheck.Empty, checkServerAddress("   "))
        assertEquals(AddressCheck.Invalid(R.string.address_no_spaces), checkServerAddress("http://exa mple.com"))
        assertEquals(AddressCheck.Invalid(R.string.address_scheme), checkServerAddress("ftp://example.com"))
    }

    @Test
    fun `display drops the scheme and a trailing slash`() {
        assertEquals("127.0.0.1:3000", displayAddress("http://127.0.0.1:3000/"))
        assertEquals("example.com/openchamber", displayAddress("https://example.com/openchamber"))
        assertEquals("[::1]:3000", displayAddress("http://[::1]:3000/"))
        // An @ in the path or query is not userinfo.
        assertEquals("example.com/path?email=a@b", displayAddress("http://example.com/path?email=a@b"))
    }

    @Test
    fun `display of a saved userinfo address never shows the credentials`() {
        val saved =
            listOf(
                "http://user:pw@example.com:3000/openchamber",
                "http://user:pw@example.com:3000/",
                "https://user@example.com/openchamber",
                // Unparsable as a URL, but still never rendered with its credentials.
                "user:pw@example.com:3000",
            )
        val displayed = saved.map(::displayAddress)
        assertEquals("example.com:3000/openchamber", displayed[0])
        assertEquals("example.com:3000", displayed[1])
        assertEquals("example.com/openchamber", displayed[2])
        assertEquals("example.com:3000", displayed[3])
        displayed.forEach { address ->
            assertFalse(address, address.contains("user"))
            assertFalse(address, address.contains("pw"))
            assertFalse(address, address.contains('@'))
        }
    }
}
