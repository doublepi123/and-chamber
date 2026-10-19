package io.andchamber.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpDownloadPolicyTest {
    private val binding = canonicalWebOrigin("https://host.example/")!!

    private fun policy(
        initial: String,
        bindingOrigin: String = binding,
        max: Int = MAX_DOWNLOAD_REDIRECTS,
    ) = DownloadRedirectPolicy(bindingOrigin, initial.toHttpUrl(), max)

    @Test
    fun initialSameOriginAllowsCookies() {
        assertTrue(policy("https://HOST.example:443/file").cookiesAllowed)
    }

    @Test
    fun initialOtherOriginIsAnonymous() {
        assertFalse(policy("https://other.example/file").cookiesAllowed)
        assertFalse(policy("http://host.example/file").cookiesAllowed) // scheme differs
        assertFalse(policy("https://host.example:8443/file").cookiesAllowed) // port differs
    }

    @Test
    fun sameOriginRedirectKeepsCookies() {
        val p = policy("https://host.example/a")
        p.nextLocation("/b")
        assertTrue(p.cookiesAllowed)
    }

    @Test
    fun crossHostRedirectDropsCookiesPermanently() {
        val p = policy("https://host.example/a")
        p.nextLocation("https://cdn.example/file")
        assertFalse(p.cookiesAllowed)
        p.nextLocation("https://host.example/back") // returning to the binding origin does not restore them
        assertFalse(p.cookiesAllowed)
    }

    @Test
    fun crossPortAndCrossSchemeDropCookies() {
        val port = policy("https://host.example/a")
        port.nextLocation("https://host.example:9443/x")
        assertFalse(port.cookiesAllowed)

        val scheme = policy("http://host.example:3000/a", bindingOrigin = canonicalWebOrigin("http://host.example:3000/")!!)
        assertTrue(scheme.cookiesAllowed)
        scheme.nextLocation("https://host.example:3000/x")
        assertFalse(scheme.cookiesAllowed)
    }

    @Test
    fun httpsToHttpIsRefused() {
        val p = policy("https://host.example/a")
        expectRejected { p.nextLocation("http://host.example/a") }
    }

    @Test
    fun httpsSeenAfterRedirectStillBlocksDowngrade() {
        val p = policy("http://other.example/a")
        p.nextLocation("https://third.example/b")
        expectRejected { p.nextLocation("http://third.example/c") }
    }

    @Test
    fun invalidLocationsAreRefused() {
        val p = policy("https://host.example/a")
        expectRejected { p.nextLocation(null) }
        expectRejected { p.nextLocation("") }
        expectRejected { p.nextLocation("ftp://host.example/x") }
        expectRejected { p.nextLocation("javascript:alert(1)") }
        expectRejected { p.nextLocation("https://user:secret@host.example/x") }
        expectRejected { p.nextLocation("https://user@host.example/x") }
    }

    @Test
    fun redirectLimitIsEnforced() {
        val p = policy("https://host.example/a", max = 2)
        p.nextLocation("/b")
        p.nextLocation("/c")
        expectRejected { p.nextLocation("/d") }
        assertEquals(2, p.redirects)
    }

    @Test
    fun sanitizesFileNames() {
        val name = sanitizeDownloadFileName("../../a/b\\c:d*.txt")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains(':'))
        assertFalse(name.startsWith("."))
        assertEquals("download", sanitizeDownloadFileName("..."))
        assertEquals("download", sanitizeDownloadFileName(""))
        assertEquals("download", sanitizeDownloadFileName(null))
        assertEquals(120, sanitizeDownloadFileName("x".repeat(300)).length)
        assertEquals("a_b.txt", sanitizeDownloadFileName("a\u0001b.txt"))
    }

    @Test
    fun numbersNamesKeepingExtension() {
        assertEquals("report (2).pdf", numberedName("report.pdf", 2))
        assertEquals("README (1)", numberedName("README", 1))
    }

    private fun expectRejected(block: () -> Unit) {
        try {
            block()
            fail("expected HttpDownloadRejected")
        } catch (_: HttpDownloadRejected) {
            // expected
        }
    }
}
