package io.andchamber.app.runtime.process

import io.andchamber.app.browser.provider.BrowserProviderClient
import io.andchamber.app.browser.provider.ProviderHttpTransport
import io.andchamber.app.browser.provider.ProviderProtocol
import io.andchamber.app.ui.browser.NativeBrowserPresenter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
            onConnection = { connectionId -> presenter.setConnection(connectionId, launchId) },
            onCancel = { requestId -> presenter.cancel(requestId) },
        )
    },
) : BrowserProviderSink {
    private val gate = Mutex()
    private val uiLock = Any()
    private var activeLaunch = 0L
    private var client: BrowserProviderClient? = null

    override fun trustedHostAttached(): Boolean = presenter.state.value.hostAttached

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

    override fun onLocalLaunch(origin: String, token: String, launchId: Long) {
        if (!ProviderProtocol.isLoopbackOrigin(origin)) return
        if (!trustedHostAttached()) {
            presenter.setConnection(null, launchId)
            return
        }
        scope.launch {
            val previous = gate.withLock {
                synchronized(uiLock) {
                    activeLaunch = launchId
                    client.also { client = null }
                }
            }
            previous?.stop()
            val next = clientFactory(origin, token, launchId)
            val stillCurrent = gate.withLock {
                synchronized(uiLock) {
                    if (activeLaunch != launchId || !trustedHostAttached()) false else {
                        client = next
                        true
                    }
                }
            }
            if (stillCurrent) next.start() else {
                presenter.setConnection(null, launchId)
                next.stop()
            }
        }
    }

    override fun onLocalStop(launchId: Long) {
        scope.launch {
            val previous = gate.withLock {
                synchronized(uiLock) {
                    if (activeLaunch != launchId) return@withLock null
                    activeLaunch = 0L
                    client.also { client = null }
                }
            }
            presenter.setConnection(null, launchId)
            previous?.stop()
        }
    }
}
