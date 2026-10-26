// SPDX-License-Identifier: Apache-2.0

package io.andchamber.app.phone.adb

import android.os.Build
import android.os.SystemClock
import io.andchamber.app.phone.core.PhoneCapabilities
import io.andchamber.app.phone.core.PhoneConnection
import io.andchamber.app.phone.core.PhoneDispatchReceipt
import io.andchamber.app.phone.core.PhoneDispatchRequest
import io.andchamber.app.phone.core.PhoneDriver
import io.andchamber.app.phone.core.PhoneDriverException
import io.andchamber.app.phone.core.PhoneDriverFailure
import io.andchamber.app.phone.core.PhoneImage
import io.andchamber.app.phone.core.PhoneObservationBinding
import io.andchamber.app.phone.core.PhoneSnapshot
import io.andchamber.app.phone.overlay.safety.PhoneOverlayBarrier
import io.github.muntashirakon.adb.AdbPairingException
import io.github.muntashirakon.adb.AdbPairingFailure
import java.util.UUID

/** One configured local ADB endpoint. The host is never placed in logs or errors. */
data class AdbDriverEndpoint(
    val host: String,
    val connectPort: Int,
)

private data class AdbPairingEndpoint(
    val host: String,
    val pairingPort: Int,
)

/**
 * The driver service the phone-control wiring configures before it asks the controller to connect.
 *
 * `PhoneController.connect()` only calls [PhoneDriver.connect]; the actual host and port therefore
 * live here, not in the controller. A person's pairing dialog supplies a transient pairing port;
 * [configurePairing] + [pair] consume it and the connect port is configured separately with
 * [configure]. Pairing codes are never stored.
 *
 * This facade is also the single [PhoneDriver] passed to the controller, so production pairing and
 * the P0 probe use the same `AdbPhonePairing` / [LibAdbDevice] path.
 */
