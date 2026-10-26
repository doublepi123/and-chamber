package io.andchamber.app.phone.android

import android.os.Build
import android.os.SystemClock
import io.andchamber.app.phone.adb.AdbDriverEndpoint
import io.andchamber.app.phone.adb.AdbIdentity
import io.andchamber.app.phone.adb.AdbPairingReceipt
import io.andchamber.app.phone.adb.AdbPairingReceiptStore
import io.andchamber.app.phone.adb.AdbPhoneDriver
import io.andchamber.app.phone.adb.AdbPhoneDriverService
import io.andchamber.app.phone.adb.AdbTransportSecurity
import io.andchamber.app.phone.android.overlay.PhoneOverlayDriverBridge
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriver
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import io.andchamber.app.phone.tool.PhoneToolProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The pairing/endpoint surface of the real driver service, behind an interface so the adapter's
 * identity handling can be tested without Android or LibADB.
 */
internal interface PhoneDriverEngine : PhoneDriver {
    fun configure(host: String, connectPort: Int): AdbDriverEndpoint

    fun configurePairing(host: String, pairingPort: Int)

    fun pair(pairingCode: CharArray, deadlineMs: Long): AdbPairingReceipt

    fun cancelPairing()

    fun hasPairingReceipt(host: String): Boolean

    fun configuredEndpoint(): AdbDriverEndpoint?

    val transportSecurity: AdbTransportSecurity
}

/** Production engine: the real service, unchanged, with explicit forwarding. */
private class AdbServiceEngine(
    private val service: AdbPhoneDriverService,
) : PhoneDriverEngine {
    override fun capabilities(): PhoneCapabilities = service.capabilities()

    override suspend fun connect(deadlineMs: Long): PhoneConnection = service.connect(deadlineMs)

    override suspend fun disconnect(deadlineMs: Long) = service.disconnect(deadlineMs)

    override suspend fun observe(deadlineMs: Long): PhoneSnapshot = service.observe(deadlineMs)

    override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage =
        service.capture(binding, deadlineMs)

    override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt =
        service.dispatch(request, deadlineMs)

    override fun configure(host: String, connectPort: Int): AdbDriverEndpoint = service.configure(host, connectPort)

    override fun configurePairing(host: String, pairingPort: Int) = service.configurePairing(host, pairingPort)

    override fun pair(pairingCode: CharArray, deadlineMs: Long): AdbPairingReceipt =
        service.pair(pairingCode, deadlineMs)

    override fun cancelPairing() = service.cancelPairing()

    override fun hasPairingReceipt(host: String): Boolean = service.hasPairingReceipt(host)

    override fun configuredEndpoint(): AdbDriverEndpoint? = service.configuredEndpoint()

    override val transportSecurity: AdbTransportSecurity
        get() = service.transportSecurity
}

/**
 * The [PhoneDriver] the controller holds, plus the small configuration surface the coordinator uses.
 *
 * The real [AdbPhoneDriverService] (and therefore the keystore identity and the LibADB transport) is
 * created on first use, so app start and the UI never touch the keystore. Capabilities are static and
 * can be reported while no driver exists.
 *
 * **Runtime identity.** The phone tool binding records `PhoneToolProtocol.serverEpoch(launchId)` with
 * the controller before a launch may use the tool, and its contract requires the connection to report
 * that same runtime id. The driver service has one fixed id, so this adapter replaces the id on the
 * connection with the current server epoch (falling back to the driver's own id when no local launch
 * is ready). The driver files stay untouched.
 *
 * Nothing here grants anything: [pair] only records a local pairing receipt and [configure] only
 * selects an endpoint. The controller still decides whether a connect or an action may happen.
 *
 * **Stale transport before an explicit reconnect.** A runtime change makes the controller publish
 * `DISCONNECTED` and drop the grant without calling the driver, so the real ADB transport can stay
 * online. [prepareForConnect] is the explicit, caller-driven teardown for that case; it never
 * connects, re-grants or replays anything. Teardown is serialized and non-cancellable, so a new
 * [configure] cannot start before it finishes and a finished teardown cannot close a newer
 * connection. [configure] and [connect] refuse while a teardown is in flight.
 *
 * **Pairing receipts across process restarts.** The production engine gets a receipt store for the
 * current certificate through [pairingReceipts]; the real implementation persists only the
 * certificate fingerprint, host and pairing time in app-private storage. A restored receipt is not
 * authorization: connect still performs the real TLS handshake, and no lease, observation or action
 * is restored. The factory runs inside the lazy engine factory, i.e. on the IO block of the first
 * driver use, so Keystore and preferences work never starts on the main thread.
 */
