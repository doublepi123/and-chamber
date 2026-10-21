package io.andchamber.app.ui.browser

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class NativeMouseSupportTest {
    @Test
    fun `missing setter throws and readback mismatch are unsupported without claiming a stub`() {
        val missing = NativeMouseSupport(UnusedSession()).applyFailure(NoSuchMethodException("setActionButton"))
        val access = NativeMouseSupport(UnusedSession()).applyFailure(IllegalAccessException("hidden"))
        val invoke = NativeMouseSupport(UnusedSession()).applyFailure(
            java.lang.reflect.UndeclaredThrowableException(InvocationTargetException(IllegalStateException("setter"))),
        )
        val mismatch = NativeMouseSupport(UnusedSession()).applyRoundTrip(MotionEvent.BUTTON_PRIMARY, 0)

        assertEquals("NO_SUCH_METHOD", (missing as MouseSupport.Unsupported).reason)
        assertEquals("ILLEGAL_ACCESS", (access as MouseSupport.Unsupported).reason)
        assertEquals("INVOCATION_FAILED", (invoke as MouseSupport.Unsupported).reason)
        assertEquals("ACTION_BUTTON_MISMATCH", (mismatch as MouseSupport.Unsupported).reason)
    }

    @Test
    fun `a matching readback is supported and a later mismatch clears the cached claim`() {
        val support = NativeMouseSupport(UnusedSession())

        assertTrue(support.applyRoundTrip(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_PRIMARY) is MouseSupport.Supported)
        assertTrue(support.probe() is MouseSupport.Supported)

        val failed = support.applyRoundTrip(MotionEvent.BUTTON_PRIMARY, 0)
        assertEquals("ACTION_BUTTON_MISMATCH", (failed as MouseSupport.Unsupported).reason)
        assertEquals("ACTION_BUTTON_MISMATCH", (support.probe() as MouseSupport.Unsupported).reason)
    }

    @Test
    fun `probe is cached and the injected session creates no input event`() {
        val session = CountingSession(MouseSupport.Supported)
        val support = NativeMouseSupport(session)

        assertTrue(support.probe() is MouseSupport.Supported)
        assertTrue(support.probe() is MouseSupport.Supported)

        assertEquals(1, session.probes)
        assertEquals(0, session.eventsCreated)
        assertEquals(0, session.applies)
    }

    @Test
    fun `the platform stub is not treated as supported`() {
        val result = NativeMouseSupport().probe()

        assertTrue(result is MouseSupport.Unsupported)
    }

    private class UnusedSession : MouseProbeSession {
        override fun probe(): MouseSupport = MouseSupport.Supported

        override fun apply(event: MotionEvent, button: Int): MouseSupport = error("apply must not run")
    }

    private class CountingSession(private val result: MouseSupport) : MouseProbeSession {
        var probes = 0
        var eventsCreated = 0
        var applies = 0

        override fun probe(): MouseSupport {
            probes += 1
            return result
        }

        override fun apply(event: MotionEvent, button: Int): MouseSupport {
            applies += 1
            eventsCreated += 1
            return result
        }
    }
}
