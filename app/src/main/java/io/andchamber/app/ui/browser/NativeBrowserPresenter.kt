package io.andchamber.app.ui.browser

import android.app.Activity
import android.content.Context
import android.view.View
import io.andchamber.app.browser.BrowserDriver
import io.andchamber.app.browser.BrowserUrlPolicy
import io.andchamber.app.browser.UrlDecision
import io.andchamber.app.browser.provider.AuthorityStamp
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
    private val pages = linkedMapOf<String, BoundPage>()
    private var page: ControlledBrowserPage? = null
    private var adapter: OpenChamberPageAdapter? = null
    private var paused = false
    private var consoleInvalidated = false
    private var connectionOwner: ConnectionOwner? = null
    private var loadJob: Job? = null
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

    /** Trusted host is attached and the chat page is still in the foreground. */
    val foreground: Boolean get() = synchronized(lock) { host != null && !paused && !consoleInvalidated }

    /** Unit tests attach a host without constructing an Activity. Production callers use the Activity overload. */
    internal fun attachHostForTest(instanceKey: String, consoleGeneration: Long) {
        consoleInvalidated = false
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
        val previous = synchronized(lock) { host }
        when {
            // Another instance, or another Activity: nothing from the old host may survive.
            previous != null && (previous.instanceKey != instanceKey || previous.context !== context) -> {
                revokeAll("host replaced")
                synchronized(lock) { releasePageLocked() }
            }
            // A new document of the same trusted console (load, reload, re-commit): the old page's
            // bridge is gone, so the grant goes too, but the native tabs and their pages stay.
            previous != null && previous.consoleGeneration != consoleGeneration -> revokeAll("console document changed")
        }
        consoleInvalidated = false
        synchronized(lock) {
            host = Host(context, instanceKey, consoleGeneration)
            publishLocked()
        }
    }

    /** The trusted console is gone (screen left, host untrusted): detach whatever generation is attached. */
    /**
     * The trusted console document started loading again. Revokes immediately and closes host
     * admission so the binding drops SSE. Tabs and pages stay. A later attachHost restores admission
     * only; it does not restore the grant.
     */
    fun invalidateConsoleDocument(instanceKey: String) {
        val matches = synchronized(lock) { host?.instanceKey == instanceKey }
        if (!matches) return
        consoleInvalidated = true
        revokeAll("console document invalidated")
        synchronized(lock) { publishLocked() }
    }

    fun detachHostAny(instanceKey: String) {
        val generation = synchronized(lock) { host?.takeIf { it.instanceKey == instanceKey }?.consoleGeneration } ?: return
        detachHost(instanceKey, generation)
    }

    fun detachHost(instanceKey: String, consoleGeneration: Long) {
        val matches = synchronized(lock) {
            val current = host ?: return
            current.instanceKey == instanceKey && current.consoleGeneration == consoleGeneration
        }
        android.util.Log.i("AndChamberProvider", "detachHost generation=$consoleGeneration matches=$matches")
        if (!matches) return
        revokeAll("host left")
        synchronized(lock) {
            releasePageLocked()
            host = null
            publishLocked()
        }
    }

    /**
     * Clears only the connection [owner] published. A stopping client's late `null` must not wipe the
     * connection a newer client already adopted.
     */
    fun clearConnection(owner: String?, runtimeLaunchId: Long, clientGeneration: Long = Long.MAX_VALUE) {
        val cleared = synchronized(lock) {
            val current = connectionOwner
            if (owner == null || this.connectionId != owner || this.runtimeLaunchId != runtimeLaunchId) return@synchronized false
            if (current != null && clientGeneration != Long.MAX_VALUE && clientGeneration < current.clientGeneration) return@synchronized false
            this.connectionId = null
            connectionOwner = current?.copy(connectionId = null)
            true
        }
        if (cleared) {
            revokeAll("connection closed")
            synchronized(lock) { publishLocked() }
        }
    }

    fun setConnection(connectionId: String?, runtimeLaunchId: Long) =
        setConnection(connectionId, runtimeLaunchId, clientGeneration = 0L)

    /**
     * Connection reports are identities, not grants. Only the current launch and client generation
     * may change the connection. A stopping older client cannot wipe a newer grant.
     */
    fun setConnection(connectionId: String?, runtimeLaunchId: Long, clientGeneration: Long) {
        val adopt = synchronized(lock) {
            val current = connectionOwner
            if (current != null && (runtimeLaunchId < current.launchId ||
                    runtimeLaunchId == current.launchId && clientGeneration < current.clientGeneration)
            ) {
                return
            }
            connectionOwner = ConnectionOwner(runtimeLaunchId, clientGeneration, connectionId)
            this.connectionId = connectionId
            this.runtimeLaunchId = runtimeLaunchId
            true
        }
        if (adopt) revokeAll("connection replaced")
        synchronized(lock) { publishLocked() }
    }

    /**
     * The only revoke path. Epoch, the active job and page refs move together before this returns.
     * A later allow cannot revive the old grant.
     */
    fun revokeAll(reason: String) = revokeAll(reason, keepRequestId = null)

    /**
     * Single revoke path. [keepRequestId] is the caller's own request: an open that revokes the
     * previous grant must not cancel itself. Every other in-flight page action is cancelled.
     */
    private fun revokeAll(reason: String, keepRequestId: String?) {
        // Reasons are fixed internal strings; no page or request content is logged.
        android.util.Log.i("AndChamberProvider", "revoke reason=$reason")
        val job = synchronized(lock) {
            ignored(reason)
            registry.revokeEpoch()
            grantEpoch = 0L
            witnessForGrant = null
            mode = ProviderMode.MANUAL
            documentEpoch += 1
            adapter?.clearRefs()
            pages.values.forEach {
                it.adapter.clearRefs()
                if (it.mutationInFlight) {
                it.outcomeUnknown = true
                it.mutationInFlight = false
            }
            }
            loadJob?.cancel()
            val running = activeJob
            if (running != null && keepRequestId != null && activeRequestId == keepRequestId) return@synchronized null
            // Reason is a fixed internal string; nothing from the page or the request is logged.
            if (running != null) android.util.Log.i("AndChamberProvider", "revoke cancels active action reason=$reason")
            activeJob = null
            activeRequestId = null
            running
        }
        job?.cancel()
        // Every revoke is visible at once: the window must never keep showing "agent on".
        synchronized(lock) { publishLocked() }
    }

    /** Host left the foreground. Revokes, but keeps the window so resume can show it again. */
    fun onHostPaused() {
        paused = true
        revokeAll("host paused")
        synchronized(lock) { publishLocked(shown = false) }
    }

    /** Restores presentation only. A paused grant is not restored. */
    fun onHostResumed() {
        paused = false
        synchronized(lock) { publishLocked() }
    }

    /**
     * The user Browser entry. The owner is recorded on the tab and is not a grant.
     * This does not authorize the page.
     */
    suspend fun openUser(url: String, ownerSessionId: String): ProviderResult {
        val request = ProviderRequest(
            serverEpoch = "user",
            connectionId = connectionId.orEmpty(),
            requestId = "user-open",
            action = "browser.open",
            parameters = mapOf("url" to url),
            directory = "/",
            sessionId = ownerSessionId,
            timeoutMs = 1_000L,
        )
        return openClaimed(request, Long.MAX_VALUE)
    }

    /** Binding compatibility. Allow itself re-probes; this only records the requested tab. */
    fun allowAsync(sessionId: String, tabId: String) {
        val tab = registry.find(tabId) ?: return
        if (tab.ownerSessionId != sessionId) return
        select(tabId)
        scope.launch { allow() }
    }

    fun revokeFromBinding() {
        revokeAll("binding revoke")
        synchronized(lock) { publishLocked() }
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
        if (synchronized(lock) { activeRequestId } != requestId) return
        revokeAll("cancelled")
    }

    /** User or frontend selection. Does not navigate and does not create a tab. */
    fun select(tabId: String): Boolean {
        val changed = synchronized(lock) {
            val tab = registry.find(tabId) ?: return false
            if (selectedTabId == tabId && pages[tabId] != null) return true
            selectedTabId = tab.tabId
            val bound = pages[tab.tabId]
            page = bound?.page
            adapter = bound?.adapter
            viewGeneration += 1
            true
        }
        if (changed) revokeAll("tab selected")
        materializeSelected()
        synchronized(lock) { publishLocked() }
        return true
    }

    /**
     * A tab registered while another owner's page was visible has no WebView yet. Selecting it is the
     * user's choice to see it, so its page is created and loaded once here. This is not a grant.
     */
    private fun materializeSelected() {
        val request = synchronized(lock) {
            val tabId = selectedTabId ?: return
            if (pages[tabId] != null) return
            if (!foreground) return
            val tab = registry.find(tabId) ?: return
            val current = host ?: return
            if (current.context != null && !hostAlive(current.context)) return
            Materialize(tab, PreparedOpen(current, current.context), documentEpoch)
        }
        val tab = request.tab
        val prepared = request.prepared
        val epoch = request.epoch
        val created = sessionFactory(prepared.context) ?: return
        val pageAdapter = adapterFor(created)
        val admitted = synchronized(lock) {
            selectedTabId == tab.tabId && pages[tab.tabId] == null && foreground &&
                host?.consoleGeneration == prepared.host.consoleGeneration && documentEpoch == epoch
        }
        if (!admitted) {
            created.destroy()
            return
        }
        synchronized(lock) {
            created.tabId = tab.tabId
            pages[tab.tabId] = BoundPage(created, pageAdapter, viewGeneration)
            page = created
            adapter = pageAdapter
            mode = ProviderMode.MANUAL
            created.manualNavigation = false
            bind(created)
        }
        loadJob = scope.launch {
            val still = synchronized(lock) {
                foreground && selectedTabId == tab.tabId && documentEpoch == epoch && pages[tab.tabId]?.page === created
            }
            if (!still || !created.loadOnce(tab.url)) synchronized(lock) { pages[tab.tabId]?.outcomeUnknown = true }
            synchronized(lock) { publishLocked() }
        }
    }

    fun close(tabId: String): Boolean {
        val removed = synchronized(lock) { registry.close(tabId) } ?: return false
        pages.remove(removed.tabId)?.page?.destroy()
        if (selectedTabId == removed.tabId) {
            synchronized(lock) {
                selectedTabId = registry.tabs().lastOrNull()?.tabId
                val bound = selectedTabId?.let { pages[it] }
                page = bound?.page
                adapter = bound?.adapter
                viewGeneration += 1
                if (selectedTabId == null) releasePageLocked()
            }
            revokeAll("tab closed")
        }
        synchronized(lock) { publishLocked() }
        return true
    }

    /** Moves the window off the agent path. A page that is no longer shown cannot be driven. */
    fun background() {
        revokeAll("background")
        synchronized(lock) { publishLocked(shown = false) }
    }

    /**
     * Human takeover. Revokes and cancels before returning, then lets the page accept a normal
     * top-level navigation. This is not an agent grant.
     */
    fun takeover() {
        synchronized(lock) {
            mode = ProviderMode.MANUAL
            page?.manualNavigation = true
            pages[selectedTabId]?.page?.manualNavigation = true
        }
        revokeAll("takeover")
        synchronized(lock) { publishLocked() }
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
            val bound = tabId?.let { pages[it] }
            val refusal = when {
                current == null -> "no host"
                tab == null -> "no tab"
                connection == null -> "no connection"
                tab.ownerSessionId.isBlank() -> "no owner"
                bound == null -> "no page"
                bound.outcomeUnknown -> "outcome unknown"
                bound.page.tabId != tab.tabId -> "page tab mismatch"
                !foreground -> "not foreground"
                else -> null
            }
            if (refusal != null) {
                android.util.Log.i("AndChamberProvider", "allow refused: $refusal")
                null
            } else {
                // Every value was checked non-null in the refusal above.
                AllowSnapshot(current!!, tab!!, connection!!, bound!!.page, documentEpoch, bound.generation)
            }
        } ?: return@withContext false
        if (!foreground) return@withContext false
        if (synchronized(lock) { pages[snapshot.tab.tabId]?.outcomeUnknown } == true) {
            synchronized(lock) {
                _state.value = _state.value.copy(pageError = "Re-open this page before allowing control")
            }
            return@withContext false
        }
        val probed = snapshot.page?.probe() ?: return@withContext false
        if (!foreground) return@withContext false
        if (probed !is ProbeAcceptance.Accept) {
            android.util.Log.i("AndChamberProvider", "allow refused: probe ${(probed as? ProbeAcceptance.Reject)?.message ?: probed.javaClass.simpleName}")
            return@withContext false
        }
        val granted = synchronized(lock) {
            val current = host
            val pageNow = page
            val bound = pages[snapshot.tab.tabId]
            current != null && pageNow != null && bound != null && !bound.outcomeUnknown &&
                current.instanceKey == snapshot.host.instanceKey &&
                current.consoleGeneration == snapshot.host.consoleGeneration &&
                pageNow === snapshot.page &&
                bound.page === snapshot.page &&
                documentEpoch == snapshot.epoch &&
                bound.generation == snapshot.generation &&
                selectedTabId == snapshot.tab.tabId &&
                connectionId == snapshot.connection
        }
        if (!granted) {
            android.util.Log.i("AndChamberProvider", "allow refused: changed during probe")
            return@withContext false
        }
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
        if (!foreground) return unavailable()
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
        // Outcome-unknown is decided by the adapter at dispatch time (no reply after the action was
        // sent). A refusal before dispatch (expired ref, no grant, refused field) changed nothing.
        // Cancellation mid-dispatch is marked by the adapter's dispatch hook before it propagates.
        val result = withContext(main) { pageAdapter.execute(request, registry, shown, currentMode, deadlineElapsed) }
        if (result.errorCode == ProviderCodes.OUTCOME_UNKNOWN) {
            synchronized(lock) { pages[shown]?.outcomeUnknown = true; publishLocked() }
        }
        return result
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
        if (!foreground) return@withContext unavailable()
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
        val named = request.parameters["tabId"]?.takeIf { it.isNotBlank() }
        if (named != null && registry.find(named) == null) {
            return@withContext ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "unknown tab")
        }
        if (named != null && registry.ownedByOther(session, request.directory, named)) {
            return@withContext ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "other session")
        }
        val url = request.parameters["url"].orEmpty()
        if (url.isBlank() || policy().checkSyntax(url) !is UrlDecision.Allow) {
            return@withContext ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "url refused")
        }
        val activeOwner = synchronized(lock) { registry.find(selectedTabId.orEmpty())?.ownerSessionId }
        if (activeOwner != null && activeOwner != session && pages[selectedTabId] != null) {
            // Keep the visible owner's page. The other owner gets a registry tab and no WebView.
            val parked = OpenChamberPageAdapter(driver = UnavailableDriver, present = { true })
                .openClaimed(request, registry, session)
            synchronized(lock) { publishLocked() }
            return@withContext parked.result
        }
        if (!foreground) return@withContext unavailable()
        val created = sessionFactory(prepared.context) ?: return@withContext unavailable()
        val replacingOwn = synchronized(lock) { activeOwner == null || activeOwner == session }
        if (!replacingOwn) {
            created.destroy()
            return@withContext ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "other session")
        }
        synchronized(lock) {
            if (host?.consoleGeneration != prepared.host.consoleGeneration) {
                created.destroy()
                return@withContext unavailable()
            }
        }
        revokeAll("open", keepRequestId = request.requestId)
        val pageAdapter = adapterFor(created)
        android.util.Log.i("AndChamberProvider", "open step=load-start")
        val opened = pageAdapter.openClaimed(request, registry, session)
        android.util.Log.i("AndChamberProvider", "open step=load-done ok=${opened.result.ok}")
        val tabId = opened.tab?.tabId
        synchronized(lock) {
            if (opened.result.ok && tabId != null) {
                created.tabId = tabId
                pages[tabId] = BoundPage(created, pageAdapter, viewGeneration + 1)
                page = created
                adapter = pageAdapter
                selectedTabId = tabId
                viewGeneration += 1
                documentEpoch += 1
                mode = ProviderMode.MANUAL
                created.manualNavigation = false
                bind(created)
            } else {
                created.destroy()
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
        val current = synchronized(lock) { page === source }
        if (!current) return
        revokeAll("fatal")
        synchronized(lock) {
            releasePageLocked()
            publishLocked(error = "The page stopped")
        }
    }

    private fun onUnknown(source: ControlledBrowserPage) {
        val current = synchronized(lock) { page === source }
        if (!current) return
        source.markUnknown()
        revokeAll("unknown navigation")
        synchronized(lock) { publishLocked() }
    }

    private fun onManualDocument(source: ControlledBrowserPage) {
        synchronized(lock) {
            if (page !== source || !source.manualNavigation) return
            source.clearAcceptedWitness()
            pages[source.tabId]?.outcomeUnknown = false
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
        documentFields = { ref -> page.currentField(ref)?.let { mapOf(ref to it) } ?: emptyMap() },
        requireCurrentDom = true,
        authority = {
            val grant = registry.currentGrant()
            AuthorityStamp(
                grantEpoch = grant?.grantEpoch ?: 0L,
                documentId = page.acceptedDocument(),
                tabId = page.tabId,
                granted = grant != null && grant.tabId == page.tabId && grant.tabId == selectedTabId,
            )
        },
        onOutcomeUnknown = {
            synchronized(lock) { pages[page.tabId]?.outcomeUnknown = true }
        },
        onDispatch = {
            synchronized(lock) { pages[page.tabId]?.mutationInFlight = true }
        },
        onDispatchDone = { reply ->
            synchronized(lock) {
                val bound = pages[page.tabId] ?: return@synchronized
                bound.mutationInFlight = false
                if (reply == null) bound.outcomeUnknown = true
            }
        },
    )

    private fun revokeLocked(reason: String) = revokeAll(reason)

    private fun releasePageLocked() {
        pages.values.forEach { it.page.destroy() }
        pages.clear()
        page?.destroy()
        page = null
        adapter = null
        viewGeneration += 1
    }

    private fun publishLocked(shown: Boolean? = null, error: String? = page?.pageError) {
        val forced = if (pages[selectedTabId]?.outcomeUnknown == true) "Re-open this page before allowing control" else error
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
            pageError = forced,
            pageReady = page?.ready == true,
            viewHandle = if (page == null) 0 else viewGeneration.toInt(),
            viewGeneration = viewGeneration,
            hostAttached = host != null,
            documentEpoch = documentEpoch,
            paused = paused,
            eligible = host != null && !paused && !consoleInvalidated,
            connected = connectionId != null,
        )
    }

    private fun unavailable() = ProviderResult(
        ok = false,
        errorCode = "BROWSER_PAGE_UNAVAILABLE",
        message = "native browser host is not attached",
    )

    private fun ignored(value: String?) = value

    private data class BoundPage(
        val page: ControlledBrowserPage,
        val adapter: OpenChamberPageAdapter,
        val generation: Long,
        var outcomeUnknown: Boolean = false,
        var mutationInFlight: Boolean = false,
    )

    private data class ConnectionOwner(
        val launchId: Long,
        val clientGeneration: Long,
        val connectionId: String?,
    )

    private data class Host(val context: Context?, val instanceKey: String, val consoleGeneration: Long)
    private data class PreparedOpen(val host: Host, val context: Context?)
    private data class Materialize(val tab: ProviderTab, val prepared: PreparedOpen, val epoch: Long)
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
    var tabId: String?
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
    fun acceptedDocument(): String? = null
    fun destroy()
}

private object UnavailableDriver : BrowserDriver {
    override suspend fun execute(request: io.andchamber.app.browser.BrowserRequest): io.andchamber.app.browser.BrowserReply =
        io.andchamber.app.browser.BrowserReply(ok = false)
}

private fun presenterHostAlive(context: Context): Boolean {
    val activity = context as? Activity ?: return false
    return !activity.isFinishing && !activity.isDestroyed
}

internal class WebControlledPage(
    private val session: BrowserSession,
) : ControlledBrowserPage {
    override var tabId: String? = null
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
    override fun acceptedDocument(): String? = session.acceptedDocumentId
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
