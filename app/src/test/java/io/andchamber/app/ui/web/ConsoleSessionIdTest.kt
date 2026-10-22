package io.andchamber.app.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The console URL is the only source of the chat session now that the page script is gone. */
class ConsoleSessionIdTest {
    private val origin = "http://127.0.0.1:3000"

    private fun session(url: String?, trustedOrigin: String = origin) = consoleSessionIdFromUrl(url, trustedOrigin)

    @Test fun `reads the session query parameter`() {
        assertEquals("ses_abc123", session("$origin/?session=ses_abc123"))
        assertEquals("abc", session("$origin/?foo=bar&session=abc&x=1"))
    }

    @Test fun `missing empty or blank values mean no session`() {
        assertNull(session("$origin/"))
        assertNull(session("$origin/?session="))
        assertNull(session("$origin/?session=&x=1"))
        assertNull(session("$origin/?session=%20"))
        assertNull(session("$origin/?other=session"))
    }

    @Test fun `a hash never replaces the search`() {
        assertNull(session("$origin/#/chat?session=abc"))
        assertEquals("abc", session("$origin/?session=abc#message-5"))
        assertEquals("abc", session("$origin/?session=abc#/settings"))
    }

    @Test fun `other origins and schemes are ignored`() {
        assertNull(session("http://127.0.0.1:3001/?session=abc"))
        assertNull(session("https://127.0.0.1:3000/?session=abc"))
        assertNull(session("http://example.com:3000/?session=abc"))
        assertNull(session("$origin/?session=abc", trustedOrigin = ""))
        assertNull(consoleSessionIdFromUrl(null, origin))
    }

    @Test fun `path variants still carry the session`() {
        assertEquals("abc", session("$origin/chat?session=abc"))
        assertEquals("abc", session("$origin/sessions/one?session=abc"))
        assertEquals("abc", session("$origin/index.html?session=abc"))
        assertEquals("abc", session("$origin/?directory=%2Froot&session=abc"))
    }

    @Test fun `percent encoding is decoded once and the first value wins`() {
        assertEquals("ses/1 x", session("$origin/?session=ses%2F1%20x"))
        assertEquals("你好", session("$origin/?session=%E4%BD%A0%E5%A5%BD"))
        assertEquals("a", session("$origin/?session=a&session=b"))
    }
}
