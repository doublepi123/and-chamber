package io.andchamber.app.ui.browser

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserMouseRouteTest {
    // A compile-time constant, so no Android runtime is needed to read it on the JVM.
    private val primary = MotionEvent.BUTTON_PRIMARY

    @Test
    fun `hover stays on the generic hover path`() {
        assertEquals(MouseRoute.GENERIC_HOVER, mouseRoute(MouseAction.HOVER, 0))
        assertEquals(MouseRoute.GENERIC_HOVER, mouseRoute(MouseAction.HOVER, primary))
    }

    @Test
    fun `a drag with the primary button held uses the mouse touch move`() {
        assertEquals(MouseRoute.TOUCH_MOVE_WITH_BUTTON, mouseRoute(MouseAction.DRAG, primary))
    }

    @Test
    fun `a drag without a held button falls back to the generic hover path`() {
        assertEquals(MouseRoute.GENERIC_HOVER, mouseRoute(MouseAction.DRAG, 0))
    }

    @Test
    fun `press release scroll and cancel keep their generic routes`() {
        assertEquals(MouseRoute.GENERIC_BUTTON_PRESS, mouseRoute(MouseAction.PRESS, 0))
        assertEquals(MouseRoute.GENERIC_BUTTON_PRESS, mouseRoute(MouseAction.PRESS, primary))
        assertEquals(MouseRoute.GENERIC_BUTTON_RELEASE, mouseRoute(MouseAction.RELEASE, primary))
        assertEquals(MouseRoute.GENERIC_BUTTON_RELEASE, mouseRoute(MouseAction.RELEASE, 0))
        assertEquals(MouseRoute.GENERIC_SCROLL, mouseRoute(MouseAction.SCROLL, 0))
        assertEquals(MouseRoute.GENERIC_SCROLL, mouseRoute(MouseAction.SCROLL, primary))
        assertEquals(MouseRoute.CANCEL, mouseRoute(MouseAction.CANCEL, 0))
        assertEquals(MouseRoute.CANCEL, mouseRoute(MouseAction.CANCEL, primary))
    }
}
