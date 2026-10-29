package io.andchamber.app.phone.android.overlay

import android.content.Context
import android.graphics.Insets
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.andchamber.app.phone.overlay.safety.OverlayExpectation
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import io.andchamber.app.phone.overlay.safety.PhoneOverlayBarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Native-only overlay data. Never the task summary's page text, nodes or input. */
internal data class PhoneOverlaySurfaceState(
    val phase: PhoneOverlayPhase,
    val appLabel: String? = null,
    val summary: String? = null,
    val leaseEndsAtMs: Long? = null,
    /** A permanent native grant has no countdown; render "no time limit", not a huge minute count. */
    val leasePermanent: Boolean = false,
    /** The current task's question, already bound to this task/launch; null when there is none. */
    val sessionPrompt: PhoneOverlayPromptView? = null,
    /** The current task's latest plain notice, independent of the question; null when none. */
    val sessionNotice: PhoneOverlayNoticeView? = null,
    /** Exact native display epoch; prevents a delayed old task feed appearing in a new surface. */
    val grantEpoch: Long? = null,
    val expanded: Boolean = false,
    val endConfirm: Boolean = false,
    val side: PhoneOverlaySide = PhoneOverlaySide.RIGHT,
    val canPause: Boolean = false,
    val canTakeOver: Boolean = false,
    val canEnd: Boolean = false,
    val canReturnToNative: Boolean = true,
    /** Why the agent stopped; null for a plain pause/take-over. */
    val stopReason: PhoneOverlayStopReason? = null,
    /** A stopped task kept for the person; not the same as an ended task. */
    val retained: Boolean = false,
    /** Configure-once entry: opens the native confirmation, never resumes from the overlay. */
    val canContinueInChat: Boolean = false,
    val demo: Boolean = false,
)

/**
 * What the overlay surface may ask for. Every entry is stop, layout or return; none grants, resumes
 * or re-reads. Layout callbacks update the manager's state, not composition.
 */
internal class PhoneOverlaySurfaceActions(
    val onExpand: () -> Unit = {},
    val onCollapse: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onTakeOver: () -> Unit = {},
    val onRequestEnd: () -> Unit = {},
    val onConfirmEnd: () -> Unit = {},
    val onCancelEnd: () -> Unit = {},
    val onReturnToNative: () -> Unit = {},
    val onCloseOverlay: () -> Unit = {},
    val onMoveLeft: () -> Unit = {},
    val onMoveRight: () -> Unit = {},
    val onDragBy: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    val onDragEnd: () -> Unit = {},
    /** Open the local chat for this task; never revokes or resumes the lease. */
    val onOpenChat: () -> Unit = {},
    /** Open the native "continue beside the chat" confirmation; never resumes from here. */
    val onContinueInChat: () -> Unit = {},
    /** One user tap on a shown choice. Native rechecks the frozen form/generation and task first. */
    val onAnswerPrompt: (formId: String, generation: Long, value: String) -> Unit = { _, _, _ -> },
    /** Re-read the pending form after an unknown result or when the answer surface opens. */
    val onRefreshPrompt: () -> Unit = {},
)

/** The designer's overlay composable. Absent means overlay mode cannot run (never an empty window). */
internal interface PhoneOverlayContentPort {
    val available: Boolean

    @Composable
    fun Content(
        state: PhoneOverlaySurfaceState,
        actions: PhoneOverlaySurfaceActions,
    )

    object Unavailable : PhoneOverlayContentPort {
        override val available: Boolean = false

        @Composable
        override fun Content(
            state: PhoneOverlaySurfaceState,
            actions: PhoneOverlaySurfaceActions,
        ) = Unit
    }
}

/** A read could not hide the window; the driver must fail closed, never read through it. */
internal class PhoneOverlayHideRefused(
    val refusal: PhoneOverlayHideRefusal,
) : IllegalStateException("overlay hide refused (${refusal.name})")

