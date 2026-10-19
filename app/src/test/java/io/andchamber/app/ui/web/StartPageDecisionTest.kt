package io.andchamber.app.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
