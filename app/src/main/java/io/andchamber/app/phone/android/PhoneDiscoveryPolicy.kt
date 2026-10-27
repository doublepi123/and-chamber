package io.andchamber.app.phone.android

import io.andchamber.app.phone.adb.AdbHostPolicy
import java.net.InetAddress

/**
 * The two Android wireless-debugging service types this app may discover.
 *
 * The value is always the query form: no trailing dot. A discovered `NsdServiceInfo` may report the
 * type with a trailing dot, so [forServiceType] normalises before comparing. The service *name* is
 * bookkeeping for service-lost events only; it is never used to guess which device this is.
 */
internal enum class PhoneDiscoveryKind(
    val serviceType: String,
) {
    PAIRING("_adb-tls-pairing._tcp"),
    CONNECT("_adb-tls-connect._tcp"),
    ;

    companion object {
        fun forServiceType(raw: String?): PhoneDiscoveryKind? {
            val normalized = normalizeServiceType(raw) ?: return null
            return entries.firstOrNull { it.serviceType == normalized }
        }
    }
}

/** Android resolve callbacks may add one leading dot; query types stay without either edge dot. */
internal fun normalizeServiceType(raw: String?): String? =
    raw?.trim()?.removePrefix(".")?.removeSuffix(".")?.takeIf { it.isNotEmpty() }

/**
 * Official capability boundary for the legacy Wi-Fi multicast lock used by NSD.
 *
 * API 30-33 discovery goes through the legacy NsdManager extension and needs a short
 * [android.net.wifi.WifiManager.MulticastLock]; API 34+ does not need this app to hold one. This is a
 * platform-version decision, not a "try and silently drop the failure" fallback.
 */
internal fun discoveryNeedsMulticastLock(sdkInt: Int): Boolean = sdkInt in 30..33

/**
 * `NsdManager.registerServiceInfoCallback` lives in the T extension SDK version 7, not merely in the
 * platform API level. Both facts are required; a device reporting API 34 with T extension 0 does not
 * support it and must use the bounded legacy resolve path.
 */
internal const val SERVICE_INFO_CALLBACK_EXTENSION: Int = 7

internal fun serviceInfoCallbackSupported(
    sdkInt: Int,
    tExtensionVersion: Int,
): Boolean = sdkInt >= 33 && tExtensionVersion >= SERVICE_INFO_CALLBACK_EXTENSION

/**
 * Per-advertisement resolve tokens.
 *
 * A `serviceName` is only the platform's bookkeeping handle for `onServiceLost`; it is never an
 * identity or authentication. A token is minted for every `onServiceFound`, invalidated by the
 * matching `onServiceLost` (or session stop), and re-minted for a later same-name found. A resolve
 * callback may publish only when its exact token is still current, so a resolve that was in flight
 * when the advertisement disappeared can never resurrect that endpoint.
 */
internal data class PhoneResolveToken(
    val kind: PhoneDiscoveryKind,
    val serviceName: String,
    val version: Long,
)

internal class PhoneDiscoveryResolveGate {
    private data class Key(
        val kind: PhoneDiscoveryKind,
        val serviceName: String,
    )

    private val current = LinkedHashMap<Key, Long>()
    private var sequence = 0L

    @Synchronized
    fun found(
        kind: PhoneDiscoveryKind,
        serviceName: String,
    ): PhoneResolveToken {
        sequence += 1
        current[Key(kind, serviceName)] = sequence
        return PhoneResolveToken(kind, serviceName, sequence)
    }

    @Synchronized
    fun currentVersion(
        kind: PhoneDiscoveryKind,
        serviceName: String,
    ): Long? = current[Key(kind, serviceName)]

    @Synchronized
    fun lost(
        kind: PhoneDiscoveryKind,
        serviceName: String,
    ) {
        current.remove(Key(kind, serviceName))
    }

    @Synchronized
    fun isCurrent(token: PhoneResolveToken): Boolean =
        current[Key(token.kind, token.serviceName)] == token.version

    /** A full discovery/session stop invalidates every older resolve callback. */
    @Synchronized
    fun clear() {
        current.clear()
    }
}

/** The only local-listening evidence accepted before a pairing/connect endpoint becomes Ready. */
internal enum class PhonePortProbeResult {
    /** `bind(127.0.0.1:port)` failed with EADDRINUSE: some socket on this phone already owns it. */
    OCCUPIED,

    /** The bind succeeded: the advertisement is stale; close the probe socket and discard it. */
    FREE,

