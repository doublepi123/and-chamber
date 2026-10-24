package io.andchamber.app.ui.browser

import io.andchamber.app.browser.BrowserUrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The takeover-preserving Reload's one controlled GET on the real [BrowserSession] state machine
 * (no WebView). The page is manual from the start; only that load's first document start keeps its
 * controlled ticket and witness rules. Nothing loads twice, and no later or foreign document gets
 * a relaxed rule.
 */
class BrowserManualControlledLoadTest {
    private val doc = "d".repeat(32)
    private val url = "https://example.com/reload"

    /** The same steps WebControlledPage.loadOnce performs before load(); no second GET. */
    private fun controlledLoad(): BrowserSession = newSession().apply {
        beginControlledGet()
        markControlledGet(url)
    }

    @Test
    fun `the reload's first start keeps the controlled ticket in manual mode`() {
        val session = controlledLoad()
        val ticket = session.ticket
        var manualDocs = 0
        session.onManualDocument = { manualDocs += 1 }

        assertTrue("the pending controlled load is marked", session.keepManualForControlledLoad())
        assertTrue("manual from the commit on, before any start", session.manualNavigation)
        // Before the start: the initial about:blank handling of this exact controlled load is kept.
        assertTrue(session.initialNoWitnessProbeAllowed(ticket, session.epoch()))

        assertTrue(session.noteDocumentStart(url))

        assertEquals("the controlled ticket is not replaced", ticket, session.ticket)
        assertEquals("not counted as a person's new document", 0, manualDocs)
        assertTrue("controlled provenance stays", session.controlledGet)
        assertEquals(url, session.controlledGetUrl)
        assertEquals(1, session.mainStarts)
        assertFalse(session.unknownNavigation)
        assertTrue("still the person's navigation", session.manualNavigation)
        // After the start the initial blank rule is closed again, exactly as for an agent load.
        assertFalse(session.initialNoWitnessProbeAllowed(ticket, session.epoch()))

        // The ready witness of that load is the normal one-time acceptance on the same ticket.
        assertTrue(session.acceptReady(ticket, doc))
        assertEquals(doc, session.acceptedDocumentId)
        assertTrue(session.committed)
    }

    @Test
    fun `the person's next navigation is a new manual document with no relaxed rule`() {
        val session = controlledLoad()
        val ticket = session.ticket
        var manualDocs = 0
        session.onManualDocument = { manualDocs += 1 }
        session.keepManualForControlledLoad()
        assertTrue(session.noteDocumentStart(url))
        assertTrue(session.acceptReady(ticket, doc))

        // A link or form the person follows on the reloaded page.
        assertTrue(session.noteDocumentStart("https://example.com/next"))

        assertEquals("a person's document gets a new ticket", ticket + 1, session.ticket)
        assertEquals(1, manualDocs)
        assertFalse("a manual document has no controlled provenance", session.controlledGet)
        assertNull("the old witness is cleared", session.acceptedDocumentId)
        assertFalse(session.unknownNavigation)
        assertFalse("no initial-blank leniency for a manual document", session.initialNoWitnessProbeAllowed(session.ticket, session.epoch()))
        assertFalse("the old ticket is no longer ready-able", session.acceptReady(ticket, doc))
    }

    @Test
    fun `a person navigating before the reload's document started replaces that load`() {
        val session = controlledLoad()
        val ticket = session.ticket
        var manualDocs = 0
        session.onManualDocument = { manualDocs += 1 }
        session.keepManualForControlledLoad()
        // The reload's own start happened; then, before ready, the person follows a link.
        assertTrue(session.noteDocumentStart(url))
        assertTrue(session.noteDocumentStart("https://example.com/clicked-before-ready"))

        assertEquals(ticket + 1, session.ticket)
        assertEquals(1, manualDocs)
        assertFalse(session.unknownNavigation)
        assertFalse("the late ready callback of the reload ticket is stale", session.acceptReady(ticket, doc))
    }

    @Test
    fun `an ordinary manual page and an agent page keep their old start rules`() {
        // Takeover on an already started page: every start is a person's new document.
        val manual = controlledLoad()
        assertTrue(manual.noteMainStart())
        manual.manualNavigation = true
        val ticket = manual.ticket
        assertFalse("an initial-blank rule never reopens for a manual page", manual.initialNoWitnessProbeAllowed(ticket, manual.epoch()))
        assertTrue(manual.noteDocumentStart("https://example.com/manual"))
        assertEquals(ticket + 1, manual.ticket)
        assertFalse(manual.controlledGet)

        // Agent page: a second start on the same ticket is still an unknown navigation.
        val agent = controlledLoad()
        assertTrue(agent.noteDocumentStart(url))
        assertFalse(agent.noteDocumentStart(url))
        assertTrue(agent.unknownNavigation)
    }

    @Test
    fun `the marker only arms for a still pending controlled load`() {
        val started = controlledLoad()
        assertTrue(started.noteMainStart())
        assertFalse("an already started load is not marked", started.keepManualForControlledLoad())
        assertTrue("but the page is still the person's", started.manualNavigation)
        val ticket = started.ticket
        assertTrue(started.noteDocumentStart(url))
        assertEquals("an unmarked manual start always takes a new ticket", ticket + 1, started.ticket)

        val uncontrolled = newSession().apply { beginControlledGet() }
        assertFalse("no controlled URL, no marker", uncontrolled.keepManualForControlledLoad())

        // A new controlled GET (e.g. a later Resume + reload) drops an older marker.
        val reused = controlledLoad()
        assertTrue(reused.keepManualForControlledLoad())
        reused.manualNavigation = false
        reused.beginControlledGet()
        reused.markControlledGet(url)
        reused.manualNavigation = true
        val next = reused.ticket
        assertTrue(reused.noteDocumentStart(url))
        assertEquals("a stale marker never continues a different ticket", next + 1, reused.ticket)
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
}
