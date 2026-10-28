package io.andchamber.app.phone.android.overlay

import android.content.Context
import android.os.SystemClock
import io.andchamber.app.phone.android.PhoneIntegration
import io.andchamber.app.notify.SessionPromptDeliveryState
import io.andchamber.app.notify.SessionPromptPort
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.core.PhoneRect
import io.andchamber.app.phone.overlay.safety.OverlayPhase
import io.andchamber.app.phone.overlay.safety.PhoneOverlayBarrier
import io.andchamber.app.ui.phone.PhoneControl
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneLastAction
import io.andchamber.app.ui.phone.PhonePending
import io.andchamber.app.ui.phone.PhoneScreenState
import io.andchamber.app.ui.phone.overlay.OverlayMode
import io.andchamber.app.ui.phone.overlay.OverlayPermission
import io.andchamber.app.ui.phone.overlay.OverlaySurfaceMode
import io.andchamber.app.ui.phone.overlay.OverlayTest
import io.andchamber.app.ui.phone.overlay.PhoneOverlaySettingsActions
import io.andchamber.app.ui.phone.overlay.PhoneOverlaySettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Native adapter between the phone controller and the overlay window.
 *
 * The manager owns every piece of display state (phase, expanded, end confirmation, side position)
 * and pushes it to the surface; the designer composable is a pure function of that value. The
 * coordinator never draws, never grants and never treats permission or a successful `addView` as
 * visibility. Stop requests carry an explicit native receipt: the overlay only says "taken over" or
 * "ended" after the controller's revoke is confirmed synchronously.
 */