    /** A bind error other than EADDRINUSE (EACCES, invalid fd, …) is not proof of occupancy. */
    ERROR,
}

internal fun interface PhoneLocalPortProbe {
    fun probe(port: Int): PhonePortProbeResult
}

/** One resolved, local-address-approved candidate waiting for its short loopback bind probe. */
internal data class PhoneProbeCandidate(
    val kind: PhoneDiscoveryKind,
    val revision: Long,
    val token: PhoneResolveToken,
    val serviceType: String?,
    val serviceName: String,
    val addresses: List<InetAddress>,
    val port: Int,
)

/**
 * One live local endpoint. [host] is always loopback once accepted: a discovered own-interface
 * address is evidence that the advertiser is this device, not a target address to dial. [serviceName]
 * only lets a later service-lost event remove this exact advertisement.
 */
internal data class PhoneDiscoveredEndpoint(
    val kind: PhoneDiscoveryKind,
    val host: String,
    val port: Int,
    val serviceName: String,
    val receivedAtMs: Long,
) {
    /** Identity of the endpoint itself; two service names for the same port are one endpoint. */
    val identity: String get() = "$host:$port"
}

/**
 * Fixed discovery outcome. Only [Ready] carries an endpoint; [Ambiguous] deliberately carries no
 * candidate, so no caller can pick one at random. Nothing here claims pairing or a connection.
 */
internal sealed interface PhoneDiscoveryStatus {
    data object Idle : PhoneDiscoveryStatus

    data object Searching : PhoneDiscoveryStatus

    data class Ready(val endpoint: PhoneDiscoveredEndpoint) : PhoneDiscoveryStatus

    data class Ambiguous(val candidates: Int) : PhoneDiscoveryStatus

    data object TimedOut : PhoneDiscoveryStatus

    data object Unavailable : PhoneDiscoveryStatus
}

/** A snapshot for one bounded discovery session. Never persisted and never authorization. */
internal data class PhoneDiscoverySnapshot(
    val generation: Long,
    val pairing: PhoneDiscoveryStatus,
    val connect: PhoneDiscoveryStatus,
)

/** True once no further result can arrive for this session. */
internal fun PhoneDiscoveryStatus.isTerminal(): Boolean =
    this == PhoneDiscoveryStatus.Idle ||
        this == PhoneDiscoveryStatus.TimedOut ||
        this == PhoneDiscoveryStatus.Unavailable

/**
 * Pure, per-type discovery ledger with session revisions and a source TTL.
 *
 * Rules:
 * - A late callback from an older revision (stop, new pairing, retry) is ignored.
 * - An endpoint is accepted only when [AdbHostPolicy] proves the address is loopback or one of this
 *   device's numeric interface addresses. The target is then normalised to loopback; a remote peer or
 *   a host name can never become a target.
 * - Two different live ports are [PhoneDiscoveryStatus.Ambiguous], never "last one wins".
 * - Losing one advertisement removes exactly that entry; another live endpoint may still be Ready.
 * - Each type has its own session, so one discovery failure cannot end the other type.
 */
