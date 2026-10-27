package io.andchamber.app.phone.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ext.SdkExtensions
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.RequiresExtension
import io.andchamber.app.phone.adb.AdbHostPolicy
import java.net.InetAddress
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Bounded NSD discovery for this device's own wireless-debugging endpoints.
 *
 * The service types are Android's official `_adb-tls-pairing._tcp` / `_adb-tls-connect._tcp`. Nothing
 * here pairs, connects or grants; it reports fixed endpoint/outcome values to its owner and stops after
 * the caller's bound. Results are accepted only when [PhoneDiscoveryLedger] proves the advertiser is
 * loopback or one of this device's own numeric addresses, so another phone on the LAN can never become
 * a target. The service *name* is never used to infer identity.
 *
 * Resolve calls are serialised: `NsdManager.resolveService` rejects a second concurrent request with
 * `FAILURE_ALREADY_ACTIVE`, so found events wait in a queue and the next resolve starts only after the
 * previous callback. When the device reports API 33 plus T extension SDK version 7, the same
 * advertisement is followed with `registerServiceInfoCallback`, so a port change can arrive as an
 * update instead of relying on a cached one-shot resolve; registration failure falls back to one
 * bounded legacy resolve. A late callback from an older token/session revision is ignored.
 *
 * Every resolved local candidate is checked by a short loopback bind probe before it may become Ready:
 * EADDRINUSE means the port is really owned on this phone; a successful bind (free) or any other bind
 * error discards the advertisement. No TCP connect, no port scan.
 *
 * On API 30-33 the legacy NSD extension needs a short Wi-Fi multicast lock; it is held only while
 * this discovery runs and released in [stop] (and on every failure path). API 34+ does not hold it.
 */