internal class PhoneOverlayCoordinator(
    private val context: Context,
    private val settings: PhoneOverlaySettings,
    private val integration: PhoneIntegration,
    private val content: PhoneOverlayContentPort = PhoneOverlayContentPort.Unavailable,
    /** Native-only task progress for the overlay's phase/event projection. */
    private val taskProgress: StateFlow<PhoneTaskProgressView?> = MutableStateFlow(null),
    /** Memory-only notification question for the current native task; null in tests / when disabled. */
    private val conversation: SessionPromptPort? = null,
    /** The trusted local server launch; the question view and submit fence are bound to it. */
    private val currentLaunchId: () -> Long? = { null },
    /** Ends exactly the retained/live native task (key/epoch). A wrong owner must return false. */
    private val endTask: (PhoneTaskKey, Long) -> Boolean = { _, _ -> false },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val startDisplayService: () -> Unit = { PhoneOverlayDisplayService.start(context) },
    /** Request the display service to stop. Renamed so it cannot recurse into [requestStopService]. */
    private val stopServiceRequest: () -> Unit = { PhoneOverlayDisplayService.stop(context) },
) {
    private val geometryActive = MutableStateFlow(false)
    private val surface =
        PhoneOverlaySurfaceHost(
            context,
            content,
            onReadHideTimedOut = ::onReadHideTimedOut,
            onInsetsChanged = ::onSurfaceInsetsChanged,
            onVisibilityChanged = { geometryActive.value = it },
            clock = clock,
        )
    private val demoSurface = PhoneOverlaySurfaceHost(context, content, demo = true, clock = clock)

    private var state = PhoneOverlayWindowState(ownerKey = OWNER_RETAINED)
    private var demoWindow = PhoneOverlayWindowState(ownerKey = OWNER_DEMO, generation = 1L)
    private var shownState: PhoneScreenState? = null

    /** Thread-safe display-owner snapshot; read by the driver bridge off the main thread. */
    @Volatile
    private var driverOwnerSnapshot: String = "${state.ownerKey}:${state.generation}"

    private var nativeVisible = true
    private var displayServiceRequested = false
    private var serviceEpochOwned: Long? = null
    /** Owner minted by the current display-service instance; late callbacks for another owner are ignored. */
    private var displayOwner: String? = null
    private var lastAuthorityAtMs = 0L
    private var pendingStop: PhoneOverlayStopRequest? = null
    private var overlayLostWhileLeased = false
    /** A failed/unreachable surface is not re-attached by every 15s tick. */
    private var attachSuppressed = false
    /** What really happened to the overlay for the settings card; never a visibility claim. */
    private var lastSurfaceMode: OverlaySurfaceMode? = null
    private var lastStopReasonValue: PhoneOverlayStopReason? = null
    private var dragDx = 0f

    /** Frozen task owner the current question was last shown for; a change clears old selections. */
    private var conversationKey: PhoneTaskKey? = null
    private var conversationEpoch: Long? = null
    private var conversationRoute: String? = null
    /** The user's one submit whose final delivery may need the read-only error panel reopened. */
    private var promptSubmitWatch: PhonePromptSubmitWatch? = null

    /** Per-rotation user posture. Memory only; never a phase, owner, lease or summary. */
    private val positionMemory = PhoneOverlayPositionMemory()
    private var lastRotation: Int? = null

    private var demoTest: OverlayTest = OverlayTest.NotRun
    private var demoJob: Job? = null

    private val mutableSettings = MutableStateFlow(buildSettingsState())
    val settingsState: StateFlow<PhoneOverlaySettingsState> = mutableSettings.asStateFlow()

    init {
        PhoneOverlayHolder.install(this)
        scope.launch { integration.state.collect(::onPhoneState) }
        // A confirmed native stop (need_user/finish/end) is a task fact, not a screen-state guess:
        // project its typed phase immediately instead of replaying a stale screen state.
        scope.launch {
            taskProgress.collect(::onTaskProgressChanged)
        }
        // The question projection is memory-only and changes only on a local notification event or an
        // explicit refresh; collecting it is not a poll.
        conversation?.let { port ->
            scope.launch {
                port.projection.collect { refresh() }
            }
            scope.launch {
                port.notice.collect { refresh() }
            }
        }
        scope.launch {
            geometryActive.collectLatest { shown ->
                if (!shown) return@collectLatest
                while (isActive) {
                    delay(500L)
                    if (!nativeVisible && surface.isShown && !surface.isHiddenForRead) onSurfaceInsetsChanged()
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                if (surface.isShown && !surface.isHiddenForRead) {
                    // Only the manager's intent is refreshed here; visibility stays the driver's call.
                    surface.refreshFreshness()
                }
                if (nativeVisible) {
                    if (surface.isShown) surface.detach()
                } else {
                    refresh()
                }
                boundLifetime()
            }
        }
    }

    // --- settings card ---------------------------------------------------------------------

    fun settingsSnapshot(): PhoneOverlaySettingsSnapshot =
        PhoneOverlaySettingsSnapshot(
            requested = settings.mode(),
            effective = settings.effectiveMode(),
            gate = settings.gate(),
        )

    /** State for the designer settings card. Overlay stays off until a demo was really seen. */
    fun settingsStateNow(): PhoneOverlaySettingsState = buildSettingsState()

    fun settingsActions(): PhoneOverlaySettingsActions =
        PhoneOverlaySettingsActions(
            onOpenSystemSettings = ::openPermissionSettings,
            onRecheck = ::recheckPermission,
            onStartTest = { startOverlayDemo() },
            onConfirmSeen = ::confirmDemoSeen,
            onChooseMode = ::chooseMode,
        )

    /** Opens the system overlay-permission screen. The result is only a permission, not visibility. */
    fun openPermissionSettings() {
        runCatching { context.startActivity(settings.permissionIntent()) }
    }

    /**
     * Shows the marked demo capsule. It needs the draw-over grant and the designer content, and it is
     * refused while a live lease exists so a test can never be mistaken for real control.
     */
    fun startOverlayDemo(): Boolean {
        if (!content.available) return false
        if (settings.gate() != PhoneOverlayGate.READY) {
            demoTest = OverlayTest.NotRun
            publishSettings()
            return false
        }
        if (integration.liveLeaseEpoch() != null) return false
        demoJob?.cancel()
        demoSurface.detach()
        demoTest = OverlayTest.Showing
        publishSettings()
        val attached =
            demoSurface.attach(
                demoWindow.withPhase(PhoneOverlayPhase.RUNNING),
                demoActions(),
            )
        if (!attached) {
            demoTest = OverlayTest.NotSeen
            publishSettings()
            return false
        }
        demoJob =
            scope.launch {
                delay(DEMO_TIMEOUT_MS)
                if (demoTest == OverlayTest.Showing || demoTest == OverlayTest.TapReceived) {
                    demoTest = OverlayTest.TimedOut
                    demoSurface.detach()
                    publishSettings()
                }
            }
        return true
    }

    /**
     * The person's answer after tapping the demo. A confirmation without the tap (`Showing`) is
     * refused, so the mode cannot be unlocked by pressing "I saw it" alone.
     */
    fun confirmDemoSeen(seen: Boolean): Boolean {
        if (!demoConfirmationAllowed(demoTest == OverlayTest.TapReceived)) return false
        demoJob?.cancel()
        demoSurface.detach()
        demoTest = if (seen) OverlayTest.Seen else OverlayTest.NotSeen
        publishSettings()
        refresh()
        return true
    }

    private fun demoActions(): PhoneOverlaySurfaceActions =
        PhoneOverlaySurfaceActions(
            onTakeOver = {
                if (demoTest == OverlayTest.Showing) {
                    demoTest = OverlayTest.TapReceived
                    publishSettings()
                }
            },
            onCloseOverlay = { closeDemo(if (demoTest == OverlayTest.Showing) OverlayTest.NotSeen else demoTest) },
            onDragBy = { dx, dy -> demoDrag(dx, dy) },
            onDragEnd = { demoDragEnd() },
        )

    private fun demoDrag(dx: Float, dy: Float) {
        dragDx += dx
        val geometry = demoSurface.geometryNow() ?: return
        demoWindow = overlayReflow(demoWindow.copy(offsetY = demoWindow.offsetY + dy.toInt()), geometry)
        demoSurface.update(demoWindow, demoActions())
    }

    private fun demoDragEnd() {
        val geometry = demoSurface.geometryNow() ?: return
        val syntheticX =
            if (demoWindow.side == PhoneOverlaySide.LEFT) {
                geometry.sideMarginPx + dragDx
            } else {
                geometry.screenWidthPx - geometry.sideMarginPx + dragDx
            }
        demoWindow =
            overlayDragRelease(
                demoWindow.copy(phase = PhoneOverlayPhase.RUNNING),
                syntheticX,
                (demoWindow.offsetY + geometry.collapsedHeightPx).toFloat(),
                geometry,
            ).copy(phase = PhoneOverlayPhase.RUNNING, endConfirm = false) // the demo cannot end anything
        dragDx = 0f
        demoSurface.update(demoWindow, demoActions())
    }

    private fun closeDemo(test: OverlayTest) {
        demoJob?.cancel()
        demoSurface.detach()
        if (demoTest != OverlayTest.Seen) demoTest = test
        publishSettings()
    }

    private fun chooseMode(mode: OverlayMode) {
        selectMode(
            if (mode == OverlayMode.OverlayAndNotification) {
                PhoneOverlayMode.OVERLAY
            } else {
                PhoneOverlayMode.NOTIFICATION_ONLY
            },
        )
    }

    fun selectMode(mode: PhoneOverlayMode) {
        val target =
            if (overlayModeSelectionAllowed(mode, settings.gate(), demoTest == OverlayTest.Seen)) {
                mode
            } else {
                PhoneOverlayMode.NOTIFICATION_ONLY
            }
        if (overlayModeChangeRequiresStop(settings.mode(), target, integration.liveLeaseEpoch() != null)) {
            // Leaving Overlay while a lease is live is a native stop, not a silent detach fallback.
            overlayLostWhileLeased = false
            stopForModeChange()
            if (integration.liveLeaseEpoch() != null) {
                // The revoke did not take effect. Do not persist notification-only and silently
                // continue without the selected overlay stop entry.
                publishSettings()
                refresh()
                return
            }
        }
        settings.setMode(target)
        attachSuppressed = false
        overlayLostWhileLeased = false
        lastSurfaceMode =
            if (target == PhoneOverlayMode.OVERLAY) lastSurfaceMode else OverlaySurfaceMode.NotificationOnly
        publishSettings()
        refresh()
    }

    private fun recheckPermission() {
        if (demoTest == OverlayTest.Seen && settings.gate() != PhoneOverlayGate.READY) {
            demoTest = OverlayTest.NotSeen
        }
        // The person explicitly revisited the permission: one fresh bounded attach is allowed again.
        attachSuppressed = false
        publishSettings()
        refresh()
    }

    // --- lifecycle -------------------------------------------------------------------------

    fun onHostVisible() {
        nativeVisible = true
        // Only the real control surface closes. The demo capsule was started by the person from this
        // screen and stays until they finish it; the collector must not close it.
        surface.detach()
        publishSettings()
    }

    fun onHostHidden() {
        nativeVisible = false
        refresh()
    }

    /** Rotation or an inset change: re-clamp the window and bump the revision for old observations. */
    fun onConfigurationChanged(owner: String) {
        // Layout only, and only for the current service instance + task owner. A late callback from an
        // older notification/service is a no-op; it never revives a phase or a lease.
        if (!overlayConfigurationOwnerAllowed(owner, displayOwner)) return
        if (!PhoneOverlayDisplayOwner.hasCurrentNonce(owner)) return
        val rotation = currentRotation()
        val previous = lastRotation
        if (previous != null && previous != rotation) {
            // Save the old orientation's posture before restoring the new one.
            positionMemory.remember(previous, state.side, state.offsetY)
        }
        lastRotation = rotation
        val remembered = positionMemory.forRotation(rotation)
        // Only geometry is restored. The phase/owner/summary stay the current native truth, so a
        // takeover or a new task is never rewound to an old "Running".
        val base = if (remembered != null) state.withDrag(remembered.side, remembered.offsetY) else state
        val geometry = surface.geometryNow() ?: return
        applyState(overlayReflow(base, geometry))
        refresh()
    }

    private fun rememberPosition() {
        positionMemory.remember(currentRotation(), state.side, state.offsetY)
    }

    private fun onSurfaceInsetsChanged() {
        if (nativeVisible || surface.isHiddenForRead) return
        val reflowed = surface.reflow() ?: return
        if (reflowed != state) applyState(overlayGeometryUpdate(state, reflowed))
    }

    private fun currentRotation(): Int =
        runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService(android.view.WindowManager::class.java)?.defaultDisplay?.rotation ?: 0
        }.getOrDefault(0)

    /** Opaque task-display owner used by the display service notification identity. No summary. */
    fun displayTaskOwner(): String = "${state.ownerKey}:${state.generation}"

    /**
     * Driver-only read-only mode gate. True while the person selected Overlay (even if its surface
     * was lost: the driver must refuse, not fall back) or this manager still owns a surface. False
     * only for an explicitly notification-only/off mode with no own surface, preserving the original
     * no-overlay driver path. Never derived from model input.
     */
    internal fun driverOverlayRequired(): Boolean =
        settings.mode() == PhoneOverlayMode.OVERLAY || surface.isShown ||
            surface.isHiddenForRead || demoSurface.isShown

    /** Driver-only read-only barrier: exactly this manager surface, never a second window. */
    internal fun driverOverlayBarrier(): PhoneOverlayBarrier = surface

    /** Thread-safe display-owner snapshot for an epoch-bounded safety callback. */
    internal fun driverDisplayOwner(): String = driverOwnerSnapshot

    /** Live controller epoch for the driver's operation guard; thread-safe read. */
    internal fun driverAuthorityEpoch(): Long? = integration.liveLeaseEpoch()

    /**
     * Bounded Main-thread wait for this epoch's manager surface before the driver's first read.
     *
     * Notification-only needs no overlay and returns true. For a selected overlay this only waits
     * until the manager has attached exactly this epoch's window and its barrier reports VISIBLE; it
     * never proves visibility, grants, resumes or extends a lease. A changed epoch/owner returns
     * false immediately, and so does the timeout.
     */
    internal suspend fun awaitDriverSurface(epoch: Long): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (settings.mode() != PhoneOverlayMode.OVERLAY) return@withContext true
            val startedAtMs = clock()
            var elapsedMs = 0L
            while (elapsedMs < SURFACE_WAIT_TIMEOUT_MS) {
                val gate =
                    overlayDriverSurfaceGate(
                        overlaySelected = true,
                        epoch = epoch,
                        liveEpoch = integration.liveLeaseEpoch(),
                        ownerKey = state.ownerKey,
                        hostVisible = nativeVisible,
                        surfaceShown = surface.isShown,
                        hiddenForRead = surface.isHiddenForRead,
                        phase = surface.expectation(clock()).phase,
                    )
                when (gate) {
                    OverlayDriverSurfaceGate.NOT_REQUIRED,
                    OverlayDriverSurfaceGate.READY,
                    -> return@withContext true

                    OverlayDriverSurfaceGate.STALE -> return@withContext false
                    OverlayDriverSurfaceGate.WAITING -> Unit
                }
                delay(minOf(SURFACE_WAIT_POLL_MS, SURFACE_WAIT_TIMEOUT_MS - elapsedMs))
                elapsedMs = clock() - startedAtMs
            }
            false
        }

    /**
     * Driver-only occlusion escape for the current live task capsule.
     *
     * It only relayouts this manager's own window: no grant, renewal, resume, stop or revoke, and no
     * action on behalf of the agent. It runs on Main and only for the exact current live lease owner;
     * a demo capsule, a notification-only mode, a missing/hidden surface or a stale owner returns
     * false without touching anything. An expanded panel is collapsed, then the layout is chosen by
     * [overlayMakeWayPlacement] and applied through the normal surface update path. `true` is
     * returned only after the first frame of that layout really arrived.
     *
     * The automatic move is deliberately not stored in [positionMemory]: it is an occlusion escape,
     * not the person's posture, so their own remembered drag position still wins for the next task.
     */
    internal suspend fun driverMakeWay(
        avoid: List<PhoneRect>,
        currentFrame: PhoneRect,
        deadlineMs: Long,
    ): Boolean = withContext(Dispatchers.Main.immediate) {
        if (avoid.isEmpty()) return@withContext false
        val liveEpoch = integration.liveLeaseEpoch() ?: return@withContext false
        if (settings.mode() != PhoneOverlayMode.OVERLAY) return@withContext false
        if (state.ownerKey != grantOwner(liveEpoch)) return@withContext false
        if (nativeVisible || !surface.isShown || surface.isHiddenForRead) return@withContext false
        val geometry = surface.geometryNow() ?: return@withContext false
        if (deadlineMs - clock() <= 0L) return@withContext false
        val collapsed = if (state.expanded) state.withExpanded(false) else state
        val placement = overlayPlacement(collapsed, geometry)
        val choice =
            overlayMakeWayPlacement(
                currentSide = state.side,
                currentTopPx = placement.topPx,
                frameWidthPx = currentFrame.width,
                // An expanded panel collapses first; the collapsed capsule is never taller.
                frameHeightPx = placement.heightPx,
                geometry = geometry,
                avoid = avoid,
            ) ?: return@withContext false
        val moved = collapsed.withDrag(choice.side, choice.offsetY)
        if (moved == state) return@withContext false
        applyState(moved)
        val remaining = deadlineMs - clock()
        if (remaining <= 0L) return@withContext false
        withTimeoutOrNull(remaining) { surface.awaitFrame() } ?: false
    }

    /**
     * Known overlay loss reported by the driver bridge after it froze and re-checked the same
     * epoch/display owner on the main thread. This is a visibility failure, not a person's
     * take-over: it stops the exact lease as `ScreenUncertain`, keeps the task/result and tries one
     * bounded re-show of the already-stopped state.
     */
    internal fun onDriverOverlayLost(): Boolean =
        handleOverlayFailure(
            PhoneOverlayFailure.VISIBILITY_UNCONFIRMED,
            tryShowStopped = true,
        )

    fun onDisplayServiceStarted(owner: String) {
        displayOwner = owner
        displayServiceRequested = true
        serviceEpochOwned = integration.liveLeaseEpoch()
        if (attachSuppressed &&
            lastStopReasonValue == PhoneOverlayStopReason.OverlayUnreachable &&
            lastSurfaceMode == null &&
            hasVerifiedNotificationFallback()
        ) {
            // A display-service owner exists and the stop channel is verified: the recovery entry is
            // available. This is not evidence that the person can see the notification.
            lastSurfaceMode = OverlaySurfaceMode.MovedToNotification
        }
        publishSettings()
        refresh()
    }

    fun onDisplayServiceDestroyed(owner: String) {
        // A late onDestroy from an older service instance must not touch a newer display owner.
        if (!PhoneOverlayDisplayOwner.matches(owner, displayOwner)) return
        displayOwner = null
        displayServiceRequested = false
        serviceEpochOwned = null
        surface.detach()
        if (integration.liveLeaseEpoch() != null) {
            // Unexpected loss of the only guaranteed stop entry: stop the live lease, keep the task.
            handleOverlayFailure(PhoneOverlayFailure.DISPLAY_SERVICE_LOST, tryShowStopped = true)
        }
        publishSettings()
    }

    /** The foreground notification could not be shown: no stop entry, so a live lease fails closed. */
    fun onDisplayPermissionLost(owner: String) {
        if (!PhoneOverlayDisplayOwner.matches(owner, displayOwner)) return
        if (!PhoneOverlayDisplayOwner.hasCurrentNonce(owner)) return
        if (integration.liveLeaseEpoch() != null) {
            handleOverlayFailure(PhoneOverlayFailure.NOTIFICATION_LOST, tryShowStopped = true)
        }
        requestStopService()
    }

    /**
     * The notification's close, for exactly this service owner. Only a genuinely closed overlay returns
     * true so the service may stop itself; a live task is refused and turns into the end confirmation.
     */
    fun onCloseOverlay(owner: String): Boolean {
        if (!PhoneOverlayDisplayOwner.matches(owner, displayOwner)) return false
        if (!PhoneOverlayDisplayOwner.hasCurrentNonce(owner)) return false
        if (PhoneOverlayDisplayOwner.taskOwner(owner) != displayTaskOwner()) return false
        return closeIfEnded()
    }

    /** Closing is only allowed once the task ended and no lease is live; a live task must not vanish. */
    fun onCloseOverlay(): Boolean {
        val closed = closeIfEnded()
        if (closed) requestStopService()
        return closed
    }

    private fun closeIfEnded(): Boolean {
        if (!overlayCloseAllowed(hasLease = integration.liveLeaseEpoch() != null, phase = state.phase)) {
            // A live task cannot be silently hidden: offer the end confirmation and keep the surface.
            if (integration.liveLeaseEpoch() != null) applyState(state.withEndConfirm(confirming = true))
            return false
        }
        demoJob?.cancel()
        demoSurface.detach()
        surface.detach()
        state = state.withPhase(PhoneOverlayPhase.ENDED).withStopFacts(null, false, false)
        publishSettings()
        return true
    }

    // --- stop requests ---------------------------------------------------------------------

    fun onTakeOver(): Boolean {
        if (pendingStop != null) return false
        if (state.phase == PhoneOverlayPhase.TAKEN_OVER) return true
        pendingStop = PhoneOverlayStopRequest.TAKE_OVER
        applyState(state.withPhase(PhoneOverlayPhase.STOP_PENDING))
        val stopped = integration.onOverlayTakeOver()
        val receipt = stopped && integration.liveLeaseEpoch() == null
        pendingStop = null
        applyState(state.withPhase(if (receipt) PhoneOverlayPhase.TAKEN_OVER else PhoneOverlayPhase.UNKNOWN))
        if (receipt) lastAuthorityAtMs = clock()
        publishSettings()
        refresh()
        return receipt
    }

    fun onEndConfirmed(): Boolean {
        if (pendingStop != null) return false
        if (!state.endConfirm) return false
        // End only the exact native task/epoch shown here. A person may end a retained task whose
        // lease was already stopped (takeover/need_user/unknown); no grant is re-created. Without a
        // current task there is nothing to end, so the request fails closed.
        val retained = taskProgress.value?.snapshot ?: return false
        pendingStop = PhoneOverlayStopRequest.END_CONFIRMED
        applyState(state.withPhase(PhoneOverlayPhase.STOP_PENDING).withEndConfirm(confirming = false))
        val ended = runCatching { endTask(retained.key, retained.grantEpoch) }.getOrDefault(false)
        pendingStop = null
        applyState(
            state.withPhase(if (ended) PhoneOverlayPhase.NEEDS_CHECK else PhoneOverlayPhase.UNKNOWN)
                .withStopFacts(if (ended) null else state.stopReason, false, false),
        )
        if (ended) lastAuthorityAtMs = clock()
        publishSettings()
        refresh()
        return ended
    }

    /** Return to AndChamber: revoke first, keep the overlay reachable until Main is really resumed. */
    fun onReturnToNative(): Boolean = launchEntry(PhoneOverlayEntry.ReturnToNative)

    /** Open the chat for the current/retained task. Never revokes, resumes or extends a lease. */
    fun onOpenChat(): Boolean {
        if (!overlayEntryRevokesLease(PhoneOverlayEntry.OpenChat)) {
            applyState(state.withExpanded(false))
        }
        return launchEntry(PhoneOverlayEntry.OpenChat)
    }

    /** Open the native "continue beside the chat" confirmation; it never resumes from here. */
    fun onContinueInChat(): Boolean = launchEntry(PhoneOverlayEntry.ContinueInChat)

    private fun launchEntry(entry: PhoneOverlayEntry): Boolean {
        if (overlayEntryRevokesLease(entry) && integration.liveLeaseEpoch() != null) onTakeOver()
        val intent =
            when (entry) {
                PhoneOverlayEntry.OpenChat ->
                    PhoneOverlayDisplayService.openChatIntent(
                        context,
                        integration.currentTaskSessionId(),
                    )

                PhoneOverlayEntry.ContinueInChat,
                PhoneOverlayEntry.ReturnToNative,
                -> PhoneOverlayDisplayService.openNativeIntent(context)
            }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    /**
     * Native task progress changed (a confirmed stop, or a native result). Only the typed phase for
     * the current task is applied here; the label/summary and live-lease facts still come from the
     * screen projection. A closed result and a newer live lease are left alone.
     */
    private fun onTaskProgressChanged(view: PhoneTaskProgressView?) {
        val snapshot = view?.snapshot ?: return
        if (shownState == null) return
        // A route claim or a new task/epoch is a conversation-owner change even while the task has no
        // phase projection (ACTIVE). Refresh it before any phase filtering so a question is never
        // hidden behind a same-phase emission, and a new epoch never keeps an old selection.
        if (snapshot.key != conversationKey ||
            snapshot.grantEpoch != conversationEpoch ||
            snapshot.routeSession != conversationRoute
        ) {
            refresh()
        }
        val liveEpoch = integration.liveLeaseEpoch()
        if (!overlayTaskSnapshotBelongsToDisplay(snapshot.grantEpoch, liveEpoch, state.ownerKey)) {
            return
        }
        val phase = overlayTaskPhase(snapshot.state) ?: return
        // A live unknown is projected immediately: only mutations are blocked, observation may still
        // be authorized. Waiting/ended results require the lease to be gone.
        if (liveEpoch != null && phase != PhoneOverlayPhase.UNKNOWN) return
        // A closed result card is not reopened by a late unknown/progress emission.
        if (liveEpoch == null && state.phase == PhoneOverlayPhase.ENDED) return
        if (state.phase == phase) return
        state =
            state
                .withPhase(phase)
                .withStopFacts(
                    state.stopReason,
                    overlayTaskIsRetained(snapshot.state) && phase != PhoneOverlayPhase.ENDED,
                    state.canContinueInChat,
                )
        lastAuthorityAtMs = clock()
        publishSettings()
        refresh()
    }

    // --- state plumbing --------------------------------------------------------------------

    private fun onPhoneState(screen: PhoneScreenState) {
        shownState = screen
        val liveEpoch = integration.liveLeaseEpoch()
        if (liveEpoch != null) {
            if (demoSurface.isShown) closeDemo(OverlayTest.NotRun)
            val owner = "grant-$liveEpoch"
            if (state.ownerKey != owner) {
                state = state.withNewOwner(owner)
                positionMemory.forRotation(currentRotation())?.let { position ->
                    state = state.withDrag(position.side, position.offsetY)
                }
                state = overlayReflow(state, surface.geometryFor(state))
                driverOwnerSnapshot = "${state.ownerKey}:${state.generation}"
                overlayLostWhileLeased = false
                // A real new authority gets one fresh bounded attach after an earlier unreachable one.
                attachSuppressed = false
                // The previous task's fallback/reason no longer describes this one.
                lastStopReasonValue = null
                lastSurfaceMode = null
            }
        }
        val lease =
            (screen.control as? PhoneControl.Agent)?.lease
                ?: (screen.control as? PhoneControl.Halted)?.lease
        val label = lease?.target?.label
        val summary = lease?.summary
        val leasePresentation =
            overlayLeasePresentation(lease?.expiresAtMs?.takeIf { screen.control is PhoneControl.Agent })
        val halted = screen.control as? PhoneControl.Halted
        val explicitNeedUser =
            liveEpoch == null &&
                halted != null &&
                halted.reason == PhoneHaltReason.NeedUser &&
                halted.confirmed &&
                halted.lastAction != PhoneLastAction.MayStillApply
        // A task-state fact only speaks for its own epoch: a stale waiting/ended projection must not
        // label a newer grant, and a resume after a stop is a new live epoch of the same task.
        val taskState =
            taskProgress.value?.snapshot
                ?.takeIf {
                    overlayTaskSnapshotBelongsToDisplay(
                        snapshotGrantEpoch = it.grantEpoch,
                        liveEpoch = liveEpoch,
                        ownerKey = state.ownerKey,
                    )
                }
                ?.state
        val derived =
            overlayPhaseFor(
                agentLive = screen.control is PhoneControl.Agent,
                pausing = screen.pending == PhonePending.Pausing,
                paused = halted?.reason == PhoneHaltReason.Paused,
                pausedConfirmed = halted?.confirmed == true,
                takenOver = halted?.reason == PhoneHaltReason.TakenOver,
                taskState = taskState,
                unknownOutcome = halted?.lastAction == PhoneLastAction.MayStillApply,
                // A result the person closed (or the bounded lifetime ended) stays closed while no new
                // lease is live; a late progress emission must not reopen it.
                closed = state.phase == PhoneOverlayPhase.ENDED && liveEpoch == null,
                explicitNeedUser = explicitNeedUser,
            )
        val nextPhase =
            derived ?: when (state.phase) {
                // A live-looking phase whose native lease disappeared is uncertain, not "running".
                PhoneOverlayPhase.RUNNING, PhoneOverlayPhase.PAUSING, PhoneOverlayPhase.PAUSED ->
                    PhoneOverlayPhase.UNKNOWN
                else -> state.phase
            }
        val previousPhase = state.phase
        state = state.withPhase(nextPhase, label, summary, leasePresentation.leaseEndsAtMs)
            .withLeasePermanent(leasePresentation.leasePermanent)
        val mappedStopReason = overlayStopReasonFor(halted?.reason, taskState)
        // A failure's explanation survives a state emission that has no halt fact of its own; only a
        // new live authority (handled above) clears it.
        val stopReason =
            if (mappedStopReason == null &&
                liveEpoch == null &&
                state.stopReason == PhoneOverlayStopReason.OverlayUnreachable &&
                (nextPhase == PhoneOverlayPhase.UNKNOWN || nextPhase == PhoneOverlayPhase.TAKEN_OVER)
            ) {
                PhoneOverlayStopReason.OverlayUnreachable
            } else {
                mappedStopReason
            }
        val retained =
            liveEpoch == null &&
                overlayTaskIsRetained(taskState) &&
                nextPhase != PhoneOverlayPhase.IDLE &&
                nextPhase != PhoneOverlayPhase.ENDED &&
                nextPhase != PhoneOverlayPhase.NEEDS_CHECK
        val continueInChat =
            liveEpoch == null && overlayCanContinueInChat(screen, nextPhase)
        state = state.withStopFacts(stopReason, retained, continueInChat)
        if (stopReason != null) lastStopReasonValue = stopReason
        if (nextPhase == PhoneOverlayPhase.RUNNING ||
            nextPhase == PhoneOverlayPhase.PAUSING ||
            nextPhase == PhoneOverlayPhase.PAUSED
        ) {
            lastAuthorityAtMs = clock()
        } else if (nextPhase != previousPhase &&
            (nextPhase == PhoneOverlayPhase.WAITING_USER || nextPhase == PhoneOverlayPhase.NEEDS_CHECK)
        ) {
            // A confirmed native stop starts the bounded retention for its result; later emissions of
            // the same phase must not extend it forever.
            lastAuthorityAtMs = clock()
        }
        refresh()
    }

    private fun refresh() {
        syncConversation()
        publishSettings()
        val hasLease = integration.liveLeaseEpoch() != null
        val mode = settings.mode()
        val gate = settings.gate()
        val contentAvailable = content.available
        if (overlayLeaseMustFailClosed(hasLease, mode, contentAvailable, gate)) {
            // Selected Overlay but permission/content/window lost. This is not a take-over: stop the
            // exact lease as a StopChannelLost/ScreenUncertain failure and keep its real reason.
            val failure =
                if (gate != PhoneOverlayGate.READY) {
                    PhoneOverlayFailure.PERMISSION_REVOKED
                } else {
                    PhoneOverlayFailure.VISIBILITY_UNCONFIRMED
                }
            handleOverlayFailure(failure, tryShowStopped = false)
            return
        }
        // A driver read owns the window. Keep the host's authority/control state current (Pause,
        // need_user, takeover, a new owner) but never reattach while the read is running.
        if (surface.isHiddenForRead) {
            if (overlayMayAttach(contentAvailable, mode, gate)) {
                surface.update(state, liveActions())
            } else {
                // Notification-only/off was selected: a old read must not resurrect the overlay.
                surface.detach()
            }
            return
        }
        if (nativeVisible) {
            if (surface.isShown) surface.detach()
            stopServiceIfIdle()
            return
        }
        if (!overlayMayAttach(contentAvailable, mode, gate)) {
            if (surface.isShown) surface.detach()
            stopServiceIfIdle()
            return
        }
        if (attachSuppressed) {
            // An earlier failure already tried the bounded stopped display. The tick loop must not
            // hammer addView; the display-service notification/native recovery stays available.
            if (surface.isShown) surface.detach()
            stopServiceIfIdle()
            return
        }
        val window = when (state.phase) {
            PhoneOverlayPhase.IDLE, PhoneOverlayPhase.ENDED -> null
            else -> state
        }
        if (window == null) {
            if (surface.isShown) surface.detach()
            stopServiceIfIdle()
            return
        }
        // A live display service minted its notification owner for an older task. Without a lease it
        // can be replaced safely, so a stale close action never points at this task.
        if (displayServiceRequested &&
            PhoneOverlayDisplayOwner.taskOwner(displayOwner) != displayTaskOwner() &&
            integration.liveLeaseEpoch() == null
        ) {
            requestStopService()
        }
        ensureDisplayService()
        val attached =
            if (surface.isShown) {
                surface.update(window, liveActions())
                true
            } else {
                surface.attach(window, liveActions())
            }
        if (attached) {
            if (mode == PhoneOverlayMode.OVERLAY) lastSurfaceMode = OverlaySurfaceMode.Overlay
            return
        }
        if (hasLease) {
            // Requesting the window failed: stop the exact lease, never claim a take-over.
            handleOverlayFailure(PhoneOverlayFailure.ATTACH_FAILED, tryShowStopped = false)
        } else {
            recordUnreachable(overlayTaskIsRetained(currentTaskSnapshot()?.state))
        }
    }

    /**
     * A read-hide restore exceeded its Main scheduling budget. The overlay is genuinely not shown
     * and the ticket is uncertain, not "stopped" or rolled back. Settle only the current owner:
     * stop a still-live native lease as a visibility failure and try one bounded re-show of the
     * already-stopped state. A timeout from a previous owner never touches a newer one.
     */
    private fun onReadHideTimedOut(lease: PhoneOverlayReadLease) {
        val liveEpoch = integration.liveLeaseEpoch()
        if (!overlayReadTimeoutStillOwns(lease, liveEpoch, state.ownerKey, state.generation)) {
            if (liveEpoch != null && state.ownerKey != "grant-$liveEpoch") {
                // A newer lease exists but onPhoneState has not moved the display owner yet. Do not
                // re-show the old state as its stop entry; that refresh will happen for the new owner.
                surface.detach()
                return
            }
            // The old read timed out against an owner that is no longer current. Refresh the current
            // authority; never touch the newer lease/task.
            refresh()
            return
        }
        handleOverlayFailure(PhoneOverlayFailure.READ_RESTORE_TIMEOUT, tryShowStopped = true)
    }

    /**
     * The one failure path. It first stops the exact lease through the native failure API (never the
     * take-over API), keeps the real halt reason and the task context, then either shows one bounded
     * stopped card or records an honest notification fallback. The settings card always says why.
     */
    private fun handleOverlayFailure(
        failure: PhoneOverlayFailure,
        tryShowStopped: Boolean,
    ): Boolean {
        if (overlayLostWhileLeased) return false
        overlayLostWhileLeased = true
        // Stop new native actions first. The take-over API is deliberately not used here.
        val hadLiveLease = integration.liveLeaseEpoch() != null
        val stopped = integration.onOverlayFailure(phoneHaltReasonFor(failure))
        surface.detach()
        val taskState = currentTaskSnapshot()?.state
        val retainedTask = overlayTaskIsRetained(taskState)
        val receipt = hadLiveLease && stopped && integration.liveLeaseEpoch() == null
        val nextPhase = overlayFailurePhase(state.phase, retainedTask, receipt, hadLiveLease)
        val canContinue =
            (!hadLiveLease || receipt) && overlayCanContinueInChat(shownState, nextPhase)
        state =
            state
                .withPhase(nextPhase)
                .withStopFacts(
                    PhoneOverlayStopReason.OverlayUnreachable,
                    retainedTask && nextPhase != PhoneOverlayPhase.ENDED,
                    canContinue,
                )
        lastStopReasonValue = PhoneOverlayStopReason.OverlayUnreachable
        lastAuthorityAtMs = clock()
        if (tryShowStopped &&
            overlayFailureMayShowStopped(failure) &&
            mayAttachNow() &&
            state.phase != PhoneOverlayPhase.IDLE &&
            state.phase != PhoneOverlayPhase.ENDED
        ) {
            val geometry = surface.geometryFor(state)
            val reflowed = overlayReflow(state, geometry)
            if (surface.attach(reflowed, liveActions())) {
                state = reflowed
                attachSuppressed = false
                lastSurfaceMode = OverlaySurfaceMode.Overlay
                publishSettings()
                return receipt
            }
        }
        recordUnreachable(retainedTask, failure, receipt, hadLiveLease)
        return receipt
    }

    /** No visible stopped card was possible: keep the real notification/native fallback, honestly. */
    private fun recordUnreachable(
        retainedTask: Boolean,
        failure: PhoneOverlayFailure = PhoneOverlayFailure.ATTACH_FAILED,
        stopReceipt: Boolean = false,
        hadLiveLease: Boolean = false,
    ) {
        surface.detach()
        attachSuppressed = true
        val nextPhase = overlayFailurePhase(state.phase, retainedTask, stopReceipt, hadLiveLease)
        val canContinue =
            (!hadLiveLease || stopReceipt) && overlayCanContinueInChat(shownState, nextPhase)
        state =
            state
                .withPhase(nextPhase)
                .withStopFacts(
                    PhoneOverlayStopReason.OverlayUnreachable,
                    retainedTask && nextPhase != PhoneOverlayPhase.ENDED,
                    canContinue,
                )
        lastStopReasonValue = PhoneOverlayStopReason.OverlayUnreachable
        lastSurfaceMode =
            overlaySurfaceAfterFailure(
                failure = failure,
                notificationLive = hasVerifiedNotificationFallback(),
            )
        lastAuthorityAtMs = clock()
        publishSettings()
    }

    /**
     * A recovery entry really exists: this display-service owner plus a verified notification stop
     * channel. `startForeground`/submission alone is not evidence and never claims visibility.
     */
    private fun hasVerifiedNotificationFallback(): Boolean =
        verifiedNotificationFallback(
            displayOwner = displayOwner,
            stopChannel = integration.state.value.stopChannel,
        )

    private fun mayAttachNow(): Boolean =
        overlayMayAttach(content.available, settings.mode(), settings.gate())

    private fun currentTaskSnapshot(): io.andchamber.app.phone.overlay.progress.PhoneTaskSnapshot? {
        val snapshot = taskProgress.value?.snapshot ?: return null
        val liveEpoch = integration.liveLeaseEpoch()
        return snapshot.takeIf {
            overlayTaskSnapshotBelongsToDisplay(it.grantEpoch, liveEpoch, state.ownerKey)
        }
    }

    /**
     * Binds the memory-only notification question to the current native task. A new task key or
     * grant epoch clears any old selection; the same session/launch is revalidated and gets a fresh
     * generation, so a previous answer can never submit against the new owner.
     */
    private fun syncConversation() {
        val port = conversation ?: return
        val snapshot = currentTaskSnapshot()
        val key = snapshot?.key
        val epoch = snapshot?.grantEpoch
        val step = conversationReconcile(conversationKey, conversationEpoch, key, epoch, snapshot?.routeSession)
        if (step.invalidate) {
            conversationKey = key
            conversationEpoch = epoch
            port.invalidate(step.keepSession)
            if (step.refresh) port.refresh()
        }
        conversationRoute = snapshot?.routeSession
        state =
            state
                .withSessionPrompt(phoneOverlayPromptView(port.projection.value, snapshot, currentLaunchId()))
                .withSessionNotice(phoneOverlayNoticeView(port.notice.value, snapshot, currentLaunchId()))
        settlePromptSubmitWatch()
    }

    /**
     * After the user's one submit collapsed the panel, reopen its read-only error/recovery entry only
     * for the same task/epoch/launch and the same form/generation. A new owner or question never
     * reopens the old panel; Accepted (or Gone/Unsupported) stays collapsed.
     */
    private fun settlePromptSubmitWatch() {
        val watch = promptSubmitWatch ?: return
        val port = conversation ?: return
        val projection = port.projection.value
        val sameOwner =
            phoneTaskConversationStillOwns(
                owner = watch.owner,
                snapshot = currentTaskSnapshot(),
                launchId = currentLaunchId(),
                projection = projection,
            )
        val sameAnswer = projection.formId == watch.formId && projection.generation == watch.generation
        if (!sameOwner || !sameAnswer) {
            promptSubmitWatch = null
            return
        }
        if (promptSubmitShouldExpand(watch, sameOwner, sameAnswer, projection.deliveryState)) {
            promptSubmitWatch = null
            if (!state.expanded) applyState(state.withExpanded(true))
            return
        }
        when (projection.deliveryState) {
            SessionPromptDeliveryState.Checking,
            SessionPromptDeliveryState.Ready,
            SessionPromptDeliveryState.Sending,
            -> Unit
            SessionPromptDeliveryState.Idle,
            SessionPromptDeliveryState.Accepted,
            SessionPromptDeliveryState.Unknown,
            SessionPromptDeliveryState.Blocked,
            SessionPromptDeliveryState.Refused,
            SessionPromptDeliveryState.Gone,
            SessionPromptDeliveryState.Unsupported,
            SessionPromptDeliveryState.Superseded,
            -> promptSubmitWatch = null
        }
    }

    /** Freezes the current native task/key/epoch/route/launch; null means no question may be answered. */
    private fun conversationOwner(): PhoneTaskConversationOwner? {
        val snapshot = currentTaskSnapshot() ?: return null
        val routeSession = snapshot.routeSession ?: return null
        val launch = currentLaunchId() ?: return null
        return PhoneTaskConversationOwner(
            taskId = snapshot.key.taskId,
            runtimeId = snapshot.key.runtimeId,
            grantEpoch = snapshot.grantEpoch,
            routeSession = routeSession,
            launchId = launch,
        )
    }

    private fun answerPrompt(
        formId: String,
        generation: Long,
        value: String,
    ) {
        val port = conversation ?: return
        val owner = conversationOwner() ?: return
        val submitted =
            port.submit(formId, generation, value) {
                phoneTaskConversationStillOwns(
                    owner = owner,
                    snapshot = currentTaskSnapshot(),
                    launchId = currentLaunchId(),
                    projection = port.projection.value,
                )
            }
        val watch = promptSubmitWatchFor(submitted, owner, formId, generation) ?: return
        promptSubmitWatch = watch
        // The question is answered once by a real user tap. Collapse now, while the model is still
        // blocked on the question, so the driver cannot read its own overlay covering the target.
        // The IO pending check and HTTP reply continue independently.
        applyState(state.withExpanded(false))
    }

    private fun refreshPrompt() {
        conversation?.refresh()
    }

    /** Deliberate native mode change: a person's stop path, not a visibility failure. */
    private fun stopForModeChange() {
        if (overlayLostWhileLeased) return
        overlayLostWhileLeased = true
        surface.detach()
        val stopped = integration.onOverlayTakeOver()
        val receipt = stopped && integration.liveLeaseEpoch() == null
        state = state.withPhase(if (receipt) PhoneOverlayPhase.TAKEN_OVER else PhoneOverlayPhase.UNKNOWN)
        lastAuthorityAtMs = clock()
        publishSettings()
        stopServiceIfIdle()
    }

    private fun liveActions(): PhoneOverlaySurfaceActions =
        PhoneOverlaySurfaceActions(
            onExpand = {
                if (state.sessionPrompt != null) refreshPrompt()
                applyState(state.withExpanded(true))
            },
            onCollapse = { applyState(state.withExpanded(false)) },
            onPause = {
                if (state.phase == PhoneOverlayPhase.RUNNING) {
                    integration.onOverlayPause()
                    applyState(state.withPhase(PhoneOverlayPhase.PAUSING))
                }
            },
            onTakeOver = { onTakeOver() },
            onRequestEnd = { applyState(state.withEndConfirm(confirming = true)) },
            onConfirmEnd = { onEndConfirmed() },
            onCancelEnd = { applyState(state.withEndConfirm(confirming = false)) },
            onReturnToNative = { onReturnToNative() },
            onCloseOverlay = { onCloseOverlay() },
            onMoveLeft = {
                applyState(state.withSide(PhoneOverlaySide.LEFT))
                rememberPosition()
            },
            onMoveRight = {
                applyState(state.withSide(PhoneOverlaySide.RIGHT))
                rememberPosition()
            },
            onDragBy = { dx, dy -> surfaceDrag(dx, dy) },
            onDragEnd = { surfaceDragEnd() },
            onOpenChat = { onOpenChat() },
            onContinueInChat = { onContinueInChat() },
            onAnswerPrompt = ::answerPrompt,
            onRefreshPrompt = ::refreshPrompt,
        )

    private fun applyState(next: PhoneOverlayWindowState) {
        if (next == state) return
        state = next
        when {
            // A read owns the real window: store the newest authority/control facts so restore
            // shows the phase that is current now, not the RUNNING snapshot from before the hide.
            surface.isHiddenForRead -> surface.update(next, liveActions())
            !nativeVisible && surface.isShown -> surface.update(next, liveActions())
        }
        publishSettings()
    }

    private fun surfaceDrag(dx: Float, dy: Float) {
        dragDx += dx
        val geometry = surface.geometryNow() ?: return
        applyState(overlayReflow(state.copy(offsetY = state.offsetY + dy.toInt()), geometry))
    }

    private fun surfaceDragEnd() {
        val geometry = surface.geometryNow() ?: return
        val syntheticX =
            if (state.side == PhoneOverlaySide.LEFT) {
                geometry.sideMarginPx + dragDx
            } else {
                geometry.screenWidthPx - geometry.sideMarginPx + dragDx
            }
        val height = if (state.expanded) geometry.expandedHeightPx else geometry.collapsedHeightPx
        applyState(
            overlayDragRelease(
                state,
                syntheticX,
                (state.offsetY + height).toFloat(),
                geometry,
            ),
        )
        dragDx = 0f
        rememberPosition()
    }

    private fun ensureDisplayService() {
        if (displayServiceRequested || nativeVisible || surface.isHiddenForRead) return
        runCatching { startDisplayService() }
    }

    private fun stopServiceIfIdle() {
        if (!displayServiceRequested) return
        val live = integration.liveLeaseEpoch() != null
        val retained = state.phase in RETAINED_PHASES
        if (live || retained) return
        if (clock() - lastAuthorityAtMs < BOUNDED_RETENTION_MS) return
        requestStopService()
    }

    private fun boundLifetime() {
        // The demo has its own short timeout; a previous/no task's retention clock cannot close it.
        if (demoTest == OverlayTest.Showing || demoTest == OverlayTest.TapReceived) return
        if (lastAuthorityAtMs == 0L) return
        val live = integration.liveLeaseEpoch() != null
        if (live) {
            lastAuthorityAtMs = clock()
            return
        }
        if (!taskDisplayRetentionElapsed(lastAuthorityAtMs, clock(), BOUNDED_RETENTION_MS,
                demoTest == OverlayTest.Showing || demoTest == OverlayTest.TapReceived)) return
        if (surface.isShown) surface.detach()
        demoJob?.cancel()
        demoSurface.detach()
        // A hidden read still owns the window; keep the host's authority state current so a later
        // restore cannot bring back RUNNING after the retention clock ended.
        applyState(state.withPhase(PhoneOverlayPhase.ENDED).withStopFacts(null, false, false))
        requestStopService()
    }

    private fun requestStopService() {
        if (!displayServiceRequested) return
        displayServiceRequested = false
        serviceEpochOwned = null
        runCatching { stopServiceRequest() }
    }

    private fun publishSettings() {
        mutableSettings.value = buildSettingsState()
    }

    private fun buildSettingsState(): PhoneOverlaySettingsState =
        PhoneOverlaySettingsState(
            available = content.available,
            mode =
                if (settings.mode() == PhoneOverlayMode.OVERLAY) {
                    OverlayMode.OverlayAndNotification
                } else {
                    OverlayMode.NotificationOnly
                },
            permission =
                when (settings.gate()) {
                    PhoneOverlayGate.READY -> OverlayPermission.Granted
                    PhoneOverlayGate.NEEDS_PERMISSION -> OverlayPermission.NotGranted
                    PhoneOverlayGate.UNAVAILABLE -> OverlayPermission.Unsupported
                },
            test = demoTest,
            pending = pendingStop != null,
            lastSurface = lastSurfaceMode,
            lastStopReason = lastStopReasonValue?.toUiStopReason(),
        )

    private companion object {
        const val TICK_MS = 15_000L

        /** After the lease ends, the display may stay for a bounded time at most. */
        const val BOUNDED_RETENTION_MS = 10 * 60_000L

        /** A demo capsule that nobody taps or confirms disappears on its own. */
        const val DEMO_TIMEOUT_MS = 45_000L

        /** The driver's first read waits this long for the manager to attach the exact epoch. */
        const val SURFACE_WAIT_TIMEOUT_MS = 5_000L
        const val SURFACE_WAIT_POLL_MS = 50L

        const val OWNER_RETAINED = "retained"
        const val OWNER_DEMO = "demo"

        val RETAINED_PHASES =
            setOf(
                PhoneOverlayPhase.TAKEN_OVER,
                PhoneOverlayPhase.WAITING_USER,
                PhoneOverlayPhase.NEEDS_CHECK,
                PhoneOverlayPhase.STOP_PENDING,
                PhoneOverlayPhase.UNKNOWN,
                PhoneOverlayPhase.ENDED,
            )
    }
}

/** What the native settings card shows. Nothing here is a grant or a visibility claim. */
internal data class PhoneOverlaySettingsSnapshot(
    val requested: PhoneOverlayMode,
    val effective: PhoneOverlayMode,
    val gate: PhoneOverlayGate,
)

/** Process-wide handle for the display service (started by the system/coordinator, not by Compose). */
internal object PhoneOverlayHolder {
    @Volatile
    private var coordinator: PhoneOverlayCoordinator? = null

    fun install(value: PhoneOverlayCoordinator) {
        coordinator = value
    }

    fun current(): PhoneOverlayCoordinator? = coordinator
}
