package io.andchamber.app.ui.web

import org.junit.Assert.*
import org.junit.Test

class ComposerBoundsTest {
    private fun metrics(top: Double = 600.0, bottom: Double = 780.0) =
        ComposerMetrics(top, bottom, 400.0, 800.0, true)

    @Test fun `uses full visual width ratio for multiline composer and gap`() {
        val m = ComposerMeasurement(metrics(), 8, 2, 1000, 2000)
        val top = m.topPx(8, 2, 1000, 2000)
        assertEquals(1500f, top!!, .001f)
        assertEquals(530f, composerClearancePx(top, 2000, 30f)!!, .001f)
        val grown = m.copy(metrics = metrics(top = 400.0))
        assertEquals(1030f, composerClearancePx(grown.topPx(8, 2, 1000, 2000), 2000, 30f)!!, .001f)
    }

    @Test fun `rejects stale document binding and native size`() {
        val m = ComposerMeasurement(metrics(), 8, 2, 1000, 2000)
        assertNull(m.topPx(9, 2, 1000, 2000))
        assertNull(m.topPx(8, 3, 1000, 2000))
        assertNull(m.topPx(8, 2, 1000, 1000))
        assertNull(m.topPx(8, 2, 2000, 1000))
        assertFalse(matchesComposerDocument(8, 9, 2, 2))
    }

    @Test fun `nonfinite negative oversized and out of viewport values are invalid not zero`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0, 40000.0).forEach {
            assertFalse(metrics().copy(top = it).valid())
            assertFalse(metrics().copy(bottom = it).valid())
            assertFalse(metrics().copy(viewportWidth = it).valid())
            assertFalse(metrics().copy(viewportHeight = it).valid())
        }
        assertFalse(metrics(700.0, 600.0).valid())
        assertFalse(metrics(bottom = 801.0).valid())
        assertFalse(metrics().copy(viewportWidth = 0.0).valid())
        assertFalse(metrics().copy(visible = false).valid())
        assertTrue(ComposerMetrics(0.0, 0.0, 400.0, 800.0, false).valid())
        assertNull(composerClearancePx(Float.NaN, 2000, 30f))
        assertNull(composerClearancePx(null, 2000, 30f))
    }

    @Test fun `IME adjusted viewport and landscape use their measured content bounds`() {
        val ime = ComposerMeasurement(ComposerMetrics(220.0, 390.0, 400.0, 400.0, true), 8, 2, 1000, 1000)
        assertEquals(480f, composerClearancePx(ime.topPx(8, 2, 1000, 1000), 1000, 30f)!!, .001f)
        val landscape = ComposerMeasurement(ComposerMetrics(140.0, 300.0, 800.0, 320.0, true), 8, 2, 2000, 800)
        assertEquals(480f, composerClearancePx(landscape.topPx(8, 2, 2000, 800), 800, 30f)!!, .001f)
    }

    @Test fun `offscreen or hidden composer never becomes a valid top zero`() {
        val hidden = ComposerMeasurement(ComposerMetrics(0.0, 0.0, 400.0, 800.0, false), 8, 2, 1000, 2000)
        assertNull(hidden.topPx(8, 2, 1000, 2000))
        assertNull(hidden.copy(metrics = metrics()).topPx(8, 2, 0, 0))
        val tooTall = ComposerMeasurement(metrics(), 8, 2, 1000, 1000)
        assertNull(tooTall.topPx(8, 2, 1000, 1000))
    }

    @Test fun `actual landscape viewport rounding is accepted without falling back`() {
        val m = ComposerMeasurement(ComposerMetrics(48.0, 360.0, 863.2380981445312, 360.0, true),
            6, 0, 2264, 943)
        assertEquals("accepted", m.validityReason(6, 0, 2264, 943))
        assertEquals(125.88879f, m.topPx(6, 0, 2264, 943)!!, .01f)
        assertTrue(composerClearancePx(m.topPx(6, 0, 2264, 943), 943, 31.5f)!! > 800f)
        // More than one CSS pixel of projection overflow is not rounding.
        val wrong = m.copy(metrics = m.metrics.copy(bottom = 362.0, viewportHeight = 362.0))
        assertNull(wrong.topPx(6, 0, 2264, 943))
        assertEquals("mapped-bottom-outside-native", wrong.validityReason(6, 0, 2264, 943))
    }

    @Test fun `reload requires new document and a resize requires freshly measured native dimensions`() {
        val old = ComposerMeasurement(metrics(), 6, 0, 1000, 2000)
        assertEquals("stale-document", old.validityReason(7, 0, 1000, 2000))
        assertNull(old.topPx(7, 0, 1000, 2000))
        val reloaded = old.copy(consoleGeneration = 7)
        assertNotNull(reloaded.topPx(7, 0, 1000, 2000))
        assertNull(reloaded.topPx(7, 0, 2264, 943))
        val fresh = ComposerMeasurement(ComposerMetrics(48.0, 360.0, 863.2380981445312, 360.0, true), 7, 0, 2264, 943)
        assertNotNull(fresh.topPx(7, 0, 2264, 943))
    }
}
