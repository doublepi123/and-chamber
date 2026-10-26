package io.andchamber.app.phone.android

import io.andchamber.app.phone.adb.AdbDriverEndpoint
import io.andchamber.app.phone.adb.AdbPairingReceipt
import io.andchamber.app.phone.adb.AdbTransportSecurity
import io.andchamber.app.phone.android.overlay.PhoneOverlayDriverBridge
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriverAction
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneGeometry
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production facade + overlay bridge: only an overlay-required operation's POLICY_DENIED may ask for
 * a native revoke, and only for the lease epoch/display owner captured before the call. Notification-
 * only keeps the original path, an Unknown dispatch is never downgraded to a loss/rollback, and a
 * stale operation cannot touch a newer grant.
 */
class PhoneDriverFacadeOverlayTest {
    private val binding =
        PhoneObservationBinding(
            observationId = "obs-1",
            generation = 1,
            packageName = "com.example.target",
            windowId = "win-1",
            rotation = 0,
            geometry = PhoneGeometry(1080, 2400, 0),
        )
    private val request = PhoneDispatchRequest(binding, PhoneDriverAction.Back)

    private class FakeEngine : PhoneDriverEngine {
        var observeFailure: PhoneDriverFailure? = null
        var captureFailure: PhoneDriverFailure? = null
        var dispatchReceipt: PhoneDispatchReceipt =
            PhoneDispatchReceipt.NotSent(PhoneDriverFailure.POLICY_DENIED)
        var onObserve: () -> Unit = {}
        var onCapture: () -> Unit = {}
        var onDispatch: () -> Unit = {}

        override fun capabilities(): PhoneCapabilities = PhoneCapabilities(observe = true)

        override suspend fun connect(deadlineMs: Long): PhoneConnection = error("unused")

        override suspend fun disconnect(deadlineMs: Long) = Unit

        override suspend fun observe(deadlineMs: Long): PhoneSnapshot {
            onObserve()
            observeFailure?.let { throw PhoneDriverException(it) }
            return PhoneSnapshot("com.example.target", "win-1", PhoneGeometry(1080, 2400, 0), emptyList(), emptyMap())
        }

        override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage {
            onCapture()
            captureFailure?.let { throw PhoneDriverException(it) }
            return PhoneImage(byteArrayOf(1, 2, 3), "image/png", 1080, 2400)
        }

        override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt {
            onDispatch()
            return dispatchReceipt
        }

        override fun configure(host: String, connectPort: Int): AdbDriverEndpoint =
            AdbDriverEndpoint(host, connectPort)

        override fun configurePairing(host: String, pairingPort: Int) = Unit

        override fun pair(pairingCode: CharArray, deadlineMs: Long): AdbPairingReceipt = error("unused")

        override fun cancelPairing() = Unit

        override fun hasPairingReceipt(host: String): Boolean = true

        override fun configuredEndpoint(): AdbDriverEndpoint? = null

        override val transportSecurity: AdbTransportSecurity = AdbTransportSecurity.TLS
    }

    private fun bridge(
        required: () -> Boolean,
        epoch: () -> Long? = { 7L },
        owner: () -> String? = { "grant-7:2" },
        lost: MutableList<Pair<Long, String?>>,
    ) = PhoneOverlayDriverBridge(
        overlayRequired = required,
        manager = { error("the facade must not ask the manager for a witness") },
        authorityEpoch = epoch,
        displayOwner = owner,
        onSafetyLost = { e, o -> lost += e to o },
        mainDispatcher = Dispatchers.Unconfined,
    )

    private fun facade(
        engine: FakeEngine,
        overlayBridge: PhoneOverlayDriverBridge?,
        created: () -> Unit = {},
    ) = PhoneDriverFacade(
        identity = { error("identity must stay lazy") },
        runtimeIdentity = { "launch-1" },
        platformSupported = true,
        pairingReceipts = { error("the overlay path must not touch pairing receipts") },
        overlayBridge = overlayBridge,
        engineFactory = {
            created()
            engine
        },
    )

    @Test
    fun `constructing the facade and reading capabilities touches neither the bridge nor the engine`() {
        var bridgeCalls = 0
        var engineCreated = 0
        val overlay =
            PhoneOverlayDriverBridge(
                overlayRequired = { bridgeCalls++; false },
                manager = { bridgeCalls++; null },
                authorityEpoch = { bridgeCalls++; 7L },
                displayOwner = { bridgeCalls++; "owner" },
                onSafetyLost = { _, _ -> bridgeCalls++ },
            )
        val facade =
            facade(FakeEngine(), overlay) {
                engineCreated += 1
            }

        facade.capabilities()

        assertEquals(0, bridgeCalls)
        assertEquals(0, engineCreated)
    }

    @Test
    fun `an overlay required observe refusal asks for a revoke of that exact owner`() = runTest {
        val lost = mutableListOf<Pair<Long, String?>>()
        val engine = FakeEngine().apply { observeFailure = PhoneDriverFailure.POLICY_DENIED }
        val facade = facade(engine, bridge(required = { true }, lost = lost))

        val failure = runCatching { facade.observe(1_000) }.exceptionOrNull()

        assertTrue(failure is PhoneDriverException)
        assertEquals(PhoneDriverFailure.POLICY_DENIED, (failure as PhoneDriverException).failure)
        assertEquals(listOf(7L to "grant-7:2"), lost)
    }

