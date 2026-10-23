package io.andchamber.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Small window / full screen layout in dp for common phone sizes. The page keeps one fixed
 * viewport; the window only scales it. Safe area = screen minus status/navigation bars.
 */
class BrowserWindowLayoutTest {
    private val composer = 136f

    private fun layout(
        w: Float, h: Float, ime: Float = 0f, composer: Float = this.composer,
        full: Boolean = false, placement: FloatPlacement = FloatPlacement(),
    ): Pair<BrowserWindowLayout, Pair<Float, Float>> {
        val vp = browserViewportDp(w, h)
        return browserWindowLayout(w, h, ime, composer, vp.first, vp.second, full, placement) to vp
    }

    private fun assertInside(l: BrowserWindowLayout, w: Float, bottom: Float) {
        val r = l.window
        assertTrue("left ${r.left}", r.left >= 0f)
        assertTrue("top ${r.top}", r.top >= 0f)
        assertTrue("right ${r.right} > $w", r.right <= w + 0.01f)
        assertTrue("bottom ${r.bottom} > $bottom", r.bottom <= bottom + 0.01f)
    }

    @Test fun `viewport is the full screen page area and ignores keyboard and composer`() {
        val (_, vp) = layout(360f, 740f)
        assertEquals(360f, vp.first, 0f)
        assertEquals(740f - 2 * BROWSER_BAR_HEIGHT, vp.second, 0f)
        // Same safe area with the keyboard open: the page size does not change.
        val (_, withIme) = layout(360f, 740f, ime = 300f)
        assertEquals(vp, withIme)
    }

    @Test fun `common phones get a small window with a real page, inside the chat area`() {
        for ((w, h) in listOf(320f to 568f, 320f to 640f, 360f to 640f, 360f to 740f, 412f to 892f, 412f to 915f)) {
            val (l, vp) = layout(w, h)
            assertEquals("$w x $h", BrowserPresentation.Float, l.presentation)
            assertFalse(l.blocked)
            val pageHeight = vp.second * l.scale
            assertTrue("$w x $h page $pageHeight", pageHeight >= MIN_FLOAT_PAGE_HEIGHT)
            // Never the old 96dp page: at least a third of the chat area shows the page.
            assertTrue("$w x $h page $pageHeight", pageHeight >= (h - composer) / 3f)
            assertTrue(l.window.width >= MIN_FLOAT_WIDTH)
            assertInside(l, w, h - composer)
            // Leaves part of the chat visible by default.
            assertTrue(l.window.height <= (h - composer) * MAX_FLOAT_HEIGHT_FRACTION + 0.5f)
        }
    }

    @Test fun `full screen fills the safe area and draws the page 1 to 1`() {
        val (l, vp) = layout(360f, 740f, full = true)
        assertEquals(BrowserPresentation.Full, l.presentation)
        assertEquals(WindowRect(0f, 0f, 360f, 740f), l.window)
        assertTrue(l.showBottomBar)
        // Page area = window - both bars = the fixed viewport exactly.
        assertEquals(vp.second, l.window.height - 2 * BROWSER_BAR_HEIGHT, 0f)
        assertEquals(vp.first, l.window.width, 0f)
    }

    @Test fun `full screen is kept while the keyboard is open and drops only the bottom bar`() {
        val (l, _) = layout(360f, 740f, ime = 320f, full = true)
        assertEquals(BrowserPresentation.Full, l.presentation)
        assertFalse(l.blocked)
        assertFalse(l.showBottomBar)
        assertEquals(420f, l.window.height, 0f)
    }

    @Test fun `landscape low height goes full screen instead of an unusable small window`() {
        for ((w, h) in listOf(740f to 312f, 892f to 360f, 640f to 288f)) {
            val (l, _) = layout(w, h, composer = 72f)
            assertEquals("$w x $h", BrowserPresentation.Full, l.presentation)
            assertTrue(l.autoFull)
            assertFalse(l.blocked)
            assertInside(l, w, h)
        }
    }

    @Test fun `the keyboard never folds or blocks the page, it goes full screen with a compact bar`() {
        for ((w, h, ime) in listOf(Triple(360f, 740f, 320f), Triple(320f, 568f, 260f), Triple(412f, 892f, 400f),
                Triple(740f, 312f, 180f))) {
            val (l, _) = layout(w, h, ime = ime, composer = 72f)
            assertEquals("$w x $h ime $ime", BrowserPresentation.Full, l.presentation)
            assertFalse(l.blocked)
            assertTrue(l.imeOpen)
            assertFalse(l.showBottomBar)
            assertTrue(l.window.height - BROWSER_BAR_HEIGHT >= MIN_FULL_PAGE_HEIGHT)
            assertInside(l, w, h - ime)
        }
    }

    @Test fun `a tall phone may keep a small window above the keyboard, still presented`() {
        val (l, _) = layout(412f, 892f, ime = 340f, composer = 72f)
        assertFalse(l.blocked)
        assertInside(l, 412f, 892f - 340f - if (l.presentation == BrowserPresentation.Float) 72f else 0f)
    }