internal class PhoneDriverFacade(
    private val identity: () -> AdbIdentity,
    /** The local launch's runtime epoch, or null when no launch is ready. */
    private val runtimeIdentity: () -> String? = { null },
    private val platformSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    /**
     * Receipt store for the current certificate. The default is memory-only, matching the probe and
     * the tests. Production passes an app-private persistent store; that lambda must not touch
     * Android I/O on the main thread, because it is called from the lazy engine factory.
     */
    private val pairingReceipts: (AdbIdentity) -> AdbPairingReceiptStore = { AdbPairingReceiptStore() },
    /**
     * Production overlay guard. The bridge is lazy: it only reaches the already-built native
     * coordinator/integration when the driver samples its mode or records a safety loss. The default
     * null keeps the baseline no-overlay phone behavior and the existing tests unchanged.
     */
    private val overlayBridge: PhoneOverlayDriverBridge? = null,
    private val engineFactory: () -> PhoneDriverEngine = {
        // First real driver use: the only place Keystore, identity and receipt persistence run.
        val owner = identity()
        AdbServiceEngine(
            AdbPhoneDriverService(
                identity = owner,
                runtimeId = FALLBACK_RUNTIME_ID,
                pairingReceipts = pairingReceipts(owner),
                clock = { SystemClock.elapsedRealtime() },
                platformSupported = platformSupported,
                overlayBarrier = overlayBridge,
            ),
        )
    },
) : PhoneDriver {
    private val engineDelegate = lazy(LazyThreadSafetyMode.SYNCHRONIZED) { engineFactory() }

    private val engine: PhoneDriverEngine get() = engineDelegate.value

    /** One explicit teardown at a time; it gates [configure]/[connect] until it completes. */
    private val lifecycleLock = Any()
    private var pendingTeardown: CompletableDeferred<Unit>? = null

    override fun capabilities(): PhoneCapabilities = AdbPhoneDriver.capabilitiesFor(platformSupported)

    override suspend fun connect(deadlineMs: Long): PhoneConnection {
        refuseWhileTearingDown()
        val connection = engine.connect(deadlineMs)
        val epoch = runCatching { runtimeIdentity() }.getOrNull()
        return when {
            epoch.isNullOrBlank() || epoch == connection.runtimeId -> connection
            // Same launch, same runtime identity: the controller never sees the driver's fixed id.
            else -> connection.copy(runtimeId = epoch)
        }
    }

    /**
     * Best-effort teardown of the current transport. A facade that never created its engine has no
     * transport to close, so this returns without starting Keystore or preference I/O (a disconnect
     * can be triggered from the UI thread after a runtime change).
     */
    override suspend fun disconnect(deadlineMs: Long) {
        if (!engineDelegate.isInitialized()) return
        engine.disconnect(deadlineMs)
    }

    override suspend fun observe(deadlineMs: Long): PhoneSnapshot {
        val guard = overlayBridge?.beginOperation(deadlineMs)
        return try {
            engine.observe(deadlineMs)
        } catch (failure: PhoneDriverException) {
            notifyOverlayRefused(guard, failure.failure)
            throw failure
        }
    }

    override suspend fun capture(binding: PhoneObservationBinding, deadlineMs: Long): PhoneImage {
        val guard = overlayBridge?.beginOperation(deadlineMs)
        return try {
            engine.capture(binding, deadlineMs)
        } catch (failure: PhoneDriverException) {
            notifyOverlayRefused(guard, failure.failure)
            throw failure
        }
    }

    override suspend fun dispatch(request: PhoneDispatchRequest, deadlineMs: Long): PhoneDispatchReceipt {
        val guard = overlayBridge?.beginOperation(deadlineMs)
        val receipt =
            try {
                engine.dispatch(request, deadlineMs)
            } catch (failure: PhoneDriverException) {
                notifyOverlayRefused(guard, failure.failure)
                throw failure
            }
        // A refused dispatch is a known not-sent result. An Unknown receipt is never treated as a
        // loss/rollback here: the mutation may have reached the device.
        if (guard != null &&
            receipt is PhoneDispatchReceipt.NotSent &&
            receipt.failure == PhoneDriverFailure.POLICY_DENIED
        ) {
            notifyOverlayRefused(guard, PhoneDriverFailure.POLICY_DENIED)
        }
        return receipt
    }

    /**
     * Only an overlay-required operation's POLICY_DENIED is a possible overlay safety loss. The
     * bridge re-checks the frozen epoch/display owner on the main thread; a stale operation can never
     * revoke a newer grant. A failed notification is not an acknowledgement.
     */
    private fun notifyOverlayRefused(
        guard: PhoneOverlayDriverBridge.OperationGuard?,
        failure: PhoneDriverFailure,
    ) {
        if (guard == null || failure != PhoneDriverFailure.POLICY_DENIED) return
        overlayBridge?.onRefusedWhileRequired(guard)
    }

    /**
     * Closes the previous real transport before an explicit connect.
     *
     * A runtime change makes the controller publish `DISCONNECTED` and drop the grant without
     * calling the driver, while the ADB transport can still report itself connected. The engine's
     * [configure] then refuses, and the UI has no Disconnect action left. The native caller awaits
     * this in the same connect operation, immediately before [configure] and the controller's
     * `connect`, so the normal explicit connect path can select the new endpoint.
     *
     * This closes (and waits for) only the current transport: it never connects, re-grants,
     * re-observes or replays a request, and it never creates the engine. A facade that was never
     * used has nothing to tear down and no keystore work happens here. Teardown is serialized and
     * not cancellable, so a cancelled connect attempt cannot leave a late teardown behind.
     *
     * Call it from the connect coroutine/IO path, not concurrently with another connect. Do not
     * wrap it in `runBlocking`: the blocking transport close happens on the engine's IO dispatcher.
     */
    suspend fun prepareForConnect(deadlineMs: Long = PhoneLimits.DEFAULT_CALL_MS) {
        if (!engineDelegate.isInitialized()) return
        val gate: CompletableDeferred<Unit>
        val owner: Boolean
        synchronized(lifecycleLock) {
            val existing = pendingTeardown
            if (existing == null) {
                gate = CompletableDeferred()
                pendingTeardown = gate
                owner = true
            } else {
                gate = existing
                owner = false
            }
        }
        if (!owner) {
            // A teardown is already running: wait for that one instead of closing twice.
            gate.await()
            return
        }
        try {
            // Non-cancellable so a cancelled attempt still finishes its teardown before the gate
            // opens; otherwise a late close could race a connection made after it.
            withContext(NonCancellable) {
                runCatching { engine.disconnect(deadlineMs.coerceIn(1L, PhoneLimits.MAX_CALL_MS)) }
            }
        } finally {
            synchronized(lifecycleLock) {
                if (pendingTeardown === gate) pendingTeardown = null
            }
            gate.complete(Unit)
        }
    }

    /**
     * Selects the connect endpoint. Must happen while disconnected, before `controller.connect`.
     * After a runtime change first await [prepareForConnect]; while its teardown is in flight this
     * refuses instead of selecting an endpoint on a still-live transport.
     */
    fun configure(host: String, connectPort: Int): AdbDriverEndpoint = synchronized(lifecycleLock) {
        if (pendingTeardown != null) throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        engine.configure(host, connectPort)
    }

    /** Records the transient pairing port from the system pairing dialog. */
    fun configurePairing(host: String, pairingPort: Int) = engine.configurePairing(host, pairingPort)

    /** One bounded pairing exchange. The code is passed as a char array and cleared by the callee. */
    fun pair(pairingCode: CharArray, deadlineMs: Long): AdbPairingReceipt = engine.pair(pairingCode, deadlineMs)

    /**
     * Closes a blocked pairing socket. Safe from the UI or a notification action thread, and it never
     * creates the engine: cancelling must not trigger keystore work on the main thread.
     */
    fun cancelPairing() {
        if (!engineDelegate.isInitialized()) return
        runCatching { engine.cancelPairing() }
    }

    /** True only for a local pairing receipt for this host; a connection is not a receipt. */
    fun hasPairingReceipt(host: String): Boolean = runCatching { engine.hasPairingReceipt(host) }.getOrDefault(false)

    fun configuredEndpoint(): AdbDriverEndpoint? = runCatching { engine.configuredEndpoint() }.getOrNull()

    fun transportSecurity(): AdbTransportSecurity =
        runCatching { engine.transportSecurity }.getOrDefault(AdbTransportSecurity.PLAINTEXT)

    /** Configure and connect must not attach while a prepare teardown is still closing the old one. */
    private fun refuseWhileTearingDown() {
        if (synchronized(lifecycleLock) { pendingTeardown != null }) {
            throw PhoneDriverException(PhoneDriverFailure.DRIVER)
        }
    }

    companion object {
        /** Only used before a local launch is ready; the tool cannot act without one. */
        const val FALLBACK_RUNTIME_ID = "phone-control"

        /** `launch-<id>` for the local server epoch, the only id the tool binding accepts. */
        fun runtimeEpoch(launchId: Long?): String? = launchId?.let(PhoneToolProtocol::serverEpoch)
    }
}
