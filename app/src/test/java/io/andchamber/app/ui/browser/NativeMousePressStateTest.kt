package io.andchamber.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMousePressStateTest {
    private val identity = MouseDocumentIdentity("view", 4L, "witness")

    @Test
    fun `a closed input gate rejects later events`() {
        val gate = NativeInputGate()
        assertTrue(gate.admit())
        gate.close()
        assertFalse(gate.admit())
    }

    @Test
    fun `idle construction failure does not retire`() {
        val state = NativeMousePressState(identity)
        assertFalse(state.beforePressDispatch(constructed = false, actionButtonApplied = false))
        assertEquals(MousePressOutcome.NoActivePress, state.interrupt("apply-failed", unavoidable = true))
        assertFalse(state.retired)
    }

    @Test
    fun `an active press interruption retires the original view and a new view starts clean`() {
        val state = NativeMousePressState(identity)
        assertTrue(state.beforePressDispatch(constructed = true, actionButtonApplied = true))
        val retired = state.interrupt("host-lost", unavoidable = true)
        assertEquals(MousePressOutcome.RetireRequired(identity, "host-lost", false), retired)
        assertFalse(state.accepts(identity))
        assertFalse(state.beforePressDispatch(constructed = true, actionButtonApplied = true))
        val fresh = NativeMousePressState(identity.copy(viewId = "fresh"))
        assertTrue(fresh.accepts(identity.copy(viewId = "fresh")))
        assertFalse(fresh.retired)
    }

    @Test
    fun `a delivered release ends the press without retiring`() {
        val state = NativeMousePressState(identity)
        assertTrue(state.beforePressDispatch(constructed = true, actionButtonApplied = true))
        state.releaseDelivered()
        assertEquals(MousePressOutcome.NoActivePress, state.interrupt("geometry", unavoidable = true))
        assertTrue(state.beforePressDispatch(constructed = true, actionButtonApplied = true))
        assertEquals(MousePressOutcome.DeferUntilRelease, state.interrupt("chrome", unavoidable = false))
        assertFalse(state.retired)
        assertTrue(state.activePress)
        assertFalse(state.retired)
    }
}
