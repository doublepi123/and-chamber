package io.andchamber.app.phone.android

import io.andchamber.app.phone.adb.AdbHostPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * Focused discovery policy: only this device's own numeric addresses become an endpoint, the target
 * is loopback, two live ports are ambiguous, a lost advertisement closes that endpoint, and every
 * session/epoch guard refuses late callbacks. Nothing here pairs, connects or grants.
 */
class PhoneDiscoveryPolicyTest {
    private val ownAddress = InetAddress.getByName("192.0.2.10")
    private val remoteAddress = InetAddress.getByName("198.51.100.20")
    private val policy = AdbHostPolicy(localAddresses = { listOf(ownAddress) })

    private fun ledger(ttlMs: Long = 60_000L) =
        PhoneDiscoveryLedger(hostPolicy = policy, sourceTtlMs = ttlMs)

    @Test
    fun `platform resolve edge dots preserve the exact permitted service type`() {
        PhoneDiscoveryKind.entries.forEach { kind ->
            listOf(kind.serviceType, ".${kind.serviceType}", "${kind.serviceType}.", ".${kind.serviceType}.")
                .forEach { type -> assertEquals(kind, PhoneDiscoveryKind.forServiceType(type)) }
        }
        assertEquals(null, PhoneDiscoveryKind.forServiceType("._adb._tcp"))
        assertEquals(null, PhoneDiscoveryKind.forServiceType(".._adb-tls-pairing._tcp"))
        assertEquals(null, PhoneDiscoveryKind.forServiceType("._adb-tls-pairing._tcp.example.com"))
    }

    private fun PhoneDiscoveryLedger.acceptPairing(
        revision: Long,
        name: String,
        port: Int,
        now: Long,
        address: InetAddress = ownAddress,
    ) = accept(
        kind = PhoneDiscoveryKind.PAIRING,
        revision = revision,
        serviceType = PhoneDiscoveryKind.PAIRING.serviceType,
        serviceName = name,
        addresses = listOf(address),
        port = port,
        nowMs = now,
    )

    @Test
    fun `own interface and loopback are accepted, remote peers are not`() {
        val ledger = ledger()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)

        assertEquals(PhoneDiscoveryStatus.Searching, ledger.acceptPairing(revision, "remote", 40000, 1, remoteAddress))

