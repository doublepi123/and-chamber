package io.andchamber.app.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartPageDecisionTest {
    @Test
    fun `saved page survives gateway and gateway retry then is consumed once on ready`() {
        assertGatewaySequence(deepLink = null, resume = "/?session=resume-test", expected = "/?session=resume-test")
    }

    @Test
    fun `notification wins over saved page through gateway retries and consumes both on ready`() {
        assertGatewaySequence(
            deepLink = "/?session=notification-test",
            resume = "/?session=resume-test",
            expected = "/?session=notification-test",
        )
    }

    private fun assertGatewaySequence(deepLink: String?, resume: String?, expected: String) {
        var pendingDeepLink = deepLink
        var pendingResume = resume
        var consumed = 0
        // Native prepare returns Gateway -> Gateway retry -> Ready -> another Ready.
        listOf(true, true, false, false).forEachIndexed { index, gateway ->
            val decision = startPageDecision(pendingDeepLink, pendingResume, gateway)
            assertEquals(if (index < 3) expected else null, decision.path)
            // The join rule travels with the path: a saved page is never a notification link.
            assertEquals(
                when {
                    index == 3 -> null
                    deepLink != null -> StartPageSource.Notification
                    else -> StartPageSource.Remembered
                },
                decision.source,
            )
            assertEquals(index == 2, decision.consume)
            if (decision.consume) {
                consumed++
                pendingDeepLink = null
                pendingResume = null
            }
            assertEquals(if (index < 2) 0 else 1, consumed)
        }
        assertEquals(1, consumed)
    }

    @Test
    fun `no path has nothing to consume`() {
        assertFalse(startPageDecision(null, null, needsGatewayLogin = false).consume)
        assertFalse(startPageDecision(null, null, needsGatewayLogin = true).consume)
    }

    @Test
    fun `notification link is joined onto the instance base path`() {
        val page =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000/openchamber",
                baseUrl = "http://127.0.0.1:3000/openchamber",
                deepLinkPath = "/?session=s1",
                resumePath = null,
                needsGatewayLogin = false,
            )
        assertEquals("http://127.0.0.1:3000/openchamber/?session=s1", page.url)
        assertTrue(page.consume)
    }

    @Test
    fun `remembered page on a sub-path instance is restored without repeating the base path`() {
        val page =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000/openchamber",
                baseUrl = "http://127.0.0.1:3000/openchamber",
                deepLinkPath = null,
                resumePath = "/openchamber/?session=s1",
                needsGatewayLogin = false,
            )
        assertEquals("http://127.0.0.1:3000/openchamber/?session=s1", page.url)
        assertTrue(page.consume)
    }

    @Test
    fun `remembered page at the root is unchanged`() {
        val page =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000",
                baseUrl = "http://127.0.0.1:3000",
                deepLinkPath = null,
                resumePath = "/?session=s1",
                needsGatewayLogin = false,
            )
        assertEquals("http://127.0.0.1:3000/?session=s1", page.url)
    }

    @Test
    fun `notification wins over a saved page and stays pending through a gateway round`() {
        val page =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000/openchamber",
                baseUrl = "http://127.0.0.1:3000/openchamber",
                deepLinkPath = "/?session=notification",
                resumePath = "/openchamber/?session=saved",
                needsGatewayLogin = true,
            )
        assertEquals("http://127.0.0.1:3000/openchamber/?session=notification", page.url)
        assertFalse(page.consume)
        val decision = startPageDecision("/?session=notification", "/openchamber/?session=saved", needsGatewayLogin = true)
        assertEquals(StartPageSource.Notification, decision.source)
    }

    @Test
    fun `nothing pending loads the start URL`() {
        val page =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000/openchamber",
                baseUrl = "http://127.0.0.1:3000/openchamber",
                deepLinkPath = null,
                resumePath = null,
                needsGatewayLogin = false,
            )
        assertEquals("http://127.0.0.1:3000/openchamber", page.url)
        assertFalse(page.consume)
    }

    @Test
    fun `an unusable base never falls back to a duplicated saved path`() {
        val page =
            startPageUrl(
                startUrl = "http://127.0.0.1:3000/openchamber",
                baseUrl = "not a url",
                deepLinkPath = null,
                resumePath = "/openchamber/?session=s1",
                needsGatewayLogin = false,
            )
        assertEquals("http://127.0.0.1:3000/openchamber", page.url)
        assertTrue(page.consume)
    }
}
