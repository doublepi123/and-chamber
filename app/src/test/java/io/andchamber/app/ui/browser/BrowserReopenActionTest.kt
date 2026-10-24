package io.andchamber.app.ui.browser

import io.andchamber.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The window's single control action, and when Reopen can run. Projection only, no authority. */
class BrowserReopenActionTest {
    @Test fun `a page that needs a fresh load offers Reopen, never a Resume that would be refused`() {
        assertEquals(BrowserControlAction.Reopen, browserControlAction(agentOn = false, reopenNeeded = true, canResume = true))
        assertEquals(BrowserControlAction.Reopen, browserControlAction(agentOn = false, reopenNeeded = true, canResume = false))
    }

    @Test fun `agent in control keeps Take over, a plain takeover keeps Resume`() {
        assertEquals(BrowserControlAction.TakeOver, browserControlAction(agentOn = true, reopenNeeded = false, canResume = false))
        assertEquals(BrowserControlAction.Resume, browserControlAction(agentOn = false, reopenNeeded = false, canResume = true))
        assertEquals(BrowserControlAction.None, browserControlAction(agentOn = false, reopenNeeded = false, canResume = false))
    }

    @Test fun `a retired placeholder keeps its own Reopen and the bar adds none`() {
        assertTrue(browserReopenNeeded(requiresReopen = true, pageRetired = false))
        assertFalse(browserReopenNeeded(requiresReopen = true, pageRetired = true))
        assertFalse(browserReopenNeeded(requiresReopen = false, pageRetired = false))
    }

    @Test fun `Reopen is off without an address, during a press, when not presentable, or while running`() {
        val url = "https://example.com/"
        assertTrue(browserCanReopen(url, mousePressActive = false, eligible = true, reopening = false))
        assertFalse(browserCanReopen(null, mousePressActive = false, eligible = true, reopening = false))
        assertFalse(browserCanReopen(url, mousePressActive = true, eligible = true, reopening = false))
        // Folded/occluded windows and a background host are not eligible.
        assertFalse(browserCanReopen(url, mousePressActive = false, eligible = false, reopening = false))
        assertFalse(browserCanReopen(url, mousePressActive = false, eligible = true, reopening = true))
    }

    @Test fun `the status line matches the Reopen button even without page error text`() {
        assertEquals(
            R.string.browser_reopen_required,
            browserFloatStatusResource(null, false, true, false, false, false, requiresReopen = true),
        )
        // An agent in control, or a retired page, keeps its own line.
        assertEquals(
            R.string.browser_status_on,
            browserFloatStatusResource(null, true, true, false, false, false, requiresReopen = true),
        )
        assertEquals(
            R.string.browser_float_status_manual,
            browserFloatStatusResource(null, false, true, true, false, false, requiresReopen = true),
        )
    }

    @Test fun `a failed reopen always says so`() {
        assertEquals(R.string.browser_reopen_failed, browserReopenFailureResource(false, "no page"))
        assertEquals(R.string.browser_reopen_failed, browserReopenFailureResource(false, null))
        assertEquals(null, browserReopenFailureResource(true, null))
    }

    @Test fun `a read-only grant offers Take over and its own line until the person takes over`() {
        fun status(pageError: String? = null, readOnly: Boolean = true, unknown: Boolean = true, reopen: Boolean = false) =
            browserFloatStatusResource(pageError, false, true, false, false, false,
                requiresReopen = reopen, actionOutcomeUnknown = unknown, agentReadOnly = readOnly)
        assertEquals(BrowserControlAction.TakeOver,
            browserControlAction(agentOn = false, reopenNeeded = false, canResume = true, agentReadOnly = true))
        assertEquals(R.string.browser_action_outcome_unknown, status())
        assertEquals(R.string.browser_action_outcome_unknown, status(pageError = "Re-open this page before allowing control"))
        // A real page error and a document reopen still win.
        assertEquals(null, status(pageError = "The certificate isn't trusted"))
        assertEquals(R.string.browser_reopen_required, status(reopen = true))
        // After Take over the grant is gone: the manual line and Resume.
        assertEquals(R.string.browser_float_status_manual, status(readOnly = false))
        assertEquals(BrowserControlAction.Resume,
            browserControlAction(agentOn = false, reopenNeeded = false, canResume = true, agentReadOnly = false))
        // The agent in full control keeps Take over as before.
        assertEquals(BrowserControlAction.TakeOver,
            browserControlAction(agentOn = true, reopenNeeded = false, canResume = false, agentReadOnly = false))
    }

    @Test fun `an unconfirmed action without a grant keeps Resume, never the reopen wording`() {
        fun status(pageError: String? = null, agentOn: Boolean = false, retired: Boolean = false, reopen: Boolean = false) =
            browserFloatStatusResource(pageError, agentOn, true, retired, false, false,
                requiresReopen = reopen, actionOutcomeUnknown = true)
        // No grant is left (the person took over): the read-only notice would be untrue.
        assertEquals(R.string.browser_float_status_manual, status())
        // An older reopen notice still attached as the page error is never shown for it.
        assertEquals(R.string.browser_float_status_manual, status(pageError = "Re-open this page before allowing control"))
        // A real document problem still wins.
        assertEquals(R.string.browser_reopen_required, status(reopen = true))
        assertEquals(null, status(pageError = "The certificate isn't trusted"))
        assertEquals(R.string.browser_mouse_gesture_interrupted, status(pageError = "mouse-gesture-interrupted", retired = true))
        // Resume stays the action: the app does not replay anything, the person decides.
        assertEquals(BrowserControlAction.Resume, browserControlAction(agentOn = false, reopenNeeded = false, canResume = true))
        // Without the flag the ordinary manual line is unchanged.
        assertEquals(
            R.string.browser_float_status_manual,
            browserFloatStatusResource(null, false, true, false, false, false),
        )
    }
}
