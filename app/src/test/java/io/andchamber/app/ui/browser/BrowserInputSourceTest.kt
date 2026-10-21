package io.andchamber.app.ui.browser

import org.junit.Assert.*
import org.junit.Test

class BrowserInputSourceTest {
    @Test fun `old A cannot route events to selected B`() {
        val a = Any()
        val b = Any()
        assertTrue(browserInputSourceMatches(a, a))
        assertFalse(browserInputSourceMatches(a, b))
        assertTrue(browserInputSourceMatches(b, b))
    }

    @Test fun `retired or absent source never matches`() {
        assertFalse(browserInputSourceMatches(Any(), null))
        assertFalse(browserInputSourceMatches(null, Any()))
        assertFalse(browserInputSourceMatches(null, null))
    }

    @Test fun `equal values do not substitute for captured view identity`() {
        data class ViewStandIn(val handle: Int)
        val captured = ViewStandIn(1)
        val other = ViewStandIn(1)
        assertEquals(captured, other)
        assertFalse(browserInputSourceMatches(other, captured))
        assertTrue(browserInputSourceMatches(captured, captured))
    }
}
