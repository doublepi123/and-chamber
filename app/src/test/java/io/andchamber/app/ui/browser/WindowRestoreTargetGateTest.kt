package io.andchamber.app.ui.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure B5 target-gate rules. No Android View; the same functions the presenter calls. */
class WindowRestoreTargetGateTest {
    private val change = WindowChangeToken(transitionId = 4L, next = false)
    private val binding = WindowContentBinding(change, sourceHandle = 7, viewId = "view-1", afterLayoutRevision = 3L)

    /** Target: the small content area after collapse, in physical px. Height is the content height. */
    private fun target(height: Int = 252, width: Int = 819, left: Float = 198f, top: Float = 1464f, revision: Long = 4L) =
        WindowContentTarget(binding, layoutRevision = revision, windowLeftPx = left, windowTopPx = top, widthPx = width, heightPx = height)

    @Test
    fun `a target with a non-finite or non-positive rectangle is not well formed`() {
        assertTrue(target().isWellFormed())
        assertFalse(target(height = 0).isWellFormed())
        assertFalse(target(width = -1).isWellFormed())
        assertFalse(target(left = Float.NaN).isWellFormed())
        assertFalse(target(top = Float.POSITIVE_INFINITY).isWellFormed())
        assertFalse(target(revision = -1L).isWellFormed())
    }

    @Test
    fun `native frame must match the independent target within one physical px`() {
        val t = target()
        assertTrue(nativeMatchesTarget(198, 1464, 819, 252, t))
        assertTrue("one px of rounding is accepted", nativeMatchesTarget(199, 1463, 820, 253, t))
        assertFalse("two px away is refused", nativeMatchesTarget(200, 1464, 819, 252, t))
        assertFalse("the enlarged frame is refused", nativeMatchesTarget(42, 178, 996, 2117, t))
    }

    @Test
    fun `the native 819x252 with the visual 1024x1911_97 is refused`() {
        // The counter-example: a small native frame but the visual viewport still enlarged (projection ~1529).
        assertFalse(cssProjectionMatchesTarget(nativeWidth = 819, visualWidth = 1024f, visualHeight = 1911.9666f, target = target()))
    }

    @Test
    fun `the correct target projection 819 over 1024 by 315_0769 matches the 252 target`() {
        // s = 819/1024 = 0.7998; s * 315.0769 = 252.0 (+-2 px allowed).
        assertTrue(cssProjectionMatchesTarget(nativeWidth = 819, visualWidth = 1024f, visualHeight = 315.0769f, target = target()))
    }

    @Test
    fun `projection tolerance is two physical px and no more`() {
        // Scale 0.7998 with visual height 317.5 projects to ~254; 2 px away is accepted, 3 px is refused.
        assertTrue(cssProjectionMatchesTarget(819, 1024f, 317.5f, target()))
        assertFalse(cssProjectionMatchesTarget(819, 1024f, 319.5f, target()))
    }

    @Test
    fun `projection refuses non-finite, zero or negative inputs`() {
        val t = target()
        assertFalse(cssProjectionMatchesTarget(0, 1024f, 315f, t))
        assertFalse(cssProjectionMatchesTarget(819, 0f, 315f, t))
        assertFalse(cssProjectionMatchesTarget(819, Float.NaN, 315f, t))
        assertFalse(cssProjectionMatchesTarget(819, 1024f, Float.POSITIVE_INFINITY, t))
    }

    @Test
    fun `the binding is an opaque instance compared by identity, not by field equality`() {
        // Same fields, different instance: not the bound observation.
        val other = WindowContentBinding(change, sourceHandle = 7, viewId = "view-1", afterLayoutRevision = 3L)
        assertFalse("a different instance with equal fields is not the bound observation", binding === other)
        assertSame("a target carries the very binding instance it was made from", binding, target().binding)
    }
}
