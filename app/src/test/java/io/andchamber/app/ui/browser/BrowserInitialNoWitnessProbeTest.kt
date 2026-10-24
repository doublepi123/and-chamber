package io.andchamber.app.ui.browser

import io.andchamber.app.browser.BrowserUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The initial about:blank no-witness probe may be Pending only before the controlled target's
 * first main-frame start. Every started/accepted/failed/foreign/manual/non-controlled case keeps
 * the old strict rejection; the later target witness is accepted normally.
 */
class BrowserInitialNoWitnessProbeTest {
    private val doc = "d".repeat(32)

    private fun noWitness() = """{"ok":false,"error":"no witness"}"""
    private fun witness(id: String) = """{"ok":true,"documentId":"$id","readyState":"complete"}"""

    @Test
    fun `initial no witness is pending only with the strict initial state and allowed controlled url`() {
        assertEquals(
            ProbeAcceptance.Pending,
            BrowserDriverLogic.acceptProbe(
                acceptedDocumentId = null,
                raw = noWitness(),
                policyAllowsUrl = false, // displayUrl is not known yet
                allowSameDocument = true,
                initialNoWitnessAllowed = true,
                initialControlledUrlAllowed = true,
            ),
        )
        assertEquals(
            ProbeAcceptance.Reject("no witness"),
            BrowserDriverLogic.acceptProbe(
                acceptedDocumentId = null,
                raw = noWitness(),
                policyAllowsUrl = false,
                initialNoWitnessAllowed = false,
                initialControlledUrlAllowed = true,
            ),
        )
        assertEquals(
            "a refused controlled URL is not masked as the blank callback",
            ProbeAcceptance.Reject("no witness"),
            BrowserDriverLogic.acceptProbe(
                acceptedDocumentId = null,
                raw = noWitness(),
                policyAllowsUrl = false,
                initialNoWitnessAllowed = true,
                initialControlledUrlAllowed = false,
            ),
        )
    }

    @Test
    fun `a valid first witness with no display url is still refused`() {
        assertEquals(
            ProbeAcceptance.Reject("url refused"),
            BrowserDriverLogic.acceptProbe(
                acceptedDocumentId = null,
                raw = witness(doc),
                policyAllowsUrl = false,
                allowSameDocument = true,
                initialNoWitnessAllowed = true,
                initialControlledUrlAllowed = true,
            ),
        )
    }

    @Test
    fun `initial pending does not change the later first https document`() {
        assertEquals(
            ProbeAcceptance.Pending,
            BrowserDriverLogic.acceptProbe(
                null,
                noWitness(),
                policyAllowsUrl = false,
                initialNoWitnessAllowed = true,
                initialControlledUrlAllowed = true,
            ),
        )
        assertEquals(
            ProbeAcceptance.Accept(doc),
            BrowserDriverLogic.acceptProbe(
                acceptedDocumentId = null,
                raw = witness(doc),
                policyAllowsUrl = true,
                allowSameDocument = true,
                initialNoWitnessAllowed = false,
                initialControlledUrlAllowed = false,
            ),
        )
    }

    @Test
    fun `other probe failures stay strict even with the initial flags`() {
        assertEquals(
            ProbeAcceptance.Reject("not ready"),
            BrowserDriverLogic.acceptProbe(
                acceptedDocumentId = null,
                raw = """{"ok":false,"error":"not ready"}""",
                policyAllowsUrl = false,
                initialNoWitnessAllowed = true,
                initialControlledUrlAllowed = true,
            ),
        )
    }

    @Test
    fun `the initial controlled url is checked separately and never masks a denied display url`() {
        val policy = BrowserUrlPolicy()
        assertTrue(
            BrowserDriverLogic.initialControlledUrlAllowed("https://example.com/search", null, policy),
        )
        assertFalse(
            "a non-https controlled url is refused",
            BrowserDriverLogic.initialControlledUrlAllowed("http://example.com/search", null, policy),
        )
        assertFalse(
            "a denied display url is not masked",
            BrowserDriverLogic.initialControlledUrlAllowed(
                "https://example.com/search",
                "http://example.com/denied",
                policy,
            ),
        )
        assertTrue(
            BrowserDriverLogic.initialControlledUrlAllowed(
                "https://example.com/search",
                "https://example.com/results",
                policy,
            ),
        )
    }

    @Test
    fun `the initial blank ignore is exact and only for the strict initial state`() {
        assertTrue(BrowserDriverLogic.shouldIgnoreInitialBlankFinished("about:blank", initialAllowed = true))
        assertFalse(BrowserDriverLogic.shouldIgnoreInitialBlankFinished("about:blank#fragment", initialAllowed = true))
        assertFalse(BrowserDriverLogic.shouldIgnoreInitialBlankFinished("https://example.com/", initialAllowed = true))
        assertFalse(BrowserDriverLogic.shouldIgnoreInitialBlankFinished("about:blank", initialAllowed = false))
    }

