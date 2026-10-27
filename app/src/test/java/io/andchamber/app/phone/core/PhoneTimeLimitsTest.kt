package io.andchamber.app.phone.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one explicit permanent sentinel shared by native configuration, the core lease, the standing
 * scope and the tool status. A finite duration must never turn into "permanent" through overflow.
 */
class PhoneTimeLimitsTest {
    @Test
    fun `permanent is the single explicit sentinel`() {
        assertEquals(Long.MAX_VALUE, PhoneTimeLimits.PERMANENT)
        assertTrue(PhoneTimeLimits.isPermanent(PhoneTimeLimits.PERMANENT))
        assertFalse(PhoneTimeLimits.isPermanent(PhoneTimeLimits.PERMANENT - 1))
        assertFalse(PhoneTimeLimits.isPermanent(0L))
        assertFalse(PhoneTimeLimits.isPermanent(-1L))
    }

    @Test
    fun `a permanent duration maps to a permanent deadline without arithmetic`() {
        assertEquals(
            PhoneTimeLimits.PERMANENT,
            PhoneTimeLimits.expiresAt(0L, PhoneTimeLimits.PERMANENT),
        )
        assertEquals(
            PhoneTimeLimits.PERMANENT,
            PhoneTimeLimits.expiresAt(PhoneTimeLimits.PERMANENT - 1, PhoneTimeLimits.PERMANENT),
        )
    }

    @Test
    fun `a finite duration keeps a finite deadline`() {
        assertEquals(1_060L, PhoneTimeLimits.expiresAt(1_000L, 60L))
        assertEquals(60_000L, PhoneTimeLimits.expiresAt(0L, 60_000L))
        assertEquals(
            PhoneTimeLimits.PERMANENT - 1,
            PhoneTimeLimits.expiresAt(0L, PhoneTimeLimits.PERMANENT - 1),
        )
        assertFalse(
            PhoneTimeLimits.isPermanent(PhoneTimeLimits.expiresAt(0L, PhoneTimeLimits.PERMANENT - 1)),
        )
    }

    @Test
    fun `an overflowing finite duration is refused instead of becoming permanent`() {
        listOf(
            2L to (PhoneTimeLimits.PERMANENT - 1),
            1L to (PhoneTimeLimits.PERMANENT - 1),
            PhoneTimeLimits.PERMANENT - 1 to 2L,
            PhoneTimeLimits.PERMANENT - 1 to PhoneTimeLimits.PERMANENT - 1,
        ).forEach { (now, duration) ->
            try {
                PhoneTimeLimits.expiresAt(now, duration)
                throw AssertionError("now=$now duration=$duration must be refused, not wrapped")
            } catch (_: IllegalArgumentException) {
                // Refusing is the required outcome.
            }
        }
    }

    @Test
    fun `an invalid duration or negative now is refused`() {
        listOf(0L, -1L).forEach { duration ->
            try {
                PhoneTimeLimits.expiresAt(0L, duration)
                throw AssertionError("duration $duration must be refused")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
        try {
            PhoneTimeLimits.expiresAt(-1L, 1_000L)
            throw AssertionError("negative now must be refused")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `a permanent deadline never expires by time but a finite deadline does`() {
        assertFalse(PhoneTimeLimits.isExpired(PhoneTimeLimits.PERMANENT, PhoneTimeLimits.PERMANENT))
        assertFalse(PhoneTimeLimits.isExpired(Long.MAX_VALUE / 2, PhoneTimeLimits.PERMANENT))
        assertFalse(PhoneTimeLimits.isExpired(999L, 1_000L))
        assertTrue(PhoneTimeLimits.isExpired(1_000L, 1_000L))
        assertTrue(PhoneTimeLimits.isExpired(1_001L, 1_000L))
    }

    @Test
    fun `remaining is null for permanent and finite otherwise`() {
        assertNull(PhoneTimeLimits.remainingMs(0L, PhoneTimeLimits.PERMANENT))
        assertNull(PhoneTimeLimits.remainingMs(PhoneTimeLimits.PERMANENT - 1, PhoneTimeLimits.PERMANENT))
        assertEquals(400L, PhoneTimeLimits.remainingMs(600L, 1_000L))
        assertEquals(0L, PhoneTimeLimits.remainingMs(1_100L, 1_000L))
    }
}
