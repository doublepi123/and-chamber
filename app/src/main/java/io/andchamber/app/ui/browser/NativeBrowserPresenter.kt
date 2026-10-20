package io.andchamber.app.ui.browser

import android.app.Activity
import android.content.Context
import android.view.View
import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.UrlDecision
import io.andchamber.app.browser.provider.OpenChamberPageAdapter
import io.andchamber.app.browser.provider.PageField
import io.andchamber.app.browser.provider.ProviderCodes
import io.andchamber.app.browser.provider.ProviderExecutor
import io.andchamber.app.browser.provider.ProviderMode
import io.andchamber.app.browser.provider.ProviderRegistry
import io.andchamber.app.browser.provider.ProviderRequest
import io.andchamber.app.browser.provider.ProviderResult
import io.andchamber.app.browser.provider.ProviderTab
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job

/**
 * The one process-level owner of the native browser tab registry, the visible page, and agent
 * authority. A trusted local chat host attaches an Activity; leaving that host revokes and
 * releases. Without a foreground host, open returns unavailable and creates no invisible browser.
 *
 * The floating window only collects [state]. It does not create a session, attach a router, or
 * load a URL.
 */
class NativeBrowserPresenter(
    val registry: ProviderRegistry = ProviderRegistry(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val policy: () -> BrowserUrlPolicy = { BrowserUrlPolicy() },
    private val hostAlive: (Context) -> Boolean = ::presenterHostAlive,
    private val sessionFactory: (Context?) -> ControlledBrowserPage? = { context ->
        context?.let { BrowserSession.tryCreate(it, policy)?.let { page -> WebControlledPage(page) } }
    },
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : ProviderExecutor {
    private val lock = Any()
    private val action = Mutex()
    private val _state = MutableStateFlow(NativeBrowserState())
    val state: StateFlow<NativeBrowserState> = _state.asStateFlow()

    private var host: Host? = null
    private var testHost: Boolean = false
    private var page: ControlledBrowserPage? = null
    private var adapter: OpenChamberPageAdapter? = null
    private var connectionId: String? = null
    private var runtimeLaunchId: Long = 0L
    private var selectedTabId: String? = null
    private var mode = ProviderMode.MANUAL
    private var grantEpoch = 0L
    private var documentEpoch = 0L
    private var activeJob: Job? = null
    private var activeRequestId: String? = null
    private var viewGeneration = 0L
    private var witnessForGrant: String? = null

    /** UI and tests observe the page the presenter owns. Null until a claimed open creates one. */
    fun currentView(): View? = synchronized(lock) { page?.view }

    /** Unit tests attach a host without constructing an Activity. Production callers use the Activity overload. */
    internal fun attachHostForTest(instanceKey: String, consoleGeneration: Long) {
        testHost = true
        synchronized(lock) {
            host = Host(null, instanceKey, consoleGeneration)
            publishLocked()
        }
    }

    fun attachHost(activity: Activity, instanceKey: String, consoleGeneration: Long) =
        attachHost(activity as Context, instanceKey, consoleGeneration)

    /** Test and UI entry. Production passes the foreground Activity; a non-Activity context cannot create a page. */
    fun attachHost(context: Context, instanceKey: String, consoleGeneration: Long) {
        synchronized(lock) {
            val previous = host
            if (previous != null && (previous.instanceKey != instanceKey || previous.consoleGeneration != consoleGeneration)) {
                revokeLocked("host replaced")
                releasePageLocked()
            }
            host = Host(context, instanceKey, consoleGeneration)
            publishLocked()
        }
    }

    fun detachHost(instanceKey: String, consoleGeneration: Long) {
        synchronized(lock) {
            val current = host ?: return
            if (current.instanceKey != instanceKey || current.consoleGeneration != consoleGeneration) return
            revokeLocked("host left")
            releasePageLocked()
            host = null
            publishLocked()
        }
    }

    fun setConnection(connectionId: String?, runtimeLaunchId: Long) {
        synchronized(lock) {
            val changed = this.connectionId != connectionId || this.runtimeLaunchId != runtimeLaunchId
            this.connectionId = connectionId
            this.runtimeLaunchId = runtimeLaunchId
            if (changed) revokeLocked("connection replaced")
            publishLocked()
        }
    }

    /** Binding compatibility. Allow itself re-probes; this only records the requested tab. */
    fun allowAsync(sessionId: String, tabId: String) {
        val tab = registry.find(tabId) ?: return
        if (tab.ownerSessionId != sessionId) return
        select(tabId)
        scope.launch { allow() }
    }

    fun revokeFromBinding() {
        synchronized(lock) {
            revokeLocked("binding revoke")
            publishLocked()
        }
    }

    fun prepareAgent() {
        synchronized(lock) {
            page?.manualNavigation = false
            if (registry.currentGrant() == null) mode = ProviderMode.MANUAL
            publishLocked()
        }
    }

    internal fun beginForTest(requestId: String): Boolean = synchronized(lock) {
        if (activeJob != null) return false
        activeJob = Job()
        activeRequestId = requestId
        true
    }

    fun cancel(requestId: String) {
        val job = synchronized(lock) {
            if (activeRequestId != requestId) null else activeJob.also {
                activeJob = null
                activeRequestId = null
                revokeLocked("cancelled")
            }
        }
        job?.cancel()
    }

    /** User or frontend selection. Does not navigate and does not create a tab. */
    fun select(tabId: String): Boolean = synchronized(lock) {
        val tab = registry.find(tabId) ?: return false
        if (selectedTabId == tabId) return true
        revokeLocked("tab selected")
        selectedTabId = tab.tabId
        publishLocked()
        true
    }

    fun close(tabId: String): Boolean = synchronized(lock) {
        val removed = registry.close(tabId) ?: return false
        if (selectedTabId == removed.tabId) {
            revokeLocked("tab closed")
            selectedTabId = registry.tabs().lastOrNull()?.tabId
            if (selectedTabId == null) releasePageLocked()
        }
        publishLocked()
        true
    }

    /** Moves the window off the agent path. A page that is no longer shown cannot be driven. */
    fun background() {
        synchronized(lock) {
            revokeLocked("background")
            publishLocked(shown = false)
        }
    }

    /**
     * Human takeover. Revokes and cancels before returning, then lets the page accept a normal
     * top-level navigation. This is not an agent grant.
     */
    fun takeover() {
        val job = synchronized(lock) {
            mode = ProviderMode.MANUAL
            page?.manualNavigation = true
            adapter?.clearRefs()
            val running = activeJob
            activeJob = null
            activeRequestId = null
            revokeLocked("takeover")
            publishLocked()
            running
        }
        job?.cancel()
    }

    /**
     * Re-probes the visible document, then grants only if the same view and epoch are still current.
     * A fresh observation is required. The witness id may stay the same only for that document.
     */
    suspend fun allow(): Boolean = withContext(main) {
        val snapshot = synchronized(lock) {
            val current = host
            val tabId = selectedTabId
            val tab = tabId?.let { registry.find(it) }
            val connection = connectionId
            if (current == null || tab == null || connection == null || tab.ownerSessionId.isBlank()) {
                null
            } else {
                AllowSnapshot(current, tab, connection, page, documentEpoch, viewGeneration)
            }
        } ?: return@withContext false
        val probed = snapshot.page?.probe() ?: return@withContext false
        if (probed !is ProbeAcceptance.Accept) return@withContext false
        val granted = synchronized(lock) {
            val current = host
            val pageNow = page
            current != null && pageNow != null &&
                current.instanceKey == snapshot.host.instanceKey &&
                current.consoleGeneration == snapshot.host.consoleGeneration &&
                pageNow === snapshot.page &&
                documentEpoch == snapshot.epoch &&
                viewGeneration == snapshot.generation &&
                selectedTabId == snapshot.tab.tabId &&
                connectionId == snapshot.connection
        }
        if (!granted) return@withContext false
        synchronized(lock) {
            val pageNow = page ?: return@synchronized
            pageNow.manualNavigation = false
            pageNow.syncWitness(probed.documentId)
            witnessForGrant = probed.documentId
            registry.grant(runtimeLaunchId, snapshot.connection, snapshot.tab.ownerSessionId, snapshot.tab.tabId)
            grantEpoch = registry.currentGrant()?.grantEpoch ?: 0L
            mode = ProviderMode.AGENT
            publishLocked()
        }
        true
    }

    override suspend fun execute(
        request: ProviderRequest,
        registry: ProviderRegistry,
        mode: ProviderMode,
        visibleTabId: String?,
        deadlineElapsed: Long,
        clock: () -> Long,
    ): ProviderResult {
        if (registry !== this.registry) {
            return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "registry mismatch")
        }
        if (!beginAction(request.requestId)) {
            return ProviderResult(false, errorCode = ProviderCodes.BUSY)
        }
        return try {
            action.withLock {
                dispatch(request, mode, visibleTabId, deadlineElapsed, clock)
            }
        } finally {
            endAction(request.requestId)
        }
    }

    private suspend fun beginAction(requestId: String): Boolean {
        val job = currentCoroutineContext().job
        return synchronized(lock) {
            if (activeJob != null) {
                false
            } else {
                activeJob = job
                activeRequestId = requestId
                true
            }
        }
    }

    private fun endAction(requestId: String) {
        synchronized(lock) {
            if (activeRequestId == requestId) {
                activeJob = null
                activeRequestId = null
            }
        }
    }

    private suspend fun dispatch(
        request: ProviderRequest,
        incomingMode: ProviderMode,
        visibleTabId: String?,
        deadlineElapsed: Long,
        now: () -> Long,
    ): ProviderResult {
        if (now() >= deadlineElapsed) return ProviderResult(false, errorCode = ProviderCodes.DEADLINE)
        val session = request.sessionId?.takeIf { it.isNotBlank() }
            ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST)
        val refused = synchronized(lock) { refusal(request, session) }
        if (refused != null) return refused
        if (request.action == "browser.open") {
            return openClaimed(request, session, deadlineElapsed, now)
        }
        val currentMode = synchronized(lock) { mode }
        val shown = synchronized(lock) { selectedTabId }
        val pageAdapter = synchronized(lock) { adapter } ?: return unavailable()
        ignored(incomingMode.name)
        ignored(visibleTabId)
        // WebView may only be touched on the main thread; the provider client calls from IO.
        return withContext(main) { pageAdapter.execute(request, registry, shown, currentMode, deadlineElapsed) }
    }

    /**
     * Validates the connection, host, context, deadline and URL, then creates exactly one controlled
     * session and loads it once. Returns the registry tab id with `drivable:false`.
     */
    suspend fun openClaimed(
        request: ProviderRequest,
        deadlineElapsed: Long,
    ): ProviderResult {
        val session = request.sessionId?.takeIf { it.isNotBlank() }
            ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST)
        return openClaimed(request, session, deadlineElapsed, clock)
    }

    private suspend fun openClaimed(
        request: ProviderRequest,
        session: String,
        deadlineElapsed: Long,
        now: () -> Long,
    ): ProviderResult = withContext(main) {
        if (now() >= deadlineElapsed) return@withContext ProviderResult(false, errorCode = ProviderCodes.DEADLINE)
        val prepared = synchronized(lock) {
            val currentHost = host
            val context = currentHost?.context
            if (currentHost == null || (!testHost && (context == null || !hostAlive(context)))) {
                null
            } else {
                PreparedOpen(currentHost, context)
            }
        } ?: return@withContext unavailable()
        val url = request.parameters["url"].orEmpty()
        if (url.isBlank() || policy().checkSyntax(url) !is UrlDecision.Allow) {
            return@withContext ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "url refused")
        }
        val named = request.parameters["tabId"]?.takeIf { it.isNotBlank() }
        if (named != null && registry.find(named) == null) {
            return@withContext ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "unknown tab")
        }
        if (named != null && registry.ownedByOther(session, request.directory, named)) {
            return@withContext ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "other session")
        }
        val created = sessionFactory(prepared.context) ?: return@withContext unavailable()
        val previous = synchronized(lock) {
            if (host?.consoleGeneration != prepared.host.consoleGeneration) {
                created.destroy()
                return@withContext unavailable()
            }
            revokeLocked("open")
            val old = page
            page = created
            viewGeneration += 1
            documentEpoch += 1
            adapter = adapterFor(created)
            bind(created)
            publishLocked(shown = true)
            old
        }
        previous?.destroy()
        val pageAdapter = synchronized(lock) { adapter } ?: return@withContext unavailable()
        val opened = pageAdapter.openClaimed(request, registry, session)
        synchronized(lock) {
            if (opened.result.ok) {
                selectedTabId = opened.tab?.tabId
                mode = ProviderMode.MANUAL
                created.manualNavigation = false
            }
            publishLocked(shown = opened.result.ok)
        }
        opened.result
    }

    private fun bind(created: ControlledBrowserPage) {
        created.onProbe = { viewId, ticket -> scope.launch { acceptProbe(created, viewId, ticket) } }
        created.onFatal = { onFatal(created) }
        created.onUnknown = { onUnknown(created) }
        created.onManualDocument = { onManualDocument(created) }
    }

    private suspend fun acceptProbe(source: ControlledBrowserPage, viewId: String, ticket: Long) {
        val raw = synchronized(lock) {
            if (page !== source || !source.owns(viewId, ticket)) return
            source
        }
        val probed = raw.probe()
        synchronized(lock) {
            if (page !== source || !source.owns(viewId, ticket)) return
            when (probed) {
                is ProbeAcceptance.Accept -> {
                    source.acceptReady(ticket, probed.documentId)
                    publishLocked()
                }
                is ProbeAcceptance.Pending -> Unit
                is ProbeAcceptance.Reject -> onUnknown(source)
            }
        }
    }

    private fun onFatal(source: ControlledBrowserPage) {
        synchronized(lock) {
            if (page !== source) return
            revokeLocked("fatal")
            releasePageLocked()
            publishLocked(error = "The page stopped")
        }
    }

    private fun onUnknown(source: ControlledBrowserPage) {
        synchronized(lock) {
            if (page !== source) return
            source.markUnknown()
            revokeLocked("unknown navigation")
            publishLocked()
        }
    }

    private fun onManualDocument(source: ControlledBrowserPage) {
        synchronized(lock) {
            if (page !== source || !source.manualNavigation) return
            source.clearAcceptedWitness()
            adapter?.clearRefs()
            documentEpoch += 1
            revokeLocked("manual document")
            publishLocked()
        }
    }

    private fun refusal(request: ProviderRequest, session: String): ProviderResult? {
        if (host == null) return unavailable()
        if (connectionId == null || connectionId != request.connectionId) {
            return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "connection refused")
        }
        if (request.action != "browser.open") {
            val named = request.parameters["tabId"]
            val target = if (named.isNullOrBlank()) registry.find(selectedTabId.orEmpty()) else registry.find(named)
            if (target == null || target.ownerSessionId != session) {
                return ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)
            }
        }
        return null
    }

    private fun adapterFor(page: ControlledBrowserPage) = OpenChamberPageAdapter(
        driver = page.driver(policy),
        present = { tab -> page.loadOnce(tab.url) },
        instanceKey = host?.instanceKey ?: "local",
        clock = clock,
        viewportSize = { page.width to page.height },
        documentFields = { ref -> page.currentField(ref)?.let { mapOf(ref to it) } },
    )

    private fun revokeLocked(reason: String) {
        ignored(reason)
        registry.revokeEpoch()
        grantEpoch = 0L
        witnessForGrant = null
        mode = ProviderMode.MANUAL
        documentEpoch += 1
    }

    private fun releasePageLocked() {
        page?.destroy()
        page = null
        adapter = null
        viewGeneration += 1
    }

    private fun publishLocked(shown: Boolean? = null, error: String? = page?.pageError) {
        val tabs = registry.tabs().map { tab ->
            NativeBrowserTab(
                id = tab.tabId,
                title = tab.title,
                url = tab.url,
                owner = tab.ownerSessionId,
                active = tab.tabId == selectedTabId,
            )
        }
        val visible = shown ?: (selectedTabId != null && page != null && host != null)
        _state.value = NativeBrowserState(
            tabs = tabs,
            selectedTabId = selectedTabId,
            shown = visible,
            mode = mode,
            agentOn = mode == ProviderMode.AGENT && registry.currentGrant() != null,
            pageError = error,
            pageReady = page?.ready == true,
            viewHandle = page?.handle ?: 0,
            viewGeneration = viewGeneration,
            hostAttached = host != null,
            documentEpoch = documentEpoch,
        )
    }

    private fun unavailable() = ProviderResult(
        ok = false,
        errorCode = "BROWSER_PAGE_UNAVAILABLE",
        message = "native browser host is not attached",
    )

    private fun ignored(value: String?) = value

    private data class Host(val context: Context?, val instanceKey: String, val consoleGeneration: Long)
    private data class PreparedOpen(val host: Host, val context: Context?)
    private data class AllowSnapshot(
        val host: Host,
        val tab: ProviderTab,
        val connection: String,
        val page: ControlledBrowserPage?,
        val epoch: Long,
        val generation: Long,
    )
}