    @Test
    fun `an overlay required capture refusal asks for a revoke`() = runTest {
        val lost = mutableListOf<Pair<Long, String?>>()
        val engine = FakeEngine().apply { captureFailure = PhoneDriverFailure.POLICY_DENIED }
        val facade = facade(engine, bridge(required = { true }, lost = lost))

        assertTrue(runCatching { facade.capture(binding, 1_000) }.isFailure)

        assertEquals(listOf(7L to "grant-7:2"), lost)
    }

    @Test
    fun `a not sent policy dispatch is returned and requests a revoke`() = runTest {
        val lost = mutableListOf<Pair<Long, String?>>()
        val engine = FakeEngine().apply {
            dispatchReceipt = PhoneDispatchReceipt.NotSent(PhoneDriverFailure.POLICY_DENIED)
        }
        val facade = facade(engine, bridge(required = { true }, lost = lost))

        val receipt = facade.dispatch(request, 1_000)

        assertEquals(PhoneDispatchReceipt.NotSent(PhoneDriverFailure.POLICY_DENIED), receipt)
        assertEquals(listOf(7L to "grant-7:2"), lost)
    }

    @Test
    fun `an unknown dispatch never claims a loss or a rollback`() = runTest {
        val lost = mutableListOf<Pair<Long, String?>>()
        val engine = FakeEngine().apply {
            dispatchReceipt = PhoneDispatchReceipt.Unknown(PhoneDriverFailure.OUTCOME_UNKNOWN)
        }
        val facade = facade(engine, bridge(required = { true }, lost = lost))

        val receipt = facade.dispatch(request, 1_000)

        assertEquals(PhoneDispatchReceipt.Unknown(PhoneDriverFailure.OUTCOME_UNKNOWN), receipt)
        assertTrue(lost.isEmpty())

        // Even a policy-tagged Unknown stays Unknown; only an explicit NotSent is known not sent.
        engine.dispatchReceipt = PhoneDispatchReceipt.Unknown(PhoneDriverFailure.POLICY_DENIED)
        assertEquals(engine.dispatchReceipt, facade.dispatch(request, 1_000))
        assertTrue(lost.isEmpty())
    }

    @Test
    fun `a notification only refusal keeps the original path and never revokes`() = runTest {
        val lost = mutableListOf<Pair<Long, String?>>()
        val engine = FakeEngine().apply {
            observeFailure = PhoneDriverFailure.POLICY_DENIED
            dispatchReceipt = PhoneDispatchReceipt.NotSent(PhoneDriverFailure.POLICY_DENIED)
        }
        val facade = facade(engine, bridge(required = { false }, lost = lost))

        assertTrue(runCatching { facade.observe(1_000) }.isFailure)
        assertEquals(PhoneDispatchReceipt.NotSent(PhoneDriverFailure.POLICY_DENIED), facade.dispatch(request, 1_000))

        assertTrue(lost.isEmpty())
    }

    @Test
    fun `a failure of an operation started on an old epoch never revokes a newer grant`() = runTest {
        var epoch: Long? = 7L
        var owner: String? = "grant-7:2"
        val lost = mutableListOf<Pair<Long, String?>>()
        val engine = FakeEngine().apply {
            observeFailure = PhoneDriverFailure.POLICY_DENIED
            onObserve = {
                // A new grant happens while the old operation is in flight.
                epoch = 8L
                owner = "grant-8:3"
            }
        }
        val facade = facade(engine, bridge(required = { true }, epoch = { epoch }, owner = { owner }, lost = lost))

        assertTrue(runCatching { facade.observe(1_000) }.isFailure)

        assertTrue(lost.isEmpty())
        assertEquals(8L, epoch)
    }

    @Test
    fun `a stale owner with the same epoch also keeps the newer grant`() = runTest {
        var epoch: Long? = 7L
        var owner: String? = "grant-7:2"
        val lost = mutableListOf<Pair<Long, String?>>()
        val engine = FakeEngine().apply {
            captureFailure = PhoneDriverFailure.POLICY_DENIED
            onCapture = { owner = "grant-7:3" }
        }
        val facade = facade(engine, bridge(required = { true }, epoch = { epoch }, owner = { owner }, lost = lost))

        assertTrue(runCatching { facade.capture(binding, 1_000) }.isFailure)

        assertTrue(lost.isEmpty())
        assertEquals(7L, epoch)
    }

    @Test
    fun `a facade without a bridge keeps the baseline phone behavior`() = runTest {
        val engine = FakeEngine().apply { observeFailure = PhoneDriverFailure.POLICY_DENIED }
        val facade = facade(engine, overlayBridge = null)

        assertTrue(runCatching { facade.observe(1_000) }.isFailure)
        assertFalse(engine.observeFailure == null)
    }
}
