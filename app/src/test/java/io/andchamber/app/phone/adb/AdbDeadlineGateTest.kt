package io.andchamber.app.phone.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The completion/deadline gate has exactly one winner, decided against the real monotonic deadline.
 * A late watchdog thread must still be able to hard-close after a late-ending operation, while a
 * genuine pre-deadline completion must never be invalidated afterwards.
 */
class AdbDeadlineGateTest {
    private var now = 1_000L

    private fun gate(deadlineAtMs: Long = 1_100L) = DeadlineGate(deadlineAtMs) { now }

    @Test
    fun `a completion before the deadline wins and disarms a late watchdog`() {
        val gate = gate()

        assertTrue(gate.finish())
        now = 5_000L // the watchdog thread was scheduled late
        assertFalse("a real completion must not be followed by a hard close", gate.expire())
        assertFalse(gate.expired)
        assertTrue("the finally cancel still owns the interrupt", gate.finish())
    }

    @Test
    fun `a completion at or after the deadline cannot win`() {
        val gate = gate()

        now = 1_100L
        assertFalse(gate.finish())
        assertFalse("a repeated completion must not steal expiry", gate.finish())
        assertTrue(gate.deadlinePassed)
        assertTrue(gate.expire())
        assertTrue(gate.expired)
        assertFalse(gate.finish())
    }

    @Test
    fun `a late scheduled watchdog still owns the hard close`() {
        val gate = gate()

        now = 1_500L // past the deadline, and the watchdog thread has not run yet
        assertTrue(gate.deadlinePassed)
        assertFalse("the operation finished late and cannot claim success", gate.finish())
        assertTrue(gate.expire())
        assertTrue(gate.expired)
    }

    @Test
    fun `expiry first owns the outcome and is never downgraded`() {
        val gate = gate()

        now = 1_050L
        assertTrue(gate.expire())
        assertFalse(gate.finish())
        assertTrue(gate.expired)
        assertFalse(gate.expire())
    }

    @Test
    fun `the deadline comparison is strict`() {
        val gate = gate()

        now = 1_099L
        assertFalse(gate.deadlinePassed)
        assertTrue(gate.finish())
    }
}
