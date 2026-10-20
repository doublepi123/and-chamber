package io.andchamber.app.browser

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class BrowserUrlPolicyTest {
    private val policy = BrowserUrlPolicy(
        forbiddenOrigins = setOf("https://console.example.com"),
        resolver = DnsResolver { host ->
            when (host) {
                "public.example" -> listOf(InetAddress.getByName("93.184.216.34"))
                "rebind.example" -> listOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.1.2.3"))
                "empty.example" -> emptyList()
                else -> error("unexpected lookup $host")
            }
        },
    )

    @Test
    fun `public https is allowed and origin drops path`() {
        val decision = policy.checkSyntax("https://public.example/a/b?q=1#f")
        assertEquals(UrlDecision.Allow("https://public.example:443"), decision)
    }

    @Test
    fun `non-https userinfo and local names are refused`() {
        assertDeny("http://public.example", UrlDenyReason.NOT_PUBLIC_HTTPS)
        assertDeny("https://user:pw@public.example", UrlDenyReason.USERINFO)
        assertDeny("https://printer.local", UrlDenyReason.HOST)
        assertDeny("https://app.localhost", UrlDenyReason.HOST)
        assertDeny("https://localhost", UrlDenyReason.HOST)
    }

    @Test
    fun `private loopback link-local multicast unspecified and mapped addresses are refused`() {
        assertDeny("https://10.1.2.3", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://127.0.0.1", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://169.254.1.1", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://224.0.0.1", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://0.0.0.0", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://[::1]", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://[fd00::1]", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://[fe80::1]", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://[ff02::1]", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://[::]", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://[::ffff:127.0.0.1]", UrlDenyReason.NON_PUBLIC_ADDRESS)
        assertDeny("https://[::ffff:10.0.0.1]", UrlDenyReason.NON_PUBLIC_ADDRESS)
    }

    @Test
    fun `confusable hosts and extra forbidden origins are refused`() {
        assertDeny("https://xn--example-9ua.com", UrlDenyReason.HOST)
        assertDeny("https://exämple.com", UrlDenyReason.HOST)
        assertDeny("https://console.example.com/admin", UrlDenyReason.FORBIDDEN_ORIGIN)
        assertDeny("https://Console.Example.com/admin", UrlDenyReason.FORBIDDEN_ORIGIN)
    }

    @Test
    fun `dns preflight refuses a mixed answer and does not block on syntax`() {
        runBlocking {
            assertEquals(
                UrlDecision.Allow("https://public.example:443"),
                policy.preflight("https://public.example/x"),
            )
            assertEquals(
                UrlDecision.Deny(UrlDenyReason.NON_PUBLIC_ADDRESS),
                policy.preflight("https://rebind.example"),
            )
            assertEquals(UrlDecision.Deny(UrlDenyReason.DNS), policy.preflight("https://empty.example"))
        }
        assertEquals(UrlDecision.Deny(UrlDenyReason.NOT_PUBLIC_HTTPS), policy.checkSyntax("http://127.0.0.1"))
    }

    private fun assertDeny(url: String, reason: UrlDenyReason) {
        val decision = policy.checkSyntax(url)
        assertTrue("$url -> $decision", decision is UrlDecision.Deny)
        assertEquals(reason, (decision as UrlDecision.Deny).reason)
    }
}
