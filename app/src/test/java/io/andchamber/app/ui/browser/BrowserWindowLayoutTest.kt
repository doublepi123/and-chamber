package io.andchamber.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun `fixed viewport is drawn 1 to 1 at full size and fitted up or down otherwise`() {
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
        // A larger container enlarges the page until one side touches, still whole and centred.
        val large = fixedViewportTransform(4000, 4000, 1080, 1932)
        assertEquals(4000f / 1932f, large.scale, 0.0001f)
        assertEquals(0f, large.offsetY, 0.01f)
        assertEquals((4000 - 1080 * large.scale) / 2f, large.offsetX, 0.01f)
        assertDrawnInside(large, 4000, 4000, 1080, 1932)
        // Page point -> container point uses offset + scale * local.
        assertEquals(80f + 0.5f * 100f, wide.toContainerX(100f), 0.001f)
    }

    /** The drawn page is fully inside the container (never cropped) and never stretched. */
    private fun assertDrawnInside(t: ViewportTransform, cw: Int, ch: Int, vw: Int, vh: Int) {
        assertTrue("left ${t.offsetX}", t.offsetX >= 0f)
        assertTrue("top ${t.offsetY}", t.offsetY >= 0f)
        // Strict, in the same Float arithmetic the container uses: not even a fraction past the edge.
        assertTrue("right ${t.offsetX + vw * t.scale} > $cw", t.offsetX + vw * t.scale <= cw)
        assertTrue("bottom ${t.offsetY + vh * t.scale} > $ch", t.offsetY + vh * t.scale <= ch)
        // One side touches: no spare room on both axes at once.
        assertTrue("not fitted: ${t.offsetX}, ${t.offsetY}", t.offsetX < 0.51f || t.offsetY < 0.51f)
    }

    @Test fun `computer layout page is enlarged to fit a landscape tablet's full screen`() {
        // 1920 x 1080 page in a 3200 x 1738 px landscape tablet full-screen page area.
        val t = fixedViewportTransform(3200, 1738, 1920, 1080)
        assertEquals(1738f / 1080f, t.scale, 0.0001f)
        assertTrue("scale ${t.scale}", t.scale > 1.6f && t.scale < 1.62f)
        // Height fills; only the aspect difference is left as two even, narrow side bands.
        assertEquals(0f, t.offsetY, 0.01f)
        assertEquals((3200 - 1920 * t.scale) / 2f, t.offsetX, 0.01f)
        assertTrue("side band ${t.offsetX}", t.offsetX < 60f)
        assertDrawnInside(t, 3200, 1738, 1920, 1080)
    }

    @Test fun `computer layout page in a portrait tablet is bound by the width`() {
        // 2136 x 2803 px portrait tablet full-screen page area: width is the tighter side.
        val t = fixedViewportTransform(2136, 2803, 1920, 1080)
        assertEquals(2136f / 1920f, t.scale, 0.0001f)
        assertTrue(t.scale > 1f)
        assertEquals(0f, t.offsetX, 0.01f)
        assertEquals((2803 - 1080 * t.scale) / 2f, t.offsetY, 0.01f)
        assertDrawnInside(t, 2136, 2803, 1920, 1080)
    }

    @Test fun `phone layout page equal to the full screen area stays 1 to 1`() {
        // Phone layout's page is the full-screen page area itself: no enlarging, no bands.
        assertEquals(ViewportTransform(1f, 0f, 0f), fixedViewportTransform(2560, 1504, 2560, 1504))
        assertEquals(ViewportTransform(1f, 0f, 0f), fixedViewportTransform(945, 1690, 945, 1690))
    }

    @Test fun `enlarged and reduced transforms map page points back without drift`() {
        val cases = listOf(
            fixedViewportTransform(3200, 1738, 1920, 1080),
            fixedViewportTransform(2136, 2803, 1920, 1080),
            fixedViewportTransform(945, 1690, 1920, 1080),
            fixedViewportTransform(700, 966, 1080, 1932),
        )
        val points = listOf(0f to 0f, 960f to 540f, 1919f to 1079f, 123.5f to 77.25f)
        for (t in cases) {
            for ((x, y) in points) {
                // The platform's inverse of child scale + translation: (container - offset) / scale.
                assertEquals("$t x=$x", x, (t.toContainerX(x) - t.offsetX) / t.scale, 0.01f)
                assertEquals("$t y=$y", y, (t.toContainerY(y) - t.offsetY) / t.scale, 0.01f)
            }
        }
        // Centre of the page is the centre of the container on the banded axis.
        val pad = fixedViewportTransform(3200, 1738, 1920, 1080)
        assertEquals(1600f, pad.toContainerX(960f), 0.01f)
        assertEquals(869f, pad.toContainerY(540f), 0.01f)
    }

    // ---- Fitted scale never rounds the page past its container ---------------------------------------

    /**
     * The window rectangles the container reports, built the way FixedViewportContainer.buildSnapshot
     * does (Float page position and size; visible = the container's own rect cut to the page's outward
     * whole-pixel bounds), then resolved by the production capture check.
     */
    private fun captureFrameFor(
        t: ViewportTransform, locX: Int, locY: Int, cw: Int, ch: Int, vw: Int, vh: Int, rootW: Int, rootH: Int,
    ): CaptureFrame? {
        val pageLeft = locX + t.offsetX
        val pageTop = locY + t.offsetY
        val pageWidth = vw * t.scale
        val pageHeight = vh * t.scale
        val visible = CaptureRect(
            maxOf(locX, kotlin.math.floor(pageLeft).toInt()),
            maxOf(locY, kotlin.math.floor(pageTop).toInt()),
            minOf(locX + cw, kotlin.math.ceil(pageLeft + pageWidth).toInt()),
            minOf(locY + ch, kotlin.math.ceil(pageTop + pageHeight).toInt()),
        )
        val presentation = BrowserPresentationGeometry(1L, vw, vh, t.scale, pageLeft, pageTop, pageWidth, pageHeight, visible)
        return BrowserCaptureGeometry.captureFrame(presentation, rootW, rootH)
    }

    @Test fun `1718 px high full screen fits a 1920 x 1080 page without rounding past it`() {
        // The emulator's keyboard-closed full screen: page area 3200 x 1718 at window y 198.
        val t = fixedViewportTransform(3200, 1718, 1920, 1080)
        // Plain Float division rounds up: 1080 x (1718f / 1080) = 1718.0001.
        assertTrue(1080 * (1718f / 1080) > 1718f)
        assertTrue("scale ${t.scale}", 1080 * t.scale <= 1718f)
        assertEquals(1718f / 1080, t.scale, 1e-6f)
        assertEquals(Math.nextDown(1718f / 1080), t.scale, 0f)
        // Only the sub-pixel remainder is split, no padding.
        assertTrue("offsetY ${t.offsetY}", t.offsetY >= 0f && t.offsetY < 0.001f)
        assertDrawnInside(t, 3200, 1718, 1920, 1080)
        // The page's outward whole-pixel bounds are exactly the container's visible rect
        // (72..3128 x 198..1916), not one row past it: capture is allowed.
        val pageTop = 198 + t.offsetY
        assertEquals(198, kotlin.math.floor(pageTop).toInt())
        assertEquals(1916, kotlin.math.ceil(pageTop + 1080 * t.scale).toInt())
        assertEquals(72, kotlin.math.floor(t.offsetX).toInt())
        assertEquals(3128, kotlin.math.ceil(t.offsetX + 1920 * t.scale).toInt())
        val frame = captureFrameFor(t, 0, 198, 3200, 1718, 1920, 1080, 3200, 2136)
        assertNotNull("capture must accept the fitted full-screen page", frame)
        frame!!
        assertTrue("captured rect ${frame.rect}", frame.rect.top >= 198 && frame.rect.bottom <= 1916)
        assertTrue(frame.rect.left >= 72 && frame.rect.right <= 3128)
        // The old plain-division scale gives the probe's refused geometry (bottom 1917).
        val old = 1718f / 1080
        assertEquals(1917, kotlin.math.ceil(198 + 1080 * old).toInt())
        assertNull(captureFrameFor(ViewportTransform(old, (3200 - 1920 * old) / 2f, 0f), 0, 198, 3200, 1718, 1920, 1080, 3200, 2136))
    }

    @Test fun `fit scale stays inside its container on every axis size`() {
        for (viewport in listOf(1080, 1920, 768, 1366, 3840, 2160, 945, 1690)) {
            for (container in 1..4200) {
                val s = fitAxisScale(container, viewport)
                assertTrue("$container / $viewport -> $s", s > 0f && viewport * s <= container)
                // The largest such Float: one step up would cross the edge (exact product, as checked).
                val up = Math.nextUp(s)
                assertTrue("$container / $viewport not tight: $s", up.toDouble() * viewport > container)
            }
        }
    }

    @Test fun `fitted full screens are always capturable, centred band or not`() {
        for ((vw, vh) in listOf(1920 to 1080, 1366 to 768, 945 to 1690)) {
            for (ch in 300..2200 step 7) {
                for (cw in listOf(482, 964, 1080, 2136, 2560, 3200)) {
                    val t = fixedViewportTransform(cw, ch, vw, vh)
                    assertDrawnInside(t, cw, ch, vw, vh)
                    assertNotNull("$cw x $ch for $vw x $vh: $t",
                        captureFrameFor(t, 0, 198, cw, ch, vw, vh, cw, ch + 400))
                }
            }
        }
    }

    @Test fun `keyboard width fit uses the same exact bound`() {
        // Narrower window while the keyboard is open: the width bound may not round past it either.
        for (cw in listOf(482, 486, 964, 965, 2400)) {
            val t = keyboardViewportTransform(cw, 880, 1920, 1080, 3200, 1738, 300f)
            assertTrue("$cw: ${1920 * t.scale}", 1920 * t.scale <= cw)
            assertEquals(fitAxisScale(cw, 1920), t.scale, 0f)
        }
        // The keyboard-closed scale it keeps is the exact fitted one.
        assertEquals(fixedViewportTransform(3200, 1718, 1920, 1080).scale,
            keyboardViewportTransform(3200, 600, 1920, 1080, 3200, 1718, null).scale, 0f)
    }

    // ---- Keyboard open: same scale, cut and moved instead of shrunk --------------------------------

    @Test fun `keyboard keeps the landscape tablet computer layout scale instead of shrinking`() {
        val closed = fixedViewportTransform(3200, 1738, 1920, 1080)
        // Keyboard leaves about 880 px of page area; the old fit would draw it at 880 / 1080 = 0.815.
        val open = keyboardViewportTransform(3200, 880, 1920, 1080, 3200, 1738, anchorPageY = 300f)
        assertEquals(closed.scale, open.scale, 0f)
        assertTrue("scale ${open.scale}", open.scale > 1.6f)
        assertEquals(closed.offsetX, open.offsetX, 0.01f)
        // The tapped point sits in the middle of what is left above the keyboard.
        assertEquals(440f, open.toContainerY(300f), 0.01f)
        assertTrue(open.offsetY <= 0f)
    }

    @Test fun `keyboard anchor never moves an edge of the page inside the visible part`() {
        val spare = 880 - 1080 * (1738f / 1080f)
        // Near the top: the page's top stays at the top.
        assertEquals(0f, keyboardViewportTransform(3200, 880, 1920, 1080, 3200, 1738, 10f).offsetY, 0.01f)
        // Near the bottom: the page's bottom stays at the bottom, no empty band under it.
        val bottom = keyboardViewportTransform(3200, 880, 1920, 1080, 3200, 1738, 1075f)
        assertEquals(spare, bottom.offsetY, 0.01f)
        assertEquals(880f, bottom.toContainerY(1080f), 0.01f)
        // Out-of-page or unusable anchors are clamped or ignored, never NaN.
        assertEquals(spare, keyboardViewportTransform(3200, 880, 1920, 1080, 3200, 1738, 99_999f).offsetY, 0.01f)
        assertEquals(0f, keyboardViewportTransform(3200, 880, 1920, 1080, 3200, 1738, Float.NaN).offsetY, 0f)
    }

    @Test fun `keyboard without a tap shows the top of the page at the same scale`() {
        val open = keyboardViewportTransform(3200, 880, 1920, 1080, 3200, 1738, anchorPageY = null)
        assertEquals(1738f / 1080f, open.scale, 0.0001f)
        assertEquals(0f, open.offsetY, 0f)
    }

    @Test fun `keyboard height never changes the scale, through every animation height`() {
        val scales = (1738 downTo 300 step 37).map {
            keyboardViewportTransform(3200, it, 1920, 1080, 3200, 1738, 540f).scale
        }.toSet()
        assertEquals(setOf(1738f / 1080f), scales)
    }

    @Test fun `a page that still fits above the keyboard is centred, not moved`() {
        // Portrait tablet: width-bound 1.1125 scale, 1201 px page, 1500 px left above the keyboard.
        val open = keyboardViewportTransform(2136, 1500, 1920, 1080, 2136, 2803, anchorPageY = 1000f)
        assertEquals(2136f / 1920f, open.scale, 0.0001f)
        assertEquals((1500 - 1080 * open.scale) / 2f, open.offsetY, 0.01f)
        assertDrawnInside(open, 2136, 1500, 1920, 1080)
    }

    @Test fun `only a real width change can lower the keyboard scale, to fit the width`() {
        // Window became narrower than the drawn page: fit the width, never wider than the window.
        val open = keyboardViewportTransform(2400, 880, 1920, 1080, 3200, 1738, 300f)
        assertEquals(2400f / 1920f, open.scale, 0.0001f)
        assertEquals(0f, open.offsetX, 0.01f)
        assertTrue(open.toContainerX(1920f) <= 2400.01f)
    }

    @Test fun `phone layout keeps 1 to 1 with the keyboard open`() {
        // 360 x 740 dp phone at 2.625: page area 945 x 1690 px, keyboard leaves 976 px.
        val open = keyboardViewportTransform(945, 976, 945, 1690, 945, 1690, anchorPageY = 1000f)
        assertEquals(1f, open.scale, 0f)
        assertEquals(0f, open.offsetX, 0f)
        assertEquals(488f, open.toContainerY(1000f), 0.01f)
        assertEquals(976 - 1690f, keyboardViewportTransform(945, 976, 945, 1690, 945, 1690, 1690f).offsetY, 0.01f)
    }

    @Test fun `keyboard transform without a usable reference falls back to the fitted page`() {
        for ((rw, rh) in listOf(0 to 1738, 3200 to 0, -1 to -1)) {
            assertEquals(fixedViewportTransform(3200, 880, 1920, 1080),
                keyboardViewportTransform(3200, 880, 1920, 1080, rw, rh, 300f))
        }
        // Unknown page size or a zero container: same safe values as the fitted page, never NaN.
        assertEquals(fixedViewportTransform(3200, 880, 0, 0), keyboardViewportTransform(3200, 880, 0, 0, 3200, 1738, 1f))
        assertEquals(fixedViewportTransform(0, 0, 1920, 1080), keyboardViewportTransform(0, 0, 1920, 1080, 3200, 1738, 1f))
    }

    @Test fun `keyboard closed again gives back exactly the fitted presentation`() {
        // Reference equal to the container (keyboard closed) and a page that fits: same as the fit.
        for ((cw, ch) in listOf(3200 to 1738, 2136 to 2803, 945 to 1690)) {
            val (vw, vh) = if (cw == 945) 945 to 1690 else 1920 to 1080
            assertEquals(fixedViewportTransform(cw, ch, vw, vh), keyboardViewportTransform(cw, ch, vw, vh, cw, ch, 10f))
        }
    }

    @Test fun `keyboard transforms map page points back without drift`() {
        val cases = listOf(
            keyboardViewportTransform(3200, 880, 1920, 1080, 3200, 1738, 300f),
            keyboardViewportTransform(3200, 880, 1920, 1080, 3200, 1738, 1075f),
            keyboardViewportTransform(945, 976, 945, 1690, 945, 1690, 1400f),
            keyboardViewportTransform(2400, 880, 1920, 1080, 3200, 1738, 300f),
        )
        for (t in cases) {
            for ((x, y) in listOf(0f to 0f, 471.5f to 299.25f, 944f to 1079f)) {
                assertEquals("$t x=$x", x, (t.toContainerX(x) - t.offsetX) / t.scale, 0.01f)
                assertEquals("$t y=$y", y, (t.toContainerY(y) - t.offsetY) / t.scale, 0.01f)
            }
        }
    }

    @Test fun `keyboard reference is the keyboard-closed page area of the same presentation`() {
        // Landscape tablet, full screen: closed area is the window minus both bars.
        val w = 1155f
        val h = 770f
        val (vw, vh) = browserLayoutViewportDp(browserViewportDp(w, h), true, 1920, 1080)
        val closed = browserWindowLayout(w, h, 0f, 72f, vw, vh, fullRequested = true)
        assertEquals(w to h - 2 * BROWSER_BAR_HEIGHT, browserPageAreaDp(closed))
        // Keyboard open: compact bar only, so the open area is shorter than the closed reference.
        val open = browserWindowLayout(w, h, 330f, 72f, vw, vh, fullRequested = true)
        assertEquals(w to h - 330f - BROWSER_BAR_HEIGHT, browserPageAreaDp(open))
        // Folded or empty layouts have no page area.
        assertEquals(null, browserPageAreaDp(browserWindowLayout(360f, 110f, 0f, 72f, 360f, 644f, true)))
    }

    @Test fun `trusted address is the shown page and never a refused one`() {
        assertEquals("https://example.com/a", browserShownAddress("https://example.com/a", "https://example.org/"))
        assertEquals("https://example.org/", browserShownAddress(null, "https://example.org/"))
        assertEquals(null, browserShownAddress("http://example.com/", null))
        assertEquals(null, browserShownAddress("https://localhost/", null))
        assertEquals("example.com" to "/a?b", splitAddress("https://example.com/a?b"))
        assertEquals("example.com" to "", splitAddress("https://example.com/"))
    }

    @Test fun `computer layout keeps its own fixed size, whatever the phone does`() {
        val phonePx = 945 to 1690
        assertEquals(1920 to 1080, browserPageViewportPx(true, 1920, 1080, phonePx.first, phonePx.second))
        assertEquals(1366 to 768, browserPageViewportPx(true, 1366, 768, 1690, 945))
        // Phone layout, or an unusable desktop size, uses the phone viewport.
        assertEquals(phonePx, browserPageViewportPx(false, 1920, 1080, phonePx.first, phonePx.second))
        assertEquals(phonePx, browserPageViewportPx(true, 0, 1080, phonePx.first, phonePx.second))
    }

    @Test fun `computer layout windows keep the page proportions and stay usable on phones`() {
        for ((w, h) in listOf(320f to 640f, 360f to 740f, 412f to 892f)) {
            val phone = browserViewportDp(w, h)
            val (vpW, vpH) = browserLayoutViewportDp(phone, true, 1920, 1080)
            assertEquals(1920f / 1080f, vpW / vpH, 0.01f)
            for (full in listOf(false, true)) {
                val l = browserWindowLayout(w, h, 0f, composer, vpW, vpH, full)
                assertFalse("$w x $h full=$full", l.blocked)
                assertInside(l, w, if (l.presentation == BrowserPresentation.Float) h - composer else h)
                if (l.presentation == BrowserPresentation.Float) {
                    assertTrue(vpH * l.scale >= MIN_FLOAT_PAGE_HEIGHT - 0.01f)
                    assertEquals(vpH * l.scale + 2 * BROWSER_BAR_HEIGHT, l.window.height, 0.01f)
                }
            }
        }
        assertEquals(browserViewportDp(360f, 740f), browserLayoutViewportDp(browserViewportDp(360f, 740f), false, 1920, 1080))
    }

    @Test fun `a wide page is centred in a tall full screen, never cropped or stretched`() {
        // 1920 x 1080 page in a 945 x 1690 px full-screen page area.
        val t = fixedViewportTransform(945, 1690, 1920, 1080)
        assertEquals(945f / 1920f, t.scale, 0.0001f)
        assertEquals(0f, t.offsetX, 0.01f)
        assertEquals((1690 - 1080 * t.scale) / 2f, t.offsetY, 0.01f)
        assertTrue(t.offsetY + 1080 * t.scale <= 1690f)
    }

    @Test fun `only a phone screen change in phone layout invalidates the page geometry`() {
        assertTrue(browserViewportChangeInvalidates(false, 945 to 1690, false, 1690 to 945))
        assertFalse(browserViewportChangeInvalidates(false, 945 to 1690, false, 945 to 1690))
        // Agent resize and layout switches handle their own invalidation and reload.
        assertFalse(browserViewportChangeInvalidates(true, 1920 to 1080, true, 1366 to 768))
        assertFalse(browserViewportChangeInvalidates(false, 945 to 1690, true, 1920 to 1080))
        assertFalse(browserViewportChangeInvalidates(true, 1920 to 1080, false, 945 to 1690))
    }
}
