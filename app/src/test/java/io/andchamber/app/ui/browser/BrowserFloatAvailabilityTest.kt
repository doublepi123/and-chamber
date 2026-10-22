package io.andchamber.app.ui.browser

import io.andchamber.app.R
import org.junit.Assert.*
import org.junit.Test

class BrowserFloatAvailabilityTest {
    @Test fun `eligible host without current native ready document cannot allow`() {
        assertFalse(browserFloatCanAllow(true, false, "session-a"))
        assertTrue(browserFloatCanAllow(true, true, "session-a"))
        assertFalse(browserFloatCanAllow(false, true, "session-a"))
    }

    @Test fun `missing or blank owner cannot allow even with ready page`() {
        listOf(null, "", "  ").forEach { assertFalse(browserFloatCanAllow(true, true, it)) }
    }

    @Test fun `only known internal messages receive system copy`() {
        assertEquals(R.string.browser_reopen_required, browserFloatErrorResource("reopen-required"))
        assertEquals(R.string.browser_reopen_required, browserFloatErrorResource("Re-open this page before allowing control"))
        assertEquals(R.string.browser_mouse_unsupported, browserFloatErrorResource("mouse unsupported"))
    }

    @Test fun `unrecognized website errors retain their original display`() {
        listOf(null, "", "Website unavailable", "This website says mouse unsupported", "reopen-required: page detail",
            "mode-reopen-required: website detail")
            .forEach { assertNull(browserFloatErrorResource(it)) }
    }

    @Test fun `mode notice does not reuse control reopen copy or change ready page availability`() {
        assertEquals(R.string.browser_mode_reopen_required, browserFloatErrorResource("mode-reopen-required"))
        assertNotEquals(browserFloatErrorResource("reopen-required"), browserFloatErrorResource("mode-reopen-required"))
        assertEquals(R.string.browser_reopen_required, browserFloatErrorResource("Re-open this page before allowing control"))
        assertTrue(browserFloatCanAllow(true, true, "session-a"))
        assertFalse(browserFloatCanAllow(true, false, "session-a"))
    }
}
