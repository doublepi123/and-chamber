package io.andchamber.app.ui.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status panel's layout rule: short content stays centered, tall content scrolls as a whole. */
class StatusPanelPlacementTest {
    @Test fun `short content is centered in the available height and does not scroll`() {
        val placement = statusPanelPlacement(availableHeight = 1000, contentHeight = 400)
        assertEquals(1000, placement.height)
        assertEquals(300, placement.contentTop)
        assertFalse(placement.scrolls(1000))
    }

    @Test fun `content that exactly fits stays at the top without scrolling`() {
        val placement = statusPanelPlacement(availableHeight = 600, contentHeight = 600)
        assertEquals(600, placement.height)
        assertEquals(0, placement.contentTop)
        assertFalse(placement.scrolls(600))
    }

    @Test fun `tall content starts at the top and the whole panel, buttons included, scrolls`() {
        // e.g. a short landscape or split screen: title, body, detail and every button are measured
        // together, so the scroll range covers the last button too.
        val placement = statusPanelPlacement(availableHeight = 320, contentHeight = 780)
        assertEquals("the scroll box is as tall as all of the content", 780, placement.height)
        assertEquals("nothing is pushed above the top", 0, placement.contentTop)
        assertTrue(placement.scrolls(320))
    }

    @Test fun `a negative available height never produces a negative offset`() {
        val placement = statusPanelPlacement(availableHeight = -48, contentHeight = 200)
        assertEquals(200, placement.height)
        assertEquals(0, placement.contentTop)
        assertTrue(placement.scrolls(0))
    }
}
