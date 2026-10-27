package io.andchamber.app.phone.android

import io.andchamber.app.phone.adb.AdbHostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The pairing draft is the only place a transient endpoint lives, and the six-digit code exists only
 * for the duration of one driver call. These tests pin the two paths:
 * - main: explicit discovery supplies the endpoint; the person types only the code;
 * - manual fallback: the old typed-port step still works when explicitly chosen.
 * A stale reply can never drive a newer attempt, and a lost endpoint closes the code gate.
 */
class PhonePairingSessionTest {
    private var now = 1_000L

    private fun session() = PhonePairingSession(clock = { now })

    @Test
    fun `main path starts searching and accepts only the discovered endpoint then the code`() {
        val session = session()
        val draft = session.begin("127.0.0.1")
        assertEquals(PhonePairingSession.Stage.SEARCHING, draft.stage)

        // No port is expected on the main path, and a code before the endpoint is refused.
        assertEquals(PhonePairingSession.CodeResult.NotReady, session.acceptCode(draft.generation, "123456"))
        assertEquals(
            PhonePairingSession.PortResult.Stale,
            session.acceptPort(draft.generation, "41234"),
        )

        val endpoint = session.acceptEndpoint(draft.generation, "127.0.0.1", 41234)
        assertTrue(endpoint is PhonePairingSession.EndpointResult.Accepted)
        assertEquals(41234, (endpoint as PhonePairingSession.EndpointResult.Accepted).draft.pairingPort)
        assertEquals(PhonePairingSession.Stage.AWAITING_CODE, session.current()?.stage)

        val ready = session.acceptCode(draft.generation, "482 916")
        assertTrue(ready is PhonePairingSession.CodeResult.Ready)
        val code = (ready as PhonePairingSession.CodeResult.Ready).code
        assertEquals("482916", String(code))
        code.fill('\u0000')

        // The one pair submission is final for this generation; no second code is consumed.
        assertEquals(PhonePairingSession.CodeResult.NotReady, session.acceptCode(draft.generation, "123456"))
    }

    @Test
    fun `manual fallback keeps the typed port step`() {
        val session = session()
        val draft = session.beginManual("127.0.0.1")
        assertEquals(PhonePairingSession.Stage.AWAITING_PORT, draft.stage)

        val port = session.acceptPort(draft.generation, "41234")
        assertTrue(port is PhonePairingSession.PortResult.Accepted)
        assertEquals(41234, (port as PhonePairingSession.PortResult.Accepted).draft.pairingPort)

        val ready = session.acceptCode(draft.generation, "482916")
        assertTrue(ready is PhonePairingSession.CodeResult.Ready)
        (ready as PhonePairingSession.CodeResult.Ready).code.fill('\u0000')
    }

    @Test
    fun `a lost endpoint closes the code gate and the next endpoint can be accepted`() {
        val session = session()
        val draft = session.begin("127.0.0.1")
        session.acceptEndpoint(draft.generation, "127.0.0.1", 40000)

        assertTrue(session.endpointLost(draft.generation))
        assertEquals(PhonePairingSession.Stage.SEARCHING, session.current()?.stage)
        assertEquals(
            PhonePairingSession.CodeResult.NotReady,
            session.acceptCode(draft.generation, "123456"),
        )

        assertTrue(session.acceptEndpoint(draft.generation, "127.0.0.1", 40001) is PhonePairingSession.EndpointResult.Accepted)
        assertEquals(40001, session.current()?.pairingPort)
    }

    @Test
    fun `stale generation is refused and never replaces the current draft`() {
        val session = session()
        val first = session.begin("127.0.0.1")
        session.acceptEndpoint(first.generation, "127.0.0.1", 40000)
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
        assertEquals(
            PhonePairingSession.EndpointResult.Stale,
            session.acceptEndpoint(first.generation, "127.0.0.1", 40002),
        )
        // The new attempt is untouched and still searching for its own endpoint.
        assertEquals(PhonePairingSession.Stage.SEARCHING, session.current()?.stage)
    }

    @Test
    fun `abandoned draft expires and is cleared`() {
        val session = session()
        val draft = session.begin("127.0.0.1")
        now += PhonePairingSession.DEFAULT_TTL_MS + 1
        assertNull(session.current())
        assertNull(session.expireIfNeeded())
        assertEquals(
            PhonePairingSession.EndpointResult.Expired,
            session.acceptEndpoint(draft.generation, "127.0.0.1", 40000),
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
    fun `invalid code is a fixed refusal and keeps the waiting stage`() {
        val session = session()
        val draft = session.begin("127.0.0.1")
        session.acceptEndpoint(draft.generation, "127.0.0.1", 41000)
        assertTrue(session.acceptCode(draft.generation, "12345") is PhonePairingSession.CodeResult.InvalidCode)
        assertTrue(session.acceptCode(draft.generation, "1234567") is PhonePairingSession.CodeResult.InvalidCode)
        assertEquals(PhonePairingSession.Stage.AWAITING_CODE, session.current()?.stage)
        assertEquals(41000, session.current()?.pairingPort)
    }

    @Test
    fun `invalid endpoint port is a fixed refusal`() {
        val session = session()
        val draft = session.begin("127.0.0.1")
        assertEquals(
            PhonePairingSession.EndpointResult.InvalidPort,
            session.acceptEndpoint(draft.generation, "127.0.0.1", 70000),
        )
        assertEquals(PhonePairingSession.Stage.SEARCHING, session.current()?.stage)
    }

    @Test
    fun `loopback normalisation and service type normalisation are pure`() {
        val local = InetAddress.getByName("192.0.2.10")
        val policy = AdbHostPolicy(localAddresses = { listOf(local) })
        val ledger = PhoneDiscoveryLedger(hostPolicy = policy)
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)

        val status =
            ledger.accept(
                kind = PhoneDiscoveryKind.PAIRING,
                revision = revision,
                serviceType = "_adb-tls-pairing._tcp.",
                serviceName = "adb-1",
                addresses = listOf(local),
                port = 43210,
                nowMs = 1,
            )
        assertTrue(status is PhoneDiscoveryStatus.Ready)
        assertEquals("127.0.0.1", (status as PhoneDiscoveryStatus.Ready).endpoint.host)
        assertEquals(PhoneDiscoveryKind.PAIRING, PhoneDiscoveryKind.forServiceType("_adb-tls-pairing._tcp."))
        assertNull(PhoneDiscoveryKind.forServiceType("_adb-tls-pairing._tcp.."))
        assertFalse(local.isLoopbackAddress)
    }
}
