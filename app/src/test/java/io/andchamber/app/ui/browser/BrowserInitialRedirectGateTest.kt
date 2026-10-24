package io.andchamber.app.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the first open of a controlled page whose own HTTP redirect used to be refused as
 * an unsupported main-frame navigation. The initial loadUrl never reaches this gate; only its
 * redirect does. A fresh controlled GET may follow that redirect in place, while every other
 * redirect keeps the existing refusal.
 */
class BrowserInitialRedirectGateTest {

    private fun allowed(
        method: String? = "GET",
        isRedirect: Boolean = true,
        manual: Boolean = false,
        controlled: Boolean = true,
        controlledTicket: Long = 7L,
        ticket: Long = 7L,
        mainStarts: Int = 0,
        documentAccepted: Boolean = false,
        unknownNavigation: Boolean = false,
    ): Boolean = MainFrameGate.allowsControlledInitialRedirect(
        method = method,
        isRedirect = isRedirect,
        manual = manual,
        controlled = controlled,
        controlledTicket = controlledTicket,
        ticket = ticket,
        mainStarts = mainStarts,
        documentAccepted = documentAccepted,
        unknownNavigation = unknownNavigation,
    )

    @Test
    fun `first controlled GET redirect continues before any document starts`() {
        assertTrue(allowed())
    }

    @Test
    fun `redirect is refused once a main document started`() {
        assertFalse(allowed(mainStarts = 1))
        assertFalse(allowed(mainStarts = 2))
    }

    @Test
    fun `redirect is refused after a document or submit document was accepted`() {
        assertFalse(allowed(documentAccepted = true))
    }

    @Test
    fun `redirect is refused without a controlled GET`() {
        assertFalse(allowed(controlled = false))
        assertFalse(allowed(controlledTicket = 0L))
    }

    @Test
    fun `redirect is refused for a stale ticket`() {
        assertFalse(allowed(controlledTicket = 6L))
        assertFalse(allowed(ticket = 8L))
    }

    @Test
    fun `POST manual and non-redirect requests keep the default refusal`() {
        assertFalse(allowed(method = "POST"))
        assertFalse(allowed(manual = true))
        assertFalse(allowed(isRedirect = false))
    }

    @Test
    fun `unknown navigation keeps the refusal`() {
        assertFalse(allowed(unknownNavigation = true))
    }

    @Test
    fun `plain GET definition is not widened`() {
        assertTrue(MainFrameGate.isPlainGet("GET", isRedirect = false))
        assertFalse(MainFrameGate.isPlainGet("GET", isRedirect = true))
        assertFalse(MainFrameGate.isPlainGet("POST", isRedirect = false))
        assertFalse(MainFrameGate.allows("GET", isRedirect = true, manual = false))
    }
}
