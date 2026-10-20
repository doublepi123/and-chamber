package io.andchamber.app.runtime.process

import io.andchamber.app.browser.provider.BrowserProviderClient
import io.andchamber.app.browser.provider.ProviderHttpTransport
import io.andchamber.app.browser.provider.ProviderProtocol
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Starts the native provider only for a verified local loopback launch whose trusted chat host is
 * in the foreground. A disconnect revokes that connection immediately. Reconnect does not restore
 * a grant. The token is not logged.
 */
class LocalBrowserProviderBinding(
    private val presenter: NativeBrowserPresenter,
    private val router: ProviderPageRouter = ProviderPageRouter(presenter),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val clientFactory: (String, String, Long) -> BrowserProviderClient = { origin, token, launchId ->
        BrowserProviderClient(
            origin = origin,
            token = token,
            runtimeLaunchId = launchId,
            instanceKey = "local",
            clock = clock,
            transport = ProviderHttpTransport(origin, token),
            executor = router,
            scope = scope,
            registry = presenter.registry,
            onConnection = ownConnection(presenter, launchId, CLIENTS.incrementAndGet()),
            onCancel = { requestId -> presenter.cancel(requestId) },
        )
    },
) : BrowserProviderSink {
    companion object {
        private val CLIENTS = AtomicLong()
        /**
         * Each client publishes its own connection and clears only that one, so a stopping client's
         * late disconnect cannot wipe the connection a newer client already adopted.
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

    private val gate = Mutex()
    private val uiLock = Any()
    private var activeLaunch = 0L
    private var pending: Pending? = null
    private var client: BrowserProviderClient? = null
    private val attempt = AtomicLong()

    private data class Pending(val origin: String, val token: String, val launchId: Long)

    init {
        scope.launch {
            presenter.state.map { it.eligible }.distinctUntilChanged().collect { attached ->
                if (attached) connectPending() else stopCurrent()
            }
        }
    }

    override fun trustedHostAttached(): Boolean = presenter.state.value.eligible

    /** UI control for the current local launch. A call after launch replacement is a no-op. */
    fun grant(sessionId: String, tabId: String) = presenter.allowAsync(sessionId, tabId)

    fun revoke() {
        presenter.revokeFromBinding()
        withCurrent { it.revoke() }
    }

    fun setManual(manual: Boolean) {
        if (manual) presenter.takeover() else presenter.prepareAgent()
        withCurrent { it.setManual(manual) }
    }

    fun setVisibleTab(tabId: String?) {
        if (tabId == null) presenter.background() else presenter.select(tabId)
        withCurrent { it.setVisibleTab(tabId) }
    }

    private fun withCurrent(block: (BrowserProviderClient) -> Unit) {
        val current = synchronized(uiLock) { client }
        if (current != null) block(current)
    }

    override fun noteVerifiedLaunch(origin: String, token: String, launchId: Long) {
        if (!ProviderProtocol.isLoopbackOrigin(origin)) return
        synchronized(uiLock) { pending = Pending(origin, token, launchId) }
    }

    override fun onLocalLaunch(origin: String, token: String, launchId: Long) {
        if (!ProviderProtocol.isLoopbackOrigin(origin)) return
        synchronized(uiLock) { pending = Pending(origin, token, launchId) }
        if (!trustedHostAttached()) return
        val ticket = attempt.incrementAndGet()
        scope.launch {
            val previous = gate.withLock {
                if (ticket != attempt.get()) return@launch
                synchronized(uiLock) {
                    activeLaunch = launchId
                    client.also { client = null }
                }
            }
            previous?.stop()
            if (ticket != attempt.get()) return@launch
            val next = clientFactory(origin, token, launchId)
            val stillCurrent = gate.withLock {
                synchronized(uiLock) {
                    if (activeLaunch != launchId || !trustedHostAttached()) false else {
                        client = next
                        true
                    }
                }
            }
            // A client that never started published no connection; stopping it clears nothing else.
            if (stillCurrent) next.start() else next.stop()
        }
    }

    /** The launch itself ended: its credential is gone for good. */
    override fun onLocalStop(launchId: Long) {
        synchronized(uiLock) { if (pending?.launchId == launchId) pending = null }
        disconnect(launchId)
    }

    /**
     * Closes the provider connection for [launchId] but keeps the verified credential, so the next
     * host attach reconnects. Used when the trusted chat page leaves the foreground.
     */
    private fun disconnect(launchId: Long) {
        val ticket = attempt.incrementAndGet()
        scope.launch {
            val previous = gate.withLock {
                if (ticket != attempt.get()) return@launch
                synchronized(uiLock) {
                    if (activeLaunch != launchId) return@withLock null
                    activeLaunch = 0L
                    client.also { client = null }
                }
            } ?: return@launch
            // The client clears only the connection it published (see ownConnection).
            previous.stop()
        }
    }

    private fun connectPending() {
        val next = synchronized(uiLock) { pending } ?: return
        onLocalLaunch(next.origin, next.token, next.launchId)
    }

    private fun stopCurrent() {
        val launch = synchronized(uiLock) { activeLaunch }
        if (launch != 0L) disconnect(launch)
    }
}