    @Test fun `the fixed viewport does not move with the keyboard`() {
        val stable = StableViewport()
        val closed = stable.update(360f, 740f, imeOpen = false)
        // Keyboard animating in and open, including a frame where inset and height disagree.
        for (h in listOf(700f, 520f, 744f, 740f)) assertEquals(closed, stable.update(360f, h, imeOpen = true))
        assertEquals(closed, stable.update(360f, 740f, imeOpen = false))
        // Rotation is a real change.
        assertEquals(browserViewportDp(740f, 360f), stable.update(740f, 360f, imeOpen = false))
    }

    @Test fun `a tiny area folds even when full screen is asked`() {
        val (l, _) = layout(360f, 110f, full = true)
        assertEquals(BrowserPresentation.Collapsed, l.presentation)
        assertTrue(l.blocked)
    }

    @Test fun `resize is clamped between a readable page and the area`() {
        val (base, vp) = layout(360f, 740f)
        val grow = resizedFloatScale(base.scale, 10_000f, 10_000f, vp.first, vp.second, base.minScale, base.maxScale)
        assertEquals(base.maxScale, grow, 0f)
        val shrink = resizedFloatScale(base.scale, -10_000f, -10_000f, vp.first, vp.second, base.minScale, base.maxScale)
        assertEquals(base.minScale, shrink, 0f)
        for (scale in listOf(grow, shrink)) {
            val (l, _) = layout(360f, 740f, placement = FloatPlacement(scale = scale))
            assertInside(l, 360f, 740f - composer)
            assertTrue(vp.second * l.scale >= MIN_FLOAT_PAGE_HEIGHT - 0.01f)
        }
    }

    @Test fun `a diagonal resize does not count twice`() {
        val (base, vp) = layout(412f, 892f)
        val one = resizedFloatScale(base.scale, 40f, 0f, vp.first, vp.second, 0f, 1f)
        val both = resizedFloatScale(base.scale, 40f, 40f, vp.first, vp.second, 0f, 1f)
        assertEquals(one, both, 0.0001f)
        assertTrue(one > base.scale)
    }

    @Test fun `moving keeps the whole window inside, and the saved place survives a shorter area`() {
        val (l, _) = layout(360f, 740f)
        val far = movedFloatPlacement(l, FloatPlacement(), -10_000f, -10_000f)
        assertEquals(FLOAT_MARGIN, far.left!!, 0f)
        assertEquals(FLOAT_MARGIN, far.top!!, 0f)
        val back = movedFloatPlacement(l, far, 10_000f, 10_000f)
        assertEquals(l.maxLeft, back.left!!, 0f)
        assertEquals(l.maxTop, back.top!!, 0f)
        // A taller composer later: the same placement is clamped, never off screen.
        val (taller, _) = layout(360f, 740f, composer = 200f, placement = back)
        assertEquals(BrowserPresentation.Float, taller.presentation)
        assertInside(taller, 360f, 740f - 200f)
    }

    @Test fun `font scale and narrow screens keep 48dp buttons in the bars`() {
        val (l, _) = layout(320f, 568f)
        // Address (any width) + three 48dp buttons fit in the narrowest window.
        assertTrue(l.window.width >= 3 * 48f + 48f)
        assertEquals(BrowserPresentation.Float, l.presentation)
    }

    @Test fun `fixed viewport is drawn 1 to 1 at full size and scaled down otherwise`() {
        assertEquals(ViewportTransform(1f, 0f, 0f), fixedViewportTransform(1080, 1932, 1080, 1932))
        // A pixel short is scaled to fit, never cropped: the whole page stays visible.
        val short = fixedViewportTransform(1080, 1931, 1080, 1932)
        assertTrue(short.scale < 1f)
        assertTrue(1932 * short.scale <= 1931f)
        val small = fixedViewportTransform(540, 966, 1080, 1932)
        assertEquals(0.5f, small.scale, 0.0001f)
        assertEquals(0f, small.offsetX, 0f)
        // Wider than the scaled page: centred, never stretched.
        val wide = fixedViewportTransform(700, 966, 1080, 1932)
        assertEquals(0.5f, wide.scale, 0.0001f)
        assertEquals((700 - 540) / 2f, wide.offsetX, 0.01f)
        assertTrue(fixedViewportTransform(4000, 4000, 1080, 1932).scale <= 1f)
        // Page point -> container point uses offset + scale * local.
        assertEquals(80f + 0.5f * 100f, wide.toContainerX(100f), 0.001f)
    }

    @Test fun `trusted address is the shown page and never a refused one`() {
        assertEquals("https://example.com/a", browserShownAddress("https://example.com/a", "https://example.org/"))
        assertEquals("https://example.org/", browserShownAddress(null, "https://example.org/"))
        assertEquals(null, browserShownAddress("http://example.com/", null))
        assertEquals(null, browserShownAddress("https://localhost/", null))
        assertEquals("example.com" to "/a?b", splitAddress("https://example.com/a?b"))
        assertEquals("example.com" to "", splitAddress("https://example.com/"))
    }
}