        val ready = ledger.acceptPairing(revision, "local", 41000, 2) as PhoneDiscoveryStatus.Ready
        assertEquals("127.0.0.1", ready.endpoint.host)
        assertEquals(41000, ready.endpoint.port)
        assertEquals("local", ready.endpoint.serviceName)
    }

    @Test
    fun `any local address in a multi-address record is enough`() {
        val ledger = ledger()
        val revision = ledger.begin(PhoneDiscoveryKind.CONNECT, nowMs = 0, timeoutMs = 10_000)
        val status =
            ledger.accept(
                kind = PhoneDiscoveryKind.CONNECT,
                revision = revision,
                serviceType = PhoneDiscoveryKind.CONNECT.serviceType,
                serviceName = "adb",
                addresses = listOf(remoteAddress, InetAddress.getByName("127.0.0.1")),
                port = 42000,
                nowMs = 1,
            )
        assertTrue(status is PhoneDiscoveryStatus.Ready)
    }

    @Test
    fun `two live ports are ambiguous and losing one resolves the other`() {
        val ledger = ledger()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        ledger.acceptPairing(revision, "adb-a", 40000, 1)
        val ambiguous = ledger.acceptPairing(revision, "adb-b", 40001, 2)
        assertEquals(PhoneDiscoveryStatus.Ambiguous(2), ambiguous)

        val resolved = ledger.lost(PhoneDiscoveryKind.PAIRING, revision, "adb-b", 3)
        assertEquals(40000, (resolved as PhoneDiscoveryStatus.Ready).endpoint.port)

        val lastLost = ledger.lost(PhoneDiscoveryKind.PAIRING, revision, "adb-a", 4)
        assertEquals(PhoneDiscoveryStatus.Searching, lastLost)
    }

    @Test
    fun `an unresolved ambiguity becomes a timeout at the bound`() {
        val ledger = ledger()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        ledger.acceptPairing(revision, "adb-a", 40000, 1)
        assertEquals(PhoneDiscoveryStatus.Ambiguous(2), ledger.acceptPairing(revision, "adb-b", 40001, 2))
        assertEquals(PhoneDiscoveryStatus.TimedOut, ledger.status(PhoneDiscoveryKind.PAIRING, 10_000))
    }

    @Test
    fun `a resolve in flight is dropped after its advertisement is lost`() {
        val ledger = ledger()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        val gate = PhoneDiscoveryResolveGate()
        val token = gate.found(PhoneDiscoveryKind.PAIRING, "adb-1")

        // onServiceFound started a resolve; onServiceLost arrives before its resolved callback.
        gate.lost(PhoneDiscoveryKind.PAIRING, "adb-1")
        if (gate.isCurrent(token)) {
            ledger.acceptPairing(revision, "adb-1", 40000, 1)
        }
        assertEquals(PhoneDiscoveryStatus.Searching, ledger.status(PhoneDiscoveryKind.PAIRING, 2))

        // A later found for the same platform name mints a new token and can resolve normally.
        val newer = gate.found(PhoneDiscoveryKind.PAIRING, "adb-1")
        assertFalse(gate.isCurrent(token))
        assertTrue(gate.isCurrent(newer))
        val ready = ledger.acceptPairing(revision, "adb-1", 40001, 3)
        assertEquals(40001, (ready as PhoneDiscoveryStatus.Ready).endpoint.port)
    }

    @Test
    fun `only a local port that is actually bound becomes Ready`() {
        val ledger = ledger()
        val gate = PhoneDiscoveryResolveGate()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        val token = gate.found(PhoneDiscoveryKind.PAIRING, "adb-1")
        val candidate =
            ledger.probeCandidate(
                kind = PhoneDiscoveryKind.PAIRING,
                revision = revision,
                token = token,
                serviceType = PhoneDiscoveryKind.PAIRING.serviceType,
                serviceName = "adb-1",
                addresses = listOf(ownAddress),
                port = 40000,
                nowMs = 1,
            )

        val occupied = ledger.probeAndAccept(candidate!!, gate, PhoneLocalPortProbe { PhonePortProbeResult.OCCUPIED }, 2)
        assertEquals(40000, (occupied as PhoneDiscoveryStatus.Ready).endpoint.port)
    }

    @Test
    fun `a free local port and a probe error never open Ready`() {
        val ledger = ledger()
        val gate = PhoneDiscoveryResolveGate()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        val token = gate.found(PhoneDiscoveryKind.PAIRING, "adb-1")
        fun candidate(port: Int) =
            ledger.probeCandidate(
                kind = PhoneDiscoveryKind.PAIRING,
                revision = revision,
                token = token,
                serviceType = PhoneDiscoveryKind.PAIRING.serviceType,
                serviceName = "adb-1",
                addresses = listOf(ownAddress),
                port = port,
                nowMs = 1,
            )!!

        assertEquals(
            PhoneDiscoveryStatus.Searching,
            ledger.probeAndAccept(candidate(40000), gate, PhoneLocalPortProbe { PhonePortProbeResult.FREE }, 2),
        )
        assertEquals(
            PhoneDiscoveryStatus.Searching,
            ledger.probeAndAccept(candidate(40000), gate, PhoneLocalPortProbe { PhonePortProbeResult.ERROR }, 3),
        )
    }

    @Test
    fun `a lost advertisement during the probe cannot be revived by a late OCCUPIED result`() {
        val ledger = ledger()
        val gate = PhoneDiscoveryResolveGate()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        val token = gate.found(PhoneDiscoveryKind.PAIRING, "adb-1")
        val candidate =
            ledger.probeCandidate(
                kind = PhoneDiscoveryKind.PAIRING,
                revision = revision,
                token = token,
                serviceType = PhoneDiscoveryKind.PAIRING.serviceType,
                serviceName = "adb-1",
                addresses = listOf(ownAddress),
                port = 40000,
                nowMs = 1,
            )!!

        val status =
            ledger.probeAndAccept(
                candidate = candidate,
                gate = gate,
                probe =
                    PhoneLocalPortProbe {
                        gate.lost(PhoneDiscoveryKind.PAIRING, "adb-1")
                        PhonePortProbeResult.OCCUPIED
                    },
                nowMs = 2,
            )
        assertEquals(PhoneDiscoveryStatus.Searching, status)
    }

    @Test
    fun `a new port for the same name never reuses the old Ready value`() {
        val ledger = ledger()
        val gate = PhoneDiscoveryResolveGate()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        val oldToken = gate.found(PhoneDiscoveryKind.PAIRING, "adb-1")
        fun candidate(token: PhoneResolveToken, port: Int, now: Long) =
            ledger.probeCandidate(
                kind = PhoneDiscoveryKind.PAIRING,
                revision = revision,
                token = token,
                serviceType = PhoneDiscoveryKind.PAIRING.serviceType,
                serviceName = "adb-1",
                addresses = listOf(ownAddress),
                port = port,
                nowMs = now,
            )!!

        val old = candidate(oldToken, 40000, 1)
        assertEquals(
            40000,
            (ledger.probeAndAccept(old, gate, PhoneLocalPortProbe { PhonePortProbeResult.OCCUPIED }, 2) as PhoneDiscoveryStatus.Ready).endpoint.port,
        )

        // New found for the same name: token changes and the old endpoint stops being Ready.
        val newToken = gate.found(PhoneDiscoveryKind.PAIRING, "adb-1")
        ledger.lost(PhoneDiscoveryKind.PAIRING, revision, "adb-1", 3)
        assertEquals(PhoneDiscoveryStatus.Searching, ledger.status(PhoneDiscoveryKind.PAIRING, 3))
        assertEquals(
            PhoneDiscoveryStatus.Searching,
            ledger.acceptProbed(old, PhonePortProbeResult.OCCUPIED, gate, 4),
        )

        val newer = candidate(newToken, 40001, 4)
        assertEquals(
            40001,
            (ledger.probeAndAccept(newer, gate, PhoneLocalPortProbe { PhonePortProbeResult.OCCUPIED }, 5) as PhoneDiscoveryStatus.Ready).endpoint.port,
        )
    }

    @Test
    fun `a new found for the same name invalidates an older in-flight resolve`() {
        val gate = PhoneDiscoveryResolveGate()
        val first = gate.found(PhoneDiscoveryKind.CONNECT, "adb-1")
        val second = gate.found(PhoneDiscoveryKind.CONNECT, "adb-1")
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `old kind or session resolve callbacks never affect a newer session`() {
        val gate = PhoneDiscoveryResolveGate()
        val pairing = gate.found(PhoneDiscoveryKind.PAIRING, "same-name")
        val connect = gate.found(PhoneDiscoveryKind.CONNECT, "same-name")
        gate.lost(PhoneDiscoveryKind.PAIRING, "same-name")
        assertFalse(gate.isCurrent(pairing))
        assertTrue("another kind with the same bookkeeping name is independent", gate.isCurrent(connect))

        // Session stop clears every token; an old callback cannot publish into a new session.
        gate.clear()
        assertFalse(gate.isCurrent(connect))
        val nextSession = gate.found(PhoneDiscoveryKind.CONNECT, "same-name")
        assertTrue(gate.isCurrent(nextSession))
    }

    @Test
    fun `multicast lock follows the API 30-33 legacy boundary`() {
        assertFalse(discoveryNeedsMulticastLock(29))
        assertTrue(discoveryNeedsMulticastLock(30))
        assertTrue(discoveryNeedsMulticastLock(32))
        assertTrue(discoveryNeedsMulticastLock(33))
        assertFalse(discoveryNeedsMulticastLock(34))
        assertFalse(discoveryNeedsMulticastLock(35))
    }

    @Test
    fun `service info callback needs both API 33 and T extension 7`() {
        assertFalse(serviceInfoCallbackSupported(33, 0))
        assertFalse(serviceInfoCallbackSupported(33, 6))
        assertTrue(serviceInfoCallbackSupported(33, 7))
        assertTrue(serviceInfoCallbackSupported(34, 7))
        // API level alone is not capability: an API 34 device can still report T extension 0.
        assertFalse(serviceInfoCallbackSupported(34, 0))
        assertFalse(serviceInfoCallbackSupported(32, 7))
    }

    @Test
    fun `two service names for the same port are one endpoint`() {
        val ledger = ledger()
        val revision = ledger.begin(PhoneDiscoveryKind.CONNECT, nowMs = 0, timeoutMs = 10_000)
        fun accept(name: String, port: Int, now: Long) =
            ledger.accept(
                kind = PhoneDiscoveryKind.CONNECT,
                revision = revision,
                serviceType = PhoneDiscoveryKind.CONNECT.serviceType,
                serviceName = name,
                addresses = listOf(ownAddress),
                port = port,
                nowMs = now,
            )
        accept("one", 40000, 1)
        val status = accept("two", 40000, 2)
        assertEquals(40000, (status as PhoneDiscoveryStatus.Ready).endpoint.port)
    }

    @Test
    fun `a source expires after its TTL and then the session times out`() {
        val ledger = ledger(ttlMs = 100L)
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 1_000)
        assertTrue(ledger.acceptPairing(revision, "adb", 41000, 1) is PhoneDiscoveryStatus.Ready)
        assertEquals(PhoneDiscoveryStatus.Searching, ledger.status(PhoneDiscoveryKind.PAIRING, 102))
        assertEquals(PhoneDiscoveryStatus.TimedOut, ledger.status(PhoneDiscoveryKind.PAIRING, 1_000))
    }

    @Test
    fun `a re-advertised service replaces its old port instead of reviving it`() {
        val ledger = ledger()
        val revision = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        assertTrue(ledger.acceptPairing(revision, "adb-1", 40000, 1) is PhoneDiscoveryStatus.Ready)
        val replaced = ledger.acceptPairing(revision, "adb-1", 40001, 2)
        assertEquals(40001, (replaced as PhoneDiscoveryStatus.Ready).endpoint.port)
    }

    @Test
    fun `a stopped or replaced session ignores its late callbacks`() {
        val ledger = ledger()
        val first = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        assertTrue(ledger.acceptPairing(first, "old", 40000, 1) is PhoneDiscoveryStatus.Ready)

        ledger.stop(PhoneDiscoveryKind.PAIRING)
        val second = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 2, timeoutMs = 10_000)
        assertNotEquals(first, second)

        assertEquals(PhoneDiscoveryStatus.Searching, ledger.acceptPairing(first, "late", 40001, 300))
        assertEquals(PhoneDiscoveryStatus.Searching, ledger.status(PhoneDiscoveryKind.PAIRING, 300))

        assertTrue(ledger.acceptPairing(second, "current", 41000, 400) is PhoneDiscoveryStatus.Ready)
    }

    @Test
    fun `one discovery failure does not end the other type`() {
        val ledger = ledger()
        val pairing = ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 10_000)
        val connect = ledger.begin(PhoneDiscoveryKind.CONNECT, nowMs = 0, timeoutMs = 10_000)
        ledger.fail(PhoneDiscoveryKind.PAIRING, pairing, nowMs = 1)

        assertEquals(PhoneDiscoveryStatus.Unavailable, ledger.status(PhoneDiscoveryKind.PAIRING, 2))
        assertEquals(PhoneDiscoveryStatus.Searching, ledger.status(PhoneDiscoveryKind.CONNECT, 2))

        val ready =
            ledger.accept(
                kind = PhoneDiscoveryKind.CONNECT,
                revision = connect,
                serviceType = PhoneDiscoveryKind.CONNECT.serviceType,
                serviceName = "adb",
                addresses = listOf(ownAddress),
                port = 43000,
                nowMs = 3,
            )
        assertTrue(ready is PhoneDiscoveryStatus.Ready)
    }

    @Test
    fun `a session without an endpoint waits until its fixed timeout`() {
        val ledger = ledger()
        ledger.begin(PhoneDiscoveryKind.PAIRING, nowMs = 0, timeoutMs = 5_000)
        assertEquals(PhoneDiscoveryStatus.Searching, ledger.status(PhoneDiscoveryKind.PAIRING, 4_999))
        assertEquals(PhoneDiscoveryStatus.TimedOut, ledger.status(PhoneDiscoveryKind.PAIRING, 5_000))
    }

    @Test
    fun `only an explicit pairing generation can auto connect once`() {
        val gate = PhoneAutoConnectGate()
        val endpoint =
            PhoneDiscoveredEndpoint(
                kind = PhoneDiscoveryKind.CONNECT,
                host = "127.0.0.1",
                port = 42000,
                serviceName = "adb",
                receivedAtMs = 0,
            )

        // Screen open or a receipt alone is not an intent.
        assertFalse(gate.shouldAttempt(generation = 1, endpoint = endpoint, enabled = true))

        gate.arm(1)
        assertFalse("no endpoint yet: wait for the bounded search", gate.shouldAttempt(1, null, true))
        assertTrue(gate.shouldAttempt(1, endpoint, enabled = true))

        gate.markAttempted(1)
        assertFalse("one attempt per explicit pairing", gate.shouldAttempt(1, endpoint, enabled = true))

        gate.disarm()
        assertFalse(gate.shouldAttempt(1, endpoint, enabled = true))
        assertFalse("disabled never connects", gate.shouldAttempt(2, endpoint, enabled = false))

        gate.arm(2)
        assertTrue("a new explicit pairing gets its own one attempt", gate.shouldAttempt(2, endpoint, enabled = true))
    }
}