/**
 * Owns the one overlay window and is the manager side of the driver's [PhoneOverlayBarrier].
 *
 * The window is a non-focusable `TYPE_APPLICATION_OVERLAY` whose width follows the panel state, never
 * a full-screen transparent layer. Its `LayoutParams.title` is the stable, discoverable token
 * [overlayWindowTitle]; a random UUID cannot be matched by the driver's witness, and a successful
 * `addView` remains a *request*, not visibility.
 *
 * `withContentHidden` refuses to run a read unless the exact owner/generation/revision was hidden and
 * confirmed, and restores under a bounded NonCancellable for the same display owner. A capture hide
 * only makes the attached root view INVISIBLE: the window, its `LayoutParams.title` token, its surface
 * and its Compose owner stay alive, so WindowManager reports `mViewVisibility=0x4` and no
 * remove/add (and no Compose destroy/recreate) can flash the capsule between two reads. A restore
 * whose Main scheduling exceeds that budget is marked uncertain, rejects new actions, and is settled
 * by an independent owner-bound Main cleanup. A temporary read never bumps the generation, and a
 * detach invalidates every pending restore.
 */
internal class PhoneOverlaySurfaceHost(
    private val context: Context,
    private val content: PhoneOverlayContentPort,
    private val demo: Boolean = false,
    private val onVisibilityChanged: (shown: Boolean) -> Unit = {},
    /** Main-thread callback after a timed-out restore is independently settled. */
    private val onReadHideTimedOut: (PhoneOverlayReadLease) -> Unit = {},
    private val onInsetsChanged: () -> Unit = {},
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : PhoneOverlayBarrier {
    private val lock = Any()
    private val main = Handler(Looper.getMainLooper())
    private val windowManager = context.getSystemService(WindowManager::class.java)

    @Volatile
    private var state: PhoneOverlayWindowState? = null
    private var actions: PhoneOverlaySurfaceActions? = null
    @Volatile
    private var view: ComposeView? = null
    private var composeOwner: ComposeOwner? = null
    private var params: WindowManager.LayoutParams? = null

    /** A read owns the window right now; no attach may re-show it. */
    @Volatile
    private var hiddenForRead = false

    /** The exact read ticket; timed-out cleanup is owner/ticket-bound and must not clear a newer one. */
    private val readHideTickets = PhoneOverlayReadHideTickets()

    /** The display owner was detached deliberately; old restores must not revive it. */
    @Volatile
    private var detached = true

    @Volatile
    private var freshnessDeadlineMs = 0L

    /**
     * The window is attached and intended to be visible. A capture-hidden window keeps its real view
     * attached but is "not shown" here, exactly as the former remove/restore path looked to callers.
     */
    val isShown: Boolean get() = synchronized(lock) { view != null && !hiddenForRead }

    val isHiddenForRead: Boolean get() = hiddenForRead

    /** Current screen/inset geometry for drag clamping; null before a display owner exists. */
    fun geometryNow(): PhoneOverlayGeometry? = state?.let(::geometry)

    fun geometryFor(next: PhoneOverlayWindowState): PhoneOverlayGeometry = geometry(next)

    /** Attaches or updates. False while a read hides the window or the designer content is missing. */
    fun attach(
        next: PhoneOverlayWindowState,
        nextActions: PhoneOverlaySurfaceActions,
    ): Boolean {
        checkMainThread()
        if (!content.available) return false
        if (hiddenForRead) return false
        state = next
        actions = nextActions
        val current = view
        if (current != null) {
            applyLayout(current, next)
            render(current, next, nextActions)
            touchFreshness()
            return true
        }
        return addWindow(next, nextActions)
    }

    fun update(
        next: PhoneOverlayWindowState,
        nextActions: PhoneOverlaySurfaceActions,
    ) {
        checkMainThread()
        state = next
        actions = nextActions
        if (hiddenForRead) return
        val current = view ?: return
        applyLayout(current, next)
        render(current, next, nextActions)
        touchFreshness()
    }

    /** Re-clamp after rotation or an IME/inset change. Does nothing while a read owns the window. */
    fun reflow(): PhoneOverlayWindowState? {
        checkMainThread()
        val currentState = state ?: return null
        if (view == null || hiddenForRead) return currentState
        val reflowed = overlayReflow(currentState, geometry(currentState))
        if (reflowed != currentState) {
            state = reflowed
            applyLayout(view ?: return reflowed, reflowed)
        }
        return reflowed
    }

    /** Extends the manager expectation while it still intends to show the window. Not visibility. */
    fun refreshFreshness() {
        if (view == null || hiddenForRead) return
        touchFreshness()
    }

    /**
     * Waits for the next real frame of the currently attached window. Used after an automatic
     * make-way layout so the caller reports a move only once it was applied. False when nothing is
     * attached; the caller's own deadline bounds the wait (a view that never draws leaves it
     * suspended until that bound).
     */
    internal suspend fun awaitFrame(): Boolean {
        val current = view ?: return false
        return withContext(Dispatchers.Main.immediate) {
            awaitOverlayFirstFrame(current)
            view === current
        }
    }

    /** Removes the window and invalidates every pending restore for this display owner. */
    fun detach() {
        checkMainThread()
        val current = view
        if (current != null && !removeWindow(current)) {
            // Keep the real view tracked, but invalidate its authority/restore expectation.
            detached = true
            hiddenForRead = false
            readHideTickets.clear()
            freshnessDeadlineMs = 0L
            onVisibilityChanged(false)
            return
        }
        view = null
        composeOwner?.destroy()
        composeOwner = null
        params = null
        state = state?.copy(expanded = false, endConfirm = false)
        hiddenForRead = false
        readHideTickets.clear()
        detached = true
        synchronized(lock) {
            freshnessDeadlineMs = 0L
        }
        onVisibilityChanged(false)
    }

    override fun expectation(deadlineMs: Long): OverlayExpectation {
        val current = state
        val now = clock()
        val attachedNow = isShown
        val phaseNow =
            when {
                // A Main restore timed out: reject new actions until the exact ticket is settled.
                readHideTickets.uncertainLease != null -> OverlayPhase.UNAVAILABLE
                hiddenForRead -> hiddenPhase
                attachedNow -> OverlayPhase.VISIBLE
                else -> OverlayPhase.UNAVAILABLE
            }
        return synchronized(lock) {
            OverlayExpectation(
                owner = current?.ownerKey ?: "",
                generation = current?.generation ?: 0L,
                revision = current?.revision ?: 0L,
                phase = phaseNow,
                windowToken = if (attachedNow || hiddenForRead) current?.windowTitle else null,
                freshnessDeadlineMs =
                    if (freshnessDeadlineMs > now) {
                        minOf(freshnessDeadlineMs, deadlineMs.takeIf { it > now } ?: freshnessDeadlineMs)
                    } else {
                        now
                    },
            )
        }
    }

    @Volatile
    private var hiddenPhase = OverlayPhase.COLLECTION_HIDDEN

    override suspend fun <T> withContentHidden(
        owner: String,
        generation: Long,
        revision: Long,
        phase: OverlayPhase,
        deadlineMs: Long,
        block: suspend () -> T,
    ): T {
        require(phase == OverlayPhase.CAPTURE_HIDDEN || phase == OverlayPhase.COLLECTION_HIDDEN) {
            "only a bounded display hide"
        }
        val current = state ?: throw PhoneOverlayHideRefused(PhoneOverlayHideRefusal.NOT_ATTACHED)
        val refusal =
            withContext(Dispatchers.Main.immediate) {
                overlayHideRefusal(owner, generation, revision, current, isShown, hiddenForRead)
            }
        if (refusal != null) throw PhoneOverlayHideRefused(refusal)
        val lease = PhoneOverlayReadLease(owner, generation, revision, phase)
        return withOverlayReadHide(
            lease = lease,
            hide = { withContext(Dispatchers.Main.immediate) { hideNow(lease, deadlineMs) } },
            restore = { restoreReadHide(it) },
        ) {
            block()
        }
    }

    /**
     * Hides the attached window without removing it. The root view becomes INVISIBLE, so the next
     * traversal reports the window with `mViewVisibility=0x4` while the surface/token/title and the
     * Compose owner remain the same. The wait for that traversal is what makes the driver's
     * `dumpsys window` forced-hidden witness honest; a view that never gets there refuses the read.
     */
    private suspend fun hideNow(
        lease: PhoneOverlayReadLease,
        deadlineMs: Long,
    ): Boolean {
        checkMainThread()
        val currentView = view ?: return false
        val currentState = state ?: return false
        if (overlayHideRefusal(
                lease.owner,
                lease.generation,
                lease.revision,
                currentState,
                attached = true,
                hiddenForRead = false,
            ) != null
        ) {
            return false
        }
        hiddenPhase = lease.phase
        hiddenForRead = true
        currentView.visibility = View.INVISIBLE
        // Installed before the traversal wait. If cancellation wins while the hide is still being
        // committed, the try/finally in withOverlayReadHide still has this owner-bound ticket.
        readHideTickets.install(lease)
        freshnessDeadlineMs = clock() + FRESHNESS_MS
        val budget = (deadlineMs - clock()).coerceIn(1L, MAX_HIDE_COMMIT_MS)
        // A refused commit leaves the ticket for the caller's restore (which shows the current
        // state again); the driver then fails closed instead of reading through the capsule.
        return awaitViewHidden(currentView, budget)
    }

    /** Waits for the real traversal that carries `View.INVISIBLE` to WindowManager. */
    private suspend fun awaitViewHidden(view: ComposeView, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            awaitOverlayFirstFrame(view)
            view.windowVisibility == View.INVISIBLE
        } ?: false

    private suspend fun restoreReadHide(lease: PhoneOverlayReadLease) {
        settleReadHideWithTimeout(
            lease = lease,
            tickets = readHideTickets,
            timeoutMs = RESTORE_TIMEOUT_MS,
            restore = {
                withContext(Dispatchers.Main.immediate) {
                    restoreAfterRead(lease)
                }
            },
            onTimeout = {
                // The cancelled read's scope must not own this cleanup. Post to Main without waiting;
                // expectation is already UNAVAILABLE through the uncertain ticket.
                main.post { settleTimedOutReadHide(lease) }
            },
        )
    }

    /** Main-thread, owner-bound settlement after a restore scheduling timeout. */
    private fun settleTimedOutReadHide(lease: PhoneOverlayReadLease) {
        checkMainThread()
        val settled = readHideTickets.settleTimedOut(lease) ?: return
        // The restore never reached its visible frame. Remove the still-attached (INVISIBLE) window
        // before the coordinator decides to fail closed or re-show current state; a new owner must
        // never inherit a view whose frame was never confirmed.
        if (view != null) {
            detach()
        } else {
            hiddenForRead = false
        }
        // Never call this a rollback or a stopped lease. The coordinator either fails closed on the
        // still-live native owner or safely re-shows the current already-revoked state.
        onReadHideTimedOut(settled)
    }

    private suspend fun restoreAfterRead(lease: PhoneOverlayReadLease) {
        checkMainThread()
        if (readHideTickets.activeLease() !== lease) {
            // No ticket owns this hide (for example a deliberate detach already cleared it). If a
            // hidden flag was left without an owner, fail safe instead of leaving the attached window
            // invisible forever; a newer ticket must never be touched.
            if (readHideTickets.activeLease() == null && hiddenForRead) {
                hiddenForRead = false
                showCurrentWindow()
            }
            return
        }
        val currentState = state
        val currentActions = actions
        val currentView = view
        if (currentState == null || currentActions == null || currentView == null) {
            if (readHideTickets.settle(lease)) hiddenForRead = false
            if (view == null) onVisibilityChanged(false)
            return
        }
        val refusal =
            overlayRestoreRefusal(
                requestOwner = lease.owner,
                requestGeneration = lease.generation,
                current = currentState,
                attached = true,
                detached = detached,
            )
        if (refusal != null) {
            // A deliberate detach wins. The old read never restores it; the next coordinator refresh
            // may attach the current authoritative state.
            if (readHideTickets.settle(lease)) {
                hiddenForRead = false
                if (!detached) showCurrentWindow()
            }
            return
        }

        // Same window, same owner: render the current native authority and make the very same view
        // visible again. Never remove/add, never destroy/recreate the Compose owner.
        applyLayout(currentView, currentState)
        render(currentView, currentState, currentActions)
        var rendered = currentState

        while (true) {
            currentView.visibility = View.VISIBLE
            val framed =
                awaitCurrentReadFrame(
                    lease = lease,
                    tickets = readHideTickets,
                    ownerKey = { state?.ownerKey.orEmpty() },
                    generation = { state?.generation ?: Long.MIN_VALUE },
                    viewIsCurrent = { view === currentView },
                    awaitFrame = { awaitOverlayFirstFrame(currentView) },
                )
            if (!framed) {
                abandonRestore(lease, currentView)
                return
            }
            val latest = state
            val latestActions = actions
            if (latest == null || latestActions == null || view !== currentView) {
                abandonRestore(lease, currentView)
                return
            }
            if (latest == rendered) {
                // First layout/draw for the current authority completed. Only now may the read
                // settle and return to the driver's post-check.
                if (!readHideTickets.settle(lease)) {
                    abandonRestore(lease, currentView)
                    return
                }
                hiddenForRead = false
                touchFreshness()
                return
            }
            // Pause/Takeover/need_user/new geometry arrived while hidden. Render the latest native
            // authority and require its own frame before settling; never bring back RUNNING.
            applyLayout(currentView, latest)
            render(currentView, latest, latestActions)
            rendered = latest
        }
    }

    /** Fails safe on an attached-but-invisible window that no live ticket owns any more. */
    private fun showCurrentWindow() {
        val current = view ?: return
        if (!detached && current.visibility != View.VISIBLE) current.visibility = View.VISIBLE
    }

    /** A restore cannot settle its old ticket; remove only this read's view and leave new tickets. */
    private fun abandonRestore(
        lease: PhoneOverlayReadLease,
        currentView: ComposeView,
    ) {
        checkMainThread()
        if (readHideTickets.activeLease() !== lease) return
        if (view === currentView) {
            detach()
            return
        }
        readHideTickets.settle(lease)
        hiddenForRead = false
    }

    private suspend fun awaitOverlayFirstFrame(view: ComposeView) {
        suspendCancellableCoroutine { continuation ->
            val observer = view.viewTreeObserver
            if (!observer.isAlive) {
                // A detached view never yields a frame. Leave the bounded restore timeout to mark
                // this ticket uncertain; never claim visibility from addView alone.
                return@suspendCancellableCoroutine
            }
            val listener =
                object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        if (observer.isAlive) observer.removeOnPreDrawListener(this)
                        // Post runs after this traversal/draw on the same main Looper. No sleep and
                        // no second addWindow; the existing view is what the driver will witness.
                        view.post {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        return true
                    }
                }
            observer.addOnPreDrawListener(listener)
            continuation.invokeOnCancellation {
                // Cancellation may arrive off the main thread. Never touch a ViewTreeObserver there;
                // the view's own queue removes the listener once Main is available.
                view.post {
                    runCatching {
                        if (observer.isAlive) observer.removeOnPreDrawListener(listener)
                    }
                }
            }
        }
    }

    private fun addWindow(
        next: PhoneOverlayWindowState,
        nextActions: PhoneOverlaySurfaceActions,
    ): Boolean {
        val manager = windowManager ?: return false
        val composeOwner = ComposeOwner()
        val composeView =
            ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                setViewTreeLifecycleOwner(composeOwner)
                setViewTreeViewModelStoreOwner(composeOwner)
                setViewTreeSavedStateRegistryOwner(composeOwner)
                setOnApplyWindowInsetsListener { _, insets ->
                    post {
                        if (view === this && !hiddenForRead && !detached) onInsetsChanged()
                    }
                    insets
                }
            }
        val layout = layoutParams(next)
        val added =
            runCatching {
                composeOwner.start()
                render(composeView, next, nextActions)
                manager.addView(composeView, layout)
                true
            }.getOrDefault(false)
        if (!added) {
            composeOwner.destroy()
            runCatching { manager.removeView(composeView) }
            view = null
            detached = true
            return false
        }
        view = composeView
        this.composeOwner = composeOwner
        params = layout
        detached = false
        hiddenForRead = false
        touchFreshness()
        onVisibilityChanged(true)
        return true
    }

    private fun layoutParams(next: PhoneOverlayWindowState): WindowManager.LayoutParams {
        val placement = overlayPlacement(next, geometry(next))
        val layout =
            WindowManager.LayoutParams(
                if (next.expanded) placement.widthPx else WindowManager.LayoutParams.WRAP_CONTENT,
                if (next.expanded) placement.heightPx else WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
        layout.gravity =
            if (next.side == PhoneOverlaySide.LEFT) {
                Gravity.TOP or Gravity.START
            } else {
                Gravity.TOP or Gravity.END
            }
        layout.x = placement.horizontalMarginPx
        layout.y = placement.topPx
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // Placement is in full-display coordinates and already excludes bars/IME. Do not let
            // WindowManager add the status-bar origin again (which shifts us into the keyboard).
            layout.setFitInsetsTypes(0)
        }
        // The real, discoverable token the driver can match against its witness.
        layout.title = next.windowTitle
        return layout
    }

    private fun applyLayout(
        current: View,
        next: PhoneOverlayWindowState,
    ) {
        val manager = windowManager ?: return
        val layout = layoutParams(next)
        params = layout
        runCatching { manager.updateViewLayout(current, layout) }
    }

    private fun render(
        current: ComposeView,
        next: PhoneOverlayWindowState,
        nextActions: PhoneOverlaySurfaceActions,
    ) {
        val surface =
            PhoneOverlaySurfaceState(
                phase = next.phase,
                appLabel = next.targetLabel,
                summary = next.summary,
                leaseEndsAtMs = next.leaseEndsAtMs,
                leasePermanent = next.leasePermanent,
                sessionPrompt = next.sessionPrompt,
                sessionNotice = next.sessionNotice,
                grantEpoch = next.ownerKey.removePrefix("grant-").toLongOrNull(),
                expanded = next.expanded,
                endConfirm = next.endConfirm,
                side = next.side,
                canPause = next.phase == PhoneOverlayPhase.RUNNING,
                canTakeOver = next.phase == PhoneOverlayPhase.RUNNING || next.phase == PhoneOverlayPhase.PAUSED,
                canEnd = next.phase == PhoneOverlayPhase.RUNNING ||
                    next.phase == PhoneOverlayPhase.PAUSED ||
                    next.phase == PhoneOverlayPhase.TAKEN_OVER ||
                    next.phase == PhoneOverlayPhase.NEEDS_CHECK,
                stopReason = next.stopReason,
                retained = next.retained,
                canContinueInChat = next.canContinueInChat,
                demo = demo,
            )
        current.setContent { content.Content(surface, nextActions) }
    }

    private fun removeWindow(current: View): Boolean {
        val manager = windowManager ?: return false
        if (runCatching { manager.removeViewImmediate(current) }.isFailure) return false
        view = null
        composeOwner?.destroy()
        composeOwner = null
        params = null
        onVisibilityChanged(false)
        return true
    }

    private fun touchFreshness() {
        synchronized(lock) {
            freshnessDeadlineMs = clock() + FRESHNESS_MS
        }
    }

    private fun geometry(next: PhoneOverlayWindowState): PhoneOverlayGeometry {
        val manager = windowManager ?: return PhoneOverlayGeometry(1, 1, 0, 0, 0, 1, 1, 1)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        manager.defaultDisplay.getRealMetrics(metrics)
        val density = metrics.density
        val insets = insets(manager)
        return PhoneOverlayGeometry(
            screenWidthPx = metrics.widthPixels,
            screenHeightPx = metrics.heightPixels,
            topInsetPx = insets.first,
            bottomInsetPx = insets.second,
            sideMarginPx = (16 * density).toInt(),
            collapsedHeightPx = (72 * density).toInt(),
            expandedHeightPx = (360 * density).toInt(),
            expandedWidthPx = (320 * density).toInt(),
        )
    }

    private fun insets(manager: WindowManager): Pair<Int, Int> =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            runCatching {
                val current = manager.currentWindowMetrics.windowInsets
                val bars: Insets = current.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                val ime: Insets = current.getInsets(WindowInsets.Type.ime())
                bars.top to maxOf(bars.bottom, ime.bottom)
            }.getOrDefault(0 to 0)
        } else {
            0 to 0
        }

    private fun checkMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "overlay window must be main thread" }
    }

    private class ComposeOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore = ViewModelStore()
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun start() {
            savedStateController.performRestore(null)
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    private companion object {
        const val FRESHNESS_MS = 45_000L
        const val RESTORE_TIMEOUT_MS = 1_000L

        /** A main-loop traversal normally runs within a frame; never wait longer than this. */
        const val MAX_HIDE_COMMIT_MS = 250L
    }
}