internal class PhoneDiscoveryLedger(
    private val hostPolicy: AdbHostPolicy = AdbHostPolicy(),
    private val sourceTtlMs: Long = DEFAULT_SOURCE_TTL_MS,
) {
    private data class Entry(
        val port: Int,
        val receivedAtMs: Long,
    )

    private data class Session(
        val revision: Long,
        val deadlineMs: Long,
        var unavailable: Boolean = false,
        var expired: Boolean = false,
        val entries: LinkedHashMap<String, Entry> = LinkedHashMap(),
    )

    private val sessions = LinkedHashMap<PhoneDiscoveryKind, Session>()
    private var sequence = 0L

    /** Opens a fresh session for [kind] and returns its revision. */
    @Synchronized
    fun begin(
        kind: PhoneDiscoveryKind,
        nowMs: Long,
        timeoutMs: Long,
    ): Long {
        sequence += 1
        sessions[kind] = Session(sequence, nowMs + timeoutMs.coerceAtLeast(1L))
        return sequence
    }

    @Synchronized
    fun revision(kind: PhoneDiscoveryKind): Long? = sessions[kind]?.revision

    /** The currently live port for a service-name bookkeeping key, or null. */
    @Synchronized
    fun livePort(
        kind: PhoneDiscoveryKind,
        serviceName: String,
        nowMs: Long,
    ): Int? {
        val session = sessions[kind] ?: return null
        val entry = session.entries[serviceName] ?: return null
        if (nowMs - entry.receivedAtMs > sourceTtlMs) return null
        return entry.port
    }

    /**
     * Records one resolved advertisement. [addresses] is every address the platform reported, so a
     * device is still recognised when only one of several addresses is local.
     */
    @Synchronized
    fun accept(
        kind: PhoneDiscoveryKind,
        revision: Long,
        serviceType: String?,
        serviceName: String,
        addresses: Collection<InetAddress>,
        port: Int,
        nowMs: Long,
    ): PhoneDiscoveryStatus {
        val session = sessions[kind] ?: return PhoneDiscoveryStatus.Idle
        if (session.revision != revision) return statusLocked(kind, nowMs)
        if (PhoneDiscoveryKind.forServiceType(serviceType) != kind) return statusLocked(kind, nowMs)
        if (port !in 1..65_535) return statusLocked(kind, nowMs)
        if (session.expired || nowMs >= session.deadlineMs) return statusLocked(kind, nowMs)
        if (!addresses.any(::isLocal)) return statusLocked(kind, nowMs)
        val key = serviceName.ifBlank { "${kind.name}:$port" }
        session.entries[key] = Entry(port = port, receivedAtMs = nowMs)
        return statusLocked(kind, nowMs)
    }

    /** Removes exactly the advertisement named by [serviceName]; other endpoints stay live. */
    @Synchronized
    fun lost(
        kind: PhoneDiscoveryKind,
        revision: Long,
        serviceName: String,
        nowMs: Long,
    ): PhoneDiscoveryStatus {
        val session = sessions[kind] ?: return PhoneDiscoveryStatus.Idle
        if (session.revision != revision) return statusLocked(kind, nowMs)
        if (serviceName.isNotBlank()) session.entries.remove(serviceName)
        return statusLocked(kind, nowMs)
    }

    @Synchronized
    fun fail(
        kind: PhoneDiscoveryKind,
        revision: Long,
        nowMs: Long,
    ): PhoneDiscoveryStatus {
        val session = sessions[kind] ?: return PhoneDiscoveryStatus.Idle
        if (session.revision != revision) return statusLocked(kind, nowMs)
        session.unavailable = true
        return statusLocked(kind, nowMs)
    }

    @Synchronized
    fun timeout(
        kind: PhoneDiscoveryKind,
        revision: Long,
        nowMs: Long,
    ): PhoneDiscoveryStatus {
        val session = sessions[kind] ?: return PhoneDiscoveryStatus.Idle
        if (session.revision != revision) return statusLocked(kind, nowMs)
        session.expired = true
        return statusLocked(kind, nowMs)
    }

    @Synchronized
    fun status(
        kind: PhoneDiscoveryKind,
        nowMs: Long,
    ): PhoneDiscoveryStatus = statusLocked(kind, nowMs)

    @Synchronized
    fun stop(kind: PhoneDiscoveryKind) {
        sessions.remove(kind)
    }

    @Synchronized
    fun stopAll() {
        sessions.clear()
    }

    /**
     * Validates one resolved advertisement without publishing it. The caller runs the short bind
     * probe off the main thread, then calls [acceptProbed] on the main thread with the result.
     */
    @Synchronized
    fun probeCandidate(
        kind: PhoneDiscoveryKind,
        revision: Long,
        token: PhoneResolveToken,
        serviceType: String?,
        serviceName: String,
        addresses: Collection<InetAddress>,
        port: Int,
        nowMs: Long,
    ): PhoneProbeCandidate? {
        val session = sessions[kind] ?: return null
        if (session.revision != revision || session.expired || nowMs >= session.deadlineMs) return null
        if (PhoneDiscoveryKind.forServiceType(serviceType) != kind) return null
        if (port !in 1..65_535) return null
        if (!addresses.any(::isLocal)) return null
        return PhoneProbeCandidate(
            kind = kind,
            revision = revision,
            token = token,
            serviceType = serviceType,
            serviceName = serviceName,
            addresses = addresses.toList(),
            port = port,
        )
    }

    /**
     * Publishes a probed candidate only when the probe saw EADDRINUSE, the resolve token is still
     * current and the same session is still open. A FREE/ERROR probe, a lost advertisement or a
     * newer found for the same name can never turn this endpoint Ready.
     */
    @Synchronized
    fun acceptProbed(
        candidate: PhoneProbeCandidate,
        probe: PhonePortProbeResult,
        gate: PhoneDiscoveryResolveGate,
        nowMs: Long,
    ): PhoneDiscoveryStatus {
        val session = sessions[candidate.kind] ?: return PhoneDiscoveryStatus.Idle
        if (!gate.isCurrent(candidate.token)) return statusLocked(candidate.kind, nowMs)
        if (probe != PhonePortProbeResult.OCCUPIED) return statusLocked(candidate.kind, nowMs)
        if (session.revision != candidate.revision || session.expired || nowMs >= session.deadlineMs) {
            return statusLocked(candidate.kind, nowMs)
        }
        return accept(
            kind = candidate.kind,
            revision = candidate.revision,
            serviceType = candidate.serviceType,
            serviceName = candidate.serviceName,
            addresses = candidate.addresses,
            port = candidate.port,
            nowMs = nowMs,
        )
    }

    private fun statusLocked(
        kind: PhoneDiscoveryKind,
        nowMs: Long,
    ): PhoneDiscoveryStatus {
        val session = sessions[kind] ?: return PhoneDiscoveryStatus.Idle
        val live =
            session.entries.values
                .filter { nowMs - it.receivedAtMs <= sourceTtlMs }
                .distinctBy { it.port }
        if (live.size == 1) {
            val entry = live.single()
            return PhoneDiscoveryStatus.Ready(
                PhoneDiscoveredEndpoint(
                    kind = kind,
                    host = LOOPBACK_HOST,
                    port = entry.port,
                    serviceName = session.entries.entries.firstOrNull { it.value === entry }?.key.orEmpty(),
                    receivedAtMs = entry.receivedAtMs,
                ),
            )
        }
        if (nowMs >= session.deadlineMs || session.expired) {
            // At the bound an unresolved ambiguity is a timeout, not a reason to keep a foreground
            // search alive. A single still-live endpoint was handled above.
            return if (session.unavailable) {
                PhoneDiscoveryStatus.Unavailable
            } else {
                PhoneDiscoveryStatus.TimedOut
            }
        }
        if (live.size > 1) return PhoneDiscoveryStatus.Ambiguous(live.size)
        if (session.unavailable) return PhoneDiscoveryStatus.Unavailable
        return PhoneDiscoveryStatus.Searching
    }

    private fun isLocal(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        // A scoped IPv6 literal (fe80::1%wlan0) is not a literal the policy can parse, but the policy
        // compares address bytes. Strip only the scope for the policy check.
        val literal = address.hostAddress?.substringBefore('%') ?: return false
        return hostPolicy.isAllowed(literal)
    }

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"

        /** A resolved advertisement is evidence for at most this long. */
        const val DEFAULT_SOURCE_TTL_MS: Long = 5 * 60_000L
    }
}

