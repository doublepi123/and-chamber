package io.andchamber.app.phone.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing draft is the only place the transient port lives, and the six-digit code exists only for
 * the duration of one driver call. These tests pin the two properties the notification flow relies on:
 * a stale reply can never drive a newer attempt, and an abandoned draft expires.
 */
class PhonePairingSessionTest {
    private var now = 1_000L

    private fun session() = PhonePairingSession(clock = { now })

    @Test
    fun `port then code is the only accepted order`() {
        val session = session()
        val draft = session.begin("127.0.0.1")

        val early = session.acceptCode(draft.generation, "123456")
        assertEquals(PhonePairingSession.CodeResult.NotReady, early)

        val port = session.acceptPort(draft.generation, "41234")
        assertTrue(port is PhonePairingSession.PortResult.Accepted)
        assertEquals(41234, (port as PhonePairingSession.PortResult.Accepted).draft.pairingPort)

        val ready = session.acceptCode(draft.generation, "482 916")
        assertTrue(ready is PhonePairingSession.CodeResult.Ready)
        val code = (ready as PhonePairingSession.CodeResult.Ready).code
        assertEquals("482916", String(code))
        code.fill('\u0000')
    }

    @Test
    fun `stale generation is refused and never replaces the current draft`() {
        val session = session()
        val first = session.begin("127.0.0.1")
        session.acceptPort(first.generation, "40000")
        val second = session.begin("127.0.0.1")
        assertNotEquals(first.generation, second.generation)

        assertEquals(
            PhonePairingSession.PortResult.Stale,
            session.acceptPort(first.generation, "40001"),
        )
        assertEquals(
            PhonePairingSession.CodeResult.Stale,
            session.acceptCode(first.generation, "123456"),
        )
        // The new attempt is untouched and still waiting for its own port.
        assertEquals(PhonePairingSession.Stage.AWAITING_PORT, session.current()?.stage)
    }

    @Test
    fun `abandoned draft expires and is cleared`() {
        val session = session()
        val draft = session.begin("127.0.0.1")
        now += PhonePairingSession.DEFAULT_TTL_MS + 1
        assertNull(session.current())
        assertNull(session.expireIfNeeded())
        assertEquals(
            PhonePairingSession.PortResult.Expired,
            session.acceptPort(draft.generation, "40000"),
        )
    }

    @Test
    fun `cancel drops the draft without touching a newer one`() {
        val session = session()
        session.begin("127.0.0.1")
        session.cancel()
        assertNull(session.current())
    }

    @Test
    fun `invalid input is a fixed refusal, never a silent default`() {
        val session = session()
        val draft = session.begin("127.0.0.1")
        assertEquals(
            PhonePairingSession.PortResult.InvalidPort,
            session.acceptPort(draft.generation, "70000"),
        )
        assertEquals(
            PhonePairingSession.PortResult.InvalidPort,
            session.acceptPort(draft.generation, "abc"),
        )
        session.acceptPort(draft.generation, "41000")
        assertTrue(session.acceptCode(draft.generation, "12345") is PhonePairingSession.CodeResult.InvalidCode)
        assertTrue(session.acceptCode(draft.generation, "1234567") is PhonePairingSession.CodeResult.InvalidCode)
        assertEquals(41000, session.current()?.pairingPort)
    }
}