internal class PhonePortDiscovery(
    context: Context,
    private val hostPolicy: AdbHostPolicy = AdbHostPolicy(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val portProbe: PhoneLocalPortProbe = LoopbackBindPortProbe,
    private val onChange: (pairing: PhoneDiscoveryStatus, connect: PhoneDiscoveryStatus) -> Unit,
) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val manager: NsdManager? = appContext.getSystemService(NsdManager::class.java)
    private val ledger = PhoneDiscoveryLedger(hostPolicy)
    private val resolveGate = PhoneDiscoveryResolveGate()
    private val listeners = LinkedHashMap<PhoneDiscoveryKind, NsdManager.DiscoveryListener>()
    private val revisions = LinkedHashMap<PhoneDiscoveryKind, Long>()
    private val deadlineTasks = LinkedHashMap<PhoneDiscoveryKind, Runnable>()
    /** API 34+ continuous advertisement callbacks, keyed by the same resolve token. Any on older APIs. */
    private val serviceInfoCallbacks = LinkedHashMap<PhoneResolveToken, Any>()
    private val mainExecutor = Executor { command -> main.post(command) }

    private data class PendingResolve(
        val kind: PhoneDiscoveryKind,
        val revision: Long,
        val info: NsdServiceInfo,
        val token: PhoneResolveToken,
    )

    private val pendingResolves = ArrayDeque<PendingResolve>()
    private var resolving = false
    private var running = false
    private var session = 0L
    private var probeExecutor: ExecutorService? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    /**
     * Starts the requested kinds with one shared bound. Returns false only when NSD is unavailable or
     * no kind was requested; the owner must then stop the foreground service.
     */
    fun start(
        kinds: Set<PhoneDiscoveryKind>,
        timeoutMs: Long,
    ): Boolean {
        if (running) return true
        val nsd = manager ?: return false
        if (kinds.isEmpty()) return false
        running = true
        session += 1
        val currentSession = session
        resolveGate.clear()
        probeExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "phone-port-probe").apply { isDaemon = true }
        }
        acquireMulticastLock()
        val now = clock()
        val bound = timeoutMs.coerceIn(1_000L, MAX_TIMEOUT_MS)
        kinds.forEach { kind ->
            val revision = ledger.begin(kind, now, bound)
            revisions[kind] = revision
            scheduleDeadline(kind, revision, bound)
            discover(nsd, kind, currentSession)
        }
        publish()
        return true
    }

    /** Stops every listener, drops the queue and releases the multicast lock. Idempotent. */
    fun stop() {
        if (!running) {
            releaseMulticastLock()
            return
        }
        running = false
        session += 1
        resolving = false
        probeExecutor?.shutdownNow()
        probeExecutor = null
        unregisterAllServiceCallbacks()
        pendingResolves.clear()
        deadlineTasks.values.forEach { main.removeCallbacks(it) }
        deadlineTasks.clear()
        val nsd = manager
        listeners.keys.toList().forEach { kind ->
            val listener = listeners.remove(kind) ?: return@forEach
            runCatching { nsd?.stopServiceDiscovery(listener) }
        }
        revisions.clear()
        resolveGate.clear()
        ledger.stopAll()
        releaseMulticastLock()
    }

    private fun scheduleDeadline(
        kind: PhoneDiscoveryKind,
        revision: Long,
        timeoutMs: Long,
    ) {
        val task =
            Runnable {
                if (!running || revisions[kind] != revision) return@Runnable
                ledger.timeout(kind, revision, clock())
                publish()
            }
        deadlineTasks[kind] = task
        main.postDelayed(task, timeoutMs)
    }

    private fun discover(
        nsd: NsdManager,
        kind: PhoneDiscoveryKind,
        session: Long,
    ) {
        val listener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    main.post { handleFound(kind, info, session) }
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    main.post { handleLost(kind, info, session) }
                }

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    main.post { failKind(kind, session) }
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
        listeners[kind] = listener
        // Query without a trailing dot; incoming reported types are normalised before comparison.
        runCatching { nsd.discoverServices(kind.serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { failKind(kind, session) }
    }

    private fun handleFound(
        kind: PhoneDiscoveryKind,
        info: NsdServiceInfo,
        callbackSession: Long,
    ) {
        if (!running || callbackSession != session) return
        if (!revisions.containsKey(kind)) return
        if (PhoneDiscoveryKind.forServiceType(info.serviceType) != kind) return
        val revision = revisions[kind] ?: return
        val name = info.serviceName.orEmpty()
        val priorVersion = resolveGate.currentVersion(kind, name)
        val priorPort = ledger.livePort(kind, name, clock())
        if (priorVersion != null) {
            unregisterServiceCallbacks(kind, name)
        }
        // A newer found for the same name supersedes an older resolve still in flight.
        val token = resolveGate.found(kind, name)
        pendingResolves.removeAll {
            it.kind == kind && it.token.serviceName == name && it.token.version != token.version
        }
        if (priorVersion != null && priorPort != null && priorPort != info.port) {
            // The advertisement's name now refers to a different port. Never keep the old port Ready
            // while the new candidate is being resolved/probed; a FREE/ERROR probe leaves it cleared.
            ledger.lost(kind, revision, name, clock())
            publish()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >=
            SERVICE_INFO_CALLBACK_EXTENSION
        ) {
            // Continuous updates cover a port change that a cached one-shot resolve would miss. The
            // legacy resolve is also queued immediately: either path can supply the current port, and
            // the bind probe still rejects a cached old one.
            registerServiceInfo(kind, revision, token, info)
            pendingResolves.addLast(PendingResolve(kind, revision, info, token))
            resolveNext()
        } else {
            pendingResolves.addLast(PendingResolve(kind, revision, info, token))
            resolveNext()
        }
    }

    private fun handleLost(
        kind: PhoneDiscoveryKind,
        info: NsdServiceInfo,
        callbackSession: Long,
    ) {
        if (!running || callbackSession != session) return
        val revision = revisions[kind] ?: return
        // The callback came from this kind's own listener; do not drop a loss merely because the
        // platform omitted or normalised the reported type.
        val name = info.serviceName.orEmpty()
        // Invalidate the advertisement's resolve token before removing queued resolves. An in-flight
        // resolve's callback is checked against this gate and must never publish the lost endpoint.
        unregisterServiceCallbacks(kind, name)
        resolveGate.lost(kind, name)
        pendingResolves.removeAll { it.kind == kind && it.token.serviceName == name }
        ledger.lost(kind, revision, name, clock())
        publish()
    }

    /**
     * Runs at most one resolve at a time. `FAILURE_ALREADY_ACTIVE` cannot happen because the flag is
     * cleared only in a callback, which then starts the next request.
     */
    private fun resolveNext() {
        if (!running || resolving) return
        val nsd = manager ?: return
        val next = pendingResolves.removeFirstOrNull() ?: return
        if (revisions[next.kind] != next.revision) {
            resolveNext()
            return
        }
        resolving = true
        @Suppress("DEPRECATION")
        val started =
            runCatching {
                nsd.resolveService(
                    next.info,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(
                            serviceInfo: NsdServiceInfo,
                            errorCode: Int,
                        ) {
                            main.post {
                                resolving = false
                                resolveNext()
                            }
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            main.post {
                                resolving = false
                                if (running &&
                                    resolveGate.isCurrent(next.token) &&
                                    serviceInfo.serviceName.orEmpty() == next.token.serviceName
                                ) {
                                    acceptResolved(next.kind, next.revision, next.token, serviceInfo)
                                }
                                resolveNext()
                            }
                        }
                    },
                )
            }.isSuccess
        if (!started) {
            resolving = false
            resolveNext()
        }
    }

    @RequiresExtension(
        extension = Build.VERSION_CODES.TIRAMISU,
        version = SERVICE_INFO_CALLBACK_EXTENSION,
    )
    private fun registerServiceInfo(
        kind: PhoneDiscoveryKind,
        revision: Long,
        token: PhoneResolveToken,
        info: NsdServiceInfo,
    ) {
        val nsd = manager ?: return
        val callback =
            object : NsdManager.ServiceInfoCallback {
                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    main.post { handleServiceUpdate(kind, revision, token, serviceInfo) }
                }

                override fun onServiceLost() {
                    main.post { handleServiceInfoLost(kind, revision, token) }
                }

                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    main.post {
                        serviceInfoCallbacks.remove(token)
                        // Registration failure is a deterministic fallback point, not a guessed wait.
                        fallbackResolve(kind, revision, token, info)
                    }
                }

                override fun onServiceInfoCallbackUnregistered() {
                    main.post {
                        serviceInfoCallbacks.remove(token)
                        // If the registration was still current (not a lost/new-found/stop path), keep a
                        // bounded legacy resolve fallback rather than waiting for a callback that is gone.
                        fallbackResolve(kind, revision, token, info)
                    }
                }
            }
        serviceInfoCallbacks[token] = callback
        val registered =
            runCatching {
                nsd.registerServiceInfoCallback(info, mainExecutor, callback)
            }.isSuccess
        if (!registered) {
            serviceInfoCallbacks.remove(token)
            fallbackResolve(kind, revision, token, info)
        }
    }

    private fun handleServiceUpdate(
        kind: PhoneDiscoveryKind,
        revision: Long,
        token: PhoneResolveToken,
        info: NsdServiceInfo,
    ) {
        if (!running || revisions[kind] != revision) return
        if (!resolveGate.isCurrent(token)) return
        if (info.serviceName.orEmpty() != token.serviceName) return
        acceptResolved(kind, revision, token, info)
    }

    private fun handleServiceInfoLost(
        kind: PhoneDiscoveryKind,
        revision: Long,
        token: PhoneResolveToken,
    ) {
        if (!running || revisions[kind] != revision) return
        if (!resolveGate.isCurrent(token)) return
        serviceInfoCallbacks.remove(token)
        resolveGate.lost(kind, token.serviceName)
        pendingResolves.removeAll { it.token == token }
        ledger.lost(kind, revision, token.serviceName, clock())
        publish()
    }

    private fun fallbackResolve(
        kind: PhoneDiscoveryKind,
        revision: Long,
        token: PhoneResolveToken,
        info: NsdServiceInfo,
    ) {
        if (!running || revisions[kind] != revision) return
        if (!resolveGate.isCurrent(token)) return
        pendingResolves.removeAll { it.token == token }
        pendingResolves.addLast(PendingResolve(kind, revision, info, token))
        resolveNext()
    }

    private fun unregisterServiceCallbacks(
        kind: PhoneDiscoveryKind,
        serviceName: String,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) <
            SERVICE_INFO_CALLBACK_EXTENSION
        ) {
            serviceInfoCallbacks.clear()
            return
        }
        val tokens =
            serviceInfoCallbacks.keys.filter {
                it.kind == kind && it.serviceName == serviceName
            }
        unregisterTokens(tokens)
    }

    private fun unregisterAllServiceCallbacks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) <
            SERVICE_INFO_CALLBACK_EXTENSION
        ) {
            serviceInfoCallbacks.clear()
            return
        }
        unregisterTokens(serviceInfoCallbacks.keys.toList())
    }

    @RequiresExtension(
        extension = Build.VERSION_CODES.TIRAMISU,
        version = SERVICE_INFO_CALLBACK_EXTENSION,
    )
    private fun unregisterTokens(tokens: List<PhoneResolveToken>) {
        if (tokens.isEmpty()) return
        val nsd = manager
        tokens.forEach { token ->
            val callback = serviceInfoCallbacks.remove(token) ?: return@forEach
            runCatching {
                nsd?.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback)
            }
        }
    }

    private fun acceptResolved(
        kind: PhoneDiscoveryKind,
        revision: Long,
        token: PhoneResolveToken,
        info: NsdServiceInfo,
    ) {
        if (!running || revisions[kind] != revision) return
        if (PhoneDiscoveryKind.forServiceType(info.serviceType) != kind) return
        val name = info.serviceName.orEmpty()
        if (name != token.serviceName) return
        val now = clock()
        val livePort = ledger.livePort(kind, name, now)
        if (livePort == info.port) return
        if (livePort != null) {
            // Same advertisement name now reports another port; close the old endpoint first. A FREE
            // or failed probe then leaves Searching, never the old Ready value.
            ledger.lost(kind, revision, name, now)
            publish()
        }
        val addresses = reportedAddresses(info)
        if (addresses.isEmpty()) return
        val candidate =
            ledger.probeCandidate(
                kind = kind,
                revision = revision,
                token = token,
                serviceType = info.serviceType,
                serviceName = name,
                addresses = addresses,
                port = info.port,
                nowMs = now,
            ) ?: return
        val callbackSession = session
        val executor = probeExecutor ?: return
        executor.execute {
            val result = portProbe.probe(candidate.port)
            main.post {
                // Re-check after the syscall: a lost advertisement, new found, session stop or newer
                // revision must not be published by this late probe.
                if (!running || callbackSession != session) return@post
                val before = ledger.status(kind, clock())
                val after = ledger.acceptProbed(candidate, result, resolveGate, clock())
                if (after != before) publish()
            }
        }
    }

    /**
     * Every address the platform reported. API 34's `getHostAddresses` is checked first so a host
     * with several addresses is not dismissed because the deprecated `host` picked a non-local one.
     */
    @Suppress("DEPRECATION")
    private fun reportedAddresses(info: NsdServiceInfo): List<InetAddress> {
        val all =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                runCatching { info.hostAddresses.orEmpty() }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
        if (all.isNotEmpty()) return all
        return listOfNotNull(info.host)
    }

    private fun failKind(
        kind: PhoneDiscoveryKind,
        callbackSession: Long,
    ) {
        if (!running || callbackSession != session) return
        val revision = revisions[kind] ?: return
        // This type alone is unavailable; the other type keeps running.
        ledger.fail(kind, revision, clock())
        publish()
    }

    private fun publish() {
        if (!running) return
        val now = clock()
        onChange(
            ledger.status(PhoneDiscoveryKind.PAIRING, now),
            ledger.status(PhoneDiscoveryKind.CONNECT, now),
        )
    }

    private fun acquireMulticastLock() {
        // API 30-33 uses the legacy NSD extension and needs the short Wi-Fi multicast lock. API 34+
        // does not. This is the official version boundary, not a silent retry fallback.
        if (!discoveryNeedsMulticastLock(Build.VERSION.SDK_INT)) return
        if (multicastLock != null) return
        val wifi = appContext.getSystemService(WifiManager::class.java) ?: return
        runCatching {
            val lock = wifi.createMulticastLock(MULTICAST_LOCK_TAG)
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        multicastLock = null
        runCatching { lock.release() }
    }

    companion object {
        const val MAX_TIMEOUT_MS: Long = 5 * 60_000L

        private const val MULTICAST_LOCK_TAG = "andchamber-phone-discovery"
    }
}