/**
 * Pure probe pipeline used by focused tests and by the platform once it has a candidate.
 *
 * Production runs [PhoneLocalPortProbe.probe] on its short IO executor and calls [acceptProbed] on the
 * main thread afterwards; this helper keeps the same ordering for tests (probe first, then the token /
 * session re-check inside [acceptProbed]).
 */
internal fun PhoneDiscoveryLedger.probeAndAccept(
    candidate: PhoneProbeCandidate,
    gate: PhoneDiscoveryResolveGate,
    probe: PhoneLocalPortProbe,
    nowMs: Long,
): PhoneDiscoveryStatus {
    if (!gate.isCurrent(candidate.token)) return status(candidate.kind, nowMs)
    val result = probe.probe(candidate.port)
    return acceptProbed(candidate, result, gate, nowMs)
}

/**
 * One explicit-pairing auto-connect latch.
 *
 * [arm] is called only after a real pairing success for the generation the person started. The latch
 * attempts at most once per generation: a failed connect never loops, and a new generation needs a new
 * explicit pairing. [disarm] makes cancellation/disable drop the automatic intent.
 */
internal class PhoneAutoConnectGate {
    private var armedFor: Long? = null
    private var attemptedFor: Long? = null

    @Synchronized
    fun arm(generation: Long) {
        armedFor = generation
    }

    @Synchronized
    fun disarm() {
        armedFor = null
    }

    @Synchronized
    fun shouldAttempt(
        generation: Long,
        endpoint: PhoneDiscoveredEndpoint?,
        enabled: Boolean,
    ): Boolean =
        enabled &&
            armedFor == generation &&
            attemptedFor != generation &&
            endpoint != null

    @Synchronized
    fun markAttempted(generation: Long) {
        attemptedFor = generation
    }

    @Synchronized
    fun isAttempted(generation: Long): Boolean = attemptedFor == generation

    @Synchronized
    fun isArmedFor(generation: Long): Boolean = armedFor == generation
}
