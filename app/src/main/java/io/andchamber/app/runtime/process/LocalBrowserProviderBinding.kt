package io.andchamber.app.runtime.process

import io.andchamber.app.browser.tool.BrowserToolEndpoint
import io.andchamber.app.browser.tool.BrowserToolServer
import io.andchamber.app.browser.tool.BrowserToolTransport
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns the plugin tool server for one local launch. The server listens for the whole launch and
 * follows the presenter's transport eligibility: an eligible trusted foreground host gets a fresh
 * `plugin-<launchId>-<n>` connection, losing eligibility clears it and cancels its in-flight work.
 * A grant stays bound to a connection id, so backgrounding or a console change revokes exactly as
 * before. Reconnect does not restore a grant.
 *
 * The token is generated per launch, written only into the 0600 endpoint file, and never logged.
 */
class LocalBrowserProviderBinding(
    private val presenter: NativeBrowserPresenter,
    private val router: ProviderPageRouter = ProviderPageRouter(presenter),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val transportFactory: (Long, String) -> BrowserToolTransport = { launchId, token ->
        BrowserToolServer(
            launchId = launchId,
            token = token,
            executor = router,
            registry = presenter.registry,
            clock = clock,
            onCancel = { requestId -> presenter.cancel(requestId) },
        )
    },
    private val tokenSource: () -> String = { BrowserToolEndpoint.newToken() },
    /** Receives a short, non-sensitive reason when the tool server is skipped for this launch. */
    private val onUnavailable: (String) -> Unit = {},
) : BrowserToolHost {
    companion object {
        /**
         * Each connection is published and cleared by its owner, so a stopping server's late
         * `null` cannot wipe the connection a newer one already adopted.
         */
        fun ownConnection(
            presenter: NativeBrowserPresenter,
            launchId: Long,
            clientGeneration: Long = 0L,
        ): (String?) -> Unit {
            val published = AtomicReference<String?>(null)
            return { connectionId ->
                if (connectionId != null) {
                    published.set(connectionId)
                    presenter.setConnection(connectionId, launchId, clientGeneration)
                } else {
                    val mine = published.getAndSet(null)
                    presenter.clearConnection(mine, launchId, clientGeneration)
                }
            }
        }
    }

    /** One lifecycle lock: install, connect, disconnect and stop all order through it. */
    private val lock = Any()
    private var active: Active? = null

    /** The launch currently between `start()` entry and install. */
    private var pending: Long? = null

    /**
     * Monotonic revoked watermark. Launch ids only grow, so a stop is terminal for that launch and
     * every older one; an old start can never clear the mark of a newer launch.
     */
    private var revokedThrough = Long.MIN_VALUE

    /**
     * The launch that wrote the shared endpoint file, recorded under [lock]. This is the only owner
     * allowed to delete it: a delayed cleanup of launch A must never remove launch B's file.
     */
    private var endpointOwner: EndpointOwner? = null
    private var lastEligible = false
    private var observedInvalidation = 0L
    private val generations = AtomicLong()

    private data class EndpointOwner(
        val launchId: Long,
        val file: File,
        val trustedRoot: File,
        val token: String,
    )

    private data class Active(
        val launchId: Long,
        val transport: BrowserToolTransport,
        var connectionId: String? = null,
        var generation: Long = 0L,
    )

    init {
        scope.launch {
            presenter.state
                .map { it.transportEligible to it.transportInvalidation }
                .distinctUntilChanged()
                .collect { (eligible, invalidation) ->
                    val invalidated = invalidation != observedInvalidation
                    observedInvalidation = invalidation
                    synchronized(lock) {
                        val current = active
                        if (current == null) {
                            lastEligible = eligible
                            return@synchronized
                        }
                        // `stop()` clears `active` under this very lock. Re-check identity so an
                        // emission that raced a stop never reconnects a stopped transport.
                        if (active !== current) return@synchronized
                        when {
                            // Presentation blocks do not affect `eligible`: keep the connection.
                            !eligible -> {
                                disconnectLocked(current)
                            }
                            // A fast false->true may have been conflated; the invalidation counter
                            // still changed, so the old connection is dropped and a new one is issued.
                            invalidated || !lastEligible -> {
                                disconnectLocked(current)
                                if (active === current) connectLocked(current)
                            }
                        }
                        lastEligible = eligible
                    }
                }
        }
    }

    /** True while the current host is a trusted foreground host, so the tool server may connect. */
    fun trustedHostAttached(): Boolean = presenter.state.value.transportEligible

    /** UI control for the current local launch. A call after launch replacement is a no-op. */
    fun grant(sessionId: String, tabId: String) = presenter.allowAsync(sessionId, tabId)

    fun revoke() {
        presenter.revokeFromBinding()
    }

    fun setManual(manual: Boolean) {
        if (manual) presenter.takeover() else presenter.prepareAgent()
    }

    fun setVisibleTab(tabId: String?) {
        if (tabId == null) presenter.background() else presenter.select(tabId)
    }

    override fun start(
        launchId: Long,
        home: File,
        trustedRoot: File,
    ): Boolean {
        // Register the launch before any blocking work. A stop that arrives first, or while the
        // transport is starting, is terminal: this start then tears its own transport down and
        // never writes the endpoint or connects the presenter.
        val previous = synchronized(lock) {
            if (launchId <= revokedThrough) return false
            // Launch ids only grow. A late start for an older launch must not tear down a newer
            // one, and must never clear the newer launch's pending install.
            if ((active?.launchId ?: Long.MIN_VALUE) > launchId || (pending ?: Long.MIN_VALUE) > launchId) {
                return false
            }
            pending = launchId
            active.also {
                active = null
                // The new launch shares the path. Delete the old file under this same lock that
                // publishes the next one, so a delayed close can never remove the new endpoint.
                discardEndpointLocked(it?.launchId)
            }
        }
        previous?.let { closeActive(it) }
        if (!stillPending(launchId)) {
            finishPending(launchId)
            return false
        }

        val token = tokenSource()
        val transport = try {
            transportFactory(launchId, token)
        } catch (error: Exception) {
            finishPending(launchId)
            onUnavailable("browser-tool transport unavailable: ${error.javaClass.simpleName}")
            return false
        }
        if (!stillPending(launchId)) {
            finishPending(launchId)
            runCatching { transport.stop() }
            return false
        }
        val port = try {
            transport.start()
        } catch (error: Exception) {
            finishPending(launchId)
            runCatching { transport.stop() }
            onUnavailable("browser-tool server did not start: ${error.javaClass.simpleName}")
            return false
        }
        return when (val installed = synchronized(lock) {
            installLocked(launchId, home, trustedRoot, port, token, transport)
        }) {
            is Install.Installed -> true
            Install.Aborted -> {
                runCatching { transport.stop() }
                false
            }
            is Install.Failed -> {
                runCatching { transport.stop() }
                onUnavailable("browser-tool endpoint not written: ${installed.error.javaClass.simpleName}")
                false
            }
        }
    }

    override fun stop(launchId: Long) {
        val removed = synchronized(lock) {
            // A stop for a launch that is still starting wins over that start. The watermark is
            // permanent, so a repeated start of the same launch stays refused.
            if (launchId > revokedThrough) revokedThrough = launchId
            if (pending == launchId) pending = null
            // Owner-checked and under the lock: a newer launch's endpoint is never touched.
            discardEndpointLocked(launchId)
            val current = active
            if (current != null && current.launchId == launchId) {
                active = null
                lastEligible = false
                current
            } else {
                null
            }
        }
        if (removed != null) closeActive(removed)
    }

    /**
     * Removes an endpoint that no installed launch owns, e.g. one left by an earlier process.
     * An endpoint owned by a live launch is left alone: it may belong to a concurrent newer start.
     */
    override fun clearStaleEndpoint(
        home: File,
        trustedRoot: File,
    ): Boolean =
        synchronized(lock) {
            if (endpointOwner != null) return true
            BrowserToolEndpoint.delete(BrowserToolEndpoint.hostFile(home), trustedRoot)
        }

    /** Must hold [lock]. Writes the endpoint and publishes [transport] only if still wanted. */
    private fun installLocked(
        launchId: Long,
        home: File,
        trustedRoot: File,
        port: Int,
        token: String,
        transport: BrowserToolTransport,
    ): Install {
        if (pending != launchId || launchId <= revokedThrough) {
            // Only this launch's own pending mark is cleared; a newer start keeps its pending.
            if (pending == launchId) pending = null
            return Install.Aborted
        }
        return try {
            val file = BrowserToolEndpoint.hostFile(home)
            BrowserToolEndpoint.write(
                file = file,
                home = home,
                trustedRoot = trustedRoot,
                port = port,
                token = token,
            )
            endpointOwner = EndpointOwner(launchId = launchId, file = file, trustedRoot = trustedRoot, token = token)
            val next = Active(launchId = launchId, transport = transport)
            active = next
            lastEligible = presenter.state.value.transportEligible
            if (lastEligible) connectLocked(next)
            Install.Installed(next)
        } catch (error: Exception) {
            if (pending == launchId) pending = null
            // Runs under [lock]; only the file this launch just wrote is removed.
            discardEndpointLocked(launchId)
            Install.Failed(error)
        }
    }

    private fun stillPending(launchId: Long): Boolean =
        synchronized(lock) { pending == launchId && launchId > revokedThrough }

    private fun finishPending(launchId: Long) {
        synchronized(lock) {
            if (pending == launchId) pending = null
        }
    }

    /** Must hold [lock]. Deletes the endpoint only when [launchId] (or its own owner) wrote it. */
    private fun discardEndpointLocked(launchId: Long?) {
        val owner = endpointOwner ?: return
        if (launchId != null && owner.launchId != launchId) return
        endpointOwner = null
        BrowserToolEndpoint.delete(owner.file, owner.trustedRoot)
    }

    /** Transport close only: the endpoint was already handled under [lock] by the caller. */
    private fun closeActive(target: Active) {
        disconnect(target)
        runCatching { target.transport.stop() }
    }

    /** Must hold [lock]. */
    private fun connectLocked(target: Active) {
        if (active !== target) return
        val generation = generations.incrementAndGet()
        val id = "plugin-${target.launchId}-$generation"
        target.connectionId = id
        target.generation = generation
        presenter.setConnection(id, target.launchId, generation)
        target.transport.setConnection(id)
    }

    /** Must hold [lock]. */
    private fun disconnectLocked(target: Active) {
        disconnect(target)
    }

    private fun disconnect(target: Active) {
        val id = target.connectionId ?: return
        target.connectionId = null
        target.transport.setConnection(null)
        presenter.clearConnection(id, target.launchId, target.generation)
    }

    private sealed class Install {
        class Installed(val active: Active) : Install()

        object Aborted : Install()

        class Failed(val error: Exception) : Install()
    }
}
