package io.andchamber.app.phone.android

import io.andchamber.app.phone.adb.AdbDeviceException
import io.andchamber.app.phone.adb.AdbDeviceFailure
import io.andchamber.app.phone.adb.AdbDriverEndpoint
import io.andchamber.app.phone.adb.AdbPairingReceipt
import io.andchamber.app.phone.adb.AdbTransportSecurity
import io.andchamber.app.phone.core.PhoneAgentAction
import io.andchamber.app.phone.core.PhoneAgentRequest
import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneCallResult
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneConnectionResult
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.core.PhoneGrantResult
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A runtime change makes the controller publish `DISCONNECTED` and drop the grant without calling
 * the driver. The real ADB transport can still be online, so the engine's `configure` refuses and
 * the UI (which only offers Disconnect while connected) cannot recover. `prepareForConnect` is the
 * explicit teardown the native connect path awaits before `configure` and `controller.connect`.
 *
 * These tests keep a fake transport state so "logically disconnected but physically live" is real,
 * not assumed, and they pin the ordering: a pending teardown blocks a new connect, and the finished
 * teardown never closes the transport that replaced it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhoneDriverFacadeReconnectTest {
    private class FakeTransport(val label: String) {
        var connected = false
        var disconnects = 0
    }

    private class FakeEngine : PhoneDriverEngine {
        var configureCalls = 0
        var connectCalls = 0
        var disconnectCalls = 0
        var transport: FakeTransport? = null
        var endpoint: AdbDriverEndpoint? = null
        val closed = mutableListOf<String>()

        /** Set to hold a disconnect open, like a teardown still closing the old transport. */
        var teardownGate: CompletableDeferred<Unit>? = null

        override fun capabilities(): PhoneCapabilities = PhoneCapabilities(observe = true)

        override suspend fun connect(deadlineMs: Long): PhoneConnection {
            val current = checkNotNull(transport) { "connect without a configured endpoint" }
            check(!current.connected) { "one device must not be connected twice" }
            current.connected = true
            connectCalls += 1
            return PhoneConnection(connectionId = "conn-$connectCalls", runtimeId = "phone-control")
        }

        override suspend fun disconnect(deadlineMs: Long) {
            disconnectCalls += 1
            teardownGate?.await()
            val current = transport ?: return
            if (current.connected) {
                current.connected = false
                current.disconnects += 1
                closed += current.label
            }
        }

        override suspend fun observe(deadlineMs: Long): PhoneSnapshot = error("observe must not run")

        override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage =
            error("capture must not run")

        override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt =
            error("no request may be replayed")

        /** Mirrors the real service: a still-connected device refuses a new endpoint. */
        override fun configure(host: String, connectPort: Int): AdbDriverEndpoint {
            if (transport?.connected == true) throw AdbDeviceException(AdbDeviceFailure.DRIVER)
            configureCalls += 1
            transport = FakeTransport("transport-$configureCalls")
            return AdbDriverEndpoint(host, connectPort).also { endpoint = it }
        }

        override fun configurePairing(host: String, pairingPort: Int) = Unit

        override fun pair(pairingCode: CharArray, deadlineMs: Long): AdbPairingReceipt = error("unused")

        override fun cancelPairing() = Unit

        override fun hasPairingReceipt(host: String): Boolean = true

        override fun configuredEndpoint(): AdbDriverEndpoint? = endpoint

        override val transportSecurity: AdbTransportSecurity = AdbTransportSecurity.TLS
    }

    private fun facade(
        engine: FakeEngine,
        epoch: () -> String? = { "launch-7" },
        created: () -> Unit = {},
    ) = PhoneDriverFacade(
        identity = { error("identity must stay lazy") },
        runtimeIdentity = epoch,
        platformSupported = true,
        engineFactory = {
            created()
            engine
        },
    )

    @Test
    fun `prepareForConnect closes the live transport so the explicit reconnect can configure again`() = runTest {
        val engine = FakeEngine()
        var epoch = "launch-7"
        val facade = facade(engine, epoch = { epoch })
        val controller = PhoneController(driver = facade, clock = { 1_000L })

        facade.configure("192.0.2.10", 5555)
        controller.enable()
        assertTrue(controller.connect(1_000) is PhoneConnectionResult.Connected)
        val granted = controller.grant("com.example.app", setOf(PhoneActionKind.TAP), 60_000)
        assertTrue(granted is PhoneGrantResult.Granted)
        val grantEpoch = controller.state.value.epoch
        val firstTransport = checkNotNull(engine.transport)
        assertTrue(firstTransport.connected)

        // The launch restarts: the controller drops the logical connection and the grant, but the
        // real transport is still online because the controller never talks to the driver here.
        epoch = "launch-8"
        controller.onRuntimeChanged(epoch)
        assertEquals(PhoneConnectionState.DISCONNECTED, controller.state.value.connection)
        assertNull(controller.state.value.connectionId)
        assertNull(controller.state.value.grant)
        assertNull(controller.state.value.observation)
        assertTrue("the physical transport is still live", firstTransport.connected)

        // The reported bug: configure refuses while that transport is connected.
        assertThrows(AdbDeviceException::class.java) {
            facade.configure("192.0.2.10", 5555)
        }
        assertEquals(1, engine.configureCalls)

        facade.prepareForConnect(1_000)
        assertFalse(firstTransport.connected)
        assertEquals(1, firstTransport.disconnects)
        // Closing the transport is not a connect: nothing reconnects on its own.
        assertEquals(1, engine.connectCalls)

        assertEquals(AdbDriverEndpoint("192.0.2.10", 5555), facade.configure("192.0.2.10", 5555))
        assertEquals(2, engine.configureCalls)
        val secondTransport = checkNotNull(engine.transport)
        assertNotSame(firstTransport, secondTransport)

        // The normal explicit connect then runs unchanged.
        assertTrue(controller.connect(1_000) is PhoneConnectionResult.Connected)
        assertEquals(2, engine.connectCalls)
        assertEquals(PhoneConnectionState.CONNECTED, controller.state.value.connection)
        assertEquals("conn-2", controller.state.value.connectionId)
        assertEquals("launch-8", controller.state.value.runtimeId)
        assertTrue(secondTransport.connected)

        // No grant, observation or old request comes back with the new connection.
        assertNull(controller.state.value.grant)
        assertNull(controller.state.value.observation)
        assertEquals(grantEpoch + 1, controller.state.value.epoch)
        val old = controller.execute(
            PhoneAgentRequest(
                requestId = "r-old",
                epoch = grantEpoch,
                sessionLabel = "session-a",
                action = PhoneAgentAction.Observe,
            ),
        )
        assertEquals(PhoneErrorCode.EPOCH_CHANGED, (old as PhoneCallResult.Refused).error)
        assertEquals(listOf("transport-1"), engine.closed)
    }

    @Test
    fun `a pending teardown refuses configure and connect and never closes the new transport`() = runTest {
        val engine = FakeEngine()
        val facade = facade(engine)

        facade.configure("192.0.2.10", 5555)
        facade.connect(1_000)
        val firstTransport = checkNotNull(engine.transport)
        assertTrue(firstTransport.connected)

        val gate = CompletableDeferred<Unit>()
        engine.teardownGate = gate
        val teardown = async { facade.prepareForConnect(1_000) }
        runCurrent()
        assertEquals(1, engine.disconnectCalls)
        assertFalse("teardown is still in flight", teardown.isCompleted)

        // While the old transport is still closing, neither a new endpoint nor a new connection
        // may start, so no late teardown can ever land on the replacement.
        assertThrows(PhoneDriverException::class.java) {
            facade.configure("192.0.2.11", 5555)
        }
        val blocked = async { runCatching { facade.connect(1_000) } }
        runCurrent()
        assertTrue(blocked.isCompleted)
        assertTrue(blocked.await().isFailure)
        assertEquals(1, engine.configureCalls)
        assertEquals(1, engine.connectCalls)
        assertTrue(firstTransport.connected)
        assertEquals(0, firstTransport.disconnects)

        gate.complete(Unit)
        teardown.await()
        assertFalse(firstTransport.connected)
        assertEquals(1, firstTransport.disconnects)

        // After the teardown completed, the explicit flow selects and connects the new endpoint.
        facade.configure("192.0.2.11", 5555)
        facade.connect(1_000)
        val secondTransport = checkNotNull(engine.transport)
        assertNotSame(firstTransport, secondTransport)
        assertTrue(secondTransport.connected)
        assertEquals(2, engine.connectCalls)
        runCurrent()
        assertEquals(listOf("transport-1"), engine.closed)
        assertTrue("the finished teardown must not close the replacement", secondTransport.connected)
    }

    @Test
    fun `a second prepare waits for the teardown already in flight instead of closing twice`() = runTest {
        val engine = FakeEngine()
        val facade = facade(engine)
        facade.configure("192.0.2.10", 5555)
        facade.connect(1_000)
        val transport = checkNotNull(engine.transport)

        val gate = CompletableDeferred<Unit>()
        engine.teardownGate = gate
        val first = async { facade.prepareForConnect(1_000) }
        runCurrent()
        val second = async { facade.prepareForConnect(1_000) }
        runCurrent()

        assertEquals(1, engine.disconnectCalls)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)

        gate.complete(Unit)
        first.await()
        second.await()

        assertEquals(1, engine.disconnectCalls)
        assertEquals(1, transport.disconnects)
        assertFalse(transport.connected)
    }

    @Test
    fun `prepareForConnect never creates the engine or the keystore identity`() = runTest {
        val engine = FakeEngine()
        var created = 0
        val facade = facade(engine) { created += 1 }

        facade.prepareForConnect(1_000)

        assertEquals(0, created)
        assertEquals(0, engine.disconnectCalls)
        assertEquals(0, engine.configureCalls)
    }

    @Test
    fun `connect still reports the current runtime epoch after a reconnect`() = runTest {
        val engine = FakeEngine()
        var epoch = "launch-7"
        val facade = facade(engine, epoch = { epoch })
        facade.configure("192.0.2.10", 5555)
        assertEquals("launch-7", facade.connect(1_000).runtimeId)

        epoch = "launch-8"
        facade.prepareForConnect(1_000)
        facade.configure("192.0.2.10", 5555)
        assertEquals("launch-8", facade.connect(1_000).runtimeId)
    }
}
