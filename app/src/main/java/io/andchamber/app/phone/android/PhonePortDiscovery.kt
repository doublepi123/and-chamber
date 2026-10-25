package io.andchamber.app.phone.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import io.andchamber.app.phone.adb.AdbHostPolicy

/**
 * Bounded NSD discovery for this device's own wireless-debugging ports.
 *
 * It fills the screen's prefill hints only. Results that do not resolve to loopback or to one of this
 * device's own interface addresses are discarded, so a phone advertising on the same network can
 * never become a target. Nothing here connects, pairs or grants, and discovery always ends after a
 * fixed time: the person can still type both ports.
 */
class PhonePortDiscovery(
    private val context: Context,
    private val hostPolicy: AdbHostPolicy = AdbHostPolicy(),
    private val onHint: (pairingPort: Int?, connectPort: Int?) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var pairingPort: Int? = null
    private var connectPort: Int? = null
    private var running = false
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val stopTask = Runnable { stop() }

    /** Starts both bounded searches. Idempotent; returns false when NSD is unavailable. */
    @Synchronized
    fun start(timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val nsd = manager ?: return false
        if (running) return true
        running = true
        pairingPort = null
        connectPort = null
        discover(nsd, TYPE_PAIRING)
        discover(nsd, TYPE_CONNECT)
        handler.postDelayed(stopTask, timeoutMs.coerceIn(500L, 15_000L))
        return true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacks(stopTask)
        val nsd = manager
        listeners.forEach { listener ->
            runCatching { nsd?.stopServiceDiscovery(listener) }
        }
        listeners.clear()
    }

    @Suppress("DEPRECATION")
    private fun discover(
        nsd: NsdManager,
        type: String,
    ) {
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    if (info.serviceType != type) return
                    runCatching {
                        nsd.resolveService(
                            info,
                            object : NsdManager.ResolveListener {
                                override fun onResolveFailed(
                                    serviceInfo: NsdServiceInfo,
                                    errorCode: Int,
                                ) = Unit

                                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                    accept(type, serviceInfo)
                                }
                            },
                        )
                    }
                }

                override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    stop()
                }

                override fun onStopDiscoveryFailed(
                    serviceType: String,
                    errorCode: Int,
                ) {
                    stop()
                }
            }
        listeners += listener
        runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { stop() }
    }

    @Synchronized
    private fun accept(
        type: String,
        info: NsdServiceInfo,
    ) {
        if (!running) return
        val address = info.host ?: return
        // Loopback or one of this device's own addresses only; host names and remote phones are out.
        if (!hostPolicy.isAllowed(address.hostAddress ?: return)) return
        val port = info.port
        if (port !in 1..65_535) return
        when (type) {
            TYPE_PAIRING -> pairingPort = port
            TYPE_CONNECT -> connectPort = port
            else -> return
        }
        onHint(pairingPort, connectPort)
        if (pairingPort != null && connectPort != null) stop()
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 4_000L

        const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
        const val TYPE_CONNECT = "_adb-tls-connect._tcp"
    }
}
