package io.andchamber.app.ui.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The posted `shouldInterceptRequest` decision is the race boundary: the IO thread captured one
 * action intent, but by the time Main runs a newer action may own the session's one slot.
 *
 * These tests execute the same production gate the session's posted callback uses. They assert the
 * two forbidden side effects stay at zero for a stale/expired capture: no receipt is emitted into
 * the newer action's deferred, and the generic page-unknown path is not invoked (which would clear
 * the newer intent and revoke the page). A genuinely unarmed unsupported navigation still takes the
 * fail-closed path.
 */
class NavigationSlotGateTest {
    private class Intent(val name: String, val expired: Boolean = false)

    private class Recorder {
        var rejected = 0
        var unsupported = 0
    }

    private fun decide(captured: Any?, current: Any?, capturedValid: Boolean): Recorder {
        val recorder = Recorder()
        NavigationSlotGate.postedUnsupported(
            captured = captured,
            current = current,
            capturedValid = capturedValid,
            reject = { recorder.rejected += 1 },
            unsupported = { recorder.unsupported += 1 },
        )
        return recorder
    }

    @Test
    fun `a current valid capture rejects only that action`() {
        val action = Intent("A")
        val out = decide(action, action, capturedValid = true)
        assertEquals("the captured action receives its own rejection", 1, out.rejected)
        assertEquals("a rejection never uses the page-wide unknown path", 0, out.unsupported)
    }

    @Test
    fun `a capture already replaced by a newer action emits and marks nothing`() {
        val old = Intent("A")
        val newer = Intent("B")
        val out = decide(old, newer, capturedValid = true)
        assertEquals("the old action must not fill the newer receipt", 0, out.rejected)
        assertEquals("the old callback must not mark the page unknown", 0, out.unsupported)
    }

    @Test
    fun `an expired capture replaced by a newer action emits and marks nothing`() {
        val old = Intent("A", expired = true)
        val newer = Intent("B")
        // Ownership already moved to B, so validity of A is irrelevant; both values drop.
        assertEquals(0, decide(old, newer, capturedValid = false).rejected)
        assertEquals(0, decide(old, newer, capturedValid = false).unsupported)
        assertEquals(0, decide(old, newer, capturedValid = true).rejected)
        assertEquals(0, decide(old, newer, capturedValid = true).unsupported)
    }

    @Test
    fun `a capture that no longer owns the slot is dropped even when unexpired`() {
        val old = Intent("A")
        val out = decide(old, current = null, capturedValid = true)
        assertEquals(0, out.rejected)
        assertEquals(0, out.unsupported)
    }

    @Test
    fun `an owning but expired capture neither rejects nor marks the page unknown`() {
        val action = Intent("A", expired = true)
        val out = decide(action, action, capturedValid = false)
        assertEquals("an expired arm has no valid rejection", 0, out.rejected)
        assertEquals("its budget owns the outcome, not the page-wide unknown", 0, out.unsupported)
    }

    @Test
    fun `a genuinely unarmed unsupported navigation keeps the fail-closed path`() {
        val out = decide(captured = null, current = null, capturedValid = false)
        assertEquals("no action may receive a receipt", 0, out.rejected)
        assertEquals("an ordinary unsupported navigation still fails closed", 1, out.unsupported)
    }

    @Test
    fun `an unarmed capture never poisons a newer action`() {
        val newer = Intent("B")
        val out = decide(captured = null, current = newer, capturedValid = false)
        assertEquals("the newer action must not receive the old receipt", 0, out.rejected)
        assertEquals("the newer action must not be marked unknown", 0, out.unsupported)
    }
}
