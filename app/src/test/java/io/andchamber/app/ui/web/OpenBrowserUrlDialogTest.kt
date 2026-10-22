package io.andchamber.app.ui.web

import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.UrlDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class OpenBrowserUrlDialogTest {
    private fun normalized(input: String) = normalizeBrowserUrl(input)

    @Test
    fun `a plain host gets https`() {
        assertEquals("https://example.com", normalized("example.com"))
        assertEquals("https://www.example.com", normalized("www.example.com"))
    }

    @Test
    fun `a host with a path keeps the path, query and fragment`() {
        assertEquals("https://example.com/docs/start", normalized("example.com/docs/start"))
        assertEquals("https://example.com/search?q=1&page=2#results", normalized("example.com/search?q=1&page=2#results"))
        assertEquals("https://example.com?q=1", normalized("example.com?q=1"))
    }

    @Test
    fun `http is kept as typed`() {
        assertEquals("http://example.com/a", normalized("http://example.com/a"))
    }

    @Test
    fun `https is kept as typed`() {
        assertEquals("https://example.com/a", normalized("https://example.com/a"))
        assertEquals("https://example.com/", normalized("https://example.com/"))
    }

    @Test
    fun `an uppercase scheme and host are lowercased, the path is not`() {
        assertEquals("https://example.com/Path?Q=A#Frag", normalized("HTTPS://EXAMPLE.COM/Path?Q=A#Frag"))
        assertEquals("http://example.com", normalized("Http://Example.com"))
    }

    @Test
    fun `spaces around the address are trimmed`() {
        assertEquals("https://example.com", normalized("  example.com  "))
        assertEquals("https://example.com/a", normalized("\thttps://example.com/a\n"))
        // Ideographic space from a Chinese keyboard, and a zero-width space from a pasted link.
        assertEquals("https://example.com", normalized("\u3000example.com\u3000"))
        assertEquals("https://example.com", normalized("\u200Bexample.com\uFEFF"))
    }

    @Test
    fun `empty and blank text is not an address`() {
        assertNull(normalized(""))
        assertNull(normalized("   "))
        assertNull(normalized("\n"))
    }

    @Test
    fun `spaces inside are refused`() {
        assertNull(normalized("example .com"))
        assertNull(normalized("https://example.com/a b"))
        assertNull(normalized("search words"))
        assertNull(normalized("https://example.com/\u3000x"))
    }

    @Test
    fun `javascript is refused in any spelling`() {
        assertNull(normalized("javascript:alert(1)"))
        assertNull(normalized("JavaScript:alert(1)"))
        assertNull(normalized("  javascript:alert(1)"))
        // Digits after the colon must not read as host and port.
        assertNull(normalized("javascript:1"))
        // Control characters inside the scheme are not stripped into "javascript".
        assertNull(normalized("java\tscript:alert(1)"))
        assertNull(normalized("java\u0000script:alert(1)"))
    }

    @Test
    fun `file is refused`() {
        assertNull(normalized("file:///etc/hosts"))
        assertNull(normalized("FILE:///sdcard/a.html"))
        assertNull(normalized("file://localhost/etc/hosts"))
    }

    @Test
    fun `data is refused`() {
        assertNull(normalized("data:text/html,<p>hi</p>"))
        assertNull(normalized("data:text/html;base64,PHA+aGk8L3A+"))
    }

    @Test
    fun `other non-web schemes are refused`() {
        listOf(
            "intent://scan/#Intent;scheme=zxing;end",
            "content://com.example.provider/file",
            "about:blank",
            "blob:https://example.com/1234",
            "mailto:user@example.com",
            "tel:123",
            "sms:123",
            "ftp://example.com/file",
            "ws://example.com/socket",
            "chrome://settings",
            "view-source:https://example.com",
            "market://details?id=x",
            "vbscript:msgbox(1)",
        ).forEach { assertNull(it, normalized(it)) }
    }

    @Test
    fun `a missing host is refused`() {
        assertNull(normalized("https://"))
        assertNull(normalized("http://"))
        assertNull(normalized("https:///path"))
        assertNull(normalized("https://:8080"))
        assertNull(normalized("https://?q=1"))
        assertNull(normalized("//"))
        // Without the two slashes there is no host either.
        assertNull(normalized("https:example.com"))
        assertNull(normalized("http:/example.com"))
    }

    @Test
    fun `a port is kept`() {
        assertEquals("https://example.com:8443", normalized("example.com:8443"))
        assertEquals("https://example.com:8443/docs?x=1", normalized("example.com:8443/docs?x=1"))
        assertEquals("http://example.com:8080/a", normalized("http://example.com:8080/a"))
        assertEquals("https://localhost:3000", normalized("localhost:3000"))
        assertEquals("https://192.0.2.1:8080", normalized("192.0.2.1:8080"))
        assertEquals("https://[2001:db8::1]:8443/a", normalized("https://[2001:db8::1]:8443/a"))
    }

    @Test
    fun `an empty port is dropped and leading zeros are not kept`() {
        assertEquals("https://example.com", normalized("https://example.com:"))
        assertEquals("https://example.com/x", normalized("https://example.com:/x"))
        assertEquals("https://example.com:80/x", normalized("https://example.com:0080/x"))
    }

    @Test
    fun `a port outside 1 to 65535 is refused`() {
        assertNull(normalized("example.com:0"))
        assertNull(normalized("example.com:65536"))
        assertNull(normalized("example.com:99999"))
        assertEquals("https://example.com:65535", normalized("example.com:65535"))
    }

    @Test
    fun `a user name or password is refused`() {
        assertNull(normalized("https://user:secret@example.com"))
        assertNull(normalized("user@example.com"))
        assertNull(normalized("https://@example.com"))
    }

    @Test
    fun `a scheme typed without its colon is not taken for a host`() {
        assertNull(normalized("https//example.com"))
        assertNull(normalized("http//example.com"))
    }

    @Test
    fun `a host with invalid characters is refused`() {
        assertNull(normalized("exa_mple.com"))
        assertNull(normalized("example..com"))
        assertNull(normalized("-bad.example"))
        assertNull(normalized("https://exa<mple.com"))
    }

    @Test
    fun `a non-ASCII host is refused instead of folded into another one`() {
        assertNull(normalized("例子.中国"))
        assertNull(normalized("https://例子.中国/"))
        // Fullwidth letters and colon from an IME look like ASCII but are not.
        assertNull(normalized("ｅｘａｍｐｌｅ.com"))
        assertNull(normalized("https：//example.com"))
    }

    @Test
    fun `a scheme-relative address gets https`() {
        assertEquals("https://example.com/a", normalized("//example.com/a"))
    }

    @Test
    fun `characters a browser escapes are escaped and existing escapes are kept`() {
        assertEquals("https://example.com/a%7Cb", normalized("example.com/a|b"))
        assertEquals("https://example.com/?a=50%25", normalized("example.com/?a=50%"))
        assertEquals("https://example.com/?off=100%25", normalized("example.com/?off=100%25"))
        assertEquals("https://example.com/#a%23b", normalized("example.com/#a#b"))
        assertEquals("https://example.com/%7Bx%7D%5E%60%22%3C%3E%5B1%5D", normalized("example.com/{x}^`\"<>[1]"))
        assertEquals("https://example.com/a'b(c)*!\$&+,;=:@", normalized("example.com/a'b(c)*!\$&+,;=:@"))
    }

    @Test
    fun `non-ASCII text after the host is escaped as UTF-8`() {
        assertEquals("https://example.com/wiki/%E5%8C%97%E4%BA%AC", normalized("example.com/wiki/北京"))
        assertEquals(
            "https://example.com/s?q=%E4%B8%AD%E6%96%87#%E7%89%87",
            normalized("https://example.com/s?q=中文#片"),
        )
        assertEquals("https://example.com/%F0%9F%98%80", normalized("example.com/\uD83D\uDE00"))
        // Half of an emoji is broken text, not an address.
        assertNull(normalized("example.com/\uD83D"))
        assertNull(normalized("example.com/\uDE00x"))
    }

    @Test
    fun `every result is ASCII, parses with the same host, and normalizes to itself`() {
        listOf(
            "example.com",
            "HTTPS://EXAMPLE.COM/Path",
            "example.com/a|b?c=de",
            "example.com/wiki/北京#历史",
            "example.com:8443/x?y=%zz#a#b",
            "http://192.0.2.7/a",
            "https://[2001:db8::1]/",
        ).forEach { input ->
            val url = normalized(input)!!
            assertTrue(url, url.all { it.code in 0x21..0x7E })
            assertTrue(url, url.startsWith("https://") || url.startsWith("http://"))
            val uri = URI(url)
            assertEquals(url, uri.toString())
            assertTrue(url, uri.host != null && uri.rawUserInfo == null)
            assertEquals(url, normalized(url))
        }
    }

    @Test
    fun `a typed address is ready only when the browser window would open it`() {
        val policy = BrowserUrlPolicy()
        assertEquals(BrowserUrlCheck.Ready("https://example.com/docs"), checkBrowserUrl("example.com/docs", policy))
        assertEquals(BrowserUrlCheck.Ready("https://example.com:8443"), checkBrowserUrl("example.com:8443", policy))
        // Plain http, loopback and home-network addresses are refused by the window, so Open stays off.
        assertEquals(BrowserUrlCheck.Refused("http://example.com"), checkBrowserUrl("http://example.com", policy))
        assertEquals(BrowserUrlCheck.Refused("https://localhost:3000"), checkBrowserUrl("localhost:3000", policy))
        assertEquals(BrowserUrlCheck.Refused("https://192.168.1.20"), checkBrowserUrl("192.168.1.20", policy))
        assertEquals(BrowserUrlCheck.Refused("https://127.0.0.1:8080"), checkBrowserUrl("127.0.0.1:8080", policy))
        // Ready always agrees with the window's own check.
        listOf("example.com", "https://www.example.org/a?b=c#d", "EXAMPLE.COM/Path").forEach { input ->
            val ready = checkBrowserUrl(input, policy) as BrowserUrlCheck.Ready
            assertTrue(ready.url, policy.checkSyntax(ready.url) is UrlDecision.Allow)
        }
    }

    @Test
    fun `text still on its way to an address shows no message yet`() {
        listOf(
            "", "  ", "h", "ht", "HTTPS", "https:", "https:/", "https://", "http://",
            "localhost:", "example.com:", "www.", "https://www.example.",
        ).forEach { assertEquals(it, BrowserUrlCheck.Unfinished, checkBrowserUrl(it)) }
    }

    @Test
    fun `text that can't become an address is invalid`() {
        listOf("javascript:alert(1)", "file:///etc/hosts", "data:text/html,x", "example .com", "https:///x", "https//example.com")
            .forEach { assertEquals(it, BrowserUrlCheck.Invalid, checkBrowserUrl(it)) }
    }
}
