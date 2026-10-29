package io.andchamber.app.phone.android

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import io.andchamber.app.MainActivity
import io.andchamber.app.phone.adb.AdbDeviceException
import io.andchamber.app.phone.android.overlay.PhoneOverlayHolder
import io.andchamber.app.phone.android.overlay.PhoneTaskIdentity
import io.andchamber.app.phone.android.overlay.PhoneTaskNativeEndHelper
import io.andchamber.app.phone.android.overlay.PhoneTaskNativeFacts
import io.andchamber.app.phone.android.overlay.PhoneTaskProgressView
import io.andchamber.app.phone.core.PhoneActionKind
import io.andchamber.app.phone.core.PhoneAgentAction
import io.andchamber.app.phone.core.PhoneAgentRequest
import io.andchamber.app.phone.core.PhoneBlockReason
import io.andchamber.app.phone.core.PhoneCallResult
import io.andchamber.app.phone.core.PhoneConnectionResult
import io.andchamber.app.phone.core.PhoneConnectionState
import io.andchamber.app.phone.core.PhoneController
import io.andchamber.app.phone.core.PhoneErrorCode
import io.andchamber.app.phone.core.PhoneEvent
import io.andchamber.app.phone.core.PhoneGrant
import io.andchamber.app.phone.core.PhoneGrantResult
import io.andchamber.app.phone.core.PhoneLimits
import io.andchamber.app.phone.core.PhoneState
import io.andchamber.app.phone.core.PhoneStandingBlockReason
import io.andchamber.app.phone.core.PhoneTargetPolicy
import io.andchamber.app.phone.core.PhoneTimeLimits
import io.andchamber.app.phone.overlay.progress.PhoneProgressKind
import io.andchamber.app.phone.overlay.progress.PhoneProgressReason
import io.andchamber.app.phone.overlay.progress.PhoneStopAcknowledgement
import io.andchamber.app.phone.overlay.progress.PhoneTaskKey
import io.andchamber.app.phone.overlay.progress.PhoneTaskState
import io.andchamber.app.phone.standing.PhoneOpenAppResult
import io.andchamber.app.phone.standing.PhoneStandingApp
import io.andchamber.app.phone.standing.PhoneStandingRefusal
import io.andchamber.app.phone.standing.PhoneStandingResume
import io.andchamber.app.phone.standing.PhoneStandingScope
import io.andchamber.app.phone.standing.PhoneStandingView
import io.andchamber.app.phone.standing.PhoneTaskContinuationStatus
import io.andchamber.app.phone.standing.PhoneTaskStartFailureStage
import io.andchamber.app.phone.standing.PhoneTaskStartResult
import io.andchamber.app.phone.standing.PhoneTaskStartState
import io.andchamber.app.notify.OpenSessionLink
import io.andchamber.app.ui.phone.PhoneAutoConnect
import io.andchamber.app.ui.phone.PhoneBusy
import io.andchamber.app.ui.phone.PhoneConnectRequest
import io.andchamber.app.ui.phone.PhoneContinueDelivery
import io.andchamber.app.ui.phone.PhoneContinueFailure
import io.andchamber.app.ui.phone.PhoneContinuation
import io.andchamber.app.ui.phone.MAX_STANDING_APPS
import io.andchamber.app.ui.phone.PhoneControlActions
import io.andchamber.app.ui.phone.PhoneDiagnosticTokens
import io.andchamber.app.ui.phone.PhoneDeviceProblem
import io.andchamber.app.ui.phone.PhoneEndpointHints
import io.andchamber.app.ui.phone.PhoneGrantRequest
import io.andchamber.app.ui.phone.PhoneHaltReason
import io.andchamber.app.ui.phone.PhoneLastAction
import io.andchamber.app.ui.phone.PhonePairRequest
import io.andchamber.app.ui.phone.PhonePairingNotice
import io.andchamber.app.ui.phone.PhonePending
import io.andchamber.app.ui.phone.PhoneScreenState
import io.andchamber.app.ui.phone.PhoneStandingRequest
import io.andchamber.app.ui.phone.PhoneStopChannel
import io.andchamber.app.ui.phone.PhoneTarget
import io.andchamber.app.ui.phone.PhoneTargetVerdict
import io.andchamber.app.ui.phone.phoneHaltFacts
import io.github.muntashirakon.adb.AdbPairingException
import io.github.muntashirakon.adb.AdbPairingFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The Android side of phone control: maps the pure controller into the screen's model, owns the
 * device link (pairing/connect), the native grant gate, the foreground service and the minimal
 * task/halt memory.
 *
 * It never grants on its own: every grant comes from a [PhoneGrantRequest] the screen already
 * confirmed with the person, and the controller re-checks it. The agent's tool only proposes a task;
 * its proposal is a prefill and is dismissed when a person grants or replaces it.
 *
 * All fields are touched on the main thread. Coroutines run on [scope] (main) and only the driver
 * calls hop to IO.
 */