/**
 * Proves that exactly one already-resolved local port is currently bound on loopback.
 *
 * It creates a TCP socket, binds it to `127.0.0.1:port` and closes it without `listen()` or `connect()`
 * — no bytes, no TLS/ADB handshake interference, no port scan. A successful bind means the advertised
 * port is free and the advertisement is stale (FREE). Only `EADDRINUSE` proves some local socket owns
 * the port (OCCUPIED); EACCES or any other error is ERROR, never occupancy. SO_REUSEADDR avoids
 * treating a TIME_WAIT remnant as an active listener.
 */
internal object LoopbackBindPortProbe : PhoneLocalPortProbe {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    override fun probe(port: Int): PhonePortProbeResult {
        if (port !in 1..65_535) return PhonePortProbeResult.ERROR
        val fd =
            try {
                Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, 0)
            } catch (_: Exception) {
                return PhonePortProbeResult.ERROR
            }
        try {
            // No SO_REUSEADDR: a wildcard/specific-address pair must not be allowed to look free just
            // because both sockets set reuse. Plain bind is the occupancy proof: EADDRINUSE only.
            Os.bind(fd, loopback, port)
            return PhonePortProbeResult.FREE
        } catch (failure: ErrnoException) {
            return if (failure.errno == OsConstants.EADDRINUSE) {
                PhonePortProbeResult.OCCUPIED
            } else {
                PhonePortProbeResult.ERROR
            }
        } catch (_: Exception) {
            return PhonePortProbeResult.ERROR
        } finally {
            runCatching { Os.close(fd) }
        }
    }
}