/**
 * The page operations the presenter needs. Production uses [WebControlledPage]. Tests supply a
 * fake so the authority rules do not need a WebView.
 */
interface ControlledBrowserPage {
    val view: View?
    val handle: Int
    val width: Int
    val height: Int
    val pageError: String?
    val ready: Boolean
    var manualNavigation: Boolean
    var onProbe: ((String, Long) -> Unit)?
    var onFatal: (() -> Unit)?
    var onUnknown: (() -> Unit)?
    var onManualDocument: (() -> Unit)?
    fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver
    suspend fun loadOnce(url: String): Boolean
    suspend fun probe(): ProbeAcceptance
    fun acceptReady(ticket: Long, documentId: String)
    fun owns(viewId: String, ticket: Long): Boolean
    fun markUnknown()
    fun clearAcceptedWitness()
    fun syncWitness(documentId: String)
    suspend fun currentFields(): Map<String, PageField>?
    suspend fun currentField(ref: String): PageField? = currentFields()?.get(ref)
    fun destroy()
}

private fun presenterHostAlive(context: Context): Boolean {
    val activity = context as? Activity ?: return false
    return !activity.isFinishing && !activity.isDestroyed
}

internal class WebControlledPage(
    private val session: BrowserSession,
) : ControlledBrowserPage {
    override val view: View get() = session.view
    override val handle: Int get() = session.view.hashCode()
    override val width: Int get() = session.view.width
    override val height: Int get() = session.view.height
    override val pageError: String? get() = session.pageError
    override val ready: Boolean get() = session.committed && !session.unknownNavigation
    override var manualNavigation: Boolean
        get() = session.manualNavigation
        set(value) { session.manualNavigation = value }
    override var onProbe: ((String, Long) -> Unit)?
        get() = session.onProbe
        set(value) { session.onProbe = value }
    override var onFatal: (() -> Unit)?
        get() = session.onFatal
        set(value) { session.onFatal = value }
    override var onUnknown: (() -> Unit)?
        get() = session.onUnknown
        set(value) { session.onUnknown = value }
    override var onManualDocument: (() -> Unit)? = null
        set(value) {
            field = value
            session.onManualDocument = value
        }
    private var loaded = false

    override fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver = BrowserPageDriver(
        session = { session },
        policy = policy,
        navigateControlled = { false },
    )

    override suspend fun loadOnce(url: String): Boolean {
        if (loaded) return false
        loaded = true
        session.beginControlledGet()
        if (!session.installWitness()) return false
        session.load(url, session.epoch())
        return true
    }

    override suspend fun probe(): ProbeAcceptance {
        val raw = session.eval(BrowserDriverLogic.probeScript(), session.epoch()) ?: return ProbeAcceptance.Reject("no probe")
        val allowed = session.displayUrl?.let { BrowserUrlPolicy().checkSyntax(it) is UrlDecision.Allow } == true
        // Allow is a fresh observation of the document already shown; the same witness is expected.
        return BrowserDriverLogic.acceptProbe(session.acceptedDocumentId, raw, allowed, allowSameDocument = true)
    }

    override fun acceptReady(ticket: Long, documentId: String) = session.acceptReady(ticket, documentId)
    override fun owns(viewId: String, ticket: Long): Boolean = session.owns(viewId, ticket)
    override fun markUnknown() = session.markUnknownNavigation()
    override fun clearAcceptedWitness() {
        session.acceptedDocumentId = null
        session.invalidateRefs()
    }
    override fun syncWitness(documentId: String) {
        session.acceptedDocumentId = documentId
    }
    override suspend fun currentFields(): Map<String, PageField>? = null

    override suspend fun currentField(ref: String): PageField? {
        val raw = session.eval(BrowserDriverLogic.fieldScript(session.documentNonce, ref), session.epoch()) ?: return null
        val parsed = BrowserDriverLogic.parseScriptResult(raw)
        val json = (parsed as? ScriptResult.Ok)?.json ?: return null
        return PageField(
            role = json["role"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            type = json["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            name = json["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            submit = json["submit"]?.jsonPrimitive?.booleanOrNull == true,
        )
    }
    override fun destroy() = session.destroy()
}
