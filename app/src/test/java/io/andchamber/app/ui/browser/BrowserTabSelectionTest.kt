package io.andchamber.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The deterministic neighbour rule used when the selected tab is closed. */
class BrowserTabSelectionTest {
    private val order = listOf("tab-a", "tab-b", "tab-c")

    @Test
    fun `a closed tab selects the next tab when one exists`() {
        assertEquals("tab-b", nextSelectedTabAfterClose(order, "tab-a"))
        assertEquals("tab-c", nextSelectedTabAfterClose(order, "tab-b"))
    }

    @Test
    fun `the last tab selects the previous tab`() {
        assertEquals("tab-b", nextSelectedTabAfterClose(order, "tab-c"))
    }

    @Test
    fun `the only tab leaves no selection`() {
        assertNull(nextSelectedTabAfterClose(listOf("tab-a"), "tab-a"))
    }

    @Test
    fun `an unknown closing tab leaves no selection`() {
        assertNull(nextSelectedTabAfterClose(order, "tab-missing"))
        assertNull(nextSelectedTabAfterClose(emptyList(), "tab-a"))
    }
}