internal class PhoneIntegration(
    private val context: Context,
    private val store: PhoneIntegrationStore,
    private val driver: PhoneDriverFacade,
    private val controller: PhoneController,
    private val proposals: PhoneProposalSource = NoPhoneProposals,
    /** Native-only: publishes the granted epoch so exactly one chat may claim it. Fail-closed. */
    private val grantRoutes: PhoneGrantRoutePublisher = PhoneGrantRoutePublisher.Unavailable,
    /**
     * Native-only task facts. The binding is published before the route becomes claimable, and a
     * lease drop is reported for exactly the epoch that really ended; never an inferred ack.
     */
    private val taskNative: PhoneTaskNativeFacts = PhoneTaskNativeFacts.Unavailable,
    /** Native readiness probes: is the runtime up, and did this launch load the phone tool? */
    private val readiness: PhoneAgentToolReadiness = PhoneAgentToolReadiness.Unavailable,
    /** Native-only one-shot wake-up for the original chat after a confirmed continuation. */
    private val continueSender: PhoneStandingContinueSender = PhoneStandingContinueSender.Unavailable,
    /** Read-only projection of the native task progress, for the need-user note. */
    private val taskProgress: () -> PhoneTaskProgressView? = { null },
    private val notifications: PhoneControlNotifications = PhoneControlNotifications(context),
    private val catalog: PhoneTargetCatalog =
        PhoneTargetCatalog(hostPackage = context.packageName) {
            PhoneTargetCatalog.androidQuery(context, context.packageName)
        },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val hostPackage: String = context.packageName,
    private val launchTarget: (String) -> Boolean = { launchDefaultApp(context, it) },
    /** Read-only: does this package have a launcher activity right now? Never grants. */
    private val hasLaunchTarget: (String) -> Boolean = { packageName -> hasLauncherActivity(context, packageName) },
    private val openDeveloperSettings: () -> Unit = { openDevelopmentSettings(context) },
    private val openNotificationSettings: () -> Unit = { openAppNotificationSettings(context) },
    private val serviceStart: (Long) -> Boolean = { epoch -> PhoneControlService.start(context, epoch) },
    /** Bounded acknowledgement that the foreground stop entry really exists for [epoch]. */
    private val serviceAwaitReady: suspend (Long) -> Boolean = { epoch -> PhoneControlService.awaitReady(epoch) },
    private val serviceStop: () -> Unit = { PhoneControlService.stop(context) },
    private val keyguardLocked: () -> Boolean = {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    },
    private val available: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
) {
    private val pairing = PhonePairingSession(clock)

    /** Freezes one confirmed grant attempt; every stop control invalidates it. */
    private val grantAttempts = PhoneGrantAttemptOwner()

    /**
     * Saved standing configuration plus its monotonic window end. Configuration is not authority:
     * the controller still grants each task and the native screen still owns revocation.
     */
    private var standingState: PhoneStandingState? =
        store.standing()?.let { config ->
            restoredStandingState(
                config = config,
                nowWallMs = wallClock(),
                nowElapsedMs = clock(),
            )
        }

    /** In-memory binding: a saved scope is bound to the first successful chat session/runtime. */
    private var standingBinding = PhoneStandingBindingState()

    /** True once a standing task actually bound a runtime/session; only a native new save clears it. */
    private var standingBindingEverUsed = false

    /** Any save/revoke invalidates continuations that were frozen against the previous revision. */
    private var standingRevision = 0L

    /** A confirmed restart is in progress; the runtime is never called Ready while this is true. */
    private var standingRestarting = false
    private var standingRestartDeadlineMs = 0L
    private var standingRestartRevision: Long? = null
    private var standingRestartFromRuntimeId: String? = null

    /** Thread-safe projection for the tool's non-suspend `apps` call. Updated on the main thread. */
    @Volatile
    private var standingViewCache: PhoneStandingView? = null

    /**
     * All-apps mode listing, published from the launcher catalogue on the main thread. Bounded and
     * sanitized; it is a picker, never authority.
     */
    @Volatile
    private var launchableAppsCache: List<PhoneStandingApp> = emptyList()

    @Volatile
    private var launchableAppsTruncated: Boolean = false

    /** True once a launcher-catalogue load was started for the all-apps listing. */
    private var launchableCatalogRequested: Boolean = false

    /**
     * Last accepted observation package of the live all-apps task, kept across an observation
     * invalidation after a sent action. Display/reconnect hints only; cleared with the grant epoch.
     */
    @Volatile
    private var lastObservedPackage: String? = null

    /** All-apps name policy, shared by the listing, a new request and a re-open. */
    private val allAppsPolicy = PhoneTargetPolicy(hostPackage)

    private var continuationDelivery = PhoneContinueDelivery.Idle

    /**
     * Technical reason for [PhoneContinueDelivery.NotResumed]. It never replaces the original halt
     * reason or the saved standing block; it only explains why access did not come back.
     */
    private var continuationFailure: PhoneContinueFailure? = null
    private var continuationFailureStage: PhoneTaskStartFailureStage? = null
    private var continuationFailureError: PhoneErrorCode? = null

    /**
     * One in-flight continuation attempt. It is used only by the outer exception cleanup; normal
     * failures go through the exact rollback below. It is never authority and is cleared when the
     * attempt ends.
     */
    private var continuationAttempt: ContinuationAttempt? = null

    private data class ContinuationAttempt(
        val epoch: Long,
        var key: PhoneTaskKey? = null,
        val halt: PhoneHaltSnapshot,
        val previousRetainedEpoch: Long?,
        val session: String,
        var wakeAttempted: Boolean = false,
    )
    private var continuationInFlight = false

    /** Bumped whenever the real native phone screen opens; a queued chat start must not proceed. */
    private var phoneScreenGeneration = 0L

    /**
     * True while the native PHONE route is composed. Only that route's own entry/dispose and its
     * activity-resume effect touch it. A host-target start is refused while it is true: the host
     * launch would bring that page forward and the page applies the host-return stop.
     */
    private var phoneScreenOpen = false

    private val facts =
        MutableStateFlow(
            PhoneScreenFacts(
                hostPackage = hostPackage,
                available = available,
                enabled = available && store.enabled(),
                task = store.task(),
                stopChannel = notifications.stopChannelState(),
                standingEnabled = available,
                standingState = standingState,
            ),
        )

    private val mutableState = MutableStateFlow(phoneScreenState(controller.state.value, facts.value, clock()))
    val state: StateFlow<PhoneScreenState> = mutableState.asStateFlow()

    /**
     * The screen's countdown reads the controller's own clock, not a copy: the lease timestamps and the
     * countdown can never disagree, and a wall-clock change can neither extend nor expire a lease.
     * `PhoneControlScreen` must be given this function, never `System.currentTimeMillis`.
     */
    val monotonicClock: () -> Long = { controller.nowMs() }

    /** The lease the coordinator remembers from the last native grant (never authority by itself). */
    private var lease: PhoneLeaseFacts? = null

    /**
     * Routing memory for the current task's chat. In memory only: it lets a native resume publish the
     * same route instead of falling back to the first-action-claim path. Never authentication.
     */
    private var taskSession: String? = null

    /**
     * Stable native task identity. A resume keeps it; a new task, a finished task or a different
     * runtime gets a new one. Memory only: a restart keeps the needs-check record but no owner.
     */
    private val taskIdentity = PhoneTaskIdentity()

    /**
     * The last grant epoch of the retained task. A person may end that retained task after its lease
     * was already stopped (takeover/need_user/unknown); the end is allowed only for this exact epoch
     * and never for a newer grant.
     */
    private var retainedTaskEpoch: Long? = null

    /**
     * Native phone-screen End. Freezing and the exact end path live in [nativeUiEnd]; the model
     * finish/overlay End flows do not use it, so they never double-record or overwrite a model note.
     */
    private val nativeUiEnd =
        PhoneTaskNativeEndHelper(
            freeze = ::nativeUiEndBinding,
            endExact = ::endTaskExact,
            publish = { binding, ack ->
                taskNative.onNativeLeaseDropped(
                    taskId = binding.taskId,
                    runtimeId = binding.runtimeId,
                    epoch = binding.epoch,
                    reason = PhoneProgressReason.USER_REQUESTED,
                    ended = true,
                    ack = ack,
                )
            },
            finishWithoutFact = ::finish,
        )

    /** Pairing attempt identity: every cancel/disable/new attempt invalidates late results. */
    private var pairingOperationSeq = 0L
    private var activePairingOperation: Long? = null

    /**
     * The explicit bounded-discovery session. Results are accepted only for the current pairing
     * generation or the completed pair's own connect search; a stop/new attempt drops the generation so
     * a late callback cannot revive an old port.
     */
    private var pairingDiscoveryGeneration: Long? = null
    private var pairingCompletedGeneration: Long? = null
    private var pairingDiscoveredEndpoint: PhoneDiscoveredEndpoint? = null
    private var connectDiscoveredEndpoint: PhoneDiscoveredEndpoint? = null
    private var connectDiscoveryStatus: PhoneDiscoveryStatus = PhoneDiscoveryStatus.Idle

    /** The explicit "connect to this phone" search runs only in-process and only for a short bound. */
    private var connectSearch: PhonePortDiscovery? = null
    private var connectSearchGeneration = 0L
    private var connectSearchActive = false
    private var connectSearchConsumed = false

    /**
     * A launch reconnect stays silent: when nothing answers (wireless debugging off, no endpoint)
     * it reports no problem, so the native page keeps its ordinary "Find and connect" path.
     */
    private var connectSearchSilent = false

    /** One launch reconnect per app run; the person's own connect is never limited by it. */
    private var launchAutoConnectAttempted = false
    private var launchAutoConnectInFlight = false
    private var launchAutoConnectRuntime: String? = null

    private val autoConnect = PhoneAutoConnectGate()

    private val observers = mutableListOf<Job>()

    init {
        notifications.ensureChannels()
        if (facts.value.enabled) controller.enable()
        observeController()
        observeFacts()
        observeProposals()
        startTicker()
        refreshStandingProjection()
        refreshAgentToolBlocker()
    }

    // --- screen callbacks -------------------------------------------------------------------

    /**
     * Builds the callback surface. [onBack] is navigation, owned by the UI route.
     * [onRestartRuntime] is the person-confirmed local runtime restart from Home. [onOpenChat] only
     * navigates; it never revokes, grants or resumes by itself.
     */
    fun actions(
        onBack: () -> Unit,
        onRestartRuntime: () -> Unit = {},
        onOpenChat: () -> Unit = { openChat() },
    ): PhoneControlActions =
        PhoneControlActions(
            onBack = onBack,
            onEnable = ::setEnabled,
            onOpenWirelessSettings = ::openWirelessDebuggingSettings,
            onOpenNotificationSettings = { openNotificationSettings() },
            onRecheckStopChannel = ::recheckStopChannel,
            onBeginPairingNotification = ::beginPairingNotification,
            onCancelPairingNotification = ::cancelPairingNotification,
            onPair = ::pairManually,
            onConnect = ::connect,
            onAutoConnect = ::connectToThisPhone,
            onDisconnect = ::disconnect,
            onGrant = ::grant,
            onPause = ::pause,
            onTakeOver = ::takeOver,
            onFinish = ::finishFromNativeUi,
            onObserve = ::observeTarget,
            onOpenTarget = { packageName -> launchTarget(packageName) },
            onAcknowledgeChecked = ::acknowledgeChecked,
            onDismissResult = ::dismissResult,
            onSaveStanding = ::saveStanding,
            onRevokeStanding = ::revokeStanding,
            onRestartRuntime = {
                standingRestarting = true
                standingRestartDeadlineMs = clock() + RESTART_PROBE_TIMEOUT_MS
                standingRestartRevision = standingRevision
                standingRestartFromRuntimeId = controller.state.value.runtimeId
                onRestartRuntime()
                refreshAgentToolBlocker()
            },
            onRecheckRuntime = {
                finishConfirmedRestartIfReady()
                refreshAgentToolBlocker()
            },
            onOpenChat = onOpenChat,
            onContinueInChat = ::continueInChat,
            onResumeStanding = ::resumeStandingFromUi,
        )

    /** Called when the native screen appears: refresh the picker and the stop entry, find ports. */
    fun onScreenOpened() {
        // The real native phone screen is in front: apply the old host-return stop exactly here,
        // not merely because the Activity resumed on the chat screen. Bump the generation first so
        // a queued chat start can never launch after this point.
        phoneScreenOpen = true
        phoneScreenGeneration += 1
        // A silent launch reconnect yields to the person's own control surface; it must not leave
        // the page waiting on a search the person did not start.
        if (connectSearchActive && connectSearchSilent) cancelConnectSearch(clearPending = true)
        onHostResumed()
        if (!facts.value.available) return
        recheckStopChannel()
        refreshAgentToolBlocker()
        finishConfirmedRestartIfReady()
        loadTargets()
        refreshPairingState()
        refreshStandingProjection()
    }

    fun onScreenClosed() {
        phoneScreenOpen = false
        // The ordinary screen's cheap hint discovery is gone. An explicit pairing must keep its
        // foreground discovery while the person is in the system settings pages; only the separate
        // in-process "connect to this phone" search belongs to the screen.
        cancelConnectSearch(clearPending = true)
    }

    // --- lifecycle from the activity/service ------------------------------------------------

    /**
     * The native phone screen really came to the front (called from [onScreenOpened], not from a
     * plain Activity resume). It drops the lease as a separate stop path from the notification; it
     * is never a pause. Merely reading the local chat keeps the standing scope intact.
     */
    fun onHostResumed() {
        invalidateGrantAttempt()
        recheckStopChannel()
        val core = controller.state.value
        if (!hostReturnDropsLease(hasGrant = core.grant != null, paused = core.paused)) return
        // A paused lease is still a lease: returning to the host stops Observe/Capture as well.
        val remembered = lease ?: facts.value.halt?.lease
        halt(
            PhoneHaltReason.HostReturned,
            remembered,
            actionInFlight = core.inFlightMutation || core.pausing,
        )
        controller.onHostReturned()
        serviceStop()
    }

    /** A locked screen drops the lease without touching the transport. */
    fun onScreenOff() {
        invalidateGrantAttempt()
        val core = controller.state.value
        if (core.grant == null) return
        val actionInFlight = core.inFlightMutation
        halt(PhoneHaltReason.ScreenLocked, actionInFlight = actionInFlight)
        controller.onDeviceLocked()
        serviceStop()
    }

    /** A lost transport drops the lease; the coordinator keeps what is known for the screen. */
    fun onConnectionLost() {
        invalidateGrantAttempt()
        if (controller.state.value.grant == null && !controller.state.value.paused) return
        halt(PhoneHaltReason.ConnectionLost, actionInFlight = controller.state.value.inFlightMutation)
        controller.onConnectionLost()
        serviceStop()
    }

    fun onNotificationPause(epoch: Long) {
        if (epoch != controller.state.value.epoch) return
        pause()
    }

    /**
     * The notification's Return action, after the caller already proved the nonce.
     *
     * It stops exactly [epoch]: a stale action is a no-op and can never revoke a newer grant. Revoke
     * and service stop happen here; opening AndChamber is the caller's job from a real Activity
     * context ([openPhone]), because Android 12+ blocks a notification trampoline that starts an
     * Activity from a service. The legacy service path passes no lambda: it revokes and stops only.
     */
    fun onNotificationReturn(
        epoch: Long,
        openPhone: (() -> Unit)? = null,
    ): Boolean {
        val core = controller.state.value
        if (!serviceActionIsCurrent(epoch, core.grant?.epoch)) return false
        invalidateGrantAttempt()
        halt(PhoneHaltReason.HostReturned, actionInFlight = core.inFlightMutation || core.pausing)
        controller.onHostReturned()
        serviceStop()
        openPhone?.invoke()
        return true
    }

    /**
     * Overlay light pause: freezes mutations only, exactly like the native Pause. Reads may continue.
     */
    fun onOverlayPause() {
        pause()
    }

    /**
     * Overlay takeover. Refuses new actions, revokes the whole lease (cancelling in-flight reads),
     * keeps the unknown facts for the screen, and stays in the current app: it never launches the
     * target. The native acknowledgement only means new reads/writes are disabled; commands already
     * sent are not rolled back.
     */
    fun onOverlayTakeOver(): Boolean {
        invalidateGrantAttempt()
        val core = controller.state.value
        if (core.grant == null) return false
        val binding = overlayTaskBinding()
        val remembered = lease ?: facts.value.halt?.lease
        halt(
            PhoneHaltReason.TakenOver,
            remembered,
            actionInFlight = core.inFlightMutation || core.pausing,
        )
        controller.revoke()
        controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)
        serviceStop()
        publishTaskStop(binding, PhoneProgressReason.TAKEOVER, ended = false)
        refreshContinuation()
        return true
    }

    /**
     * The current native task, for the progress/overlay task adapter. Read-only and bounded: no
     * page text, no nodes, no coordinates. Null when no lease exists (a retained record is not a task)
     * or when the native side has not confirmed a task id yet. A manual task has no route session
     * until the tool server validates its first claim; it is never filled with a placeholder.
     */
    fun overlayTaskBinding(): PhoneOverlayTaskBinding? {
        val core = controller.state.value
        val grant = core.grant ?: return null
        val current = lease ?: return null
        val identity = taskIdentity.current() ?: return null
        return PhoneOverlayTaskBinding(
            taskId = identity.first,
            runtimeId = identity.second,
            epoch = grant.epoch,
            routeSession = taskSession,
            summary = current.summary,
            packageName = current.target.packageName,
        )
    }

    /**
     * Read-only, thread-safe: the package the live task is currently on. In all-apps follow mode it
     * is the latest accepted observation's real package (the target may have handed off to another
     * allowed app); before the first observation it is the grant's launched target. Null when no
     * live grant exists.
     */
    fun currentForegroundPackage(): String? {
        val grant = controller.state.value.grant ?: return null
        return lastObservedPackage ?: grant.targetPackage
    }

    /**
     * Read-only, thread-safe: the current foreground target is AndChamber itself. The overlay
     * manager uses it to stay on the notification-only path while the host is the app in front,
     * because its own surface is deliberately detached then. It never grants, stops or widens
     * anything.
     */
    fun currentTaskIsHost(): Boolean = currentForegroundPackage() == hostPackage

    /**
     * Read-only, thread-safe: the current foreground package, or null. Used only to compare the
     * focused app for the "target hides non-system overlays" fallback; never authority.
     */
    fun liveTaskTargetPackage(): String? = currentForegroundPackage()

    /**
     * Read-only, thread-safe: the person's notification stop entry is verified right now. The
     * overlay fallback may only use the notification path while this is true.
     */
    fun notificationStopVerified(): Boolean = facts.value.stopChannel == PhoneStopChannel.Verified

    /**
     * Stops exactly the task/epoch the model asked to hand over. Main thread only. It returns null
     * when that binding is not the live native owner anymore, so a queued model event can never
     * revoke a newer grant. [PhoneStopAcknowledgement.confirmed] only says the controller's local
     * revoke succeeded (new operations and late reads are refused); sent actions may still apply.
     */
    internal fun stopTaskExact(
        binding: PhoneOverlayTaskBinding,
        reason: PhoneProgressReason,
    ): PhoneStopAcknowledgement? {
        if (reason !in TASK_STOP_REASONS) return null
        val core = controller.state.value
        val grant = core.grant ?: return null
        if (grant.epoch != binding.epoch) return null
        if (!taskIdentity.matches(binding.taskId, binding.runtimeId)) return null
        invalidateGrantAttempt()
        halt(
            PhoneHaltReason.NeedUser,
            lease,
            actionInFlight = core.inFlightMutation || core.pausing,
        )
        controller.revoke()
        // A model need_user hand-off is a native stop: the standing scope waits for the person.
        controller.blockStanding(PhoneStandingBlockReason.NEED_USER)
        serviceStop()
        refreshContinuation()
        if (controller.state.value.grant != null) return null
        return PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
    }

    /**
     * Ends exactly this task/epoch. Main thread only.
     *
     * A live grant for exactly this epoch is ended through the native finish path (revoke + persisted
     * needs-check record). A task whose lease was already stopped (takeover/need_user/unknown) may
     * still be ended, but only when it is the retained task at this exact epoch: nothing newer is
     * touched and no grant is created. The ack only says new operations and late reads are blocked;
     * sent actions may still apply. A wrong key/epoch returns null and persists nothing.
     */
    internal fun endTaskExact(
        binding: PhoneOverlayTaskBinding,
        agentReported: Boolean = false,
    ): PhoneStopAcknowledgement? {
        if (!taskIdentity.matches(binding.taskId, binding.runtimeId)) return null
        val core = controller.state.value
        val grant = core.grant
        if (grant != null) {
            if (grant.epoch != binding.epoch) return null
            val remembered = lease ?: return null
            if (remembered.target.packageName != binding.packageName ||
                remembered.summary != binding.summary
            ) {
                return null
            }
            // Light note only for the agent's own finish on a clean live grant: no stop, no unknown
            // block, nothing in flight. Every other end keeps the full "check the result" card.
            val clean =
                agentReported &&
                    facts.value.halt == null &&
                    core.blocked == null &&
                    !core.inFlightMutation &&
                    !core.pausing
            finish(agentReported = clean)
            if (controller.state.value.grant != null) return null
            return PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
        }
        if (binding.epoch != retainedTaskEpoch) return null
        // The person is ending the retained task: a still-pending confirmation for a new attempt is
        // superseded and must not later commit over the ended task. A live grant was rejected above.
        invalidateGrantAttempt()
        if (!persistRetainedEnd(binding)) return null
        return PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true)
    }

    /**
     * Persists the native result card for a task whose lease is already stopped. No regrant, no
     * controller epoch change, no unrelated task or halt is touched.
     */
    private fun persistRetainedEnd(binding: PhoneOverlayTaskBinding): Boolean {
        val record = store.task() ?: return false
        if (record.targetPackage != binding.packageName || record.summary != binding.summary) return false
        val halt = facts.value.halt
        store.setTask(
            record.copy(
                needsCheck = true,
                haltReason = halt?.reason?.name ?: record.haltReason,
                acknowledged = halt?.acknowledged == true || record.acknowledged,
                agentReported = false,
            ),
        )
        taskSession = null
        retainedTaskEpoch = null
        mutateFacts {
            it.copy(
                halt = null,
                lease = null,
                task = store.task(),
                pending = null,
                busy = null,
            )
        }
        serviceStop()
        return true
    }

    /**
     * Tool lane callback: the native server validated the first claim of a manual route and this is
     * the session that claimed it. Main thread only. It only pins routing memory for the exact
     * current task/epoch; it grants nothing and cannot add a route by itself.
     */
    internal fun onTaskRouteAdopted(
        binding: PhoneOverlayTaskBinding,
        sessionLabel: String,
    ) {
        val core = controller.state.value
        val grant = core.grant ?: return
        if (grant.epoch != binding.epoch) return
        if (!taskIdentity.matches(binding.taskId, binding.runtimeId)) return
        taskSession = sessionLabel
    }

    // --- configure once: standing scope -------------------------------------------------------

    /** Thread-safe, non-suspend projection for the tool's `apps` action. Never authority. */
    fun standingViewSnapshot(): PhoneStandingView? = standingViewCache

    /**
     * Read-only redacted continuation status for the separate status lane. It reads only the same
     * immutable [PhoneScreenFacts.continuation] projection used by the screen, has no extra guard or
     * cache, and never grants/resumes/retries. Null is reserved for a caller with no screen fact.
     */
    fun continuationViewSnapshot(): PhoneTaskContinuationStatus? {
        val continuation = facts.value.continuation
        return PhoneTaskContinuationStatus(
            state = continuation.delivery.toTaskContinuationState(),
            failure =
                if (continuation.delivery == PhoneContinueDelivery.NotResumed) {
                    (continuation.failure ?: PhoneContinueFailure.Other).toTaskContinuationFailure()
                } else {
                    null
                },
        )
    }

    /**
     * One direct chat request inside the person's saved standing scope. Main thread only.
     *
     * Order is part of the guarantee: native session/runtime/task checks, core grant with a fresh
     * epoch, foreground stop entry, native task binding/lease facts, launch the allowed package,
     * and only then a real Observe of the target. Any failure rolls back exactly the new epoch and
     * leaves no task the person cannot stop.
     */
    internal suspend fun startStandingTask(
        sessionId: String,
        summary: String,
        packageName: String,
    ): PhoneTaskStartResult {
        val state = standingState ?: return PhoneTaskStartResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)
        val revision = standingRevision
        val screenGeneration = phoneScreenGeneration
        val core = controller.state.value
        val hasLiveTask = core.grant != null
        val hasRetainedTask = facts.value.halt != null || retainedTaskEpoch != null || facts.value.lease != null
        val refusal =
            standingRequestRefusal(
                config = state.config,
                nowWallMs = wallClock(),
                runtimeId = core.runtimeId,
                sessionId = sessionId,
                binding = standingBinding,
                hasLiveTask = hasLiveTask,
                hasRetainedTask = hasRetainedTask,
                blocked = core.standingBlocked || core.blocked != null,
            )
        if (refusal != null) return PhoneTaskStartResult.Refused(refusal)
        if (state.config.allApps) {
            // No list to check: the all-apps name policy plus a real launcher activity right now.
            if (allAppsPolicy.refusal(packageName, allApps = true) != null ||
                !hasLaunchTarget(packageName)
            ) {
                return PhoneTaskStartResult.Refused(PhoneStandingRefusal.TARGET_NOT_ALLOWED)
            }
        } else if (state.config.apps.none { it.packageName == packageName }) {
            return PhoneTaskStartResult.Refused(PhoneStandingRefusal.TARGET_NOT_ALLOWED)
        }
        // A host-target request must not bring the native phone page forward: that page applies the
        // host-return stop and would revoke this brand-new grant mid-observation. Refuse before any
        // grant/lease; the person leaves the page and retries.
        if (hostTargetBlockedByPhoneScreen(packageName, hostPackage, phoneScreenOpen)) {
            return PhoneTaskStartResult.Refused(PhoneStandingRefusal.PHONE_SCREEN_OPEN)
        }
        // The person must have a reachable stop entry before any new lease is created.
        val stopChannel = notifications.stopChannelState()
        if (stopChannel != PhoneStopChannel.Verified) {
            mutateFacts { it.copy(stopChannel = stopChannel) }
            return PhoneTaskStartResult.Refused(PhoneStandingRefusal.STANDING_BLOCKED)
        }
        val runtimeId = core.runtimeId ?: return PhoneTaskStartResult.Refused(PhoneStandingRefusal.NOT_CONNECTED)
        // A queued legacy per-task confirmation must not commit over this new standing task.
        invalidateGrantAttempt()
        val target = standingTarget(packageName)
        val boundedSummary = summary.trim().take(PhoneLimits.MAX_NODE_LABEL_CHARS)
        if (boundedSummary.isEmpty()) return PhoneTaskStartResult.Refused(PhoneStandingRefusal.INVALID)
        val prospectiveBinding = standingNextBinding(standingBinding, runtimeId, sessionId)
        val scope =
            PhoneStandingScope(
                runtimeId = runtimeId,
                sessionLabel = sessionId,
                allowedPackages = state.config.apps.map { it.packageName },
                allowedActions = state.config.actions,
                windowEndsMs = state.endsAtElapsedMs,
                perTaskMs = state.config.perTaskMs,
                taskRevision = revision,
                allApps = state.config.allApps,
            )
        val granted =
            when (val result = controller.grantFromPreauthorization(scope, packageName)) {
                is io.andchamber.app.phone.standing.PhoneStandingGrantResult.Granted -> result.grant
                is io.andchamber.app.phone.standing.PhoneStandingGrantResult.Refused ->
                    return PhoneTaskStartResult.Refused(result.refusal)
            }
        // A new grant epoch starts with the launched package until its first real observation.
        lastObservedPackage = null
        val epoch = granted.epoch
        // Every failure after the grant is a fixed stage; the exact epoch/key rollback is unchanged
        // and a newer owner is never overwritten by this start's diagnostic.
        fun failStart(
            stage: PhoneTaskStartFailureStage,
            error: PhoneErrorCode?,
            key: PhoneTaskKey?,
        ): PhoneTaskStartResult.Refused {
            // A watchdog read abandoned the connection (core already revoked). Re-arm the same
            // bounded silent reconnect once; this never restores the lease or starts anything.
            if (error == PhoneErrorCode.READ_TIMEOUT) onReadTimeoutDisconnected()
            publishStandingStartFailure(epoch, key, stage, error)
            rollbackStandingStart(epoch, key)
            return PhoneTaskStartResult.Refused(PhoneStandingRefusal.START_FAILED, stage, error)
        }
        val cancelGuard =
            PhoneStandingStartCancelGuard(epoch) { createdEpoch, key, rememberedLease ->
                rollbackStandingStart(createdEpoch, key, expectedLease = rememberedLease)
            }
        return cancelGuard.transaction {
            // A new save/revoke during admission invalidates this start; revoke only its exact epoch.
            if (revision != standingRevision ||
                screenGeneration != phoneScreenGeneration ||
                state !== standingState
            ) {
                return@transaction failStart(PhoneTaskStartFailureStage.AUTHORITY, error = null, key = null)
            }
            // The foreground service builds its notification from this remembered lease. Old
            // completeGrant did the same before serviceStart; setting it only after the service is up
            // leaves serviceNotificationFor() null and the service can miss its startForeground deadline.
            val rememberedLease = PhoneLeaseFacts(target, boundedSummary, actionScopes(state.config.actions))
            lease = rememberedLease
            cancelGuard.rememberLease(rememberedLease)
            if (!startStopEntry(epoch)) {
                if (lease === rememberedLease) lease = null
                return@transaction failStart(PhoneTaskStartFailureStage.STOP_ENTRY, error = null, key = null)
            }
            // A stop control may have run while the stop entry was being proven.
            val afterStopEntry = controller.state.value
            if (revision != standingRevision ||
                screenGeneration != phoneScreenGeneration ||
                afterStopEntry.grant?.epoch != epoch ||
                afterStopEntry.paused ||
                afterStopEntry.standingBlocked ||
                afterStopEntry.blocked != null
            ) {
                if (lease === rememberedLease) lease = null
                return@transaction failStart(PhoneTaskStartFailureStage.AUTHORITY, error = null, key = null)
            }
            val binding =
                runCatching { bindStandingTask(epoch, runtimeId, sessionId, boundedSummary, packageName) }
                    .getOrElse {
                        if (lease === rememberedLease) lease = null
                        return@transaction failStart(PhoneTaskStartFailureStage.TASK_BINDING, error = null, key = null)
                    }
            val taskKey = PhoneTaskKey(binding.taskId, binding.runtimeId)
            cancelGuard.bind(taskKey)
            taskSession = sessionId
            retainedTaskEpoch = epoch
            runCatching {
                store.setTask(PhoneTaskRecord(targetPackage = packageName, summary = boundedSummary, needsCheck = true))
                mutateFacts {
                    it.copy(
                        lease = lease,
                        task = store.task(),
                        halt = null,
                        request = null,
                        continuation = PhoneContinuation(),
                        diagnostics = it.diagnostics.copy(lastError = null),
                    )
                }
            }.onFailure {
                return@transaction failStart(
                    PhoneTaskStartFailureStage.TASK_RECORD,
                    error = null,
                    key = taskKey,
                )
            }
            setContinuationDelivery(PhoneContinueDelivery.Idle)
            val launched = runCatching { launchTarget(packageName) }.getOrDefault(false)
            if (!launched) {
                return@transaction failStart(
                    PhoneTaskStartFailureStage.TARGET_LAUNCH,
                    error = null,
                    key = taskKey,
                )
            }
            // Only a real foreground observation of an allowed package may answer Started. In
            // all-apps mode that includes the target's own handoff to another allowed app.
            when (
                val observed =
                    observeStandingTarget(epoch, sessionId, packageName, allApps = state.config.allApps)
            ) {
                is PhoneStandingObserveResult.Failed ->
                    return@transaction failStart(
                        observed.stage,
                        observed.error,
                        taskKey,
                    )
                PhoneStandingObserveResult.Observed -> Unit
            }
            // A pause/take-over/save during the observation must not leave a task reported as running.
            val after = controller.state.value
            if (revision != standingRevision ||
                screenGeneration != phoneScreenGeneration ||
                after.grant?.epoch != epoch ||
                after.paused ||
                after.standingBlocked ||
                after.blocked != null
            ) {
                return@transaction failStart(
                    PhoneTaskStartFailureStage.AUTHORITY,
                    error = null,
                    key = taskKey,
                )
            }
            standingBinding = prospectiveBinding
            standingBindingEverUsed = true
            refreshStandingProjection()
            PhoneTaskStartResult.Started(
                epoch = epoch,
                key = taskKey,
                state = PhoneTaskStartState.RUNNING,
            )
        }
    }

    /**
     * Brings an allowed app to the foreground inside the already-running task. It never creates an
     * epoch or publishes a route. Explicit mode only reopens the task's exact target; all-apps mode
     * may open any launchable allowed package (the target may hand off to another app).
     */
    internal suspend fun openStandingApp(
        sessionId: String,
        packageName: String,
    ): PhoneOpenAppResult {
        val state = standingState ?: return PhoneOpenAppResult.Refused(PhoneStandingRefusal.NOT_CONFIGURED)
        if (!state.config.isActive(wallClock()) ||
            state.expired ||
            state.endsAtElapsedMs <= clock() ||
            state.config.revoked
        ) {
            return PhoneOpenAppResult.Refused(PhoneStandingRefusal.EXPIRED)
        }
        val core = controller.state.value
        if (core.standingBlocked || core.blocked != null) {
            return PhoneOpenAppResult.Refused(PhoneStandingRefusal.STANDING_BLOCKED)
        }
        if (core.paused) return PhoneOpenAppResult.Refused(PhoneStandingRefusal.PAUSED)
        val grant = core.grant ?: return PhoneOpenAppResult.Refused(PhoneStandingRefusal.INVALID)
        val currentSession = taskSession ?: return PhoneOpenAppResult.Refused(PhoneStandingRefusal.INVALID)
        if (currentSession != sessionId) return PhoneOpenAppResult.Refused(PhoneStandingRefusal.SESSION_MISMATCH)
        val current = lease ?: return PhoneOpenAppResult.Refused(PhoneStandingRefusal.INVALID)
        val allowedTarget =
            openAppTargetAllowed(
                allApps = state.config.allApps,
                taskTarget = current.target.packageName,
                grantTarget = grant.targetPackage,
                packageName = packageName,
                // The all-apps name policy and a real launcher activity right now. Explicit mode
                // already proved its list at grant time.
                allAppsPolicyAllowed = allAppsPolicy.refusal(packageName, allApps = true) == null,
                launchable = hasLaunchTarget(packageName),
            )
        if (!allowedTarget) {
            return PhoneOpenAppResult.Refused(PhoneStandingRefusal.TARGET_NOT_ALLOWED)
        }
        val epoch = grant.epoch
        if (!runCatching { launchTarget(packageName) }.getOrDefault(false)) {
            return PhoneOpenAppResult.Refused(PhoneStandingRefusal.START_FAILED)
        }
        // A successful startActivity is not proof. Only a real Observe of this exact epoch/session
        // showing the allowed package may answer Opened; a pause/stop/newer epoch invalidates it.
        if (observeStandingTarget(
                epoch,
                sessionId,
                packageName,
                allApps = state.config.allApps,
            ) !is PhoneStandingObserveResult.Observed
        ) {
            return PhoneOpenAppResult.Refused(PhoneStandingRefusal.START_FAILED)
        }
        val after = controller.state.value
        if (after.grant?.epoch != epoch ||
            after.paused ||
            after.standingBlocked ||
            after.blocked != null ||
            facts.value.halt != null ||
            lease?.target?.packageName != packageName
        ) {
            return PhoneOpenAppResult.Refused(PhoneStandingRefusal.START_FAILED)
        }
        return PhoneOpenAppResult.Opened
    }

    /**
     * Tool-lane rollback for a direct start it must refuse (stale epoch / unpublished route). Only
     * the exact epoch + task identity is revoked; an older or newer owner is never touched.
     */
    internal fun abortStandingTask(
        epoch: Long,
        key: PhoneTaskKey,
    ): Boolean {
        val identity = taskIdentity.current()
        val matches = identity != null && identity.first == key.taskId && identity.second == key.runtimeId
        val identityOwnedByNewerTask = identity != null && !matches
        if (!standingExactLiveOwner(
                requestedEpoch = epoch,
                liveEpoch = controller.state.value.grant?.epoch,
                identityMatchesRequestedKey = matches,
                identityOwnedByNewerTask = identityOwnedByNewerTask,
            )
        ) {
            // Superseded or already stopped: leave every newer owner/fact untouched.
            return false
        }
        controller.revoke()
        serviceStop()
        closeLease(keepTask = false)
        taskSession = null
        setContinuationDelivery(PhoneContinueDelivery.Idle)
        mutateFacts {
            it.copy(
                lease = null,
                halt = null,
                task = null,
                pending = null,
                busy = null,
                continuation = PhoneContinuation(),
            )
        }
        refreshStandingProjection()
        return controller.state.value.grant == null
    }

    /**
     * Overlay stop-entry/visibility failure. Stops new reads/writes and revokes the exact lease like
     * take-over, but keeps the real failure reason: it never claims the person took over. The saved
     * standing scope stays, suspended until the person resumes it natively.
     */
    fun onOverlayFailure(reason: PhoneHaltReason): Boolean {
        if (reason != PhoneHaltReason.StopChannelLost && reason != PhoneHaltReason.ScreenUncertain) {
            return false
        }
        invalidateGrantAttempt()
        val core = controller.state.value
        if (core.grant == null) return false
        val binding = overlayTaskBinding()
        halt(reason, lease, actionInFlight = core.inFlightMutation || core.pausing)
        controller.revoke()
        controller.blockStanding(
            if (reason == PhoneHaltReason.StopChannelLost) {
                PhoneStandingBlockReason.CONNECTION_LOST
            } else {
                PhoneStandingBlockReason.UNKNOWN_OUTCOME
            },
        )
        serviceStop()
        publishTaskStop(
            binding,
            if (reason == PhoneHaltReason.StopChannelLost) {
                PhoneProgressReason.CONNECTION_LOST
            } else {
                PhoneProgressReason.OUTCOME_UNKNOWN
            },
            ended = false,
        )
        setContinuationDelivery(PhoneContinueDelivery.Idle)
        refreshContinuation()
        return controller.state.value.grant == null
    }

    /**
     * Read-only routing hint for the overlay/notification. It is not authentication: a retained
     * task's session, else the saved standing binding. Null when neither is known.
     */
    fun currentTaskSessionId(): String? {
        taskSession?.let { return it }
        val hasTask =
            controller.state.value.grant != null ||
                retainedTaskEpoch != null ||
                facts.value.halt != null ||
                facts.value.lease != null
        // A live/retained task without a validated route must not borrow the standing binding.
        return if (hasTask) null else standingBinding.sessionLabel
    }

    // --- standing configuration / continuation -------------------------------------------------

    private fun saveStanding(request: PhoneStandingRequest) {
        if (guardBusy()) return
        // An unchecked unknown outcome or an unsettled action must be acknowledged/settled on the
        // native result card before a new configuration can replace the task facts that carry it.
        val currentCore = controller.state.value
        val unresolved = facts.value.halt
        if (currentCore.blocked != null ||
            currentCore.inFlightMutation ||
            currentCore.pausing ||
            (unresolved != null && unresolved.lastAction == PhoneLastAction.MayStillApply && !unresolved.acknowledged)
        ) {
            mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
            return
        }
        // "All apps" is the person's explicit whole-device choice: no list is stored. Any apps the
        // screen still carries alongside that flag are ignored, so the two shapes can never mix.
        val allApps = request.allApps
        val apps =
            if (allApps) {
                emptyList()
            } else {
                request.apps
                    .distinctBy { it.packageName }
                    .filter { PhoneTargetCatalog.verdictFor(it.packageName, hostPackage) == PhoneTargetVerdict.Allowed }
                    .take(MAX_STANDING_APPS)
            }
        val kinds = actionKinds(request.actions)
        if ((!allApps && apps.isEmpty()) ||
            kinds.isEmpty() ||
            !validStandingBounds(request.windowMs, request.perTaskMs)
        ) {
            mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
            return
        }
        val priorRuntime = controller.state.value.runtimeId
        val priorSession = standingBinding.sessionLabel ?: taskSession
        detachStandingTask()
        // Saving is a fresh native confirmation: clear an old standing block/pause for the exact
        // runtime. An unchecked unknown outcome still refuses, so it cannot be erased this way.
        if (priorRuntime != null) {
            controller.resumeStandingForPerson(
                PhoneStandingResume(priorRuntime, priorSession ?: "standing", standingRevision),
            )
        }
        standingRevision += 1
        val known = facts.value.targets.associateBy { it.packageName }
        val nowWallMs = wallClock()
        val nowElapsedMs = clock()
        val config =
            PhoneStandingConfig(
                apps = apps.map { target ->
                    val label = known[target.packageName]?.label ?: target.label
                    io.andchamber.app.phone.standing.PhoneStandingApp(label, target.packageName)
                },
                actions = kinds,
                // PERMANENT maps to PERMANENT without adding MAX to the clock (overflow).
                windowEndsAtWallMs = PhoneTimeLimits.expiresAt(nowWallMs, request.windowMs),
                perTaskMs = request.perTaskMs,
                allApps = allApps,
            )
        standingState =
            PhoneStandingState(
                config = config,
                endsAtElapsedMs = PhoneTimeLimits.expiresAt(nowElapsedMs, request.windowMs),
            )
        standingBinding = PhoneStandingBindingState()
        standingBindingEverUsed = false
        setContinuationDelivery(PhoneContinueDelivery.Idle)
        store.setStanding(config)
        mutateFacts {
            it.copy(
                standingState = standingState,
                standingEnabled = available,
                continuation = PhoneContinuation(),
                busy = null,
                problem = null,
                diagnostics = it.diagnostics.copy(lastError = null),
            )
        }
        refreshStandingProjection()
        refreshAgentToolBlocker()
    }

    private fun revokeStanding() {
        if (guardBusy()) return
        val current = standingState ?: return
        val core = controller.state.value
        val unresolved = facts.value.halt
        if (core.blocked != null ||
            core.inFlightMutation ||
            core.pausing ||
            (unresolved != null && unresolved.lastAction == PhoneLastAction.MayStillApply && !unresolved.acknowledged)
        ) {
            mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
            return
        }
        detachStandingTask()
        standingRevision += 1
        setContinuationDelivery(PhoneContinueDelivery.Idle)
        val revoked = current.config.copy(revoked = true)
        standingState = current.copy(config = revoked, expired = false)
        standingBinding = PhoneStandingBindingState()
        standingBindingEverUsed = false
        store.setStanding(revoked)
        mutateFacts {
            it.copy(
                standingState = standingState,
                continuation = PhoneContinuation(),
                busy = null,
            )
        }
        refreshStandingProjection()
    }

    /** Stop any live/retained native task but keep the saved configuration. Save/revoke use this. */
    private fun detachStandingTask() {
        invalidateGrantAttempt()
        if (controller.state.value.grant != null) controller.revoke()
        serviceStop()
        closeLease(keepTask = false)
        taskSession = null
        retainedTaskEpoch = null
        standingBinding = PhoneStandingBindingState()
        mutateFacts {
            it.copy(
                lease = null,
                halt = null,
                task = null,
                pending = null,
                busy = null,
                continuation = PhoneContinuation(),
            )
        }
    }

    private fun openChat() {
        // Navigation only. It never revokes, resumes or extends anything.
        OpenSessionLink.offer(currentTaskSessionId(), null)
    }

    /**
     * Person-confirmed continuation beside the chat. It is offered only after a settled stop in the
     * same task/session/runtime; it creates one fresh epoch, re-observes the target, then wakes the
     * original chat exactly once with a fixed prompt. A failed wake does not roll back the lease.
     */
    private fun continueInChat() {
        // Idle: first confirmation. NotResumed: a technical failure before access came back, so a
        // fresh person confirmation may try again. Sending/Delivered/Failed are never repeated.
        if (!continuationMayStart(continuationDelivery, continuationInFlight)) return
        val state = standingState ?: return
        val halt = facts.value.halt ?: return
        val core = controller.state.value
        val runtimeId = core.runtimeId ?: return
        val session = taskSession ?: standingBinding.sessionLabel ?: return
        if (standingBinding.runtimeId != runtimeId || standingBinding.sessionLabel != session) return
        if (!halt.confirmed || halt.lastAction == PhoneLastAction.MayStillApply) return
        if (facts.value.pending != null || core.inFlightMutation || core.pausing) return
        if (core.blocked != null) return
        if (state.config.revoked ||
            state.expired ||
            state.endsAtElapsedMs <= clock() ||
            !state.config.isActive(wallClock())
        ) {
            return
        }
        if (!standingTargetCovered(state.config, halt.lease.target.packageName)) return
        if (facts.value.runtimeStatus.tool != io.andchamber.app.ui.phone.PhoneToolStatus.Ready) return
        if (facts.value.stopChannel != PhoneStopChannel.Verified) return
        continuationInFlight = true
        setContinuationDelivery(PhoneContinueDelivery.Sending)
        refreshContinuation()
        val revision = standingRevision
        val previousRetainedEpoch = retainedTaskEpoch
        val previousTaskSession = taskSession
        val previousIdentity = taskIdentity.current()?.let { PhoneTaskKey(it.first, it.second) }
        scope.launch {
            try {
                performContinuation(
                    state = state,
                    halt = halt,
                    runtimeId = runtimeId,
                    session = session,
                    revision = revision,
                )
            } catch (cancelled: CancellationException) {
                cleanupUnexpectedContinuation(
                    halt = halt,
                    revision = revision,
                    previousRetainedEpoch = previousRetainedEpoch,
                    previousTaskSession = previousTaskSession,
                    previousIdentity = previousIdentity,
                    runtimeId = runtimeId,
                )
                throw cancelled
            } catch (_: Exception) {
                // The strict transaction cleanup owns the grant/lease/task here; the label alone is
                // never treated as a rollback.
                cleanupUnexpectedContinuation(
                    halt = halt,
                    revision = revision,
                    previousRetainedEpoch = previousRetainedEpoch,
                    previousTaskSession = previousTaskSession,
                    previousIdentity = previousIdentity,
                    runtimeId = runtimeId,
                )
            } finally {
                continuationInFlight = false
                continuationAttempt = null
            }
        }
    }

    /**
     * Safety net for an unexpected exception outside an explicit failure path. Before the wake
     * attempt it performs the same exact-owner rollback and reports [PhoneContinueDelivery.NotResumed];
     * after the wake attempt it keeps the established live grant and only marks the one-shot wake as
     * failed. A newer owner is never touched.
     *
     * With no attempt object yet, a NotResumed/Other is recorded only when the original halt,
     * revision, runtime, task identity/session and retained epoch are still unchanged and there is
     * no new grant or wake. Otherwise the cleanup is neutral and never grants retry eligibility.
     */
    private fun cleanupUnexpectedContinuation(
        halt: PhoneHaltSnapshot,
        revision: Long,
        previousRetainedEpoch: Long?,
        previousTaskSession: String?,
        previousIdentity: PhoneTaskKey?,
        runtimeId: String,
    ) {
        val attempt = continuationAttempt
        if (attempt == null) {
            val core = controller.state.value
            val identity = taskIdentity.current()
            val identityMatches =
                previousIdentity == null ||
                    (
                        identity != null &&
                            identity.first == previousIdentity.taskId &&
                            identity.second == previousIdentity.runtimeId
                    )
            val unchanged =
                core.grant == null &&
                    core.runtimeId == runtimeId &&
                    facts.value.halt === halt &&
                    standingRevision == revision &&
                    retainedTaskEpoch == previousRetainedEpoch &&
                    taskSession == previousTaskSession &&
                    identityMatches
            if (unchanged) {
                // No grant and no wake happened for this failed attempt: keep/rebuild the original
                // block and report the technical failure instead of silently going Idle.
                controller.blockStanding(halt.reason.toStandingBlockReason())
                setContinuationDelivery(PhoneContinueDelivery.NotResumed, PhoneContinueFailure.Other)
                refreshContinuation()
            } else if (continuationDelivery == PhoneContinueDelivery.Sending) {
                setContinuationDelivery(PhoneContinueDelivery.Idle)
                refreshContinuation()
            }
            return
        }
        if (!attempt.wakeAttempted) {
            val failure = PhoneContinueFailure.Other
            if (
                rollbackContinuation(
                    epoch = attempt.epoch,
                    key = attempt.key,
                    session = attempt.session,
                    previousRetainedEpoch = attempt.previousRetainedEpoch,
                    halt = attempt.halt,
                    failure = failure,
                    stage = null,
                    error = null,
                )
            ) {
                setContinuationDelivery(continuationPreWakeDelivery(), failure)
                refreshContinuation()
            } else if (continuationDelivery == PhoneContinueDelivery.Sending) {
                setContinuationDelivery(PhoneContinueDelivery.Idle)
                refreshContinuation()
            }
            return
        }
        if (continuationStillOwns(attempt.epoch, attempt.key)) {
            setContinuationDelivery(PhoneContinueDelivery.Failed)
            refreshContinuation()
        } else if (continuationDelivery == PhoneContinueDelivery.Sending) {
            setContinuationDelivery(PhoneContinueDelivery.Idle)
            refreshContinuation()
        }
    }

    /**
     * The person's explicit "Resume chat use" confirmation. Main thread, synchronous.
     *
     * Every guard is checked against fresh native probes and the current controller state before the
     * controller is touched. A refusal leaves the standing block, binding, configuration and task
     * memory exactly as they were and only reports the existing "request ignored" feedback. Opening
     * the screen, reconnecting or the tool becoming ready never reaches this entry.
     *
     * A successful resume is the existing native person primitive, so it also clears the ordinary
     * pause under the same lock. It never grants, replays or changes the saved scope; the next chat
     * request still passes the full standing policy. The same-runtime binding is deliberately kept.
     */
    private fun resumeStandingFromUi() {
        if (guardBusy()) return
        val core = controller.state.value
        val state = standingState
        val halt = facts.value.halt
        val presence = runCatching { readiness.runtimePresence() }.getOrDefault(PhoneRuntimePresence.UNKNOWN)
        val toolReady = runCatching { readiness.toolReady() }.getOrNull()
        val gate =
            StandingResumeFacts(
                available = facts.value.available,
                enabled = facts.value.enabled,
                controllerEnabled = core.enabled,
                config = state?.config,
                nowWallMs = wallClock(),
                nowElapsedMs = clock(),
                endsAtElapsedMs = state?.endsAtElapsedMs ?: 0L,
                expired = state?.expired == true,
                runtimeId = core.runtimeId,
                binding = standingBinding,
                runtimeRunning = presence == PhoneRuntimePresence.RUNNING,
                toolReady = toolReady == true,
                connectionConnected = core.connection == PhoneConnectionState.CONNECTED,
                stopChannelVerified = notifications.stopChannelState() == PhoneStopChannel.Verified,
                pending = facts.value.pending,
                continuationInFlight = continuationInFlight,
                continuationSending = continuationDelivery == PhoneContinueDelivery.Sending,
                hasGrant = core.grant != null,
                hasHalt = halt != null,
                hasLease = facts.value.lease != null,
                hasRetainedTask = retainedTaskEpoch != null,
                hasTaskOwner = taskIdentity.current() != null,
                inFlightMutation = core.inFlightMutation,
                pausing = core.pausing,
                standingBlocked = core.standingBlocked,
                standingBlockReason = core.standingBlockReason,
                coreBlocked = core.blocked != null,
                haltMayStillApply = halt?.lastAction == PhoneLastAction.MayStillApply,
                haltAcknowledged = halt?.acknowledged == true,
            )
        val runtimeId = gate.runtimeId
        if (standingResumeRefusal(gate) != null || runtimeId == null) {
            mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
            return
        }
        // The operation itself: only now may pending say "resuming". The resume is synchronous and
        // clears the block/pause under the controller lock; finally always restores the projection.
        mutateFacts { it.copy(pending = PhonePending.Resuming, busy = null) }
        try {
            val resumed =
                confirmedStandingResume(gate) {
                    controller.resumeStandingForPerson(
                        PhoneStandingResume(
                            runtimeId = runtimeId,
                            sessionLabel = standingBinding.sessionLabel ?: taskSession ?: "standing",
                            taskRevision = standingRevision,
                        ),
                    )
                }
            if (!resumed) mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
        } finally {
            mutateFacts { it.copy(pending = null) }
            refreshStandingProjection()
        }
    }

    private suspend fun performContinuation(
        state: PhoneStandingState,
        halt: PhoneHaltSnapshot,
        runtimeId: String,
        session: String,
        revision: Long,
    ) {
        val target = halt.lease.target
        val previousRetainedEpoch = retainedTaskEpoch
        val screenGeneration = phoneScreenGeneration
        fun clearSendingWhenSuperseded() {
            if (continuationDelivery == PhoneContinueDelivery.Sending) {
                // Neutral cleanup only: never write this old attempt's failure/halt over a newer owner.
                setContinuationDelivery(PhoneContinueDelivery.Idle)
                refreshContinuation()
            }
        }
        fun supersededBeforeGrant(): Boolean {
            val liveEpoch = controller.state.value.grant?.epoch
            return revision != standingRevision ||
                screenGeneration != phoneScreenGeneration ||
                facts.value.halt !== halt ||
                retainedTaskEpoch != previousRetainedEpoch ||
                (liveEpoch != null && liveEpoch != previousRetainedEpoch)
        }
        invalidateGrantAttempt()
        if (!controller.resumeStandingForPerson(PhoneStandingResume(runtimeId, session, revision))) {
            if (supersededBeforeGrant()) {
                clearSendingWhenSuperseded()
            } else {
                // Access did not come back. Keep the original halt/task and report a technical
                // "not resumed"; never turn this into a second person step or a takeover.
                controller.blockStanding(halt.reason.toStandingBlockReason())
                setContinuationDelivery(
                    continuationPreWakeDelivery(),
                    PhoneContinueFailure.NotReady,
                    PhoneTaskStartFailureStage.AUTHORITY,
                    null,
                )
                refreshContinuation()
            }
            return
        }
        if (supersededBeforeGrant()) {
            // A save/revoke/screen/new task already owns the newer state. Do not re-block it or
            // overwrite its continuation delivery with this old attempt's failure.
            clearSendingWhenSuperseded()
            return
        }
        // Revalidate after the async resume: the all-apps launcher fact may have changed, and this
        // keeps the same policy+launcher rule as the pre-confirmation gate. Explicit mode is just
        // list membership, exactly as before.
        if (!standingTargetCovered(state.config, target.packageName)) {
            if (supersededBeforeGrant()) {
                clearSendingWhenSuperseded()
            } else {
                controller.blockStanding(halt.reason.toStandingBlockReason())
                val failure = continuationFailureFor(null, null, PhoneStandingRefusal.TARGET_NOT_ALLOWED)
                setContinuationDelivery(continuationPreWakeDelivery(), failure)
                refreshContinuation()
            }
            return
        }
        val scope =
            PhoneStandingScope(
                runtimeId = runtimeId,
                sessionLabel = session,
                allowedPackages = state.config.apps.map { it.packageName },
                allowedActions = state.config.actions,
                windowEndsMs = state.endsAtElapsedMs,
                perTaskMs = state.config.perTaskMs,
                taskRevision = revision,
                allApps = state.config.allApps,
            )
        val granted =
            when (val result = controller.grantFromPreauthorization(scope, target.packageName)) {
                is io.andchamber.app.phone.standing.PhoneStandingGrantResult.Granted -> result.grant
                is io.andchamber.app.phone.standing.PhoneStandingGrantResult.Refused -> {
                    if (supersededBeforeGrant()) {
                        clearSendingWhenSuperseded()
                    } else {
                        if (result.refusal != PhoneStandingRefusal.EXPIRED) {
                            controller.blockStanding(halt.reason.toStandingBlockReason())
                        }
                        val failure = continuationFailureFor(null, null, result.refusal)
                        setContinuationDelivery(continuationPreWakeDelivery(), failure)
                        refreshContinuation()
                    }
                    return
                }
            }
        lastObservedPackage = null
        val epoch = granted.epoch
        var continuationKey: PhoneTaskKey? = null
        val attempt =
            ContinuationAttempt(
                epoch = epoch,
                halt = halt,
                previousRetainedEpoch = previousRetainedEpoch,
                session = session,
            )
        continuationAttempt = attempt
        fun fail(
            failure: PhoneContinueFailure,
            stage: PhoneTaskStartFailureStage? = null,
            error: PhoneErrorCode? = null,
        ) {
            if (error == PhoneErrorCode.READ_TIMEOUT) onReadTimeoutDisconnected()
            if (
                rollbackContinuation(
                    epoch = epoch,
                    key = continuationKey,
                    session = session,
                    previousRetainedEpoch = previousRetainedEpoch,
                    halt = halt,
                    failure = failure,
                    stage = stage,
                    error = error,
                )
            ) {
                setContinuationDelivery(continuationPreWakeDelivery(), failure, stage, error)
                refreshContinuation()
            } else {
                clearSendingWhenSuperseded()
            }
        }
        // Cancellation at either suspension point after the new grant must never leave that grant
        // behind. fail() already performs the exact-owner continuation rollback and delivery state;
        // the original cancellation is rethrown unchanged.
        suspend fun <T> withCancelRollback(
            stage: PhoneTaskStartFailureStage,
            block: suspend () -> T,
        ): T =
            try {
                block()
            } catch (cancelled: CancellationException) {
                fail(PhoneContinueFailure.Cancelled, stage, PhoneErrorCode.CANCELLED)
                throw cancelled
            }
        // The foreground service builds its notification from this lease; it must already be
        // non-null before serviceStart, exactly like the first start path.
        lease = halt.lease
        if (revision != standingRevision || screenGeneration != phoneScreenGeneration) {
            fail(PhoneContinueFailure.Cancelled, PhoneTaskStartFailureStage.AUTHORITY, null)
            return
        }
        if (!withCancelRollback(PhoneTaskStartFailureStage.STOP_ENTRY) { startStopEntry(epoch) }) {
            fail(PhoneContinueFailure.StopChannelUnavailable, PhoneTaskStartFailureStage.STOP_ENTRY, null)
            return
        }
        if (revision != standingRevision ||
            screenGeneration != phoneScreenGeneration ||
            controller.state.value.grant?.epoch != epoch ||
            controller.state.value.paused ||
            controller.state.value.standingBlocked ||
            controller.state.value.blocked != null
        ) {
            fail(PhoneContinueFailure.Cancelled, PhoneTaskStartFailureStage.AUTHORITY, null)
            return
        }
        taskSession = session
        val continuationBinding =
            runCatching {
                taskIdentity.bindOnPublish(
                    epoch = epoch,
                    resumed = true,
                    runtimeId = runtimeId,
                    routeSession = session,
                    summary = halt.lease.summary,
                    packageName = target.packageName,
                ).also { taskNative.onTaskBinding(it) }
            }.getOrNull()
        if (continuationBinding == null) {
            fail(PhoneContinueFailure.Other, PhoneTaskStartFailureStage.TASK_BINDING, null)
            return
        }
        continuationKey = PhoneTaskKey(continuationBinding.taskId, continuationBinding.runtimeId)
        attempt.key = continuationKey
        retainedTaskEpoch = epoch
        if (!runCatching { grantRoutes.publishGrantRoute(epoch, session) }.getOrDefault(false)) {
            fail(PhoneContinueFailure.Other, PhoneTaskStartFailureStage.TASK_BINDING, null)
            return
        }
        val launched = runCatching { launchTarget(target.packageName) }.getOrDefault(false)
        if (!launched) {
            fail(PhoneContinueFailure.TargetUnavailable, PhoneTaskStartFailureStage.TARGET_LAUNCH, null)
            return
        }
        val observed =
            withCancelRollback(PhoneTaskStartFailureStage.OBSERVATION) {
                observeStandingTarget(epoch, session, target.packageName, allApps = state.config.allApps)
            }
        if (observed is PhoneStandingObserveResult.Failed) {
            fail(
                continuationFailureFor(observed.stage, observed.error),
                observed.stage,
                observed.error,
            )
            return
        }
        val after = controller.state.value
        if (revision != standingRevision ||
            screenGeneration != phoneScreenGeneration ||
            after.grant?.epoch != epoch ||
            after.paused ||
            after.standingBlocked ||
            after.blocked != null
        ) {
            fail(PhoneContinueFailure.Cancelled, PhoneTaskStartFailureStage.AUTHORITY, null)
            return
        }
        runCatching {
            store.setTask(
                PhoneTaskRecord(
                    targetPackage = target.packageName,
                    summary = halt.lease.summary,
                    needsCheck = true,
                ),
            )
            setContinuationDelivery(PhoneContinueDelivery.Sending)
            mutateFacts {
                it.copy(
                    halt = null,
                    lease = halt.lease,
                    task = store.task(),
                    pending = null,
                    busy = null,
                    continuation = PhoneContinuation(delivery = PhoneContinueDelivery.Sending),
                    diagnostics = it.diagnostics.copy(lastError = null),
                )
            }
        }.onFailure {
            fail(PhoneContinueFailure.Other, PhoneTaskStartFailureStage.TASK_RECORD, null)
            return
        }
        // A stop during the launch/observe window supersedes the continuation: do not wake the chat
        // into a task that is already stopped, and do not overwrite the newer halt/delivery state.
        if (controller.state.value.grant?.epoch != epoch || facts.value.halt != null) {
            clearSendingWhenSuperseded()
            return
        }
        // From here on access is already back. A failed/unknown wake is the old one-shot failure:
        // keep the live grant and never re-send. An unexpected exception/cancellation is handled by
        // the outer cleanup with wakeAttempted set.
        attempt.wakeAttempted = true
        val delivered =
            try {
                continueSender.send(session, STANDING_CONTINUE_PROMPT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        if (continuationStillOwns(epoch, continuationKey)) {
            setContinuationDelivery(continuationWakeDelivery(delivered))
            refreshContinuation()
        } else {
            clearSendingWhenSuperseded()
        }
    }

    /**
     * Exact-owner rollback for a continuation. It only runs when the live epoch is still the one
     * this attempt created and its task identity was not superseded. A stop/save/new task that owns
     * the newer state makes this return false and leaves every newer fact, binding, service and
     * delivery untouched.
     */
    private fun rollbackContinuation(
        epoch: Long,
        key: PhoneTaskKey?,
        session: String,
        previousRetainedEpoch: Long?,
        halt: PhoneHaltSnapshot,
        failure: PhoneContinueFailure,
        stage: PhoneTaskStartFailureStage?,
        error: PhoneErrorCode?,
    ): Boolean {
        val identity = taskIdentity.current()
        val keyMatches =
            key != null &&
                identity != null &&
                identity.first == key.taskId &&
                identity.second == key.runtimeId
        val identityOwnedByNewerTask = key != null && identity != null && !keyMatches
        val core = controller.state.value
        val progress = runCatching { taskProgress()?.snapshot }.getOrNull()
        // A read-only Observe can make the controller revoke its own grant before returning
        // READ_TIMEOUT/NOT_CONNECTED. That is an explicit typed stop of this exact attempt, not a
        // superseded owner: keep the retained task and never revoke/affect anything again.
        val coreStopped =
            continuationCoreStoppedExact(
                typedError = error,
                liveEpoch = core.grant?.epoch,
                runtimeId = core.runtimeId,
                attemptEpoch = epoch,
                key = key,
                retainedEpoch = retainedTaskEpoch,
                identityMatchesKey = keyMatches,
                haltStillSame = facts.value.halt === halt,
                progressKeyMatches = key != null && progress?.key == key,
                progressEpoch = progress?.grantEpoch,
            )
        val exact =
            standingExactLiveOwner(
                requestedEpoch = epoch,
                liveEpoch = core.grant?.epoch,
                identityMatchesRequestedKey = keyMatches,
                identityOwnedByNewerTask = identityOwnedByNewerTask,
                requireIdentity = key != null,
            ) || coreStopped
        if (!exact) {
            return false
        }
        if (!coreStopped) controller.revoke()
        serviceStop()
        if (key != null && keyMatches) {
            // The bound task/key is kept at the latest attempted epoch. Publish a real native stop
            // receipt (ended=false) so the adapter/progress store projects STOPPING, not abandoned.
            val kept =
                taskNative.onNativeLeaseDropped(
                    taskId = key.taskId,
                    runtimeId = key.runtimeId,
                    epoch = epoch,
                    reason =
                        if (coreStopped) {
                            PhoneProgressReason.CONNECTION_LOST
                        } else {
                            PhoneProgressReason.USER_REQUESTED
                        },
                    ended = false,
                    ack = PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true),
                )
            // Keep the retained epoch in step with the binding that really stayed published.
            retainedTaskEpoch = if (kept) epoch else previousRetainedEpoch
        } else {
            // Nothing was bound for the new attempt: keep the original retained binding/epoch.
            retainedTaskEpoch = previousRetainedEpoch
        }
        taskSession = session
        lease = null
        // Rebuild only the original stop's block reason; a technical failure is reported separately
        // and never rewrites the person's standing authority.
        controller.blockStanding(halt.reason.toStandingBlockReason())
        mutateFacts {
            it.copy(
                lease = null,
                halt = halt,
                task = store.task(),
                pending = null,
                busy = null,
                continuation =
                    PhoneContinuation(
                        delivery = continuationPreWakeDelivery(),
                        failure = failure,
                    ),
            )
        }
        return true
    }

    /** Whether this exact continuation still owns the live grant and no newer stop took it. */
    private fun continuationStillOwns(
        epoch: Long,
        key: PhoneTaskKey?,
    ): Boolean {
        val identity = taskIdentity.current()
        val keyMatches =
            key != null &&
                identity != null &&
                identity.first == key.taskId &&
                identity.second == key.runtimeId
        return standingExactLiveOwner(
            requestedEpoch = epoch,
            liveEpoch = controller.state.value.grant?.epoch,
            identityMatchesRequestedKey = keyMatches,
            identityOwnedByNewerTask = identity != null && key != null && !keyMatches,
        ) && facts.value.halt == null
    }

    private fun standingTarget(packageName: String): PhoneTarget =
        facts.value.targets.firstOrNull { it.packageName == packageName }
            ?: PhoneTarget(
                label = packageName,
                packageName = packageName,
                verdict = PhoneTargetVerdict.Allowed,
            )

    /**
     * Continuation coverage for the halted target. Explicit mode stays list membership; all-apps
     * mode has no list, so it needs the same two gates as a new request: the all-apps name policy
     * and a real launcher activity right now. Read-only; it never grants or widens the scope.
     */
    private fun standingTargetCovered(
        config: PhoneStandingConfig,
        packageName: String,
    ): Boolean =
        if (!config.allApps) {
            standingCoversHaltedTarget(config, packageName, allAppsPolicyAllowed = false, launchable = false)
        } else {
            standingCoversHaltedTarget(
                config = config,
                packageName = packageName,
                allAppsPolicyAllowed = allAppsPolicy.refusal(packageName, allApps = true) == null,
                launchable = hasLaunchTarget(packageName),
            )
        }

    private suspend fun startStopEntry(epoch: Long): Boolean =
        try {
            serviceStart(epoch) && serviceAwaitReady(epoch)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private fun bindStandingTask(
        epoch: Long,
        runtimeId: String,
        sessionId: String,
        summary: String,
        packageName: String,
    ): PhoneOverlayTaskBinding {
        val binding =
            taskIdentity.bindOnPublish(
                epoch = epoch,
                resumed = false,
                runtimeId = runtimeId,
                routeSession = sessionId,
                summary = summary,
                packageName = packageName,
            )
        taskNative.onTaskBinding(binding)
        return binding
    }

    /**
     * The one bounded observation used by a start, an open-app and a continuation.
     *
     * A single absolute monotonic deadline of [PhoneLimits.MAX_CALL_MS] is taken before the overlay
     * wait, so the wait and every retry share the same budget. Only window transitions and a target
     * that is not foreground yet retry; the first terminal typed error (hierarchy busy, read timeout,
     * deadline, lost connection, cancellation, security refusal, any other driver failure) stops and
     * is returned unchanged. A real window/package observation is still required before success.
     *
     * [allApps] is the frozen standing mode. In explicit mode only [packageName] is accepted. In
     * all-apps follow mode any foreground package that passes the all-apps policy is accepted (the
     * target may hand off to an OAuth/payment/browser app); the real package is recorded by the
     * controller's observation, never replaced by the launched one.
     */
    private suspend fun observeStandingTarget(
        epoch: Long,
        sessionId: String,
        packageName: String,
        allApps: Boolean,
    ): PhoneStandingObserveResult {
        val deadlineElapsedMs = clock() + PhoneLimits.MAX_CALL_MS
        // With the overlay selected, the first real driver read must wait until the current epoch's
        // surface is attached and its real window witness is available. Notification-only or no
        // coordinator keeps the original path. Wait exactly once; its bounded API is unchanged and
        // the elapsed time is charged to the same deadline below.
        val surfaceReady =
            try {
                PhoneOverlayHolder.current()?.awaitDriverSurface(epoch) ?: true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        if (!surfaceReady) {
            return PhoneStandingObserveResult.Failed(
                stage = PhoneTaskStartFailureStage.OVERLAY,
                error = if (clock() >= deadlineElapsedMs) PhoneErrorCode.DEADLINE else null,
            )
        }
        val acceptPackage: (String) -> Boolean = { observed ->
            standingAcceptsObservedPackage(
                allApps = allApps,
                launchedPackage = packageName,
                observedPackage = observed,
                allAppsPolicyAllowed =
                    allApps && allAppsPolicy.refusal(observed, allApps = true) == null,
            )
        }
        return observeStandingWithinBudget(
            deadlineElapsedMs = deadlineElapsedMs,
            nowElapsedMs = clock,
            acceptPackage = acceptPackage,
        ) { remainingMs ->
            val core = controller.state.value
            val localFailure =
                when {
                    core.grant?.epoch != epoch -> PhoneErrorCode.EPOCH_CHANGED
                    core.paused -> PhoneErrorCode.PAUSED
                    // A native standing stop cancels this observation; it is never retried.
                    core.standingBlocked -> PhoneErrorCode.CANCELLED
                    core.blocked != null -> PhoneErrorCode.OUTCOME_UNKNOWN
                    else -> null
                }
            if (localFailure != null) {
                return@observeStandingWithinBudget PhoneStandingObserveRead.Failed(localFailure)
            }
            val request =
                PhoneAgentRequest(
                    requestId = "standing-" + UUID.randomUUID(),
                    epoch = epoch,
                    sessionLabel = sessionId,
                    action = PhoneAgentAction.Observe,
                )
            val call =
                try {
                    // The shared remaining budget bounds this controller call; it can never start a
                    // fresh 15 seconds per attempt.
                    controller.execute(request, remainingMs.coerceIn(1L, PhoneLimits.MAX_CALL_MS))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return@observeStandingWithinBudget PhoneStandingObserveRead.Failed(PhoneErrorCode.DRIVER_UNAVAILABLE)
                }
            when (call) {
                is PhoneCallResult.Observed ->
                    PhoneStandingObserveRead.Observed(call.observation.binding.packageName)
                is PhoneCallResult.Refused -> call.error.toStandingObserveRead()
                is PhoneCallResult.Dispatched ->
                    (call.error ?: PhoneErrorCode.DRIVER_UNAVAILABLE).toStandingObserveRead()
                is PhoneCallResult.Captured ->
                    PhoneStandingObserveRead.Failed(PhoneErrorCode.DRIVER_UNAVAILABLE)
            }
        }
    }

    /**
     * Fixed-stage diagnostic for a start failure. It is published only while this start still owns
     * the live epoch or its exact task identity; an old start can never overwrite a newer owner's
     * details. The token contains only the fixed stage/error name, never page/model text or a raw
     * exception, and the previous `lastError` is never reused to describe this attempt.
     */
    private fun publishStandingStartFailure(
        epoch: Long,
        key: PhoneTaskKey?,
        stage: PhoneTaskStartFailureStage,
        error: PhoneErrorCode?,
    ) {
        val core = controller.state.value
        val identity = taskIdentity.current()
        val keyMatches =
            key != null &&
                identity != null &&
                identity.first == key.taskId &&
                identity.second == key.runtimeId
        // A different identity already owns the task (or a newer one is about to): never publish
        // this old attempt's diagnostic over it.
        if (key != null && identity != null && !keyMatches) return
        val liveEpoch = core.grant?.epoch
        val owns =
            when {
                liveEpoch != null -> liveEpoch == epoch
                keyMatches -> true
                else -> false
            }
        if (!owns) return
        mutateFacts {
            it.copy(diagnostics = it.diagnostics.copy(lastError = standingStartFailureToken(stage, error)))
        }
    }

    private fun rollbackStandingStart(
        epoch: Long,
        key: PhoneTaskKey?,
        expectedLease: PhoneLeaseFacts? = null,
    ) {
        val identity = taskIdentity.current()
        val keyMatches = key != null && identity != null && identity.first == key.taskId && identity.second == key.runtimeId
        val identityOwnedByNewerTask = key != null && identity != null && !keyMatches
        val retainedBefore = retainedTaskEpoch
        val exact =
            standingRollbackTargets(
                requestedEpoch = epoch,
                liveEpoch = controller.state.value.grant?.epoch,
                retainedEpoch = retainedBefore,
                identityMatchesRequestedKey = keyMatches,
                identityOwnedByNewerTask = identityOwnedByNewerTask,
            )
        if (!exact) {
            // A newer live task or a newer identity owns the state now. Do not revoke, stop the
            // service, clear the binding or overwrite facts for the old start.
            return
        }
        revokeEpochIfCurrent(epoch)
        serviceStop()
        // Facts/identity belong to this start only after its own binding was published. A
        // cancellation cleanup also refuses to clear a lease that is no longer the one this attempt
        // remembered.
        val leaseIsOurs = expectedLease == null || lease === expectedLease
        val ownsFacts = (keyMatches || retainedBefore == epoch) && leaseIsOurs
        if (ownsFacts) {
            if (keyMatches) {
                taskNative.onTaskBinding(null)
                taskIdentity.clear()
            }
            // This start was never reported as started. A stop that landed during its own startup
            // (for example the host-return stop) produced a halt for this exact epoch; keeping it
            // would project a retained task the person can neither continue nor end and would make
            // every later request BUSY. Clear it only when no action may have been applied.
            val clearHalt =
                rollbackMayClearHalt(
                    haltLastAction = facts.value.halt?.lastAction,
                    retainedEpoch = retainedBefore,
                    requestedEpoch = epoch,
                    identityMatchesRequestedKey = keyMatches,
                )
            lease = null
            taskSession = null
            retainedTaskEpoch = null
            if (facts.value.halt == null || clearHalt) store.clearTask()
            mutateFacts {
                it.copy(
                    lease = null,
                    halt = if (clearHalt) null else it.halt,
                    task = if (clearHalt || it.halt == null) null else store.task(),
                    pending = null,
                    busy = null,
                    continuation = PhoneContinuation(),
                )
            }
            refreshStandingProjection()
        } else if (expectedLease != null && lease === expectedLease) {
            // Cancellation before a task binding: only this attempt's remembered lease exists.
            lease = null
        }
    }

    private fun revokeEpochIfCurrent(epoch: Long) {
        if (controller.state.value.grant?.epoch == epoch) controller.revoke()
    }

    /** The live lease epoch, or null. The foreground service validates every action against it. */
    fun liveLeaseEpoch(): Long? = controller.state.value.grant?.epoch

    /**
     * The stop-entry notification for exactly [epoch], or null when that lease is not live. It shows
     * only the target app's label and a fixed phase line, never the task summary.
     */
    fun serviceNotificationFor(epoch: Long): Notification? {
        val core = controller.state.value
        val grant = core.grant ?: return null
        if (grant.epoch != epoch) return null
        val current = lease ?: return null
        return notifications.running(
            epoch = epoch,
            // In all-apps follow mode the label names the app now in front, not only the launched
            // one. It is display only; the lease/epoch still own authority.
            appLabel = followedTargetLabel(core, current.target),
            paused = core.paused,
            // A pause is only settled once the in-flight action has finished; until then the
            // notification must not claim the agent is paused.
            pausing = core.pausing || (core.paused && core.inFlightMutation),
        )
    }

    /** Display label for the currently followed package; falls back to the launched target. */
    private fun followedTargetLabel(core: PhoneState, launched: PhoneTarget): String {
        if (core.grant?.allApps != true) return launched.label
        val followed =
            core.observation?.binding?.packageName ?: lastObservedPackage ?: return launched.label
        if (followed == launched.packageName) return launched.label
        return facts.value.targets.firstOrNull { it.packageName == followed }?.label
            ?: safeAppLabel(followed, followed)
    }

    /**
     * The stop entry died, was stopped, or could not enter the foreground for [epoch]. Fail closed:
     * revoke exactly that lease (a newer epoch is left alone) and keep the reason for the screen.
     */
    fun onServiceLost(epoch: Long) {
        val core = controller.state.value
        val grant = core.grant ?: return
        if (grant.epoch != epoch) return
        invalidateGrantAttempt()
        halt(PhoneHaltReason.StopChannelLost, actionInFlight = core.inFlightMutation)
        controller.revoke()
        controller.blockStanding(PhoneStandingBlockReason.CONNECTION_LOST)
        serviceStop()
        refreshContinuation()
        mutateFacts { it.copy(stopChannel = notifications.stopChannelState()) }
    }

    // --- pairing ---------------------------------------------------------------------------

    fun beginPairingNotification(host: String) {
        if (!facts.value.available || !facts.value.enabled) return
        if (!notifications.notificationsAllowed()) {
            mutateFacts { it.copy(pairingNotice = PhonePairingNotice.NotificationsBlocked) }
            return
        }
        // A previous attempt may still be blocked inside the driver's synchronized pair(); cancel its
        // real socket first so this draft is not stuck behind it, and never on the main thread.
        invalidatePairing()
        pairing.cancel()
        stopPairingDiscovery()
        autoConnect.disarm()
        notifications.ensureChannels()
        val target = host.trim().ifEmpty { DEFAULT_HOST }
        // Main path: no port. The bounded foreground discovery must report the one local endpoint.
        val draft = pairing.begin(target)
        pairingDiscoveryGeneration = draft.generation
        pairingCompletedGeneration = null
        pairingDiscoveredEndpoint = null
        connectDiscoveredEndpoint = null
        connectDiscoveryStatus = PhoneDiscoveryStatus.Idle
        mutateFacts {
            it.copy(
                pairingNotice = PhonePairingNotice.Waiting,
                hints = it.hints.copy(host = draft.host, pairingPort = null, connectPort = null),
                problem = null,
                pending = null,
                discoveryPairing = PhoneDiscoveryStatus.Searching,
                discoveryConnect = PhoneDiscoveryStatus.Idle,
                autoConnect = PhoneAutoConnect.Idle,
            )
        }
        if (!PhoneDiscoveryService.startPairing(context, draft.generation)) {
            failPairingDiscovery(PhonePairingOutcome.UNAVAILABLE)
            return
        }
        // The notification exists before the system screen opens, so nothing has to be typed later.
        openDeveloperSettings()
        schedulePairingExpiry(draft.generation)
    }

    fun onPairingPortReply(generation: Long, text: String) {
        when (val result = pairing.acceptPort(generation, text)) {
            is PhonePairingSession.PortResult.Accepted -> {
                val draft = result.draft
                val port = draft.pairingPort ?: return
                // configurePairing takes the driver's lock that pair() holds; run it off the main thread.
                scope.launch {
                    val configured =
                        withContext(Dispatchers.IO) {
                            runCatching { driver.configurePairing(draft.host, port) }
                        }
                    // A cancel, disable or newer attempt makes this reply irrelevant.
                    if (!facts.value.enabled || pairing.current()?.generation != generation) return@launch
                    if (configured.isFailure) {
                        failPairing(PhonePairingOutcome.POLICY_REFUSED)
                        return@launch
                    }
                    notifications.notifyPairing(
                        PhonePairNotifications.PAIRING_ID,
                        notifications.pairingCode(generation),
                    )
                    mutateFacts {
                        it.copy(
                            pairingNotice = PhonePairingNotice.Waiting,
                            hints = it.hints.copy(pairingPort = port),
                            problem = null,
                        )
                    }
                }
            }
            PhonePairingSession.PortResult.InvalidPort -> {
                // Keep the same step open so the person can retype; no state error is invented.
                val draft = pairing.current() ?: return
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.pairingPort(draft.generation),
                )
            }
            PhonePairingSession.PortResult.Stale,
            PhonePairingSession.PortResult.Expired,
            -> Unit
        }
    }

    fun onPairingCodeReply(generation: Long, text: String) {
        when (val result = pairing.acceptCode(generation, text)) {
            is PhonePairingSession.CodeResult.Ready -> runPairing(result.draft, result.code)
            PhonePairingSession.CodeResult.InvalidCode -> {
                val draft = pairing.current() ?: return
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.pairingCode(draft.generation),
                )
            }
            PhonePairingSession.CodeResult.Stale,
            PhonePairingSession.CodeResult.Expired,
            PhonePairingSession.CodeResult.NotReady,
            -> Unit
        }
    }

    fun onPairingCancelRequested(generation: Long) {
        val live =
            pairing.current()?.generation
                ?: pairingDiscoveryGeneration
                ?: pairingCompletedGeneration
                ?: return
        // A Cancel minted for an older notification must never end a newer explicit attempt.
        if (generation != live) return
        cancelPairingNotification()
    }

    /** Native retry of the bounded discovery path after a fixed timeout; opens the system screen again. */
    fun retryPairingDiscovery() {
        if (!facts.value.available || !facts.value.enabled) return
        beginPairingNotification(facts.value.hints.host)
    }

    /**
     * The explicit "connect to this phone" action. It opens only a short in-process search for the
     * single current local TLS-connect endpoint; it never scans ports and never runs from screen open.
     */
    fun connectToThisPhone() {
        // A launch reconnect may already be searching. The person's tap adopts it, so the result is
        // reported normally instead of staying silent.
        if (connectSearchActive && connectSearchSilent) {
            connectSearchSilent = false
            return
        }
        if (guardBusy()) return
        if (!facts.value.available || !facts.value.enabled) return
        startConnectSearch()
    }

    /**
     * Background reconnect for a phone link the person already configured. It is called when the
     * local instance is in front with its runtime starting or running, so the chat tool has a device
     * to work with without opening the native phone page (opening it applies the host-return stop).
     *
     * Reuses the same bounded connect discovery and one TLS connect as **Find and connect**. It
     * never pairs, never grants, never changes the standing scope, never starts a task and never
     * restarts the runtime for the plugin. Single-flight per app run; a failure stays silent and
     * leaves the native page's normal connect path untouched.
     */
    fun autoConnectIfConfigured(localRuntimeReady: Boolean) {
        // A connect made before this launch's phone tool registered is revoked when the runtime
        // identity is recorded, so the attempt belongs to one runtime identity, not to the app run.
        val runtimeNow = controller.state.value.runtimeId
        if (launchAutoConnectRuntime != runtimeNow) {
            launchAutoConnectRuntime = runtimeNow
            launchAutoConnectAttempted = false
        }
        if (launchAutoConnectAttempted || launchAutoConnectInFlight) return
        val state = facts.value
        val core = controller.state.value
        // Cheap facts first, so a disabled experiment or a visible link never touches the driver.
        if (!state.enabled || !state.available || state.standingState == null || !localRuntimeReady) return
        if (core.connection == PhoneConnectionState.CONNECTED) return
        if (state.pending == PhonePending.Connecting || connectSearchActive) return
        launchAutoConnectInFlight = true
        scope.launch {
            val hasReceipt =
                withContext(Dispatchers.IO) {
                    runCatching { driver.hasPairingReceipt(DEFAULT_HOST) }.getOrDefault(false)
                }
            // Wait (bounded) until this launch's phone tool is registered and the runtime identity
            // is recorded. Connecting earlier is undone by that registration.
            val toolReady =
                withTimeoutOrNull(AUTO_CONNECT_TOOL_WAIT_MS) {
                    while (
                        runCatching { readiness.toolReady() }.getOrNull() != true ||
                        controller.state.value.runtimeId == null
                    ) {
                        delay(AUTO_CONNECT_TOOL_POLL_MS)
                    }
                    true
                } ?: false
            launchAutoConnectInFlight = false
            if (launchAutoConnectRuntime != controller.state.value.runtimeId) {
                launchAutoConnectRuntime = controller.state.value.runtimeId
                launchAutoConnectAttempted = false
            }
            // Re-check on the main thread: a person may have connected, disabled or paired meanwhile.
            val now = facts.value
            val coreNow = controller.state.value
            if (!shouldAutoConnectOnLaunch(
                    enabled = now.enabled,
                    available = now.available,
                    hasPairingReceipt = hasReceipt,
                    hasStandingScope = now.standingState != null,
                    localRuntimeReady = localRuntimeReady && toolReady,
                    connected = coreNow.connection == PhoneConnectionState.CONNECTED,
                    connecting = now.pending == PhonePending.Connecting || connectSearchActive,
                    attemptedThisRun = launchAutoConnectAttempted,
                )
            ) {
                return@launch
            }
            launchAutoConnectAttempted = true
            startConnectSearch(silent = true)
        }
    }

    /**
     * A READ_TIMEOUT abandoned the exact control connection; the core has already revoked the lease
     * and published the disconnect. This only re-arms the same bounded, silent background reconnect
     * as a launch: one attempt for this event, no lease/grant/resume, no task start and no runtime
     * restart. A pending device operation or an existing search is left alone.
     */
    fun onReadTimeoutDisconnected() {
        launchAutoConnectAttempted = false
        launchAutoConnectInFlight = false
        if (!shouldRearmAfterReadTimeout(
                enabled = facts.value.enabled,
                available = facts.value.available,
                pending = facts.value.pending != null,
                searching = connectSearchActive,
            )
        ) {
            return
        }
        startConnectSearch(silent = true)
    }

    fun openWirelessDebuggingSettings() {
        openDeveloperSettings()
    }

    private fun cancelPairingNotification() {
        invalidatePairing()
        pairing.cancel()
        notifications.cancelPairing()
        pairingDiscoveryGeneration = null
        pairingCompletedGeneration = null
        pairingDiscoveredEndpoint = null
        connectDiscoveredEndpoint = null
        connectDiscoveryStatus = PhoneDiscoveryStatus.Idle
        autoConnect.disarm()
        stopPairingDiscovery()
        cancelConnectSearch(clearPending = false)
        mutateFacts {
            it.copy(
                pairingNotice = PhonePairingNotice.Idle,
                pending = null,
                discoveryPairing = PhoneDiscoveryStatus.Idle,
                discoveryConnect = PhoneDiscoveryStatus.Idle,
                autoConnect = PhoneAutoConnect.Idle,
            )
        }
    }

    /**
     * Makes every in-flight pairing result stale and closes a blocked driver socket.
     *
     * This cancels the local exchange only; it never claims that the device's system pairing window or
     * any authorization it granted was revoked.
     */
    private fun invalidatePairing() {
        pairingOperationSeq += 1
        activePairingOperation = null
        runCatching { driver.cancelPairing() }
    }

    private fun schedulePairingExpiry(generation: Long) {
        scope.launch {
            delay(PhonePairingSession.DEFAULT_TTL_MS + 1_000L)
            val expired = pairing.expireIfNeeded()
            if (expired == generation) {
                invalidatePairing()
                notifications.cancelPairing()
                pairingDiscoveryGeneration = null
                pairingCompletedGeneration = null
                pairingDiscoveredEndpoint = null
                connectDiscoveredEndpoint = null
                connectDiscoveryStatus = PhoneDiscoveryStatus.Idle
                autoConnect.disarm()
                stopPairingDiscovery()
                mutateFacts {
                    it.copy(
                        pairingNotice = PhonePairingNotice.Idle,
                        pending = null,
                        discoveryPairing = PhoneDiscoveryStatus.TimedOut,
                        discoveryConnect = PhoneDiscoveryStatus.Idle,
                    )
                }
            }
        }
    }

    private fun runPairing(draft: PhonePairingSession.Draft, code: CharArray) {
        val operation = ++pairingOperationSeq
        activePairingOperation = operation
        notifications.notifyPairing(
            PhonePairNotifications.PAIRING_ID,
            notifications.pairingBusy(draft.generation),
        )
        mutateFacts { it.copy(pending = PhonePending.Pairing, problem = null) }
        scope.launch {
            val outcome =
                try {
                    withContext(Dispatchers.IO) { driver.pair(code, PAIRING_DEADLINE_MS) }
                    PhonePairingOutcome.PAIRED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AdbPairingException) {
                    pairingOutcome(failure.failure)
                } catch (_: Exception) {
                    PhonePairingOutcome.UNAVAILABLE
                } finally {
                    code.fill('\u0000')
                }
            // Cancel, disable or a newer attempt invalidates this result. A late success must never
            // cancel the newer notification, mark the UI paired or finish a newer draft.
            val current =
                pairingResultIsCurrent(
                    operation = operation,
                    activeOperation = activePairingOperation,
                    draftGeneration = draft.generation,
                    currentGeneration = pairing.current()?.generation,
                    enabled = facts.value.enabled,
                )
            if (!current) return@launch
            activePairingOperation = null
            pairing.finish(draft.generation)
            if (outcome == PhonePairingOutcome.PAIRED) {
                // A receipt/real TLS pairing success. This is not a connection and grants nothing.
                pairingCompletedGeneration = draft.generation
                pairingDiscoveredEndpoint = null
                connectDiscoveredEndpoint = null
                connectDiscoveryStatus = PhoneDiscoveryStatus.Searching
                autoConnect.arm(draft.generation)
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.paired(),
                )
                mutateFacts {
                    it.copy(
                        paired = true,
                        problem = null,
                        pairingNotice = PhonePairingNotice.Idle,
                        pending = null,
                        discoveryPairing = PhoneDiscoveryStatus.Idle,
                        discoveryConnect = PhoneDiscoveryStatus.Searching,
                    )
                }
                // Search the connect type only now, after pairing really succeeded.
                if (!PhoneDiscoveryService.requestConnectPhase(draft.generation)) {
                    abandonAutoConnect(
                        draft.generation,
                        PhoneDiscoveryStatus.Unavailable,
                    )
                    return@launch
                }
                tryAutoConnectAfterPairing()
            } else {
                // Fixed local error. This never claims the device's system authorization was revoked.
                pairingDiscoveryGeneration = null
                pairingCompletedGeneration = null
                pairingDiscoveredEndpoint = null
                connectDiscoveredEndpoint = null
                autoConnect.disarm()
                stopPairingDiscovery()
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.pairingFailed(outcome),
                )
                mutateFacts {
                    it.copy(
                        paired = false,
                        problem = problemFor(outcome),
                        pairingNotice = PhonePairingNotice.Idle,
                        pending = null,
                        busy = null,
                        discoveryPairing = PhoneDiscoveryStatus.Idle,
                        discoveryConnect = PhoneDiscoveryStatus.Idle,
                    )
                }
            }
        }
    }

    private fun pairManually(request: PhonePairRequest) {
        if (guardBusy()) return
        val code = PhonePairingSession.sanitizePairingCode(request.code)
        if (code == null) {
            mutateFacts { it.copy(problem = PhoneDeviceProblem.PairingCodeRejected) }
            return
        }
        // Manual pairing supersedes any notification attempt; the draft must not stay waiting.
        invalidatePairing()
        pairing.cancel()
        notifications.cancelPairing()
        pairingDiscoveryGeneration = null
        pairingCompletedGeneration = null
        pairingDiscoveredEndpoint = null
        connectDiscoveredEndpoint = null
        connectDiscoveryStatus = PhoneDiscoveryStatus.Idle
        autoConnect.disarm()
        stopPairingDiscovery()
        cancelConnectSearch(clearPending = false)
        val operation = ++pairingOperationSeq
        activePairingOperation = operation
        mutateFacts { it.copy(pending = PhonePending.Pairing, problem = null, busy = null) }
        scope.launch {
            val outcome =
                try {
                    withContext(Dispatchers.IO) {
                        driver.configurePairing(request.host.trim().ifEmpty { DEFAULT_HOST }, request.port)
                        driver.pair(code, PAIRING_DEADLINE_MS)
                    }
                    PhonePairingOutcome.PAIRED
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: AdbPairingException) {
                    pairingOutcome(failure.failure)
                } catch (_: AdbDeviceException) {
                    PhonePairingOutcome.POLICY_REFUSED
                } catch (_: Exception) {
                    PhonePairingOutcome.UNAVAILABLE
                } finally {
                    code.fill('\u0000')
                }
            // A cancel or disable makes this result irrelevant; it must not change the UI.
            if (!facts.value.enabled || activePairingOperation != operation) return@launch
            activePairingOperation = null
            if (outcome == PhonePairingOutcome.PAIRED) {
                notifications.notifyPairing(
                    PhonePairNotifications.PAIRING_ID,
                    notifications.paired(),
                )
            }
            mutateFacts {
                it.copy(
                    pending = null,
                    paired = outcome == PhonePairingOutcome.PAIRED,
                    problem = problemFor(outcome),
                    hints =
                        it.hints.copy(
                            host = request.host.trim().ifEmpty { DEFAULT_HOST },
                            pairingPort = request.port,
                        ),
                    discoveryPairing = PhoneDiscoveryStatus.Idle,
                    discoveryConnect = PhoneDiscoveryStatus.Idle,
                )
            }
        }
    }

    private fun refreshPairingState() {
        if (!facts.value.available) return
        val host = facts.value.hints.host
        scope.launch {
            val paired = withContext(Dispatchers.IO) { runCatching { driver.hasPairingReceipt(host) }.getOrDefault(false) }
            mutateFacts { it.copy(paired = paired) }
        }
    }

    /** Fixed local failure: cancels the draft and shows one error; no system trust is claimed. */
    private fun failPairing(outcome: PhonePairingOutcome) {
        invalidatePairing()
        pairing.cancel()
        pairingDiscoveryGeneration = null
        pairingCompletedGeneration = null
        pairingDiscoveredEndpoint = null
        connectDiscoveredEndpoint = null
        autoConnect.disarm()
        stopPairingDiscovery()
        notifications.notifyPairing(
            PhonePairNotifications.PAIRING_ID,
            notifications.pairingFailed(outcome),
        )
        mutateFacts {
            it.copy(
                pending = null,
                paired = false,
                problem = problemFor(outcome),
                pairingNotice = PhonePairingNotice.Idle,
            )
        }
    }

    // --- device link -----------------------------------------------------------------------

    private fun connect(request: PhoneConnectRequest) {
        if (guardBusy()) return
        if (!facts.value.available) {
            mutateFacts { it.copy(problem = PhoneDeviceProblem.Other) }
            return
        }
        if (request.port <= 0) {
            // The person asked to connect to this phone; a separate bounded search finds the one local
            // TLS-connect endpoint. No port is typed on the main path.
            startConnectSearch()
            return
        }
        val host = request.host.trim().ifEmpty { DEFAULT_HOST }
        mutateFacts {
            it.copy(
                pending = PhonePending.Connecting,
                problem = null,
                busy = null,
                autoConnect = PhoneAutoConnect.Idle,
            )
        }
        scope.launch {
            val paired = withContext(Dispatchers.IO) { runCatching { driver.hasPairingReceipt(host) }.getOrDefault(false) }
            if (!paired) {
                mutateFacts {
                    it.copy(
                        pending = null,
                        paired = false,
                        problem = PhoneDeviceProblem.NotAuthorized,
                        hints = it.hints.copy(host = host, connectPort = request.port),
                    )
                }
                return@launch
            }
            // Await the teardown a runtime change may have left behind, then select the endpoint in the
            // same IO block. Both are awaited inside this connect operation: a late close can never
            // race the connection this attempt is about to make.
            val configured =
                withContext(Dispatchers.IO) {
                    runCatching {
                        driver.prepareForConnect(PhoneLimits.DEFAULT_CALL_MS)
                        driver.configure(host, request.port)
                    }
                }
            if (configured.isFailure) {
                mutateFacts {
                    it.copy(
                        pending = null,
                        problem = PhoneDeviceProblem.Other,
                        hints = it.hints.copy(host = host, connectPort = request.port),
                    )
                }
                return@launch
            }
            val result = runCatching { controller.connect(PhoneLimits.DEFAULT_CALL_MS) }
                .getOrElse { PhoneConnectionResult.Failed(PhoneErrorCode.DRIVER_UNAVAILABLE) }
            when (result) {
                is PhoneConnectionResult.Connected ->
                    mutateFacts {
                        it.copy(
                            pending = null,
                            paired = true,
                            problem = null,
                            hints = it.hints.copy(host = host, connectPort = request.port),
                        )
                    }
                is PhoneConnectionResult.Failed ->
                    mutateFacts {
                        it.copy(
                            pending = null,
                            problem = problemFor(result.error),
                            hints = it.hints.copy(host = host, connectPort = request.port),
                        )
                    }
            }
        }
    }

    private fun disconnect() {
        if (guardBusy()) return
        invalidateGrantAttempt()
        cancelPairingNotification()
        cancelConnectSearch(clearPending = false)
        autoConnect.disarm()
        mutateFacts {
            it.copy(
                pending = PhonePending.Disconnecting,
                busy = null,
                autoConnect = PhoneAutoConnect.Idle,
            )
        }
        scope.launch {
            controller.disconnect()
            closeLease(keepTask = true)
            mutateFacts {
                it.copy(
                    pending = null,
                    lease = null,
                    halt = null,
                    problem = null,
                )
            }
            serviceStop()
        }
    }

    // --- grant and stop controls -------------------------------------------------------------

    private fun grant(request: PhoneGrantRequest) {
        if (guardBusy()) return
        val current = facts.value
        if (!current.available) return
        val core = controller.state.value
        if (core.connection != PhoneConnectionState.CONNECTED) {
            mutateFacts { it.copy(problem = PhoneDeviceProblem.ConnectRefused) }
            return
        }
        if (core.inFlightMutation || core.pausing) {
            // A new grant would clear the controller's unknown-outcome block while the previous
            // action may still settle; wait for it instead of silently replacing the lease.
            mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
            return
        }
        if (keyguardLocked()) {
            // A locked screen cannot show the target app; refuse instead of granting into the lock.
            mutateFacts {
                it.copy(
                    problem = PhoneDeviceProblem.Other,
                    diagnostics = it.diagnostics.copy(lastError = PhoneDiagnosticTokens.SCREEN_LOCKED),
                )
            }
            return
        }
        val stopChannel = notifications.stopChannelState()
        if (stopChannel != PhoneStopChannel.Verified) {
            mutateFacts { it.copy(stopChannel = stopChannel) }
            return
        }
        val target =
            current.targets.firstOrNull { it.packageName == request.target.packageName }
                ?: request.target.takeIf { it.packageName == current.hostPackage }
        if (target == null) {
            // Unknown package: not in the native picker, so it is not a target.
            mutateFacts {
                it.copy(
                    problem = PhoneDeviceProblem.Other,
                    diagnostics = it.diagnostics.copy(lastError = PhoneDiagnosticTokens.TARGET_UNAVAILABLE),
                )
            }
            return
        }
        // The agent tool must already be loaded by this launch. Blocking here keeps the person's
        // task draft and the agent's proposal intact; nothing is consumed or cleared on this path.
        if (refreshAgentToolBlocker() != null) {
            mutateFacts { it.copy(busy = null) }
            return
        }
        val kinds = actionKinds(request.actions)
        if (kinds.isEmpty()) return
        // Freeze the confirmed object now, before any suspension. Waiting for the foreground stop
        // entry must never change what the person confirmed: the displayed proposal revision (null
        // for a manual task) and, for a resume, the chat that owned the task.
        val frozenRevision = facts.value.requestRevision
        val haltAtConfirm = facts.value.halt
        val resumingAtConfirm =
            haltAtConfirm != null &&
                // A different runtime is a new task: an old task must never own the new grant.
                haltAtConfirm.reason != PhoneHaltReason.RuntimeChanged &&
                haltAtConfirm.lease.target.packageName == target.packageName &&
                haltAtConfirm.lease.summary == request.summary.trim().take(PhoneLimits.MAX_NODE_LABEL_CHARS)
        val frozenResumeSession = if (resumingAtConfirm) taskSession else null
        // Freeze this confirmation as the only live attempt. A later stop invalidates it; a newer
        // grant replaces it. The controller epoch check remains independent.
        val attempt = grantAttempts.begin()
        // A fresh attempt supersedes the previous explanation; only a new failure may set one.
        mutateFacts {
            it.copy(
                pending = PhonePending.Granting,
                busy = null,
                problem = null,
                diagnostics = it.diagnostics.copy(lastError = null),
            )
        }
        // Order is part of the guarantee: admit the epoch, prove the foreground stop entry, publish the
        // route for exactly one chat, then launch the app. Any current failure revokes what was created;
        // an invalidated continuation touches nothing.
        scope.launch {
            if (!grantAttempts.isCurrent(attempt)) return@launch
            when (val result = controller.grant(target.packageName, kinds, request.durationMs)) {
                is PhoneGrantResult.Granted -> {
                    if (!grantAttempts.isCurrent(attempt)) {
                        // A stop control ran while this coroutine was queued. Revoke only the epoch this
                        // attempt created; never touch the newer task, service or facts.
                        revokeAttemptEpoch(result.grant.epoch)
                        return@launch
                    }
                    completeGrant(
                        attempt = attempt,
                        granted = result.grant,
                        request = request,
                        target = target,
                        stopChannel = stopChannel,
                        frozenRevision = frozenRevision,
                        frozenResumeSession = frozenResumeSession,
                        resumedTask = resumingAtConfirm,
                    )
                }
                is PhoneGrantResult.Refused -> {
                    if (!grantAttempts.finish(attempt)) return@launch
                    mutateFacts {
                        it.copy(
                            pending = null,
                            problem = problemFor(result.error),
                        )
                    }
                }
            }
        }
    }

    private suspend fun completeGrant(
        attempt: PhoneGrantAttemptOwner.Attempt,
        granted: PhoneGrant,
        request: PhoneGrantRequest,
        target: PhoneTarget,
        stopChannel: PhoneStopChannel,
        /** The proposal revision displayed when the person confirmed; null for a manual task. */
        frozenRevision: Long?,
        /** The chat that owned a resumed task, captured at confirmation; null for a new task. */
        frozenResumeSession: String?,
        /** True when the person confirmed a resume of the retained task, in the same runtime. */
        resumedTask: Boolean,
    ) {
        if (!grantAttempts.isCurrent(attempt)) return
        val epoch = granted.epoch
        val remembered =
            PhoneLeaseFacts(
                target = target,
                summary = request.summary.trim().take(PhoneLimits.MAX_NODE_LABEL_CHARS),
                actions = request.actions,
            )
        // The service notification needs the target label; the lease is memory-only display data and
        // is not authority. Record it before asking the service to show it, and clear it on failure.
        lease = remembered
        // 1) + 2) Prove the foreground stop entry, re-check the frozen epoch and revision, then publish
        // the route exactly once. The frozen revision is the one the screen displayed at confirmation;
        // waiting for the service never changes the confirmed object. Every step is also gated by the
        // attempt owner, so a stop that happened during the wait touches nothing.
        val outcome =
            runGrantAttempt(
                owner = grantAttempts,
                attempt = attempt,
                epoch = epoch,
                frozenRevision = frozenRevision,
                frozenResumeSession = frozenResumeSession,
                awaitStopEntry = { serviceStart(epoch) && serviceAwaitReady(epoch) },
                currentEpoch = { controller.state.value.grant?.epoch },
                currentRevision = { facts.value.requestRevision },
                consume = { proposals.consume(it) },
                publish = { routeEpoch, label ->
                    // Publish the native task owner before a chat can claim this route. The first real
                    // action then resolves the same frozen key synchronously, not after a UI collector.
                    val runtimeId =
                        controller.state.value.runtimeId ?: PhoneDriverFacade.FALLBACK_RUNTIME_ID
                    val ownerReady =
                        runCatching {
                            val binding =
                                taskIdentity.bindOnPublish(
                                    epoch = routeEpoch,
                                    resumed = resumedTask,
                                    runtimeId = runtimeId,
                                    routeSession = label,
                                    summary = remembered.summary,
                                    packageName = target.packageName,
                                )
                            taskNative.onTaskBinding(binding)
                        }.isSuccess
                    val published =
                        ownerReady &&
                            runCatching {
                                grantRoutes.publishGrantRoute(routeEpoch, label)
                            }.getOrDefault(false)
                    if (ownerReady) retainedTaskEpoch = routeEpoch
                    // A grant whose route could not be published is revoked; it owns no task.
                    if (!published) runCatching { taskNative.onTaskBinding(null) }
                    published
                },
            )
        applyGrantAttemptOutcome(
            owner = grantAttempts,
            attempt = attempt,
            outcome = outcome,
            commit = { confirmedSession ->
                commitGrant(
                    confirmedSession = confirmedSession,
                    target = target,
                    stopChannel = stopChannel,
                    remembered = remembered,
                )
            },
            fail = { error -> failGrant(epoch, error) },
        )
    }

    /** Commits a grant whose attempt was already claimed by [applyGrantAttemptOutcome]. */
    private fun commitGrant(
        confirmedSession: String?,
        target: PhoneTarget,
        stopChannel: PhoneStopChannel,
        remembered: PhoneLeaseFacts,
    ) {
        taskSession = confirmedSession
        store.setTask(
            PhoneTaskRecord(
                targetPackage = target.packageName,
                summary = remembered.summary,
                needsCheck = true,
            ),
        )
        mutateFacts {
            it.copy(
                pending = null,
                halt = null,
                lease = remembered,
                task = store.task(),
                request = null,
                requestRevision = null,
                stopChannel = stopChannel,
                problem = null,
                agentToolBlocker = null,
            )
        }
        if (!launchTarget(target.packageName)) {
            halt(PhoneHaltReason.TargetLeft)
            controller.revoke()
            serviceStop()
            mutateFacts { it.copy(pending = null, lease = null) }
        }
    }

    /**
     * Fail-closed cleanup for the current grant attempt that never became usable. The caller has
     * already claimed the attempt, so this cannot erase a newer grant, service, task or facts.
     */
    private fun failGrant(epoch: Long?, error: String) {
        // Revoke only the epoch this attempt created: a newer grant must survive a late failure.
        val current = controller.state.value.grant
        if (epoch == null || current?.epoch == epoch) controller.revoke()
        closeLease(keepTask = false)
        taskSession = null
        serviceStop()
        mutateFacts {
            it.copy(
                pending = null,
                lease = null,
                halt = null,
                task = null,
                request = null,
                requestRevision = null,
                busy = null,
                problem = PhoneDeviceProblem.Other,
                diagnostics = it.diagnostics.copy(lastError = error),
            )
        }
    }

    /**
     * A stop control invalidates the frozen confirmation before it touches the service or task.
     * Settling the attempt's own pending here is what keeps the screen from staying "Granting";
     * a newer grant has already replaced the owner, so its pending is not touched.
     */
    private fun invalidateGrantAttempt() {
        val invalidated = grantAttempts.invalidate() ?: return
        val nextPending = pendingAfterGrantInvalidation(invalidated, facts.value.pending)
        if (nextPending != facts.value.pending) {
            mutateFacts { it.copy(pending = nextPending) }
        }
    }

    /** Core-only cleanup for a grant created after its attempt was invalidated. Never native state. */
    private fun revokeAttemptEpoch(epoch: Long) {
        if (controller.state.value.grant?.epoch == epoch) controller.revoke()
    }

    private fun pause() {
        invalidateGrantAttempt()
        val core = controller.state.value
        if (core.grant == null) return
        halt(PhoneHaltReason.Paused)
        controller.pause()
        serviceStart(core.epoch)
    }

    /**
     * "I'll take over" is a stop, not a pause. A paused lease still allows Observe/Capture, so the
     * whole lease is revoked (cancelling any in-flight read) before the confirmed app is opened.
     * A remembered task without a live grant still opens the app; it never grants again.
     */
    private fun takeOver() {
        invalidateGrantAttempt()
        val core = controller.state.value
        val remembered = lease ?: facts.value.halt?.lease
        val plan = takeoverPlan(hasGrant = core.grant != null, rememberedTarget = remembered?.target?.packageName)
        if (plan.revokeGrant) {
            val binding = overlayTaskBinding()
            halt(
                PhoneHaltReason.TakenOver,
                remembered,
                actionInFlight = core.inFlightMutation || core.pausing,
            )
            controller.revoke()
            controller.blockStanding(PhoneStandingBlockReason.TAKEOVER)
            serviceStop()
            publishTaskStop(binding, PhoneProgressReason.TAKEOVER, ended = false)
            refreshContinuation()
        }
        plan.launchTarget?.let { launchTarget(it) }
    }

    /**
     * Native phone screen "End". It freezes the exact current task before any field is cleared,
     * saves the native needs-check record/revokes through the existing exact-owner end path, then
     * publishes exactly one native ended fact so an UNKNOWN task becomes ENDED_PENDING_CHECK and its
     * result card can be shown/closed. The model finish and overlay End keep their own typed flows.
     */
    fun finishFromNativeUi() {
        nativeUiEnd.endFromNativeUi()
    }

    /**
     * The exact task for a native End. A live grant wins (its binding carries the route); otherwise
     * the retained epoch + task identity + persisted/remembered record reconstruct it. Null means
     * there is no current task to end; the caller then keeps the original cleanup without inventing
     * a native end.
     */
    private fun nativeUiEndBinding(): PhoneOverlayTaskBinding? {
        overlayTaskBinding()?.let { return it }
        val epoch = retainedTaskEpoch ?: return null
        val identity = taskIdentity.current() ?: return null
        val remembered = lease ?: facts.value.halt?.lease
        val record = store.task()
        val summary = remembered?.summary ?: record?.summary ?: return null
        val packageName = remembered?.target?.packageName ?: record?.targetPackage ?: return null
        return PhoneOverlayTaskBinding(
            taskId = identity.first,
            runtimeId = identity.second,
            epoch = epoch,
            routeSession = taskSession,
            summary = summary,
            packageName = packageName,
        )
    }

    /**
     * Ends the task. The grant is revoked by the controller, so the coordinator keeps the minimal
     * record (target, summary, needs-check) for the result card; it never claims a verified result.
     * Internal callers do not publish a native end fact here; the native UI entry does that once.
     */
    private fun finish(agentReported: Boolean = false) {
        invalidateGrantAttempt()
        val remembered = lease
        controller.pause()
        if (remembered != null) {
            store.setTask(
                PhoneTaskRecord(
                    targetPackage = remembered.target.packageName,
                    summary = remembered.summary,
                    needsCheck = true,
                    haltReason = facts.value.halt?.reason?.name,
                    acknowledged = facts.value.halt?.acknowledged == true,
                    agentReported = agentReported && facts.value.halt == null,
                ),
            )
        }
        controller.revoke()
        taskSession = null
        retainedTaskEpoch = null
        setContinuationDelivery(PhoneContinueDelivery.Idle)
        mutateFacts {
            it.copy(
                halt = null,
                lease = null,
                task = store.task(),
                pending = null,
                busy = null,
                continuation = PhoneContinuation(),
            )
        }
        serviceStop()
    }

    private fun acknowledgeChecked() {
        controller.acknowledgeUnknownOutcome()
        val current = facts.value.halt ?: return
        mutateFacts {
            it.copy(
                halt = current.copy(lastAction = PhoneLastAction.UserChecked, acknowledged = true),
                task = store.task()?.copy(acknowledged = true),
            )
        }
        store.task()?.let { store.setTask(it.copy(acknowledged = true)) }
        refreshContinuation()
    }

    private fun dismissResult() {
        store.clearTask()
        taskSession = null
        taskIdentity.clear()
        retainedTaskEpoch = null
        taskNative.onTaskBinding(null)
        setContinuationDelivery(PhoneContinueDelivery.Idle)
        mutateFacts { it.copy(task = null, continuation = PhoneContinuation()) }
    }

    private fun observeTarget() {
        val core = controller.state.value
        if (core.grant == null || core.paused) {
            // No lease: never read the target screen quietly. The screen shows the connect/grant step.
            mutateFacts { it.copy(problem = PhoneDeviceProblem.Other) }
            return
        }
        if (guardBusy()) return
        mutateFacts { it.copy(pending = PhonePending.Observing, busy = null) }
        val request =
            PhoneAgentRequest(
                requestId = "native-" + UUID.randomUUID().toString(),
                epoch = core.epoch,
                sessionLabel = NATIVE_SESSION,
                action = PhoneAgentAction.Observe,
            )
        scope.launch {
            val result =
                runCatching { controller.execute(request, PhoneLimits.DEFAULT_CALL_MS) }
                    .getOrElse {
                        PhoneCallResult.Refused(
                            request.requestId,
                            request.epoch,
                            PhoneErrorCode.DRIVER_UNAVAILABLE,
                        )
                    }
            val error =
                when (result) {
                    is PhoneCallResult.Refused -> result.error
                    is PhoneCallResult.Dispatched -> result.error
                    else -> null
                }
            // The driver verified the window; a mismatch means the target app left the screen. The
            // native side stops instead of pretending the observation failed harmlessly.
            if (facts.value.halt == null) {
                val stopReason =
                    when (error) {
                        // Explicit mode: another package is a target loss. All-apps follow mode:
                        // an allowed handoff is not a loss, but a refused secure surface is.
                        PhoneErrorCode.TARGET_MISMATCH,
                        PhoneErrorCode.TARGET_FORBIDDEN,
                        -> PhoneHaltReason.TargetLeft
                        PhoneErrorCode.WINDOW_CHANGED, PhoneErrorCode.GEOMETRY_CHANGED,
                        PhoneErrorCode.STALE_OBSERVATION,
                        -> PhoneHaltReason.ScreenUncertain
                        else -> null
                    }
                if (stopReason != null) {
                    halt(stopReason, actionInFlight = false)
                    controller.pause()
                    controller.blockStanding(PhoneStandingBlockReason.UNKNOWN_OUTCOME)
                    runCatching { serviceStart(controller.state.value.epoch) }
                    refreshContinuation()
                }
            }
            mutateFacts {
                it.copy(
                    pending = null,
                    diagnostics =
                        it.diagnostics.copy(
                            lastError = error?.name,
                            observedAtMs = (result as? PhoneCallResult.Observed)?.observation?.observedAtMs
                                ?: it.diagnostics.observedAtMs,
                            observedNodes = (result as? PhoneCallResult.Observed)?.observation?.nodes?.size
                                ?: it.diagnostics.observedNodes,
                        ),
                )
            }
        }
    }

    // --- enable/disable ---------------------------------------------------------------------

    private fun setEnabled(value: Boolean) {
        if (!facts.value.available) return
        if (value) {
            store.setEnabled(true)
            controller.enable()
            mutateFacts { it.copy(enabled = true, problem = null, busy = null) }
            recheckStopChannel()
            refreshAgentToolBlocker()
            loadTargets()
            refreshPairingState()
            refreshStandingProjection()
            refreshContinuation()
        } else {
            store.setEnabled(false)
            invalidateGrantAttempt()
            cancelPairingNotification()
            cancelConnectSearch(clearPending = false)
            autoConnect.disarm()
            controller.disable()
            scope.launch { runCatching { controller.disconnect() } }
            closeLease(keepTask = false)
            taskSession = null
            retainedTaskEpoch = null
            standingBinding = PhoneStandingBindingState()
            standingBindingEverUsed = false
            setContinuationDelivery(PhoneContinueDelivery.Idle)
            serviceStop()
            notifications.cancelPairing()
            mutateFacts { it.copy(agentToolBlocker = null) }
            mutateFacts {
                it.copy(
                    enabled = false,
                    lease = null,
                    halt = null,
                    task = null,
                    targets = emptyList(),
                    request = null,
                    pending = null,
                    busy = null,
                    continuation = PhoneContinuation(),
                    pairingNotice = PhonePairingNotice.Idle,
                    hints = PhoneEndpointHints(host = it.hints.host),
                    discoveryPairing = PhoneDiscoveryStatus.Idle,
                    discoveryConnect = PhoneDiscoveryStatus.Idle,
                    autoConnect = PhoneAutoConnect.Idle,
                )
            }
            refreshStandingProjection()
        }
    }

    private fun recheckStopChannel() {
        mutateFacts { it.copy(stopChannel = notifications.stopChannelState()) }
    }

    /**
     * Re-reads the two native probes and publishes the one missing step, if any. Only reads: the
     * runtime is never restarted here and no grant is created or extended.
     */
    private fun refreshAgentToolBlocker(): PhoneAgentToolBlocker? {
        val presence = runCatching { readiness.runtimePresence() }.getOrDefault(PhoneRuntimePresence.UNKNOWN)
        val toolReady = runCatching { readiness.toolReady() }.getOrNull()
        val blocker =
            runCatching {
                agentToolBlocker(
                    enabled = facts.value.enabled,
                    presence = presence,
                    toolReady = toolReady,
                )
            }.getOrNull()
        val runtime = phoneRuntimeStatus(presence, toolReady, standingRestarting)
        mutateFacts {
            if (it.agentToolBlocker == blocker && it.runtimeStatus == runtime) {
                it
            } else {
                it.copy(agentToolBlocker = blocker, runtimeStatus = runtime)
            }
        }
        return blocker
    }

    /**
     * One explicit, narrow restart completion. It only clears a `RUNTIME_CHANGED` standing block
     * for the first, never-bound configuration with no task owner, after the new runtime is really
     * running and the tool handshake is ready. It uses the strict `resumeStanding`, never the
     * person-continuation primitive, so pause/unknown are untouched.
     */
    private fun finishConfirmedRestartIfReady() {
        if (!standingRestarting) return
        val presence = runCatching { readiness.runtimePresence() }.getOrDefault(PhoneRuntimePresence.UNKNOWN)
        val toolReady = runCatching { readiness.toolReady() }.getOrNull()
        if (presence != PhoneRuntimePresence.RUNNING || toolReady != true) {
            if (clock() >= standingRestartDeadlineMs) completeConfirmedRestart()
            return
        }
        val core = controller.state.value
        val runtimeId = core.runtimeId
        // Only a different runtime identity proves the confirmed restart happened. A still-old
        // runtime (or an unknown handshake) must never trigger an early completion.
        val newRuntime = runtimeId != null && runtimeId != standingRestartFromRuntimeId
        if (!newRuntime) {
            if (clock() >= standingRestartDeadlineMs) completeConfirmedRestart()
            return
        }
        val state = standingState
        val nowElapsed = clock()
        val nowWall = wallClock()
        val active =
            state != null &&
                !state.config.revoked &&
                state.config.isActive(nowWall) &&
                !state.expired &&
                state.endsAtElapsedMs > nowElapsed
        val mayClear =
            confirmedRestartMayClearRuntimeBlock(
                restartConfirmed = standingRestarting,
                restartRevision = standingRestartRevision,
                currentRevision = standingRevision,
                configActive = active,
                bindingNeverBound =
                    !standingBindingEverUsed &&
                        standingBinding.runtimeId == null &&
                        standingBinding.sessionLabel == null,
                standingBlocked = core.standingBlocked,
                standingBlockReason = core.standingBlockReason,
                controllerEnabled = core.enabled,
                controllerPaused = core.paused,
                controllerUnknownBlocked = core.blocked != null,
                runtimeId = runtimeId,
                runtimeRunning = presence == PhoneRuntimePresence.RUNNING,
                toolReady = toolReady == true,
                hasLiveGrant = core.grant != null,
                hasHalt = facts.value.halt != null,
                hasRetainedTask = retainedTaskEpoch != null,
                hasLease = facts.value.lease != null,
                // A closed result card (record only, no halt/retained epoch/identity/lease) is
                // evidence for the person, not a task that owns the scope: it never blocks this.
                hasTaskOwner = taskIdentity.current() != null,
            )
        if (mayClear && runtimeId != null) {
            controller.resumeStanding(
                PhoneStandingResume(
                    runtimeId = runtimeId,
                    sessionLabel = "",
                    taskRevision = standingRevision,
                ),
            )
        }
        // The confirmed restart itself is complete once the new runtime is up and the handshake is
        // ready. If the gate refused, the standing block stays and the person must re-save/continue.
        completeConfirmedRestart()
    }

    /** Ends the restarting UI state without touching any authority; the gate above owns the block. */
    private fun completeConfirmedRestart() {
        standingRestarting = false
        standingRestartDeadlineMs = 0L
        standingRestartRevision = null
        standingRestartFromRuntimeId = null
        refreshAgentToolBlocker()
    }

    private fun refreshStandingProjection() {
        val nowElapsed = clock()
        val nowWall = wallClock()
        val state = standingState
        val expired =
            state != null &&
                (state.expired || state.endsAtElapsedMs <= nowElapsed || state.config.windowEndsAtWallMs <= nowWall)
        if (state != null && expired != state.expired) {
            standingState = state.copy(expired = expired)
        }
        publishStandingView()
        // The all-apps listing needs the launcher catalogue, which the screen normally loads when it
        // opens. A launch with a restored all-apps scope (no screen yet) still needs it for `apps`.
        if (standingState?.config?.allApps == true && !launchableCatalogRequested) loadTargets()
        mutateFacts {
            if (it.standingEnabled == available && it.standingState == standingState) {
                it
            } else {
                it.copy(standingEnabled = available, standingState = standingState)
            }
        }
    }

    /** Publishes the volatile tool projection. It carries no authority and is never persisted. */
    private fun publishStandingView() {
        val nowElapsed = clock()
        val nowWall = wallClock()
        standingViewCache =
            standingState
                ?.takeIf {
                    !it.expired &&
                        !it.config.revoked &&
                        it.endsAtElapsedMs > nowElapsed &&
                        it.config.isActive(nowWall)
                }
                ?.let { state ->
                    PhoneStandingView(
                        apps = state.config.apps,
                        actions = state.config.actions,
                        windowEndsAtMs = state.endsAtElapsedMs,
                        allApps = state.config.allApps,
                        launchableApps = if (state.config.allApps) launchableAppsCache else emptyList(),
                        catalogueTruncated = state.config.allApps && launchableAppsTruncated,
                    )
                }
    }

    private fun setContinuationDelivery(
        delivery: PhoneContinueDelivery,
        failure: PhoneContinueFailure? = null,
        stage: PhoneTaskStartFailureStage? = null,
        error: PhoneErrorCode? = null,
    ) {
        continuationDelivery = delivery
        val notResumed = delivery == PhoneContinueDelivery.NotResumed
        continuationFailure = if (notResumed) failure ?: PhoneContinueFailure.Other else null
        continuationFailureStage = if (notResumed) stage else null
        continuationFailureError = if (notResumed) error else null
    }

    private fun refreshContinuation() {
        val snapshot = runCatching { taskProgress()?.snapshot }.getOrNull()
        val session = taskSession
        val belongs =
            snapshot != null &&
                (session == null || snapshot.routeSession == null || snapshot.routeSession == session)
        val needUser = belongs && snapshot?.state == PhoneTaskState.WAITING_USER
        val note =
            if (needUser) {
                snapshot?.events
                    ?.lastOrNull { it.kind == PhoneProgressKind.MODEL_NEED_USER }
                    ?.text
                    ?.take(PhoneLimits.MAX_NODE_LABEL_CHARS)
            } else {
                null
            }
        val failure =
            if (continuationDelivery == PhoneContinueDelivery.NotResumed) {
                continuationFailure ?: PhoneContinueFailure.Other
            } else {
                null
            }
        val next =
            PhoneContinuation(
                needUser = needUser,
                agentNote = note,
                delivery = continuationDelivery,
                failure = failure,
            )
        mutateFacts { if (it.continuation == next) it else it.copy(continuation = next) }
    }

    private fun loadTargets() {
        if (!facts.value.available) return
        launchableCatalogRequested = true
        scope.launch {
            val loaded =
                withContext(Dispatchers.IO) { runCatching { catalog.loadAll() }.getOrNull() }
            if (loaded == null) {
                // Let a later projection retry instead of claiming an empty catalogue forever.
                launchableCatalogRequested = false
                return@launch
            }
            publishLaunchableApps(loaded)
            mutateFacts { it.copy(targets = loaded.targets) }
        }
    }

    /**
     * The all-apps `apps` listing: the launchable catalogue filtered by the all-apps name policy,
     * deduplicated by package, sanitized and bounded. [LauncherCatalog.truncated] stays honest when
     * the device reported more apps than the bound. It is a picker, not authority.
     */
    private fun publishLaunchableApps(loaded: LauncherCatalog) {
        val allowed =
            loaded.targets
                .filter { allAppsPolicy.refusal(it.packageName, allApps = true) == null }
                .distinctBy { it.packageName }
        val bounded = allowed.take(PhoneTargetCatalog.MAX_TARGETS)
        launchableAppsCache =
            bounded.map { target ->
                PhoneStandingApp(
                    label = safeAppLabel(target.label, target.packageName),
                    packageName = target.packageName,
                )
            }
        launchableAppsTruncated = loaded.truncated || allowed.size > bounded.size
        publishStandingView()
    }

    // --- bounded local discovery ------------------------------------------------------------

    /**
     * Fixed discovery update from [PhoneDiscoveryService]. It only reacts for the exact explicit
     * pairing generation (or its completed connect phase); an old service generation is ignored.
     */
    internal fun onDiscoverySnapshot(snapshot: PhoneDiscoverySnapshot) {
        if (!facts.value.enabled) {
            stopPairingDiscovery()
            return
        }
        val completed = pairingCompletedGeneration
        val active = pairingDiscoveryGeneration
        if (snapshot.generation != active && snapshot.generation != completed) return

        connectDiscoveryStatus = snapshot.connect
        if (snapshot.connect is PhoneDiscoveryStatus.Ready) {
            val endpoint = (snapshot.connect as PhoneDiscoveryStatus.Ready).endpoint
            if (connectDiscoveredEndpoint?.identity != endpoint.identity) {
                connectDiscoveredEndpoint = endpoint
            }
        } else {
            connectDiscoveredEndpoint = null
        }
        mutateFacts { it.copy(discoveryConnect = snapshot.connect) }

        if (completed == snapshot.generation) {
            // Pairing already succeeded: only the connect endpoint matters now.
            tryAutoConnectAfterPairing()
            return
        }
        handlePairingDiscoveryStatus(snapshot.generation, snapshot.pairing)
    }

    private fun handlePairingDiscoveryStatus(
        generation: Long,
        status: PhoneDiscoveryStatus,
    ) {
        if (generation != pairingDiscoveryGeneration) return
        val draft = pairing.current() ?: return
        if (draft.generation != generation) return
        when (status) {
            is PhoneDiscoveryStatus.Ready -> {
                val endpoint = status.endpoint
                if (pairingDiscoveredEndpoint?.identity != endpoint.identity) {
                    // A replaced advertisement must close the old code gate first; otherwise the
                    // already-configured port would silently keep accepting the old code notification.
                    if (pairingDiscoveredEndpoint != null) invalidatePairingEndpoint(generation)
                    acceptPairingEndpoint(generation, endpoint)
                }
            }
            is PhoneDiscoveryStatus.Ambiguous -> {
                invalidatePairingEndpoint(generation)
                mutateFacts { it.copy(discoveryPairing = status) }
            }
            PhoneDiscoveryStatus.TimedOut -> failPairingDiscovery(PhonePairingOutcome.TIMEOUT)
            PhoneDiscoveryStatus.Unavailable -> failPairingDiscovery(PhonePairingOutcome.UNAVAILABLE)
            PhoneDiscoveryStatus.Idle, PhoneDiscoveryStatus.Searching -> {
                invalidatePairingEndpoint(generation)
                mutateFacts { it.copy(discoveryPairing = status) }
            }
        }
    }

    private fun acceptPairingEndpoint(
        generation: Long,
        endpoint: PhoneDiscoveredEndpoint,
    ) {
        when (pairing.acceptEndpoint(generation, endpoint.host, endpoint.port)) {
            is PhonePairingSession.EndpointResult.Accepted -> {
                pairingDiscoveredEndpoint = endpoint
                mutateFacts {
                    it.copy(
                        pairingNotice = PhonePairingNotice.Waiting,
                        problem = null,
                        hints = it.hints.copy(pairingPort = endpoint.port, host = endpoint.host),
                        discoveryPairing = PhoneDiscoveryStatus.Ready(endpoint),
                    )
                }
                // configurePairing takes the driver lock pair() also uses; never on the main thread.
                scope.launch {
                    val configured =
                        withContext(Dispatchers.IO) {
                            runCatching { driver.configurePairing(endpoint.host, endpoint.port) }
                        }
                    // Cancel, timeout, a lost endpoint or a newer attempt makes this stale.
                    if (!facts.value.enabled) return@launch
                    val current = pairing.current()
                    if (current?.generation != generation ||
                        current.stage != PhonePairingSession.Stage.AWAITING_CODE ||
                        pairingDiscoveredEndpoint?.identity != endpoint.identity
                    ) {
                        return@launch
                    }
                    if (configured.isFailure) {
                        failPairingDiscovery(PhonePairingOutcome.POLICY_REFUSED)
                        return@launch
                    }
                    notifications.notifyPairing(
                        PhonePairNotifications.PAIRING_ID,
                        notifications.pairingCode(generation),
                    )
                    mutateFacts {
                        it.copy(
                            pairingNotice = PhonePairingNotice.Waiting,
                            hints = it.hints.copy(pairingPort = endpoint.port, host = endpoint.host),
                            problem = null,
                            discoveryPairing = PhoneDiscoveryStatus.Ready(endpoint),
                        )
                    }
                }
            }
            PhonePairingSession.EndpointResult.InvalidPort,
            PhonePairingSession.EndpointResult.Expired,
            PhonePairingSession.EndpointResult.NotSearching,
            PhonePairingSession.EndpointResult.Stale,
            -> Unit
        }
    }

    /**
     * The endpoint or its advertisement disappeared. The code gate closes with it: a stale code reply
     * can no longer be accepted, and the person can wait for the system dialog to re-advertise. While a
     * bounded pair exchange is already running this is only discovery bookkeeping, not a stop.
     */
    private fun invalidatePairingEndpoint(generation: Long) {
        if (pairingDiscoveredEndpoint == null) return
        pairingDiscoveredEndpoint = null
        val reopened = pairing.endpointLost(generation)
        if (!reopened) return
        notifications.notifyPairing(
            PhonePairNotifications.PAIRING_ID,
            notifications.pairingSearching(generation),
        )
        mutateFacts {
            it.copy(
                pairingNotice = PhonePairingNotice.Waiting,
                hints = it.hints.copy(pairingPort = null),
                discoveryPairing = PhoneDiscoveryStatus.Searching,
            )
        }
    }

    /** Fixed unavailable/timed-out state. No automatic port prompt; the existing manual path remains. */
    private fun failPairingDiscovery(outcome: PhonePairingOutcome) {
        invalidatePairing()
        pairing.cancel()
        pairingDiscoveryGeneration = null
        pairingCompletedGeneration = null
        pairingDiscoveredEndpoint = null
        connectDiscoveredEndpoint = null
        autoConnect.disarm()
        stopPairingDiscovery()
        notifications.notifyPairing(
            PhonePairNotifications.PAIRING_ID,
            notifications.pairingFailed(outcome),
        )
        val status =
            if (outcome == PhonePairingOutcome.TIMEOUT) {
                PhoneDiscoveryStatus.TimedOut
            } else {
                PhoneDiscoveryStatus.Unavailable
            }
        connectDiscoveryStatus = PhoneDiscoveryStatus.Idle
        mutateFacts {
            it.copy(
                pending = null,
                paired = false,
                problem = problemFor(outcome),
                pairingNotice = PhonePairingNotice.Idle,
                hints = it.hints.copy(pairingPort = null, connectPort = null),
                discoveryPairing = status,
                discoveryConnect = PhoneDiscoveryStatus.Idle,
                autoConnect = PhoneAutoConnect.Idle,
            )
        }
    }

    internal fun onDiscoveryUnavailable(generation: Long) {
        if (generation != pairingDiscoveryGeneration) return
        failPairingDiscovery(PhonePairingOutcome.UNAVAILABLE)
    }

    /**
     * The foreground discovery ended. An intentional stop clears the generation first and is ignored.
     * An unexpected stop closes the automatic path; it never silently keeps waiting and never asks for
     * a typed port.
     */
    internal fun onDiscoveryServiceStopped(generation: Long) {
        if (generation == 0L) return
        if (pairingCompletedGeneration == generation) {
            if (autoConnect.isAttempted(generation)) return
            // Pairing succeeded but the connect search ended without an endpoint: no loop, manual path.
            pairingCompletedGeneration = null
            pairingDiscoveryGeneration = null
            connectDiscoveredEndpoint = null
            autoConnect.disarm()
            stopPairingDiscovery()
            mutateFacts {
                it.copy(
                    pending = null,
                    problem = PhoneDeviceProblem.Other,
                    discoveryConnect = PhoneDiscoveryStatus.Unavailable,
                    autoConnect = PhoneAutoConnect.Failed,
                )
            }
            return
        }
        if (generation != pairingDiscoveryGeneration) return
        if (pairing.current()?.stage == PhonePairingSession.Stage.PAIRING) return
        failPairingDiscovery(PhonePairingOutcome.UNAVAILABLE)
    }

    private fun stopPairingDiscovery() {
        PhoneDiscoveryService.stop(context)
    }

    // --- explicit connect discovery ---------------------------------------------------------

    /**
     * "Connect to this phone" after pairing: one short in-process search for the single current
     * TLS-connect endpoint. It never scans ports and never loops; ambiguity waits for the bound.
     * [silent] is only for the launch reconnect: a search that finds nothing then leaves no error
     * behind, so the page keeps its own connect action.
     */
    private fun startConnectSearch(silent: Boolean = false) {
        if (!facts.value.available || !facts.value.enabled) return
        if (connectSearchActive) return
        val generation = ++connectSearchGeneration
        connectSearchConsumed = false
        connectSearchSilent = silent
        val created =
            PhonePortDiscovery(context) { _, connect ->
                onConnectSearchUpdate(generation, connect)
            }
        if (!created.start(setOf(PhoneDiscoveryKind.CONNECT), CONNECT_SEARCH_TIMEOUT_MS)) {
            created.stop()
            connectSearchSilent = false
            connectDiscoveryStatus = PhoneDiscoveryStatus.Unavailable
            if (silent) {
                mutateFacts {
                    it.copy(
                        pending = null,
                        problem = null,
                        discoveryConnect = PhoneDiscoveryStatus.Idle,
                    )
                }
                return
            }
            mutateFacts {
                it.copy(
                    pending = null,
                    problem = PhoneDeviceProblem.Other,
                    discoveryConnect = PhoneDiscoveryStatus.Unavailable,
                )
            }
            return
        }
        connectSearch = created
        connectSearchActive = true
        connectDiscoveryStatus = PhoneDiscoveryStatus.Searching
        mutateFacts {
            it.copy(
                pending = PhonePending.Connecting,
                problem = null,
                hints = it.hints.copy(connectPort = null),
                discoveryConnect = PhoneDiscoveryStatus.Searching,
                autoConnect = PhoneAutoConnect.Idle,
            )
        }
    }

    private fun onConnectSearchUpdate(
        generation: Long,
        status: PhoneDiscoveryStatus,
    ) {
        if (!connectSearchActive || generation != connectSearchGeneration) return
        if (!facts.value.enabled) {
            cancelConnectSearch(clearPending = true)
            return
        }
        connectDiscoveryStatus = status
        mutateFacts { it.copy(discoveryConnect = status) }
        when (status) {
            is PhoneDiscoveryStatus.Ready -> {
                if (connectSearchConsumed) return
                connectSearchConsumed = true
                connectToDiscoveredEndpoint(endpoint = status.endpoint, autoPairGeneration = null)
            }
            PhoneDiscoveryStatus.TimedOut,
            PhoneDiscoveryStatus.Unavailable,
            -> {
                val silent = connectSearchSilent
                cancelConnectSearch(clearPending = false)
                if (silent) {
                    // Nothing answered: stay disconnected and say nothing; the page's own connect
                    // action is still there.
                    mutateFacts {
                        it.copy(
                            pending = null,
                            discoveryConnect = PhoneDiscoveryStatus.Idle,
                        )
                    }
                } else {
                    mutateFacts {
                        it.copy(
                            pending = null,
                            problem = PhoneDeviceProblem.Other,
                            discoveryConnect = status,
                        )
                    }
                }
            }
            PhoneDiscoveryStatus.Idle,
            PhoneDiscoveryStatus.Searching,
            is PhoneDiscoveryStatus.Ambiguous,
            -> Unit
        }
    }

    private fun cancelConnectSearch(clearPending: Boolean) {
        val wasActive = connectSearchActive
        connectSearch?.stop()
        connectSearch = null
        connectSearchActive = false
        connectSearchConsumed = false
        connectSearchSilent = false
        connectSearchGeneration += 1
        if (clearPending && wasActive && facts.value.pending == PhonePending.Connecting) {
            mutateFacts { it.copy(pending = null) }
        }
    }

    // --- one automatic connect after explicit pairing --------------------------------------

    private fun tryAutoConnectAfterPairing() {
        val generation = pairingCompletedGeneration ?: return
        val endpoint = connectDiscoveredEndpoint
        if (autoConnect.shouldAttempt(generation, endpoint, facts.value.enabled)) {
            autoConnect.markAttempted(generation)
            connectToDiscoveredEndpoint(endpoint = endpoint!!, autoPairGeneration = generation)
            return
        }
        if (autoConnect.isAttempted(generation)) return
        val terminal = connectDiscoveryStatus
        if (terminal == PhoneDiscoveryStatus.TimedOut || terminal == PhoneDiscoveryStatus.Unavailable) {
            finishPairingDiscoveryWithoutConnect(generation, terminal)
        }
    }

    private fun finishPairingDiscoveryWithoutConnect(
        generation: Long,
        status: PhoneDiscoveryStatus,
    ) {
        if (pairingCompletedGeneration != generation) return
        pairingCompletedGeneration = null
        pairingDiscoveryGeneration = null
        connectDiscoveredEndpoint = null
        autoConnect.disarm()
        stopPairingDiscovery()
        // Pairing itself succeeded. Do not post a "pairing failed" notification for a connect search
        // that found no endpoint; the screen shows the fixed TimedOut/Unavailable connect state.
        mutateFacts {
            it.copy(
                pending = null,
                paired = true,
                problem = PhoneDeviceProblem.Other,
                hints = it.hints.copy(connectPort = null),
                discoveryConnect = status,
                autoConnect = PhoneAutoConnect.Failed,
            )
        }
    }

    /** No live foreground service can start the connect search: keep the pairing receipt, no loop. */
    private fun abandonAutoConnect(
        generation: Long,
        status: PhoneDiscoveryStatus,
    ) {
        if (pairingCompletedGeneration != generation) return
        pairingCompletedGeneration = null
        pairingDiscoveryGeneration = null
        connectDiscoveredEndpoint = null
        autoConnect.disarm()
        stopPairingDiscovery()
        mutateFacts {
            it.copy(
                pending = null,
                paired = true,
                discoveryConnect = status,
                autoConnect = PhoneAutoConnect.Failed,
            )
        }
    }

    /**
     * Exactly one configure+connect for a discovered endpoint. [autoPairGeneration] is non-null only
     * for the explicit pairing the person started; opening the screen never arms it. A failure is
     * reported once and is never retried, and nothing here grants, clears a standing block or resumes
     * a task.
     */
    private fun connectToDiscoveredEndpoint(
        endpoint: PhoneDiscoveredEndpoint,
        autoPairGeneration: Long?,
    ) {
        val explicitGeneration =
            if (autoPairGeneration == null) connectSearchGeneration else null
        // A launch reconnect found something but the one connect failed: stay quiet, keep the
        // manual "Find and connect" path.
        val silent = autoPairGeneration == null && connectSearchSilent
        fun stillCurrent(): Boolean =
            if (autoPairGeneration == null) {
                facts.value.enabled &&
                    connectSearchActive &&
                    explicitGeneration == connectSearchGeneration
            } else {
                facts.value.enabled && autoConnect.isArmedFor(autoPairGeneration)
            }
        mutateFacts {
            it.copy(
                pending = PhonePending.Connecting,
                problem = null,
                hints = it.hints.copy(host = endpoint.host, connectPort = endpoint.port),
                discoveryConnect = PhoneDiscoveryStatus.Ready(endpoint),
                autoConnect =
                    if (autoPairGeneration == null) {
                        PhoneAutoConnect.Idle
                    } else {
                        PhoneAutoConnect.Running
                    },
            )
        }
        scope.launch {
            val paired =
                withContext(Dispatchers.IO) {
                    runCatching { driver.hasPairingReceipt(DEFAULT_HOST) }.getOrDefault(false)
                }
            if (!stillCurrent()) return@launch
            if (!paired) {
                mutateFacts {
                    it.copy(
                        pending = null,
                        paired = false,
                        problem = if (silent) null else PhoneDeviceProblem.NotAuthorized,
                        discoveryConnect = PhoneDiscoveryStatus.Idle,
                        autoConnect =
                            if (autoPairGeneration == null) {
                                PhoneAutoConnect.Idle
                            } else {
                                PhoneAutoConnect.Failed
                            },
                    )
                }
                finishConnectAttempt(autoPairGeneration)
                return@launch
            }
            val configured =
                withContext(Dispatchers.IO) {
                    runCatching {
                        driver.prepareForConnect(PhoneLimits.DEFAULT_CALL_MS)
                        driver.configure(endpoint.host, endpoint.port)
                    }
                }
            if (!stillCurrent()) return@launch
            if (configured.isFailure) {
                mutateFacts {
                    it.copy(
                        pending = null,
                        problem = if (silent) null else PhoneDeviceProblem.Other,
                        discoveryConnect = PhoneDiscoveryStatus.Idle,
                        autoConnect =
                            if (autoPairGeneration == null) {
                                PhoneAutoConnect.Idle
                            } else {
                                PhoneAutoConnect.Failed
                            },
                    )
                }
                finishConnectAttempt(autoPairGeneration)
                return@launch
            }
            val result =
                runCatching { controller.connect(PhoneLimits.DEFAULT_CALL_MS) }
                    .getOrElse { PhoneConnectionResult.Failed(PhoneErrorCode.DRIVER_UNAVAILABLE) }
            if (!stillCurrent()) return@launch
            when (result) {
                is PhoneConnectionResult.Connected ->
                    mutateFacts {
                        it.copy(
                            pending = null,
                            paired = true,
                            problem = null,
                            hints = it.hints.copy(host = endpoint.host, connectPort = endpoint.port),
                            discoveryConnect = PhoneDiscoveryStatus.Ready(endpoint),
                            autoConnect = PhoneAutoConnect.Idle,
                        )
                    }
                is PhoneConnectionResult.Failed ->
                    mutateFacts {
                        it.copy(
                            pending = null,
                            problem = if (silent) null else problemFor(result.error),
                            discoveryConnect = PhoneDiscoveryStatus.Idle,
                            autoConnect =
                                if (autoPairGeneration == null) {
                                    PhoneAutoConnect.Idle
                                } else {
                                    PhoneAutoConnect.Failed
                                },
                        )
                    }
            }
            finishConnectAttempt(autoPairGeneration)
        }
    }

    private fun finishConnectAttempt(autoPairGeneration: Long?) {
        if (autoPairGeneration == null) {
            cancelConnectSearch(clearPending = false)
            return
        }
        // One attempt per explicit pairing. A later connect is a new person action.
        autoConnect.markAttempted(autoPairGeneration)
        pairingCompletedGeneration = null
        pairingDiscoveryGeneration = null
        connectDiscoveredEndpoint = null
        autoConnect.disarm()
        stopPairingDiscovery()
        connectDiscoveryStatus = PhoneDiscoveryStatus.Idle
        mutateFacts {
            it.copy(
                discoveryPairing = PhoneDiscoveryStatus.Idle,
                discoveryConnect = PhoneDiscoveryStatus.Idle,
            )
        }
    }

    // --- state plumbing --------------------------------------------------------------------

    private fun observeController() {
        observers +=
            scope.launch {
                var previous: PhoneState? = null
                controller.state.collect { core ->
                    deriveHalt(previous, core)
                    surfaceUnknownOutcome(core)
                    settleHalt(core)
                    // A different local runtime cannot continue the old task/session binding. A new
                    // request may bind the saved scope to the new runtime only after the retained
                    // task is ended/checked natively.
                    if (core.runtimeId != null &&
                        standingBinding.runtimeId != null &&
                        core.runtimeId != standingBinding.runtimeId
                    ) {
                        standingBinding = PhoneStandingBindingState()
                    }
                    // All-apps follow display: remember the last real observation package for this
                    // grant epoch. It survives the observation invalidation after a sent action and
                    // is cleared by any new grant/revoke (epoch change). Never authority.
                    if (core.grant?.epoch != previous?.grant?.epoch) lastObservedPackage = null
                    if (core.grant?.allApps == true) {
                        core.observation?.binding?.packageName?.let { lastObservedPackage = it }
                    }
                    val followedNow = if (core.grant?.allApps == true) lastObservedPackage else null
                    if (facts.value.followedPackage != followedNow) {
                        mutateFacts { it.copy(followedPackage = followedNow) }
                    }
                    previous = core
                    publish()
                }
            }
    }

    /**
     * An uncertain mutation is a native stop, not just a tool error: the controller already refuses
     * further mutations while blocked, and the screen must say so and offer the person the check/ack
     * path. It is never retried automatically and never reported as "nothing happened".
     */
    private fun surfaceUnknownOutcome(core: PhoneState) {
        if (core.blocked != PhoneBlockReason.UNKNOWN_OUTCOME) return
        if (facts.value.halt != null) return
        val remembered = lease ?: return
        halt(PhoneHaltReason.OutcomeUnknown, remembered, actionInFlight = true)
        // Directly, so the recorded reason stays OutcomeUnknown instead of becoming Paused.
        controller.pause()
        runCatching { serviceStart(core.epoch) }
    }

    private fun observeFacts() {
        observers += scope.launch { facts.collect { publish() } }
    }

    private fun observeProposals() {
        observers +=
            scope.launch {
                proposals.pending.collect { proposal ->
                    mutateFacts {
                        it.copy(
                            request =
                                proposal?.let {
                                    io.andchamber.app.ui.phone.PhoneAgentRequest(
                                        summary = it.summary,
                                        packageName = it.packageName,
                                    )
                                },
                            // The revision the next grant may consume is exactly the displayed one.
                            requestRevision = proposal?.revision,
                        )
                    }
                }
            }
    }

    private fun startTicker() {
        observers +=
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    // Probe first, then the one explicit restart gate, then the normal read-only
                    // status refresh. The gate is separate from the probe.
                    refreshAgentToolBlocker()
                    finishConfirmedRestartIfReady()
                    refreshStandingProjection()
                    refreshContinuation()
                    val core = controller.state.value
                    if (core.grant != null) controller.refresh()
                    if (core.grant == null) continue
                    // A lease is only usable while the stop entry can be reached from another app.
                    if (notifications.stopChannelState() != PhoneStopChannel.Verified) {
                        invalidateGrantAttempt()
                        halt(PhoneHaltReason.StopChannelLost)
                        controller.pause()
                        serviceStop()
                        mutateFacts { it.copy(stopChannel = notifications.stopChannelState()) }
                    }
                }
            }
    }

    /**
     * Keeps the reason the controller dropped a lease. The controller clears its grant (and the
     * unknown-outcome block) on revoke, so the coordinator records the halt itself, once.
     */
    private fun deriveHalt(previous: PhoneState?, core: PhoneState) {
        val remembered = lease ?: return
        if (previous?.grant == null || core.grant != null) return
        if (facts.value.halt != null) return
        val reason =
            when (core.lastEvent) {
                PhoneEvent.LOCKED -> PhoneHaltReason.ScreenLocked
                PhoneEvent.HOST_RETURNED -> PhoneHaltReason.HostReturned
                PhoneEvent.CONNECTION_LOST -> PhoneHaltReason.ConnectionLost
                PhoneEvent.LEASE_EXPIRED -> PhoneHaltReason.Expired
                PhoneEvent.RUNTIME_CHANGED -> PhoneHaltReason.RuntimeChanged
                else -> null
            } ?: return
        halt(reason, remembered, actionInFlight = previous?.inFlightMutation == true)
        // Exact-owner stop fact for the epoch that was really live and is now gone. The controller's
        // revoke is synchronous, but this identity still comes from `previous`, not from "no lease now".
        val droppedEpoch = previous?.grant?.epoch ?: return
        val identity = taskIdentity.current() ?: return
        val typed = reason.toProgressStopReason() ?: return
        publishTaskStop(identity.first, identity.second, droppedEpoch, typed, ended = false)
    }

    /** Latches the halt facts: a dispatched action never becomes "nothing happened". */
    private fun settleHalt(core: PhoneState) {
        val current = facts.value.halt ?: return
        if (current.confirmed) return
        if (core.pausing || core.inFlightMutation) return
        val factsNow =
            phoneHaltFacts(
                pausing = core.pausing,
                inFlightMutation = core.inFlightMutation,
                outcomeUnknown = core.blocked == PhoneBlockReason.UNKNOWN_OUTCOME,
                acknowledged = current.acknowledged,
            )
        halt(
            current.lease,
            current.reason,
            confirmed = true,
            lastAction = if (current.lastAction == PhoneLastAction.MayStillApply) {
                PhoneLastAction.MayStillApply
            } else {
                factsNow.lastAction
            },
            acknowledged = current.acknowledged,
        )
    }

    private fun halt(
        reason: PhoneHaltReason,
        remembered: PhoneLeaseFacts? = lease,
        actionInFlight: Boolean = controller.state.value.inFlightMutation,
    ) {
        if (remembered == null) return
        val core = controller.state.value
        val derived =
            phoneHaltFacts(
                pausing = core.pausing || actionInFlight,
                inFlightMutation = core.inFlightMutation || actionInFlight,
                outcomeUnknown = core.blocked == PhoneBlockReason.UNKNOWN_OUTCOME,
            )
        halt(
            remembered,
            reason,
            // A dispatched action can never become "nothing happened" after a revoke cleared the block,
            // and an earlier "may still apply" survives this halt too.
            confirmed = derived.confirmed && !actionInFlight,
            lastAction =
                latchedHaltLastAction(
                    existing = facts.value.halt?.lastAction,
                    actionInFlight = actionInFlight || core.inFlightMutation,
                    derived =
                        if (derived.lastAction == PhoneLastAction.MayStillApply) {
                            PhoneLastAction.MayStillApply
                        } else {
                            PhoneLastAction.NoneInFlight
                        },
                    acknowledged = false,
                ),
            acknowledged = false,
        )
    }

    private fun halt(
        remembered: PhoneLeaseFacts,
        reason: PhoneHaltReason,
        confirmed: Boolean,
        lastAction: PhoneLastAction,
        acknowledged: Boolean,
    ) {
        // A genuinely new stop may be continued once; a re-settle of the same halt must not erase
        // the one-shot delivery result.
        if (facts.value.halt == null) setContinuationDelivery(PhoneContinueDelivery.Idle)
        mutateFacts {
            it.copy(
                halt =
                    PhoneHaltSnapshot(
                        lease = remembered,
                        reason = reason,
                        confirmed = confirmed,
                        lastAction = lastAction,
                        acknowledged = acknowledged,
                    ),
            )
        }
        store.setTask(
            store.task()?.copy(
                needsCheck = true,
                haltReason = reason.name,
                acknowledged = acknowledged,
                agentReported = false,
            ),
        )
        refreshContinuation()
    }

    private fun closeLease(keepTask: Boolean) {
        lease = null
        if (!keepTask) {
            store.clearTask()
            taskIdentity.clear()
            retainedTaskEpoch = null
            taskNative.onTaskBinding(null)
        }
    }

    /**
     * Native-only stop receipt for exactly [taskId]/[runtimeId]/[epoch]. Only a revoke that already
     * completed synchronously reaches here; a late continuation for an old task cannot pass the
     * adapter's exact key/epoch check. It never claims that sent actions were rolled back.
     */
    private fun publishTaskStop(
        binding: PhoneOverlayTaskBinding?,
        reason: PhoneProgressReason,
        ended: Boolean,
    ) {
        if (binding == null) return
        publishTaskStop(binding.taskId, binding.runtimeId, binding.epoch, reason, ended)
    }

    private fun publishTaskStop(
        taskId: String,
        runtimeId: String,
        epoch: Long,
        reason: PhoneProgressReason,
        ended: Boolean,
    ) {
        if (controller.state.value.grant != null) return
        runCatching {
            taskNative.onNativeLeaseDropped(
                taskId = taskId,
                runtimeId = runtimeId,
                epoch = epoch,
                reason = reason,
                ended = ended,
                ack = PhoneStopAcknowledgement(newOperationsBlocked = true, lateReadsBlocked = true),
            )
        }
    }

    /**
     * Original stop reason -> the standing block the chat must keep waiting on. A model need_user is
     * its own reason, not a person takeover; unknown/connection/lock/runtime keep their safety reason.
     * This never widens authority.
     */
    private fun PhoneHaltReason.toStandingBlockReason(): PhoneStandingBlockReason =
        continuationStandingBlockReason(this)

    private fun PhoneHaltReason.toProgressStopReason(): PhoneProgressReason? =
        when (this) {
            PhoneHaltReason.ScreenLocked -> PhoneProgressReason.DEVICE_LOCKED
            PhoneHaltReason.HostReturned -> PhoneProgressReason.HOST_RETURNED
            PhoneHaltReason.ConnectionLost -> PhoneProgressReason.CONNECTION_LOST
            PhoneHaltReason.Expired -> PhoneProgressReason.LEASE_EXPIRED
            PhoneHaltReason.RuntimeChanged -> PhoneProgressReason.RUNTIME_CHANGED
            PhoneHaltReason.OutcomeUnknown -> PhoneProgressReason.OUTCOME_UNKNOWN
            PhoneHaltReason.NeedUser -> PhoneProgressReason.NEED_USER
            PhoneHaltReason.Paused,
            PhoneHaltReason.TakenOver,
            PhoneHaltReason.TargetLeft,
            PhoneHaltReason.StopChannelLost,
            PhoneHaltReason.ScreenUncertain,
            -> null
        }

    private fun guardBusy(): Boolean {
        if (facts.value.pending == null) return false
        mutateFacts { it.copy(busy = PhoneBusy.RequestIgnored) }
        return true
    }

    private fun publish() {
        mutableState.value = phoneScreenState(controller.state.value, facts.value, controller.nowMs())
    }

    private fun mutateFacts(transform: (PhoneScreenFacts) -> PhoneScreenFacts) {
        while (true) {
            val current = facts.value
            val next = transform(current)
            if (next == current) return
            if (facts.compareAndSet(current, next)) {
                publish()
                return
            }
        }
    }

    private fun problemFor(outcome: PhonePairingOutcome): PhoneDeviceProblem? =
        when (outcome) {
            PhonePairingOutcome.PAIRED -> null
            PhonePairingOutcome.CODE_REJECTED -> PhoneDeviceProblem.PairingCodeRejected
            PhonePairingOutcome.PORT_UNREACHABLE -> PhoneDeviceProblem.PairingPortUnreachable
            PhonePairingOutcome.POLICY_REFUSED -> PhoneDeviceProblem.Other
            PhonePairingOutcome.CANCELLED -> PhoneDeviceProblem.Other
            PhonePairingOutcome.TIMEOUT -> PhoneDeviceProblem.Other
            PhonePairingOutcome.UNAVAILABLE -> PhoneDeviceProblem.Other
        }

    private fun problemFor(error: PhoneErrorCode): PhoneDeviceProblem =
        when (error) {
            PhoneErrorCode.NOT_CONNECTED -> PhoneDeviceProblem.ConnectRefused
            PhoneErrorCode.DEADLINE -> PhoneDeviceProblem.ConnectRefused
            PhoneErrorCode.TARGET_FORBIDDEN -> PhoneDeviceProblem.Other
            PhoneErrorCode.DISABLED -> PhoneDeviceProblem.Other
            PhoneErrorCode.NOT_GRANTED, PhoneErrorCode.LEASE_EXPIRED, PhoneErrorCode.EPOCH_CHANGED,
            PhoneErrorCode.PAUSED, PhoneErrorCode.CANCELLED, PhoneErrorCode.OUTCOME_UNKNOWN,
            PhoneErrorCode.INVALID_REQUEST, PhoneErrorCode.CAPACITY, PhoneErrorCode.DUPLICATE,
            PhoneErrorCode.BUSY, PhoneErrorCode.UNSUPPORTED_ACTION, PhoneErrorCode.UNICODE_UNSUPPORTED,
            PhoneErrorCode.ACTION_NOT_GRANTED, PhoneErrorCode.TARGET_MISMATCH,
            PhoneErrorCode.WINDOW_CHANGED, PhoneErrorCode.GEOMETRY_CHANGED,
            PhoneErrorCode.STALE_OBSERVATION, PhoneErrorCode.OBSERVATION_REQUIRED,
            PhoneErrorCode.OUT_OF_BOUNDS, PhoneErrorCode.OVERLAY_OBSTRUCTED, PhoneErrorCode.DRIVER_UNAVAILABLE,
            // Neither is a connection-result error; this only keeps the exhaustive mapping.
            PhoneErrorCode.HIERARCHY_NOT_IDLE, PhoneErrorCode.READ_TIMEOUT,
            -> PhoneDeviceProblem.Other
        }

    private fun pairingOutcome(failure: AdbPairingFailure): PhonePairingOutcome =
        when (failure) {
            AdbPairingFailure.WRONG_CODE -> PhonePairingOutcome.CODE_REJECTED
            AdbPairingFailure.UNREACHABLE -> PhonePairingOutcome.PORT_UNREACHABLE
            AdbPairingFailure.POLICY, AdbPairingFailure.INVALID_INPUT -> PhonePairingOutcome.POLICY_REFUSED
            AdbPairingFailure.DEADLINE -> PhonePairingOutcome.TIMEOUT
            AdbPairingFailure.CANCELLED -> PhonePairingOutcome.CANCELLED
            AdbPairingFailure.TLS_UNAVAILABLE,
            AdbPairingFailure.TLS_HANDSHAKE,
            AdbPairingFailure.PROTOCOL,
            -> PhonePairingOutcome.UNAVAILABLE
        }

    companion object {
        const val DEFAULT_HOST = "127.0.0.1"
        const val NATIVE_SESSION = "native-ui"

        /** Technical token for the details pane when the foreground stop entry could not start. */
        const val GRANT_TOKEN_SERVICE_UNAVAILABLE = "service_unavailable"

        /** Long enough for one handshake, within the pairing entry point's own bound. */
        const val PAIRING_DEADLINE_MS = 60_000L

        /** One explicit "connect to this phone" search, short and in-process. */
        const val CONNECT_SEARCH_TIMEOUT_MS = 45_000L

        /** Bounded wait for this launch's phone tool before a background reconnect. */
        private const val AUTO_CONNECT_TOOL_WAIT_MS = 120_000L
        private const val AUTO_CONNECT_TOOL_POLL_MS = 1_000L

        /** Stop reasons an agent-requested handoff may carry into the task store. */
        private val TASK_STOP_REASONS: Set<PhoneProgressReason> =
            setOf(
                PhoneProgressReason.USER_REQUESTED,
                PhoneProgressReason.TAKEOVER,
                PhoneProgressReason.LEASE_EXPIRED,
                PhoneProgressReason.HOST_RETURNED,
                PhoneProgressReason.CONNECTION_LOST,
                PhoneProgressReason.DEVICE_LOCKED,
                PhoneProgressReason.RUNTIME_CHANGED,
                PhoneProgressReason.OUTCOME_UNKNOWN,
            )

        private const val TICK_MS = 1_000L

        /** A restart must either show a non-ready probe or finish within this bound. */
        private const val RESTART_PROBE_TIMEOUT_MS = 60_000L

        internal fun launchDefaultApp(context: Context, packageName: String): Boolean =
            runCatching {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrDefault(false)

        /**
         * Read-only launcher check for all-apps mode: the package must have a launch intent right
         * now. It proves nothing about authority and launches nothing.
         */
        internal fun hasLauncherActivity(context: Context, packageName: String): Boolean =
            runCatching { context.packageManager.getLaunchIntentForPackage(packageName) != null }
                .getOrDefault(false)

        /** One bounded, control-character-free label; the package is the fallback. */
        internal fun safeAppLabel(label: String, packageName: String): String {
            val bounded = label.trim().take(PhoneLimits.MAX_NODE_LABEL_CHARS)
            return if (bounded.isEmpty() || bounded.any(Char::isISOControl)) packageName else bounded
        }

        internal fun openDevelopmentSettings(context: Context) {
            val opened =
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                    true
                }.getOrDefault(false)
            if (!opened) {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        internal fun openAppNotificationSettings(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

/** Notification ids used by the pairing flow. The control id lives with the notifications class. */
internal object PhonePairNotifications {
    const val PAIRING_ID = PhoneControlNotifications.PAIRING_NOTIFICATION_ID
}

/**
 * Bounded identity of the native task shown in the overlay, for the progress adapter only.
 * It carries the confirmed summary the person already saw; no page text or device content.
 * [routeSession] is null for a manual native task until the tool server validated the first claim
 * and told the task adapter about it; it is never a placeholder chat id.
 */
data class PhoneOverlayTaskBinding(
    val taskId: String,
    val runtimeId: String,
    val epoch: Long,
    val routeSession: String?,
    val summary: String,
    val packageName: String,
)
