package io.andchamber.app.runtime.network

import java.io.File

/** Source of "the network changed" events. [register] returns the matching unregister action. */
fun interface NetworkEvents {
    fun register(onChange: () -> Unit): () -> Unit
}

/** Observable outcome of a refresh, so a failure is visible rather than silently dropped. */
fun interface RuntimeDnsReporter {
    fun report(message: String)
}

/**
 * Keeps one rootfs's /etc/resolv.conf in step with the device's network while a server runs.
 *
 * Lifecycle ([start] / [stop]) and writes share one lock. Every start and stop advances [generation].
 * A refresh samples the DNS servers outside the lock (the provider may be slow), then, under the lock,
 * re-checks that its generation is still current before it writes. [stop] invalidates the generation
 * under the same lock, so once stop returns no write from an earlier generation can land.
 *
 * Writes use [ResolvConf.writeAtomically]: readers see the old or the new file, never a partial one.
 * A failure is reported through [reporter] and never thrown across the event callback.
 */
class DnsRefresher(
    private val dnsProvider: SystemDnsProvider,
    private val events: NetworkEvents,
    private val reporter: RuntimeDnsReporter = RuntimeDnsReporter {},
    private val writeResolv: (File, List<String>) -> Unit = { rootfs, servers ->
        ResolvConf.writeAtomically(File(rootfs, "etc/resolv.conf"), ResolvConf.render(servers))
    },
) {
    private val lock = Any()

    /** Advanced by every start and stop. A refresh is valid only while its token still matches. */
    private var generation = 0L
    private var activeRootfs: File? = null
    private var lastServers: List<String>? = null
    private var unregister: (() -> Unit)? = null

    /**
     * Sample sequence. Each refresh takes the next number before it samples, and a sample may commit
     * only if no newer sample has committed already. So a slow sample that started earlier cannot
     * overwrite the result of a faster, later one within the same generation.
     */
    private var sampleSequence = 0L
    private var committedSequence = 0L

    /**
     * Starts watching [rootfs]: registers the network events, then refreshes once right away.
     * If registration fails, nothing stays registered and the error is returned, so a later start can retry.
     */
    fun start(rootfs: File): Result<Unit> {
        val token: Long
        val previous: (() -> Unit)?
        synchronized(lock) {
            token = ++generation
            activeRootfs = rootfs
            lastServers = null
            previous = unregister
            unregister = null
        }
        previous?.let { runCatching(it) }
        val stop = runCatching { events.register { refresh(token) } }
        if (stop.isFailure) {
            synchronized(lock) { if (generation == token) activeRootfs = null }
            report("DNS watch registration failed: ${stop.exceptionOrNull()?.message}")
            return Result.failure(stop.exceptionOrNull() ?: IllegalStateException("registration failed"))
        }
        val accepted = synchronized(lock) {
            if (generation == token) {
                unregister = stop.getOrThrow()
                true
            } else {
                false
            }
        }
        if (!accepted) {
            // A stop or newer start arrived while we registered: undo this registration.
            runCatching { stop.getOrThrow().invoke() }
            return Result.success(Unit)
        }
        refresh(token)
        return Result.success(Unit)
    }

    /**
     * Ends watching. Advances the generation under the lock, so any refresh that has sampled but not
     * yet written is rejected. Unregisters the network events. Safe to call when not started.
     */
    fun stop() {
        val unregisterAction: (() -> Unit)?
        synchronized(lock) {
            ++generation
            activeRootfs = null
            lastServers = null
            unregisterAction = unregister
            unregister = null
        }
        unregisterAction?.let { action ->
            runCatching(action).onFailure { report("DNS watch unregister failed: ${it.message}") }
        }
    }

    /**
     * Runs one refresh for [token]. Internal for tests; normally driven by [events]. Failures from the
     * provider, the writer and the reporter are caught here and are not rethrown to the caller. The
     * sampling itself runs on the calling thread, which for Android is the network callback thread.
     */
    internal fun refresh(token: Long) {
        val sequence = synchronized(lock) { ++sampleSequence }
        val sampled =
            runCatching { ResolvConf.servers(dnsProvider.dnsServers()) }
                .getOrElse { error ->
                    report("DNS sampling failed: ${error.message}")
                    return
                }
        synchronized(lock) {
            // Commit point, under the lock:
            //  - the generation must still be current (no stop or newer start since this refresh began);
            //  - no newer sample may have committed already (stale samples lose within a generation).
            if (generation != token) return
            if (sequence <= committedSequence) return
            val rootfs = activeRootfs ?: return
            if (sampled == lastServers && rootfs.resolvConfMatches(sampled)) {
                committedSequence = sequence
                return
            }
            // The sequence advances only when the write succeeds, so a failed write never blocks an
            // older sample from trying again.
            runCatching { writeResolv(rootfs, sampled) }
                .onSuccess {
                    lastServers = sampled
                    committedSequence = sequence
                }
                .onFailure { report("DNS write failed: ${it.message}") }
        }
    }

    /** Reports through the injected reporter. A throwing reporter must not escape the event callback. */
    private fun report(message: String) {
        runCatching { reporter.report(message) }
    }

    private fun File.resolvConfMatches(servers: List<String>): Boolean =
        runCatching { File(this, "etc/resolv.conf").readText() == ResolvConf.render(servers) }.getOrDefault(false)
}
