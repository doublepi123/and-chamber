package io.andchamber.app.ui.browser

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import io.andchamber.app.BuildConfig
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Bounded wait for the native page to be attached and shown again before a hidden-window wake
     * asks allow() for default control. Production waits a short bounded interval; tests inject a
     * deterministic seam. It never grants by itself.
     */
    private val awaitPageVisible: suspend (ControlledBrowserPage) -> Boolean = { page ->
        withTimeoutOrNull(2_000L) {
            while (!page.nativePresentationVisible()) delay(16)
            true
        } ?: false
    },
) : ProviderExecutor {
    private val lock = Any()
    private val action = Mutex()
    private val _state = MutableStateFlow(NativeBrowserState())
    val state: StateFlow<NativeBrowserState> = _state.asStateFlow()

    private var host: Host? = null
    private var testHost: Boolean = false
    private val pages = linkedMapOf<String, BoundPage>()
    private var page: ControlledBrowserPage? = null
    /** Test-only observer installed before the first page; production leaves it null. */
    private var pageDecorator: ((ControlledBrowserPage) -> Unit)? = null
    private var pageCreated = false
    private var adapter: OpenChamberPageAdapter? = null
    private var paused = false
    /**
     * The person explicitly hid the window. A hidden page is kept mounted, but it is not presented
     * and it is not admitted: no callback, probe, resume or state publish may show it again. Only
     * [show]/[restore], an explicit open, or an agent action through [restoreForAgentAction] clears
     * it. The latter is blocked by [explicitTakeover]. It never grants anything by itself.
     */
    private var hidden = false
    /**
     * A person explicitly took the page over. This is stronger than [hidden]: an agent action may
     * bring back a merely hidden window, but it must never override an explicit takeover. A new
     * explicit open or an explicit Allow clears it.
     */
    private var explicitTakeover = false
    /** Monotonic one-shot request for the UI to present the small floating window. */
    private var presentationRequestSerial = 0L
    private var consoleInvalidated = false
    /** The chat session the trusted console currently shows. An identity, never a grant. */
    private var consoleSessionId: String? = null
    /** Presentation gate set by the floating window. It is not a grant and never creates a page. */
    private var presentationBlocked = false
    private val tabModes = linkedMapOf<String, Boolean>()
    /**
     * Legacy presentation set from before default control. An explicit open no longer waits for an
     * Allow tap, so nothing is added here; the field and its state output stay for binding
     * compatibility (always false).
     */
    private val awaitingAllowTabs = linkedSetOf<String>()
    /**
     * One-shot default-control decision made by an explicit open (agent tool or the person's own
     * entry). It is bound to that page, load ticket, tab and console generation; the next accepted
     * ready probe consumes it and asks [allow] for the actual grant. It is never authority itself.
     * Every revoke, takeover, hide, connection change, retirement, selection or close drops it, so
     * a later probe, page-finished callback or restored window can never grant again on its own.
     */
    private var autoGrantIntent: AutoGrantIntent? = null
    /** One bounded retry of a pending default-control intent; never a loop. */
    private var autoGrantRetryActive = false
    /**
     * A hidden-window agent wake that could not complete because the kept page was not mounted yet
     * or allow() failed. It is bound to the page, tab, owner session and original connection, and a
     * later foreground action retries it. Connection/owner/takeover/hide/page changes clear it.
     */
    private var pendingWake: PendingWake? = null
    private var mouseMode = false
    private var pointerViewX = 0f
    private var pointerViewY = 0f
    private var geometryGeneration = 0L
    private var currentProbeSerial = 0L
    private var geometry: MeasuredGeometry? = null
    /** The latest post-accept geometry request that has not produced a sample yet. */
    private var inflightGeometry: GeometryRequest? = null
    private var gestureDropped = false
    /** Raw-input fence. A new selected view gets a new gesture that waits for a fresh DOWN. */
    private val inputAdmission = InputAdmission()
    private val inputMemo = InputEventMemo()
    private val gesture: TouchpadGesture get() = inputAdmission.gesture
    private val mouseSupport = NativeMouseSupport()
    internal var mouseProbe: () -> MouseProbeResult = {
        when (val support = mouseSupport.probe()) {
            MouseSupport.Supported -> MouseProbeResult(true)
            is MouseSupport.Unsupported -> MouseProbeResult(false, support.reason)
        }
    }
    private var connectionOwner: ConnectionOwner? = null
    private var loadJob: Job? = null
    private var connectionId: String? = null
    private var runtimeLaunchId: Long = 0L
    private var selectedTabId: String? = null
    private var mode = ProviderMode.MANUAL
    private var grantEpoch = 0L
    private var documentEpoch = 0L
    private var activeJob: Job? = null
    private var activeAction: String? = null
    private var activeDispatched = false
    private var activePageAccess = false
    /** Tab whose page the active action dispatched to. Lets a retirement cancel only that action. */
    private var activeTab: String? = null
    private val scrollMemory = ScrollMemory()
    private var interactionSeq = 0L
    private var modeSeq = 0L
    private val _windowRestoreResult = MutableStateFlow<RestoreResult?>(null)
    internal val windowRestoreResult: StateFlow<RestoreResult?> = _windowRestoreResult.asStateFlow()
    private var activeRequestId: String? = null
    /** True while a tool `open` is watching for control: nothing of its own is in flight. */
    private var allowWaitActive = false
    private var viewGeneration = 0L
    private var witnessForGrant: String? = null

    /** UI and tests observe the page the presenter owns. Null until a claimed open creates one. */
    fun currentView(): View? = synchronized(lock) {
        val bound = pages[selectedTabId] ?: return@synchronized null
        if (bound.retired) null else bound.page.view
    }

    /**
     * Trusted host is attached, the chat page is in the foreground, and the window presents the
     * page. An explicitly hidden window is never foreground, even if it was hidden while paused
     * and the pause later ends.
     */
    val foreground: Boolean
        get() = synchronized(lock) { presentableLocked() && !hidden }

    /** Host and console admission, ignoring the person's hide choice. */
    private fun presentableLocked(): Boolean =
        host != null && !paused && !consoleInvalidated && !presentationBlocked

    /**
     * The floating window's temporary presentation gate. It changes admission only: while blocked,
     * input, allow and page commands refuse, but the grant is kept. A transient UI block (keyboard,
     * composer, window mode change) must not lose authority; real page/host failures revoke through
     * their own paths. Turning it off does not grant, reload, reopen or create a page, and a press
     * that may already have reached the page is still retired so it cannot complete later.
     * Internal because only the in-app floating window (same module) calls it.
     */
    internal fun setPresentationBlocked(blocked: Boolean) {
        val changed = synchronized(lock) {
            if (presentationBlocked == blocked) return@synchronized false
            presentationBlocked = blocked
            true
        }
        if (!changed) return
        if (blocked) {
            // Retire first: a press that may already have reached the page is never left for a later UP.
            retireActivePress("presentation-blocked")
            // Close the raw-input fence too. A leftover MOVE/UP after the gate clears must not click,
            // and the next admitted touch waits for a fresh DOWN on the same still-mounted view.
            inputAdmission.bind(null)
            inputMemo.clear()
        }
        synchronized(lock) { publishLocked() }
        if (!blocked) scheduleAutoGrantRetry()
    }

    /**
     * Unit tests attach a host without constructing an Activity. Production callers use the Activity
     * overload. Debug builds only: a release build refuses this entry point.
     */
    internal fun attachHostForTest(instanceKey: String, consoleGeneration: Long) {
        check(BuildConfig.DEBUG) { "attachHostForTest is debug-only" }
        consoleInvalidated = false
        testHost = true
        synchronized(lock) {
            host = Host(null, instanceKey, consoleGeneration)
            publishLocked()
        }
    }

    /**
     * Test-only. Installs an observer that sees each production page exactly once, synchronously at
     * creation and before the first mode change, load, bind or registry open. It only observes or
     * attaches a test WebViewClient; it receives the original page and cannot replace it. Accepted
     * once, before any page exists. Production never calls this, and a release build refuses it.
     */
    internal fun installPageDecorator(decorator: (ControlledBrowserPage) -> Unit) {
        check(BuildConfig.DEBUG) { "installPageDecorator is debug-only" }
        synchronized(lock) {
            check(!pageCreated && pages.isEmpty() && page == null && !activePageAccess && loadJob == null) {
                "page decorator must be installed before the first page"
            }
            check(pageDecorator == null) { "page decorator already installed" }
            pageDecorator = decorator
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
                // A different window starts visible-eligible; a hide belongs to the window it was made in.
                synchronized(lock) {
                    hidden = false
                    explicitTakeover = false
                }
                // The console document may survive (pooled WebView) across an Activity change, and it
                // would not re-report the same session. Its recorded session stays valid here.
                synchronized(lock) { releasePageLocked() }
            }
            // A new document of the same trusted console (load, reload, re-commit): the old page's
            // bridge is gone, so the grant goes too, but the native tabs and their pages stay.
            previous != null && previous.consoleGeneration != consoleGeneration -> {
                // The trusted console document was replaced under a press: that press cannot survive.
                retireActivePress("console-document-changed")
                revokeAll("console document changed")
                // Keep the recorded session. The console's sync is module-scoped and deduplicates by
                // session, so a same-document SPA re-commit (each chat switch commits a new URL) never
                // re-reports it; clearing here left every later open parked. A real new document
                // re-reports on mount and overwrites it. Only detach (host left) clears it.
            }
        }
        consoleInvalidated = false
        synchronized(lock) {
            // A fresh window starts un-hidden; only a re-attach of the same window keeps the choice.
            if (previous == null) {
                hidden = false
                explicitTakeover = false
            }
            host = Host(context, instanceKey, consoleGeneration)
            publishLocked()
        }
    }

    /**
     * Records the chat session the console currently shows. It is an identity, not a grant: it only
     * lets an open from that same session take the window from another owner's visible page. The
     * console reports it on every chat change; a blank value means unknown, which keeps the parked
     * behaviour for every owner.
     */
    internal fun setConsoleSession(sessionId: String?) {
        val next = sessionId?.takeIf { it.isNotBlank() }
        synchronized(lock) { consoleSessionId = next }
    }

    /** The trusted console is gone (screen left, host untrusted): detach whatever generation is attached. */
    /**
     * The trusted console document started loading again. Revokes immediately and closes host
     * admission so the binding drops SSE. Tabs and pages stay. A later attachHost restores admission
     * only; it does not restore the grant.
     */
    fun setDesktopMode(enabled: Boolean): ModeSwitch = applyMode(enabled, keepRequestId = null)

    fun resizeViewport(viewport: String, keepRequestId: String?): ModeSwitch {
        val enabled = when (viewport) {
            "desktop" -> true
            "mobile" -> false
            "fill" -> tabModes[selectedTabId] == true
            else -> return ModeSwitch.ReopenRequired
        }
        return applyMode(enabled, keepRequestId)
    }

    private fun applyMode(enabled: Boolean, keepRequestId: String?): ModeSwitch {
        // A press in progress defers the layout change. Nothing is applied and the press is not retired.
        if (page?.mousePressActive() == true) return ModeSwitch.Unchanged
        val tabId = selectedTabId
        val pageNow = page
        if (tabId != null && tabModes[tabId] == enabled && (pageNow == null || pageNow.desktopConfigured() == enabled)) return ModeSwitch.Unchanged
        if (pageNow != null && !pageNow.controlledGetProvenance()) {
            revokeAll("desktop mode refused", keepRequestId)
            synchronized(lock) {
                pages[tabId]?.modeNotice = "mode-reopen-required"
                publishLocked()
            }
            return ModeSwitch.ReopenRequired
        }
        if (tabId != null) tabModes[tabId] = enabled
        modeSeq += 1
        scrollMemory.observePage(scrollIdentity())
        if (!enabled) mouseMode = false
        pageNow?.setDesktopMode(enabled)
        // A viewport change is presentation, not a takeover: the grant stays. Refs and geometry are
        // dropped because the layout under the old snapshot is gone, and the controlled GET reloads
        // the same URL for the new mode.
        invalidateGeometry("desktop mode", keepRequestId)
        if (pageNow?.controlledGetProvenance() == true) pageNow.reloadGet()
        synchronized(lock) { publishLocked() }
        return ModeSwitch.Applied
    }

    fun setMouseMode(enabled: Boolean) {
        // Deferred while a press is active: no mode change, no takeover, no retirement.
        if (page?.mousePressActive() == true) return
        if (enabled && !mouseProbe().supported) {
            mouseMode = false
            synchronized(lock) { publishLocked(error = "mouse unsupported") }
            return
        }
        mouseMode = enabled && (tabModes[selectedTabId] == true)
        // Mouse mode is an input preference, not a takeover: enabling it, dragging or scrolling must
        // not pause the agent. Only the explicit takeover entry revokes.
        synchronized(lock) { publishLocked() }
    }

    /**
     * Independent presentation-only notification for float/full mode, dragging or resizing the
     * window. Unlike [onGeometryChanged] it never clears snapshot refs or geometry, never cancels an
     * action, never retires a mouse press and never changes authority. The UI must use this for
     * window bounds and keep [onGeometryChanged] for a real native page-frame change.
     */
    fun onPresentationChanged() {
        synchronized(lock) { publishLocked() }
        scheduleAutoGrantRetry()
    }

    fun onGeometryChanged() = invalidateGeometry("geometry changed")

    internal suspend fun beforeWindowExpansionChange(next: Boolean): WindowChange {
        if (page?.mousePressActive() == true) return WindowChange.Superseded
        val pageNow = page ?: return WindowChange.Superseded
        val identity = scrollIdentity()
        val stamp = ScrollCaptureStamp(pageNow.refEpoch(), geometryGeneration, pageNow.refEpoch())
        val request = scrollMemory.beginChange(next, identity, stamp)
        // A previous cycle's readback is not a result for this pending window change.
        _windowRestoreResult.value = null
        if (!next) return scrollMemory.completeChange(request, scrollIdentity(), currentScrollStamp())
        val sample = pageNow.readScroll()
        return scrollMemory.completeChange(request, scrollIdentity(), currentScrollStamp(), sample)
    }

    /**
     * Legacy token-only overload. It no longer starts a restore: a restore needs a registered content target, so a
     * Surface or token callback alone can never release bookmark consumption. It keeps the ordinary geometry settle.
     */
    internal suspend fun onGeometrySettled(change: WindowChangeToken?) {
        onGeometrySettled()
        if (change == null || change.next) return
        // A token that is no longer the current restore transition is ignored: it must never overwrite the current result.
        if (!scrollMemory.isCurrentRestoreToken(change)) return
        // No registered target for this transition: refuse honestly, never restore from an unmeasured frame.
        if (windowRestoreTarget?.binding?.change != change) {
            _windowRestoreResult.value = RestoreResult.NotSaved(ScrollNotSavedReason.NO_BOOKMARK)
        }
    }

    /** The bounded registration deadline of the bound transition. Cancelled as soon as its target is registered. */
    private var targetDeadline: Job? = null

    /**
     * Starts the bounded target-registration deadline for [binding]. It does not delay any layout: it only ends a
     * transition that never reports a target. Cancelled by the first registered target or by any new binding.
     */
    private fun startTargetDeadline(binding: WindowContentBinding) {
        targetDeadline?.cancel()
        targetDeadline = scope.launch(main) {
            kotlinx.coroutines.delay(TARGET_REGISTRATION_DEADLINE_MS)
            expireTargetRegistration(binding)
        }
    }

    /**
     * Runs only when the deadline fires. It ends the restore for [binding] only if that binding is still the current
     * one, the same source/page context, the restore token is still current, and no target was registered. Any change
     * makes it a no-op. It never scrolls and never grants anything.
     */
    private fun expireTargetRegistration(binding: WindowContentBinding) {
        val context = windowContext ?: return
        if (context.binding !== binding) return
        if (windowRestoreTarget != null) return
        if (!scrollMemory.isCurrentRestoreToken(binding.change)) return
        if (page !== context.source || scrollIdentity() != context.identity || !foreground || host == null) return
        scrollMemory.cancelRestoreToken(binding.change)
        windowContext = null
        targetDeadline = null
        _windowRestoreResult.value = RestoreResult.NotSaved(ScrollNotSavedReason.NO_TARGET_LAYOUT)
    }

    /**
     * Main, synchronous. Binds the pending window transition to the current page context. Only the transition that
     * just returned Proceed and is still the current pending token can be bound. Anything else returns null.
     */
    internal fun bindWindowContent(change: WindowChangeToken, afterLayoutRevision: Long): WindowContentBinding? {
        if (change.next || afterLayoutRevision < 0L) return null
        val source = page ?: return null
        if (!scrollMemory.isCurrentRestoreToken(change)) return null
        val binding = WindowContentBinding(change, source.handle, source.viewId(), afterLayoutRevision)
        windowContext = WindowContentContext(binding, scrollIdentity(), source, source.acceptedDocument(), page?.tabId)
        windowRestoreTarget = null
        // A bound Restore must reach a target within the bounded deadline, or the transition ends as NO_TARGET_LAYOUT.
        startTargetDeadline(binding)
        return binding
    }

    /**
     * Main. Registers a content target for a bound transition, then runs the B5 target gate. A late or stale target
     * is dropped and publishes nothing for the new transition.
     */
    internal suspend fun onWindowContentSettled(target: WindowContentTarget) {
        val context = windowContext ?: return
        if (context.binding !== target.binding) return
        if (!target.isWellFormed()) return
        if (target.layoutRevision <= context.floor) return
        val current = context.binding.change
        if (!scrollMemory.isCurrentRestoreToken(current)) return
        if (page !== context.source || scrollIdentity() != context.identity) return
        if (context.source.handle != target.binding.sourceHandle || context.source.viewId() != target.binding.viewId) return
        windowRestoreTarget = target
        context.floor = target.layoutRevision
        // The target arrived in time: the deadline is no longer needed. The gate that follows is not bounded by it.
        targetDeadline?.cancel()
        targetDeadline = null
        restoreAfterTarget(current, target, context)
    }

    private companion object {
        /** Bounded wait for a registered content target. It is a deadline, not a delay added to any layout. */
        const val TARGET_REGISTRATION_DEADLINE_MS = 2_000L
        /** Bounded re-probes of one load after only its ref epoch moved; never a reload. */
        const val READY_REPROBE_LIMIT = 4
    }

    private var windowContext: WindowContentContext? = null
    private var windowRestoreTarget: WindowContentTarget? = null

    private class WindowContentContext(
        val binding: WindowContentBinding,
        val identity: ScrollPageIdentity,
        val source: ControlledBrowserPage,
        val document: String?,
        val tabId: String?,
    ) {
        var floor: Long = binding.afterLayoutRevision
    }

    /**
     * B5 target gate. Only native frame == target and CSS projection == target start a restore. The two accepted
     * samples must be new (serials after the target was registered) and share one fingerprint. The old enlarged
     * frame, a stable but stale native frame, and small native with an old CSS projection all keep the restore
     * refused. Shared geometry, pinch, IME and capture admission are untouched.
     */
    private suspend fun restoreAfterTarget(change: WindowChangeToken, target: WindowContentTarget, context: WindowContentContext) {
        val source = context.source
        val registeredAt = currentProbeSerial
        val initialPage = scrollIdentity()
        val pair = awaitStableGeometryPair(
            request = {
                if (page !== source) currentProbeSerial else {
                    onGeometrySettled()
                    currentProbeSerial
                }
            },
            latest = { acceptedGeometry(source)?.takeIf { it.serial > registeredAt } },
            serial = { it.serial },
            matches = { sample -> sampleMatchesTarget(source, sample, target) },
            fingerprint = { geometryFingerprint(it) },
            stillCurrent = {
                page === source && scrollIdentity() == initialPage && foreground && host != null &&
                    windowRestoreTarget === target && scrollMemory.isCurrentRestoreToken(change)
            },
        )
        if (pair == null) {
            // No accepted target-matching pair in the bounded wait: do not consume the bookmark, do not scroll.
            if (windowRestoreTarget === target && scrollMemory.isCurrentRestoreToken(change)) {
                _windowRestoreResult.value = RestoreResult.NotSaved(ScrollNotSavedReason.CAPTURE_FAILED)
            }
            return
        }
        if (windowRestoreTarget !== target || !scrollMemory.isCurrentRestoreToken(change)) return
        val plan = scrollMemory.consumeRestore(change, scrollIdentity())
        if (plan is ScrollRestore.Skip) {
            _windowRestoreResult.value = plan.result
            return
        }
        val apply = plan as ScrollRestore.Apply
        if (!scrollMemory.isRestoreCurrent(apply.plan, scrollIdentity())) return
        page?.invalidateSessionRefs()
        page?.clearGeometryCache()
        if (!scrollMemory.isRestoreCurrent(apply.plan, scrollIdentity())) return
        // The native width is the one the gate already matched (the last accepted sample), not the target width.
        val matchedNativeWidth = pair.second.frame.width
        // Dispatch through the source captured before the wait, never a page that may have been replaced since.
        val actual = source.restoreScroll(apply.plan.target.x, apply.plan.target.y, target.heightPx, matchedNativeWidth)
        // The scroll may already have happened once on the captured source. A result is published only if the
        // transition, source, target and identity are still the ones that were restored. Otherwise the late readback
        // stays unpublished: it never overwrites a newer transition's result and never grants anything.
        val stillThisRestore = page === source && windowRestoreTarget === target &&
            scrollIdentity() == initialPage && scrollMemory.isRestoreCurrent(apply.plan, scrollIdentity())
        if (!stillThisRestore) return
        _windowRestoreResult.value = scrollMemory.completeRestore(apply.plan, scrollIdentity(), actual)
    }

    /** Target-specific sample check: native frame at the target, and the restore-only CSS projection at the target height. */
    private fun sampleMatchesTarget(source: ControlledBrowserPage, sample: GeometrySample, target: WindowContentTarget): Boolean {
        if (!sampleMatches(source, sample)) return false
        val frame = sample.frame
        if (!nativeMatchesTarget(frame.windowLeft, frame.windowTop, frame.width, frame.height, target)) return false
        return cssProjectionMatchesTarget(frame.width, sample.probe.visualWidth, sample.probe.visualHeight, target)
    }

    internal fun notePageInteraction() {
        interactionSeq += 1
        scrollMemory.observePage(scrollIdentity())
    }

    private fun acceptedGeometry(source: ControlledBrowserPage): GeometrySample? {
        if (page !== source) return null
        val sample = source.latestGeometry() ?: return null
        val accepted = geometry ?: return null
        if (!accepted.ready || accepted.probeSerial != sample.serial || accepted.generation != geometryGeneration) return null
        if (sample.viewId != source.viewId() || sample.tabId != source.tabId || sample.ticket != source.ticket()) return null
        if (sample.documentId != source.acceptedDocument() || sample.frame != source.nativeFrame()) return null
        return sample
    }

    private fun sampleMatches(source: ControlledBrowserPage, sample: GeometrySample): Boolean {
        val current = page ?: return false
        if (current !== source) return false
        val frame = current.nativeFrame()
        val probe = sample.probe
        return sample.viewId == current.viewId() && sample.tabId == current.tabId &&
            sample.documentId == current.acceptedDocument() && sample.ticket == current.ticket() &&
            sample.epoch == geometryGeneration && current.refEpoch() > 0L && sample.frame == frame &&
            frame.width > 1 && frame.height > 1 && frame.visibleRight > frame.visibleLeft &&
            frame.visibleBottom > frame.visibleTop &&
            probe.visualWidth > 0f && probe.visualHeight > 0f && probe.innerWidth > 0 && probe.innerHeight > 0 &&
            probe.clientWidth > 0 && probe.clientHeight > 0 && probe.devicePixelRatio > 0f &&
            geometry?.generation == geometryGeneration && geometry?.probeSerial == sample.serial
    }

    private fun geometryFingerprint(sample: GeometrySample): List<Any> {
        val probe = sample.probe
        return listOf(
            sample.frame, probe.innerWidth, probe.innerHeight, probe.clientWidth, probe.clientHeight,
            probe.visualWidth, probe.visualHeight, probe.offsetLeft, probe.offsetTop, probe.pageLeft, probe.pageTop,
            probe.scrollX, probe.scrollY, probe.devicePixelRatio, probe.visualScale,
        )
    }

    private fun currentScrollStamp(): ScrollCaptureStamp =
        ScrollCaptureStamp(page?.refEpoch() ?: 0L, geometryGeneration, page?.refEpoch() ?: 0L)

    private fun scrollIdentity(): ScrollPageIdentity = ScrollPageIdentity(
        instanceKey = host?.instanceKey.orEmpty(),
        tabId = selectedTabId.orEmpty(),
        viewId = page?.viewId().orEmpty(),
        navigationTicket = page?.ticket() ?: 0L,
        documentWitness = page?.acceptedDocument(),
        interactionSeq = interactionSeq,
        modeSeq = modeSeq,
        runtimeLaunchId = runtimeLaunchId,
        consoleGeneration = host?.consoleGeneration ?: 0L,
    )

    fun onGeometrySettled() {
        val request = synchronized(lock) {
            val source = page ?: return
            currentProbeSerial += 1
            val identity = ProbeIdentity(source.viewId(), source.tabId, source.ticket(), source.acceptedDocument(), geometryGeneration, currentProbeSerial)
            val bound = pages[selectedTabId]
            val witness = identity.documentId
            if (witness != null && bound?.page === source && !bound.retired) {
                // Ordinary layout/scale/B5 requests may always take a new serial. This record only
                // lets a repeated ready commit/finished reuse the same post-accept request.
                inflightGeometry = GeometryRequest(source, identity, bound.generation, witness, source.nativeFrame())
            }
            source to identity
        }
        request.first.requestGeometryProbe(request.second)
        scheduleAutoGrantRetry()
    }

    private fun invalidateGeometry(reason: String, keepRequestId: String? = null) {
        // An unavoidable layout change under an active press retires that page before any generation advances.
        retireActivePress("geometry-changed")
        val running = synchronized(lock) {
            geometryGeneration += 1
            currentProbeSerial += 1
            geometry = null
            inflightGeometry = null
            pages.values.forEach {
                it.adapter.clearRefs()
                it.page.invalidateSessionRefs()
                it.page.clearGeometryCache()
            }
            adapter?.clearRefs()
            val ownHandshake = keepRequestId != null && activeRequestId == keepRequestId &&
                (activeAction == "browser.open" || activeAction == "browser.resize")
            if (!activePageAccess || ownHandshake) return@synchronized null
            val job = activeJob
            val mutated = activeDispatched || pages.values.any { it.mutationInFlight }
            activeJob = null
            activeRequestId = null
            activeAction = null
            activeDispatched = false
            activePageAccess = false
            activeTab = null
            if (mutated) {
                pages.values.forEach { if (it.mutationInFlight) it.outcomeUnknown = true }
                registry.revoke()
                mode = ProviderMode.MANUAL
            }
            job
        }
        running?.cancel()
        gesture.cancel()
        gestureDropped = true
        page?.clearMouseButtons()
        ignored(reason)
        synchronized(lock) { publishLocked() }
    }

    /**
     * UI forwards page-area touches here only while mouse mode is on. A gesture is input, not
     * authority: it does not take over or revoke the agent's grant.
     */
    /** True while the page is receiving the presenter's own injected mouse events. */
    fun isInjectingMouse(): Boolean = page?.isInjectingMouse() == true

    fun mouseInput(event: MotionEvent, viewWidthPx: Int, viewHeightPx: Int) {
        // Admission is idempotent for an admitted DOWN, so the UI listener and this path may both ask.
        if (!admitPageInput(event)) return
        val index = event.findPointerIndex(gesture.primaryPointerId ?: event.getPointerId(0)).takeIf { it >= 0 } ?: 0
        mouseInput(
            PointerSample(
                event.actionMasked,
                event.getX(index),
                event.getY(index),
                event.pointerCount,
                false,
                event.eventTime,
                pointerId = event.getPointerId(index),
                actionPointerId = event.getPointerId(event.actionIndex),
                actionIndex = event.actionIndex,
            ),
            viewWidthPx,
            viewHeightPx,
        )
    }

    internal fun admitPageInput(event: MotionEvent): Boolean {
        if (synchronized(lock) { presentationBlocked }) {
            // A blocked page receives no raw input, not even a DOWN that could open a later gesture.
            inputMemo.clear()
            return false
        }
        val key = RawInputEventKey(
            event.action,
            event.downTime,
            event.eventTime,
            event.deviceId,
            event.source,
            event.getPointerId(event.actionIndex),
            List(event.pointerCount) { event.getPointerId(it) },
            event.x,
            event.y,
        )
        val owner = synchronized(lock) { pages[selectedTabId]?.takeUnless { it.retired }?.page }
        if (owner == null) {
            inputMemo.clear()
            return false
        }
        inputMemo.recall(event, key, owner)?.let { return it }
        val result = admitPageInput(event.actionMasked)
        inputMemo.remember(event, key, owner, result)
        return result
    }

    /**
     * Raw-input admission for the selected view. A new view identity gets a new gesture that waits for a fresh
     * DOWN; a MOVE, UP or CANCEL left from an older view is refused and never completes a click.
     */
    internal fun admitPageInput(action: Int): Boolean {
        if (synchronized(lock) { presentationBlocked }) return false
        val target = synchronized(lock) {
            val bound = pages[selectedTabId] ?: return@synchronized null
            if (bound.retired) null else bound.page
        } ?: return false
        if (!target.admitNativeInput()) return false
        inputAdmission.bind(target)
        return inputAdmission.admits(action)
    }

    internal fun mouseInput(sample: PointerSample, viewWidthPx: Int, viewHeightPx: Int) {
        if (synchronized(lock) { presentationBlocked }) return
        if (pages[selectedTabId]?.retired == true || !mouseMode || page?.admitNativeInput() == false) return
        // Dragging or scrolling the page is a person's gesture, not a takeover. The grant, mode and
        // manual-navigation flag stay as they are; only explicit takeover/hide/close/connection or
        // owner changes revoke. The gesture still runs under the ordinary ref/geometry checks.
        val ready = geometry?.ready == true && geometry?.viewId == page?.viewId() && geometry?.tabId == page?.tabId &&
            geometry?.viewWidthPx == viewWidthPx && geometry?.viewHeightPx == viewHeightPx
        // A fresh DOWN starts a new gesture. If geometry settled before it arrived, do not discard
        // that first valid gesture merely because a previous layout change set the drop flag.
        if (sample.action == MouseInput.ACTION_DOWN) {
            notePageInteraction()
            gestureDropped = !ready
        }
        if (!ready) gestureDropped = true
        if (gestureDropped) {
            if (sample.action == MouseInput.ACTION_UP || sample.action == MouseInput.ACTION_CANCEL) gestureDropped = false
            onGeometrySettled()
            return
        }
        val plans = MouseInput.plan(sample, pointerViewX, pointerViewY, geometry, gesture, ready)
        plans.forEach { plan ->
            if (plan.geometryGeneration != geometryGeneration) return@forEach
            if (plan.action != MouseAction.SCROLL) {
                pointerViewX = plan.viewX
                pointerViewY = plan.viewY
            }
            val target = page ?: return
            val delivered = target.injectMouse(plan)
            // The session's callback may already have retired this page during delivery. Stop here so
            // the retirement reason is not overwritten by "mouse unsupported".
            if (page !== target || isRetiredPage(target)) return
            target.lastMouseRetirement()?.let {
                retireBound(target, it)
                return
            }
            if (plan.action == MouseAction.CANCEL && target.mousePressActive()) {
                retireBound(target, "cancel")
                return
            }
            if (!delivered && (plan.action == MouseAction.PRESS || plan.action == MouseAction.RELEASE)) {
                    mouseMode = false
                    page?.clearMouseButtons()
                    synchronized(lock) { publishLocked(error = "mouse unsupported") }
                    return
            }
        }
        synchronized(lock) { publishLocked() }
    }

    fun acceptGeometry(probe: GeometryProbe, identity: ProbeIdentity, viewWidthPx: Int, viewHeightPx: Int) {
        val current = ProbeIdentity(page?.viewId().orEmpty(), page?.tabId, page?.ticket() ?: 0L, page?.acceptedDocument(), geometryGeneration, identity.probeSerial)
        if (!BrowserGeometry.accept(probe, identity, current)) return
        val frame = page?.nativeFrame()?.takeIf { it.width > 1 && it.height > 1 }
            ?: NativeFrame(viewWidthPx, viewHeightPx, 0, 0, 0, 0, viewWidthPx, viewHeightPx)
        geometry = BrowserGeometry.fromProbe(
            probe, identity, frame.width, frame.height, frame.windowLeft, frame.windowTop,
            frame.visibleLeft, frame.visibleTop, frame.visibleRight, frame.visibleBottom,
            if (tabModes[page?.tabId] == true) "desktop" else "mobile",
        )
        synchronized(lock) { publishLocked() }
    }

    fun invalidateConsoleDocument(instanceKey: String) {
        val matches = synchronized(lock) { host?.instanceKey == instanceKey }
        if (!matches) return
        retireActivePress("console-document-invalidated")
        consoleInvalidated = true
        scrollMemory.invalidateContext()
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
        retireActivePress("host-left")
        scrollMemory.invalidateContext()
        revokeAll("host left")
        synchronized(lock) {
            releasePageLocked()
            host = null
            hidden = false
            explicitTakeover = false
            consoleSessionId = null
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
        if (adopt) {
            scrollMemory.invalidateContext()
            revokeAll("connection replaced")
        }
        synchronized(lock) { publishLocked() }
    }

    /**
     * The only revoke path. Epoch, the active job and page refs move together before this returns.
     * A later allow cannot revive the old grant.
     */
    fun revokeAll(reason: String) = revokeAll(reason, keepRequestId = null)

    /**
     * Single revoke path. [keepRequestId] is the caller's own request: an open that revokes the
     * previous grant must not cancel itself. Every other in-flight page action is cancelled, except
     * an `open` that is only waiting for the person's Allow tap: it holds no page command, so the
     * revoke lets it answer with its current payload and the wait ends on the new state.
     */
    private fun revokeAll(reason: String, keepRequestId: String?, clearPendingWake: Boolean = true) {
        // Reasons are fixed internal strings; no page or request content is logged.
        android.util.Log.i("AndChamberProvider", "revoke reason=$reason")
        val job = synchronized(lock) {
            ignored(reason)
            registry.revokeEpoch()
            grantEpoch = 0L
            witnessForGrant = null
            autoGrantIntent = null
            if (clearPendingWake) pendingWake = null
            mode = ProviderMode.MANUAL
            documentEpoch += 1
            inflightGeometry = null
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
            // An open that is only watching for the person's Allow tap has nothing in flight. A
            // revoke (takeover, tab close, background, another tab) must let it answer with its
            // current payload instead of killing the response. Real cancellation comes from the
            // tool transport (a cancel request, a dropped connection or the launch ending), which
            // cancels this job itself.
            if (running != null && allowWaitActive && activeAction == "browser.open") return@synchronized null
            // Reason is a fixed internal string; nothing from the page or the request is logged.
            if (running != null) android.util.Log.i("AndChamberProvider", "revoke cancels active action reason=$reason")
            activeJob = null
            activeRequestId = null
            activeAction = null
            activeDispatched = false
            activeTab = null
            running
        }
        job?.cancel()
        // Every revoke is visible at once: the window must never keep showing "agent on".
        synchronized(lock) { publishLocked() }
    }

    /** Host left the foreground. Revokes, but keeps the window so resume can show it again. */
    fun onHostPaused() {
        paused = true
        // Retire before the grant is revoked: an active press cannot be completed once the host leaves.
        retireActivePress("host-paused")
        // Keep a hidden-window wake bound to its page/connection/owner across the pause; the next
        // foreground agent action may retry it. Nothing is shown while paused.
        revokeAll("host paused", keepRequestId = null, clearPendingWake = false)
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
    internal suspend fun reopenRetiredPage(): ProviderResult {
        val target = synchronized(lock) {
            val tabId = selectedTabId ?: return@synchronized null
            if (pages[tabId]?.retired != true || host == null || paused || consoleInvalidated ||
                presentationBlocked || connectionId == null
            ) {
                return@synchronized null
            }
            val tab = registry.find(tabId) ?: return@synchronized null
            ReopenTarget(tab, tabModes[tabId] == true)
        } ?: return unavailable()
        // The selection stays on the retired placeholder until the fresh page is accepted.
        val before = registry.tabs().map { it.tabId }.toSet()
        val opened = openClaimed(
            ProviderRequest(
                "user", connectionId.orEmpty(), "reopen", "browser.open",
                mapOf("url" to target.tab.url, "viewport" to if (target.desktop) "desktop" else "mobile"),
                target.tab.directory, target.tab.ownerSessionId, 1_000L,
            ),
            Long.MAX_VALUE,
        )
        if (!opened.ok) {
            // Failure keeps the old placeholder selectable and drops only the tab this attempt registered.
            registry.tabs().filter { it.tabId !in before }.forEach { registry.close(it.tabId) }
            synchronized(lock) { publishLocked(shown = true) }
            return opened
        }
        close(target.tab.tabId)
        return opened
    }

    private fun isRetiredPage(target: ControlledBrowserPage): Boolean =
        synchronized(lock) { pages.values.firstOrNull { it.page === target }?.retired == true }

    /** Only an active or possibly dispatched press is retired here; idle and chat focus are not. */
    private fun retireActivePress(reason: String) {
        val source = synchronized(lock) { page?.takeIf { it.mousePressActive() } } ?: return
        retireBound(source, reason)
    }

    /**
     * Retires exactly one bound page, identified by its page object. Marks it retired and cuts its
     * authority, pending work and refs under the lock, then detaches and destroys it. Re-entry from
     * the page callback returns at the first check. A late callback from a removed or replaced page
     * matches nothing. Only the selected page touches mouse mode, gesture, geometry and scroll state.
     */
    private fun retireBound(source: ControlledBrowserPage?, reason: String) {
        val target = source ?: return
        var running: Job? = null
        var wasSelected = false
        synchronized(lock) {
            val entry = pages.entries.firstOrNull { it.value.page === target } ?: return
            val bound = entry.value
            if (bound.retired) return
            if (autoGrantIntent?.page === target) autoGrantIntent = null
            if (pendingWake?.page === target) pendingWake = null
            bound.retired = true
            bound.retireReason = reason
            wasSelected = selectedTabId == entry.key
            // A dispatched mutation on this page is isolated: its outcome is unknown, never retried.
            if (bound.mutationInFlight) {
                bound.outcomeUnknown = true
                bound.mutationInFlight = false
            }
            bound.adapter.clearRefs()
            target.invalidateSessionRefs()
            target.clearGeometryCache()
            // Only this page's grant is revoked. A healthy tab's grant survives a late callback from it.
            val ownGrant = registry.currentGrant()?.tabId == entry.key
            if (ownGrant) {
                registry.revoke()
                grantEpoch = 0L
                witnessForGrant = null
            }
            if (ownGrant || wasSelected) mode = ProviderMode.MANUAL
            if (activeTab == entry.key) {
                running = activeJob
                activeJob = null
                activeRequestId = null
                activeAction = null
                activeDispatched = false
                activePageAccess = false
                activeTab = null
            }
            if (inflightGeometry?.source === target) inflightGeometry = null
            if (wasSelected) {
                mouseMode = false
                inputAdmission.gesture.retire()
                geometry = null
                loadJob?.cancel()
                page = null
                adapter = null
                scrollMemory.invalidateContext()
                interactionSeq += 1
            }
        }
        running?.cancel()
        // Closes this view's input gate; the callback re-enters and returns. No cancel, up or hover is sent.
        target.retireMouse(reason, unavoidable = true)
        target.destroy()
        synchronized(lock) {
            // A non-selected retirement must not touch the visible page's state at all.
            if (wasSelected) publishLocked(shown = true, error = "mouse-gesture-interrupted") else publishLocked()
        }
    }

    /**
     * The person's explicit navigation (address bar, reload of the current tab URL, or the console's
     * "Open web page"). This is the manual open entry: it is not an agent grant and never waits for
     * Allow. [openClaimed] revokes the grant and cancels an agent action first (takeover), checks the
     * URL against the existing [BrowserUrlPolicy] syntax policy, and loads one fresh controlled GET.
     * A refused URL changes nothing. A successful open clears an explicit hide and shows the page;
     * once that page is ready it receives default control through [allow], without a tap. A
     * takeover, hide, connection change or later navigation revokes it.
     */
    suspend fun openUser(url: String, ownerSessionId: String): ProviderResult =
        navigateManual(url, ownerSessionId)

    /**
     * The address-bar navigation entry. It reuses the selected tab when that tab belongs to the
     * same owner, so repeated navigation cannot grow an unbounded tab list; otherwise it opens one
     * new tab. It takes over, applies the existing URL policy and loads exactly one controlled GET.
     * A refused URL changes nothing and leaves the current page in place.
     */
    suspend fun navigateManual(url: String): ProviderResult {
        val owner = synchronized(lock) {
            // The address bar belongs to the shown page; fall back to the console identity only
            // when no page exists yet.
            registry.find(selectedTabId.orEmpty())?.ownerSessionId ?: consoleSessionId
        } ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "no owner")
        return navigateManual(url, owner)
    }

    private suspend fun navigateManual(url: String, ownerSessionId: String): ProviderResult {
        val target = synchronized(lock) {
            selectedTabId?.let { id -> registry.find(id)?.takeIf { it.ownerSessionId == ownerSessionId } }
        }
        val request = ProviderRequest(
            serverEpoch = "user",
            connectionId = connectionId.orEmpty(),
            requestId = "user-navigate",
            action = "browser.open",
            parameters = buildMap {
                put("url", url)
                target?.let { put("tabId", it.tabId) }
            },
            directory = target?.directory ?: "/",
            sessionId = ownerSessionId,
            timeoutMs = 1_000L,
        )
        return openClaimed(request, Long.MAX_VALUE)
    }

    /**
     * The Reload entry: the currently displayed URL of the selected page, opened as a fresh
     * controlled GET through [navigateManual]. A page with no known URL is refused.
     */
    suspend fun reloadManual(): ProviderResult {
        val url = synchronized(lock) {
            page?.currentUrl() ?: selectedTabId?.let { registry.find(it)?.url }
        } ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "no page")
        return navigateManual(url)
    }

    /** Binding compatibility. Allow itself re-probes; this only records the requested tab. */
    fun allowAsync(sessionId: String, tabId: String) {
        // A blocked presentation refuses the allow path before it can even change the selection.
        if (synchronized(lock) { presentationBlocked }) return
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

    internal fun beginForTest(requestId: String): Boolean {
        check(BuildConfig.DEBUG) { "beginForTest is debug-only" }
        return synchronized(lock) {
            if (activeJob != null) return false
            activeJob = Job()
            activeRequestId = requestId
            true
        }
    }

    fun cancel(requestId: String) {
        if (synchronized(lock) { activeRequestId } != requestId) return
        revokeAll("cancelled")
    }

    /** User or frontend selection. Does not navigate and does not create a tab. */
    fun select(tabId: String): Boolean {
        // A press on the visible page defers the tab change: nothing moves and nothing is retired.
        if (selectedTabId != tabId && page?.mousePressActive() == true) return false
        val changed = synchronized(lock) {
            val tab = registry.find(tabId) ?: return false
            if (selectedTabId == tabId && pages[tabId] != null) return true
            selectedTabId = tab.tabId
            val bound = pages[tab.tabId]
            page = if (bound?.retired == true) null else bound?.page
            adapter = if (bound?.retired == true) null else bound?.adapter
            bound?.desktop?.toString()
            viewGeneration += 1
            true
        }
        if (changed) revokeAll("tab selected")
        materializeSelected()
        synchronized(lock) { publishLocked() }
        return true
    }

    /**
     * Creates the production page through the original factory, then lets an installed decorator
     * observe that same object once, synchronously, before any mode change, load, bind or registry
     * open. The decorator cannot return a replacement. If it throws, the original page is destroyed
     * and creation fails closed, so no undecorated page is ever loaded.
     */
    private fun createPage(context: Context?): ControlledBrowserPage? {
        val created = sessionFactory(context) ?: return null
        val decorator = synchronized(lock) {
            pageCreated = true
            pageDecorator
        } ?: return created
        return try {
            decorator(created)
            created
        } catch (error: Throwable) {
            android.util.Log.i("AndChamberProvider", "page decorator failed")
            created.destroy()
            null
        }
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
        val created = createPage(prepared.context) ?: return
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
            val storedDesktop = tabModes[tab.tabId] == true
            created.setDesktopMode(storedDesktop)
            pages[tab.tabId] = BoundPage(created, pageAdapter, viewGeneration, desktop = storedDesktop)
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
        // Closing a tab whose page is pressed destroys that page: retire the press first.
        if (pages[tabId]?.page?.mousePressActive() == true) retireBound(pages[tabId]?.page, "tab-closed")
        val removed = synchronized(lock) { registry.close(tabId) } ?: return false
        synchronized(lock) {
            awaitingAllowTabs.remove(removed.tabId)
            if (autoGrantIntent?.tabId == removed.tabId) autoGrantIntent = null
            if (pendingWake?.tabId == removed.tabId) pendingWake = null
        }
        pages.remove(removed.tabId)?.page?.destroy()
        if (selectedTabId == removed.tabId) {
            synchronized(lock) {
                selectedTabId = registry.tabs().lastOrNull()?.tabId
                val bound = selectedTabId?.let { pages[it] }
                // A retired fallback shows its placeholder only: no page and no adapter.
                page = if (bound?.retired == true) null else bound?.page
                adapter = if (bound?.retired == true) null else bound?.adapter
                viewGeneration += 1
                if (selectedTabId == null) releasePageLocked()
            }
            revokeAll("tab closed")
        }
        synchronized(lock) { publishLocked() }
        return true
    }

    /**
     * Hides the window for the rest of this host's life. The selected page is kept mounted, but the
     * grant is revoked and admission ends. No probe, state publish, pause/resume or host re-attach
     * shows it again; [show] (or an explicit open) does. Kept as the compatibility name for hide.
     */
    fun background() = hide()

    /** The person dismissing the window: keep the page, drop every grant and stop presenting it. */
    fun hide() {
        // Hidden is set first so the publications below cannot momentarily show the window again.
        synchronized(lock) { hidden = true }
        // Hiding the window while a press is active is an unavoidable interruption of that press.
        retireActivePress("hidden")
        revokeAll("hidden")
        synchronized(lock) { publishLocked() }
    }

    /**
     * The person opening the window again. Presentation only: the page is not reloaded or created
     * and no grant is restored. Returns whether a page is presented now.
     */
    fun show(): Boolean = synchronized(lock) {
        if (hidden) {
            hidden = false
            publishLocked()
        }
        _state.value.shown
    }

    /** Explicit restore entry. Same as [show]; it never restores authority. */
    fun restore(): Boolean = show()

    /**
     * Human takeover. Revokes and cancels before returning, then lets the page accept a normal
     * top-level navigation. This is not an agent grant.
     */
    fun takeover() {
        synchronized(lock) {
            explicitTakeover = true
            mode = ProviderMode.MANUAL
            page?.manualNavigation = true
            pages[selectedTabId]?.page?.manualNavigation = true
            selectedTabId?.let { awaitingAllowTabs.remove(it) }
        }
        revokeAll("takeover")
        synchronized(lock) { publishLocked() }
    }

    /**
     * Re-probes the visible document, then grants only if the same view and epoch are still current.
     * A fresh observation is required. The witness id may stay the same only for that document.
     * This automatic/user-Tap entry never clears an explicit takeover latch.
     */
    suspend fun allow(): Boolean = allowInternal()

    /**
     * The person's explicit "resume agent control" entry (UI onResume). It clears the explicit
     * takeover latch, then uses the same [allowInternal] verification. A takeover that lands during
     * the probe sets the latch again and bumps the document epoch, so the late grant is refused.
     */
    suspend fun resumeAgent(): Boolean {
        val hadLatch = synchronized(lock) {
            val was = explicitTakeover
            explicitTakeover = false
            was
        }
        val granted = allowInternal()
        if (!granted && hadLatch) {
            // A resume of an existing takeover that did not verify must not silently hand control
            // to an ordinary action: keep the explicit decision unless a grant already exists.
            synchronized(lock) {
                // A new explicit open (its intent is pending) owns the decision now; do not
                // re-impose an old latch over it.
                if (registry.currentGrant() == null && autoGrantIntent == null) explicitTakeover = true
                publishLocked()
            }
        }
        return granted
    }

    /**
     * The one grant path. [expectedConnection] binds a caller that was created for an earlier
     * connection (a pending open/wake) so a connection change during its wait can never grant on
     * the new connection.
     */
    private suspend fun allowInternal(
        expectedConnection: String? = null,
        expectedPage: ControlledBrowserPage? = null,
        expectedTabId: String? = null,
    ): Boolean = withContext(main) {
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
                expectedConnection != null && connection != expectedConnection -> "connection changed"
                expectedTabId != null && tab.tabId != expectedTabId -> "tab changed"
                tab.ownerSessionId.isBlank() -> "no owner"
                bound == null -> "no page"
                expectedPage != null && bound.page !== expectedPage -> "page changed"
                bound.outcomeUnknown -> "outcome unknown"
                bound.page.tabId != tab.tabId -> "page tab mismatch"
                bound.retired -> "mouse-gesture-interrupted"
                bound.page.mousePressActive() -> "mouse press active"
                !bound.page.ready -> "page not ready"
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
            current != null && pageNow != null && bound != null && !bound.outcomeUnknown && pageNow.ready &&
                current.instanceKey == snapshot.host.instanceKey &&
                current.consoleGeneration == snapshot.host.consoleGeneration &&
                pageNow === snapshot.page &&
                bound.page === snapshot.page &&
                documentEpoch == snapshot.epoch &&
                bound.generation == snapshot.generation &&
                selectedTabId == snapshot.tab.tabId &&
                connectionId == snapshot.connection &&
                (expectedPage == null || pageNow === expectedPage) &&
                (expectedTabId == null || selectedTabId == expectedTabId) &&
                !presentationBlocked && !hidden && !explicitTakeover
        }
        if (!granted) {
            android.util.Log.i("AndChamberProvider", "allow refused: changed during probe")
            return@withContext false
        }
        val applied = synchronized(lock) {
            val pageNow = page ?: return@synchronized false
            // Final fence between the verified probe and the grant: a takeover, a document/epoch
            // move, a replaced page or a different connection invalidates the late grant.
            if (explicitTakeover || documentEpoch != snapshot.epoch ||
                connectionId != snapshot.connection || pageNow !== snapshot.page
            ) {
                false
            } else {
                pageNow.manualNavigation = false
                pageNow.syncWitness(probed.documentId)
                witnessForGrant = probed.documentId
                registry.grant(runtimeLaunchId, snapshot.connection, snapshot.tab.ownerSessionId, snapshot.tab.tabId)
                grantEpoch = registry.currentGrant()?.grantEpoch ?: 0L
                mode = ProviderMode.AGENT
                // Success consumes only the intent/wake that names this granted page. The explicit
                // takeover latch is never cleared here; only resumeAgent() or a person's open does.
                if (autoGrantIntent?.page === pageNow && autoGrantIntent?.tabId == snapshot.tab.tabId) {
                    autoGrantIntent = null
                }
                if (pendingWake?.page === pageNow) pendingWake = null
                awaitingAllowTabs.remove(snapshot.tab.tabId)
                pages[snapshot.tab.tabId]?.modeNotice = null
                publishLocked()
                true
            }
        }
        if (!applied) {
            android.util.Log.i("AndChamberProvider", "allow refused: changed before grant")
            return@withContext false
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
        val session = request.sessionId?.takeIf { it.isNotBlank() }
            ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST)
        // An agent action may bring back a window the person merely hid. It never overrides an
        // explicit takeover and never shows while the trusted host is not foreground.
        restoreForAgentAction(request, session)
        val refused = synchronized(lock) { refusal(request, session) }
        if (refused != null) return refused
        if (request.action != "browser.open" && !registry.authorized(request, request.connectionId, synchronized(lock) { selectedTabId })) {
            android.util.Log.i("AndChamberProvider", "action=${request.action} phase=refused-before-page")
            return ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)
        }
        if (!beginAction(request.requestId, request.action)) {
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

    private suspend fun beginAction(requestId: String, actionName: String): Boolean {
        val job = currentCoroutineContext().job
        return synchronized(lock) {
            if (activeJob != null) {
                false
            } else {
                activeJob = job
                activeRequestId = requestId
                activeAction = actionName
                activeDispatched = false
                activePageAccess = false
                activeTab = null
                true
            }
        }
    }

    private fun endAction(requestId: String) {
        synchronized(lock) {
            if (activeRequestId == requestId) {
                activeJob = null
                activeRequestId = null
                activeAction = null
                activeDispatched = false
                activePageAccess = false
                activeTab = null
                allowWaitActive = false
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
        // An explicit open may reveal a merely hidden window; its URL/console checks run in
        // openClaimed and it only clears hidden on success. Every other action needs presentation.
        if (!foreground && request.action != "browser.open") return unavailable()
        if (now() >= deadlineElapsed) return ProviderResult(false, errorCode = ProviderCodes.DEADLINE)
        val session = request.sessionId?.takeIf { it.isNotBlank() }
            ?: return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST)
        val refused = synchronized(lock) { refusal(request, session) }
        if (refused != null) return refused
        if (request.action == "browser.open") {
            return openClaimed(request, session, deadlineElapsed, now)
        }
        if (request.action == "browser.resize") {
            val viewport = request.parameters["viewport"].orEmpty()
            if (viewport != "desktop" && viewport != "mobile" && viewport != "fill") {
                return ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER)
            }
            if (!registry.authorized(request, request.connectionId, selectedTabId)) {
                return ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)
            }
            // A press defers the layout change: busy, nothing applied, press not retired.
            if (page?.mousePressActive() == true) return ProviderResult(false, errorCode = ProviderCodes.BUSY)
            val switched = withContext(main) { resizeViewport(viewport, request.requestId) }
            val modeName = if (tabModes[selectedTabId] == true) "desktop" else "mobile"
            return when (switched) {
                ModeSwitch.ReopenRequired -> ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "reopen-required")
                else -> ProviderResult(ok = true, data = kotlinx.serialization.json.buildJsonObject {
                    put("viewportApplied", JsonPrimitive(switched != ModeSwitch.ReopenRequired))
                    put("mode", JsonPrimitive(modeName))
                    put("geometry", JsonPrimitive("pending"))
                })
            }
        }
        val currentMode = synchronized(lock) { mode }
        val shown = synchronized(lock) { selectedTabId }
        val initialAdapter = synchronized(lock) { adapter } ?: return unavailable()
        ignored(incomingMode.name)
        ignored(visibleTabId)
        // Authority is decided before the page is marked as accessed. A refused action (no grant,
        // retired page, other tab) never sets activePageAccess, so a later geometry change cannot
        // report it as outcome-unknown. The check is repeated under the lock to close the race.
        val pageAdapter = synchronized(lock) {
            val bound = pages[shown]
            if (bound == null || bound.retired || presentationBlocked ||
                !registry.authorized(request, request.connectionId, shown)
            ) {
                return ProviderResult(false, errorCode = ProviderCodes.AUTHORIZATION_REQUIRED)
            }
            activePageAccess = true
            activeTab = shown
            initialAdapter
        }
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
        // An explicit open is one of the two ways to show a hidden window again. It still fails closed
        // if the host or console cannot present; only the person's hide choice is overridden.
        if (!synchronized(lock) { presentableLocked() }) return@withContext unavailable()
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
        val viewport = request.parameters["viewport"]
        if (viewport != null && viewport != "fill" && viewport != "desktop" && viewport != "mobile") {
            return@withContext ProviderResult(false, errorCode = ProviderCodes.UNSUPPORTED_PARAMETER)
        }
        val url = request.parameters["url"].orEmpty()
        if (url.isBlank() || policy().checkSyntax(url) !is UrlDecision.Allow) {
            return@withContext ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "url refused")
        }
        val activeOwner = synchronized(lock) { registry.find(selectedTabId.orEmpty())?.ownerSessionId }
        // The person is looking at [session] in the chat console, so the page it asked for is the one
        // to show: it takes the selection from another owner's visible page. That page is kept, not
        // destroyed or reloaded, and this is still not a grant for anyone.
        val consoleSwitch = synchronized(lock) { consoleSessionId != null && consoleSessionId == session }
        val visibleOwnerPage = synchronized(lock) { !hidden && pages[selectedTabId] != null }
        if (activeOwner != null && activeOwner != session && visibleOwnerPage && !consoleSwitch) {
            // Keep the visible owner's page. The other owner gets a registry tab and no WebView.
            val parkedDesktop = viewport == "desktop" || (viewport != "mobile" && tabModes[selectedTabId] == true)
            val parked = OpenChamberPageAdapter(driver = UnavailableDriver, present = { true })
                .openClaimed(request, registry, session)
            synchronized(lock) {
                parked.tab?.tabId?.let {
                    tabModes[it] = parkedDesktop
                    if (parked.result.ok) noteOpenedByAgent(request, it)
                }
                publishLocked()
            }
            return@withContext parked.result
        }
        if (!synchronized(lock) { presentableLocked() }) return@withContext unavailable()
        // Replacing the visible page while a press is active is deferred: refuse busy, change nothing.
        if (page?.mousePressActive() == true) return@withContext ProviderResult(false, errorCode = ProviderCodes.BUSY)
        val created = createPage(prepared.context) ?: return@withContext unavailable()
        val replacingOwn = synchronized(lock) { activeOwner == null || activeOwner == session || consoleSwitch }
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
        val requestedDesktop = when (viewport) {
            "desktop" -> true
            "mobile" -> false
            else -> tabModes[named ?: selectedTabId] == true
        }
        created.prepareMode(requestedDesktop)
        val pageAdapter = adapterFor(created)
        android.util.Log.i("AndChamberProvider", "open step=load-start")
        val opened = pageAdapter.openClaimed(request, registry, session)
        android.util.Log.i("AndChamberProvider", "open step=load-done ok=${opened.result.ok}")
        val tabId = opened.tab?.tabId
        var replacedPage: ControlledBrowserPage? = null
        synchronized(lock) {
            if (opened.result.ok && tabId != null) {
                // A reused tab (manual navigation) replaces its old page in place. Keep the tab
                // bounded and destroy the page nobody will show again.
                replacedPage = pages[tabId]?.page?.takeIf { it !== created }
                created.tabId = tabId
                tabModes[tabId] = requestedDesktop
                pages[tabId] = BoundPage(created, pageAdapter, viewGeneration + 1, desktop = requestedDesktop)
                page = created
                adapter = pageAdapter
                selectedTabId = tabId
                viewGeneration += 1
                documentEpoch += 1
                mode = ProviderMode.MANUAL
                val revealed = hidden
                hidden = false
                // Only a person's own open replaces an explicit takeover decision. An agent open
                // may show the window, but it cannot reclaim control after a takeover.
                if (request.serverEpoch == "user") explicitTakeover = false
                if (revealed) presentationRequestSerial += 1
                created.manualNavigation = false
                bind(created)
                // Explicit open: default control is decided once this exact load becomes ready.
                // pageAdapter.openClaimed has already run present() -> loadOnce(), which calls
                // beginControlledGet() and fixes the ticket; viewId is fixed at page creation.
                // The ready callback can only run after this main coroutine yields, so these
                // identities are final here and the first probe cannot miss the intent.
                autoGrantIntent = AutoGrantIntent(
                    tabId = tabId,
                    viewId = created.viewId(),
                    ticket = created.ticket(),
                    consoleGeneration = prepared.host.consoleGeneration,
                    consoleSession = consoleSessionId,
                    // Bind to the connection this open was admitted on, never a connection that
                    // replaced it while the load was in flight.
                    connection = request.connectionId.takeIf { it.isNotBlank() } ?: connectionId,
                    page = created,
                )
                noteOpenedByAgent(request, tabId)
            } else {
                created.destroy()
            }
            if (replacedPage != null) scrollMemory.invalidateContext()
            publishLocked(shown = opened.result.ok)
        }
        replacedPage?.destroy()
        if (!opened.result.ok || tabId == null || request.allowWaitMs <= 0) return@withContext opened.result
        // Wait only for control, never granting it here: the ready-time default control (through
        // allow) or the person's own Allow tap ends the wait. A tab that is not the visible page,
        // or whose page/connection changed, ends the wait at once.
        val budget = minOf(request.allowWaitMs.toLong(), (deadlineElapsed - now()).coerceAtLeast(0L))
        synchronized(lock) { allowWaitActive = true }
        val granted = try {
            awaitOpenGrant(session, tabId, created, request.connectionId, budget)
        } finally {
            synchronized(lock) { allowWaitActive = false }
        }
        if (!granted) return@withContext opened.result
        ProviderResult(ok = true, data = drivableOpenPayload(opened.result.data))
    }

    /**
     * Compatibility no-op. Explicit opens no longer wait for an Allow tap: the one-shot
     * [autoGrantIntent] grants default control after ready, through [allow]. Keeping the helper
     * central lets a future opt-out clear any stale notice instead of adding one.
     */
    private fun noteOpenedByAgent(request: ProviderRequest, tabId: String) {
        awaitingAllowTabs.remove(tabId)
    }

    private enum class OpenWait { WAITING, GRANTED, STOPPED }

    /**
     * Watches the opened page until control exists for it: either the default control granted when
     * the explicit open becomes ready, or the person's own explicit Allow tap. It observes only:
     * the presenter's own grant is the success signal, and any other outcome leaves authority
     * exactly as it was.
     */
    private suspend fun awaitOpenGrant(
        session: String,
        tabId: String,
        page: ControlledBrowserPage,
        connection: String,
        waitMs: Long,
    ): Boolean {
        if (waitMs <= 0) return false
        withTimeoutOrNull(waitMs) {
            state.first { openWaitState(it, session, tabId, page, connection) != OpenWait.WAITING }
        } ?: return false
        return openWaitState(state.value, session, tabId, page, connection) == OpenWait.GRANTED
    }

    private fun openWaitState(
        current: NativeBrowserState,
        session: String,
        tabId: String,
        page: ControlledBrowserPage,
        connection: String,
    ): OpenWait = synchronized(lock) {
        val grant = registry.currentGrant()
        val bound = pages[tabId]
        // A hidden window, another selection, a replaced/retired page, a hand navigation or a lost
        // connection can no longer become this open's Allow: end the wait.
        val watchable = current.shown && selectedTabId == tabId && bound != null && !bound.retired &&
            bound.page === page && !page.manualNavigation && page.tabId == tabId &&
            connectionId == connection && host != null && !paused && !consoleInvalidated &&
            !presentationBlocked
        when {
            !watchable -> OpenWait.STOPPED
            grant != null && grant.connectionId == connection && grant.sessionId == session &&
                grant.tabId == tabId && mode == ProviderMode.AGENT -> OpenWait.GRANTED
            // Any other live grant belongs to somebody else and can never become this page's grant.
            grant != null -> OpenWait.STOPPED
            else -> OpenWait.WAITING
        }
    }

    /** The normal open payload with the one difference a waited-for Allow makes. */
    private fun drivableOpenPayload(data: JsonObject): JsonObject =
        JsonObject(
            data.toMutableMap().apply {
                put("drivable", JsonPrimitive(true))
                remove("reason")
            },
        )

    private fun bind(created: ControlledBrowserPage) {
        created.onReadyProbe = { identity -> scope.launch(main) { acceptProbe(created, identity) } }
        created.onFatal = { onFatal(created) }
        created.onUnknown = { onUnknown(created) }
        created.onManualDocument = { onManualDocument(created) }
        created.onGeometry = { sample -> acceptSample(created, sample) }
        created.onGeometryRequested = { scope.launch(main) { onGeometrySettled() } }
        created.onMouseRetirement = { reason -> retireBound(created, reason) }
    }

    private fun acceptSample(source: ControlledBrowserPage, sample: GeometrySample) {
        synchronized(lock) {
            // Background, paused, invalidated-console or blocked callbacks are not submitted as ready.
            // This is the same foreground admission the ready request and command dispatch already use.
            if (host == null || paused || hidden || consoleInvalidated || presentationBlocked) return
            val bound = pages[selectedTabId]
            if (page !== source || bound?.page !== source || bound.retired || bound.outcomeUnknown) return
            if (!source.nativePresentationVisible()) return
            val witness = source.acceptedDocument() ?: return
            if (source.tabId != selectedTabId) return
            // Full identity and frame checks run before currentProbeSerial moves. A stale callback
            // must not raise the serial or replace a newer accepted geometry.
            if (sample.viewId != source.viewId() || sample.tabId != source.tabId || sample.ticket != source.ticket()) return
            if (sample.documentId != witness || sample.probe.documentId != witness) return
            if (sample.epoch != geometryGeneration) return
            if (sample.serial < currentProbeSerial) return
            val frame = source.nativeFrame()
            if (!validNativeFrame(frame) || sample.frame != frame) return
            val identity = ProbeIdentity(sample.viewId, source.tabId, sample.ticket, witness, sample.epoch, sample.serial)
            val current = ProbeIdentity(source.viewId(), source.tabId, source.ticket(), witness, geometryGeneration, sample.serial)
            if (!BrowserGeometry.accept(sample.probe, identity, current)) return
            // All validation passed: only now may the serial advance.
            currentProbeSerial = sample.serial
            val pending = inflightGeometry
            if (pending != null && pending.source === source && pending.identity.probeSerial <= sample.serial) {
                inflightGeometry = null
            }
            acceptGeometry(sample.probe, identity, frame.width, frame.height)
        }
        // A mounted, visible page is the bounded retry point for an open that was blocked/not yet
        // attached when its ready probe arrived.
        scheduleAutoGrantRetry()
    }

    internal suspend fun acceptProbeForTest(source: ControlledBrowserPage, identity: ReadyProbeIdentity) {
        check(BuildConfig.DEBUG) { "acceptProbeForTest is debug-only" }
        withContext(main) { acceptProbe(source, identity) }
    }

    private suspend fun acceptProbe(source: ControlledBrowserPage, identity: ReadyProbeIdentity) {
        var current = identity
        var attempts = 0
        while (true) {
            val retry = acceptProbeOnce(source, current) ?: return
            // Only a ref-epoch move on the same view/load ticket is retried; never a navigation.
            attempts += 1
            if (attempts > READY_REPROBE_LIMIT) {
                android.util.Log.i("AndChamberProvider", "probe reprobe limit ticket=${retry.ticket} epoch=${retry.sessionEpoch}")
                return
            }
            android.util.Log.i("AndChamberProvider", "probe reprobe ticket=${retry.ticket} epoch=${retry.sessionEpoch}")
            current = retry
        }
    }

    /**
     * A ready probe whose identity went stale only because document refs were invalidated (revoke,
     * console change, geometry generation) while it was in flight. The same view and the same load
     * ticket still own the page and the page is not ready yet: no navigation started, so no later
     * commit/finished callback will ever probe again. Returns the current identity to re-probe once,
     * or null when the page moved on, is ready already, or the epoch did not actually change.
     */
    private fun staleReadyRetryLocked(source: ControlledBrowserPage, identity: ReadyProbeIdentity): ReadyProbeIdentity? {
        if (page !== source || source.ready) return null
        if (source.viewId() != identity.viewId || source.ticket() != identity.ticket) return null
        val epochNow = source.refEpoch()
        if (epochNow == identity.sessionEpoch) return null
        val next = ReadyProbeIdentity(identity.viewId, identity.ticket, epochNow)
        return if (source.matchesReady(next)) next else null
    }

    /** One probe attempt. Returns a fresh identity only for [staleReadyRetryLocked]; otherwise null. */
    private suspend fun acceptProbeOnce(source: ControlledBrowserPage, identity: ReadyProbeIdentity): ReadyProbeIdentity? {
        val raw = synchronized(lock) {
            if (page !== source) return null
            if (!source.matchesReady(identity)) return staleReadyRetryLocked(source, identity)
            source
        }
        val probed = raw.probe(identity)
        var geometryRequest: Pair<ControlledBrowserPage, ProbeIdentity>? = null
        var autoGrantConnection: String? = null
        synchronized(lock) {
            if (page !== source || !source.matchesReady(identity) || probed == null) {
                android.util.Log.i("AndChamberProvider", "probe discard ticket=${identity.ticket} epoch=${identity.sessionEpoch} reason=stale")
                return staleReadyRetryLocked(source, identity)
            }
            when (probed) {
                is ProbeAcceptance.Accept -> {
                    if (source.acceptReady(identity.ticket, probed.documentId)) {
                        android.util.Log.i("AndChamberProvider", "probe accept ticket=${identity.ticket} epoch=${identity.sessionEpoch}")
                        publishLocked()
                        // The first layout/commit can reach geometry before this probe is accepted. The
                        // accepted witness now admits a probe, so request it once with this captured view.
                        geometryRequest = readyGeometryLocked(source, identity, probed.documentId)?.let { source to it }
                        autoGrantConnection = pendingAutoGrantConnectionLocked(source, identity)
                    } else {
                        android.util.Log.i("AndChamberProvider", "probe reject ticket=${identity.ticket} epoch=${identity.sessionEpoch} reason=ready-refused")
                        onUnknown(source)
                    }
                }
                is ProbeAcceptance.Pending -> android.util.Log.i("AndChamberProvider", "probe pending ticket=${identity.ticket} epoch=${identity.sessionEpoch}")
                is ProbeAcceptance.Reject -> {
                    val reason = when (probed.message) {
                        "no witness", "document changed", "witness already used", "not ready", "url refused", "loading", "no probe" -> probed.message
                        else -> "probe-rejected"
                    }
                    android.util.Log.i("AndChamberProvider", "probe reject ticket=${identity.ticket} epoch=${identity.sessionEpoch} reason=$reason")
                    onUnknown(source)
                }
            }
        }
        // Main, no suspension: use the source captured above, never a globally re-read page.
        geometryRequest?.let { (captured, request) -> captured.requestGeometryProbe(request) }
        // Default control for this explicit open, once and through the same [allow] verification.
        // Without an intent (a probe, a page-finished callback, a restored window) this is a no-op.
        // A temporary block/visibility failure keeps the intent and schedules one bounded retry.
        autoGrantConnection?.let { expected ->
            if (!allowInternal(expected, source, source.tabId)) scheduleAutoGrantRetry()
        }
        return null
    }

    /**
     * Returns the expected connection when this accepted ready probe may complete the explicit
     * open's default control. Identity failures clear the intent; a temporary presentation block or
     * not-yet-visible page keeps it for the bounded retry. Runs under the lock.
     */
    private fun pendingAutoGrantConnectionLocked(source: ControlledBrowserPage, identity: ReadyProbeIdentity): String? {
        val intent = autoGrantIntent ?: return null
        // A probe for a different page must not touch another open's pending intent.
        if (intent.page !== source) return null
        val bound = pages[intent.tabId] ?: run {
            autoGrantIntent = null
            return null
        }
        val matches = intent.page === source && bound.page === source && !bound.retired &&
            source.tabId == intent.tabId && selectedTabId == intent.tabId &&
            source.ticket() == intent.ticket && source.viewId() == intent.viewId &&
            identity.ticket == intent.ticket && identity.viewId == intent.viewId &&
            host?.consoleGeneration == intent.consoleGeneration &&
            // A session known at open time must still be the console's session. An unknown one at
            // open time may become known; it must not silently become a different session later.
            (intent.consoleSession == null || consoleSessionId == intent.consoleSession) &&
            intent.connection != null && connectionId == intent.connection
        if (!matches) {
            autoGrantIntent = null
            return null
        }
        // Temporary states keep the pending intent; the unblock/mount path retries it.
        if (presentationBlocked || !source.ready) return null
        if (hidden || paused || consoleInvalidated || explicitTakeover) {
            autoGrantIntent = null
            return null
        }
        return intent.connection
    }

    /**
     * One bounded retry of a pending default-control intent after the page mounted or the
     * presentation gate cleared. It never clears the intent on a temporary failure and never grants
     * across a different connection/page/owner. A single retry runs at a time.
     */
    private fun scheduleAutoGrantRetry() {
        val source = synchronized(lock) {
            if (autoGrantRetryActive) return
            val intent = autoGrantIntent ?: return
            if (hidden || explicitTakeover || paused || consoleInvalidated || presentationBlocked) return
            if (!presentableLocked()) return
            val bound = pages[intent.tabId] ?: return
            if (bound.retired || bound.page !== page || selectedTabId != intent.tabId) return
            if (intent.connection == null || connectionId != intent.connection) return
            autoGrantRetryActive = true
            bound.page
        }
        scope.launch(main) {
            try {
                completePendingAutoGrant(source)
            } finally {
                synchronized(lock) { autoGrantRetryActive = false }
            }
        }
    }

    private suspend fun completePendingAutoGrant(source: ControlledBrowserPage) {
        if (!withContext(main) { awaitPageVisible(source) }) return
        val intent = synchronized(lock) {
            val current = autoGrantIntent ?: return@synchronized null
            // A retry captured for an older page must never clear a newer open's intent.
            if (current.page !== source) return@synchronized null
            val bound = pages[current.tabId] ?: run {
                autoGrantIntent = null
                return@synchronized null
            }
            val identity = current.page === source && bound.page === source && !bound.retired &&
                page === source && selectedTabId == current.tabId &&
                source.ticket() == current.ticket && source.viewId() == current.viewId &&
                host?.consoleGeneration == current.consoleGeneration &&
                (current.consoleSession == null || consoleSessionId == current.consoleSession) &&
                current.connection != null && connectionId == current.connection
            if (!identity) {
                autoGrantIntent = null
                return@synchronized null
            }
            // Keep it while the page is not ready or still presentation-blocked.
            if (presentationBlocked || !source.ready) return@synchronized null
            if (hidden || paused || consoleInvalidated || explicitTakeover) {
                autoGrantIntent = null
                return@synchronized null
            }
            current
        } ?: return
        allowInternal(intent.connection, intent.page, intent.tabId)
    }

    /**
     * Decides whether an accepted ready probe needs its own post-accept geometry request. It runs under
     * the presenter lock and only records a request when the same source, document and geometry epoch
     * does not already have a valid measurement or an identical in-flight request.
     */
    private fun readyGeometryLocked(
        source: ControlledBrowserPage,
        identity: ReadyProbeIdentity,
        witness: String,
    ): ProbeIdentity? {
        val bound = pages[selectedTabId] ?: return null
        if (page !== source || bound.page !== source || bound.retired || bound.outcomeUnknown) return null
        if (host == null || paused || hidden || consoleInvalidated || presentationBlocked) return null
        if (!source.matchesReady(identity)) return null
        if (source.acceptedDocument() != witness) return null
        val viewId = source.viewId()
        val ticket = source.ticket()
        val tabId = source.tabId
        if (viewId != identity.viewId || ticket != identity.ticket || tabId != selectedTabId) return null
        // Attachment and visibility come from the real view; an unmounted ready page is not faked.
        if (!source.nativePresentationVisible()) return null
        val frame = source.nativeFrame()
        if (!validNativeFrame(frame)) return null
        val generation = geometryGeneration
        val accepted = geometry
        if (accepted != null && accepted.ready && accepted.sameFrame(frame) &&
            accepted.viewId == viewId && accepted.tabId == tabId &&
            accepted.ticket == ticket && accepted.documentId == witness && accepted.generation == generation
        ) {
            return null
        }
        val pending = inflightGeometry
        if (pending != null && pending.source === source && pending.boundGeneration == bound.generation &&
            pending.witness == witness && pending.frame == frame &&
            pending.identity.viewId == viewId && pending.identity.tabId == tabId &&
            pending.identity.ticket == ticket && pending.identity.generation == generation
        ) {
            return null
        }
        currentProbeSerial += 1
        val request = ProbeIdentity(viewId, tabId, ticket, witness, generation, currentProbeSerial)
        inflightGeometry = GeometryRequest(source, request, bound.generation, witness, frame)
        return request
    }

    private fun validNativeFrame(frame: NativeFrame): Boolean =
        frame.width > 1 && frame.height > 1 &&
            frame.visibleRight > frame.visibleLeft && frame.visibleBottom > frame.visibleTop

    private fun MeasuredGeometry.sameFrame(frame: NativeFrame): Boolean =
        viewWidthPx == frame.width && viewHeightPx == frame.height &&
            windowLeft == frame.windowLeft && windowTop == frame.windowTop &&
            visibleLeft == frame.visibleLeft && visibleTop == frame.visibleTop &&
            visibleRight == frame.visibleRight && visibleBottom == frame.visibleBottom

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
            // A document started by hand is a new navigation: an open's default-control intent does
            // not survive it.
            autoGrantIntent = null
            source.tabId?.let { awaitingAllowTabs.remove(it) }
            source.clearAcceptedWitness()
            inflightGeometry = null
            scrollMemory.observePage(scrollIdentity())
            pages[source.tabId]?.outcomeUnknown = false
            pages[source.tabId]?.modeNotice = null
            publishLocked()
        }
    }

    /**
     * Presentation only for a user-hidden window, triggered by an agent action. It requires the
     * same trusted foreground host, connection and tab owner. Explicit takeover blocks it; a
     * paused/invalidated console or a blocked presentation blocks it too. After clearing [hidden]
     * it waits (bounded) for the kept page to be mounted, then re-establishes default control only
     * through [allowInternal]'s full verification and returns its real result. A failed wake stays
     * pending for the next foreground action, bound to that page/owner/connection. A plain user
     * [show]/[restore] never calls this, and an explicit open shows through [openClaimed].
     */
    private suspend fun restoreForAgentAction(request: ProviderRequest, session: String): Boolean {
        if (request.action == "browser.open") return false
        val wake = synchronized(lock) {
            val pending = pendingWake
            if (pending != null) {
                // A different connection or owner invalidates the old wake; it must never call
                // allow() for the new one.
                if (pending.connectionId != request.connectionId || pending.ownerSessionId != session) {
                    pendingWake = null
                    return@synchronized null
                }
                if (explicitTakeover) {
                    pendingWake = null
                    return@synchronized null
                }
                // A temporary pause/block keeps the wake for the next foreground action. No new
                // presentation request is made for a retry.
                if (!presentableLocked()) return@synchronized null
                val bound = pages[pending.tabId]
                if (bound == null || bound.retired || bound.page !== pending.page ||
                    page !== pending.page || selectedTabId != pending.tabId ||
                    connectionId != pending.connectionId ||
                    registry.find(pending.tabId)?.ownerSessionId != pending.ownerSessionId
                ) {
                    pendingWake = null
                    return@synchronized null
                }
                pending
            } else {
                if (!hidden || explicitTakeover || !presentableLocked()) return@synchronized null
                if (connectionId == null || connectionId != request.connectionId) return@synchronized null
                // The window would present the selected page; the owner and page checks are the
                // ones the following refusal/authorization will use.
                val tab = selectedTabId?.let { registry.find(it) } ?: return@synchronized null
                if (tab.ownerSessionId != session) return@synchronized null
                val bound = pages[tab.tabId]
                if (bound == null || bound.retired || bound.page !== page) return@synchronized null
                val next = PendingWake(
                    page = bound.page,
                    tabId = tab.tabId,
                    ownerSessionId = session,
                    connectionId = connectionId ?: return@synchronized null,
                )
                hidden = false
                presentationRequestSerial += 1
                pendingWake = next
                publishLocked()
                next
            }
        } ?: return false
        // The UI has to recompose and attach the kept page to the restored (small) window. A
        // native page that is not shown yet would make allow() refuse, so wait on main. A failed
        // wake stays pending for the next foreground action (same page/connection/owner).
        if (!withContext(main) { awaitPageVisible(wake.page) }) return false
        val stillCurrent = synchronized(lock) {
            pendingWake === wake && page === wake.page && !hidden && !paused &&
                !consoleInvalidated && !presentationBlocked && !explicitTakeover &&
                pages[wake.tabId]?.page === wake.page && selectedTabId == wake.tabId &&
                connectionId == wake.connectionId &&
                registry.find(wake.tabId)?.ownerSessionId == wake.ownerSessionId
        }
        if (!stillCurrent) return false
        val granted = allowInternal(wake.connectionId, wake.page, wake.tabId)
        synchronized(lock) {
            if (granted && pendingWake === wake) pendingWake = null
        }
        return granted
    }

    private fun refusal(request: ProviderRequest, session: String): ProviderResult? {
        if (host == null) return unavailable()
        // A hidden window is not a page to be driven or read: refuse before any claim. This is the
        // person's explicit choice, so it is stronger than an ordinary absent foreground. An
        // explicit open is the one exception: it may show the window again after its own checks.
        if (hidden && request.action != "browser.open") return unavailable()
        // A blocked presentation is not a hidden page to be driven or read: refuse before any claim.
        if (presentationBlocked) return unavailable()
        if (connectionId == null || connectionId != request.connectionId) {
            return ProviderResult(false, errorCode = ProviderCodes.INVALID_REQUEST, message = "connection refused")
        }
        if (request.action != "browser.open") {
            val named = request.parameters["tabId"]
            val target = if (named.isNullOrBlank()) registry.find(selectedTabId.orEmpty()) else registry.find(named)
            if (target == null || target.ownerSessionId != session || pages[target.tabId]?.outcomeUnknown == true) {
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
                granted = grant != null && grant.tabId == page.tabId && grant.tabId == selectedTabId &&
                    !presentationBlocked,
                geometryEpoch = geometryGeneration,
            )
        },
        onOutcomeUnknown = {
            synchronized(lock) { pages[page.tabId]?.outcomeUnknown = true }
        },
        onDispatch = {
            notePageInteraction()
            synchronized(lock) {
                pages[page.tabId]?.mutationInFlight = true
                activeDispatched = true
                android.util.Log.i("AndChamberProvider", "action=$activeAction phase=dispatched")
            }
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
        retireActivePress("page-released")
        inflightGeometry = null
        autoGrantIntent = null
        pendingWake = null
        pages.values.forEach { it.page.destroy() }
        pages.clear()
        page?.destroy()
        page = null
        adapter = null
        viewGeneration += 1
    }

    private fun publishLocked(shown: Boolean? = null, error: String? = page?.pageError) {
        // Mouse mode exists only on a desktop-layout page. Opening or selecting a phone-layout page
        // must not keep it on: its menu cannot turn it off there. This only ever switches it off.
        if (mouseMode && tabModes[selectedTabId] != true) mouseMode = false
        val bound = pages[selectedTabId]
        val forced = when {
            bound?.page?.pageError != null -> bound.page.pageError
            bound?.outcomeUnknown == true -> "Re-open this page before allowing control"
            else -> bound?.modeNotice ?: error
        }
        // A retired selection stays visible as its placeholder; it is not a hidden page. A hidden
        // window stays hidden across every publish until show/restore or an explicit open.
        val retiredSelected = bound?.retired == true
        val visible = !hidden && (shown ?: (selectedTabId != null && (page != null || retiredSelected) && host != null))
        val tabs = registry.tabs().map { tab ->
            NativeBrowserTab(
                id = tab.tabId,
                title = tab.title,
                url = tab.url,
                owner = tab.ownerSessionId,
                active = tab.tabId == selectedTabId,
            )
        }
        // Transport admission is independent of the page presentation gate. A blocked window keeps
        // the provider connection; only losing the trusted foreground/console revokes it.
        val transportEligible = host != null && !paused && !consoleInvalidated
        val previous = _state.value
        val transportInvalidation = previous.transportInvalidation +
            if (previous.transportEligible && !transportEligible) 1L else 0L
        _state.value = NativeBrowserState(
            tabs = tabs,
            selectedTabId = selectedTabId,
            shown = visible,
            mode = mode,
            agentOn = mode == ProviderMode.AGENT && registry.currentGrant() != null && !presentationBlocked && !hidden,
            pageError = if (pages[selectedTabId]?.retired == true) "mouse-gesture-interrupted" else forced,
            pageReady = pages[selectedTabId]?.retired != true && page?.ready == true,
            pageRetired = pages[selectedTabId]?.retired == true,
            mousePressActive = page?.mousePressActive() == true,
            viewHandle = page?.handle ?: 0,
            viewGeneration = viewGeneration,
            hostAttached = host != null,
            documentEpoch = documentEpoch,
            paused = paused,
            hidden = hidden,
            presentationRequestSerial = presentationRequestSerial,
            explicitTakeover = explicitTakeover,
            transportEligible = transportEligible,
            transportInvalidation = transportInvalidation,
            eligible = transportEligible && !presentationBlocked && !hidden,
            presentationBlocked = presentationBlocked,
            desktopMode = tabModes[selectedTabId] == true,
            mouseMode = mouseMode,
            pointerX = geometry?.clientX(pointerViewX) ?: 0f,
            pointerY = geometry?.clientY(pointerViewY) ?: 0f,
            pointerUi = PointerUi(page?.handle ?: 0, geometryGeneration, pointerViewX, pointerViewY, geometry?.ready == true),
            connected = connectionId != null,
            awaitingAllow = selectedTabId != null && selectedTabId in awaitingAllowTabs,
        )
    }

    private fun unavailable() = ProviderResult(
        ok = false,
        errorCode = "BROWSER_PAGE_UNAVAILABLE",
        message = "native browser host is not attached",
    )

    private fun ignored(value: String?) = value

    private data class GeometryRequest(
        val source: ControlledBrowserPage,
        val identity: ProbeIdentity,
        val boundGeneration: Long,
        val witness: String,
        val frame: NativeFrame,
    )

    private data class BoundPage(
        val page: ControlledBrowserPage,
        val adapter: OpenChamberPageAdapter,
        val generation: Long,
        val desktop: Boolean = false,
        var outcomeUnknown: Boolean = false,
        var modeNotice: String? = null,
        var mutationInFlight: Boolean = false,
        var retired: Boolean = false,
        var retireReason: String? = null,
    )

    private data class ReopenTarget(val tab: ProviderTab, val desktop: Boolean)

    /** One explicit open's default-control decision, valid for one page and one navigation. */
    private data class AutoGrantIntent(
        val tabId: String,
        val viewId: String,
        val ticket: Long,
        val consoleGeneration: Long,
        val consoleSession: String?,
        val connection: String?,
        val page: ControlledBrowserPage,
    )

    /** A hidden-window wake bound to the identity it was created for. */
    private data class PendingWake(
        val page: ControlledBrowserPage,
        val tabId: String,
        val ownerSessionId: String,
        val connectionId: String,
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
    /** The real view is attached and shown, as reported by the platform view rather than a policy flag. */
    fun nativePresentationVisible(): Boolean = view?.let { it.isAttachedToWindow && it.isShown } ?: false
    val handle: Int
    val width: Int
    val height: Int
    val pageError: String?
    val ready: Boolean
    var manualNavigation: Boolean
    var onProbe: ((String, Long) -> Unit)?
    var onReadyProbe: ((ReadyProbeIdentity) -> Unit)?
    var onFatal: (() -> Unit)?
    var onUnknown: (() -> Unit)?
    var onManualDocument: (() -> Unit)?
    var onGeometry: ((GeometrySample) -> Unit)?
    var onGeometryRequested: (() -> Unit)?
    fun driver(policy: () -> BrowserUrlPolicy): BrowserDriver
    suspend fun loadOnce(url: String): Boolean
    suspend fun probe(identity: ReadyProbeIdentity): ProbeAcceptance? = probe()
    suspend fun probe(): ProbeAcceptance
    fun acceptReady(ticket: Long, documentId: String): Boolean
    fun matchesReady(identity: ReadyProbeIdentity): Boolean = false
    fun owns(viewId: String, ticket: Long): Boolean
    fun markUnknown()
    fun clearAcceptedWitness()
    fun syncWitness(documentId: String)
    suspend fun currentFields(): Map<String, PageField>?
    suspend fun currentField(ref: String): PageField? = currentFields()?.get(ref)
    fun acceptedDocument(): String? = null
    /** The live top-level URL the page is showing, when the implementation knows it. */
    fun currentUrl(): String? = null
    fun setDesktopMode(enabled: Boolean) = Unit
    fun desktopConfigured(): Boolean = false
    fun applyMouseButton(plan: MouseEventPlan): Boolean = true
    fun controlledGetProvenance(): Boolean = false
    fun reloadGet() = Unit
    fun injectMouse(plan: MouseEventPlan): Boolean = true
    fun lastMouseRetirement(): String? = null
    var onMouseRetirement: ((String) -> Unit)?
        get() = null
        set(_) = Unit
    fun mousePressActive(): Boolean = false
    fun retireMouse(reason: String, unavoidable: Boolean): MousePressOutcome = MousePressOutcome.NoActivePress
    fun admitNativeInput(): Boolean = true
    fun clearMouseButtons() = Unit
    fun invalidateSessionRefs() = Unit
    fun clearGeometryCache() = Unit
    suspend fun readScroll(): ScrollSample? = null
    suspend fun restoreScroll(x: Double, y: Double, targetHeightPx: Int, nativeWidthPx: Int): ScrollAnchor? = null
    fun refEpoch(): Long = 0L
    fun latestGeometry(): GeometrySample? = null
    fun requestGeometryProbe() = Unit
    fun requestGeometryProbe(identity: ProbeIdentity) = requestGeometryProbe()
    fun prepareMode(enabled: Boolean) = setDesktopMode(enabled)
    fun nativeFrame(): NativeFrame = NativeFrame(0, 0, 0, 0, 0, 0, 0, 0)
    fun viewId(): String = ""
    fun ticket(): Long = 0L
    fun isInjectingMouse(): Boolean = false
    fun noteTakeover() = Unit
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
    override var onReadyProbe: ((ReadyProbeIdentity) -> Unit)?
        get() = session.onReadyProbe
        set(value) { session.onReadyProbe = value }
    override var onProbe: ((String, Long) -> Unit)?
        get() = session.onProbe
        set(value) { session.onProbe = value }
    override var onFatal: (() -> Unit)?
        get() = session.onFatal
        set(value) { session.onFatal = value }
    override var onUnknown: (() -> Unit)?
        get() = session.onUnknown
        set(value) { session.onUnknown = value }
    override var onGeometryRequested: (() -> Unit)? = null
        set(value) {
            field = value
            session.onGeometryRequested = value
        }
    override var onGeometry: ((GeometrySample) -> Unit)? = null
        set(value) {
            field = value
            session.onGeometry = value
        }
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
        val ticket = session.beginControlledGet()
        session.markControlledGet(url)
        ticket.hashCode()
        if (!session.installWitness()) return false
        session.load(url, session.epoch())
        return true
    }

    override suspend fun probe(identity: ReadyProbeIdentity): ProbeAcceptance? {
        val raw = session.eval(BrowserDriverLogic.probeScript(), identity.sessionEpoch) ?: return null
        if (!session.matchesReady(identity)) return null
        val allowed = session.displayUrl?.let { BrowserUrlPolicy().checkSyntax(it) is UrlDecision.Allow } == true
        return BrowserDriverLogic.acceptProbe(session.acceptedDocumentId, raw, allowed, allowSameDocument = true)
    }

    override suspend fun probe(): ProbeAcceptance {
        val raw = session.eval(BrowserDriverLogic.probeScript(), session.epoch()) ?: return ProbeAcceptance.Reject("no probe")
        val allowed = session.displayUrl?.let { BrowserUrlPolicy().checkSyntax(it) is UrlDecision.Allow } == true
        // Allow is a fresh observation of the document already shown; the same witness is expected.
        return BrowserDriverLogic.acceptProbe(session.acceptedDocumentId, raw, allowed, allowSameDocument = true)
    }

    override fun acceptReady(ticket: Long, documentId: String): Boolean {
        val accepted = session.acceptReady(ticket, documentId)
        if (accepted && session.controlledGet) session.noteControlledDocument(documentId)
        return accepted
    }
    override fun matchesReady(identity: ReadyProbeIdentity): Boolean = session.matchesReady(identity)
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
    override fun currentUrl(): String? = session.displayUrl
    override fun setDesktopMode(enabled: Boolean) = session.configureDesktop(enabled)
    override fun desktopConfigured(): Boolean = session.desktopConfigured
    override fun applyMouseButton(plan: MouseEventPlan): Boolean = true
    override fun controlledGetProvenance(): Boolean =
        session.controlledGet && session.controlledGetUrl == session.displayUrl && session.controlledGetTicket == session.ticket
    /**
     * Reloads the current GET page after a UA change, as a fresh controlled GET: a new load ticket and
     * start count, so the document start is expected rather than read as an unknown navigation. The
     * witness script installed at the first load stays registered for every later document.
     */
    override fun reloadGet() {
        val url = session.displayUrl ?: return
        val ticket = session.beginControlledGet()
        session.markControlledGet(url)
        ticket.hashCode()
        session.load(url, session.epoch())
    }
    override fun injectMouse(plan: MouseEventPlan): Boolean = session.injectMouse(plan)
    override fun lastMouseRetirement(): String? = session.lastMouseRetirement()
    override var onMouseRetirement: ((String) -> Unit)?
        get() = session.onMouseRetirement
        set(value) { session.onMouseRetirement = value }
    override fun mousePressActive(): Boolean = session.mousePressActive()
    override fun retireMouse(reason: String, unavoidable: Boolean): MousePressOutcome = session.retireMouse(reason, unavoidable)
    override fun admitNativeInput(): Boolean = session.admitNativeInput()
    override fun clearMouseButtons() = session.clearMouseButtons()
    override fun invalidateSessionRefs() = session.invalidateRefs()
    override fun clearGeometryCache() { session.clearGeometryCache() }
    override suspend fun readScroll(): ScrollSample? = session.readScroll()
    override suspend fun restoreScroll(x: Double, y: Double, targetHeightPx: Int, nativeWidthPx: Int): ScrollAnchor? =
        session.restoreScroll(x, y, targetHeightPx, nativeWidthPx)
    override fun refEpoch(): Long = session.epoch()
    override fun latestGeometry(): GeometrySample? = session.latestGeometry
    override fun requestGeometryProbe() = Unit
    override fun requestGeometryProbe(identity: ProbeIdentity) = session.requestGeometry(identity)
    override fun prepareMode(enabled: Boolean) = session.configureDesktop(enabled)
    override fun nativeFrame(): NativeFrame = session.currentFrame()
    override fun viewId(): String = session.viewId
    override fun ticket(): Long = session.ticket
    override fun isInjectingMouse(): Boolean = session.injectingMouse
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