class AdbPhoneDriverService(
    private val identity: AdbIdentity,
    private val runtimeId: String,
    private val pairingReceipts: AdbPairingReceiptStore = AdbPairingReceiptStore(),
    private val hostPolicy: AdbHostPolicy = AdbHostPolicy(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val connectionId: () -> String = { UUID.randomUUID().toString() },
    private val platformSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
    private val overlayBarrier: PhoneOverlayBarrier? = null,
    private val deviceFactory: (
        AdbIdentity,
        String,
        Int,
        AdbPairingReceiptStore,
        AdbHostPolicy,
    ) -> AdbDevice = { owner, host, port, receipts, policy ->
        LibAdbDevice(
            identity = owner,
            host = host,
            port = port,
            pairingReceipts = receipts,
            hostPolicy = policy,
        )
    },
) : PhoneDriver {
    private val lock = Any()
    private var endpoint: AdbDriverEndpoint? = null
    private var pairingEndpoint: AdbPairingEndpoint? = null
    private var currentDevice: AdbDevice? = null
    private var currentDriver: AdbPhoneDriver? = null
    private var activeOperations = 0

    @Volatile
    private var activePairing: AdbPairingCancellation? = null

    /**
     * Selects the local connect endpoint. Must be called before the controller's `connect` and
     * only while no driver operation is in flight. Reconfiguration after a disconnect is allowed.
     */
    @Synchronized
    fun configure(host: String, connectPort: Int): AdbDriverEndpoint {
        if (activeOperations > 0 || currentDevice?.isConnected() == true) {
            throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        }
        validateHost(host)
        if (connectPort !in 1..65535) throw AdbDeviceException(AdbDeviceFailure.POLICY)
        pairingEndpoint?.let { pairing ->
            if (pairing.host != host) throw AdbDeviceException(AdbDeviceFailure.POLICY)
        }
        val selected = AdbDriverEndpoint(host, connectPort)
        val device = deviceFactory(identity, host, connectPort, pairingReceipts, hostPolicy)
        runCatching { currentDevice?.disconnect() }
        endpoint = selected
        currentDevice = device
        currentDriver = AdbPhoneDriver(
            device = device,
            runtimeId = runtimeId,
            clock = clock,
            connectionId = connectionId,
            platformSupported = platformSupported,
            overlayBarrier = overlayBarrier,
        )
        return selected
    }

    /** Transient pairing port from the device's pairing dialog. The code is supplied separately. */
    @Synchronized
    fun configurePairing(host: String, pairingPort: Int) {
        if (activeOperations > 0 || currentDevice?.isConnected() == true) {
            throw AdbDeviceException(AdbDeviceFailure.DRIVER)
        }
        validateHost(host)
        if (pairingPort !in 1..65535) throw AdbDeviceException(AdbDeviceFailure.POLICY)
        endpoint?.let { selected ->
            if (selected.host != host) throw AdbDeviceException(AdbDeviceFailure.POLICY)
        }
        pairingEndpoint = AdbPairingEndpoint(host, pairingPort)
    }

    /** Runs one bounded pairing exchange through the same entry point the probe uses. */
    @Synchronized
    fun pair(pairingCode: CharArray, deadlineMs: Long): AdbPairingReceipt {
        if (activeOperations > 0 || currentDevice?.isConnected() == true) {
            throw AdbPairingException(AdbPairingFailure.POLICY)
        }
        val pairing = pairingEndpoint
            ?: throw AdbPairingException(AdbPairingFailure.POLICY)
        val cancellation = AdbPairingCancellation()
        activePairing = cancellation
        val receipt = try {
            AdbPhonePairing.pair(
                identity = identity,
                host = pairing.host,
                port = pairing.pairingPort,
                pairingCode = pairingCode,
                deadlineMs = deadlineMs,
                receipts = pairingReceipts,
                hostPolicy = hostPolicy,
                clock = clock,
                cancellation = cancellation,
            )
        } finally {
            activePairing = null
        }
        pairingEndpoint = null
        return receipt
    }

    /** Stops a blocked pairing exchange by closing its socket. Safe from another thread. */
    fun cancelPairing() {
        activePairing?.cancel()
    }

    /** True only for a successful App-side pairing receipt for this certificate and host. */
    @Synchronized
    fun hasPairingReceipt(host: String): Boolean = pairingReceipts.has(identity, host)

    @Synchronized
    fun configuredEndpoint(): AdbDriverEndpoint? = endpoint

    @Synchronized
    fun isConfigured(): Boolean = currentDriver != null

    val transportSecurity: AdbTransportSecurity
        get() = synchronized(lock) { currentDriver?.transportSecurity } ?: AdbTransportSecurity.PLAINTEXT

    override fun capabilities(): PhoneCapabilities =
        AdbPhoneDriver.capabilitiesFor(platformSupported)

    fun capabilityReport(): AdbCapabilityReport =
        synchronized(lock) { currentDriver?.capabilityReport() }
            ?: AdbCapabilityReport(phone = capabilities())

    override suspend fun connect(deadlineMs: Long): PhoneConnection = operate {
        requireDriver().connect(deadlineMs)
    }

    override suspend fun disconnect(deadlineMs: Long) {
        val driver = synchronized(lock) { currentDriver } ?: return
        operate { driver.disconnect(deadlineMs) }
    }

    override suspend fun observe(deadlineMs: Long): PhoneSnapshot = operate {
        requireDriver().observe(deadlineMs)
    }

    override suspend fun capture(
        binding: PhoneObservationBinding,
        deadlineMs: Long,
    ): PhoneImage = operate {
        requireDriver().capture(binding, deadlineMs)
    }

    override suspend fun dispatch(
        request: PhoneDispatchRequest,
        deadlineMs: Long,
    ): PhoneDispatchReceipt = operate {
        requireDriver().dispatch(request, deadlineMs)
    }

    private fun requireDriver(): AdbPhoneDriver = synchronized(lock) {
        currentDriver ?: throw PhoneDriverException(PhoneDriverFailure.NOT_CONNECTED)
    }

    private suspend fun <T> operate(block: suspend () -> T): T {
        synchronized(lock) { activeOperations += 1 }
        try {
            return block()
        } finally {
            synchronized(lock) { activeOperations -= 1 }
        }
    }

    private fun validateHost(host: String) {
        if (hostPolicy.refusal(host) != null) throw AdbDeviceException(AdbDeviceFailure.POLICY)
    }
}