    @Test
    fun `only a sample crossing the first main start is stale`() {
        assertTrue(BrowserDriverLogic.isStaleInitialProbeSample(0, 1))
        assertTrue(BrowserDriverLogic.isStaleInitialProbeSample(0, 2))
        assertFalse(BrowserDriverLogic.isStaleInitialProbeSample(0, 0))
        assertFalse(BrowserDriverLogic.isStaleInitialProbeSample(1, 1))
        assertFalse(BrowserDriverLogic.isStaleInitialProbeSample(1, 2))
    }

    @Test
    fun `the initial state requires a current controlled load before any start or acceptance`() {
        fun allowed(
            ticket: Boolean = true,
            epoch: Boolean = true,
            starts: Int = 0,
            accepted: String? = null,
            error: String? = null,
            controlled: Boolean = true,
            automation: Boolean = true,
            manual: Boolean = false,
        ) = BrowserDriverLogic.isInitialNoWitnessProbeState(
            ticketCurrent = ticket,
            epochCurrent = epoch,
            mainStarts = starts,
            acceptedDocumentId = accepted,
            pageError = error,
            controlledGet = controlled,
            automationAllowed = automation,
            manualNavigation = manual,
        )

        assertTrue(allowed())
        assertFalse("after the target starts", allowed(starts = 1))
        assertFalse("after a document acceptance", allowed(accepted = doc))
        assertFalse("on a failed page", allowed(error = "failed"))
        assertFalse("an empty error is still a failure", allowed(error = ""))
        assertFalse("on a stale ticket", allowed(ticket = false))
        assertFalse("on a stale epoch", allowed(epoch = false))
        assertFalse("on a non-controlled page", allowed(controlled = false))
        assertFalse("without automation admission", allowed(automation = false))
        assertFalse("during manual navigation", allowed(manual = true))
    }

    @Test
    fun `the session state gate opens only for the fresh controlled ticket`() {
        val session = newSession()
        session.beginControlledGet()
        session.markControlledGet("https://example.com/search")
        assertTrue(session.initialNoWitnessProbeAllowed(session.ticket, session.epoch()))
        assertFalse(session.initialNoWitnessProbeAllowed(session.ticket + 1L, session.epoch()))
        assertFalse(session.initialNoWitnessProbeAllowed(session.ticket, session.epoch() + 1L))

        assertTrue(session.noteMainStart())
        assertFalse("the target started", session.initialNoWitnessProbeAllowed(session.ticket, session.epoch()))
    }

    @Test
    fun `the session state gate rejects accepted error manual and uncontrolled states`() {
        val accepted = newSession()
        accepted.beginControlledGet()
        accepted.markControlledGet("https://example.com/search")
        accepted.acceptedDocumentId = doc
        assertFalse(accepted.initialNoWitnessProbeAllowed(accepted.ticket, accepted.epoch()))

        val failed = newSession()
        failed.beginControlledGet()
        failed.markControlledGet("https://example.com/search")
        setPageError(failed, "failed")
        assertFalse(failed.initialNoWitnessProbeAllowed(failed.ticket, failed.epoch()))
        setPageError(failed, "")
        assertFalse("an empty page error is not the initial blank state", failed.initialNoWitnessProbeAllowed(failed.ticket, failed.epoch()))

        val manual = newSession()
        manual.beginControlledGet()
        manual.markControlledGet("https://example.com/search")
        manual.manualNavigation = true
        assertFalse(manual.initialNoWitnessProbeAllowed(manual.ticket, manual.epoch()))

        val uncontrolled = newSession()
        uncontrolled.beginControlledGet()
        assertFalse(uncontrolled.initialNoWitnessProbeAllowed(uncontrolled.ticket, uncontrolled.epoch()))
    }

    @Test
    fun `initial blank ignore does not forge a document id`() {
        val session = newSession()
        session.beginControlledGet()
        session.markControlledGet("https://example.com/search")
        val initial = session.initialNoWitnessProbeAllowed(session.ticket, session.epoch())
        assertTrue(initial)
        assertTrue(BrowserDriverLogic.shouldIgnoreInitialBlankFinished("about:blank", initial))
        assertNull(session.acceptedDocumentId)

        assertTrue(session.noteMainStart())
        val started = session.initialNoWitnessProbeAllowed(session.ticket, session.epoch())
        assertFalse(started)
        assertFalse(BrowserDriverLogic.shouldIgnoreInitialBlankFinished("about:blank", started))
        assertNull("ignoring or rejecting the blank callback never invents a witness", session.acceptedDocumentId)
    }

    private fun newSession(): BrowserSession {
        val constructor = BrowserSession::class.java.getDeclaredConstructor(
            android.webkit.WebView::class.java,
            kotlin.jvm.functions.Function0::class.java,
            PixelCopyBridge::class.java,
        )
        constructor.isAccessible = true
        return constructor.newInstance(null, { BrowserUrlPolicy() }, PixelCopyBridge()) as BrowserSession
    }

    private fun setPageError(session: BrowserSession, value: String) {
        val setter = BrowserSession::class.java.getDeclaredMethod("setPageError", String::class.java)
        setter.isAccessible = true
        setter.invoke(session, value)
    }
}
