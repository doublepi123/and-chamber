package io.andchamber.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserDesktopViewportTest {
    @Test
    fun `defaults are 1920 by 1080 CSS pixels`() {
        assertEquals(1920, BrowserDesktopViewport.DEFAULT_WIDTH)
        assertEquals(1080, BrowserDesktopViewport.DEFAULT_HEIGHT)
        assertEquals(BrowserDesktopViewport.Size(1920, 1080), BrowserDesktopViewport.default)
        assertTrue(BrowserDesktopViewport.isValid(1920, 1080))
    }

    @Test
    fun `a missing pair is unspecified and a single value is invalid`() {
        assertEquals(BrowserDesktopViewport.Parse.Unspecified, BrowserDesktopViewport.parse(null, null))
        assertEquals(BrowserDesktopViewport.Parse.Unspecified, BrowserDesktopViewport.parse("", " "))
        assertTrue(BrowserDesktopViewport.parse("1600", null) is BrowserDesktopViewport.Parse.Invalid)
        assertTrue(BrowserDesktopViewport.parse(null, "900") is BrowserDesktopViewport.Parse.Invalid)
    }

    @Test
    fun `valid pairs are accepted at the inclusive bounds`() {
        assertEquals(
            BrowserDesktopViewport.Parse.Valid(BrowserDesktopViewport.Size(640, 360)),
            BrowserDesktopViewport.parse("640", "360"),
        )
        assertEquals(
            BrowserDesktopViewport.Parse.Valid(BrowserDesktopViewport.Size(3840, 2160)),
            BrowserDesktopViewport.parse("3840", "2160"),
        )
        assertEquals(
            BrowserDesktopViewport.Parse.Valid(BrowserDesktopViewport.Size(1600, 900)),
            BrowserDesktopViewport.parse("1600", "900"),
        )
    }

    @Test
    fun `non integers and out of range pairs are invalid and never clamped`() {
        assertTrue(BrowserDesktopViewport.parse("1920.0", "1080") is BrowserDesktopViewport.Parse.Invalid)
        assertTrue(BrowserDesktopViewport.parse("abc", "1080") is BrowserDesktopViewport.Parse.Invalid)
        assertTrue(BrowserDesktopViewport.parse("639", "1080") is BrowserDesktopViewport.Parse.Invalid)
        assertTrue(BrowserDesktopViewport.parse("1920", "359") is BrowserDesktopViewport.Parse.Invalid)
        assertTrue(BrowserDesktopViewport.parse("3841", "1080") is BrowserDesktopViewport.Parse.Invalid)
        assertTrue(BrowserDesktopViewport.parse("1920", "2161") is BrowserDesktopViewport.Parse.Invalid)
        assertFalse(BrowserDesktopViewport.isValid(0, 0))
    }
}
