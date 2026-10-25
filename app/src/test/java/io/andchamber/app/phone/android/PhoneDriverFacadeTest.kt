package io.andchamber.app.phone.android

import io.andchamber.app.phone.adb.AdbDriverEndpoint
import io.andchamber.app.phone.adb.AdbPairingReceipt
import io.andchamber.app.phone.adb.AdbTransportSecurity
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone tool binding records `launch-<id>` with the controller before a launch may use the tool.
 * The adapter must report that same runtime id on connect; otherwise the driver's fixed id would
 * overwrite the recorded identity and the next launch would revoke instead of continuing.
 */
class PhoneDriverFacadeTest {
    private class FakeEngine(
        private val connection: PhoneConnection = PhoneConnection("conn-1", "phone-control"),
    ) : PhoneDriverEngine {
        var createdEngines = 0
        var connects = 0

        override fun capabilities(): PhoneCapabilities = PhoneCapabilities(observe = true)

        override suspend fun connect(deadlineMs: Long): PhoneConnection {
            connects += 1
            return connection
        }

        override suspend fun disconnect(deadlineMs: Long) = Unit

        override suspend fun observe(deadlineMs: Long): PhoneSnapshot = error("unused")

        override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage = error("unused")

        override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt = error("unused")

        override fun configure(host: String, connectPort: Int) = AdbDriverEndpoint(host, connectPort)

        override fun configurePairing(host: String, pairingPort: Int) = Unit

        override fun pair(pairingCode: CharArray, deadlineMs: Long): AdbPairingReceipt = error("unused")

        override fun cancelPairing() = Unit

        override fun hasPairingReceipt(host: String): Boolean = true

        override fun configuredEndpoint(): AdbDriverEndpoint? = null

        override val transportSecurity: AdbTransportSecurity = AdbTransportSecurity.TLS
    }

    private fun facade(
        engine: FakeEngine,
        epoch: String?,
        created: () -> Unit = {},
    ) = PhoneDriverFacade(
        identity = { error("identity must stay lazy") },
        runtimeIdentity = { epoch },
        // Unit tests run without an Android runtime, so the platform flag is explicit.
        platformSupported = true,
        engineFactory = {
            created()
            engine
        },
    )

    @Test
    fun `connect reports the server epoch, not the driver's fixed id`() = runTest {
        val engine = FakeEngine()
        val connection = facade(engine, "launch-7").connect(1_000)
        assertEquals("launch-7", connection.runtimeId)
        assertEquals("conn-1", connection.connectionId)
        assertEquals(1, engine.connects)
    }

    @Test
    fun `connect keeps the driver id when no launch is ready`() = runTest {
        val engine = FakeEngine()
        val connection = facade(engine, null).connect(1_000)
        assertEquals("phone-control", connection.runtimeId)
    }

    @Test
    fun `capabilities never create the engine or the keystore identity`() {
        var created = 0
        val engine = FakeEngine()
        val capabilities = facade(engine, "launch-1") { created += 1 }.capabilities()
        assertTrue(capabilities.observe)
        assertEquals(0, created)
    }

    @Test
    fun `disconnect never creates the engine or the keystore identity`() = runTest {
        var created = 0
        val facade = facade(FakeEngine(), "launch-1") { created += 1 }

        facade.disconnect(1_000)

        assertEquals(0, created)
    }

    @Test
    fun `runtime epoch is serverEpoch of the launch id`() {
        assertEquals("launch-42", PhoneDriverFacade.runtimeEpoch(42L))
        assertNull(PhoneDriverFacade.runtimeEpoch(null))
    }
}
